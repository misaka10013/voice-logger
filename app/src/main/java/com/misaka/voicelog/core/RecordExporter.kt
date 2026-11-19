package com.misaka.voicelog.core

import android.content.Context
import com.misaka.voicelog.data.SessionEntity
import com.misaka.voicelog.data.TranscriptEntity
import com.misaka.voicelog.data.VoiceLogDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 把会话导出成 Markdown。
 *
 * ## 为什么是 md 而不是 txt
 * md 在记事本里也是纯文本，但丢进 Obsidian / Typora / 任何 AI 都能保留结构
 * （几点说的、谁说的、什么场景）。txt 只剩一坨，回头自己都读不出谁在说话。
 *
 * ## 为什么写 cache/exports 再分享，而不是直接写下载目录
 * Android 10+ 直接写外部存储要 MANAGE_EXTERNAL_STORAGE（应用商店基本不给，
 * 用户看着也吓人）。走 FileProvider + 系统分享面板，用户想存哪就存哪，
 * 不需要任何存储权限 —— 而且顺手就能发微信。
 *
 * 格式刻意与云端 `/ai/voicelog?format=md` 保持一致：
 * 同一段对话，从手机导出和从接口读到的东西应该是同一个样子。
 */
object RecordExporter {

    private val TZ: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /**
     * 导出指定会话为 md 文件。
     *
     * @return 生成的临时文件（调用方负责用 FileProvider 换成 Uri 并分享）
     */
    suspend fun exportMarkdown(
        ctx: Context,
        db: VoiceLogDatabase,
        sessionIds: List<Long>,
    ): File = withContext(Dispatchers.IO) {
        val sessions = db.sessionDao().byIds(sessionIds).sortedBy { it.startWall }
        val txBySession = db.transcriptDao().listBySessions(sessionIds).groupBy { it.sessionId }

        val text = buildString {
            val days = sessions.map { dateOf(it.startWall) }.distinct()
            val span = when {
                days.isEmpty() -> ""
                days.size == 1 -> days[0]
                else -> days.first() + " ~ " + days.last()
            }
            val totalItems = sessions.sumOf { (txBySession[it.id] ?: emptyList()).size }

            appendLine("# $span 语音记录")
            appendLine()
            appendLine("共 ${sessions.size} 轮 · $totalItems 句")

            /* 多天时补一层日期标题，单天时省掉 —— 只有一天还硬加一层，
               md 大纲里会出现「一个只有一条子节点」的冗余层级。 */
            var lastDay = ""
            for (s in sessions) {
                val day = dateOf(s.startWall)
                if (days.size > 1 && day != lastDay) {
                    appendLine()
                    appendLine("---")
                    appendLine()
                    appendLine("## $day")
                    lastDay = day
                }
                val items = txBySession[s.id] ?: emptyList()
                appendLine()
                appendLine(level(s, items, days.size > 1))
                /* 时间轴偏移提示放在标题下方、句子之前 —— 先告诉他「这些 HH:mm 不可全信」，
                   再去读句子。写在末尾的话，按时间找内容的人已经翻完一遍了。 */
                skewNoteOf(s, items)?.let {
                    appendLine()
                    appendLine(it)
                }
                for (t in items) {
                    val who = t.speakerName?.takeIf { it.isNotBlank() }?.let { "$it：" } ?: ""
                    val edited = if (t.edited) "（人工改过）" else ""
                    appendLine("- **${clock(t.wallClock)}** $who${t.text}$edited")
                }
            }
        }

        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        /* 时间戳进文件名：导两次不会互相覆盖，也方便回看是哪次导的 */
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val f = File(dir, "voicelog-$stamp.md")
        f.writeText(text, Charsets.UTF_8)
        f
    }

    private fun level(s: SessionEntity, items: List<TranscriptEntity>, multiDay: Boolean): String {
        val range = clock(s.startWall) + "–" + if (s.endWall > s.startWall) clock(s.endWall) else "未结束"
        val scene = s.scene?.takeIf { it.isNotBlank() }?.let {
            " ｜场景：" + it + (s.sceneNote?.takeIf { n -> n.isNotBlank() }?.let { n -> "（$n）" } ?: "")
        } ?: ""
        val head = if (multiDay) "### " else "## "
        val empty = if (items.isEmpty()) " ｜无转写文字" else ""
        return head + range + scene + empty
    }

    /**
     * 时间轴偏移提示。返回 null = 这一轮时间戳可信，不必打扰阅读。
     *
     * ## 为什么要写进导出文件
     * 2026-11-16 之前的版本，句子墙钟由「采集开始墙钟 + 样本累计」推算。采集停摆期间
     * （麦克风被别的应用占用、来电让麦、读异常退避重试）真实时间在流逝但不产生样本，
     * 于是停摆之后的所有句子整体偏早，且偏得越多越往后。
     * 11-15 实测：一轮 645 分钟的会话，最后一句被标成 19:25，而实际录到 21:58 —— **偏早 153 分钟**。
     *
     * 这个偏差**无法从数据里还原**（不知道每个停摆发生在第几个样本处），
     * 所以只能把「不可全信」这件事显式写在文件里。不写的话，拿到 md 的人
     * 会以为这段对话真的发生在下午 5 点，而且会拿它去和别的记录对齐 —— 越用越错。
     *
     * 阈值 5 分钟：正常的使用里停几秒、几十秒很常见（别人发一条语音就会触发），
     * 每次都提示会让整个文件变成免责声明；5 分钟以上才是「可能找错时间」的量级。
     */
    private fun skewNoteOf(s: SessionEntity, items: List<TranscriptEntity>): String? {
        if (s.endWall <= s.startWall || items.isEmpty()) return null
        val last = items.maxOf { it.wallClock }
        val gap = s.endWall - last
        if (gap < 5 * 60_000L) return null
        val min = gap / 60_000L
        val span = clock(s.startWall) + "–" + clock(s.endWall)
        return "> ⚠️ 本轮约 $min 分钟没有录到声音（麦克风被占用或异常）。" +
            "下列时间戳由录音样本推算，整体偏早约 $min 分钟；" +
            "真实起止是 $span，按时间找内容请以这个区间为准。"
    }

    private fun dateOf(ms: Long): String {        val f = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        f.timeZone = TZ
        return f.format(Date(ms))
    }

    private fun clock(ms: Long): String {
        val f = SimpleDateFormat("HH:mm", Locale.CHINA)
        f.timeZone = TZ
        return f.format(Date(ms))
    }
}
