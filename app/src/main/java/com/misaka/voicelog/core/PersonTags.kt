package com.misaka.voicelog.core

import android.content.Context

/**
 * 常用说话人标签库。
 *
 * 和声纹档案是两件事，刻意分开：
 *   · 声纹档案 = 机器能自动认出来的人，数量受注册成本限制（每人要念 4 句）
 *   · 标签库 = 用户手动打标时能一键选到的名字，可以是同事、领导、临时出现的陌生人
 *
 * 为什么不做成"注册声纹才可打标"：现实中大部分对话对象一辈子不会被注册，
 * 但用户仍然希望他们把名字标对。标签库把"打标"和"能不能自动认"解耦。
 *
 * 存储用 `\n` 分隔的单个字符串而不是 getStringSet：
 * StringSet 不保证顺序，而这里顺序就是用户心里的优先级（常用的人排前面），
 * 名字里也不可能出现换行，分隔符是安全的。
 */
object PersonTags {

    private const val PREFS = "person_tags"
    private const val KEY = "tags"

    private const val MAX = 40

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<String> =
        prefs(ctx).getString(KEY, "")?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()

    private fun write(ctx: Context, tags: List<String>) {
        prefs(ctx).edit().putString(KEY, tags.joinToString("\n")).apply()
    }

    /** 添加到末尾。已存在的不重复添加，返回最新列表 */
    fun add(ctx: Context, name: String): List<String> {
        val clean = name.trim().replace("\n", " ")
        if (clean.isEmpty()) return list(ctx)
        val cur = list(ctx).toMutableList()
        if (cur.none { it == clean }) {
            cur.add(clean)
        }
        val cut = cur.take(MAX)
        write(ctx, cut)
        return cut
    }

    fun remove(ctx: Context, name: String): List<String> {
        val next = list(ctx).filter { it != name }
        write(ctx, next)
        return next
    }

    /** 批量导入，用于"把历史记录里出现过的人一次性收进标签库" */
    fun addAll(ctx: Context, names: List<String>): List<String> {
        val cur = list(ctx).toMutableList()
        names.forEach { raw ->
            val clean = raw.trim().replace("\n", " ")
            if (clean.isNotEmpty() && cur.none { it == clean }) cur.add(clean)
        }
        val cut = cur.take(MAX)
        write(ctx, cut)
        return cut
    }
}
