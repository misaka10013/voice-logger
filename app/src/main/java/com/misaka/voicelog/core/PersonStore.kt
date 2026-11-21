package com.misaka.voicelog.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「人」这一层：名字、性别、是否本人。
 *
 * ## 为什么必须单独抽出来
 * 声纹档案是按「人 × 麦克风」存的 —— 说话人A用内置麦一份、用蓝牙麦一份，
 * 是**两份档案**。如果名字写在档案上，同一个人就有两份名字副本；
 * 改一次名要改 N 处，漏一处就多出一个说话人 chip。
 *
 * 2026-11-21 的真实故障：设置页 4 份档案全叫「说话人A」或「说话人B」，
 * 但 F0 兜底用的是另一套名字（默认「我 / 对方」），于是同一个人被劈成
 * 「说话人A 5 句」+「我 30 句」两个 chip。名字有三份副本，这是必然结果。
 *
 * ## 现在的模型
 * ```
 * 说话人A（我）· 男          ← Person：名字只存这里一份
 *   ├─ 内置麦克风   4 句   ← Profile：只记 personId + micKey
 *   └─ 蓝牙耳机     4 句
 * 说话人B · 女
 *   ├─ 内置麦克风   4 句
 *   └─ 蓝牙耳机     4 句
 * ```
 *
 * ## 性别是「人」的属性，不是档案的属性
 * 基频（F0）判的是声带振动频率，跟用哪支麦无关，所以性别只能挂在人身上。
 * 判出「男声」之后能不能落名字，看的是**这个性别下有几口人**：
 *   · 恰好 1 人 → 落他的名字
 *   · 0 人或 ≥2 人 → 留「未判」，不猜
 * 这条规则不需要任何额外配置，而且是唯一诚实的做法 —— 女声侧实测错 42.6%，
 * 根因就是那里站着两个候选（本人 + 另一个人提调），F0 物理上分不开。
 */
object PersonStore {

    const val GENDER_UNKNOWN = 0
    const val GENDER_MALE = 1
    const val GENDER_FEMALE = 2

    data class Person(
        /** 与「该人第一份档案」的 id 相同，迁移时就地沿用，省一次映射 */
        val id: String,
        val name: String,
        val gender: Int = GENDER_UNKNOWN,
        val isSelf: Boolean = false,
    ) {
        val genderLabel: String
            get() = when (gender) {
                GENDER_MALE -> "男"
                GENDER_FEMALE -> "女"
                else -> "未设"
            }
    }

    private const val PREFS = "persons"
    private const val KEY_LIST = "list"
    private const val KEY_MIGRATED = "migrated_from_profiles_v1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 读写

    fun list(ctx: Context): List<Person> {
        val raw = prefs(ctx).getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Person(
                    id = o.getString("id"),
                    name = o.optString("name", "").ifBlank { "某人" },
                    gender = o.optInt("gender", GENDER_UNKNOWN),
                    isSelf = o.optBoolean("isSelf", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun byId(ctx: Context, id: String?): Person? =
        if (id.isNullOrBlank()) null else list(ctx).firstOrNull { it.id == id }

    fun self(ctx: Context): Person? = list(ctx).firstOrNull { it.isSelf }

    /**
     * 解析一份档案该显示成谁。
     *
     * 优先走档案的 personId 找人名；找不到（v2/v3 老档案、或人被删了）
     * 才退回档案文件头里那个名字快照。**这保证了改名只改一处。**
     */
    fun nameOf(ctx: Context, personId: String?, fallback: String): String =
        byId(ctx, personId)?.name ?: fallback

    private fun save(ctx: Context, people: List<Person>) {
        val arr = JSONArray()
        people.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("gender", p.gender)
                    put("isSelf", p.isSelf)
                },
            )
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun upsert(ctx: Context, person: Person) {
        val cur = list(ctx).toMutableList()
        val i = cur.indexOfFirst { it.id == person.id }
        // 同一时刻只能有一个「我」：设新的就把旧的清掉，否则统计里会出现两个「我」
        if (person.isSelf) {
            for (j in cur.indices) if (cur[j].isSelf) cur[j] = cur[j].copy(isSelf = false)
        }
        if (i >= 0) cur[i] = person else cur.add(person)
        save(ctx, cur)
    }

    fun setGender(ctx: Context, id: String, gender: Int) {
        val p = byId(ctx, id) ?: return
        upsert(ctx, p.copy(gender = gender))
    }

    fun setSelf(ctx: Context, id: String) {
        val p = byId(ctx, id) ?: return
        upsert(ctx, p.copy(isSelf = true))
    }

    fun delete(ctx: Context, id: String) {
        save(ctx, list(ctx).filter { it.id != id })
    }

    fun clearAll(ctx: Context) {
        // 迁移标记也要清：档案全没了之后重新录，应该重新走一遍归组
        prefs(ctx).edit().remove(KEY_LIST).remove(KEY_MIGRATED).apply()
    }

    /**
     * 这个性别下唯一的那个人。用于 F0 兜底。
     *
     * 返回 null 有两种情况，界面上是同一个结果「未判」，但日志要分得开：
     *   · 这个性别还没设过人 → 配置问题，该提示用户去设
     *   · 这个性别有 ≥2 人 → 物理上分不开，只能留空
     */
    fun soleOfGender(ctx: Context, gender: Int): Person? {
        if (gender == GENDER_UNKNOWN) return null
        return list(ctx).filter { it.gender == gender }.singleOrNull()
    }

    // ------------------------------------------------------------------ 迁移

    /**
     * 从 v1~v3 的「档案自带名字」结构迁移成「人 + 档案」。
     *
     * 归组依据只能是名字：那些档案里没有 personId，唯一能把
     * 「说话人A@内置麦」和「说话人A@蓝牙」认成同一个人的线索，就是两处写了同一个字符串。
     * 迁移一次之后就走 id 关联，名字怎么改都不再影响归属。
     *
     * 性别迁移时一律置 UNKNOWN —— 没有任何数据能可靠推断「哪个名字是男声」，
     * 猜一个再写进去，之后用户看到的每个错判都会以为是程序坏了。
     * 让用户在设置页点两下，比猜错强。
     *
     * ⚠️ 已经在「人」层的记录**不能重建**：新版录入页会先写 Person（带性别、本人标记），
     * 如果这里按档案信息重建一遍，用户刚设的性别会被冲回 UNKNOWN。
     * 所以有现成记录时只做一件事：给缺 personId 的档案补归属。
     */
    @Synchronized
    fun ensureMigrated(ctx: Context, profiles: List<VoiceprintStore.Profile>) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        // 一份档案都没有时不要打标：用户可能还没录，迁移要等他录完再跑
        if (profiles.isEmpty()) return

        val cur = list(ctx).toMutableList()

        if (cur.isEmpty()) {
            val groups = LinkedHashMap<String, MutableList<VoiceprintStore.Profile>>()
            profiles.forEach { pr ->
                // 已带 personId 的档案按 id 归组，兼容「迁移跑到一半被杀」的情况
                val key = pr.personId?.takeIf { it.isNotBlank() } ?: "name:${pr.name}"
                groups.getOrPut(key) { mutableListOf() }.add(pr)
            }
            val selfProfileId = VoiceprintStore.selfId(ctx)
            groups.values.forEach { members ->
                val pid = members.first().id
                cur.add(
                    Person(
                        id = pid,
                        name = members.first().name,
                        gender = GENDER_UNKNOWN,
                        isSelf = members.any { it.id == selfProfileId },
                    ),
                )
                members.forEach { m ->
                    if (m.personId != pid) runCatching { VoiceprintStore.bindPerson(ctx, m.id, pid) }
                }
            }
            save(ctx, cur)
        } else {
            profiles.filter { it.personId.isNullOrBlank() }.forEach { m ->
                val hit = cur.firstOrNull { it.name == m.name }
                val pid = hit?.id ?: m.id
                if (hit == null) cur.add(Person(pid, m.name, GENDER_UNKNOWN, false))
                runCatching { VoiceprintStore.bindPerson(ctx, m.id, pid) }
            }
            save(ctx, cur)
        }

        p.edit().putBoolean(KEY_MIGRATED, true).apply()
    }
}
