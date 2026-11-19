package com.misaka.voicelog.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把一段 PCM 编码成 AAC-LC 并封装为 `.m4a`。
 *
 * ## 为什么必须压缩
 * 采集与转写解耦后，音频要落盘等用户点「转写」。裸 PCM 是 32KB/s —— 开机 8 小时
 * 是 **921MB**，手机装不下也删不过来。AAC-LC 24kbps 只有 **10.8MB/小时**，
 * 按「有效语音占开机时长 25%」估算约 **22MB/天**，7 天缓存 150MB 左右，可接受。
 *
 * ## 为什么一段一个文件
 * VAD 切出来的每一段单独一个 m4a。不做「整轮一个长文件」是因为重试、试听、
 * 删除、定位都要精确到句；小文件的代价只是文件数多（一天几百个），
 * ext4 / F2FS 完全扛得住，而「一个 3 小时的 m4a 里找到第 47 句」才是真麻烦。
 *
 * ## 为什么每段都新建编解码器
 * MediaMuxer 的时间戳是**单调递增**的。复用同一个 encoder + 每段新建 muxer，
 * 第二段的第一个样本 pts 会是「上一段结尾」，而 muxer 从头开始算，
 * 结果是音频开头一段空白或直接写失败。所以宁可每段付 20~50ms 的初始化开销。
 *
 * ⚠️ 本类非线程安全，一次只处理一段。
 */
object AacEncoder {

    private const val TAG = "AacEncoder"

    /**
     * AAC-LC 在 16kHz 单声道下的码率。
     *
     * ★ 2026-11-08 从 24kbps 提到 48kbps：24kbps 时每样本只有 1.5 bit，
     * 高频截止约 5~6kHz，而中文清擦音 s/sh/x/f/h 的能量正落在 5~8kHz。
     * 实测对照里「电解质」→「天写质」、「蜂蜜」→「风」都是这个原因。
     *
     * 代价是体积翻倍：10.8MB/小时 → 21.6MB/小时。按每天 2 小时有效语音算
     * 也才 43MB，远不是瓶颈（模型占了 262MB）。
     */
    const val BIT_RATE = 48_000

    /**
     * 可选码率档位（bps）。
     *
     * 不是「随便给几个数」：16kbps 以下中文清擦音基本全丢，转写必然错；
     * 96kbps 以上对 16kHz 单声道已经没有意义（源信号本身只有 8kHz 带宽）。
     * 所以档位就卡在 32 / 48 / 64 三个，默认 48。
     */
    val BIT_RATE_CHOICES = listOf(32_000, 48_000, 64_000)

    /** 编码超时（微秒）。单段最多 30s（VAD 上限），给足 10s 处理窗口 */
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * 循环次数上限。正常一段 20 秒音频约 60~120 次循环（每次要么喂一块输入、
     * 要么收一块输出），给到 10000 是极宽松的上限；真撞上说明编码器已经不正常，
     * 此时抛异常让这一段标记失败，好过让整批任务永久卡住。
     */
    private const val MAX_LOOPS = 10_000

    /**
     * 编码并写文件。
     *
     * @return 写出的文件字节数；调用方据此统计占用。失败时抛异常，由上层记 FAILED。
     */
    fun encode(
        samples: FloatArray,
        outFile: File,
        sampleRate: Int = VadSegmenter.SAMPLE_RATE,
        bitRate: Int = BIT_RATE,
    ): Long {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            /* 输入窗口给 16KB：16kHz 单声道 16bit 一秒才 32KB，
               给太小会导致一段要切很多次，白白增加调用次数。 */
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var muxerStopped = false
        var trackIndex = -1

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val pcm = toPcm16(samples)
            val info = MediaCodec.BufferInfo()
            val bytesPerSample = 2
            var offset = 0
            var ptsUs = 0L
            var eosSent = false
            var guard = 0

            while (true) {
                /* 死循环保护。编码器一旦收不到 EOS，就永远不会吐出结束标志，
                   这里会一直转下去 —— 界面表现为进度条卡死、不报错、CPU 也不涨。
                   给个上限，宁愿这一段标记失败，也不能让整批导入永久卡住。 */
                if (guard++ > MAX_LOOPS) {
                    throw IllegalStateException("编码器无响应，已中止（${outFile.name}）")
                }

                // ---------- 喂输入 ----------
                if (offset < pcm.size) {
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        val remain = pcm.size - offset
                        val canWrite = minOf(buf.remaining(), remain * bytesPerSample)
                        val sampleCount = canWrite / bytesPerSample

                        if (sampleCount > 0) {
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            buf.asShortBuffer().put(pcm, offset, sampleCount)
                            buf.position(0)
                            buf.limit(sampleCount * bytesPerSample)
                            offset += sampleCount
                            ptsUs = offset * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inIdx, 0, sampleCount * bytesPerSample, ptsUs, 0)
                        }
                    }
                } else if (!eosSent) {
                    /* EOS 必须真正送出去。
                       原来只在「写完最后一块的那一次循环」里试一遍 dequeueInputBuffer，
                       拿不到（超时返回 -1）就此放弃 —— 编码器既不会吐出最后几百毫秒，
                       也永远不会给出 BUFFER_FLAG_END_OF_STREAM，循环再无退出条件。
                       改成独立分支、每轮重试，直到送出为止。 */
                    val eosIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (eosIdx >= 0) {
                        codec.queueInputBuffer(
                            eosIdx, 0, 0, ptsUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        eosSent = true
                    }
                }

                // ---------- 收输出 ----------
                val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // ⚠️ 必须在这里 addTrack：只有编码器真正跑起来之后，
                        // 它的 outputFormat 才是「实际编码参数」，configure 时传进去的不算。
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    outIdx >= 0 -> {
                        val outBuf: ByteBuffer = codec.getOutputBuffer(outIdx)!!
                        // codec config 帧（含 AAC 头）不能写进 muxer，写了部分播放器会报错
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (info.size > 0 && !isConfig && muxerStarted) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }

                if (eosSent && !muxerStarted) {
                    // 极短片段（不到一帧）可能一直没有输出，别死等
                    Log.w(TAG, "段太短，编码器无输出：${outFile.name}")
                    break
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            /* ⚠️ muxer.stop() 绝不能包在 runCatching 里吞掉。
               它负责把 moov（索引）写进文件尾；一旦失败，产出的是
               `ftyp + mdat` 却**没有 moov** 的残废文件 —— 大小看着很正常、
               连 MediaExtractor 都能打开，但一条音轨都读不出来。
               现场实测过一个这样的段：10846 字节、缺 moov、转写报"无法解码"，
               而编码侧把它当成功写进了库。宁可这里抛异常让上层标 FAILED，
               也不要把一个读不了的文件伪装成"已录好"。 */
            if (muxerStarted) {
                try {
                    muxer.stop()
                    muxerStopped = true
                } catch (t: Throwable) {
                    Log.e(TAG, "muxer.stop 失败，该段无索引无法回听：${outFile.name}", t)
                }
            }
            runCatching { muxer.release() }
        }

        if (!muxerStarted || !muxerStopped) {
            outFile.delete()
            throw IllegalStateException(
                if (!muxerStarted) "编码器没有产出任何数据（段太短或设备不支持 16kHz AAC）"
                else "封装未完成（moov 未写入），该段无法解码",
            )
        }
        /* 最后一道校验：`muxer.stop()` 返回成功不等于索引真的写进去了。
           2026-11-07 现场抓到一个 4977 字节的 m4a —— 有 ftyp + mdat、没有 moov，
           muxer.stop() 没抛异常，上层于是回了路径和大小，直到用户点转写才炸
           「音频无法解码」，而那时已经无从归因。
           moov 就在文件尾部，读尾部几 KB 找一下开销可忽略，比事后猜便宜得多。 */
        if (!hasMoov(outFile)) {
            outFile.delete()
            throw IllegalStateException("封装产物缺少 moov 索引，该段无法解码")
        }
        return outFile.length()
    }

    /** moov box 在文件尾部（MediaMuxer 边写 mdat 边攒索引，stop 时才补写） */
    private fun hasMoov(file: File): Boolean {
        val len = file.length()
        if (len <= 0L) return false
        val window = minOf(len, 64L * 1024).toInt()   // 尾部 64KB 足够覆盖 moov
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(len - window)
                val buf = ByteArray(window)
                raf.readFully(buf)
                // 直接找 ASCII "moov"。box 类型就在 box 头里，不会出现在压缩数据中
                for (i in 0..buf.size - 4) {
                    if (buf[i] == 'm'.code.toByte() && buf[i + 1] == 'o'.code.toByte() &&
                        buf[i + 2] == 'o'.code.toByte() && buf[i + 3] == 'v'.code.toByte()
                    ) {
                        return@use true
                    }
                }
                false
            }
        }.getOrDefault(false)
    }

    /** float [-1,1] → 16bit 小端 PCM。削顶而不是环绕，避免爆音 */
    private fun toPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = samples[i]
            val clamped = if (v > 1f) 1f else if (v < -1f) -1f else v
            out[i] = (clamped * 32767f).toInt().toShort()
        }
        return out
    }
}
