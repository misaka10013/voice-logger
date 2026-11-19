package com.misaka.voicelog.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * 云端同步的传输层。
 *
 * ## 只做三件事
 * 1. 拼地址、带 token、发 JSON
 * 2. 把 HTTP 响应解析成结构化的 [Reply]
 * 3. **把异常翻译成人话**
 *
 * ## 为什么第 3 件要单独强调
 * 同步是后台行为，出错时用户看不到堆栈。如果只把 `IOException` 抛给界面，
 * 用户唯一能做的就是「再点一次」，而真正的原因（token 错了 / 没联网 / 地址写错）
 * 每次都一样，他永远修不好。所以这里的每条失败路径都必须产出
 * 「用户看一眼就知道该改什么」的句子。
 *
 * ## 为什么用 HttpURLConnection 而不是 OkHttp
 * 只发一个 POST、不需要连接池复用、不需要拦截器。为此多引一个 3MB 的依赖
 * 换不来任何东西 —— 这个 App 的体积已经被 sherpa-onnx 撑得够大了。
 *
 * ## 为什么对外只有 suspend 函数
 * 曾经这里是个普通函数，调用方自己切 IO。结果 `syncNow` 漏了切，同步一按就
 * `NetworkOnMainThreadException`。**把线程切换放在调用方 = 每加一个调用点就多
 * 一次踩坑机会**，所以现在由本文件自己保证「绝不阻塞调用线程」，调用方怎么写都安全。
 */
object SyncClient {

    /** 同步超时。20 轮 × 几百句的 JSON 也就几百 KB，30s 是给慢网留的余量 */
    private const val TIMEOUT_MS = 30_000

    /** 服务端逐条回执的一行 */
    data class Row(
        val sid: String,
        /** created / updated / skipped / invalid / error */
        val result: String,
        val rev: Int,
        val reason: String,
    )

    data class Reply(
        /** 传输层是否成功（HTTP 2xx 且 body 能解析） */
        val ok: Boolean,
        val code: Int,
        val rows: List<Row>,
        /** 给人看的结论，无论成功失败都有值 */
        val message: String,
        /** 原始响应，出问题时给「详情」用 */
        val raw: String,
        /** 成功入库的条数（服务端的 done 字段） */
        val done: Int = 0,
    )

    /**
     * 发一次 POST。
     *
     * [body] 为 null 时发空 JSON —— 服务端把空 body 当成**连通性自检**，
     * 只回能力声明、不回任何数据。这正是「测试连接」要的行为：
     * 验证地址和 token 都通，但不能顺带把用户的数据吐出来。
     */
    suspend fun post(
        baseUrl: String,
        token: String,
        body: JSONObject?,
        path: String = "/vl/sync",
        isDelete: Boolean = false,
    ): Reply = withContext(Dispatchers.IO) { postBlocking(baseUrl, token, body, path, isDelete) }

    /**
     * 通知云端「这几轮已被删除」（服务端只打标记，不真删）。
     *
     * 为什么必须单独发一次而不是塞进 /vl/sync：
     * 已删的会话在本地不会再进待同步队列（那是给「活数据」用的），
     * 而且删除语义是幂等的、可以重放的，单独一条通道最清晰。
     */
    suspend fun softDelete(
        baseUrl: String,
        token: String,
        sids: List<String>,
        restore: Boolean = false,
    ): Reply {
        if (sids.isEmpty()) return Reply(true, 0, emptyList(), "没有需要同步的删除", "")
        val body = JSONObject().apply {
            put("sids", org.json.JSONArray(sids))
            if (restore) put("restore", true)
        }
        return post(baseUrl, token, body, path = "/vl/delete", isDelete = true)
    }

    /** 真正的阻塞实现，只在 IO 线程上跑。外部不要直接调用。 */
    private fun postBlocking(
        baseUrl: String,
        token: String,
        body: JSONObject?,
        path: String,
        isDelete: Boolean,
    ): Reply {
        val base = baseUrl.trim()

        /* ---- 地址与凭证的前置校验。放在发请求之前，是为了给出更准的原因 ---- */
        if (base.isEmpty()) {
            return Reply(false, 0, emptyList(), "还没填云端地址", "")
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return Reply(false, 0, emptyList(), "地址要以 https:// 开头", "")
        }
        if (token.trim().length < 16) {
            // 服务端最小长度也是 16，提前拦掉能省一次往返，也能提示得更准
            return Reply(false, 0, emptyList(), "写 token 太短（至少 16 位），确认没漏抄", "")
        }

        val urlStr = base.trimEnd('/') + path +
            "?token=" + URLEncoder.encode(token.trim(), "UTF-8")
        val insecure = base.startsWith("http://")

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            val payload = (body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(payload) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

            if (code == 401) {
                return Reply(false, code, emptyList(),
                    "写 token 不对（服务端拒绝了）。到服务端核对 vl_writetoken/app 里的值", text)
            }
            if (code == 403) {
                return Reply(false, code, emptyList(), "服务端拒绝访问（403），检查云函数权限配置", text)
            }
            /* 413 = 腾讯云网关的硬限制，不是云函数自己返回的。
               实测阈值 102,400 字节（102,340 通过 / 102,940 被拒），
               也就是说请求体在到达云函数之前就被挡掉了 —— 服务端写得再宽松也没用。 */
            if (code == 413) {
                return Reply(false, code, emptyList(),
                    "这一批数据超过了云端网关上限（100KB），已在发送前被拦截。降到 60KB 以下重试", text)
            }
            if (code !in 200..299) {
                val serverMsg = runCatching {
                    JSONObject(text).optString("error", "")
                }.getOrDefault("")
                val hint = if (serverMsg.isNotBlank()) serverMsg else "HTTP $code"
                return Reply(false, code, emptyList(), "服务端返回失败：$hint", text)
            }

            parse(code, text, insecure, isDelete)
        } catch (e: SocketTimeoutException) {
            Reply(false, 0, emptyList(),
                "连接超时（${TIMEOUT_MS / 1000}s）。检查网络，或稍后重试", e.toString())
        } catch (e: UnknownHostException) {
            Reply(false, 0, emptyList(), "地址解析不了：域名可能写错或当前没联网", e.toString())
        } catch (e: SSLException) {
            Reply(false, 0, emptyList(), "HTTPS 证书校验失败，确认地址是 https:// 且域名正确", e.toString())
        } catch (e: Exception) {
            Reply(false, 0, emptyList(), "网络异常：${e.javaClass.simpleName} ${e.message ?: ""}".trim(), e.toString())
        } finally {
            conn?.disconnect()
        }
    }

    /** 解析 2xx 响应。空 body 自检和真实同步的返回形状不同，这里统一收口 */
    private fun parse(code: Int, text: String, insecure: Boolean, isDelete: Boolean = false): Reply {
        if (text.isBlank()) {
            return Reply(false, code, emptyList(), "服务端返回空响应（部署是否成功？）", text)
        }
        val obj = runCatching { JSONObject(text) }.getOrElse {
            return Reply(false, code, emptyList(),
                "响应不是合法 JSON，可能打到了博客前端而不是云函数", text.take(300))
        }

        val warn = if (insecure) "（注意：地址是 http，token 会明文传输，建议改 https）" else ""

        /* ---- 空 body 自检：只验通 ---- */
        if (!obj.has("results")) {
            val svc = obj.optString("service", "")
            if (svc.isNotEmpty()) {
                return Reply(true, code, emptyList(), "连接正常 · $svc$warn", text)
            }
            return Reply(false, code, emptyList(),
                "服务端有响应但不是同步接口（缺 results 字段）$warn", text.take(300))
        }

        /* ---- 真实同步：逐条回执 ---- */
        val arr = obj.optJSONArray("results") ?: return Reply(
            false, code, emptyList(), "响应里 results 不是数组", text.take(300),
        )
        val rows = ArrayList<Row>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            rows += Row(
                sid = o.optString("sid", ""),
                result = o.optString("result", ""),
                rev = o.optInt("rev", 0),
                reason = o.optString("reason", ""),
            )
        }
        val done = obj.optInt("done", 0)
        val bad = rows.filter { it.result == "invalid" || it.result == "error" }

        /* ---- 删除/恢复：语义和同步不同，不能共用「同步成功 0 轮」这种话 ---- */
        if (isDelete) {
            val okCount = rows.count { it.result == "deleted" || it.result == "restored" }
            val missing = rows.count { it.result == "missing" }
            val restored = rows.any { it.result == "restored" }
            val msg = when {
                bad.isNotEmpty() && okCount == 0 ->
                    "服务端拒绝了：${bad.first().reason.ifBlank { bad.first().result }}"
                missing == rows.size -> "云端没有这几轮（可能还没同步过），本地已处理"
                else -> {
                    val head = if (restored) "已恢复 $okCount 轮" else "已在云端标记删除 $okCount 轮"
                    head + (if (missing > 0) "，$missing 轮云端不存在" else "") + warn
                }
            }
            return Reply(true, code, rows, msg, text, okCount)
        }

        val msg = when {
            bad.isEmpty() -> "同步成功 $done 轮$warn"
            done == 0 -> "全部被服务端拒绝：${bad.first().reason.ifBlank { bad.first().result }}"
            else -> "部分成功 $done 轮，${bad.size} 轮被拒（${bad.first().reason.ifBlank { bad.first().result }}）"
        }
        return Reply(true, code, rows, msg, text, done)
    }
}
