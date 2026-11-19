package com.misaka.voicelog.audio

import kotlin.math.sqrt

/**
 * 基频（F0）估计。用来判「这一段是男声还是女声」。
 *
 * ## 为什么用自相关而不是更复杂的方法
 * 目标是「一男一女两个远场人声分得开」，不是专业测音高。11-09 离线实验用同一思路
 * （归一化自相关 + 中位数）在 87 段上得到双峰 100–160Hz / 200–280Hz、分离度 3.02，
 * 说明这个精度对本场景够用。换成 YIN / CREPE 能更准，但要多带一份模型和算力，
 * 收益不足以抵消「监听时多耗电」。
 *
 * ## 三个防错设计（都是实测踩出来的）
 * 1. **低八度修正**：周期信号在 2T、3T 处也有峰，直接取全局最大峰会把男声判成女声
 *    （或反过来）。所以取「第一个足够高的局部峰」，而不是最高的那个。
 * 2. **自适应能量门槛**：静音帧的自相关全是噪声，算出来的基频毫无意义。
 *    但门槛不能写死 —— 远场录音实测 RMS 中位数只有 0.0005~0.006，
 *    写死 0.008 会把真语音帧全当成静音（第一版覆盖率仅 30%）。
 *    改成「段内 RMS 中位数的一半」后，同一批 40 段音频覆盖率 95%。
 * 3. **中位数而非均值**：一段 3 秒的话里总有清音（s/sh/f）算不出周期，
 *    均值会被这些异常值拉偏，中位数不会。
 *
 * ## 能力边界（不许承诺超出这个范围的效果）
 * · 只返回「有把握」的结果；帧数不足或整体不可靠时返回 null，调用方留「未判」
 * · 极短句（「嗯」「对」，占实测 14.1%）帧数不足，必然返回 null
 * · 电视里的女播音与真人女声在基频上无法区分 —— 那是内容层面的问题，不是音高问题
 */
object PitchDetector {

    /** 搜索范围。低于 60Hz 是人声以下的隆隆声，高于 400Hz 是儿童/假声，都不在本场景 */
    private const val MIN_F0_HZ = 60f
    private const val MAX_F0_HZ = 400f

    /** 分帧长度：40ms。低于 30ms 时 60Hz 的男声连一个周期都装不满 */
    private const val FRAME_MS = 40
    private const val HOP_MS = 20

    /** 帧能量门槛的绝对下限。低于它连噪声都算不上 */
    private const val ABS_MIN_RMS = 0.0008f

    /** 自相关峰的最小归一化值。低于它说明这一帧没有明显周期性 */
    private const val MIN_PEAK = 0.30f

    /** 低八度修正用的比例：局部峰达到「最高峰的 85%」就认为它可能是真周期 */
    private const val OCTAVE_RATIO = 0.85f

    /** 至少要有这么多帧算出基频才出结论，否则返回 null */
    private const val MIN_VOTES = 3

    /** 只分析前 3 秒。一句话的性别信息在头 3 秒已经足够，省算力 */
    private const val MAX_ANALYZE_SEC = 3

    /**
     * 估计一段音频的基频（取各帧中位数）。
     *
     * @return 基频（Hz）；无法可靠估计时返回 null —— **调用方必须把它当成「不知道」，
     *         不许用默认值兜底**，否则就是在猜。
     */
    fun estimate(samples: FloatArray, sampleRate: Int = 16_000): Float? {
        val frameLen = sampleRate * FRAME_MS / 1000
        val hop = sampleRate * HOP_MS / 1000
        if (samples.size < frameLen) return null

        val limit = minOf(samples.size, sampleRate * MAX_ANALYZE_SEC)
        val minLag = (sampleRate / MAX_F0_HZ).toInt().coerceAtLeast(2)
        val maxLag = (sampleRate / MIN_F0_HZ).toInt().coerceAtMost(frameLen - 1)
        if (maxLag <= minLag) return null

        val frameCount = (limit - frameLen) / hop + 1
        if (frameCount < MIN_VOTES) return null

        /* 第一遍：逐帧算 RMS 和「该帧的基频候选」。
           ⚠️ 能量门槛必须是**自适应**的：远场录音实测 RMS 中位数只有
           0.0005~0.006（会话 32 拉 40 段实测），固定门槛 0.008 会把
           绝大多数真语音帧当成静音 —— 第一版就是这么写的，覆盖率只有 30%。
           改成「段内 RMS 中位数的一半」之后，同一批音频覆盖率 95%。 */
        val rmsArr = FloatArray(frameCount)
        val lagArr = IntArray(frameCount)
        // 自相关曲线只在单帧内用，复用一个数组避免每帧分配
        val curve = FloatArray(maxLag + 2)
        for (i in 0 until frameCount) {
            val offset = i * hop
            val r = analyzeFrame(samples, offset, frameLen, minLag, maxLag, curve)
            rmsArr[i] = r.first
            lagArr[i] = r.second
        }

        val gate = maxOf(medianOf(rmsArr) * 0.5f, ABS_MIN_RMS)
        if (gate <= 0f) return null

        val votes = ArrayList<Float>(frameCount)
        for (i in 0 until frameCount) {
            if (rmsArr[i] < gate) continue
            val lag = lagArr[i]
            if (lag > 0) votes.add(sampleRate.toFloat() / lag)
        }
        if (votes.size < MIN_VOTES) return null
        votes.sort()
        return votes[votes.size / 2]
    }

    /** 段内 RMS 中位数。排序会破坏原数组，所以在副本上排 */
    private fun medianOf(arr: FloatArray): Float {
        if (arr.isEmpty()) return 0f
        val copy = arr.copyOf()
        copy.sort()
        return copy[copy.size / 2]
    }

    /**
     * 算一帧的 (RMS, 基频候选 lag)。lag=0 表示这一帧不可靠。
     *
     * @param curve 复用缓冲，长度必须 ≥ maxLag+2
     */
    private fun analyzeFrame(
        s: FloatArray,
        offset: Int,
        len: Int,
        minLag: Int,
        maxLag: Int,
        curve: FloatArray,
    ): Pair<Float, Int> {
        // 去直流：不去的话「均值不为 0」会给自相关加一条长尾，容易在长 lag 处出假峰
        var mean = 0.0
        for (i in 0 until len) mean += s[offset + i]
        mean /= len

        var energy = 0.0
        for (i in 0 until len) {
            val v = s[offset + i] - mean
            energy += v * v
        }
        val rms = sqrt(energy / len).toFloat()

        // 归一化自相关：除以两段的几何均值，让不同音量下的峰值可比
        var globalMax = 0f
        for (lag in minLag..maxLag) {
            var num = 0.0
            var d1 = 0.0
            var d2 = 0.0
            val n = len - lag
            var i = 0
            while (i < n) {
                val a = s[offset + i] - mean
                val b = s[offset + i + lag] - mean
                num += a * b
                d1 += a * a
                d2 += b * b
                i++
            }
            val r = if (d1 <= 0.0 || d2 <= 0.0) 0f else (num / sqrt(d1 * d2)).toFloat()
            curve[lag] = r
            if (r > globalMax) globalMax = r
        }

        if (globalMax < MIN_PEAK) return rms to 0

        /* 低八度修正：从**最短**的 lag 开始找第一个够高的局部峰。
           周期 T 的信号在 2T、3T 处也有几乎一样高的峰，直接取全局最大，
           一旦 2T 略高于 T，就会把 200Hz 判成 100Hz（女判成男），反之亦然。
           取「第一个」而不是「最高的」，才能稳定落在基频周期上。 */
        val threshold = globalMax * OCTAVE_RATIO
        for (lag in (minLag + 1) until maxLag) {
            val v = curve[lag]
            if (v >= threshold && v >= curve[lag - 1] && v >= curve[lag + 1]) {
                return rms to lag
            }
        }
        return rms to 0
    }
}
