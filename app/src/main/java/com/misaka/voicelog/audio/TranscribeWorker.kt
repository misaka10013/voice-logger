package com.misaka.voicelog.audio

import android.content.Context
import android.util.Log
import com.misaka.voicelog.core.AppPrefs
import com.misaka.voicelog.core.ModelPaths
import com.misaka.voicelog.core.VoiceprintStore
import com.misaka.voicelog.data.SegmentEntity
import com.misaka.voicelog.service.VoiceLogDatabaseHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 音频素材的落盘位置与清理。
 *
 * 统一放**内部存储** `files/audio/`，不放 `/sdcard/Android/data/`。
 * 原因不是洁癖：v2 已经踩过 —— adb 往外部目录写进去的文件，FUSE 视图不同步，
 * `File.exists()` 返回 false，App 明明看到文件就在那儿也读不到。内部存储没有这层缓存。
 */
object AudioStore {

    /** 每段一个文件，按会话分目录，方便整轮清理 */
    fun dirFor(ctx: Context, sessionId: Long): File =
        File(File(ctx.filesDir, "audio"), sessionId.toString())

    fun fileFor(ctx: Context, sessionId: Long, segmentId: Long): File =
        File(dirFor(ctx, sessionId), "$segmentId.m4a")

    fun totalBytes(ctx: Context): Long {
        val root = File(ctx.filesDir, "audio")
        if (!root.exists()) return 0L
        var sum = 0L
        root.walkTopDown().forEach { if (it.isFile) sum += it.length() }
        return sum
    }

    /** 一轮会话的音频占多少字节。用于「仅清空音频」前告诉用户能腾出多少空间 */
    fun sessionBytes(ctx: Context, sessionId: Long): Long {
        val dir = dirFor(ctx, sessionId)
        if (!dir.exists()) return 0L
        var sum = 0L
        dir.listFiles()?.forEach { if (it.isFile) sum += it.length() }
        return sum
    }

    /**
     * 物理删掉一轮会话的全部音频文件，返回释放的字节数。
     *
     * **只删文件，不碰数据库。** 段落记录的软删与句子解绑由调用方负责——
     * 因为「仅清空音频」和「删会话」对记录的处理不一样（前者保留文字，
     * 后者连文字一起进回收站），把两件事揉在一个函数里迟早会串味。
     */
    fun deleteSessionAudio(ctx: Context, sessionId: Long): Long {
        val dir = dirFor(ctx, sessionId)
        var freed = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isFile) {
                freed += f.length()
                runCatching { f.delete() }
            }
        }
        runCatching { dir.delete() }
        pruneEmptyDirs(ctx)
        return freed
    }

    /** 清掉空目录（转写后删音频会留下空壳，久了会积一堆） */
    fun pruneEmptyDirs(ctx: Context) {
        val root = File(ctx.filesDir, "audio")
        if (!root.exists()) return
        root.listFiles()?.forEach { d ->
            if (d.isDirectory && (d.listFiles()?.isEmpty() != false)) d.delete()
        }
    }
}

/**
 * 批量转写：把落盘的音频段变成文字。
 *
 * ## 它为什么独立于采集服务
 * 采集与转写解耦是 v3 的核心目的：监听时**不加载任何模型**（省 309MB 内存和持续算力），
 * 模型只在用户点「转写」时加载、跑完立刻释放。
 *
 * ## 为什么不做成前台服务
 * 转写是**用户主动触发且看着进度**的动作（不是长期后台任务）。放前台服务需要额外
 * 一条通知和一个常驻进程，收益不大。代价是中途退出会中断 —— 但已完成的段不会丢，
 * 未完成的还是 PENDING，下次点继续即可（幂等，可重入）。
 *
 * ⚠️ 全进程单例：同一时刻只允许一个转写任务，否则两个 ASR 实例会抢内存。
 */
object TranscribeWorker {

    private const val TAG = "TranscribeWorker"

    data class Progress(
        val running: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val failed: Int = 0,
        val lastText: String = "",
        val finished: Boolean = false,
    ) {
        val percent: Int get() = if (total <= 0) 0 else (done * 100 / total)
    }

    private val _state = MutableStateFlow(Progress())
    val state: StateFlow<Progress> = _state

    /**
     * 跑一批。
     *
     * @param sessionId 限定某一轮会话；传 [ALL_SESSIONS] 表示全部待转写队列
     * @param deleteAudioAfter 转写成功后是否立即删音频。默认 **false** ——
     *        保留才能回听核对，删是用户主动决定的事（素材页有手动清与按天自动清）
     * @return 成功转出的句子数
     */
    suspend fun run(
        ctx: Context,
        sessionId: Long = ALL_SESSIONS,
        deleteAudioAfter: Boolean = false,
    ): Int = withContext(Dispatchers.Default) {
        if (_state.value.running) {
            Log.w(TAG, "已有转写任务在跑，忽略本次")
            return@withContext 0
        }

        val db = VoiceLogDatabaseHolder.get(ctx)
        val segDao = db.segmentDao()
        val trDao = db.transcriptDao()
        val snDao = db.sessionDao()

        // 进程上次被杀留下的僵尸段：不重置的话它们既不在队列也不在完成态，永远卡住
        val revived = segDao.resetRunning()
        if (revived > 0) Log.w(TAG, "重置 $revived 个残留的 RUNNING 段")

        val queue = if (sessionId == ALL_SESSIONS) {
            segDao.pending(MAX_BATCH)
        } else {
            segDao.listBySession(sessionId).filter { it.status == SegmentEntity.ST_PENDING }
        }
        if (queue.isEmpty()) {
            _state.value = Progress(finished = true)
            return@withContext 0
        }

        _state.value = Progress(running = true, total = queue.size)

        // ---- 引擎只在本次任务期间存在，跑完必须释放（这是省内存的全部意义）----
        var engine: com.misaka.voicelog.asr.AsrEngine? = null
        var speaker: com.misaka.voicelog.asr.SpeakerEngine? = null
        val profiles = VoiceprintStore.profiles(ctx)
        val threshold = VoiceprintStore.threshold(ctx)
        val myName = VoiceprintStore.myName(ctx)
        /* 每条会话当时用的录音通路。声纹只认同一通路的档案 ——
           跨通路的相似度与陌生人无异（2026-11-11 实测 0.25 vs 0.24），
           混着比等于随机落名字。空 map 表示读失败，那就整批不做声纹判定。 */
        val micKeys = runCatching { snDao.allMicKeys().associate { it.id to it.micKey } }
            .getOrDefault(emptyMap())
        /* F0 判男女的配置。整批读一次：同一批转写里前后段必须用同一套阈值，
           否则事后无法解释「为什么这一段判了、那一段没判」。 */
        val f0On = AppPrefs.f0Enabled(ctx)
        val f0MaleMax = AppPrefs.f0MaleMax(ctx)
        val f0FemaleMin = AppPrefs.f0FemaleMin(ctx)
        /* 映射到听标那两个名字，单一数据源 */
        val f0MaleName = AppPrefs.abA(ctx)
        val f0FemaleName = AppPrefs.abB(ctx)

        var okCount = 0
        var failCount = 0
        try {
            if (ModelPaths.ready(ctx)) {
                engine = com.misaka.voicelog.asr.SenseVoiceEngine(
                    modelPath = ModelPaths.asrModel(ctx),
                    tokensPath = ModelPaths.asrTokens(ctx),
                    language = com.misaka.voicelog.core.AppPrefs.asrLang(ctx),
                    useItn = com.misaka.voicelog.core.AppPrefs.asrItn(ctx),
                )
            }
            if (ModelPaths.spkReady(ctx) && profiles.isNotEmpty()) {
                speaker = com.misaka.voicelog.asr.SpeakerEngine(ModelPaths.spkModel(ctx))
            }

            for ((idx, seg) in queue.withIndex()) {
                segDao.updateStatus(seg.id, SegmentEntity.ST_RUNNING)

                val path = resolveFile(ctx, seg)
                if (path == null) {
                    segDao.updateStatus(
                        seg.id,
                        SegmentEntity.ST_FAILED,
                        if (seg.filePath.isBlank()) "音频文件缺失（当时未编码成功）" else "音频文件已被删除",
                    )
                    failCount++
                    _state.value = _state.value.copy(done = idx + 1, failed = failCount)
                    continue
                }
                // 路径是推算出来的就顺手补回库，下次不必再推一次
                if (path != seg.filePath) {
                    runCatching { segDao.updateFile(seg.id, path, File(path).length()) }
                }

                val samples = AudioDecoder.decode(path)
                if (samples.isEmpty()) {
                    // 文件在但解不出声：标记失败而不是静默跳过，否则它会一直躺在待转队列里
                    segDao.updateStatus(seg.id, SegmentEntity.ST_FAILED, "音频无法解码（文件可能损坏）")
                    failCount++
                    _state.value = _state.value.copy(done = idx + 1, failed = failCount)
                    continue
                }

                val asr = engine?.transcribe(samples)
                val text = (asr?.text ?: "").trim()
                if (text.isEmpty()) {
                    // 有声音但识别不出内容（咳嗽、噪声、外语噪音）——算完成不算失败，
                    // 但也不生成空句子，避免记录里出现一串空白行
                    segDao.markDone(seg.id, SegmentEntity.ST_DONE, 0L)
                    if (deleteAudioAfter) deleteAudio(ctx, seg)
                    okCount++
                    _state.value = _state.value.copy(done = idx + 1, lastText = "")
                    continue
                }

                // ---- 声纹判定 ----
                var label: String? = null
                var auto = com.misaka.voicelog.data.TranscriptEntity.AUTO_NONE
                var score = 0f
                val durSec = samples.size.toFloat() / VadSegmenter.SAMPLE_RATE
                val segMicKey = micKeys[seg.sessionId].orEmpty()
                /* 只拿本条音频所在通路录的档案来比。通路为空 = 老数据或当时没确定，
                   此时不判 —— 用别的通路的档案去比，分数落在 0.2~0.3 的噪声区，
                   再过一个阈值就变成随机落名字，比"未判"更有害。 */
                val usableProfiles = VoiceprintStore.eligible(profiles, segMicKey)
                if (speaker != null && usableProfiles.isNotEmpty() &&
                    durSec >= VoiceprintStore.MIN_SAMPLES_SEC
                ) {
                    val emb = runCatching { speaker.embed(samples) }.getOrNull()
                    if (emb != null) {
                        val best = VoiceprintStore.match(usableProfiles, emb)
                        score = best?.score ?: 0f
                        if (best != null && score >= threshold) {
                            label = best.name
                            /* 「我」和「某个具名档案」要分开记：前者是自我识别，
                               后者是熟人识别。混成一个值，日后就没法单独评估自己的召回率。 */
                            auto = if (best.name == myName) {
                                com.misaka.voicelog.data.TranscriptEntity.AUTO_ME
                            } else {
                                com.misaka.voicelog.data.TranscriptEntity.AUTO_NAMED
                            }
                        } else {
                            /* 同上：没把握就不给名字。AUTO_UNKNOWN 与 AUTO_NONE
                               分开记，是为了事后能算出「判过但没敢下结论」有多少，
                               进而评估声纹真实召回率 —— 之前全标「他人」时这个数
                               根本统计不出来，196 句全被误当成「确定是别人」。 */
                            label = null
                            auto = com.misaka.voicelog.data.TranscriptEntity.AUTO_UNKNOWN
                        }
                    }
                }

                /* ---- 基频兜底判男女（声纹没命中才走这里）----
                   远场声纹相似度实测只有 0.35，对阈值 0.62 命中 0 句，所以真正干活的
                   是这一路。判据是声带振动频率这个物理量，不需要任何模板。
                   ⚠️ 两阈值之间（默认 160–200Hz）留空：那个区间 15.6% 判不准，
                   宁可让你标一次，也不能猜 —— 猜错会被当成事实长期引用。 */
                var f0 = 0
                if (label == null && f0On) {
                    val hz = PitchDetector.estimate(samples, VadSegmenter.SAMPLE_RATE)
                    if (hz != null) {
                        f0 = hz.toInt()
                        when {
                            hz <= f0MaleMax -> {
                                label = f0MaleName
                                /* 记成 F0_GUESS 而不是 NAMED：F0 只能证明「不是女声」，
                                   证不出「具体是谁」。实测男声侧错 3.7%、
                                   女声侧错 42.6%（候选有两个，F0 分不开），
                                   与声纹命中（0.7%）差一个数量级，界面上必须能分开。 */
                                auto = com.misaka.voicelog.data.TranscriptEntity.AUTO_F0_GUESS
                            }
                            hz >= f0FemaleMin -> {
                                label = f0FemaleName
                                auto = com.misaka.voicelog.data.TranscriptEntity.AUTO_F0_GUESS
                            }
                        }
                    }
                }

                /* 按句末标点拆成多行。
                   一段 VAD 音频里经常有好几句话（实测平均每行 1.7 句），
                   不拆开的话「A 说的」和「B 说的」只能挤在同一行，
                   而一行只能有一个说话人 —— 等于永远标不上。
                   时间戳按字符占比分摊，让每行显示的 HH:mm:ss 落在合理位置。 */
                val pieces = splitSentences(text)
                val totalLen = pieces.sumOf { it.length }.coerceAtLeast(1)
                var acc = 0
                var firstTid = 0L
                pieces.forEachIndexed { pi, piece ->
                    val sMs = seg.startMs + if (seg.durMs > 0) seg.durMs * acc / totalLen else 0L
                    val eMs = seg.startMs + if (seg.durMs > 0) seg.durMs * (acc + piece.length) / totalLen else 0L
                    acc += piece.length
                    val tid = trDao.insert(
                        com.misaka.voicelog.data.TranscriptEntity(
                            sessionId = seg.sessionId,
                            startMs = sMs,
                            endMs = eMs,
                            text = piece,
                            lang = asr?.lang ?: "",
                            wallClock = seg.wallClock + (sMs - seg.startMs),
                            speakerName = label,
                            speakerAuto = auto,
                            /* 机器值快照，人工改判不覆盖它（见 TranscriptEntity.autoSpeakerName） */
                            autoSpeakerName = label,
                            speakerScore = score,
                            f0Hz = f0,
                            segmentId = seg.id,
                        ),
                    )
                    if (pi == 0) firstTid = tid
                }
                segDao.markDone(seg.id, SegmentEntity.ST_DONE, firstTid)
                if (deleteAudioAfter) deleteAudio(ctx, seg)

                okCount++
                _state.value = _state.value.copy(done = idx + 1, lastText = text)
            }

            // ---- 回填会话统计（列表页读的是 session.segmentCount，不更新会显示 0 句）----
            queue.map { it.sessionId }.distinct().forEach { sid ->
                val list = trDao.listBySession(sid).filter { it.deletedAt == 0L }
                val speech = list.sumOf { it.endMs - it.startMs }
                snDao.updateStats(sid, list.size, speech)
            }
            AudioStore.pruneEmptyDirs(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "转写中断", e)
            _state.value = _state.value.copy(running = false, failed = failCount + 1, finished = true)
            throw e
        } finally {
            runCatching { engine?.release() }
            runCatching { speaker?.release() }
        }

        _state.value = _state.value.copy(running = false, finished = true)
        Log.i(TAG, "转写完成：成功 $okCount，失败 $failCount，共 ${queue.size}")
        okCount
    }

    /** 断句规则见 [com.misaka.voicelog.core.SentenceSplitter]，两边只此一份实现 */
    private fun splitSentences(raw: String): List<String> =
        com.misaka.voicelog.core.SentenceSplitter.split(raw)

    private fun deleteAudio(ctx: Context, seg: SegmentEntity) {
        runCatching { File(seg.filePath).delete() }
        AudioStore.pruneEmptyDirs(ctx)
    }

    /**
     * 找出这一段对应的真实音频文件。
     *
     * 主路径直接用 [SegmentEntity.filePath]。它为空的记录确实存在（历史上出现过
     * 「文件写成了、路径没回填」的段落），此时按落盘约定推算：
     * `audio/<sessionId>/<segmentId>.m4a` —— 文件名本来就是用 segment.id 命名的
     * （见 `CaptureService.encodeLoop`），这个约定是强一致的，不是靠猜。
     *
     * 直接判失败会让用户真正说过的录音白白丢掉，而文件其实好端端躺在磁盘上。
     */
    private fun resolveFile(ctx: Context, seg: SegmentEntity): String? {
        if (seg.filePath.isNotBlank()) {
            val f = File(seg.filePath)
            if (f.exists() && f.length() > 0L) return seg.filePath
        }
        val guess = AudioStore.fileFor(ctx, seg.sessionId, seg.id)
        return if (guess.exists() && guess.length() > 0L) guess.absolutePath else null
    }

    /**
     * 把某一轮里**还能救**的失败段落重新排进队列。
     *
     * 做这个入口是因为：失败段既不在「待转写」里也不在任何按钮的统计里，
     * 用户只能看着"3 句"和实际听到的 4 段干瞪眼，却没有任何办法重试。
     *
     * ⚠️ 必须**先验音频文件是否还在**再重排。
     * 原来的实现是无差别 `UPDATE ... SET status = 0`，把「音频从没写成功」的段也塞回队列
     * （2026-11-07 那轮 64 段里 63 段属于这种），结果是转一遍又失败、再点再失败，
     * 用户永远得到同一个结果，还以为是自己操作错了。不可恢复的段改用 [purgeUnrecoverable]。
     *
     * @return 真正重排的段数（文件还在的）
     */
    suspend fun retryFailed(ctx: Context, sessionId: Long): Int = withContext(Dispatchers.IO) {
        val dao = VoiceLogDatabaseHolder.get(ctx).segmentDao()
        val recoverable = dao.failedSegments(sessionId)
            .filter { resolveFile(ctx, it) != null }
            .map { it.id }
        if (recoverable.isEmpty()) return@withContext 0
        dao.requeueFailedByIds(recoverable)
    }

    /**
     * 清掉那些**音频已经不在了**的失败段。
     *
     * 它们的音频从来没写进磁盘（编码环节就断了），重试没有任何意义，
     * 留在库里只会让会话永远挂着「N 段失败」，用户每次看到都以为还能补。
     * 走软删，跟其余删除保持一致；音频文件本来就不存在，没有副作用。
     *
     * @return 清掉的段数
     */
    suspend fun purgeUnrecoverable(ctx: Context, sessionId: Long): Int = withContext(Dispatchers.IO) {
        val dao = VoiceLogDatabaseHolder.get(ctx).segmentDao()
        val dead = dao.failedSegments(sessionId)
            .filter { resolveFile(ctx, it) == null }
            .map { it.id }
        if (dead.isEmpty()) return@withContext 0
        dao.softDelete(dead, System.currentTimeMillis())
        dead.size
    }

    /** 这一轮里有多少失败段的音频其实已经不在磁盘上（供界面决定要不要给清理入口） */
    suspend fun countUnrecoverable(ctx: Context, sessionId: Long): Int = withContext(Dispatchers.IO) {
        val dao = VoiceLogDatabaseHolder.get(ctx).segmentDao()
        dao.failedSegments(sessionId).count { resolveFile(ctx, it) == null }
    }

    // ------------------------------------------------------------ 重新转录 / 清空音频

    /**
     * 「重新转录」前的盘点：点下去会毁掉什么，必须先摆给用户看。
     *
     * 三个数字各有用途：
     * · [audioSegs] = 0 时按钮就该是灰的 —— 没有音频，重转等于原地抹掉全部文字
     * · [textRows] 是即将进回收站的句子数
     * · [editedRows] 是其中**用户亲手改过**的句子数，这是真正「会心疼」的部分
     */
    data class RetranscribePlan(
        val textRows: Int,
        val editedRows: Int,
        val audioSegs: Int,
        val audioBytes: Long,
    ) {
        val canRun: Boolean get() = audioSegs > 0
    }

    /** 盘一次（要扫文件系统，只在点按钮/进页面时调，不要放进重组） */
    suspend fun planRetranscribe(ctx: Context, sessionId: Long): RetranscribePlan =
        withContext(Dispatchers.IO) {
            val db = VoiceLogDatabaseHolder.get(ctx)
            val segs = db.segmentDao().listBySession(sessionId)
            val p = RetranscribePlan(
                textRows = db.transcriptDao().countNow(sessionId),
                editedRows = db.transcriptDao().editedCountNow(sessionId),
                audioSegs = segs.count { resolveFile(ctx, it) != null },
                audioBytes = AudioStore.sessionBytes(ctx, sessionId),
            )
            Log.i(TAG, "retranscribe plan session=$sessionId segs=${segs.size} audio=${p.audioSegs} bytes=${p.audioBytes}")
            p
        }

    /**
     * 重新转录一轮：清空文字 → 音频重新排队 → 立刻跑。
     *
     * ## 旧文字为什么是**硬删**而不是软删
     * 一开始打算软删（进回收站、可找回），但软删会留下一个更糟的坑：
     * 回收站是**按会话**恢复的，恢复会话会把 `deletedAt` 全部清零 ——
     * 那一刻重转出来的新文字和旧文字会同时冒出来，同一句话出现两份，
     * 而用户以为自己只是「恢复了一次删除」。
     *
     * 所以这里就是字面意义的「清除」：弹窗里已经写明会清掉多少句、
     * 其中多少句是手改的，且不可恢复 —— 用户点确认时是知情的。
     * 音频原封不动，想再要文字就再转一次。
     *
     * ## 只重排音频还在的段
     * 音频已经不在的段重排进去只会再失败一次，还会把「失败」数字加上去，
     * 让用户以为新配置更差。这类段保持原状。
     *
     * @return 本次成功转出的句子数；0 表示没有可转的音频
     */
    suspend fun retranscribe(ctx: Context, sessionId: Long): Int = withContext(Dispatchers.IO) {
        val db = VoiceLogDatabaseHolder.get(ctx)
        val segDao = db.segmentDao()

        val alive = segDao.listBySession(sessionId).filter { resolveFile(ctx, it) != null }
        if (alive.isEmpty()) return@withContext 0
        if (_state.value.running) {
            Log.w(TAG, "已有转写任务在跑，拒绝重新转录")
            return@withContext 0
        }

        db.transcriptDao().hardDeleteBySession(sessionId)
        segDao.requeueAll(sessionId)
        db.sessionDao().updateStats(sessionId, 0, 0)

        run(ctx, sessionId)
    }

    /**
     * 仅清空音频：删文件、段落软删、句子与段落解绑，**文字全部保留**。
     *
     * 解绑（segmentId = 0）必须做：否则详情页仍会去推算
     * `audio/<sid>/<segId>.m4a`，文件没了就提示「音频已不在本地」——
     * 看起来像故障，而实际是用户自己清的。
     *
     * @return (清掉的段数, 释放的字节数)
     */
    suspend fun clearAudioOnly(ctx: Context, sessionId: Long): Pair<Int, Long> =
        withContext(Dispatchers.IO) {
            val db = VoiceLogDatabaseHolder.get(ctx)
            val segs = db.segmentDao().listBySession(sessionId)
            val freed = AudioStore.deleteSessionAudio(ctx, sessionId)
            if (segs.isNotEmpty()) {
                db.segmentDao().softDelete(segs.map { it.id }, System.currentTimeMillis())
            }
            db.transcriptDao().clearSegmentLinks(sessionId)
            segs.size to freed
        }

    /** 这一轮还有多少段音频在磁盘上（0 = 重新转录按钮该置灰） */
    suspend fun countAudio(ctx: Context, sessionId: Long): Int = withContext(Dispatchers.IO) {
        val db = VoiceLogDatabaseHolder.get(ctx)
        db.segmentDao().listBySession(sessionId).count { resolveFile(ctx, it) != null }
    }

    const val ALL_SESSIONS = -1L
    const val OTHER_LABEL = "他人"

    /** 单次任务上限。一次全转几千段会跑很久，分批更可控，用户也看得到进度 */
    private const val MAX_BATCH = 400
}
