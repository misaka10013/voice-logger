package com.misaka.voicelog.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * AI 校对的传输层。
 *
 * ## 为什么不复用 [SyncClient]
 * 三处不兼容，硬塞进去会把同步那条链路弄脏：
 * 1. **超时不同**。同步 30s 够（几百 KB JSON），校对要 70s ——
 *    服务端自己设了 55s 让模型跑完，模型实测延迟 8~32s 且抖动大。
 *    用 30s 会稳定误报超时，而超时后模型其实成功了，钱照扣。
 * 2. **响应形状不同**。校对返回 `suggestions` 没有 `results`，
 *    [SyncClient.parse] 会判成「打到了博客前端而不是云函数」。
 * 3. **语义不同**。校对是花钱操作、单批有 40 句上限，需要自己切批和限流。
 *
 * ## 本类只做传输，不碰数据库
 * 「建议存哪、要不要应用」是 [ProofreadManager] 和用户的事。
 * 传输层一旦开始写库，就再也没法做「只预览不落地」。
 */
object ProofreadClient {

    /** 连接超时：只需要建连，给 30s 和同步一致 */
    private const val CONNECT_TIMEOUT_MS = 30_000

    /**
     * 读超时：**必须比服务端自己的超时大**。
     * 服务端 `streamText` 传了 timeout=55000，模型慢到 32s 也还在跑；
     * 我们这边 70s 收，保证「服务端还在努力」时不会提前掐断。
     */
    private const val READ_TIMEOUT_MS = 70_000

    /** 单条建议。字段与服务端 `suggestions[]` 一一对应 */
    data class Suggestion(
        /** 1-based，**相对于本次提交的这一批**，不是句子 id */
        val index: Int,
        val from: String,
        val to: String,
        /** 改动类型，10 字内 */
        val why: String,
        /** 编辑距离 / 原句长度，0~1 */
        val ratio: Float,
        /** low / mid / high。high 默认不批量应用（很可能是脑补） */
        val risk: String,
    )

    data class Reply(
        val ok: Boolean,
        val code: Int,
        /** 给人看的结论，成功失败都有值 */
        val message: String,
        val suggestions: List<Suggestion> = emptyList(),
        /** 本批一共送了多少句，用于校验 index 合法性 */
        val total: Int = 0,
        /** 实际使用的模型，如 cloudbase/hy4 */
        val model: String = "",
        /** 失败时给「详情」用 */
        val raw: String = "",
    )

    /**
     * 校对一批句子。
     *
     * [items] 是 `说话人标签 to 文本`，顺序必须与调用方的句子顺序一致 ——
     * 服务端返回的 `i` 就是这里的下标 +1，错一个位置就会把 A 的修改写到 B 上。
     */
    suspend fun proofread(
        baseUrl: String,
        token: String,
        items: List<Pair<String, String>>,
        scene: String = "",
        sceneNote: String = "",
        sid: String = "",
    ): Reply = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Reply(true, 0, "没有需要校对的句子")

        val base = baseUrl.trim()
        if (base.isEmpty()) return@withContext Reply(false, 0, "还没填云端地址")
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return@withContext Reply(false, 0, "地址要以 https:// 开头")
        }
        if (token.trim().length < 16) {
            return@withContext Reply(false, 0, "写 token 太短（至少 16 位），确认没漏抄")
        }

        val body = JSONObject().apply {
            put("items", JSONArray().apply {
                items.forEach { (sp, text) ->
                    put(JSONObject().apply {
                        put("sp", sp)
                        put("x", text)
                    })
                }
            })
            if (scene.isNotBlank()) put("scene", scene)
            if (sceneNote.isNotBlank()) put("sceneNote", sceneNote)
            if (sid.isNotBlank()) put("sid", sid)
        }

        send(base, token, body)
    }

    // ------------------------------------------------------- 异步任务（2026-11-09）

    data class TaskStart(
        val ok: Boolean,
        val message: String,
        val taskId: String = "",
        val total: Int = 0,
        val model: String = "",
    )

    data class TaskStatus(
        val ok: Boolean,
        val message: String,
        /** running / done / failed / canceled */
        val status: String = "",
        val total: Int = 0,
        val suggested: Int = 0,
        /** 服务端实际耗时（毫秒），只在终态有值 */
        val elapsed: Long = 0L,
        val error: String = "",
        val model: String = "",
        val suggestions: List<Suggestion> = emptyList(),
    )

    /**
     * 发起一次**整篇**校对任务。服务端立刻返回 taskId（实测 0.8s），
     * 模型在云函数后台跑（SCF 的「同步返回与异步处理分离」特性）。
     *
     * 为什么不再由客户端切批：切批会让模型看不到全文 ——
     * 同一人名前后写法不一致、上一句说过的词下一句又写错，它都发现不了。
     */
    suspend fun startTask(
        baseUrl: String,
        token: String,
        items: List<Pair<String, String>>,
        scene: String = "",
        sceneNote: String = "",
        sid: String = "",
    ): TaskStart = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext TaskStart(true, "没有需要校对的句子")

        val body = JSONObject().apply {
            put("items", JSONArray().apply {
                items.forEach { (sp, text) ->
                    put(JSONObject().apply {
                        put("sp", sp)
                        put("x", text)
                    })
                }
            })
            if (scene.isNotBlank()) put("scene", scene)
            if (sceneNote.isNotBlank()) put("sceneNote", sceneNote)
            if (sid.isNotBlank()) put("sid", sid)
        }

        val r = postRaw(baseUrl, token, "/vl/proofread", body)
        if (r.error != null) return@withContext TaskStart(false, r.error)
        val obj = runCatching { JSONObject(r.text) }.getOrNull()
            ?: return@withContext TaskStart(false, "响应不是合法 JSON，可能打到了博客前端")
        if (!obj.optBoolean("ok", false)) {
            return@withContext TaskStart(false, obj.optString("msg", "发起校对失败"))
        }
        val id = obj.optString("taskId", "")
        if (id.isBlank()) return@withContext TaskStart(false, "服务端没返回 taskId")
        TaskStart(
            ok = true,
            message = "已提交",
            taskId = id,
            total = obj.optInt("total", 0),
            model = obj.optString("model", ""),
        )
    }

    /**
     * 查任务。`status=done` 时才带 suggestions。
     *
     * ⚠️ `suggestions[].i` 是**相对整篇非空句**的序号，和 [startTask] 传进去的
     * items 顺序一一对应 —— 两边任何一边多滤或少滤一句，都会把修改写到隔壁句子上。
     */
    suspend fun pollTask(
        baseUrl: String,
        token: String,
        taskId: String,
    ): TaskStatus = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("action", "status")
            put("taskId", taskId)
        }
        val r = postRaw(baseUrl, token, "/vl/proofread", body)
        if (r.error != null) return@withContext TaskStatus(false, r.error)
        val obj = runCatching { JSONObject(r.text) }.getOrNull()
            ?: return@withContext TaskStatus(false, "响应不是合法 JSON")
        if (!obj.optBoolean("ok", false)) {
            return@withContext TaskStatus(false, obj.optString("msg", "查询任务失败"))
        }

        val total = obj.optInt("total", 0)
        val arr = obj.optJSONArray("suggestions") ?: JSONArray()
        val out = ArrayList<Suggestion>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optInt("i", 0)
            if (idx < 1 || (total > 0 && idx > total)) continue
            out += Suggestion(
                index = idx,
                from = o.optString("from", ""),
                to = o.optString("to", ""),
                why = o.optString("why", ""),
                ratio = o.optDouble("ratio", 0.0).toFloat(),
                risk = o.optString("risk", "mid"),
            )
        }
        TaskStatus(
            ok = true,
            message = "",
            status = obj.optString("status", ""),
            total = total,
            suggested = obj.optInt("suggested", 0),
            elapsed = obj.optLong("elapsed", 0L),
            error = obj.optString("error", ""),
            model = obj.optString("model", ""),
            suggestions = out,
        )
    }

    // ---------------------------------------------------------------- 配置

    /** 服务端存的校对配置（`vl_meta/meta`） */
    data class Config(
        /** cloudbase = 云开发内置；openai = 任意 OpenAI 兼容端点 */
        val provider: String = "cloudbase",
        val model: String = "",
        val group: String = "",
        val apiBase: String = "",
        /** 只回掩码（••••1234），明文永不回传 */
        val apiKeyMasked: String = "",
        val promptPref: String = "",
        /**
         * 用户自定义的**系统提示词**。空 = 用服务端默认（[defaultSys]）。
         *
         * 留空而不是存一份默认文本，是因为默认文案只应该存在于服务端一处 ——
         * 客户端存副本必然过期，改一次文案要发两个版本。
         */
        val sysPrompt: String = "",
        /** 服务端当前默认的系统提示词，供设置页展示和「恢复默认」 */
        val defaultSys: String = "",
        val names: List<String> = emptyList(),
        /** **实体纠错词表** —— 这轮唯一被数据证明有效的降错手段（同音/专有词） */
        val words: List<String> = emptyList(),
    )

    data class ConfigReply(
        val ok: Boolean,
        val message: String,
        val config: Config = Config(),
        val raw: String = "",
    )

    /** 读配置。失败不抛异常 —— 设置页拿不到配置时应该能继续用，只是显示不出词表 */
    suspend fun getConfig(baseUrl: String, token: String): ConfigReply =
        withContext(Dispatchers.IO) {
            val r = postRaw(baseUrl, token, "/vl/config", JSONObject().apply { put("op", "get") })
            if (r.error != null) return@withContext ConfigReply(false, r.error, raw = r.text)
            val obj = runCatching { JSONObject(r.text) }.getOrNull()
                ?: return@withContext ConfigReply(false, "响应不是合法 JSON", raw = r.text.take(200))
            if (!obj.optBoolean("ok", false)) {
                return@withContext ConfigReply(false, obj.optString("msg", "读取配置失败"), raw = r.text)
            }
            val c = obj.optJSONObject("config")
            val d = obj.optJSONObject("defaults")
            ConfigReply(
                ok = true,
                message = "已读取",
                config = Config(
                    provider = c?.optString("provider", "cloudbase") ?: "cloudbase",
                    model = c?.optString("model", "") ?: "",
                    group = c?.optString("group", "") ?: "",
                    apiBase = c?.optString("apiBase", "") ?: "",
                    apiKeyMasked = c?.optString("apiKeyMasked", "") ?: "",
                    promptPref = c?.optString("promptPref", "") ?: "",
                    sysPrompt = c?.optString("sysPrompt", "") ?: "",
                    defaultSys = d?.optString("sys", "") ?: "",
                    names = jsonStrings(c?.optJSONArray("names")),
                    words = jsonStrings(c?.optJSONArray("words")),
                ),
            )
        }

    /**
     * 写配置。只传要改的字段 —— 服务端按字段做 patch，
     * 传全部字段会把别的端刚改过的值覆盖回去。
     */
    suspend fun saveConfig(
        baseUrl: String,
        token: String,
        names: List<String>? = null,
        words: List<String>? = null,
        promptPref: String? = null,
        /** 传空串 = 恢复默认提示词（不是存一个空指令） */
        sysPrompt: String? = null,
        model: String? = null,
        provider: String? = null,
        apiBase: String? = null,
        apiKey: String? = null,
    ): ConfigReply = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            if (names != null) put("names", JSONArray(names))
            if (words != null) put("words", JSONArray(words))
            if (promptPref != null) put("promptPref", promptPref)
            if (sysPrompt != null) put("sysPrompt", sysPrompt)
            if (model != null && model.isNotBlank()) put("model", model)
            if (provider != null && provider.isNotBlank()) put("provider", provider)
            if (apiBase != null) put("apiBase", apiBase)
            /* 传空串 = 清掉 key；传 null = 不改 */
            if (apiKey != null) put("apiKey", apiKey)
        }
        if (body.length() == 0) return@withContext ConfigReply(false, "没有要保存的内容")
        val r = postRaw(baseUrl, token, "/vl/config", body)
        if (r.error != null) return@withContext ConfigReply(false, r.error, raw = r.text)
        val obj = runCatching { JSONObject(r.text) }.getOrNull()
            ?: return@withContext ConfigReply(false, "响应不是合法 JSON", raw = r.text.take(200))
        if (!obj.optBoolean("ok", false)) {
            return@withContext ConfigReply(false, obj.optString("msg", "保存失败"), raw = r.text)
        }
        val saved = jsonStrings(obj.optJSONArray("saved"))
        ConfigReply(true, if (saved.isEmpty()) "已保存" else "已保存：${saved.joinToString("、")}")
    }

    private fun jsonStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }

    /** 传输结果。error 非空表示这一层就失败了，调用方不用再解析 body */
    private data class Raw(val code: Int, val text: String, val error: String?)

    /**
     * 配置类请求的公共骨架（读配置 / 写配置都用它）。
     *
     * 抽出来是因为 401/402/网络异常这几条「翻译成人话」的逻辑必须只有一份 ——
     * 散成多份时一定会有一份忘了写 402，用户看到的就是无意义的「HTTP 402」。
     */
    private fun postRaw(
        baseUrl: String,
        token: String,
        path: String,
        body: JSONObject,
    ): Raw {
        val base = baseUrl.trim()
        if (base.isEmpty()) return Raw(0, "", "还没填云端地址")
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return Raw(0, "", "地址要以 https:// 开头")
        }
        if (token.trim().length < 16) {
            return Raw(0, "", "写 token 太短（至少 16 位），确认没漏抄")
        }

        val urlStr = base.trimEnd('/') + path +
            "?token=" + URLEncoder.encode(token.trim(), "UTF-8")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CONNECT_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("msg", "") }.getOrDefault("")
                return Raw(
                    code, text,
                    when {
                        msg.isNotBlank() -> msg
                        code == 401 -> "写 token 不对（服务端拒绝了）"
                        code == 402 -> "云端 AI 额度不足或套餐不支持"
                        else -> "HTTP $code"
                    },
                )
            }
            Raw(code, text, null)
        } catch (e: Exception) {
            Raw(0, "", "网络异常：${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        } finally {
            conn?.disconnect()
        }
    }

    private fun send(base: String, token: String, body: JSONObject): Reply {
        val urlStr = base.trimEnd('/') + "/vl/proofread" +
            "?token=" + URLEncoder.encode(token.trim(), "UTF-8")

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

            when {
                code == 401 -> return Reply(false, code,
                    "写 token 不对（服务端拒绝了）。校对走的是写通道，用的是同一个 token", raw = text)
                code == 413 -> return Reply(false, code,
                    "这一批句子太长被网关拦了（>100KB）。会自动改小批次重试", raw = text)
                code !in 200..299 -> {
                    /* 服务端的 fail() 把原因放在 msg 字段，不是 error。
                       402 是腾讯云 AI 额度/套餐问题，单独给提示 —— 否则用户只会看到「HTTP 402」。 */
                    val msg = runCatching { JSONObject(text).optString("msg", "") }.getOrDefault("")
                    val hint = when {
                        msg.isNotBlank() -> msg
                        code == 402 -> "云端 AI 额度不足或套餐不支持（腾讯云 AI 返回 402）"
                        code == 502 -> "模型调用失败或返回格式异常，可稍后重试"
                        else -> "HTTP $code"
                    }
                    return Reply(false, code, "校对失败：$hint", raw = text)
                }
            }

            val obj = runCatching { JSONObject(text) }.getOrElse {
                return Reply(false, code, "响应不是合法 JSON，可能打到了博客前端而不是云函数",
                    raw = text.take(300))
            }
            if (!obj.optBoolean("ok", false)) {
                val msg = obj.optString("msg", "服务端返回 ok=false 但没给原因")
                return Reply(false, code, "校对失败：$msg", raw = text.take(400))
            }

            val total = obj.optInt("total", 0)
            val arr = obj.optJSONArray("suggestions") ?: JSONArray()
            val out = ArrayList<Suggestion>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val idx = o.optInt("i", 0)
                // 服务端已经过滤过越界，这里再挡一次：index 错了会把改动写到别的句子上
                if (idx < 1 || (total > 0 && idx > total)) continue
                out += Suggestion(
                    index = idx,
                    from = o.optString("from", ""),
                    to = o.optString("to", ""),
                    why = o.optString("why", ""),
                    ratio = o.optDouble("ratio", 0.0).toFloat(),
                    risk = o.optString("risk", "mid"),
                )
            }
            Reply(
                ok = true,
                code = code,
                message = if (out.isEmpty()) "本批无需修改" else "本批 ${out.size} 条建议",
                suggestions = out,
                total = total,
                model = obj.optString("model", ""),
                raw = text,
            )
        } catch (e: SocketTimeoutException) {
            Reply(false, 0,
                "校对超时（${READ_TIMEOUT_MS / 1000}s）。模型可能正忙，稍后对这轮重试即可，" +
                    "已完成的批次不会丢", raw = e.toString())
        } catch (e: UnknownHostException) {
            Reply(false, 0, "地址解析不了：域名可能写错或当前没联网", raw = e.toString())
        } catch (e: SSLException) {
            Reply(false, 0, "HTTPS 证书校验失败，确认地址是 https:// 且域名正确", raw = e.toString())
        } catch (e: Exception) {
            Reply(false, 0, "网络异常：${e.javaClass.simpleName} ${e.message ?: ""}".trim(), raw = e.toString())
        } finally {
            conn?.disconnect()
        }
    }
}
