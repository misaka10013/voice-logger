package com.misaka.voicelog.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「A/B 交替」的撤销快照。
 *
 * ## 为什么需要它
 * 一键交替会把整轮的说话人全部重铺。2026-11-09 就出过一次事故：用户在一段
 * **单人独白**（讲二十八宿，336 行）上点了交替，界面显示「说话人A 168 / 说话人B 168」
 * 看上去很像标注完成，实际上是错的，而且原标注已经没了 —— 既没有痕迹也没法撤回。
 *
 * 交替本身是高频操作（双人对话一开始就得铺一遍），所以不能只加固不修伤口：
 * 执行前把「每一行原本是谁」存下来，用户点「撤销」就照原样还原。
 *
 * ## 只保留最近一次
 * 不存历史栈。撤销两次的需求不存在（撤销完大概率是重新标，不是再撤），
 * 而无限栈迟早会变成一个没人清理的膨胀文件。
 */
object SpeakerUndo {

    private const val KEY_PREFIX = "ab_undo_"

    private fun key(sessionId: Long) = "$KEY_PREFIX$sessionId"

    /**
     * 保存交替前的说话人快照。
     *
     * 用 JSON 而不是拼字符串：人名里出现逗号、冒号、竖线都完全可能
     * （「老王，财务」这种），任何自造分隔符都会在某个真实名字上炸掉。
     */
    fun save(ctx: Context, sessionId: Long, rows: List<Pair<Long, String?>>) {
        runCatching {
            val arr = JSONArray()
            for ((id, name) in rows) {
                arr.put(JSONObject().apply {
                    put("id", id)
                    if (name != null) put("name", name)
                })
            }
            AppPrefs.prefs(ctx).edit().putString(key(sessionId), arr.toString()).apply()
        }
    }

    /** 有没有可撤销的快照 */
    fun has(ctx: Context, sessionId: Long): Boolean =
        !AppPrefs.prefs(ctx).getString(key(sessionId), null).isNullOrBlank()

    /** 取快照。空列表 / 解析失败都返回 null，调用方据此提示「没有可撤销的操作」 */
    fun load(ctx: Context, sessionId: Long): List<Pair<Long, String?>>? {
        val raw = AppPrefs.prefs(ctx).getString(key(sessionId), null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(o.getLong("id") to if (o.has("name")) o.getString("name") else null)
                }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun clear(ctx: Context, sessionId: Long) {
        AppPrefs.prefs(ctx).edit().remove(key(sessionId)).apply()
    }
}
