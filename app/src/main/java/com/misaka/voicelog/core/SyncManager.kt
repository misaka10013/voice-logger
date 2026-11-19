package com.misaka.voicelog.core

import android.content.Context
import com.misaka.voicelog.data.SessionEntity
import com.misaka.voicelog.data.VoiceLogDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 把本地转写文字推到云端。
 *
 * ## 边界：只推文字，永不推音频
 * 音频是素材，文字是资产。这一条是产品前提，不是实现选择 ——
 * 见 `vl.js` 顶部注释。所以这里连 segment 表都不碰。
 *
 * ## 只推「已转写」的会话
 * 只有音频、没有文字的会话推上去等于创建一堆空壳，而且会让外部 AI
 * 误判「那个时段没说话」。所以句子为空的会话直接跳过，**连云端文档都不建**。
 *
 * ## 冲突策略：服务端 last-write-wins，客户端每次带「现在」作为 updatedAt
 * 单设备场景下这是最简且正确的做法：本地永远是事实源。
 * `updatedAt` 取当前时刻而不是会话开始时刻，是因为用户可能在今天改昨天的记录，
 * 用会话时刻会让这次修改在服务端看起来「比云端旧」而被跳过。
 *
 * ## 断点与幂等
 * sid 在首次上传前就由本地生成为 UUID 并持久化，所以：
 * - 同一批重传不会产生重复文档（服务端按 sid upsert）
 * - 卸载重装后本地自增 id 归零也不会覆盖历史（UUID 不会撞）
 */
object SyncManager {

    /** 服务端 MAX_SESSIONS_PER_SYNC = 20，超过会被 400 整批拒绝 */
    private const val BATCH = 20

    /**
     * 单次请求体上限。
     *
     * ⚠️ 这不是服务端限制，是**腾讯云网关**的限制：HTTP 访问服务在请求到达云函数之前
     * 就把 body 卡在 102,400 字节（实测 102,340 通过 / 102,940 返回 413
     * `EXCEED_MAX_PAYLOAD_SIZE`）。所以服务端把 MAX_CHARS_PER_SESSION 写成 40 万字符
     * 是没有意义的 —— 那么大的包永远到不了函数。
     *
     * 取 60KB 而不是 100KB：一轮 46 分钟对话就有近千句、约 90KB，正好擦边。
     * 留 40% 余量给「估算偏小」和「JSON 转义膨胀」，宁可多发一次请求。
     */
    private const val MAX_BODY_BYTES = 60 * 1024L

    /** 每句 JSON 的固定开销：{"c":...,"t":"...","s":"...","a":0,"sc":0.87,"e":0}, */
    private const val ITEM_OVERHEAD = 62L

    /** 每轮会话头的固定开销：sid/date/startMs/endMs/scene/sceneNote/items/updatedAt */
    private const val SESSION_OVERHEAD = 200L

    private val TZ: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    data class UiState(
        /** 正在同步（界面显示进度/转圈） */
        val running: Boolean = false,
        /** 待同步轮数（有改动或有句子改动的会话数） */
        val dirty: Int = 0,
        /** 本地已删、云端还没打上删除标记的轮数 */
        val pendingDel: Int = 0,
        /** 上次成功时刻，0 = 从未成功 */
        val lastAt: Long = 0L,
        /** 上次失败原因，空 = 没失败 */
        val lastError: String = "",
        /** 最近一次的结论（成功也有一句话，便于确认「确实同步了」） */
        val lastMessage: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** 防止用户连点「立即同步」时并发跑两轮，产生重复请求与错乱的回写 */
    private val mutex = Mutex()

    /** 进设置页时刷新一次计数，让用户看到「还有几轮没同步」 */
    suspend fun refresh(ctx: Context, db: VoiceLogDatabase) {
        val st = _state.value
        _state.value = st.copy(
            dirty = countDirty(db),
            pendingDel = countPendingDel(db),
            lastAt = AppPrefs.syncLastAt(ctx),
            lastError = AppPrefs.syncLastError(ctx),
        )
    }

    private suspend fun countDirty(db: VoiceLogDatabase): Int =
        withContext(Dispatchers.IO) {
            runCatching { db.sessionDao().dirtyIds().size }.getOrDefault(0)
        }

    private suspend fun countPendingDel(db: VoiceLogDatabase): Int =
        withContext(Dispatchers.IO) {
            runCatching { db.sessionDao().pendingDeletes().size }.getOrDefault(0)
        }

    /** 「测试连接」：空 body 打 /vl/sync，只验通，不推数据（线程切换由 SyncClient 内部负责） */
    suspend fun testConnection(ctx: Context): SyncClient.Reply = SyncClient.post(
        baseUrl = AppPrefs.cloudUrl(ctx),
        token = AppPrefs.cloudToken(ctx),
        body = null,
    )

    /**
     * 清掉上一次的失败记录。
     *
     * 为什么改配置时要清：错误信息描述的是「用旧配置发请求的结果」。用户把 token
     * 改对了，界面还挂着「token 不对」的红字，就会以为没修好 —— 而实际上他连一次
     * 请求都还没重发。旧结论在新配置下没有任何信息量。
     */
    fun clearError(ctx: Context) {
        AppPrefs.setSyncLastError(ctx, "")
        _state.value = _state.value.copy(lastError = "")
    }

    /**
     * 全量推送待同步会话。
     *
     * 返回最后一次请求的回执（供界面显示）；配置不全或没有待同步内容时返回 null，
     * 并把结论写进 [state.lastMessage]。
     */
    suspend fun syncNow(ctx: Context, db: VoiceLogDatabase): SyncClient.Reply? = mutex.withLock {
        val url = AppPrefs.cloudUrl(ctx)
        val token = AppPrefs.cloudToken(ctx)
        if (url.isBlank() || token.isBlank()) {
            setMessage("还没配置云端地址或 token，先去设置里填")
            return@withLock null
        }

        _state.value = _state.value.copy(running = true, lastError = "", lastMessage = "")
        AppPrefs.setSyncLastError(ctx, "")

        try {
            /* 先把积压的删除补发掉。顺序重要：如果先推活数据再推删除，
               用户在同步过程中删掉的那一轮会在「活数据」批次里被重新创建，
               然后再被删除标记覆盖 —— 结果对，但中间会让云端短暂出现已删内容。 */
            pushDeletes(ctx, db)

            val ids = withContext(Dispatchers.IO) { db.sessionDao().dirtyIds() }
            if (ids.isEmpty()) {
                _state.value = _state.value.copy(
                    running = false, dirty = 0, pendingDel = countPendingDel(db),
                )
                setMessage("没有需要同步的内容")
                AppPrefs.setSyncLastAt(ctx, System.currentTimeMillis())
                _state.value = _state.value.copy(lastAt = AppPrefs.syncLastAt(ctx))
                return@withLock null
            }

            var pushed = 0
            var skippedEmpty = 0
            var lastReply: SyncClient.Reply? = null
            var err = ""

            for (batch in withContext(Dispatchers.IO) { chunkByBytes(db, ids) }) {
                val payload = withContext(Dispatchers.IO) { buildPayload(ctx, db, batch) }
                if (payload.sessions == 0) {
                    /* 整批都没活句子：不建云端文档，但也不能留它们一直「待同步」，
                       否则这个数字永远清不掉，用户会以为同步坏了。置为 clean 是安全的：
                       等转写完成后 TranscribeWorker 会把会话重新置脏。

                       「整轮句子被删光」也会走到这里，此时删除标记必须一起清，
                       否则下一帧 dirtyIds 又数回来了 —— 表现是同步成功但数字不动。 */
                    skippedEmpty += batch.size
                    withContext(Dispatchers.IO) {
                        batch.forEach {
                            db.sessionDao().setSyncState(it, SessionEntity.SYNC_CLEAN)
                            db.transcriptDao().clearDeletedSyncState(it)
                        }
                    }
                    continue
                }

                val reply = SyncClient.post(url, token, payload.json)
                lastReply = reply
                if (!reply.ok) { err = reply.message; break }

                withContext(Dispatchers.IO) { applyResults(db, payload, reply) }
                pushed += reply.done

                /* 每批推完立刻刷新计数：一次全量同步可能几十批、跑几十秒，
                   界面上的数字必须跟着动，否则用户会以为卡死 */
                _state.value = _state.value.copy(dirty = countDirty(db))
            }

            val now = System.currentTimeMillis()
            if (err.isNotEmpty()) {
                AppPrefs.setSyncLastError(ctx, err)
                _state.value = _state.value.copy(running = false, lastError = err)
            } else {
                AppPrefs.setSyncLastAt(ctx, now)
                val extra = if (skippedEmpty > 0) "（$skippedEmpty 轮未转写，跳过）" else ""
                setMessage("已同步 $pushed 轮$extra")
                _state.value = _state.value.copy(
                    running = false, lastAt = now,
                    dirty = countDirty(db),
                    pendingDel = countPendingDel(db),
                )
            }
            lastReply
        } catch (e: Exception) {
            val msg = "同步异常：${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            AppPrefs.setSyncLastError(ctx, msg)
            _state.value = _state.value.copy(running = false, lastError = msg)
            null
        }
    }

    /**
     * 删除若干轮会话：**本地软删 + 云端打删除标记**。
     *
     * 两边都只做「标记」，谁都不物理删：
     * - 本地标 `deletedAt`，回收站里能恢复
     * - 云端标 `deletedAt`，读接口默认过滤，AI 读不到；带 `?deleted=1` 仍可审计
     *
     * 云端失败不回滚本地 —— 用户点了删除，界面就必须立刻删掉。
     * 没打上标记的会留在 `pendingDeletes`，下次同步自动补发（所以离线删除也安全）。
     */
    suspend fun deleteSessions(
        ctx: Context,
        db: VoiceLogDatabase,
        ids: List<Long>,
    ): SyncClient.Reply? = mutex.withLock {
        if (ids.isEmpty()) return@withLock null
        val now = System.currentTimeMillis()

        withContext(Dispatchers.IO) {
            db.sessionDao().softDeleteMany(ids, now)
            /* 句子也要一起标删。只删会话行的话，按句子查询的地方
               （导出、检索）仍会把它们翻出来 —— 而且看起来像「删除没生效」。 */
            ids.forEach { db.transcriptDao().softDeleteBySession(it, now) }
            /* 段落也标删，但**文件先留着**：回收站 7 天内恢复要能接着回听。
               真正的磁盘清理在 RecycleBin.expire()，到点连文件一起删。
               不标删的话，素材页和「已缓存音频」还会算着这轮的空间和段数，
               用户删完发现占用没降，会以为删除失败了。 */
            ids.forEach { db.segmentDao().softDeleteBySession(it, now) }
        }
        _state.value = _state.value.copy(
            dirty = countDirty(db), pendingDel = countPendingDel(db),
        )

        val reply = pushDeletes(ctx, db)
        _state.value = _state.value.copy(pendingDel = countPendingDel(db))
        reply
    }

    /**
     * 恢复若干轮会话（回收站用）。
     *
     * 顺序：**先云端清标记，再本地恢复**。反过来的话，本地已经恢复、
     * 云端标记还在，此时用户去问 AI「我今天的记录呢」，AI 读到的是空的。
     */
    suspend fun restoreSessions(
        ctx: Context,
        db: VoiceLogDatabase,
        ids: List<Long>,
    ): SyncClient.Reply? = mutex.withLock {
        if (ids.isEmpty()) return@withLock null

        val sids = withContext(Dispatchers.IO) {
            db.sessionDao().byIds(ids).map { it.cloudSid }.filter { it.isNotBlank() }
        }

        var reply: SyncClient.Reply? = null
        val url = AppPrefs.cloudUrl(ctx)
        val token = AppPrefs.cloudToken(ctx)
        if (sids.isNotEmpty() && url.isNotBlank() && token.isNotBlank()) {
            reply = SyncClient.softDelete(url, token, sids, restore = true)
            if (!reply.ok) {
                /* 云端没清掉就不动本地：否则用户以为恢复了，实际 AI 那边还是空的。
                   宁可让他看到「恢复失败，再试一次」。 */
                AppPrefs.setSyncLastError(ctx, reply.message)
                _state.value = _state.value.copy(lastError = reply.message)
                return@withLock reply
            }
        }

        withContext(Dispatchers.IO) {
            db.sessionDao().restoreMany(ids)
            ids.forEach { db.transcriptDao().restoreBySession(it) }
        }
        _state.value = _state.value.copy(
            dirty = countDirty(db), pendingDel = countPendingDel(db),
        )
        setMessage("已恢复 ${ids.size} 轮")
        reply
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 把「本地已删、云端还没标记」的会话补发到 `/vl/delete`。
     *
     * 三种结局都算处理完，必须标 clean，否则数字永远清不掉：
     * - `deleted` 云端刚打上标记
     * - `missing` 云端本来就没有（这条从没同步过），无需标记
     * - 本地没有 cloudSid 同理，直接标 clean
     * 只有 `error` 保留下来等下次重试。
     */
    private suspend fun pushDeletes(ctx: Context, db: VoiceLogDatabase): SyncClient.Reply? {
        val rows = withContext(Dispatchers.IO) { db.sessionDao().pendingDeletes() }
        if (rows.isEmpty()) return null

        val url = AppPrefs.cloudUrl(ctx)
        val token = AppPrefs.cloudToken(ctx)
        if (url.isBlank() || token.isBlank()) return null

        var last: SyncClient.Reply? = null
        for (batch in rows.chunked(100)) {
            val neverUploaded = batch.filter { it.cloudSid.isBlank() }
            if (neverUploaded.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    db.sessionDao().markDeletesSynced(neverUploaded.map { it.id })
                }
            }

            val withSid = batch.filter { it.cloudSid.isNotBlank() }
            if (withSid.isEmpty()) continue

            val reply = SyncClient.softDelete(url, token, withSid.map { it.cloudSid })
            last = reply
            if (!reply.ok) break

            val done = withSid.filter { s ->
                reply.rows.any {
                    it.sid == s.cloudSid && (it.result == "deleted" || it.result == "missing")
                }
            }
            if (done.isNotEmpty()) {
                withContext(Dispatchers.IO) { db.sessionDao().markDeletesSynced(done.map { it.id }) }
            }
        }
        return last
    }

    private fun setMessage(m: String) {
        _state.value = _state.value.copy(lastMessage = m)
    }

    private class Payload(
        val json: JSONObject,
        /** 云端 sid → 本地 session.id，回执回来时用它定位 */
        val idBySid: Map<String, Long>,
        /** 本地 session.id → 该会话所有句子的 id，成功后要把它们的脏标记清掉 */
        val transcriptIds: Map<Long, List<Long>>,
        val sessions: Int,
    )

    private fun dateOf(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        f.timeZone = TZ
        return f.format(Date(ms))
    }

    /**
     * 按「预估序列化字节」把会话切成多批发送。
     *
     * 为什么不能只按会话数切：一轮 46 分钟的对话就有 ~1000 句、序列化后约 90KB，
     * 7 轮一起发就是 140KB+，网关直接 413 —— 而且这个 413 是网关返回的，
     * 云函数压根没被执行，服务端日志里什么都看不到，极难排查。
     *
     * 估算口径（宁可估大）：
     * · 每句固定 62B + 正文按 UTF-8 上限 3B/字符
     * · 每轮加 200B 会话头
     * 估大的代价是多一次 HTTP；估小的代价是整批失败。
     */
    private suspend fun chunkByBytes(db: VoiceLogDatabase, ids: List<Long>): List<List<Long>> {
        val txBySession = db.transcriptDao().listBySessions(ids).groupBy { it.sessionId }
        val out = mutableListOf<List<Long>>()
        var cur = mutableListOf<Long>()
        var bytes = 32L
        for (id in ids) {
            val list = txBySession[id].orEmpty()
            val est = SESSION_OVERHEAD + list.sumOf { ITEM_OVERHEAD + it.text.length.toLong() * 3 }
            /* 单轮自己就超限时只能让它独占一批 —— 再大就只能靠分片（暂未实现），
               此时发送会在 SyncClient 里拿到网关 413 并显示明确提示。 */
            if (cur.isNotEmpty() && (cur.size >= BATCH || bytes + est > MAX_BODY_BYTES)) {
                out.add(cur)
                cur = mutableListOf()
                bytes = 32L
            }
            cur.add(id)
            bytes += est
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private suspend fun buildPayload(ctx: Context, db: VoiceLogDatabase, batch: List<Long>): Payload {
        val sessions = db.sessionDao().byIds(batch)
        val txBySession = db.transcriptDao().listBySessions(batch).groupBy { it.sessionId }

        val arr = JSONArray()
        val idBySid = HashMap<String, Long>()
        val tIds = HashMap<Long, List<Long>>()

        for (s in sessions) {
            val list = txBySession[s.id] ?: emptyList()
            /* 没有文字 = 还没转写。不同步，也不建云端文档。 */
            if (list.isEmpty()) continue

            var sid = s.cloudSid
            if (sid.isBlank()) {
                sid = UUID.randomUUID().toString().replace("-", "")
                db.sessionDao().setCloudSid(s.id, sid)
            }

            /* endWall 可能是 0（进程被杀未收尾）。服务端校验 endMs >= startMs，
               所以用最后一句的时间兜底，而不是让它整条被拒。 */
            val end = when {
                s.endWall > s.startWall -> s.endWall
                list.isNotEmpty() -> maxOf(s.startWall, list.last().wallClock + 1000L)
                else -> s.startWall + 1000L
            }

            val items = JSONArray()
            for (t in list) {
                items.put(
                    JSONObject().apply {
                        put("c", t.wallClock)
                        put("t", t.text)
                        put("s", t.speakerName ?: "")
                        put("a", t.speakerAuto)
                        /* Float → Double：org.json 对 Float 会输出科学计数法（如 8.7E-1），
                           服务端 Number() 能收，但存进数据库后人工排查会看不懂。
                           ★ 取两位小数：直接 toDouble() 会输出 0.800000011920929（24B），
                           取整后是 0.8（9B）。977 句就是 15KB —— 在 100KB 网关限制下这不是微优化。 */
                        put("sc", Math.round(t.speakerScore * 100) / 100.0)
                        put("e", if (t.edited) 1 else 0)
                    },
                )
            }

            arr.put(
                JSONObject().apply {
                    put("sid", sid)
                    put("date", dateOf(s.startWall))
                    put("startMs", s.startWall)
                    put("endMs", end)
                    put("scene", s.scene ?: "")
                    put("sceneNote", s.sceneNote ?: "")
                    put("items", items)
                    put("updatedAt", System.currentTimeMillis())
                },
            )
            idBySid[sid] = s.id
            tIds[s.id] = list.map { it.id }
        }

        val body = JSONObject().apply {
            put("sessions", arr)
            put("device", AppPrefs.deviceId(ctx))
        }
        return Payload(body, idBySid, tIds, arr.length())
    }

    private suspend fun applyResults(db: VoiceLogDatabase, payload: Payload, reply: SyncClient.Reply) {
        for (r in reply.rows) {
            val localId = payload.idBySid[r.sid] ?: continue
            when (r.result) {
                /* skipped = 服务端已有更新的版本（本地时钟回拨才会出现）。
                   标 clean 而不是留着重试：重试也是同一个旧时间戳，永远推不上去。 */
                "created", "updated", "skipped" -> {
                    db.sessionDao().markSynced(localId, r.sid, r.rev)
                    val ids = payload.transcriptIds[localId]
                    if (!ids.isNullOrEmpty()) {
                        db.transcriptDao().setSyncState(ids, SessionEntity.SYNC_CLEAN)
                    }
                    /* 云端 items 是整份替换，所以这一轮里「已删除的句子」此刻
                       在云端也已经不存在了，本地那条 syncState=2 可以清了。
                       不清的话 dirtyIds 会永远把这一轮当成待同步 —— 见
                       TranscriptDao.clearDeletedSyncState 的注释。 */
                    db.transcriptDao().clearDeletedSyncState(localId)
                }
                /* invalid / error：保留脏标记，下次重试。原因由 Reply 汇总给界面 */
                else -> Unit
            }
        }
    }

}
