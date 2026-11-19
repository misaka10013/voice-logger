package com.misaka.voicelog.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 用系统 TTS 朗读一段测试语音。
 *
 * ## 用途不是「好玩」，是排查
 * 这条链路里任何一环坏掉，用户看到的现象都是同一句话：「录不到」。
 * 可能是麦克风被占用、可能是 VAD 模型没加载、可能是编码失败。
 * 有一个「放一段人声让你自己听」的按钮，就能把问题劈成两半：
 * 播放有声音 → 扬声器没问题，问题在采集；播放没声音 → 先修系统 TTS。
 *
 * ## 为什么用 TTS 而不是内置一段录音
 * 内置音频要额外打包文件，TTS 不需要；而且 TTS 出来的就是「人声频段」，
 * 正好能触发 Silero VAD（提示音、音乐不一定能触发）。
 */
object TtsProbe {

    private const val TAG = "TtsProbe"

    /** 测试文本：短句 + 停顿，凑够 3~4 个 VAD 段 */
    const val TEST_TEXT =
        "测试一下录音。这是第一句话。今天天气不错。晚上一起吃饭吗。"

    /**
     * 朗读测试语音。不阻塞调用方，失败只记日志（这个按钮不能因为 TTS 坏了就崩）。
     */
    fun speak(ctx: Context, text: String = TEST_TEXT) {
        var engine: TextToSpeech? = null
        var closed = false

        fun shutdown() {
            if (closed) return
            closed = true
            runCatching { engine?.stop() }
            runCatching { engine?.shutdown() }
            engine = null
            Log.d(TAG, "TTS 已释放")
        }

        engine = TextToSpeech(ctx.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS 初始化失败 status=$status")
                shutdown()
                return@TextToSpeech
            }
            val e = engine ?: return@TextToSpeech
            val cn = e.setLanguage(Locale.CHINA)
            if (cn == TextToSpeech.LANG_MISSING_DATA || cn == TextToSpeech.LANG_NOT_SUPPORTED) {
                // 没有中文语音包就退回默认语言，英文也能触发 VAD
                e.language = Locale.getDefault()
            }
            e.setSpeechRate(0.95f)
            e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vl-probe")
        }

        /* TTS 的 speak() 是异步的，没有「说完了」的回调能可靠等到。
           按文本长度估一个上限，到点统一释放 —— 不释放会一直占着 TTS 引擎。 */
        val estMs = (text.length * 260L).coerceIn(6_000L, 30_000L)
        Handler(Looper.getMainLooper()).postDelayed({ shutdown() }, estMs)
    }
}
