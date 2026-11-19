package com.misaka.voicelog.core

/**
 * 把「用户改了一句文字」这件事拆成可统计的量。
 *
 * ## 为什么需要它
 * 过去只知道「这句被改过」（`edited = 1`），不知道**改了多少、改的是什么性质**。
 * 一个字段的布尔值撑不起任何判断：删掉三个「嗯」和整句听错是同一件事，
 * 于是「识别准不准」只能靠感觉，调参也没有方向。
 *
 * 拆成 [Result.ins] / [Result.del] / [Result.sub] 之后：
 * - `del` 集中在口水词 → 该收敛 ASR 的标点/填充词，不是模型问题
 * - `ins` 多（原文缺字）→ VAD 切太早 / 增益太低，字被吃掉
 * - `sub` 多且长度相近 → 同音混淆，是**模型上限**问题，调参无用
 * - 整句都对不上 → 串音/噪音被当成人声
 *
 * ## 为什么用编辑距离而不是简单的「相不相等」
 * 用户经常只动标点、只删一个「嗯」。按字符串相等判断会把这类编辑算成 100% 错误，
 * 让字错率虚高到没有参考价值。**[normalize] 先把标点空白抹掉**，
 * 再做字符级对齐，才是「听错的程度」而不是「格式的差异」。
 *
 * ## 为什么不引入 diff-match-patch 之类的库
 * 句子长度实测中位数 12 字、99 分位 60 字，O(n·m) 的 DP 在这个量级是微秒级。
 * 为一次编辑多引一个依赖、多一份混淆规则的维护成本，不划算。
 */
object TextDiff {

    /** 改动性质。与 `transcript_edit.kind` 落库值一一对应，改名会断历史数据 */
    object Kind {
        /** 一字未动（只改了标点空白） —— 不算错误 */
        const val SAME = "same"

        /** 只删了口水词（嗯/啊/呃…）—— 是「整理」不是「听错」 */
        const val FILLER = "filler"

        /** 只补了字：原文漏了 —— VAD/增益的锅 */
        const val INSERT = "insert"

        /** 只删了字（非口水词）：原文多了 —— 噪音被当人声 */
        const val DELETE = "delete"

        /** 只换字、长度几乎不变：同音混淆 —— 模型上限 */
        const val SUBSTITUTE = "substitute"

        /** 增删改都有：整句没听清 */
        const val MIXED = "mixed"
    }

    /**
     * 口水词单字表。
     *
     * 只收「单独出现时几乎不含信息」的字。刻意**不收**「就、是、那、这」这类
     * 高频实义词的构件 —— 把「就是」的「就」当成口水词删掉，会把真正的
     * 听错（把「九个」听成「几个」）也算成整理，反而掩盖问题。
     */
    private val FILLER_CHARS = "嗯啊呃额哦噢唉哎诶欸呀哇吧呢嘛噢".toSet()

    /** 口水词多字表。按长度降序匹配，避免「那个」被「那」抢先吃掉 */
    private val FILLER_WORDS = listOf(
        "就是说", "那个", "这个", "然后", "其实", "反正", "你看", "对不对", "是不是",
    ).sortedByDescending { it.length }

    /**
     * 对比结果。
     *
     * 计数的单位是**归一化之后的字符**（去掉标点空白），所以数字是「字」不是「字节」。
     */
    data class Result(
        val ins: Int,
        val del: Int,
        val sub: Int,
        val kind: String,
        /** 全量字错率 =(S+D+I) / 真值长度，0~1（可能 >1，原句被大幅改写时） */
        val cer: Float,
        /** 剔除纯口水词删除后的字错率。判断「模型准不准」应该看这个 */
        val cerClean: Float,
    ) {
        val total: Int get() = ins + del + sub
        val isSame: Boolean get() = total == 0
    }

    /**
     * 比较识别原文 [before] 与用户改后的 [after]。
     *
     * 方向约定：[before] 是待检验的机器输出，[after] 是用户认定正确的真值。
     * 所以「插入」指用户补上了原文没有的字 —— 即**原文漏字**。
     */
    fun compare(before: String, after: String): Result {
        val a = normalize(before)
        val b = normalize(after)
        if (a == b) {
            return Result(0, 0, 0, Kind.SAME, 0f, 0f)
        }

        /* 超长句降级：DP 是 O(n·m)，600×600 也才 36 万格，仍可接受；
           再往上（粘贴整段、异常长句）就只按长度差估，避免一次编辑卡住主线程。 */
        if (a.length > MAX_DP || b.length > MAX_DP) {
            val d = kotlin.math.abs(a.length - b.length)
            val ins = (b.length - a.length).coerceAtLeast(0)
            val del = (a.length - b.length).coerceAtLeast(0)
            val sub = minOf(a.length, b.length)
            return Result(ins, del, sub, Kind.MIXED, cerOf(ins, del, sub, b.length), cerOf(ins, del, sub, b.length))
        }

        val script = align(a, b)
        val denom = b.length.coerceAtLeast(1)

        /* 纯删除的情况下可以直接整段判「是不是全是口水词」。必须整段判：
           「那个」两个字的任何一个都不在单字表里，逐字看会把一次整理判定成
           「原文多字」→ 结论变成「该查 VAD 阈值」，方向就错了。 */
        val pureDelete = script.ins == 0 && script.sub == 0 && script.del > 0
        val allFiller = pureDelete && isAllFiller(script.deletedChars)
        val fillerDel = when {
            allFiller -> script.del
            else -> script.deletedChars.count { isFillerChar(it) }
        }
        val realDel = script.del - fillerDel
        val cer = cerOf(script.ins, script.del, script.sub, denom)
        val cerClean = cerOf(script.ins, realDel, script.sub, denom)

        val kind = when {
            script.ins == 0 && script.del == 0 && script.sub == 0 -> Kind.SAME
            allFiller -> Kind.FILLER
            script.del == 0 && script.sub == 0 -> Kind.INSERT
            script.ins == 0 && script.sub == 0 -> Kind.DELETE
            script.ins == 0 && script.del == 0 -> Kind.SUBSTITUTE
            else -> Kind.MIXED
        }
        return Result(script.ins, script.del, script.sub, kind, cer, cerClean)
    }

    // ------------------------------------------------------------------ 内部

    private const val MAX_DP = 600

    private data class Script(val ins: Int, val del: Int, val sub: Int, val deletedChars: String)
    private fun cerOf(ins: Int, del: Int, sub: Int, denom: Int) =
        ((ins + del + sub).toFloat() / denom.coerceAtLeast(1)).coerceAtMost(9.99f)

    /**
     * 只保留有信息的字符：中文、字母、数字。
     *
     * 去掉标点与空白是刻意的 —— 用户重排断句、补个句号，不代表识别错了；
     * 把它们算进编辑距离，会让「字错率」变成一个跟模型无关的排版指标。
     */
    fun normalize(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c.code < 128 -> if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
                Character.isLetterOrDigit(c) -> sb.append(c)
                // 中文标点、全角符号、空白一律丢弃
                else -> Unit
            }
        }
        return sb.toString()
    }

    /** 标准 Levenshtein DP + 回溯，只统计操作数（不需要具体对齐路径） */
    private fun align(a: String, b: String): Script {
        val n = a.length
        val m = b.length
        val w = m + 1
        val d = IntArray((n + 1) * w)
        for (i in 0..n) d[i * w] = i
        for (j in 0..m) d[j] = j
        for (i in 1..n) {
            val ca = a[i - 1]
            val row = i * w
            val prow = (i - 1) * w
            for (j in 1..m) {
                val eq = if (ca == b[j - 1]) 0 else 1
                val del = d[prow + j] + 1
                val ins = d[row + j - 1] + 1
                val sub = d[prow + j - 1] + eq
                d[row + j] = minOf(del, ins, sub)
            }
        }
        // 回溯
        var i = n
        var j = m
        var ins = 0
        var del = 0
        var sub = 0
        val deleted = StringBuilder()
        while (i > 0 || j > 0) {
            val cur = d[i * w + j]
            if (i > 0 && j > 0) {
                val eq = if (a[i - 1] == b[j - 1]) 0 else 1
                if (cur == d[(i - 1) * w + (j - 1)] + eq) {
                    if (eq == 1) sub++ 
                    i--; j--
                    continue
                }
            }
            if (i > 0 && cur == d[(i - 1) * w + j] + 1) {
                del++
                deleted.append(a[i - 1])
                i--
                continue
            }
            if (j > 0 && cur == d[i * w + (j - 1)] + 1) {
                ins++
                j--
                continue
            }
            // 理论上到不了这里，防御性退出
            i = 0; j = 0
        }
        return Script(ins, del, sub, deleted.reverse().toString())
    }

    private fun isFillerChar(c: Char) = c in FILLER_CHARS

    /**
     * 一段被删掉的文字是不是「全是口水词」。
     *
     * 会被 [TranscriptEditDao] 之外的调用方用来解释结果，所以公开。
     * 注意多字词要先剥掉再判单字，否则「那个」的「个」会被当成实义词。
     */
    fun isAllFiller(s: String): Boolean {
        var rest = s
        FILLER_WORDS.forEach { rest = rest.replace(it, "") }
        return rest.all { isFillerChar(it) || it.isWhitespace() }
    }

    /** 给界面用的说明文案。不做国际化，跟着产品语言走 */
    fun describe(kind: String): String = when (kind) {
        Kind.SAME -> "只调整标点"
        Kind.FILLER -> "删口水词"
        Kind.INSERT -> "原文漏字"
        Kind.DELETE -> "原文多字"
        Kind.SUBSTITUTE -> "同音字"
        Kind.MIXED -> "整句重写"
        else -> kind
    }
}
