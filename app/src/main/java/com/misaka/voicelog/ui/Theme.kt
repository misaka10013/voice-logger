package com.misaka.voicelog.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CB8FF),
    onPrimary = Color(0xFF00204A),
    background = Color(0xFF101418),
    surface = Color(0xFF181E24),
    onSurface = Color(0xFFE4E8ED),
    surfaceVariant = Color(0xFF222A32),
    secondaryContainer = Color(0xFF1E3A5F),
    onSecondaryContainer = Color(0xFFCFE3FF),
)

/**
 * 全局配色常量。
 *
 * 单独抽出来的原因：说话人标签、状态点、告警色在不同页面要严格一致，
 * 否则同一个「他人」在列表页和详情页显示成两种紫，会让人以为是不同来源的数据。
 */
object VlColor {
    val Bg = Color(0xFF101418)
    val Surface = Color(0xFF181E24)
    val SurfaceVar = Color(0xFF222A32)
    val Line = Color(0xFF2A3038)

    val Text = Color(0xFFE4E8ED)
    val Sub = Color(0xFF8A949E)
    val Dim = Color(0xFF5B656E)

    val Accent = Color(0xFF7CB8FF)
    val Green = Color(0xFF42D77D)
    val Red = Color(0xFFE05B5B)
    val Amber = Color(0xFFFFB74D)

    /** 「我」的标签配色 */
    val MeBg = Color(0xFF17334F)
    val MeFg = Color(0xFF8CC5FF)

    /** 「他人」的标签配色 */
    val OtherBg = Color(0xFF35293F)
    val OtherFg = Color(0xFFC3A6E0)

    /** 用户手动指定的具名说话人 */
    val NamedBg = Color(0xFF1F3A2E)
    val NamedFg = Color(0xFF7FD8A5)

    /**
     * 「视频噪音」：电视、短视频里的人声。
     *
     * 必须和具名说话人分开配色 —— 原来它和「说话人B」共用同一套绿，
     * 322 句的会话里 117 句噪音和 39 句说话人B在列表上长得一模一样，
     * 扫一眼分不出哪些是被排除的、哪些是真人在说话。
     *
     * 选棕黄是因为它在语义上「不是人」：色相 35°，和具名的绿（145°）
     * 拉开 110°，色弱也能分辨；同时不像红那样报警，噪音是常态不是异常。
     */
    val NoiseBg = Color(0xFF3A3228)
    val NoiseFg = Color(0xFFD4AE7A)

    /** 未标注 */
    val NoneBg = Color(0xFF2A3038)
    val NoneFg = Color(0xFF8A949E)
}

@Composable
fun VoiceLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
