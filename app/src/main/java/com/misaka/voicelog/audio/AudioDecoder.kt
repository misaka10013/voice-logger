package com.misaka.voicelog.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder

/**
 * 把 `.m4a` 解回 16kHz 单声道 float PCM，喂给 ASR。
 *
 * ## 为什么必须能解
 * 采集端做了有损压缩（AAC 24kbps），SenseVoice 只吃 PCM。所以「点转写」这条路上
 * 解码是一次性成本：把几十段 m4a 解开、识别、然后就能删音频。
 * 相对识别本身（RTF 0.03~0.10），解码开销可忽略。
 *
 * ## 采样率兜底
 * 我们的文件都是 16kHz 编的，但**不能假设**它一定被解成 16kHz ——
 * 某些设备的解码器会按硬件偏好输出 48kHz。一旦不一致，识别结果会变成
 * 「语速快 3 倍的乱码」，而且不报错。所以这里显式检查并重采样。
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TIMEOUT_US = 10_000L

    /** 解出的 PCM + 实际采样率，便于上层判断 */
    data class Result(val samples: FloatArray, val sampleRate: Int)

    /**
     * 解码整个文件。失败返回空数组（调用方按「无有效音频」处理，不抛出，
     * 因为一段坏文件不该让整批转写中断）。
     */
    fun decode(path: String, targetRate: Int = VadSegmenter.SAMPLE_RATE): FloatArray {
        val res = runCatching { decodeInternal(path) }
            .onFailure { Log.e(TAG, "解码失败 $path", it) }
            .getOrNull() ?: return FloatArray(0)

        if (res.samples.isEmpty()) return FloatArray(0)
        return if (res.sampleRate == targetRate) res.samples else resample(res.samples, res.sampleRate, targetRate)
    }

    private fun decodeInternal(path: String): Result {
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
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return Result(FloatArray(0), VadSegmenter.SAMPLE_RATE)

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                VadSegmenter.SAMPLE_RATE
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = ArrayList<Float>(srcRate * 4)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var guard = 0

            while (!outputDone && guard++ < 100_000) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit // 采样率以首次读到的 srcRate 为准
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIdx >= 0) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = buf.asShortBuffer()
                        while (shorts.hasRemaining()) out.add(shorts.get() / 32768f)
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }

            return Result(out.toFloatArray(), srcRate)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** 线性插值重采样。语音场景够用，且不引入第三方依赖 */
    private fun resample(src: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || src.isEmpty()) return src
        val ratio = from.toDouble() / to
        val outLen = (src.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = src[i0] * (1 - frac) + src[i1] * frac
        }
        return out
    }
}
