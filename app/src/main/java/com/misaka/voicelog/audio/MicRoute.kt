package com.misaka.voicelog.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.misaka.voicelog.core.AppPrefs
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * 录音通路的「身份」与建立方式。
 *
 * 为什么要有这个概念：声纹（说话人 embedding）对**信道**极其敏感。
 * 2026-11-11 实测，同一个人：
 *   · 同一场录音里两两对比 → 相似度 0.65
 *   · 拿 11-07 手机麦录的档案去比耳机麦录的句子 → 0.25
 * 而这个人跟另一个人之间的相似度是 0.24。也就是说**换麦之后，"自己"和"别人"
 * 一样远**，声纹等于失效。这不是模型不行，是拿错了参照物。
 *
 * 所以声纹档案必须记住「它是在哪条通路上录的」，判定时只跟同一通路的档案比。
 * 通路标识用「类型 + 产品名」而不是 AudioDeviceInfo.id —— 后者在耳机重连、
 * 系统重启后会换号，拿它当长期键会在某天静默全部失配。
 */
object MicRoute {

    const val SAMPLE_RATE = 16_000

    /** 内置麦归一成这个值：同型号手机的产品名可能随系统版本变，而内置麦本来就只有一路 */
    const val KEY_BUILTIN = "builtin"

    /** 从没成功确定过通路。任何档案都不该跟它匹配 */
    const val KEY_UNKNOWN = "unknown"

    fun isBluetooth(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

    /**
     * 把设备翻译成一句人话，用于界面和落库。
     *
     * 只回答一个问题：这轮用的是哪个麦。类型 + 产品名足够。
     * 曾经在这里追加过「· 4ch」（dev.channelCounts 报的硬件能力），
     * 但本 App 一律按 16kHz 单声道录，写上去等于告诉用户「这轮是四声道」，比不写更糟。
     */
    fun describe(type: Int, productName: String?): String {
        val typeName = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 设备"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳麦"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话麦"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙 LE 麦"
            else -> "输入设备($type)"
        }
        val name = productName?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
        return if (name == null) typeName else "$typeName · $name"
    }

    fun describe(dev: AudioDeviceInfo): String =
        describe(dev.type, runCatching { dev.productName?.toString() }.getOrNull())

    /**
     * 通路身份键。**同一物理麦、不同采样率必须得到同一个值** ——
     * 系统对同一支内置麦会按采样率上报多条设备，用 id 当键会分裂成好几份档案。
     */
    fun keyOf(type: Int, productName: String?): String {
        /* 产品名保留原样大小写：它同时被用来给人看（设置页要显示「这份档案是用哪支麦录的」），
           而同一支麦的 productName 在系统里是恒定的，不存在大小写漂移的风险。 */
        val n = productName
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: "unknown"
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> KEY_BUILTIN
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "bt|$n"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired|$n"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb|$n"
            else -> "t$type|$n"
        }
    }

    fun keyOf(dev: AudioDeviceInfo): String =
        keyOf(dev.type, runCatching { dev.productName?.toString() }.getOrNull())

    /** 把通路键翻译成人话，用于设置页「这份档案绑在哪支麦上」 */
    fun keyLabel(key: String?): String = when {
        key.isNullOrBlank() -> "来源未知（旧版档案）"
        key == KEY_BUILTIN -> "内置麦克风"
        key == KEY_UNKNOWN -> "未确定"
        key.startsWith("bt|") -> "蓝牙 · " + key.removePrefix("bt|")
        key.startsWith("wired|") -> "有线 · " + key.removePrefix("wired|")
        key.startsWith("usb|") -> "USB · " + key.removePrefix("usb|")
        else -> key
    }

    /**
     * 找到可用于通话的蓝牙设备（sink 侧），找不到返回 null。
     *
     * 必须查 `availableCommunicationDevices`，不能查 `getDevices(GET_DEVICES_INPUTS)`：
     * 前者是能当通话输出的设备（sink），`setCommunicationDevice` 只接受这种；
     * 后者是麦克风（source），传进去必被拒 —— 这是 2026-11-10 排查的核心结论。
     */
    fun findBluetoothSink(am: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val all = runCatching { am.availableCommunicationDevices }.getOrNull().orEmpty()
        return all.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: all.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
    }

    /**
     * 把绑定的那支麦找出来（AudioDeviceInfo）。id 失效时按名字兜底。
     *
     * id 不稳定：蓝牙耳机重连、重装应用、系统重启后都可能换号。
     * 只按 id 找的话，用户拔插一次耳机就得回设置里重新选一遍。
     */
    fun resolveBound(am: AudioManager, ctx: Context): AudioDeviceInfo? {
        val wantId = AppPrefs.inputDeviceId(ctx)
        if (wantId == 0) return null
        val all = runCatching { am.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrNull().orEmpty()
        all.firstOrNull { it.id == wantId }?.let { return it }
        val savedName = AppPrefs.inputDeviceName(ctx)
        if (savedName.isBlank()) return null
        val byName = all.firstOrNull { describe(it) == savedName } ?: return null
        Log.w(TAG, "偏好设备 id=$wantId 已失效，按名字「$savedName」匹配到新 id=${byName.id}")
        AppPrefs.setInputDevice(ctx, byName.id, savedName)
        return byName
    }

    private const val TAG = "MicRoute"
}

/**
 * 按「设置里绑定的那支麦」录一段固定时长的音频。
 *
 * 存在的理由：声纹注册必须和日常监听走**同一条通路**，否则档案作废（见 [MicRoute]）。
 * 原来的注册流程写死了 `VOICE_RECOGNITION` + 内置麦，用户戴耳机监听时，
 * 档案与实测永远对不上。
 *
 * 蓝牙的建立顺序照抄官方 HFP 录音模板，和 [com.misaka.voicelog.service.CaptureService]
 * 里那段是同一套逻辑，但这里的目的是「录完就退」，不承担监听服务的状态机，
 * 所以没有合并成一个类 —— 强行合并会把全零容忍、中断记录这些服务语义塞进来。
 *
 * 调用顺序必须是：open() → awaitBluetoothRoute()（蓝牙才需要）→ capture() → close()。
 * 而且 **capture 必须在 route 确认之后**：系统只在你已经开始录音之后才处理
 * 通信设备请求，所以 open() 里就先 startRecording，用丢弃前若干帧的方式
 * 把建链时间吃掉。
 */
class BoundMicRecorder(private val ctx: Context) {

    private val am = ctx.getSystemService(AudioManager::class.java)
    private var rec: AudioRecord? = null
    private var sink: AudioDeviceInfo? = null
    private var listener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private val confirmed = AtomicBoolean(false)
    private var holdsCommMode = false

    /**
     * 强制把通路键记成「内置麦」。
     *
     * 用在「用户绑的是蓝牙，但这副耳机没有通话通道」的情况：此时系统会用内置麦
     * 采音，而 `routedDevice` 仍会报蓝牙（音频策略的默认输入就是它）——
     * 这正是 2026-11-10 那次"看起来在用耳机、实际是手机麦"的同一个骗局。
     * 监听侧遇到这种情况会明确回落到内置麦，注册侧必须跟着记成 builtin，
     * 否则两边通路键对不上，档案白录。
     */
    private var forceBuiltinKey = false

    /** 本次录音的通路标识，写进声纹档案。open() 之后才有意义 */
    var micKey: String = MicRoute.KEY_UNKNOWN
        private set

    /** 给人看的通路名，如「蓝牙通话麦 · OpenRun by Shokz」 */
    var displayName: String = ""
        private set

    /** 这支麦是不是走了蓝牙通话链路 —— 是的话必须调 awaitBluetoothRoute */
    var wantsBluetooth: Boolean = false
        private set

    /** 需要告诉用户的话（耳机没连、只有音乐通道等），正常时为空 */
    var note: String? = null
        private set

    /** 打开麦克风并开始接收数据。返回 false 表示彻底打不开，不用再谈通不通路 */
    fun open(): Boolean {
        val bound = runCatching { MicRoute.resolveBound(am, ctx) }.getOrNull()
        val wantName = AppPrefs.inputDeviceName(ctx)
        val boundIsBt = bound != null && MicRoute.isBluetooth(bound.type)

        if (bound == null && AppPrefs.inputDeviceId(ctx) != 0) {
            note = "绑定的「$wantName」当前没连上，这次用手机麦录"
        }

        var source = MediaRecorder.AudioSource.VOICE_RECOGNITION
        if (boundIsBt) {
            val s = runCatching { MicRoute.findBluetoothSink(am) }.getOrNull()
            if (s == null) {
                /* 耳机只连了音乐通道（A2DP）。这种情况监听时也会回落手机麦，
                   所以这里同样用手机麦录、通路键记为 builtin，两边仍然一致。 */
                note = "「$wantName」只有音乐通道、没有通话通道，这次用手机麦录"
                forceBuiltinKey = true
            } else {
                sink = s
                wantsBluetooth = true
                /* 蓝牙麦只有走 VOICE_COMMUNICATION（通信路径）才会被系统真正接通；
                   VOICE_RECOGNITION 会被路由回内置麦 —— 2026-11-10 实测：
                   路由信息报蓝牙，但实际 PCM 来自内置麦。 */
                source = MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }
        }

        val minBuf = AudioRecord.getMinBufferSize(
            MicRoute.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            note = "系统不支持 16kHz 单声道录音"
            return false
        }
        val bufSize = maxOf(minBuf, MicRoute.SAMPLE_RATE) // 约 1 秒，边读边消费足够

        val r = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(MicRoute.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufSize)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(
                    source,
                    MicRoute.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            }
        }.getOrNull()

        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { r?.release() }
            note = "麦克风打不开，可能被其他应用（通话、录音）占用"
            return false
        }
        rec = r

        /* 访问蓝牙设备需要 BLUETOOTH_CONNECT（Android 12+）。没授权时
           setCommunicationDevice 会抛 SecurityException，这里提前挡一下，
           给用户一句能照做的提示，而不是让整段录音莫名其妙失败。 */
        if (wantsBluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = runCatching {
                ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (!granted) {
                note = "没有「附近的设备」权限，系统不让用蓝牙麦克风。到 设置 → 应用权限 里允许后重试"
                runCatching { r.release() }
                rec = null
                wantsBluetooth = false
                sink = null
                return false
            }
        }

        // 必须先开录，系统才会处理下面的通信设备请求
        runCatching { r.startRecording() }.onFailure {
            Log.w(TAG, "startRecording 失败", it)
            note = "开始录音失败：" + (it.message ?: "未知原因")
            runCatching { r.release() }
            rec = null
            return false
        }

        if (wantsBluetooth && sink != null) requestBluetoothRoute(sink!!)

        return true
    }

    /**
     * 等蓝牙链路真正建立。返回 false 代表没接上，本次录音不能用作声纹样本。
     *
     * 小米实测建链要 5~8 秒（Pixel 约 1~2 秒），所以默认给 10 秒。
     * 判定只认系统侧的 communicationDevice 或变更回调 —— **绝不能看 isBluetoothScoOn**，
     * 那是自己刚设进去的请求标志，一设就是 true。
     */
    fun awaitBluetoothRoute(timeoutMs: Long = 10_000L): Boolean {
        val target = sink ?: return true // 不是蓝牙，不用等
        val deadline = System.currentTimeMillis() + timeoutMs
        val swallow = ShortArray(1024)
        while (System.currentTimeMillis() < deadline) {
            if (confirmed.get()) break
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cur = runCatching { am.communicationDevice?.id }.getOrNull()
                if (cur != null && cur == target.id) break
            } else {
                @Suppress("DEPRECATION")
                if (runCatching { am.isBluetoothScoOn }.getOrDefault(false)) break
            }
            /* 边等边把数据读掉。录音从 open() 起就一直在产生数据，
               建链期间不读会填满缓冲区触发 overrun；这些帧本来就属于旧通路，
               丢掉正好。 */
            runCatching { rec?.read(swallow, 0, swallow.size) }
            runCatching { Thread.sleep(150) }
        }

        val ok = confirmed.get() || runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.communicationDevice?.id == target.id
            } else {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn
            }
        }.getOrDefault(false)

        if (!ok) {
            note = "蓝牙麦克风没能接通（系统的通话通道没建起来），这次不算数"
            micKey = MicRoute.KEY_UNKNOWN
            displayName = ""
            return false
        }

        micKey = MicRoute.keyOf(target)
        displayName = "蓝牙通话麦 · " + runCatching { target.productName?.toString() }.getOrNull()
        return true
    }

    /**
     * 录 [seconds] 秒并返回浮点 PCM（-1~1）。
     *
     * 会先丢掉开头 0.3 秒：蓝牙切通过程里前几帧常是旧通路或空数据，
     * 留着会让 embedding 掺进手机麦的声音。
     */
    fun capture(seconds: Float): FloatArray? {
        val r = rec ?: return null
        if (forceBuiltinKey) {
            /* 耳机没通话通道，系统实际在用内置麦；routedDevice 的谎话不能信 */
            micKey = MicRoute.KEY_BUILTIN
            displayName = "内置麦克风"
        } else if (!wantsBluetooth || sink == null) {
            /* 非蓝牙通路：以系统**实际**路由为准，不拿用户选的那个当事实。
               内置麦归一成 builtin，所以哪怕系统报的 productName 变了也仍然匹配。 */
            val routed = runCatching { r.routedDevice }.getOrNull()
            if (routed != null) {
                micKey = MicRoute.keyOf(routed)
                displayName = MicRoute.describe(routed)
            } else {
                // 读不到实际路由时只能退到「内置麦」这个保守值 —— 不猜成蓝牙
                micKey = MicRoute.KEY_BUILTIN
                displayName = "内置麦克风"
            }
        }

        val total = (MicRoute.SAMPLE_RATE * seconds).toInt()
        val buf = ShortArray(total)
        val drop = (MicRoute.SAMPLE_RATE * 0.3f).toInt()
        val tmp = ShortArray(2048)
        var dropped = 0
        while (dropped < drop) {
            val n = runCatching { r.read(tmp, 0, minOf(tmp.size, drop - dropped)) }
                .getOrDefault(0)
            if (n <= 0) break
            dropped += n
        }

        var read = 0
        while (read < total) {
            val n = runCatching { r.read(buf, read, total - read) }.getOrDefault(0)
            if (n <= 0) break
            read += n
        }
        if (read < MicRoute.SAMPLE_RATE) return null // 不足 1 秒

        val floats = FloatArray(read) { buf[it] / 32768.0f }
        var energy = 0.0
        for (f in floats) energy += f.toDouble() * f
        if (sqrt(energy / floats.size) < 5e-4) return null // 基本是静音，多半麦克风被占用

        return floats
    }

    fun close() {
        listener?.let { l ->
            runCatching { am.removeOnCommunicationDeviceChangedListener(l) }
            listener = null
        }
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        rec = null

        if (holdsCommMode) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    am.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    am.stopBluetoothSco()
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = false
                }
                am.mode = AudioManager.MODE_NORMAL
            }.onFailure { Log.w(TAG, "退出通话模式失败", it) }
            holdsCommMode = false
        }
        confirmed.set(false)
        sink = null
        wantsBluetooth = false
    }

    private fun requestBluetoothRoute(target: AudioDeviceInfo) {
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
            .onFailure { Log.w(TAG, "设置通话模式失败", it) }
        holdsCommMode = true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            runCatching {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
            }.onFailure { Log.w(TAG, "startBluetoothSco 失败", it) }
            return
        }

        val targetId = target.id
        val l = AudioManager.OnCommunicationDeviceChangedListener { dev ->
            val hit = dev != null && dev.id == targetId
            confirmed.set(hit)
            Log.d(TAG, "通信设备变更 → id=${dev?.id} 命中目标=$hit")
        }
        listener = l
        runCatching {
            am.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(ctx), l)
        }.onFailure { Log.w(TAG, "注册通信设备监听失败", it) }

        val accepted = runCatching { am.setCommunicationDevice(target) }
            .onFailure { Log.w(TAG, "setCommunicationDevice 抛异常", it) }
            .getOrDefault(false)
        Log.d(TAG, "请求通信设备 ${MicRoute.describe(target)} accepted=$accepted")
        if (!accepted) {
            note = "系统拒绝了蓝牙通话路由"
        }
    }

    private companion object {
        const val TAG = "BoundMicRecorder"
    }
}
