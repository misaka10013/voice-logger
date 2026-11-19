package com.misaka.voicelog.asr

/**
 * 语音转写引擎抽象。
 *
 * 为什么要这层接口：本地方案（SenseVoice）与云方案（SileroFlow / 通义 / 讯飞）
 * 的差异只在"音频怎么变成文字"，其余链路（切句、存储、同步）完全一致。
 * 多写这 20 行，后面换后端时不用动任何上层代码。
 */
interface AsrEngine {

    data class Result(
        val text: String,
        val lang: String,
        val emotion: String = "",
    )

    /**
     * 同步转写一段音频。
     * @param samples 单声道 float PCM，取值范围 [-1, 1]
     * @param sampleRate 采样率，固定 16000
     */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16_000): Result

    fun release()
}
