package com.misaka.voicelog.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.misaka.voicelog.R
import com.misaka.voicelog.asr.AsrEngine
import com.misaka.voicelog.asr.SenseVoiceEngine
import com.misaka.voicelog.asr.SpeakerEngine
import com.misaka.voicelog.audio.AacEncoder
import com.misaka.voicelog.audio.AudioStore
import com.misaka.voicelog.audio.MicRoute
import com.misaka.voicelog.audio.PitchDetector
import com.misaka.voicelog.audio.VadSegmenter
import com.misaka.voicelog.core.AppPrefs
import com.misaka.voicelog.core.ModelPaths
import com.misaka.voicelog.core.PersonStore
import com.misaka.voicelog.core.SyncManager
import com.misaka.voicelog.core.VoiceprintStore
import com.misaka.voicelog.data.InterruptEntity
import com.misaka.voicelog.data.MIGRATION_1_2
import com.misaka.voicelog.data.MIGRATION_2_3
import com.misaka.voicelog.data.MIGRATION_3_4
import com.misaka.voicelog.data.MIGRATION_4_5
import com.misaka.voicelog.data.MIGRATION_5_6
import com.misaka.voicelog.data.MIGRATION_6_7
import com.misaka.voicelog.data.MIGRATION_7_8
import com.misaka.voicelog.data.SegmentEntity
import com.misaka.voicelog.data.SessionEntity
import com.misaka.voicelog.data.TranscriptEntity
import com.misaka.voicelog.data.VoiceLogDatabase
import com.misaka.voicelog.ui.MainActivity
import kotlinx.coroutines.runBlocking
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 常驻采集服务。
 *
 * 数据流：
 *   AudioRecord(16k/mono/PCM16)
 *     → Silero VAD 切句（只保留人声段）
 *     → 阻塞队列
 *     → SenseVoice 单线程转写
 *     → Room 落库 + SessionState 推给界面
 *
 * 「让麦」策略（测试阶段的核心要求：遇到微信电话/来电必须让出来）：
 *   1. 通话模式检测：AudioManager.mode 为 IN_CALL / IN_COMMUNICATION 时主动关闭 AudioRecord
 *   2. 静音抢占检测：麦克风被其他应用独占时，系统返回全零 PCM。
 *      连续 [SILENT_WINDOWS_FOR_INTERRUPT] 个窗口（约 2 秒）全零即判定被占用，释放并退避重试
 *   3. 录音配置回调（API 29+）：其他应用开始/结束录音时落一条中断日志，便于事后归因
 *   4. 读取异常：带退避重建 AudioRecord，不崩溃、不静默失败
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"

        const val ACTION_START = "com.misaka.voicelog.action.START"
        const val ACTION_STOP = "com.misaka.voicelog.action.STOP"

        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1001

        private const val SAMPLE_RATE = VadSegmenter.SAMPLE_RATE
        private const val READ_WINDOW = VadSegmenter.WINDOW_SIZE

        /** 连续多少个全零窗口判定为"麦克风被占用"：60 × 32ms ≈ 2 秒 */
        private const val SILENT_WINDOWS_FOR_INTERRUPT = 60

        /**
         * 蓝牙链路**尚未确认**时的全零容忍窗口：380 × 32ms ≈ 12 秒。
         *
         * HFP/SCO 建链很慢：Pixel 约 1~2 秒，小米 Redmi 实测 5~8 秒。这段期间
         * PCM 可能一直是零。用默认的 2 秒判据会在链路还没好之前就判"没数据"，
         * 接着释放重建、重新等 2 秒、再判死 —— 形成死循环，永远用不上蓝牙麦。
         * 一旦 OnCommunicationDeviceChangedListener 确认路由生效，就回到 2 秒，
         * 保证「微信抢麦」时的让麦延迟不被拖长。
         */
        private const val BT_SILENT_WINDOWS_FOR_INTERRUPT = 380

        /** 被抢占后重试间隔 */
        private const val RETRY_DELAY_MS = 1_500L

        /**
         * 连续多少次「判全零 → 重建录音器」仍然没数据，就认定问题不在 AudioRecord 实例上。
         *
         * 3 次 ≈ 6 秒。短于此的占用（微信语音说一句就走、系统瞬时抢占）按正常重试处理，
         * 超过就降频 —— 2026-11-15 实测被占用 153 分钟，按 1.5 秒重建了 2571 次，
         * 一次都没成功，纯粹在烧电和刷日志。
         */
        private const val SILENT_REBUILD_GIVE_UP = 3

        /**
         * 长期停摆后的探测间隔。
         *
         * 30 秒：最坏多丢 30 秒音频，换来电量和日志量的数量级下降。
         * 不选更长的理由：占用结束后用户还在说话，探测太慢会多丢几分钟。
         */
        private const val STALL_PROBE_DELAY_MS = 30_000L

        /** 非我 = 他人。用户可在记录页批量改成真实姓名 */
        const val OTHER_LABEL = "他人"

        fun start(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }

    private val running = AtomicBoolean(false)
    private val asrQueue = LinkedBlockingQueue<VadSegmenter.Segment>(64)

    /** 只录不转模式下的编码队列。比 asrQueue 宽松：编码比识别快得多，不容易积压 */
    private val encodeQueue = LinkedBlockingQueue<VadSegmenter.Segment>(256)

    private var captureThread: Thread? = null
    private var asrThread: Thread? = null
    private var encodeThread: Thread? = null

    /**
     * 本轮是否「只录不转」（v3 新增，对应设置里的「实时转写」开关）。
     *
     * 关闭时：只跑 VAD + AAC 编码落盘，**不加载 ASR / 声纹模型**（省 300MB+ 内存与持续算力），
     * 用户点「转写」时再由 [com.misaka.voicelog.audio.TranscribeWorker] 批量处理。
     * 打开时：走 v2 的老路径，边说边出字。
     */
    private var recordOnly = false

    /** 本轮采集起点（墙钟），用于把段内相对时间换算成绝对时间 */
    private var captureStartWall = 0L

    private var vad: VadSegmenter? = null
    private var engine: AsrEngine? = null
    private var speaker: SpeakerEngine? = null
    private lateinit var audioManager: AudioManager
    private lateinit var db: VoiceLogDatabase

    /** 本轮会话 ID，0 表示写库失败（此时仅界面可见，不进历史） */
    private var sessionId: Long = 0L

    /** 声纹判定是否启用：需要「模型可用」且「已注册模板」两个条件同时成立 */
    private var spkEnabled = false

    /** 判定阈值，会话开始时读一次，避免每句都读 SharedPreferences */
    private var spkThreshold = 0.62f

    /**
     * 「人」的完整列表（见 [PersonStore]）。名字的唯一来源。
     *
     * 声纹档案里的人名只是建档快照，显示时必须经 personId 查这里 ——
     * 否则用户改名后，列表里还是旧名字。
     */
    private var persons: List<PersonStore.Person> = emptyList()

    /** 哪几个人是「我」。判定 AUTO_ME 用，不再比名字字符串（改名不该改变统计口径） */
    private var selfPersonIds: Set<String> = emptySet()

    /**
     * F0 兜底时唯一男声/女声的人名。null = 该性别不是恰好 1 个人，留未判。
     *
     * F0 只能证明「是哪个性别」，证不出「是哪个具体的人」。该性别下站着 2 个人时
     * 物理上就分不开（2026-11-19 实测女声侧错 42.6%，根因就是那里有两个候选）。
     * 所以「恰好 1 人才落名字」不是保守，是唯一诚实的选择。
     */
    private var maleSoleName: String? = null
    private var femaleSoleName: String? = null

    /** 本轮编码码率。同样在开录时读一次 —— 已落盘的文件不会事后重编码 */
    private var aacBitRate = AacEncoder.BIT_RATE

    /** 全部声纹档案常驻内存，避免每句都读磁盘 */
    private var spkProfiles: List<VoiceprintStore.Profile> = emptyList()

    /**
     * 基频判男女的四个配置。开录时读一次，整轮不变。
     *
     * 为什么不在每句判定时读：一轮里如果中途改了阈值，前半段和后半段就用了不同标准，
     * 事后看到「明明同一句话，前面标了后面没标」就没法解释。配置在设置页写明「下一轮生效」。
     */
    private var f0Enabled = true
    private var f0MaleMax = 160f
    private var f0FemaleMin = 200f

    /**
     * 本轮实际请求的输入设备 id（0 = 跟随系统默认）。开录时解析一次。
     * 复核时要用它，不能在复核那一刻再读设置 —— 中途改设置会得到误导性的比对结果。
     */
    private var requestedDeviceId = 0

    /**
     * 本轮目标是否是蓝牙设备。它决定三件事：用哪个 AudioSource、开录前是否建立 SCO、
     * 释放时是否需要退出通信模式。三者必须一致，漏一个就会出现
     * 「界面上是蓝牙麦、数据却来自内置麦」这种静默错误。
     */
    private var targetIsBluetooth = false

    /**
     * 读一次「声纹 + 人」的全部配置。开录时调，一轮内不再变。
     *
     * 为什么要重读两次档案：`ensureMigrated` 会把 personId 写回档案文件头，
     * 但内存里那份 list 还是迁移前的对象（personId = null），直接拿去 match
     * 会得到「判出了人却挂不上名字」的结果。重读一次的成本是一次目录扫描（≤8 个文件）。
     */
    private fun loadSpeakerConfig() {
        spkThreshold = VoiceprintStore.threshold(this)
        PersonStore.ensureMigrated(this, VoiceprintStore.profiles(this))
        spkProfiles = VoiceprintStore.profiles(this)
        persons = PersonStore.list(this)
        selfPersonIds = persons.filter { it.isSelf }.map { it.id }.toSet()
        maleSoleName = persons.singleOrNull { it.gender == PersonStore.GENDER_MALE }?.name
        femaleSoleName = persons.singleOrNull { it.gender == PersonStore.GENDER_FEMALE }?.name
    }

    /**
     * 本轮是否因为蓝牙链路建不起来而回落到了手机麦。
     *
     * 这个标志不能省：回落之后 routedDevice **仍然会报蓝牙设备**（因为音频策略的
     * 「默认输入」就是它），只看 routedDevice 就又会把蓝牙写成实际来源 ——
     * 跟上一版骗人的错误一模一样。
     */
    private var fellBackToBuiltin = false

    /**
     * 本轮是否由**本服务自己**持有了 `MODE_IN_COMMUNICATION`。
     *
     * 必须单独记：主循环用 `mode == MODE_IN_COMMUNICATION` 判定"有微信电话、让出麦克风"。
     * 蓝牙麦克风恰恰要求我们自己进入这个模式，不排除的话就是自杀式判定 ——
     * 开录第一秒就把自己当成通话中的 App 暂停掉，而且每 1.5 秒重进一次，永远录不到音。
     * 这是 2026-11-10 改蓝牙路由时差点埋进去的坑（旧版之所以没炸，是因为蓝牙路由
     * 从没成功过、mode 立刻被恢复成 NORMAL）。
     */
    private var selfHoldsCommMode = false

    /**
     * 系统是否**异步确认**了蓝牙通信设备已生效。null = 还没收到回调。
     *
     * 为什么不能只看 setCommunicationDevice 的返回值：返回 true 只代表"请求被接受"，
     * 路由是异步应用的，Google 允许最长 30 秒。只有 OnCommunicationDeviceChangedListener
     * 回调里 device.id 命中目标，才算真的切过去了。
     */
    private val btRouteConfirmed = AtomicReference<Boolean?>(null)

    /** 通信设备变更监听器，退出时必须注销，否则服务重建会叠加注册 */
    private var commDeviceListener: AudioManager.OnCommunicationDeviceChangedListener? = null

    /**
     * 本轮录音的**通路身份**（见 [MicRoute.keyOf]），声纹判定只认同一通路的档案。
     *
     * 为什么不能像旧版那样"所有档案一起比"：说话人 embedding 对信道极其敏感，
     * 2026-11-11 实测同一人跨麦相似度 0.25、与陌生人 0.24 —— 拿手机麦录的档案
     * 去比耳机麦的句子，结果与随机落名字没有区别，而用户看到的却是"确定是谁"。
     *
     * 空串 = 还没确定通路。此时一律不判人，宁可空着。
     */
    @Volatile
    private var currentMicKey: String = ""

    /**
     * 本轮蓝牙通信设备（sink）的产品名，只用于界面/落库显示。
     * sink 侧的 id 与麦克风（source）侧的 id 不是一套，所以显示名字，不显示 id。
     */
    private var btSinkProductName: String? = null

    // 说明：原本打算用 AudioManager.registerAudioRecordingCallback 精确识别"谁抢了麦克风"，
    // 但 AudioRecordingConfiguration.getClientPackageName() 是 API 29 才有，
    // 且 minSdk 26 下无法直接引用。实际让麦经验证由下面两条路径覆盖：
    //   ① AudioManager.mode == IN_CALL / IN_COMMUNICATION（微信语音、来电都会走到）
    //   ② AudioRecord 连续读到全零 PCM（麦克风被独占时系统返回静音）
    // 因此这里不做包名级归因，只保留可用的两种判定。

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        db = VoiceLogDatabaseHolder.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 启动

    private fun startCapture() {
        if (running.get()) return

        // 通知必须先于录音建立 —— Android 14+ 要求 FGS 立即进入前台态
        startAsForeground()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            SessionState.error.value = getString(R.string.perm_need_audio)
            stopSelf()
            return
        }

        recordOnly = !AppPrefs.realtimeAsr(this)

        /* 只录不转模式只需要 VAD（629KB），不需要 ASR（229MB）与声纹（27MB）。
           所以这里分开校验：模型没装全也能录音，只是暂时转不了。 */
        if (recordOnly) {
            if (!java.io.File(ModelPaths.vadModel(this)).exists()) {
                SessionState.error.value = "缺少 VAD 模型，无法开始监听"
                stopSelf()
                return
            }
        } else if (!ModelPaths.ready(this)) {
            SessionState.error.value =
                getString(R.string.model_missing) + "\n缺少: " + ModelPaths.missing(this).joinToString(", ")
            stopSelf()
            return
        }

        SessionState.reset()
        val now = System.currentTimeMillis()
        SessionState.sessionStart.value = now
        SessionState.running.value = true
        SessionState.status.value = if (recordOnly) "正在监听（只录音，不转写）" else "正在加载模型…"
        running.set(true)
        captureStartWall = now

        // ---- 建立会话记录，本轮所有句子都挂在它下面 ----
        sessionId = runCatching {
            runBlocking { db.sessionDao().insert(SessionEntity(startWall = now)) }
        }.onFailure { Log.e(TAG, "create session failed", it) }.getOrDefault(0L)
        SessionState.currentSessionId.value = sessionId

        // ---- 声纹：模型在 + 至少一个人注册过 才启用，否则整轮跳过判断 ----
        loadSpeakerConfig()
        aacBitRate = AppPrefs.aacBitRate(this)
        spkEnabled = ModelPaths.spkReady(this) && spkProfiles.isNotEmpty()
        SessionState.voiceprintActive.value = spkEnabled
        Log.d(
            TAG,
            "voiceprint enabled=$spkEnabled profiles=${spkProfiles.map { it.name + "/" + it.sampleCount }} " +
                "threshold=$spkThreshold self=$selfPersonIds persons=${persons.map { it.name + "/" + it.genderLabel }}",
        )

        // 重建 VAD / ASR（每次启动都新建，避免上次释放后残留状态）
        captureThread = Thread({ captureLoop() }, "vl-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        if (recordOnly) {
            // 只录不转：不起 ASR 线程，改起编码线程
            encodeThread = Thread({ encodeLoop() }, "vl-encode").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }
        } else {
            asrThread = Thread({ asrLoop() }, "vl-asr").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }
        }
    }

    private fun stopCapture() {
        if (!running.getAndSet(false)) {
            SessionState.running.value = false
            return
        }
        SessionState.status.value = "已停止"
        SessionState.running.value = false

        /* 收尾顺序有讲究：**先等采集线程自己退出**（它会在 finally 里 flush VAD 尾段），
           再给处理线程发哨兵。反过来的话，flush 出来的最后一段可能落空（队列没人消费），
           表现为「停止前最后一句话消失」。
           Vad 非线程安全，两边同时 flush 会踩内存，所以这里必须 join 而不是各自 flush。 */
        captureThread?.interrupt()
        captureThread?.let { t -> runCatching { t.join(1500) } }
        captureThread = null

        if (recordOnly) {
            encodeQueue.offer(SENTINEL)
            // 单段最长 20s，编码约 50ms，8s 足够排空；多给几秒是因为尾段常卡在编码器初始化的那一拍
            encodeThread?.let { t ->
                runCatching { t.join(8000) }
                // join 不上就强退：卡死的编码线程会一直占着 codec，下一轮录音必然跟着出问题
                if (t.isAlive) runCatching { t.interrupt() }
            }
            encodeThread = null
            /* ⚠️ 这里原来有一句 encodeQueue.clear()，已删除。
               队列里每一个元素都是一段真实录音，清掉等于"用户说了话、音频永远没了，还不报错"。
               真没编完的（编码器卡死）改为逐条落 FAILED 记录，让缺的那一段在界面上看得见。 */
            markDroppedSegments()
        } else {
            asrQueue.offer(SENTINEL)
            /* 原来这里不等 ASR 线程收尾就直接把线程引用丢掉，
               紧接着下面会 release 掉 engine —— 线程还在 transcribe 时被抽掉引擎，
               轻则最后一句丢，重则 native 崩溃。先等它把队列吃完。 */
            asrThread?.let { t ->
                runCatching { t.join(5000) }
                if (t.isAlive) runCatching { t.interrupt() }
            }
            asrThread = null
        }

        runCatching { vad?.release() }
        vad = null
        runCatching { engine?.release() }
        engine = null
        runCatching { speaker?.release() }
        speaker = null

        // 收尾会话：写入结束时间与实际统计，供列表页直接展示
        if (sessionId > 0) {
            val id = sessionId
            runCatching {
                runBlocking {
                    /* ⚠️ 统计口径要按模式分开：只录不转时 transcript 还是空的，
                       若照抄老逻辑用句子数，会把 encodeLoop 累加的段数覆盖成 0，
                       表现为「录了一小时，列表显示 0 句」。 */
                    if (recordOnly) {
                        val segs = db.segmentDao().listBySession(id)
                        db.sessionDao().updateStats(id, segs.size, segs.sumOf { it.durMs })
                    } else {
                        val list = db.transcriptDao().listBySession(id)
                        db.sessionDao().updateStats(
                            id = id,
                            count = list.size,
                            speechMs = list.sumOf { it.endMs - it.startMs },
                        )
                    }
                    db.sessionDao().close(id, System.currentTimeMillis())
                }
            }.onFailure { Log.e(TAG, "close session failed", it) }
        }
        sessionId = 0L
        SessionState.level.value = 0f

        /* 「录完自动同步」：开关默认关，只有用户主动开了才推。
           ⚠️ 用独立 Thread 而不是 coroutine scope —— Service 紧接着就要 stopSelf，
             绑在 service 生命周期上的 scope 会被 cancel，同步半途中断。
             中断本身不致命（脏标记还在，下次手动同步会补上），但会留下一条
             看不懂的错误记录，让用户以为同步功能坏了。
           只推文字不推音频，所以即使这次失败也不会丢任何素材。 */
        if (AppPrefs.syncEnabled(this)) {
            Thread(
                {
                    runCatching {
                        runBlocking { SyncManager.syncNow(applicationContext, db) }
                            ?.let { Log.d(TAG, "auto sync: ${it.message}") }
                    }.onFailure { Log.w(TAG, "auto sync failed", it) }
                },
                "vl-autosync",
            ).start()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ------------------------------------------------------------------ 采集

    private fun captureLoop() {
        var record: AudioRecord? = null
        var silentWindows = 0
        var inCallPaused = false
        var levelTick = 0
        /** 连续「判全零 → 重建」的次数。恢复供数后清零 */
        var silentRebuilds = 0
        /** 本轮停摆开始的真实墙钟；0 = 没在停摆 */
        var stallSinceWall = 0L

        fun releaseRecord() {
            runCatching {
                record?.stop()
                record?.release()
            }
            record = null
            /* 退出蓝牙路由必须在这里做，不能只放在 stop() 里 ——
               录音器会因异常重建（read 返回负数那几条路径都会调到这里），
               漏掉就会有模式残留：全机音频被当成通话处理。 */
            exitBluetoothRoute()
        }

        try {
            /* VAD 参数在**开录这一刻**读一次。所以改设置对当前这一轮无效、
               对下一轮生效 —— 设置页的文案里写清了这一点，不能让用户以为
               是在监听中途调灵敏度。
               码率同理：已落盘的 m4a 不会因为改码率重新编码。 */
            vad = VadSegmenter(
                modelPath = ModelPaths.vadModel(this),
                threshold = AppPrefs.vadThreshold(this),
                minSilenceSec = AppPrefs.vadMinSilence(this),
                minSpeechSec = AppPrefs.vadMinSpeech(this),
                maxSpeechSec = AppPrefs.vadMaxSpeech(this),
            )

            /* F0 判男女的配置也在开录时读一次：一轮录音内的判据必须恒定，
               中途改阈值会让同一轮里前后段用不同标准，事后无法解释。 */
            f0Enabled = AppPrefs.f0Enabled(this)
            f0MaleMax = AppPrefs.f0MaleMax(this)
            f0FemaleMin = AppPrefs.f0FemaleMin(this)
            /* 人名与归属走 PersonStore 这一条通道。之前这里读的是听标那两个名字
               （abA/abB），和声纹档案的名字是两套副本 —— 同一个人被劈成两个 chip
               就是这么来的。现在名字只存一份，兜底落谁由「该性别是否只有一个人」决定。 */
            loadSpeakerConfig()

            /* ★ 只录不转模式的核心：**不创建任何 ASR / 声纹实例**。
               v2 里这两个模型常驻要占 300MB+ PSS，还要每句推理 135~208ms。
               解耦之后，监听期间的内存与算力只花在 VAD 上（每 32ms 一次，成本极低）。 */
            if (recordOnly) {
                spkEnabled = false
                SessionState.voiceprintActive.value = false
                SessionState.recordOnlyMode.value = true
                SessionState.status.value = "监听中（只录音）"
                Log.d(TAG, "record-only mode: 不加载 ASR/声纹模型")
            } else {
                engine = SenseVoiceEngine(
                    modelPath = ModelPaths.asrModel(this),
                    tokensPath = ModelPaths.asrTokens(this),
                    language = AppPrefs.asrLang(this),
                    numThreads = 2,
                    useItn = AppPrefs.asrItn(this),
                )
                if (spkEnabled) {
                    speaker = SpeakerEngine(ModelPaths.spkModel(this))
                    if (speaker?.ready != true) {
                        // 模型文件损坏或加载失败：降级为不判人，而不是整轮失败
                        Log.e(TAG, "speaker engine init failed, degrade to no-diarization")
                        runCatching { speaker?.release() }
                        speaker = null
                        spkEnabled = false
                        SessionState.voiceprintActive.value = false
                        persistInterrupt("error", "声纹模型加载失败，本轮不判说话人")
                    }
                }
                SessionState.status.value = "监听中"
            }

            while (running.get()) {
                // ---- 通话优先：让出麦克风 ----
                val mode = audioManager.mode
                /* 注意第二档：MODE_IN_COMMUNICATION 现在有两种来源 —— 微信/QQ 语音
                   （该让），以及**我们自己为了让蓝牙麦克风通车而设的**（不能让自己的麦）。
                   不区分开就会形成自杀循环：开录即误判为通话 → 释放 → 重建 → 再误判。
                   真被微信抢走时，communicationDevice 会被系统改派、回调
                   btRouteConfirmed 转 false，同时全零检测也在兜底，所以这里可以放心排除。 */
                val callActive = when (mode) {
                    AudioManager.MODE_IN_CALL -> true
                    AudioManager.MODE_IN_COMMUNICATION -> !selfHoldsCommMode
                    else -> false
                }
                if (callActive) {
                    if (!inCallPaused) {
                        inCallPaused = true
                        releaseRecord()
                        persistInterrupt("call", "通话模式 $mode，已让出麦克风")
                        SessionState.noteInterrupt("通话中，已暂停")
                        SessionState.status.value = "通话中，已让出麦克风"
                    }
                    Thread.sleep(RETRY_DELAY_MS)
                    continue
                }
                if (inCallPaused) {
                    inCallPaused = false
                    SessionState.status.value = "监听中"
                }

                // ---- 按需建立 AudioRecord ----
                if (record == null) {
                    val created = openRecord()
                    if (created == null) {
                        persistInterrupt("error", "AudioRecord 创建失败")
                        SessionState.noteInterrupt("麦克风打开失败，重试中")
                        Thread.sleep(RETRY_DELAY_MS)
                        continue
                    }
                    runCatching { created.startRecording() }
                    record = created
                    silentWindows = 0
                    /* 记录本轮实际走的是哪个麦克风。
                       ⚠️ 必须在 startRecording() 之后读：之前 getRoutedDevice() 恒为 null，
                       这是官方文档明确写的，不是偶发。
                       读不到也不影响录音，只是界面上显示「未知」。 */
                    runCatching {
                        val dev = created.routedDevice ?: return@runCatching

                        /* 回落模式：蓝牙链路没建起来，本轮实际用手机麦。
                           此时 routedDevice 仍会报蓝牙（音频策略的默认输入就是它），
                           把它当事实写上去就是上一版骗人的原因，所以直接跳过。 */
                        if (fellBackToBuiltin) {
                            Log.d(
                                TAG,
                                "回落模式：routedDevice 报「${describeInputDevice(dev)}」，实际用内置麦克风",
                            )
                            val sid = sessionId
                            if (sid > 0L) {
                                persistInputRoute(sid, "内置麦克风（蓝牙麦未接通）")
                            }
                            return@runCatching
                        }

                        /* 蓝牙路由成功时**不走下面的 id 比对**。
                           原因：requestedDeviceId 是麦克风（source）侧的 id，
                           而 setCommunicationDevice 认的是输出（sink）侧，两边不是同一套编号，
                           拿它们比必然不等 → 会稳定误报"请求未生效"，把真相盖掉。
                           这里改用回调确认状态来判断，并且等一小会儿让回调有机会到达。 */
                        if (targetIsBluetooth) {
                            val name = btSinkProductName ?: AppPrefs.inputDeviceName(this)
                            /* 回调通常几毫秒就到，但小米机型建 SCO 要 5~8 秒。
                               这里只等 1.2 秒：等到了就写"已确认"，等不到就如实写"未确认"，
                               不假装成功、也不无限等（无限等会挡住 read 循环）。 */
                            var waited = 0
                            while (btRouteConfirmed.get() == null && waited < 1200) {
                                Thread.sleep(100)
                                waited += 100
                            }
                            val confirmed = btRouteConfirmed.get() == true
                            val desc = if (confirmed) {
                                "蓝牙通话麦 · $name"
                            } else {
                                "蓝牙通话麦 · $name（系统未确认，可能仍在用内置麦）"
                            }
                            Log.d(TAG, "蓝牙输入设备: $desc")
                            SessionState.inputDevice.value = desc
                            if (!confirmed) {
                                SessionState.noteInterrupt("蓝牙通话路由未确认，如音量仍随距离变化说明还在用手机麦")
                                /* 未确认时通路是**不确定**的，声纹一律不判。
                                   回调一旦命中，registerCommDeviceListener 会把键填回去，
                                   所以这里只是推迟，不影响正常建链后的判定。 */
                                currentMicKey = ""
                            }
                            val sid = sessionId
                            if (sid > 0L) persistInputRoute(sid, desc)
                            return@runCatching
                        }

                        var desc = describeInputDevice(dev)
                        SessionState.inputDevice.value = desc
                        /* 校准成**实际**路由的通路键。上面预置的是用户「想用」的那支，
                           而 setPreferredDevice 只是建议，系统可以忽略 —— 以这里为准。 */
                        currentMicKey = MicRoute.keyOf(dev)

                        /* 复核用户指定的设备有没有真的生效（仅内置/有线/USB 这类可直选输入的设备）。
                           蓝牙已在上面单独处理，不会走到这里。
                           剩下的情况只有「设备中途消失」，比 id 就够。 */
                        val wantId = requestedDeviceId
                        if (wantId != 0 && dev.id != wantId) {
                            val wantName = AppPrefs.inputDeviceName(this)
                            Log.w(TAG, "偏好设备未生效: want=$wantId actual=${dev.id} $desc")
                            /* 落库和界面都写「实际用的是谁 + 请求未生效」。
                               上一版把「打算用谁」当事实显示，用户以为在解决远场问题，
                               实际仍在内置麦上 —— 比不显示更糟。 */
                            desc = "$desc（请求「$wantName」未生效）"
                            SessionState.inputDevice.value = desc
                            SessionState.noteInterrupt(
                                "「$wantName」未生效，实际在用「${describeInputDevice(dev)}」",
                            )
                        }

                        val sid = sessionId
                        if (sid > 0L) persistInputRoute(sid, desc)
                        Log.d(TAG, "input device: $desc [key=${currentMicKey}]")
                    }.onFailure { Log.w(TAG, "读取录音设备失败", it) }
                }

                // 取局部 final 引用：record 会被 releaseRecord() 改动，无法直接智能转换
                val rec = record ?: continue
                val shorts = ShortArray(READ_WINDOW)
                val n = rec.read(shorts, 0, READ_WINDOW)
                if (n <= 0) {
                    persistInterrupt("error", "read 返回 $n，重建录音器")
                    SessionState.noteInterrupt("音频读取异常，重建中")
                    releaseRecord()
                    Thread.sleep(RETRY_DELAY_MS)
                    continue
                }

                // ---- 全零检测：麦克风被独占 / 选中的设备没供数据 ----
                if (isAllZero(shorts, n)) {
                    silentWindows++
                    /* 蓝牙链路没确认之前要放宽窗口，理由见 BT_SILENT_WINDOWS_FOR_INTERRUPT。 */
                    val silentThreshold = if (targetIsBluetooth && btRouteConfirmed.get() != true) {
                        BT_SILENT_WINDOWS_FOR_INTERRUPT
                    } else {
                        SILENT_WINDOWS_FOR_INTERRUPT
                    }
                    if (silentWindows >= silentThreshold) {
                        silentWindows = 0
                        silentRebuilds++
                        if (stallSinceWall == 0L) {
                            stallSinceWall = System.currentTimeMillis()
                            /* ⚠️ 这里只提醒界面，**不落库**。落库要等停摆结束，
                               用一条「中断 X 分钟」的汇总。2026-11-15 实测：按原逻辑
                               每 3.6 秒落一条，一轮 2.5 小时的停摆写了 2571 条，
                               interrupt 表被彻底淹没，真实信息（丢了多久）反而看不出来。 */
                            SessionState.noteInterrupt(
                                if (targetIsBluetooth) "蓝牙麦克风没有数据，正在重试"
                                else "麦克风被其他应用占用，正在重试",
                            )
                        }
                        releaseRecord()
                        /* 连续几次重建都救不回来，说明问题不在 AudioRecord 实例上
                           （是设备/系统层面不供数），再按 1.5 秒重建只是白烧电、白刷日志。
                           降到 30 秒探测一次：最坏多丢 30 秒，比一小时重建 1000 次强。
                           ⚠️ 刻意**不自动切换到内置麦**：中途换麦会让同一轮出现两条通路，
                           声纹只能降级成只判男女声，代价比「明确告诉用户没录到」更大。 */
                        val stalled = silentRebuilds >= SILENT_REBUILD_GIVE_UP
                        Thread.sleep(if (stalled) STALL_PROBE_DELAY_MS else RETRY_DELAY_MS)
                        if (stalled) {
                            val lostMin = ((System.currentTimeMillis() - stallSinceWall) / 60_000L)
                                .coerceAtLeast(1L)
                            SessionState.status.value = "麦克风无数据，已中断 $lostMin 分钟"
                        }
                    }
                    continue
                }
                silentWindows = 0

                /* ---- 从停摆中恢复 ----
                   停摆期间时间轴是**跳过**的（不补、不压缩）：录音确实空了一段，
                   补时间等于伪造。宁可让用户看到一条明显的空档 + 一条说明。 */
                if (stallSinceWall > 0L) {
                    val lost = (System.currentTimeMillis() - stallSinceWall).coerceAtLeast(0L)
                    persistInterrupt(
                        "silent",
                        "麦克风无数据 ${fmtSpan(lost)}：这段音频没有录到，时间轴已跳过",
                    )
                    SessionState.noteInterrupt("已恢复录音，中断 ${fmtSpan(lost)}")
                    SessionState.status.value = if (recordOnly) "监听中（只录音）" else "监听中"
                    stallSinceWall = 0L
                }
                silentRebuilds = 0

                // ---- 实时电平（约每 96ms 更新一次，31 次/秒会白白拖累重组）----
                if (++levelTick >= 3) {
                    levelTick = 0
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = shorts[i].toDouble()
                        sum += v * v
                    }
                    val rms = (Math.sqrt(sum / n) / 32768.0).coerceIn(0.0, 1.0)
                    SessionState.level.value = rms.toFloat()
                }

                // ---- 送 VAD ----
                /* 墙钟在**喂入的这一刻**取一次，随段一起往下走。
                   绝不能事后用「采集开始墙钟 + 样本偏移」推算 —— 全零/读异常/来电让麦
                   那几个分支消耗了真实时间却不产生样本，推算出来的时间会一路偏早。 */
                val wallNow = System.currentTimeMillis()
                val floats = FloatArray(n) { shorts[it] / 32768.0f }
                val segments = vad?.accept(floats, wallNow) ?: emptyList()
                for (seg in segments) {
                    if (seg.durationMs < 200) continue // 太短的段多半是噪声
                    if (recordOnly) {
                        encodeQueue.offer(seg)
                        SessionState.queueDepth.value = encodeQueue.size
                    } else {
                        asrQueue.offer(seg)
                        SessionState.queueDepth.value = asrQueue.size
                    }
                }
            }
        } catch (_: InterruptedException) {
            // 正常退出
        } catch (t: Throwable) {
            Log.e(TAG, "capture loop crashed", t)
            persistInterrupt("error", "采集线程异常: ${t.javaClass.simpleName}: ${t.message}")
            SessionState.error.value = "采集异常：${t.message}"
        } finally {
            // 收尾：把 VAD 残留的尾段送出去
            runCatching {
                vad?.flush(System.currentTimeMillis())?.forEach {
                    if (recordOnly) encodeQueue.offer(it) else asrQueue.offer(it)
                }
            }
            /* 停止时仍在停摆：补一条汇总。没有它的话，这段静默在库里不留任何痕迹，
               用户事后看到「句子里突然空了 2 小时」会以为是同步故障。 */
            if (stallSinceWall > 0L) {
                persistInterrupt(
                    "silent",
                    "停止时麦克风仍无数据，本段中断 " +
                        fmtSpan(System.currentTimeMillis() - stallSinceWall),
                )
            }
            releaseRecord()
        }
    }

    /**
     * 只录不转：把 VAD 段编码成 m4a 并登记到 segment 表。
     *
     * ## 为什么先插库再写文件
     * 文件名要用 segment 的 id（`audio/<sessionId>/<id>.m4a`）。若反过来「先写文件再猜 id」，
     * 一旦写文件成功但插库失败，就会留下一堆无人认领的孤儿文件，占着空间还删不掉。
     * 现在是「先插库（filePath 留空）→ 编码 → 回填路径」，最坏情况是一条记录指向不存在的文件，
     * 转写时会被判 FAILED 并清掉，可控。
     */
    private fun encodeLoop() {
        /* 只能靠哨兵退出，不能看 running。
           原因是一个很窄但必然发生的时序：
             ① stopCapture 先把 running 置 false
             ② 再 interrupt 采集线程并 join —— 采集线程在 finally 里 flush VAD，
                把最后一段推进 encodeQueue
           如果这里用 `while (running.get() || encodeQueue.isNotEmpty())` 判条件，
           编码线程很可能在 ① 之后、② 的 flush 之前看到"队列空 + 未运行"而退出，
           于是最后一段永远没人处理 —— 就是"停止前最后一句消失 / 录了却没有音频"。
           stopCapture 一定会送哨兵，这里放心等。 */
        while (true) {
            val seg = try {
                encodeQueue.take()
            } catch (_: InterruptedException) {
                break
            }
            if (seg.startSample < 0) break // 哨兵

            val startMs = seg.startSample * 1000L / SAMPLE_RATE
            val durMs = seg.durationMs
            // 会话 id 做快照：编码要几十毫秒，期间 stopCapture 可能已把它置 0
            val sid = sessionId
            if (sid <= 0) continue

            var rowId = 0L
            try {
                /* 墙钟取段的真实时刻。wallStart=0 只可能是老调用方没传，
                   此时退回旧算法（会偏，但不至于写 0 → 1970）。 */
                val wall = seg.wallStart.takeIf { it > 0L } ?: (captureStartWall + startMs)

                /* ⚠️ 必须用同步版 DAO，不能写 runBlocking { insert() }。
                   2026-11-07 实测事故：这个编码线程在**第一段之后永久卡死**，
                   13 分钟内队列堆了 63 段没人消费，停止时全部标成"未编码完成"，
                   用户看到的就是「录了 64 段，只有一条记录还转写失败」。
                   runBlocking 在裸线程上等 Room 的挂起 DAO，会在写入时静默阻塞：
                   不抛异常、不打日志、CPU 也不涨，只有"不再有后续动作"这一个症状。 */
                rowId = db.segmentDao().insertBlocking(
                    SegmentEntity(
                        sessionId = sid,
                        startMs = startMs,
                        endMs = startMs + durMs,
                        wallClock = wall,
                        filePath = "",
                        durMs = durMs,
                    ),
                )

                val file = AudioStore.fileFor(this, sid, rowId)
                val bytes = AacEncoder.encode(seg.samples, file, bitRate = aacBitRate)

                /* 编码"返回了"不等于文件可用。必须落地校验：
                   否则一张 0 字节残片会被当成已就绪，直到用户点转写才炸，
                   而那时报的是"音频为空或无法解码"，真正的原因（编码失败）已经不见了。 */
                if (!file.exists() || file.length() <= 0L) {
                    throw IllegalStateException("编码产物为空文件")
                }

                /* 回填路径 + 累加会话统计。两个都是同步 DAO：
                   原来的 runBlocking 版本里 updateFile 成功、updateStats 没跑，
                   会话统计就永远停在 0 —— 这正好是线上事故的指纹。 */
                db.segmentDao().updateFileBlocking(rowId, file.absolutePath, bytes)
                db.sessionDao().bumpStatsBlocking(sid, durMs)
                SessionState.cachedSegments.value = SessionState.cachedSegments.value + 1
                SessionState.queueDepth.value = encodeQueue.size
            } catch (t: Throwable) {
                Log.e(TAG, "编码失败", t)
                /* 失败必须落到这条 segment 上，不能只写一行日志：
                   原来这里什么都不写，段落停在 PENDING、filePath 留空，
                   界面上既看不到失败原因，重试也只是重复同一次失败。 */
                runCatching { AudioStore.fileFor(this, sid, rowId).delete() }
                if (rowId > 0) {
                    runCatching {
                        db.segmentDao().updateStatusBlocking(
                            rowId,
                            SegmentEntity.ST_FAILED,
                            "编码失败：${t.javaClass.simpleName}",
                        )
                    }
                }
                persistInterrupt("error", "音频编码失败：${t.javaClass.simpleName}")
            }
        }
        Log.d(TAG, "encode loop 退出")
    }

    /**
     * 停止时把编码队列里没处理完的段落落成 FAILED 记录。
     *
     * 宁可让用户看到「这里有一段没存下来」，也不要静默丢掉 ——
     * 丢掉之后用户只知道"少了一句"，永远查不出是丢了还是本来就没说。
     */
    private fun markDroppedSegments() {
        if (encodeQueue.isEmpty()) return
        val dropped = mutableListOf<VadSegmenter.Segment>()
        encodeQueue.drainTo(dropped)
        dropped.removeAll { it.startSample < 0 }
        if (dropped.isEmpty()) return
        val sid = sessionId
        if (sid <= 0) return
        /* 这里用挂起版是安全的：本方法只从 stopCapture / onDestroy 调用，都在**主线程**。
           主线程上 runBlocking 等 Room 的挂起 DAO 会让 Room 自己切到它的 IO 线程，
           正常返回；死锁只发生在「裸 Thread + runBlocking + 挂起 DAO」这个组合里
           （encodeLoop 已经踩过，见那里的说明）。 */
        runCatching {
            runBlocking {
                dropped.forEach { seg ->
                    val startMs = seg.startSample * 1000L / SAMPLE_RATE
                    db.segmentDao().insert(
                        SegmentEntity(
                            sessionId = sid,
                            startMs = startMs,
                            endMs = startMs + seg.durationMs,
                            wallClock = seg.wallStart.takeIf { it > 0L }
                                ?: (captureStartWall + startMs),
                            filePath = "",
                            durMs = seg.durationMs,
                            status = SegmentEntity.ST_FAILED,
                            failReason = "停止时未编码完成",
                        ),
                    )
                }
            }
        }.onFailure { Log.e(TAG, "标记未编码段失败", it) }
        persistInterrupt("error", "停止时有 ${dropped.size} 段未完成编码")
    }

    private fun openRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        val bufSize = maxOf(minBuf, READ_WINDOW * 2 * 4)

        /* 目标设备必须在创建 AudioRecord **之前**解析出来。
           原因：音源（AudioSource）是构造参数，改不了；而蓝牙麦克风只有走
           VOICE_COMMUNICATION（通信路径）才会被系统真正接通，
           VOICE_RECOGNITION 会被路由回内置麦 —— 2026-11-10 实测踩到：
           选了蓝牙麦，routedDevice 报蓝牙、音频栈 patch 也报
           AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET，但实际 PCM 来自内置麦
           （音量随手机与人的距离变化，频谱也没有 SCO 窄带特征）。 */
        val wantId = AppPrefs.inputDeviceId(this)
        var wantDev = if (wantId != 0) {
            runCatching {
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == wantId }
            }.getOrNull()
        } else {
            null
        }

        /* AudioDeviceInfo.id 不稳定：蓝牙耳机重连、重装应用、系统重启后都可能换号。
           只按 id 找的话，用户拔插一次耳机就得回设置里重新选一遍，
           而且中间那些录音会静默用内置麦。所以 id 失效时按名字兜底匹配一次。 */
        if (wantDev == null && wantId != 0) {
            val savedName = AppPrefs.inputDeviceName(this)
            if (savedName.isNotBlank()) {
                wantDev = runCatching {
                    audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { describeInputDevice(it) == savedName }
                }.getOrNull()
                if (wantDev != null) {
                    Log.w(TAG, "偏好设备 id=$wantId 已失效，按名字「$savedName」匹配到新 id=${wantDev.id}")
                    AppPrefs.setInputDevice(this, wantDev.id, savedName)
                } else {
                    Log.w(TAG, "偏好设备 id=$wantId 与名字「$savedName」都找不到（可能未连接），本轮用系统默认")
                }
            }
        }

        requestedDeviceId = wantDev?.id ?: 0
        targetIsBluetooth = wantDev != null && isBluetoothInput(wantDev.type)
        fellBackToBuiltin = false
        selfHoldsCommMode = false
        /* 通路身份在拿到「实际路由」之前一律为空 —— 空意味着不判声纹。
           先按用户的意图预置一个，随后由 routedDevice 校准成真实值：
           请求与实际是两件事，这套代码已经在这一点上栽过三次。 */
        currentMicKey = if (wantDev != null && !targetIsBluetooth) MicRoute.keyOf(wantDev) else ""

        /* ===== 蓝牙链路（2026-11-10 按官方模板重写）=====

           旧版三个致命错误，一个都跑不通：
           ① 拿 getDevices(GET_DEVICES_INPUTS) 里的**输入**设备去调 setCommunicationDevice。
              官方文档写明这个接口只接受 **sink（输出）** 设备，"匹配的输入设备由平台自动选择"，
              传 source 侧进去会被无条件拒绝 —— 这才是「恒返回 false」的真正原因，
              不是 MIUI 把我们拉黑了。
           ② 在创建 AudioRecord **之前**阻塞轮询等链路。但系统只在**已经开始录音之后**
              才真正处理通信设备请求，而且约 6 秒内没开录就会把 mode 收回。
              等在前面的方向是反的。
           ③ 拿 communicationDevice.id 去比输入设备 id —— 一个 sink 一个 source，永远不等，
              于是"永远判定失败"。这已经是第三次拿请求本身当结果了
              （前两次：routedDevice 跟自己比、isBluetoothScoOn 是自己刚设的）。

           现在的顺序照抄官方 HFP 录音模板：
             找 sink 设备 → 建 AudioRecord(VOICE_COMMUNICATION)
             → setCommunicationDevice → （调用方）立刻 startRecording → 异步等回调确认。 */
        var btSink: AudioDeviceInfo? = null
        btSinkProductName = null
        if (targetIsBluetooth) {
            btSink = findBluetoothCommDevice()
            btSinkProductName = btSink?.let { runCatching { it.productName?.toString() }.getOrNull() }
            if (btSink == null) {
                fallBackFromBluetooth(
                    AppPrefs.inputDeviceName(this),
                    "耳机只连了音乐通道（A2DP），没有通话通道。到 蓝牙设置 里勾上「通话音频」再试",
                )
                wantDev = null
            } else {
                /* 蓝牙的通路键取 sink 侧的「类型+产品名」。sink 与 source 的 id 不是一套，
                   但 productName 是同一个耳机名，所以键能与注册时对齐。 */
                currentMicKey = MicRoute.keyOf(btSink)
            }
        }

        val buildRec: (Int) -> AudioRecord? = { audioSource ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioRecord.Builder()
                        .setAudioSource(audioSource)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(bufSize)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioRecord(
                        audioSource,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize,
                    )
                }
            }.getOrNull()
        }

        /* 音源必须在构造时就定死，改不了。蓝牙只有走 VOICE_COMMUNICATION（通信路径）
           才会被系统真正接通，VOICE_RECOGNITION 会被路由回内置麦 —— 2026-11-10 实测：
           选了蓝牙麦，routedDevice 报蓝牙、音频栈 patch 也报
           AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET，但实际 PCM 来自内置麦。
           另外 HFP 输入上限 16kHz，我们正好用 16k，不需要重采样。 */
        var rec = buildRec(
            if (btSink != null) MediaRecorder.AudioSource.VOICE_COMMUNICATION
            else MediaRecorder.AudioSource.VOICE_RECOGNITION,
        ) ?: return null

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            Log.w(TAG, "AudioRecord 未初始化，放弃本次创建")
            return null
        }

        /* 构造完成之后才设通信设备。返回值 true 只代表"请求被接受"，
           真正的切换在 startRecording 之后异步发生，所以这里不阻塞等待。 */
        if (btSink != null && !enterBluetoothRoute(btSink)) {
            /* 请求被拒：把录音留在 VOICE_COMMUNICATION 上会读到全零，
               必须换回内置麦重建，不许硬撑（旧版"每 1.5 秒重建一次"的死循环就是这么来的）。 */
            runCatching { rec.release() }
            fallBackFromBluetooth(AppPrefs.inputDeviceName(this), "系统拒绝了通话路由")
            btSink = null
            wantDev = null
            rec = buildRec(MediaRecorder.AudioSource.VOICE_RECOGNITION) ?: return null
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { rec.release() }
                Log.w(TAG, "回落重建仍失败，放弃本次创建")
                return null
            }
        }

        /* setPreferredDevice 只对内置/有线/USB 这种"输入设备本身可选"的场景有意义。
           蓝牙走的是通信设备那一套，输入侧由系统自动配对；再塞一个 source 设备进去
           反而会和自动匹配打架（社区里有因此录到全零的案例），所以跳过。
           注意：该调用只是**建议**，系统可以忽略。 */
        if (btSink == null && wantDev != null) {
            runCatching {
                rec.setPreferredDevice(wantDev)
                Log.d(TAG, "请求输入设备 id=${wantDev.id} (${describeInputDevice(wantDev)})")
            }.onFailure { Log.w(TAG, "请求输入设备失败", it) }
        } else if (wantId != 0 && btSink == null) {
            Log.w(TAG, "偏好设备 id=$wantId 不可用（未连接或名字匹配失败），改用系统默认")
            /* 必须告诉用户。这条不只是"少了个设备"：声纹是按麦绑定的，
               用户绑的耳机没连上 → 本轮通路是内置麦 → 之前用耳机录的声纹
               一句都不会生效。不提示的话，用户会以为"声纹坏了"。
               蓝牙只连了音乐通道（A2DP）时也是这一条，最常见。 */
            SessionState.noteInterrupt(
                "绑定的「${AppPrefs.inputDeviceName(this)}」没连上，本轮用手机麦。" +
                    "只连了音乐通道的蓝牙耳机要勾上「通话音频」才能当麦克风用。",
            )
            currentMicKey = MicRoute.KEY_BUILTIN
        }

        return rec
    }

    /**
     * 把「这轮用的哪支麦」同时写进两个字段：给人看的描述 + 给程序比的通路键。
     *
     * 两个都写而不是只写一个：事后翻记录时人要看懂（描述），
     * 补转历史音频时程序要能精确匹配（键）。只写描述就等于把键丢在日志里，
     * 只写键就等于让用户看到 `bt|OpenRun by Shokz` 这种东西。
     */
    private fun persistInputRoute(sid: Long, desc: String) {
        if (sid <= 0L) return
        val key = currentMicKey
        runCatching {
            db.sessionDao().updateInputDeviceBlocking(sid, desc)
            if (key.isNotBlank()) db.sessionDao().updateMicKeyBlocking(sid, key)
        }.onFailure { Log.w(TAG, "记录输入通路失败", it) }
    }

    /**
     * 蓝牙不可用时统一回落：改标志 + 落中断记录 + 改界面文案。
     *
     * 为什么必须明确告知而不是悄悄用手机麦：两者信噪比差 20dB 以上，
     * 用户以为在用耳机麦、实际走手机麦，录出来的远场音会让他以为"耳机白买了"。
     */
    private fun fallBackFromBluetooth(wantName: String, why: String) {
        Log.w(TAG, "蓝牙麦克风不可用，本轮回落内置麦：$why")
        SessionState.noteInterrupt("「$wantName」用不上：$why，本轮改用手机麦")
        SessionState.inputDevice.value = "内置麦克风（「$wantName」未接通）"
        requestedDeviceId = 0
        targetIsBluetooth = false
        fellBackToBuiltin = true
        /* 回落之后音源就是内置麦，通路键必须跟着改 —— 否则会拿内置麦录的音
           去跟「耳机麦」的声纹档案比，正是这次要根除的那种静默错配。 */
        currentMicKey = MicRoute.KEY_BUILTIN
    }

    /**
     * 找到可用于通话的蓝牙设备（sink 侧），找不到返回 null。
     *
     * 关键点：必须查 `availableCommunicationDevices`，不能查 `getDevices(GET_DEVICES_INPUTS)`。
     *   前者返回的是**能作为通话输出**的设备（sink），setCommunicationDevice 只接受这种；
     *   后者返回麦克风（source），传进去必被拒 —— 这是 2026-11-10 排查的核心结论。
     *
     * 只认 SCO / BLE_HEADSET 两种：A2DP 只能放歌、没有麦克风通道，
     * 耳机只连 A2DP 时这个列表里根本不会出现 SCO 设备，此时是真的用不了。
     */
    private fun findBluetoothCommDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val all = runCatching { audioManager.availableCommunicationDevices }
            .getOrNull().orEmpty()
        /* 诊断日志必须打全量：一次就能看出耳机有没有注册通话通道。
           sink 恒为 true，写出来是为了以后排查时不用再猜这个接口的语义。 */
        Log.d(
            TAG,
            "可用通信设备 ${all.size} 个: " + all.joinToString { d ->
                "id=${d.id} type=${d.type} ${runCatching { d.productName }.getOrNull()} sink=${d.isSink}"
            },
        )
        return all.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: all.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
    }

    /** 蓝牙输入设备的类型判断（SCO 是经典蓝牙 HFP，BLE_HEADSET 是 LE Audio） */
    private fun isBluetoothInput(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

    /**
     * 把通话音频设备指向蓝牙耳机，返回「请求是否被系统接受」。
     *
     * 两个必须记住的前提：
     *   ① 传进来的一定是 **sink 设备**（来自 `availableCommunicationDevices`）。
     *      麦克风由系统自动配对到同一个耳机，不需要、也没法自己选。
     *   ② 返回 true 只代表「请求被接受」，路由是异步应用的。所以这里**不轮询、不等待** ——
     *      系统只有在录音已经开始之后才会真正处理这个请求，而且约 6 秒内不开录
     *      就会把 mode 收回。等在这里等于自己把窗口耗光。
     *      真正的确认交给 [registerCommDeviceListener] 的回调。
     */
    private fun enterBluetoothRoute(sink: AudioDeviceInfo): Boolean {
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
            .onFailure { Log.w(TAG, "设置通话模式失败", it) }
        selfHoldsCommMode = true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            /* API 31 以下没有通信设备接口，只能走老的 SCO 请求。
               本机是 Android 16，这一档无法实测，属兼容路径。 */
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }.onFailure { Log.w(TAG, "startBluetoothSco 失败", it) }
            Log.d(TAG, "API ${Build.VERSION.SDK_INT}：走 startBluetoothSco 兼容路径")
            return true
        }

        val accepted = runCatching { audioManager.setCommunicationDevice(sink) }
            .onFailure { Log.w(TAG, "setCommunicationDevice 抛异常", it) }
            .getOrDefault(false)
        Log.d(TAG, "请求通信设备 ${describeInputDevice(sink)} accepted=$accepted")
        if (!accepted) {
            exitBluetoothRoute()
            return false
        }
        registerCommDeviceListener(sink)
        return true
    }

    /**
     * 注册通信设备变更监听：只有回调里的 device 命中目标，才算真的切过去了。
     *
     * 为什么非要这一步：`setCommunicationDevice` 返回 true 只代表「请求被接受」，
     * 官方允许最长 30 秒才真正应用；社区里大量「返回 true 但完全没效果」的案例
     * 就是这么来的（那个案例里连回调都收不到，因为提问者没设通话模式）。
     *
     * 结果只用于**回报真相**（界面文案 / 全零容忍窗口），不用于阻塞开录 ——
     * 阻塞会把 6 秒窗口耗光，反而必定失败。
     */
    private fun registerCommDeviceListener(target: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (commDeviceListener != null) return
        btRouteConfirmed.set(null)
        val targetId = target.id
        val targetName = runCatching { target.productName?.toString() }.getOrNull()
        val listener = AudioManager.OnCommunicationDeviceChangedListener { dev ->
            val hit = dev != null && dev.id == targetId
            btRouteConfirmed.set(hit)
            Log.d(TAG, "通信设备变更 → id=${dev?.id} type=${dev?.type} 命中目标=$hit")
            if (hit) {
                SessionState.inputDevice.value =
                    "蓝牙通话麦" + if (targetName.isNullOrBlank()) "" else " · $targetName"
                /* 链路确认后补上通路键：建链要 5~8 秒，这之前 currentMicKey 是空的
                   （不确定音源是哪支麦），声纹会暂停判定。这里一旦确认就恢复。 */
                val keyName = btSinkProductName ?: targetName
                if (!keyName.isNullOrBlank()) {
                    currentMicKey = MicRoute.keyOf(target.type, keyName)
                }
                /* 会话记录里也要补上 —— 建链确认晚于会话创建，不补的话
                   这条会话永远停在"通路未知"，历史音频补转时用不上声纹。 */
                val sid = sessionId
                if (sid > 0L) {
                    runCatching { db.sessionDao().updateMicKeyBlocking(sid, currentMicKey) }
                }
            }
        }
        commDeviceListener = listener
        runCatching {
            audioManager.addOnCommunicationDeviceChangedListener(
                ContextCompat.getMainExecutor(this),
                listener,
            )
        }.onFailure { Log.w(TAG, "注册通信设备监听失败", it) }
    }

    /**
     * 退出蓝牙路由。必须成对调用 —— 留在 MODE_IN_COMMUNICATION 会把全机音频
     * 按通话处理（媒体音量、A2DP 编码都会变），用户放音乐会受影响。
     */
    private fun exitBluetoothRoute() {
        commDeviceListener?.let { l ->
            runCatching { audioManager.removeOnCommunicationDeviceChangedListener(l) }
            commDeviceListener = null
        }
        btRouteConfirmed.set(null)

        if (!targetIsBluetooth) {
            /* 旧版在这里直接 return，会留下一个洞：某个分支把 targetIsBluetooth
               改成 false 而 mode 还停在 IN_COMMUNICATION，全机音频就被一直按通话处理。
               所以只要是我们自己动过 mode，无论标志如何都要还原。 */
            if (selfHoldsCommMode) {
                runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
                selfHoldsCommMode = false
            }
            return
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            selfHoldsCommMode = false
            Log.d(TAG, "已退出蓝牙音频路由")
        }.onFailure { Log.w(TAG, "退出蓝牙音频路由失败", it) }
        targetIsBluetooth = false
    }

    /**
     * 把当前录音设备翻译成一句人话，落库并显示在监听页。
     *
     * 为什么要显示到「产品名」这一级：接了领夹麦之后，用户需要确认
     * 「这次到底走的是外接麦还是手机内置」—— 两者的信噪比差 20dB 以上，
     * 直接决定说话人能不能靠能量差判出来。系统不主动告诉用户，只能我们自己说。
     */
    private fun describeInputDevice(dev: AudioDeviceInfo): String = MicRoute.describe(dev)

    private fun isAllZero(buf: ShortArray, n: Int): Boolean {        for (i in 0 until n) {
            if (buf[i].toInt() != 0) return false
        }
        return true
    }

    // ------------------------------------------------------------------ 转写

    private fun asrLoop() {
        // 同 encodeLoop：只靠哨兵退出。盯 running 会在采集线程 flush 尾段之前就跑掉
        while (true) {
            val seg = try {
                asrQueue.take()
            } catch (_: InterruptedException) {
                break
            }
            if (seg === SENTINEL) break

            SessionState.queueDepth.value = asrQueue.size
            val startWall = System.currentTimeMillis()
            val engineLocal = engine ?: continue
            // 注意：不能用 getOrElse { continue } —— inline lambda 里的 continue 是实验特性
            val result = runCatching { engineLocal.transcribe(seg.samples, SAMPLE_RATE) }
                .onFailure { Log.e(TAG, "transcribe failed", it) }
                .getOrNull() ?: continue
            val text = result.text.trim()
            if (text.isEmpty()) continue

            // ---- 声纹判定：在全部档案里找最像的一个 ----
            // 太短的句子不判：embedding 不稳定，猜错比不猜更糟（用户会误信标签）
            var autoTag = TranscriptEntity.AUTO_NONE
            var score = 0f
            var label: String? = null
            val durSec = seg.samples.size.toFloat() / SAMPLE_RATE
            /* 只跟**本条录音通路**的档案比。跨通路的相似度落在 0.2~0.3，
               和"陌生人"的分数完全重叠（2026-11-11 实测），照样比等于随机落名字。
               通路未知（空串）时同样不判 —— 不知道音从哪支麦来，就不该认人。 */
            val usableProfiles = if (spkEnabled && speaker != null && durSec >= VoiceprintStore.MIN_SAMPLES_SEC) {
                VoiceprintStore.eligible(spkProfiles, currentMicKey)
            } else {
                emptyList()
            }
            if (spkEnabled && speaker != null && durSec >= VoiceprintStore.MIN_SAMPLES_SEC &&
                usableProfiles.isEmpty()
            ) {
                /* 每句都打日志会刷屏，但这条信息是排查"为什么一句都没标"的唯一线索，
                   压成 debug 级别、只记键值，够定位又不淹日志。 */
                Log.d(
                    TAG,
                    "声纹跳过：当前麦「${MicRoute.keyLabel(currentMicKey)}」，无同通路档案" +
                        "（共 ${spkProfiles.size} 份，用的麦：${spkProfiles.map { MicRoute.keyLabel(it.micKey) }}）",
                )
            }
            if (usableProfiles.isNotEmpty()) {
                val emb = runCatching { speaker?.embed(seg.samples) }
                    .onFailure { Log.e(TAG, "embed failed", it) }
                    .getOrNull()
                if (emb != null) {
                    val m = VoiceprintStore.match(usableProfiles, emb)
                    score = m?.score ?: 0f
                    if (m != null && score >= spkThreshold) {
                        // 命中某个具名档案：名字走 Person，不走档案里的名字快照 ——
                        // 档案的 name 是建档时写的，用户改名后不会跟着变。
                        val person = m.personId?.let { pid -> persons.firstOrNull { it.id == pid } }
                        label = person?.name ?: m.name
                        // 是本人就标 AUTO_ME，否则标 AUTO_NAMED。判据是 Person.isSelf，
                        // 不再比名字字符串（改名不该改变统计口径）。
                        autoTag = if (person?.isSelf == true) {
                            TranscriptEntity.AUTO_ME
                        } else {
                            TranscriptEntity.AUTO_NAMED
                        }
                    } else {
                        /* 谁都对不上 ≠ 是别人。
                           远场录音下本人相似度也会掉到 0.4~0.5，硬标「他人」等于把
                           猜测写成事实。这份转写会进长期档案被反复引用，错一次就被
                           永久记住 —— 所以留空，让下游知道「这里不确定」。 */
                        autoTag = TranscriptEntity.AUTO_UNKNOWN
                        label = null
                    }
                }
            }

            /* ---- 基频兜底判男女 ----
               声纹在远场实测命中 0 句（相似度 0.35，阈值 0.62），所以真正起作用的
               是这一路。判据是物理量（声带振动频率），不依赖任何模板。
               11-09 实测双峰：男 100–160Hz / 女 200–280Hz，分离度 3.02。
               ⚠️ 落在两个阈值之间的一律留 null —— 那个区间有 15.6% 判不准，
               宁可空着让你标一次，也不能把一半的句子猜成某个人。 */
            var f0 = 0
            if (label == null && f0Enabled) {
                val hz = PitchDetector.estimate(seg.samples, SAMPLE_RATE)
                if (hz != null) {
                    f0 = hz.toInt()
                    when {
                        hz <= f0MaleMax -> {
                            /* 只在该性别恰好一个人时落名字。0 人 = 还没设性别，
                               ≥2 人 = 物理上分不开 —— 两种情况都留未判，
                               而不是像以前那样落一个「我 / 对方」这样的假人。 */
                            label = maleSoleName
                            autoTag = if (label != null) {
                                TranscriptEntity.AUTO_F0_GUESS
                            } else {
                                TranscriptEntity.AUTO_UNKNOWN
                            }
                        }
                        hz >= f0FemaleMin -> {
                            label = femaleSoleName
                            autoTag = if (label != null) {
                                TranscriptEntity.AUTO_F0_GUESS
                            } else {
                                TranscriptEntity.AUTO_UNKNOWN
                            }
                        }
                    }
                }
            }

            val entity = TranscriptEntity(
                sessionId = sessionId,
                startMs = seg.startSample * 1000L / SAMPLE_RATE,
                endMs = (seg.startSample + seg.samples.size) * 1000L / SAMPLE_RATE,
                text = text,
                lang = result.lang,
                /* ⚠️ 这里以前写 `System.currentTimeMillis()` —— 那是「转写完成时刻」，
                   队列积压时会整体偏晚（ASR 单线程，一句 135~208ms，积压几十句就是十几秒）。
                   实时转写的时间戳必须是**说话时刻**，所以取段产出时刻回推。 */
                wallClock = seg.wallStart.takeIf { it > 0L } ?: System.currentTimeMillis(),
                speakerName = label,
                speakerAuto = autoTag,
                /* 机器判出的名字快照。和 speakerName 分开存，是为了在用户
                   改判之后仍能回答「机器当时判的是谁」—— 那是改判率的唯一真值来源。 */
                autoSpeakerName = label,
                speakerScore = score,
                f0Hz = f0,
            )
            // 裸线程里必须用同步 DAO，见 encodeLoop 里的说明
            val id = runCatching { db.transcriptDao().insertBlocking(entity) }
                .getOrElse { -1L }

            SessionState.addItem(
                SessionState.Item(
                    id = id,
                    wallClock = entity.wallClock,
                    relMs = entity.startMs,
                    text = text,
                    lang = entity.lang,
                    durationMs = seg.durationMs,
                    speakerName = label,
                    speakerAuto = autoTag,
                ),
            )
            Log.d(
                TAG,
                "ASR ${seg.durationMs}ms → ${System.currentTimeMillis() - startWall}ms " +
                    "[spk=${label ?: "-"} ${if (score > 0) "%.2f".format(score) else ""}]: $text",
            )
        }
    }

    // ------------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("已识别 ${SessionState.totalSegments.value} 段 · 点击查看")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.btn_stop), stopIntent)
            .build()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------------ 持久化

    /**
     * 把一段毫秒数写成人类能读的跨度（`45 秒` / `3 分 20 秒` / `2 小时 33 分`）。
     *
     * 为什么要专门写：中断说明里「中断 9180000 毫秒」对用户等于没写，
     * 而这个数字正是他判断「哪段录音废了」的唯一线索。
     */
    private fun fmtSpan(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        if (totalSec < 60L) return "$totalSec 秒"
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return when {
            h > 0L -> "$h 小时 $m 分"
            s == 0L -> "$m 分"
            else -> "$m 分 $s 秒"
        }
    }

    private fun persistInterrupt(kind: String, detail: String) {
        /* 同步 DAO。这个方法会从采集线程、编码线程、asr 线程里调用 ——
           全都在裸 Thread 上，套 runBlocking 调挂起 DAO 正是本次事故的成因。 */
        runCatching {
            db.interruptDao().insertBlocking(
                InterruptEntity(
                    wallClock = System.currentTimeMillis(),
                    kind = kind,
                    detail = detail,
                ),
            )
        }
    }

    private val SENTINEL = VadSegmenter.Segment(-1L, FloatArray(0))
}

/** 数据库单例，避免 Service 和界面各开一个连接 */
object VoiceLogDatabaseHolder {
    @Volatile
    private var instance: VoiceLogDatabase? = null

    fun get(context: Context): VoiceLogDatabase =
        instance ?: synchronized(this) {
            instance ?: androidx.room.Room.databaseBuilder(
                context.applicationContext,
                VoiceLogDatabase::class.java,
                "voicelog.db",
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8,
            ).build().also { instance = it }
        }
}
