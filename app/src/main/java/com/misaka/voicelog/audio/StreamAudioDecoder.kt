package com.misaka.voicelog.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.RandomAccessFile
import java.nio.ByteOrder

/**
 * 流式解码：任意音频文件 → 16kHz 单声道 float PCM，分块回调。
 *
 * ## 为什么不复用 [AudioDecoder]
 * [AudioDecoder.decode] 是把**整个文件**解成一个 FloatArray 返回，用于转写我们自己
 * 切好的 20 秒小段（单段 ≤ 1.3MB）完全够用。但导入外部录音是另一回事：
 * 录音笔一小时的文件解成 16k float 是 **230MB**，直接 OOM。
 * 所以这里必须是流式的 —— 边解边喂 VAD，峰值内存只有一个读缓冲。
 *
 * ## 为什么自带 WAV 解析
 * `MediaExtractor` 对 WAV 的支持有坑：**24bit / 32bit PCM WAV 在多数机型上解不出来**
 * （它只稳定支持 16bit）。而专业录音笔恰恰爱用 24bit 保真。所以先用 `RIFF` 头判断，
 * WAV 走自己写的解析器，其余（m4a/mp3/amr/ogg/flac）才交给 MediaCodec。
 *
 * ## 三个静默错误必须显式处理
 * 现有 [AudioDecoder] 里没有处理这三点，导入场景下会放大成灾难：
 * 1. **声道**：立体声当单声道直接读，等于把左右声道交错当成时序 → 语速快一倍、全乱
 * 2. **解码器输出采样率与源不一致**：必须听 `INFO_OUTPUT_FORMAT_CHANGED`，不能信
 *    容器里写的那个（部分设备按硬件偏好输出 48kHz）
 * 3. **输出可能是 float 而非 short**：`KEY_PCM_ENCODING` 为 float 时按 short 读会得到噪声
 */
object StreamAudioDecoder {

    private const val TAG = "StreamAudioDecoder"
    private const val TIMEOUT_US = 10_000L

    /** 输出块大小，与 Silero VAD 的固定窗口对齐，调用方可以直接喂 */
    const val CHUNK = VadSegmenter.WINDOW_SIZE

    data class Info(
        val sampleRate: Int = 0,
        val channels: Int = 0,
        /** 实际走的解码器：`wav` / 具体 mime，出错时用于归因 */
        val encoder: String = "",
    )

    /**
     * 解码并分块回调。回调在调用线程上同步执行，不要在里面做耗时操作。
     *
     * @return 源文件信息；解析失败返回默认值（不抛异常，让调用方按「无有效音频」处理）
     */
    fun decode(path: String, onChunk: (FloatArray) -> Unit): Info {
        return runCatching {
            if (isWav(path)) decodeWav(path, onChunk) else decodeByCodec(path, onChunk)
        }.onFailure { Log.e(TAG, "解码失败 $path", it) }
            .getOrDefault(Info())
    }

    // ------------------------------------------------------------------ WAV

    private fun isWav(path: String): Boolean = runCatching {
        RandomAccessFile(path, "r").use { raf ->
            if (raf.length() < 12) return false
            val h = ByteArray(12)
            raf.readFully(h)
            String(h, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(h, 8, 4, Charsets.US_ASCII) == "WAVE"
        }
    }.getOrDefault(false)

    private fun decodeWav(path: String, onChunk: (FloatArray) -> Unit): Info {
        RandomAccessFile(path, "r").use { raf ->
            var fmt: ByteArray? = null
            var dataOffset = -1L
            var dataSize = -1L

            // 遍历 RIFF chunk，找到 fmt 与 data。DATA 可能在 fmt 之前（少见但合法）
            raf.seek(12)
            val chHead = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chHead)
                val id = String(chHead, 0, 4, Charsets.US_ASCII)
                val size = le32(chHead, 4)
                val body = raf.filePointer
                when (id) {
                    "fmt " -> {
                        val n = size.toInt().coerceIn(16, 64)
                        fmt = ByteArray(n).also { raf.readFully(it) }
                    }
                    "data" -> {
                        dataOffset = body
                        // size 为 0 或 -1（0xFFFFFFFF）表示一直读到文件末尾
                        dataSize = if (size <= 0) raf.length() - body else size.toLong()
                    }
                }
                if (fmt != null && dataOffset > 0) break
                // chunk 按 2 字节对齐，奇数长度要补一个填充字节
                raf.seek(body + size + (size and 1))
            }

            val f = fmt ?: return Info()
            if (dataOffset < 0) return Info()

            val audioFormat = le16(f, 0)          // 1=PCM 3=IEEE float 0xFFFE=extensible
            val channels = le16(f, 2).coerceAtLeast(1)
            val rate = le32(f, 4)
            val bits = le16(f, 14).coerceAtLeast(8)
            if (rate <= 0) return Info()

            val isFloat = audioFormat == 3
            // extensible 格式真正的子格式在 GUID 头部两字节，但实际 PCM 的 bit 数已够判断
            val bytesPerSample = bits / 8
            val frameBytes = bytesPerSample * channels
            if (frameBytes <= 0) return Info()

            val resampler = Resampler(rate, VadSegmenter.SAMPLE_RATE)
            val chunker = Chunker(CHUNK, onChunk)

            raf.seek(dataOffset)
            var remaining = dataSize.coerceAtMost(raf.length() - dataOffset)
            val framesPerRead = 8192
            val buf = ByteArray(frameBytes * framesPerRead)

            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val n = raf.read(buf, 0, want)
                if (n <= 0) break
                remaining -= n
                val frames = n / frameBytes
                if (frames <= 0) break

                val mono = FloatArray(frames)
                var o = 0
                for (i in 0 until frames) {
                    // 多声道下混成单声道：取算术平均，而不是只取第一路
                    var sum = 0f
                    for (c in 0 until channels) {
                        sum += readSample(buf, o, isFloat, bits)
                        o += bytesPerSample
                    }
                    mono[i] = sum / channels
                }
                chunker.push(resampler.feed(mono))
            }
            chunker.flush()
            Log.i(TAG, "WAV 解码完成：${rate}Hz/${channels}ch/${bits}bit float=$isFloat")
            return Info(rate, channels, "wav")
        }
    }

    private fun readSample(b: ByteArray, off: Int, isFloat: Boolean, bits: Int): Float = when {
        isFloat && bits == 32 -> {
            val v = (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
            Float.fromBits(v)
        }
        bits == 8 -> ((b[off].toInt() and 0xFF) - 128) / 128f      // 8bit WAV 是无符号的
        bits == 16 -> {
            val v = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
            v.toShort() / 32768f
        }
        bits == 24 -> {
            var v = (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16)
            if (v and 0x800000 != 0) v = v or 0xFF000000.toInt()   // 24bit 符号扩展
            v / 8388608f
        }
        bits == 32 -> {
            val v = (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
            v / 2147483648f
        }
        else -> 0f
    }

    // ------------------------------------------------------------- MediaCodec

    private fun decodeByCodec(path: String, onChunk: (FloatArray) -> Unit): Info {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) return Info()

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var srcRate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, VadSegmenter.SAMPLE_RATE)
            var channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var isFloat = false

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val resampler = Resampler(srcRate, VadSegmenter.SAMPLE_RATE)
            val chunker = Chunker(CHUNK, onChunk)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var guard = 0
            /* 出问题时这两项能直接判断「是没解出数据，还是数据格式读错了」：
               峰值接近 0 → 真静音；峰值 > 1 或为 NaN → 位深/字节序判断错了 */
            var totalOut = 0L
            var peak = 0f

            while (!outputDone && guard++ < 2_000_000) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        /* 解码器实际输出格式在这里才可信。采样率变了必须重建重采样器，
                           否则「源写 44.1k、实际出 48k」会让整段语速偏移 8%，识别质量下降。 */
                        val of = codec.outputFormat
                        val newRate = of.intOr(MediaFormat.KEY_SAMPLE_RATE, srcRate)
                        val newCh = of.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        if (newRate != srcRate) {
                            Log.i(TAG, "输出采样率 ${srcRate}Hz → ${newRate}Hz，重建重采样器")
                            srcRate = newRate
                        }
                        channels = newCh.coerceAtLeast(1)
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            isFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                AudioFormat.ENCODING_PCM_FLOAT
                        }
                        if (resampler.sourceRate != srcRate) resampler.rebuild(srcRate)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outIdx >= 0) {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.LITTLE_ENDIAN)

                            val mono = if (isFloat) {
                                val fb = buf.asFloatBuffer()
                                downmixFloat(fb, channels)
                            } else {
                                val sb = buf.asShortBuffer()
                                downmixShort(sb, channels)
                            }
                            chunker.push(resampler.feed(mono))
                            totalOut += mono.size
                            for (v in mono) {
                                val a = if (v < 0f) -v else v
                                if (a > peak && a.isFinite()) peak = a
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            chunker.flush()
            Log.i(
                TAG,
                "解码完成：$mime ${srcRate}Hz/${channels}ch float=$isFloat 样本=$totalOut 峰值=$peak",
            )
            return Info(srcRate, channels, mime)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun downmixShort(sb: java.nio.ShortBuffer, channels: Int): FloatArray {
        val frames = sb.remaining() / channels
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += sb.get() / 32768f
            out[i] = sum / channels
        }
        return out
    }

    private fun downmixFloat(fb: java.nio.FloatBuffer, channels: Int): FloatArray {
        val frames = fb.remaining() / channels
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += fb.get()
            out[i] = sum / channels
        }
        return out
    }

    // ---------------------------------------------------------------- 工具

    private fun MediaFormat.intOr(key: String, def: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(def) else def

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    /**
     * 带状态的线性插值重采样。
     *
     * 跨缓冲必须保留状态：解码器一次吐 4096 帧，重采样点会落在两块之间，
     * 无状态的话每次块边界都会有一个样本的断点，累积起来就是咔哒声和时序漂移。
     */
    private class Resampler(from: Int, private val to: Int) {

        var sourceRate: Int = from
            private set

        private var ratio = from.toDouble() / to
        private var buf = FloatArray(0)
        /** buf[0] 对应的绝对输入下标 */
        private var bufStart = 0L
        /** 已产出的输出样本数（绝对） */
        private var outIndex = 0L

        fun rebuild(newFrom: Int) {
            sourceRate = newFrom
            ratio = newFrom.toDouble() / to
        }

        fun feed(src: FloatArray): FloatArray {
            if (src.isEmpty()) return src
            if (sourceRate == to) return src

            if (buf.isEmpty()) {
                buf = src.copyOf()
            } else {
                val merged = FloatArray(buf.size + src.size)
                System.arraycopy(buf, 0, merged, 0, buf.size)
                System.arraycopy(src, 0, merged, buf.size, src.size)
                buf = merged
            }

            val out = ArrayList<Float>(src.size / 2 + 8)
            while (true) {
                val need = outIndex * ratio              // 绝对输入位置
                val rel = (need - bufStart).toInt()
                if (rel < 0) { outIndex++; continue }     // 理论不可达，防御死循环
                if (rel + 1 >= buf.size) break           // 等更多数据做插值
                val frac = (need - bufStart - rel).toFloat()
                out.add(buf[rel] * (1 - frac) + buf[rel + 1] * frac)
                outIndex++
            }

            // 丢掉不再需要的头部，否则 buf 会随文件线性增长
            val keep = ((outIndex * ratio) - bufStart).toInt().coerceIn(0, buf.size)
            if (keep > 0) {
                buf = buf.copyOfRange(keep, buf.size)
                bufStart += keep
            }
            return if (out.isEmpty()) FloatArray(0) else FloatArray(out.size) { out[it] }
        }
    }

    /** 把不定长的 float 流切成固定大小块，方便直接喂 VAD */
    private class Chunker(private val size: Int, private val onChunk: (FloatArray) -> Unit) {
        private var acc = FloatArray(0)

        fun push(s: FloatArray) {
            if (s.isEmpty()) return
            acc = if (acc.isEmpty()) s.copyOf() else acc + s
            var off = 0
            while (acc.size - off >= size) {
                onChunk(acc.copyOfRange(off, off + size))
                off += size
            }
            if (off > 0) acc = acc.copyOfRange(off, acc.size)
        }

        /** 末尾不足一块的样本也要吐出去，否则每个文件结尾最多丢 32ms */
        fun flush() {
            if (acc.isNotEmpty()) {
                onChunk(acc)
                acc = FloatArray(0)
            }
        }
    }
}
