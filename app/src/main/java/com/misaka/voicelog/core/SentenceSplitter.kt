package com.misaka.voicelog.core

/**
 * 把 ASR 输出的一整段文字按**句末标点**切成多句。
 *
 * ## 为什么需要它
 * VAD 只管「哪一段有声音」，模型只管「这段声音是什么字」，**没有任何一环负责断句**。
 * 实测一份 215 行、10,005 字的会话里有 369 个句末标点（306 个「。」+ 63 个「？」），
 * 平均每行 1.72 句 —— 一段 8 秒录音里挤着两个人的对话是常态。
 *
 * 而**一行只能挂一个说话人标签**，不切开就永远标不上「这句是谁说的」。
 * 在界面上手动拆 369 次不现实，所以拆在数据入口（转写）和事后补一刀（重新断句）。
 *
 * ## 规则都是保守的，宁可少切不要切碎
 * - 只在 `。？！?!` 之后切；标点跟着**前一句**尾巴走，不丢
 * - 连续标点（`？！`、`……。`）算同一个边界，不切出空句
 * - 换行符也算边界（部分引擎会输出）
 * - 纯空白/纯标点的片段丢弃
 * - 只切出 1 句就原样返回（短句走这条，零额外开销）
 * - 切出超过 80 句视为引擎异常（可能吐了一串句号），放弃拆，保留原文
 *
 * ## 为什么不合并过短的句子
 * 「对。」「嗯。」这种两字句在对话里是完整的一句，而且往往**正是换人说话的地方**。
 * 合并等于把两个人的话又黏回去，与拆分的初衷矛盾。
 */
object SentenceSplitter {

    /** 超过这个句数就认为切分异常，放弃（正常一整段 ASR 输出不会有 80 句） */
    private const val MAX_PIECES = 80

    fun split(raw: String): List<String> {
        val t = raw.trim()
        if (t.isEmpty()) return emptyList()

        val out = ArrayList<String>(4)
        val sb = StringBuilder()

        fun flush() {
            val s = sb.toString().trim()
            sb.setLength(0)
            if (s.isEmpty()) return
            /* 全是标点/空白的片段没有信息量，留着只会变成一行「……」 */
            if (s.none { it.isLetterOrDigit() }) return
            out.add(s)
        }

        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\n' || c == '\r') {
                flush()
                i++
                continue
            }
            sb.append(c)
            if (!isEnd(c)) {
                i++
                continue
            }
            /* 句末标点后面若还跟着标点（「？！」「。。。'」），一起收进这一句 */
            var j = i + 1
            while (j < t.length && isEnd(t[j])) {
                sb.append(t[j])
                j++
            }
            flush()
            i = j
        }
        flush()

        return when {
            out.isEmpty() -> listOf(t)
            out.size == 1 -> listOf(t)
            out.size > MAX_PIECES -> listOf(t)
            else -> out
        }
    }

    private fun isEnd(c: Char) =
        c == '。' || c == '？' || c == '！' || c == '?' || c == '!'
}
