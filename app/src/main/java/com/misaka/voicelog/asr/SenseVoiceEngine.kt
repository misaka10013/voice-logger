package com.misaka.voicelog.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

/**
 * SenseVoice-Small（INT8）本地转写。
 *
 * 选它的理由：
 * · 中文识别比同尺寸 Whisper 准，且自带语种识别
 * · INT8 模型 228MB，arm64 上 RTF 约 0.02~0.1（K60 至尊版这类芯片更快）
 * · 支持 ITN（useInverseTextNormalization），"二零二六年"会输出"2026年"
 *
 * ⚠️ 非线程安全：OfflineRecognizer 内部有状态，只在单个 ASR 线程使用。
 */
class SenseVoiceEngine(
    modelPath: String,
    tokensPath: String,
    language: String = "zh",
    numThreads: Int = 2,
    /** 逆文本规整：「二零二六年」→「2026年」。关掉可保留「说的原样」 */
    useItn: Boolean = true,
) : AsrEngine {

    private val recognizer: OfflineRecognizer = run {
        val senseVoice = OfflineSenseVoiceModelConfig(
            model = modelPath,
            language = language,
            useInverseTextNormalization = useItn,
        )
        val modelConfig = OfflineModelConfig(
            senseVoice = senseVoice,
            tokens = tokensPath,
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
            modelType = "sense_voice",
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = 16_000,
                featureDim = 80,
                dither = 0.0f,
            ),
            modelConfig = modelConfig,
        )
        // 同 Vad：绝对路径 + null assetManager
        OfflineRecognizer(null, config)
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): AsrEngine.Result {
        if (samples.isEmpty()) return AsrEngine.Result("", "")

        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            val r = recognizer.getResult(stream)
            AsrEngine.Result(
                text = r.text.trim(),
                lang = r.lang ?: "",
                emotion = r.emotion ?: "",
            )
        } finally {
            runCatching { stream.release() }
        }
    }

    override fun release() {
        runCatching { recognizer.release() }
    }
}
