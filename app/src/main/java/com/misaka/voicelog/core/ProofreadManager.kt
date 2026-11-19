package com.misaka.voicelog.core

import android.content.Context
import com.misaka.voicelog.data.TranscriptEntity
import com.misaka.voicelog.data.VoiceLogDatabase
import com.misaka.voicelog.data.applyProofTracked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 校对的调度层。
 *
 * ## 为什么不用 WorkManager
 * 一轮 221 句 = 13 批 × 约 15s ≈ 3.5 分钟。WorkManager 要额外依赖 + Worker 类 +
 * 通知渠道，换来的唯一好处是「杀进程后能续跑」。而本功能**天然幂等**
 * （见 [VoiceLogDatabase] 里 `proofCandidates` 的 `proofAt = 0` 条件），
 * 用户重进页面再点一次就续上了。不值得为此引一个组件。
 *
 * ## 为什么 scope 挂在 object 上，而不是跟随页面
 * 用 `rememberCoroutineScope` 的话，用户一退出详情页协程就被取消 ——
 * 于是「点完校对去干别的」这个最自然的用法直接失效。挂在单例上，
 * 只要进程活着就继续跑，进度通过 [state] 回传给界面。
 *
 * ## 三条不变量（改代码前先读）
 * 1. **建议永远不自动写进原文**。模型看不到音频，它保证的是「读起来通顺」，
 *    不是「说得一样」。「我在吃面」被改成「我在吃米饭」完全通顺，但错。
 *    所以 [ProofreadClient] 的返回只进 `proofText` 字段，必须用户点采纳。
 * 2. **同一时刻只跑一轮**。两个会话同时校对会让云函数并发打到 AI 配额上限，
 *    而且进度条没法表达。用 [running] 单标志挡住。
 * 3. **中途失败不回滚已完成的批次**。前面的钱已经花了、建议已经有用，
 *    失败只记录原因，让用户重试（幂等，不会重复花钱）。
 */
object ProofreadManager {

    /**
     * 轮询间隔。
     *
     * 为什么不做得更短：服务端任务是「整篇一次送模型」，混元要跑几十秒到十几分钟，
     * 3 秒一次足够灵敏，也不会把云函数调用次数堆起来（每次轮询都是一次函数调用）。
     */
    private const val POLL_INTERVAL_MS = 3_000L

    /**
     * 轮询硬上限：25 分钟。
     *
     * 为什么要有：任务在服务端可能因为超时/异常永远停在 running（比如云函数 900s
     * 被杀而没写终态）。没有上限的话客户端会一直轮询到用户发现为止。
     * 到点就报「可能已在服务端中断」，让用户重试（幂等，不会重复花钱）。
     */
    private const val POLL_MAX_MS = 25 * 60 * 1000L

    /** 轮询连续失败几次才放弃。网络抖一下就终止会让人白等十几分钟 */
    private const val POLL_FAIL_LIMIT = 5

    /**
     * 单片请求体的字节预算。
     *
     * 为什么是 45KB 而不是「接近上限的 100KB」：云开发 HTTP 入口实测在 102,340 字节
     * 通过、102,940 返回 413，中间没有明确文档值；而请求体除了句子还要带
     * scene、sceneNote、token 等字段和 JSON 转义（中文转义后体积会涨）。
     * 留一倍余量，宁可多花一次调用，也不要整批被 413 打回来。
     */
    private const val MAX_PAYLOAD_BYTES = 45_000

    /** 单片句数上限。服务端硬限 2000，这里压到 1500 留余量 */
    private const val MAX_ITEMS_PER_CHUNK = 1_500

    data class UiState(
        /** 正在跑 */
        val running: Boolean = false,
        /** 正在校对哪一轮（0 = 无） */
        val sessionId: Long = 0L,
        /** 已等待秒数（服务端是整篇一次跑，没有批进度可报） */
        val elapsedSec: Int = 0,
        /** 本次送了多少句 */
        val total: Int = 0,
        /** 累计拿到多少条建议 */
        val suggested: Int = 0,
        /** 上次的结论（成功也有一句话） */
        val message: String = "",
        /** 上次失败原因，空 = 没失败 */
        val error: String = "",
        /** 本轮有建议待处理的句子数（含上次遗留的） */
        val pending: Int = 0,
        /** 最近使用的模型，如 cloudbase/hy3 */
        val model: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** 进程级作用域：页面销毁不影响校对继续跑 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 刷新「本轮还有几条建议待处理」，进页面时调一次 */
    suspend fun refresh(db: VoiceLogDatabase, sessionId: Long) {
        val n = withContext(Dispatchers.IO) {
            db.transcriptDao().pendingProofCountNow(sessionId)
        }
        _state.value = _state.value.copy(pending = n)
    }

    /**
     * 开始校对一轮。
     *
     * [force] = true 时先把本轮的建议清空、`proofAt` 归零，让全部句子重新排队 ——
     * 用途是「用户补了人名/词表，想用新配置重跑」。默认 false，断点续跑。
     *
     * 返回 false 表示已经在跑，本次调用被忽略（防止连点）。
     */
    fun start(
        ctx: Context,
        db: VoiceLogDatabase,
        sessionId: Long,
        force: Boolean = false,
    ): Boolean {
        if (job?.isActive == true) return false

        val appCtx = ctx.applicationContext
        _state.value = _state.value.copy(
            running = true,
            sessionId = sessionId,
            elapsedSec = 0,
            total = 0,
            suggested = 0,
            message = "",
            error = "",
        )
        job = scope.launch {
            runProofread(appCtx, db, sessionId, force)
        }
        return true
    }

    /** 用户主动中止。已完成的批次保留（幂等，下次接着跑） */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false, message = "已中止，已完成的部分保留")
    }

    private suspend fun runProofread(
        ctx: Context,
        db: VoiceLogDatabase,
        sessionId: Long,
        force: Boolean,
    ) {
        try {
            val dao = db.transcriptDao()

            if (force) {
                withContext(Dispatchers.IO) { dao.resetProofs(sessionId) }
            }

            val cands = withContext(Dispatchers.IO) { dao.proofCandidates(sessionId) }
            if (cands.isEmpty()) {
                _state.value = _state.value.copy(
                    running = false,
                    message = "这一轮没有可校对的句子（都校对过，或都被手改过）",
                )
                refresh(db, sessionId)
                return
            }

            val session = withContext(Dispatchers.IO) { db.sessionDao().byId(sessionId) }
            val scene = session?.scene ?: ""
            val sceneNote = session?.sceneNote ?: ""

            /* ★ 服务端返回的 `i` 是「相对提交列表」的序号，必须与本地严格同序同长。
               所以先滤掉空句，之后写库只认这份 all —— 中间任何一处多滤一句，
               都会把 A 句的修改写到 B 句上。 */
            val all = cands.filter { it.text.isNotBlank() }
            if (all.isEmpty()) {
                _state.value = _state.value.copy(running = false, message = "没有可校对的有效句子")
                refresh(db, sessionId)
                return
            }

            val url = AppPrefs.cloudUrl(ctx)
            val token = AppPrefs.cloudToken(ctx)

            /* 按请求体预算切片。云函数的 HTTP 入口上限约 100KB（实测 102,340 过、
               102,940 返 413），超了就整批被拒 —— 一句 45 字的会话送 2000 句必炸。
               切成多片顺序提交，每片自己是一个服务端任务，互不影响。 */
            val chunks = chunkByPayload(all)
            var appliedTotal = 0
            var lastModel = ""
            val t0All = System.currentTimeMillis()

            for ((ci, chunk) in chunks.withIndex()) {
                /* 片内用 valid 这个名字：下面的对齐/写库逻辑全部以「本片提交列表」
                   为基准，改名反而容易在两处之间看出错觉。 */
                val valid = chunk

                val started = ProofreadClient.startTask(
                    baseUrl = url,
                    token = token,
                    items = valid.map { (it.speakerName ?: "") to it.text },
                    scene = scene,
                    sceneNote = sceneNote,
                )
                if (!started.ok || started.taskId.isBlank()) {
                    _state.value = _state.value.copy(
                        running = false,
                        error = if (chunks.size > 1) {
                            "第 ${ci + 1}/${chunks.size} 片发起失败：${started.message}。" +
                                "已完成的片不受影响"
                        } else {
                            "发起校对失败：${started.message}"
                        },
                    )
                    return
                }
                lastModel = started.model

                _state.value = _state.value.copy(
                    total = all.size,
                    model = started.model,
                    message = if (chunks.size > 1) {
                        "第 ${ci + 1}/${chunks.size} 片已提交（本片 ${valid.size} 句，共 ${all.size} 句）"
                    } else {
                        "已提交，模型正在通读全文（${valid.size} 句）"
                    },
                )

                val t0 = System.currentTimeMillis()
                var failStreak = 0
                var lastError = ""
                var chunkApplied = 0

            while (true) {
                if (System.currentTimeMillis() - t0 > POLL_MAX_MS) {
                    _state.value = _state.value.copy(
                        running = false,
                        error = "等了 25 分钟还没结果，服务端可能已中断。" +
                            "重试只会重跑没校对过的句子，不会重复花钱",
                    )
                    return
                }
                delay(POLL_INTERVAL_MS)

                val st = ProofreadClient.pollTask(url, token, started.taskId)
                if (!st.ok) {
                    failStreak++
                    lastError = st.message
                    if (failStreak >= POLL_FAIL_LIMIT) {
                        _state.value = _state.value.copy(
                            running = false,
                            error = "查询校对进度连续失败 $failStreak 次：$lastError",
                        )
                        return
                    }
                    /* 网络抖一下就放弃会让人白等十几分钟，先继续轮询 */
                    continue
                }
                failStreak = 0

                val sec = ((System.currentTimeMillis() - t0) / 1000).toInt()
                _state.value = _state.value.copy(
                    elapsedSec = sec,
                    suggested = st.suggested,
                    model = st.model.ifBlank { started.model },
                )

                when (st.status) {
                    "done" -> {
                        val at = System.currentTimeMillis()
                        var applied = 0
                        withContext(Dispatchers.IO) {
                            for (s in st.suggestions) {
                                val target = valid.getOrNull(s.index - 1) ?: continue
                                /* 双重校验：服务端回的 from 必须和本地这句对得上。
                                   对不上说明对齐错了 —— 宁可丢掉这条建议，也不能写错句子。 */
                                if (s.from.isNotBlank() && !sameText(s.from, target.text)) continue
                                dao.setProof(target.id, s.to, s.risk, s.ratio, at)
                                applied++
                            }
                            /* 「校对过但无需修改」的句子也要打戳，否则下次会被再送一遍，
                               等于为同一句话重复付费。 */
                            val touched = st.suggestions.mapNotNull { valid.getOrNull(it.index - 1)?.id }.toSet()
                            for (t in valid) {
                                if (t.id !in touched) dao.markProofChecked(t.id, at)
                            }
                        }
                        chunkApplied = applied
                        appliedTotal += applied
                        /* 不再 `return`：还有下一片要送。这一片的结果已经落库，
                           后面的片挂了也不会连累它（幂等 + 已写完）。 */
                        break
                    }

                    "failed" -> {
                        _state.value = _state.value.copy(
                            running = false,
                            error = if (chunks.size > 1) {
                                "第 ${ci + 1}/${chunks.size} 片校对失败：" +
                                    "${st.error.ifBlank { "服务端没给原因" }}（已完成 ${ci} 片保留）"
                            } else {
                                "校对失败：${st.error.ifBlank { "服务端没给原因" }}"
                            },
                        )
                        return
                    }

                    "canceled" -> {
                        _state.value = _state.value.copy(running = false, message = "已取消")
                        return
                    }
                }
            }

                if (chunkApplied > 0) {
                    _state.value = _state.value.copy(suggested = appliedTotal)
                }
            }

            val secAll = ((System.currentTimeMillis() - t0All) / 1000).toInt()
            _state.value = _state.value.copy(
                running = false,
                suggested = appliedTotal,
                elapsedSec = secAll,
                model = lastModel,
                message = if (appliedTotal > 0) {
                    "校对完成 · $appliedTotal 条建议待确认（${chunks.size} 片，耗时 ${secAll}s）"
                } else {
                    "校对完成 · 没有需要修改的地方（${chunks.size} 片，耗时 ${secAll}s）"
                },
                error = "",
            )
            refresh(db, sessionId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = _state.value.copy(running = false, message = "已中止，已完成的部分保留")
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                running = false,
                error = "校对中断：${e.javaClass.simpleName} ${e.message ?: ""}".trim(),
            )
        }
    }
    /**
     * 批量采纳某一风险等级的建议。
     *
     * 只对 low 开放批量（`risk = "low"` 意味着改动率 <15%，即改了一两个字）：
     * mid/high 整句被重写的概率高，必须逐条看。这个分级由服务端算，客户端不重算 ——
     * 两处算法不一致时会出现「界面说低风险、实际改了一整句」。
     *
     * 返回实际采纳的条数。
     */
    suspend fun applyByRisk(
        db: VoiceLogDatabase,
        sessionId: Long,
        risk: String = "low",
    ): Int = withContext(Dispatchers.IO) {
        val list: List<TranscriptEntity> = db.transcriptDao().proofsByRisk(sessionId, risk)
        var n = 0
        val at = System.currentTimeMillis()
        for (t in list) {
            val suggest = t.proofText ?: continue
            if (suggest.isBlank()) continue
            db.applyProofTracked(t.id, suggest, at)
            n++
        }
        n
    }

    /**
     * 一次性接受本轮**所有**待确认建议（low + mid + high）。
     *
     * 为什么允许 high 也一起过：这是用户明确要的快捷路径 ——
     * 这些文字主要是给日记 AI 当素材，不值得逐句抠。风险由用户承担，
     * 但**写库必须由用户触发**，模型永远不能自己改（这条底线不变）。
     *
     * 返回实际写入条数。
     */
    suspend fun applyAll(db: VoiceLogDatabase, sessionId: Long): Int = withContext(Dispatchers.IO) {
        var n = 0
        val at = System.currentTimeMillis()
        for (risk in listOf("low", "mid", "high")) {
            for (t in db.transcriptDao().proofsByRisk(sessionId, risk)) {
                val suggest = t.proofText ?: continue
                if (suggest.isBlank()) continue
                db.applyProofTracked(t.id, suggest, at)
                n++
            }
        }
        n
    }

    /** 采纳单条。同 [applyByRisk] 的写库路径，只是范围是 1 句 */
    suspend fun applyOne(db: VoiceLogDatabase, id: Long, suggest: String) {
        withContext(Dispatchers.IO) {
            db.applyProofTracked(id, suggest, System.currentTimeMillis())
        }
    }

    /** 忽略单条建议（保留 proofAt → 不会被重复校对，也不会重复花钱） */
    suspend fun dropOne(db: VoiceLogDatabase, id: Long) {
        withContext(Dispatchers.IO) { db.transcriptDao().dropProof(id) }
    }

    /** 只清除本次的错误提示，不清进度（用户重试时调） */
    fun clearError() {
        _state.value = _state.value.copy(error = "")
    }

    /**
     * 按请求体预算把句子切成若干片。
     *
     * 切片只看**字节**，不看句数，因为体积由文字长度决定：100 句长会议发言
     * 可能比 800 句短对话还大。句数上限只是兜住服务端那道 2000 的硬限。
     *
     * 边界一定落在句与句之间（不切断句子）：切断一句会让两片都对不上原文，
     * 而服务端的 `from` 校验会因此把建议全丢掉 —— 钱花了、改没改成。
     */
    private fun chunkByPayload(all: List<TranscriptEntity>): List<List<TranscriptEntity>> {
        if (all.isEmpty()) return emptyList()
        val out = ArrayList<List<TranscriptEntity>>()
        var cur = ArrayList<TranscriptEntity>()
        var bytes = 0
        for (t in all) {
            val size = t.text.toByteArray(Charsets.UTF_8).size + (t.speakerName?.length ?: 0) + 24
            if (cur.isNotEmpty() &&
                (bytes + size > MAX_PAYLOAD_BYTES || cur.size >= MAX_ITEMS_PER_CHUNK)
            ) {
                out.add(cur)
                cur = ArrayList()
                bytes = 0
            }
            cur.add(t)
            bytes += size
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /**
     * 文本比对：忽略空白与全半角差异。
     *
     * 为什么不能直接用 `==`：服务端会把 `[说话人]` 前缀剥掉、把连续空格规范掉，
     * 传过去和回过来本来就可能差几个空格。用严格相等会让大量合法建议被误丢弃，
     * 而这些被丢的建议是**已经花过钱**的。
     */
    private fun sameText(a: String, b: String): Boolean =
        a.replace(Regex("\\s+"), "") == b.replace(Regex("\\s+"), "")
}
