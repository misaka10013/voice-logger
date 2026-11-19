package com.misaka.voicelog.audio

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * 基于 Silero VAD 的语音端点切分。
 *
 * 设计要点：
 * · 只在"检测到有人说话"时才产出一段音频交给 ASR，静音期间零推理开销
 * · minSilenceDuration = 0.8s —— 说话停顿 0.8 秒才断句。
 *   ★ 2026-11-08 从 0.25s 改上来：0.25s 会把一句完整的话切成 2~3 个碎片
 *   （实测 46 分钟切成 977 段、平均 2.8 秒），而 SenseVoice 是**逐段独立解码**，
 *   碎片得不到任何上下文，同一段音频里「蜂蜜水」第一次对、第二次转成「风水」。
 *   放长到 0.8s 后平均段长回到 8~10 秒，接近 Whisper 的 30s 窗口利用率。
 *   代价是句间停顿短于 0.8s 的两人对话可能被并成一段，但相比"上下文为零"值得。
 * · maxSpeechDuration = 30s —— 超过强制切断，避免一个人连说三分钟导致单段过长、识别变慢
 *
 * ⚠️ 本类非线程安全，只允许采集线程调用。
 */
class VadSegmenter(
    modelPath: String,
    private val sampleRate: Int = SAMPLE_RATE,
    /**
     * 判定「这一段是人声」的概率阈值。
     * 越低越灵敏（远处小声说话也能切出来），代价是环境噪声更容易被当成说话。
     * 默认 0.5 是 Silero 官方给的中性值。
     */
    threshold: Float = DEFAULT_THRESHOLD,
    /**
     * 停顿多久算一句话说完（秒）。
     *
     * ★ 这个参数直接决定转写质量，不是「手感」问题：SenseVoice 逐段独立解码，
     * 段太碎（0.25s）会切出 2~3 秒的碎片，同一句话里「蜂蜜水」第二次就转成「风水」。
     * 默认 0.8s；嘈杂环境里对方说话停顿长，调到 1.5s 能让一句完整的话留在同一段里。
     */
    minSilenceSec: Float = DEFAULT_MIN_SILENCE_SEC,
    /**
     * 最短语音时长（秒）。短于它的声音不当成一句话。
     *
     * 2026-11-10 从硬编码 0.25s 放出来：它决定「碗筷碰撞、键盘声」这类瞬态
     * 会不会被切成一段送进 ASR 变成一行莫名其妙的字。调大能滤掉噪声。
     */
    minSpeechSec: Float = DEFAULT_MIN_SPEECH_SEC,
    /**
     * 最长单段时长（秒）。超过强制切断。
     *
     * 2026-11-10 从硬编码 30s 放出来。一人连说三分钟不切会得到一个巨长的段：
     * ASR 变慢、内存涨，而且一期只能标同一个人。调小能把长独白拆细。
     */
    maxSpeechSec: Float = DEFAULT_MAX_SPEECH_SEC,
) {

    /** 一段已完成的人声 */
    data class Segment(
        /** 相对采集开始的起始样本下标。**只用于音频内部坐标系，不能拿来推墙钟** */
        val startSample: Long,
        val samples: FloatArray,
        /**
         * 本段**产出那一刻**的真实墙钟（毫秒），由采集循环在 accept/flush 时传入。
         *
         * ★ 为什么必须由外部传、不能内部 `System.currentTimeMillis()`：
         * 采集循环里有些分支会「消耗真实时间但不产生样本」——读到全零、read 返回负数
         * 后的 `sleep(1500)`、来电让麦。这些时间**不会**进 `fedSamples`。
         * 若墙钟用 `采集开始墙钟 + 样本偏移` 推算，这些空洞就被抹平，且误差只增不减，
         * 表现为「所有句子时间偏早，越到后面偏得越多」。
         * 2026-11-15 实测：一轮 645 分钟的会话被压缩成 492 分钟，偏早 153 分钟。
         *
         * 以「产出现在」为锚点往回推段长，停摆期自然在时间轴上留出真实空洞。
         */
        val wallEnd: Long = 0L,
    ) {
        val durationMs: Long get() = samples.size * 1000L / SAMPLE_RATE

        /** 本段起始的真实墙钟。0 = 调用方没传（老路径），此时墙钟不可信 */
        val wallStart: Long get() = if (wallEnd > 0L) wallEnd - durationMs else 0L
    }

    private val vad: Vad = run {
        val silero = SileroVadModelConfig(
            model = modelPath,
            threshold = threshold,
            minSilenceDuration = minSilenceSec,
            minSpeechDuration = minSpeechSec,
            windowSize = WINDOW_SIZE,
            maxSpeechDuration = maxSpeechSec,
        )
        val cfg = VadModelConfig(
            sileroVadModelConfig = silero,
            sampleRate = sampleRate,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        // ⚠️ 用绝对路径加载模型时必须传 null assetManager。
        // 传了非空值会让 sherpa-onnx 走 assets 分支，报 "Load ... failed"（issues/2562）。
        @Suppress("UNUSED_EXPRESSION")
        Vad(null, cfg)
    }

    /** 已喂入的总样本数，用于换算时间戳 */
    private var fedSamples = 0L

    /** 当前是否正在说话（给 UI 显示"正在听"用） */
    val speechDetected: Boolean get() = vad.isSpeechDetected()

    /**
     * 喂入一段 PCM（float, [-1,1]），返回本次新完成的语音段（通常 0 或 1 个）。
     */
    fun accept(samples: FloatArray, wallNow: Long = System.currentTimeMillis()): List<Segment> {
        vad.acceptWaveform(samples)
        fedSamples += samples.size
        return drain(wallNow)
    }

    /** 采集停止时调用，把 VAD 内部残留的尾段吐出来 */
    fun flush(wallNow: Long = System.currentTimeMillis()): List<Segment> {
        vad.flush()
        return drain(wallNow)
    }

    fun reset() {
        vad.reset()
        fedSamples = 0L
    }

    fun release() {
        runCatching { vad.release() }
    }

    /**
     * 取空队列。
     *
     * 时间戳不依赖 SpeechSegment.start 的内部语义（不同版本实现有差异），
     * 直接用"当前已喂入总量 - 段长度"推算，误差在一个 VAD 帧以内（32ms），
     * 对"回顾第几分钟说了什么"这个用途完全够。
     */
    private fun drain(wallNow: Long): List<Segment> {
        var out: MutableList<Segment>? = null
        while (!vad.empty()) {
            val seg = vad.front()
            val samples = seg.samples
            if (samples.isNotEmpty()) {
                val start = (fedSamples - samples.size).coerceAtLeast(0L)
                /* 一次 accept 吐出多段时它们共用同一个 wallNow —— 尾部对齐，误差不超过
                   一次读取窗口（32ms），比「按样本累加」那种动辄偏几十分钟的误差好得多。 */
                (out ?: ArrayList<Segment>(2).also { out = it })
                    .add(Segment(start, samples, wallNow))
            }
            vad.pop()
        }
        return out ?: emptyList()
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** Silero VAD 在 16kHz 下的固定窗口：32ms */
        const val WINDOW_SIZE = 512

        const val DEFAULT_THRESHOLD = 0.5f
        const val DEFAULT_MIN_SILENCE_SEC = 0.8f
        const val DEFAULT_MIN_SPEECH_SEC = 0.25f
        const val DEFAULT_MAX_SPEECH_SEC = 30.0f
    }
}
