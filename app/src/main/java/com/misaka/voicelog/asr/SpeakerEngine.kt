package com.misaka.voicelog.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

/**
 * 声纹特征提取（3D-Speaker campplus，中文优化）。
 *
 * 只做「把一段音频变成向量」这一件事，比对逻辑在 [com.misaka.voicelog.core.VoiceprintStore]。
 * 这样注册流程（UI 层临时创建）和实时判定（服务里常驻）可以共用同一份提取代码。
 *
 * ⚠️ 非线程安全，也不建议多实例常驻：模型 27MB，开两份白吃内存。
 */
class SpeakerEngine(modelPath: String, numThreads: Int = 2) {

    companion object {
        private const val TAG = "SpeakerEngine"
        private const val SAMPLE_RATE = 16_000
    }

    private var extractor: SpeakerEmbeddingExtractor? = runCatching {
        val cfg = SpeakerEmbeddingExtractorConfig(
            // 绝对路径加载时必须传 null（同 Vad / OfflineRecognizer 的坑）
            model = modelPath,
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
        )
        SpeakerEmbeddingExtractor(null, cfg)
    }.onFailure { Log.e(TAG, "init failed", it) }.getOrNull()

    val dim: Int get() = runCatching { extractor?.dim() ?: 0 }.getOrDefault(0)

    val ready: Boolean get() = extractor != null

    /**
     * 提取声纹向量。
     *
     * 音频过短时 campplus 内部凑不够一个 chunk，`isReady` 为 false，compute 返回空数组。
     * 这里统一返回 null，让调用方明确知道"这句没法判"，而不是拿到一个全零向量去比。
     */
    fun embed(samples: FloatArray): FloatArray? {
        val ex = extractor ?: return null
        if (samples.size < SAMPLE_RATE * 0.4f) return null
        return runCatching {
            val stream = ex.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                if (!ex.isReady(stream)) return null
                val emb = ex.compute(stream)
                if (emb == null || emb.isEmpty()) null else emb
            } finally {
                runCatching { stream.release() }
            }
        }.onFailure { Log.e(TAG, "embed failed", it) }.getOrNull()
    }

    /** 静音或纯噪声的向量没有区分度，用它判人只会制造假阳性 */
    fun isSilentOrNoise(samples: FloatArray): Boolean {
        var energy = 0.0
        for (s in samples) energy += s.toDouble() * s
        val rms = if (samples.isEmpty()) 0.0 else Math.sqrt(energy / samples.size)
        return rms < 1e-4
    }

    fun release() {
        runCatching { extractor?.release() }
        extractor = null
    }
}
