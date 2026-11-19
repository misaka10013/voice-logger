package com.misaka.voicelog.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * 基频估计的回归测试。
 *
 * 分两组：
 * 1. **合成信号** —— 精确验证三个防错设计（低八度修正 / 自适应能量门槛 / 帧数门槛）
 *    是否真的按设计工作。合成信号有已知真值，所以这里能断言精确值。
 * 2. **真实录音** —— 拿会话 32 的 5 段真实音频，断言 Kotlin 实现与离线 numpy
 *    参考实现（`f0_truth.csv` 那批）结论一致。这防的是「移植时改坏了一行」，
 *    光靠合成信号测不出来。
 *
 * 为什么需要这组测试：F0 直接决定说话人标错还是留空，而它的失效方式是
 * **静默的** —— 返回一个看似合理的错误数值，界面上看不出异常，只有翻对话
 * 记录才会发现人被标错了。所以必须把「已知输入 → 期望输出」钉死。
 */
class PitchDetectorTest {

    private val sr = 16_000

    /** 生成正弦（可叠加强谐波）信号 */
    private fun tone(
        f0: Double,
        durSec: Double,
        amp: Double = 0.2,
        harmonics: List<Pair<Int, Double>> = emptyList(),
    ): FloatArray {
        val n = (sr * durSec).toInt()
        val norm = 1.0 + harmonics.sumOf { it.second }
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            var v = sin(2 * PI * f0 * t)
            for ((mult, w) in harmonics) v += w * sin(2 * PI * f0 * mult * t)
            (amp * v / norm).toFloat()
        }
    }

    // ---------------------------------------------------------------- 合成信号

    @Test
    fun `男声基频 110Hz 被正确估出`() {
        val f0 = PitchDetector.estimate(tone(110.0, 1.5))
        assertNotNull("110Hz 的纯音应当能估出基频", f0)
        assertEquals(110.0, f0!!.toDouble(), 5.0)
    }

    @Test
    fun `女声基频 230Hz 被正确估出`() {
        val f0 = PitchDetector.estimate(tone(230.0, 1.5))
        assertNotNull("230Hz 的纯音应当能估出基频", f0)
        assertEquals(230.0, f0!!.toDouble(), 5.0)
    }

    /**
     * 低八度修正的核心用例。
     *
     * 信号周期是 1/110 秒，但能量集中在 220Hz 分量上 —— 自相关在 lag=2T 处
     * 会比 lag=T 处略高，如果按「取全局最大峰」实现，会返回 220Hz（把男声判成女声）。
     * 正确实现取「第一个达到最高峰 85% 的局部峰」，返回 110Hz。
     */
    @Test
    fun `能量偏向二次谐波时仍取真基频而非谐波`() {
        val f0 = PitchDetector.estimate(tone(110.0, 1.5, harmonics = listOf(2 to 1.6)))
        assertNotNull(f0)
        assertEquals("不能返回 220Hz（二次谐波）", 110.0, f0!!.toDouble(), 6.0)
    }

    @Test
    fun `静音返回 null 而不是随便给个数`() {
        assertNull(PitchDetector.estimate(FloatArray(sr * 2)))
    }

    @Test
    fun `白噪声返回 null`() {
        val rnd = Random(42)
        val noise = FloatArray(sr * 2) { (rnd.nextFloat() - 0.5f) * 0.3f }
        assertNull("白噪声没有周期性，不能编一个基频出来", PitchDetector.estimate(noise))
    }

    @Test
    fun `短于两帧的音频返回 null`() {
        assertNull(PitchDetector.estimate(tone(150.0, 0.02)))
    }

    /**
     * 很小的音量也要能估出来。
     *
     * 这条是踩坑记录：第一版写死 RMS >= 0.008，结果真机远场录音（RMS 中位数
     * 0.0005~0.006）几乎全被判成静音，覆盖率只有 30%。改成「段内 RMS 中位数
     * 的一半」后覆盖率 95%。这里用 0.004 振幅回归验证 —— 它低于旧门槛。
     */
    @Test
    fun `远场小音量也能估出基频`() {
        val f0 = PitchDetector.estimate(tone(145.0, 1.5, amp = 0.004))
        assertNotNull("0.004 振幅（约 -48dBFS）是实测远场常见电平，必须能算", f0)
        assertEquals(145.0, f0!!.toDouble(), 6.0)
    }

    // ---------------------------------------------------------------- 真实录音

    /**
     * 真实音频回归：断言 Kotlin 实现与离线 numpy 参考实现结论一致。
     *
     * 真值来自 `tools/f0_reference.py` 在同一批音频上的输出。容差 ±8Hz 是
     * 因为两条实现的浮点累加顺序不同（Kotlin 顺序累加 vs numpy 向量化），
     * 允许 1Hz 量级抖动；8Hz 已经远小于男女分界间距（160↔200Hz）。
     */
    @Test
    fun `真实录音与离线参考实现结论一致`() {
        /* 这 5 段是家里真实对话的片段，属隐私数据，已写进 .gitignore 不入库。
           换台机器 clone 下来就没有它们 —— 此时跳过本组，合成信号那组照跑。 */
        assumeTrue(
            "本地缺少真实录音样本（隐私数据不入库），跳过本组",
            javaClass.classLoader!!.getResource("f0/1422.wav") != null,
        )

        // file → 期望基频（null 表示参考实现也判不出来）
        val cases = listOf(
            Triple("1422.wav", 110.0, "男声，短句"),
            Triple("1424.wav", 235.5, "女声"),
            Triple("1435.wav", 179.8, "落在未判区间"),
            Triple("1442.wav", 113.9, "最短样本 0.7 秒"),
            Triple("1444.wav", null, "参考实现判不出，Kotlin 也必须判不出"),
        )
        for ((file, expected, note) in cases) {
            val samples = readWav("f0/$file")
            val actual = PitchDetector.estimate(samples, sr)
            if (expected == null) {
                assertNull("$file（$note）参考实现无法估计，不应给出数值", actual)
            } else {
                assertNotNull("$file（$note）应当能估出基频", actual)
                assertTrue(
                    "$file（$note）期望 ${expected}Hz，实际 ${actual}Hz，偏差超过 8Hz",
                    abs(actual!!.toDouble() - expected) <= 8.0,
                )
            }
        }
    }

    /** 读 16bit 单声道 PCM wav（遍历 chunk，不假定 44 字节定长头） */
    private fun readWav(path: String): FloatArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        var pos = 12 // 跳过 "RIFF" + size + "WAVE"
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            if (id == "data") {
                dataOffset = pos + 8
                dataLen = minOf(size, bytes.size - dataOffset)
                break
            }
            pos += 8 + size + (size and 1) // chunk 按 2 字节对齐
        }
        require(dataOffset > 0) { "$path 里找不到 data chunk" }

        val n = dataLen / 2
        return FloatArray(n) { buf.getShort(dataOffset + it * 2) / 32768f }
    }
}
