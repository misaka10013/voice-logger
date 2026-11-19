package com.misaka.voicelog.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.misaka.voicelog.core.ModelPaths
import com.misaka.voicelog.data.SegmentEntity
import com.misaka.voicelog.data.SessionEntity
import com.misaka.voicelog.service.VoiceLogDatabaseHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 把外部音频文件导入成一轮可转写的会话。
 *
 * ## 为什么需要它
 * 手机不是唯一的录音设备。录音笔（8 麦阵列、远场降噪）在会议/采访场景的拾音质量
 * 明显好于手机，但它没有能力把音频送进这套系统 —— 尤其搜狗 E1 这类 2024 年已停服的
 * 设备，云转写没了，只剩本地录音能力。导入功能是让这类设备"续命"的唯一低成本路径。
 *
 * ## 数据流
 * `content://` 或 `file://`
 *   → 复制到 app 私有临时目录（外部 Uri 不能随机 seek，且读大文件期间可能被回收）
 *   → 流式解码成 16k 单声道 PCM（[StreamAudioDecoder]）
 *   → Silero VAD 切句
 *   → 每段编码回 m4a 落盘（与实时录音完全同构，转写/回听/删除都复用现有链路）
 *   → 建 session + N 个 segment（source = import）
 *
 * ## 刻意不做的事
 * - **不在这里直接转写**：转写要加载 260MB 模型、跑几分钟，属于用户主动触发的动作。
 *   导入完成后跳到会话详情，那里已经有「继续转写」按钮。
 * - **不改 session 表结构**：区分来源靠 `segment.source`。加字段要 Room 迁移，
 *   而迁移一旦写错就是全库数据事故，不值当。
 */
object AudioFileImporter {

    private const val TAG = "AudioFileImporter"

    data class Progress(
        /** 0~100，解码进度 */
        val percent: Int = 0,
        /** 已切出的语音段数 */
        val segCount: Int = 0,
        /** 阶段说明，如「正在解码 40%」 */
        val stage: String = "准备中",
        val finished: Boolean = false,
        /** 完成后给用户看的一句话结论 */
        val summary: String = "",
    )

    data class Result(
        val sessionId: Long,
        val segments: Int,
        val speechMs: Long,
        /** 归档时间取的是文件修改时间还是当前时间，界面上要说清楚 */
        val archivedByFileTime: Boolean,
    )

    private val _state = MutableStateFlow<Progress?>(null)

    /** null = 当前没有导入任务。非 null 时界面应该显示进度并禁止再点导入 */
    val state: StateFlow<Progress?> = _state

    private const val MAX_BYTES = 4L * 1024 * 1024 * 1024    // 4GB 上限，超过基本是选错文件

    /**
     * 低音量重试时的放大倍数（约 +18dB）。
     *
     * 只在「首轮一段都没切出来」时启用，所以不会影响正常音量的文件 ——
     * 而正常音量的文件被放大反而会削顶失真，那才是真损坏。
     */
    private const val LOW_VOLUME_GAIN = 8f

    /**
     * 执行导入。可取消 —— 取消时会把已建好的会话与音频整轮清掉，不留半成品。
     *
     * @return 成功返回结果；模型未就绪或文件不可读时抛 [IOException]
     */
    suspend fun import(ctx: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        if (!ModelPaths.ready(ctx)) {
            throw IOException("转写模型未就绪，无法切分语音（设置里可检查模型状态）")
        }

        val db = VoiceLogDatabaseHolder.get(ctx)
        val tmpDir = File(ctx.cacheDir, "import").apply { mkdirs() }
        val totalBytes = querySize(ctx, uri)
        if (totalBytes > MAX_BYTES) throw IOException("文件超过 4GB，请先裁剪或分段导入")

        // 空间预检：宁可提前拒绝，也不要在写到一半时抛 ENOSPC 留下半个文件
        if (totalBytes > 0) {
            val usable = runCatching { tmpDir.usableSpace }.getOrDefault(0L)
            if (usable in 1 until totalBytes + 64L * 1024 * 1024) {
                throw IOException(
                    "手机剩余空间不足（需约 ${fmtMb(totalBytes)}，可用 ${fmtMb(usable)}）",
                )
            }
        }

        _state.value = Progress(stage = "正在读取文件…")

        val tmp = File(tmpDir, "src_${System.currentTimeMillis()}.tmp")
        var sessionId = 0L
        try {
            // ---- 1. 复制到私有目录 ----
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
            } ?: throw IOException("无法读取该文件（可能已被移走或没有访问权限）")

            if (tmp.length() == 0L) throw IOException("文件是空的")

            val durationMs = durationOf(tmp.absolutePath)
            val fileTime = queryLastModified(ctx, uri)
            val startWall = fileTime ?: System.currentTimeMillis()
            val byFileTime = fileTime != null

            // ---- 2. 建会话 ----
            sessionId = db.sessionDao().insert(
                SessionEntity(
                    startWall = startWall,
                    endWall = if (durationMs > 0) startWall + durationMs else 0L,
                    syncState = SessionEntity.SYNC_DIRTY,
                ),
            )

            // ---- 3. 流式解码 + VAD 切句（独立线程，见 decodeAndPersist 的说明）----
            var outcome =
                decodeAndPersist(ctx, db, sessionId, startWall, tmp.absolutePath, durationMs)

            if (outcome.sampleRate == 0) {
                throw IOException("这个文件解不出音频（格式可能不受支持，试试 WAV/MP3/M4A）")
            }

            /* 一段都没切出来，最常见的原因是**音量太小**而不是没人说话：
               录音笔放桌上、会议室后排、揣在包里，远场拾音的电平常在 -30dB 上下，
               恰好卡在 Silero VAD 的判定边界上。实测同一段音频：原样导入切出 0 段，
               提升 20dB 后立刻切出 4 段。所以这里自动再试一次，而不是直接告诉用户
               「没检测到说话」—— 那句话会让人以为录音坏了，是最容易促使人放弃的提示。 */
            if (outcome.segments == 0) {
                Log.w(TAG, "首轮未检出语音，提升音量后重试")
                _state.value = Progress(stage = "音量偏低，正在增强后重试…")
                runCatching { db.segmentDao().hardDeleteBySession(sessionId) }
                runCatching { AudioStore.dirFor(ctx, sessionId).deleteRecursively() }
                outcome = decodeAndPersist(
                    ctx, db, sessionId, startWall, tmp.absolutePath, durationMs, LOW_VOLUME_GAIN,
                )
                if (outcome.segments > 0) {
                    Log.i(TAG, "增强音量后切出 ${outcome.segments} 段")
                }
            }

            if (outcome.segments == 0) {
                throw IOException("整段音频里没有检测到说话（全是静音或纯音乐）")
            }
            val segCount = outcome.segments
            val speechMs = outcome.speechMs

            // ---- 4. 回填会话统计 ----
            db.sessionDao().updateStats(sessionId, 0, speechMs)
            if (durationMs > 0) db.sessionDao().close(sessionId, startWall + durationMs)

            _state.value = Progress(
                percent = 100,
                segCount = segCount,
                stage = "完成",
                finished = true,
                summary = "切出 $segCount 段语音，约 ${speechMs / 60000} 分钟",
            )
            Log.i(TAG, "导入完成：session=$sessionId 段数=$segCount 语音=${speechMs}ms")
            Result(sessionId, segCount, speechMs, byFileTime)
        } catch (e: CancellationException) {
            // 用户中途取消：整轮清掉，不能留一个半截会话在列表里
            withContext(NonCancellable) { cleanup(ctx, sessionId) }
            _state.value = null
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { cleanup(ctx, sessionId) }
            _state.value = null
            throw e
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** 导入结束（无论成败）后调用，把进度状态收回 */
    fun clear() {
        _state.value = null
    }

    /** 解码线程的产出 */
    private class DecodeOutcome(
        val sampleRate: Int = 0,
        val segments: Int = 0,
        val speechMs: Long = 0L,
    )

    /**
     * 在**独立线程**上完成「解码 → VAD 切句 → 落盘 → 入库」。
     *
     * ## 为什么必须是裸线程
     * 这段活是「CPU 密集 + 阻塞式调用 + Room 挂起查询」的混合体。最初把它直接写在
     * `withContext(Dispatchers.IO)` 里，用 `runBlocking` 去调 Room 的挂起 DAO，
     * 实测**切出第一句之后就死锁**：进度条冻结在 51%、段数一直显示 0，整个
     * 导入永久挂住（既不完也不报错，是三种失败里最难查的一种）。
     *
     * 采集服务里同样一组 `VadSegmenter + AacEncoder + runBlocking{dao}` 跑在裸 Thread 上
     * 一直是好的，所以这里照抄那套已验证的组合，不再往协程调度器里塞阻塞调用。
     *
     * 取消靠 interrupt：VAD / MediaCodec 的阻塞调用会抛异常退出，交由上层清理半成品。
     */
    private suspend fun decodeAndPersist(
        ctx: Context,
        db: com.misaka.voicelog.data.VoiceLogDatabase,
        sessionId: Long,
        startWall: Long,
        path: String,
        durationMs: Long,
        gain: Float = 1f,
    ): DecodeOutcome = suspendCancellableCoroutine { cont ->
        val worker = Thread({
            try {
                val vad = VadSegmenter(ModelPaths.vadModel(ctx))
                // 目标 16kHz 下的总样本数，只用来算百分比；拿不到时长就不显示百分比
                val expected = if (durationMs > 0) durationMs * 16 else 0L
                var decoded = 0L
                var segCount = 0
                var speechMs = 0L
                var blocks = 0
                var lastPercent = -1
                var sampleRate = 0

                fun take(segs: List<VadSegmenter.Segment>) {
                    for (s in segs) {
                        val ok = persistSegment(ctx, db, sessionId, startWall, s)
                        if (ok > 0) {
                            segCount++
                            speechMs += ok
                        }
                    }
                }

                sampleRate = StreamAudioDecoder.decode(path) { chunk ->
                    decoded += chunk.size
                    /* 增益只作用于「低音量重试」那一轮。
                       削顶用 coerce 而不是回绕：录音笔远场拾音音量小，放大后偶尔过冲，
                       回绕会变成刺耳的爆音，削顶只是轻微失真。 */
                    val fed = if (gain == 1f) {
                        chunk
                    } else {
                        FloatArray(chunk.size) { (chunk[it] * gain).coerceIn(-1f, 1f) }
                    }
                    take(vad.accept(fed))
                    blocks++
                    // 每 16 块（约 0.5 秒音频）刷一次进度，够顺滑又不至于刷爆 UI
                    if (blocks % 16 == 0) {
                        val p = if (expected > 0) {
                            ((decoded * 100) / expected).toInt().coerceIn(0, 99)
                        } else {
                            0
                        }
                        if (p != lastPercent) {
                            lastPercent = p
                            _state.value = Progress(
                                percent = p,
                                segCount = segCount,
                                stage = if (expected > 0) "正在解码 $p%" else "正在解码…",
                            )
                        }
                    }
                }.sampleRate

                take(vad.flush())
                vad.release()
                Log.i(TAG, "解码线程完成：段数=$segCount 语音=${speechMs}ms 采样率=$sampleRate")
                cont.resume(DecodeOutcome(sampleRate, segCount, speechMs))
            } catch (t: Throwable) {
                Log.e(TAG, "解码线程异常", t)
                cont.resumeWithException(
                    if (t is Exception) t else IOException(t.message ?: "解码失败", t),
                )
            }
        }, "vl-import")
        worker.start()
        cont.invokeOnCancellation { worker.interrupt() }
    }

    /**
     * 落一段音频。
     *
     * 与实时采集走**完全相同**的落盘约定（`audio/<sessionId>/<segmentId>.m4a`），
     * 所以转写、回听、删除、清理都不需要为导入来源写第二套逻辑。
     *
     * @return 该段语音时长（毫秒），0 表示这段没能落盘
     */
    private fun persistSegment(
        ctx: Context,
        db: com.misaka.voicelog.data.VoiceLogDatabase,
        sessionId: Long,
        startWall: Long,
        seg: VadSegmenter.Segment,
    ): Long {
        val startMs = seg.startSample * 1000L / VadSegmenter.SAMPLE_RATE
        val durMs = seg.durationMs
        Log.i(TAG, "段落入库 startMs=$startMs dur=${durMs}ms samples=${seg.samples.size}")

        val id = db.segmentDao().insertBlocking(
            SegmentEntity(
                sessionId = sessionId,
                startMs = startMs,
                endMs = startMs + durMs,
                wallClock = startWall + startMs,
                filePath = "",
                durMs = durMs,
                source = SegmentEntity.SRC_IMPORT,
            ),
        )
        val file = AudioStore.fileFor(ctx, sessionId, id)
        return runCatching {
            val bytes = AacEncoder.encode(seg.samples, file)
            Log.i(TAG, "段落编码完成 id=$id bytes=$bytes")
            db.segmentDao().updateFileBlocking(id, file.absolutePath, bytes)
            durMs
        }.getOrElse { e ->
            /* 编码失败不能静默跳过：留一条 FAILED 记录，用户至少知道这里少了一段，
               并且能点「重试失败段」再试一次。 */
            Log.e(TAG, "导入段落编码失败 seg=$id", e)
            runCatching { file.delete() }
            runCatching {
                db.segmentDao().updateStatusBlocking(
                    id, SegmentEntity.ST_FAILED, "导入时编码失败：${e.javaClass.simpleName}",
                )
            }
            0L
        }
    }

    private suspend fun cleanup(ctx: Context, sessionId: Long) {
        if (sessionId <= 0L) return
        runCatching { VoiceLogDatabaseHolder.get(ctx).segmentDao().hardDeleteBySession(sessionId) }
        runCatching { VoiceLogDatabaseHolder.get(ctx).sessionDao().hardDelete(sessionId) }
        runCatching { AudioStore.dirFor(ctx, sessionId).deleteRecursively() }
    }

    // ---------------------------------------------------------------- 元数据

    private fun durationOf(path: String): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    private fun querySize(ctx: Context, uri: Uri): Long {
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (i >= 0 && c.moveToFirst()) return c.getLong(i)
            }
        }
        return runCatching {
            if (uri.scheme == "file") File(uri.path!!).length() else 0L
        }.getOrDefault(0L)
    }

    /**
     * 取文件的最后修改时间，作为这一轮会话的归档时间。
     *
     * 从电脑拷进手机的录音文件通常保留了原始 mtime（Windows/MTP 都会带上），
     * 所以这比"导入那一刻的时间"更接近真实的录制时间。取不到就退回当前时间，
     * 并在界面上标明是靠什么归档的 —— 时间轴错位却不说明，比错位本身更让人困惑。
     */
    private fun queryLastModified(ctx: Context, uri: Uri): Long? {
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (i >= 0 && c.moveToFirst() && !c.isNull(i)) {
                    val v = c.getLong(i)
                    if (v > 0L) return v
                }
            }
        }
        return runCatching {
            if (uri.scheme == "file") {
                File(uri.path!!).lastModified().takeIf { it > 0L }
            } else {
                null
            }
        }.getOrNull()
    }

    private fun fmtMb(bytes: Long): String = "${bytes / 1024 / 1024}MB"
}
