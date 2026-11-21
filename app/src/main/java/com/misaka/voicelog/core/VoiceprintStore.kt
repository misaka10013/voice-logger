package com.misaka.voicelog.core

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * 声纹档案库（多人版）。
 *
 * 为什么从「一个 owner.bin」改成「每人一个文件」：
 *   v1 只能回答"是不是我"，实际使用里最常见的场景是"这句话是我老婆说的"。
 *   只分「我 / 他人」时，用户每轮都要手动把「他人」改成人名，几百句就是几百次点击。
 *   把每个人都做成独立档案后，判定从二分类变成"跟谁最像"，一句话直接落名字。
 *
 * 存储选择"每人一个文件 + 文件名带 id"，而不是一个索引 JSON：
 *   · 增删改一个人不需要读改写整个索引，没有并发写坏的可能
 *   · 一个文件损坏只损失一个人，不会拖垮整库
 *   · 名字直接写在文件头（writeUTF），不必维护 id→名字的外部映射
 *   代价是列出所有人要扫一次目录，一天只做一次的加载量，可以忽略。
 *
 * 判定为什么不直接用 sherpa-onnx 的 SpeakerEmbeddingManager：
 *   它的 verify() 只返回布尔值，拿不到相似度分数。本项目必须能给出分数，否则：
 *     · 阈值无法调（不同人的嗓音分布不同，没有通用最优值）
 *     · 界面无法提示"这句判得很勉强"，用户会失去纠错的抓手
 */
object VoiceprintStore {

    /** 一个说话人的声纹档案。samples 保留全部注册样本，不取平均 —— 见 compare 的说明 */
    data class Profile(
        val id: String,
        val name: String,
        val samples: List<FloatArray>,
        /**
         * 录制这份档案时的录音通路标识（见 [com.misaka.voicelog.audio.MicRoute.keyOf]）。
         *
         * 为什么必须记：说话人 embedding 对信道极其敏感。2026-11-11 实测同一个人
         * 在同一场录音里两两相似度 0.65，但拿手机麦录的档案去比耳机麦录的句子只有 0.25，
         * 而此人跟另一个人之间的相似度是 0.24 —— 换麦之后"自己"和"别人"一样远。
         * 所以判定时只允许跟**同一通路**的档案比，不同通路一律不判。
         *
         * null = v2 版本留下的旧档案，来源未知，任何场景下都不参与判定。
         */
        val micKey: String? = null,

        /**
         * 这份档案属于哪个人（见 [PersonStore]）。
         *
         * 为什么名字不能只写在这里：一个人通常有多份档案（内置麦一份、蓝牙一份），
         * 名字写在档案上就是多份副本，改一次名要改 N 处、漏一处就多出一个 chip。
         * 2026-11-21 的真实故障就是这么来的。现在名字只在 [PersonStore] 存一份，
         * 档案只记 personId；[name] 退化成「建档时的快照」，仅在 personId 找不到人时兜底显示。
         *
         * null = v2/v3 老档案还没迁移，由 [PersonStore.ensureMigrated] 按名字补齐。
         */
        val personId: String? = null,
    ) {
        val sampleCount: Int get() = samples.size
    }

    /**
     * 一次判定的结果：最像谁、有多像。
     *
     * [personId] 非空时，[name] 只是档案里的名字快照，**显示应该用 PersonStore 的名字** ——
     * 用户改名后快照不会跟着变，直接显示快照就会出现「同一句在列表里叫说话人A、
     * 在设置里叫新名字」的错位。
     */
    data class Match(val name: String, val score: Float, val personId: String? = null)

    private const val PREFS = "voiceprint"
    private const val KEY_NAME = "my_label"
    private const val KEY_SELF_ID = "self_profile_id"
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_THRESHOLD_MIGRATED = "threshold_migrated_v3"

    const val DEFAULT_NAME = "我"

    /**
     * 默认阈值 0.40。
     *
     * 取值依据是 2026-11-11 的实测（同一通路上，会话 46 的 6 段真实对话）：
     * 同一个人两两最低 0.430，不同人最高 0.289。0.40 正好卡在两者之间，
     * 两边都留了余量。文献里开源同类实现（VoiceType、cyberkeller）建议区间也是 0.3~0.5。
     *
     * 旧版默认 0.62、上限允许拉到 0.90，那两个值在任何条件下都达不到 ——
     * 等于把「认人」整个关掉，用户却看不出这一点。现在滑块上限压到 0.60。
     */
    private const val DEFAULT_THRESHOLD = 0.40f
    const val MIN_THRESHOLD = 0.25f
    const val MAX_THRESHOLD = 0.65f

    /** v2 文件头魔数，和 v1 的 "VLPR" 区分开，避免把旧文件当新格式读 */
    private const val MAGIC = 0x564C5032 // "VLP2"
    /** v3 起多写一个 micKey；v4 起再多写一个 personId。旧版本文件仍能读 */
    private const val VERSION = 4
    private const val MIN_READ_VERSION = 2
    private const val LEGACY_MAGIC = 0x564C5052 // "VLPR"

    /** 句子短于此长度不做声纹判断：embedding 太不稳定，判出的结果基本是噪声 */
    const val MIN_SAMPLES_SEC = 1.0f

    /** 上限只用来防止误操作刷爆目录，正常家庭/小团队场景 8 个人绰绰有余 */
    const val MAX_PROFILES = 8

    @Volatile
    private var legacyChecked = false

    private fun dir(ctx: Context): File = File(ctx.filesDir, "voiceprint").apply { mkdirs() }

    private fun fileFor(ctx: Context, id: String): File = File(dir(ctx), "p_$id.bin")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 配置

    /**
     * 用户自己的显示名。
     *
     * 优先取「自己」那份档案的名字 —— 注册了本人声纹之后，名字的唯一事实来源是档案，
     * 不应再维护第二份。没有档案时才退回 prefs 里的手填值。
     */
    fun myName(ctx: Context): String =
        PersonStore.self(ctx)?.name
            ?: selfProfile(ctx)?.let { PersonStore.nameOf(ctx, it.personId, it.name) }
            ?: prefs(ctx).getString(KEY_NAME, DEFAULT_NAME)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NAME

    fun setMyName(ctx: Context, name: String) {
        val clean = name.trim().ifBlank { DEFAULT_NAME }
        val self = selfProfile(ctx)
        if (self != null) {
            renameProfile(ctx, self.id, clean)
        } else {
            prefs(ctx).edit().putString(KEY_NAME, clean).apply()
        }
    }

    /** 判定阈值：与同通路档案的相似度高于此值就落名字，否则算「未判」 */
    fun threshold(ctx: Context): Float {
        migrateThresholdOnce(ctx)
        return prefs(ctx).getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
            .coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
    }

    fun setThreshold(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(KEY_THRESHOLD, v.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)).apply()
    }

    /**
     * 一次性把旧版本留下的不合理阈值拉回可用区间。
     *
     * 旧版滑块允许拉到 0.90，而 0.90 在**任何条件下**都达不到 ——
     * 同一支麦上本人之间的实测相似度也只有 0.43~0.65。拉到那一档等于
     * 把「认人」整个关掉，而界面上看不出这一点（用户以为是自己设得严）。
     *
     * 只处理超出新上限（0.65）的值，其余保留用户的选择：
     * 有人可能是故意往高调成 0.55 求稳，那种不该被改。
     */
    private fun migrateThresholdOnce(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_THRESHOLD_MIGRATED, false)) return
        val editor = p.edit().putBoolean(KEY_THRESHOLD_MIGRATED, true)
        if (p.contains(KEY_THRESHOLD)) {
            val old = p.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
            if (old > MAX_THRESHOLD) {
                // 超出可达范围的旧值一律重置成默认 —— 它不是"更严格"，而是"不工作"
                editor.putFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
            }
        }
        editor.apply()
    }

    fun selfId(ctx: Context): String? =
        prefs(ctx).getString(KEY_SELF_ID, null)?.takeIf { it.isNotBlank() }

    fun setSelfId(ctx: Context, id: String?) {
        prefs(ctx).edit().putString(KEY_SELF_ID, id).apply()
    }

    fun selfProfile(ctx: Context): Profile? {
        val id = selfId(ctx) ?: return null
        return profiles(ctx).firstOrNull { it.id == id }
    }

    // ------------------------------------------------------------------ 档案读写

    /**
     * 列出全部档案，按创建时间升序（id 就是创建时刻的毫秒时间戳）。
     *
     * 首次调用会尝试把 v1 的 owner.bin 迁移成一个「我」的档案，
     * 迁移后原文件改名保留而不是删除 —— 万一读格式出错，用户还能手工捞回来。
     */
    fun profiles(ctx: Context): List<Profile> {
        migrateLegacy(ctx)
        val files = dir(ctx).listFiles { f ->
            f.isFile && f.name.startsWith("p_") && f.name.endsWith(".bin")
        } ?: return emptyList()

        return files.mapNotNull { f ->
            val id = f.name.removePrefix("p_").removeSuffix(".bin")
            readProfile(id, f)
        }.sortedBy { it.id.toLongOrNull() ?: 0L }
    }

    fun profileById(ctx: Context, id: String): Profile? =
        profiles(ctx).firstOrNull { it.id == id }

    fun newId(): String = System.currentTimeMillis().toString()

    fun saveProfile(
        ctx: Context,
        id: String,
        name: String,
        samples: List<FloatArray>,
        micKey: String?,
        personId: String? = null,
    ) {
        val valid = samples.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return
        val dim = valid.first().size
        DataOutputStream(fileFor(ctx, id).outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(name.trim().ifBlank { "某人" })
            /* 空串表示"来源未知"，读回来是 null。不能省略这一行：
               v3 的读取顺序要求它一定存在，省了会把后面的样本数读成字符串长度。 */
            out.writeUTF(micKey ?: "")
            /* 同上：v4 起这个字段必须写（空串 = 未归属），否则 v4 读取顺序错位。
               理论上 v3 读 v4 文件会因版本号更小而走 [MIN_READ_VERSION] 之外的分支，
               但不要指望这个 —— 写满字段是最省的防御。 */
            out.writeUTF(personId ?: "")
            out.writeInt(valid.size)
            out.writeInt(dim)
            valid.forEach { emb ->
                // 长度不一致的样本直接补零，避免写出错位的数据
                if (emb.size == dim) emb.forEach { out.writeFloat(it) }
                else repeat(dim) { out.writeFloat(0f) }
            }
        }
    }

    /**
     * 改名：读出样本重写一遍文件。
     *
     * 没有做成"改名 = 换文件名"，因为文件名里的 id 是所有引用（selfId、界面列表 key）
     * 的依据，改名就换 id 会让引用全部失效。样本只有几百 KB，重写代价很低。
     */
    fun renameProfile(ctx: Context, id: String, newName: String): Boolean {
        val p = profileById(ctx, id) ?: return false
        saveProfile(ctx, id, newName, p.samples, p.micKey, p.personId)
        return true
    }

    /**
     * 给旧版（v2）档案补写录音通路。
     *
     * 为什么要单独留一个「事后补绑」而不是只让用户重录：
     * v2 档案是旧版注册代码录的，那段代码用 `VOICE_RECOGNITION` 且**没调 setPreferredDevice**，
     * 走的是系统默认输入 —— 没插耳机时就是内置麦。样本本身有效，
     * 因为少一个字段就整份作废、逼用户重录 4 句，是拿产品缺陷罚用户。
     *
     * ⚠️ 只补标签，不动样本。样本出自哪支麦是既成事实，补绑只是把这个事实记下来，
     * 不改变任何相似度分数。所以绑错了不会让分数变高或变低，
     * 只会让这份档案去参与一场它本来不该参与的比对 —— 假阳性风险仍然由阈值把关。
     */
    fun bindMic(ctx: Context, id: String, micKey: String): Boolean {
        val p = profileById(ctx, id) ?: return false
        if (micKey.isBlank() || micKey == KEY_NOT_A_MIC) return false
        saveProfile(ctx, id, p.name, p.samples, micKey, p.personId)
        return true
    }

    /**
     * 把档案挂到某个人名下（或改挂）。
     *
     * 只重写文件头，样本一字不动 —— 归属是元数据，跟声音特征无关。
     * 换人不会让相似度变高或变低，只是决定了「这份档案代表谁」。
     */
    fun bindPerson(ctx: Context, id: String, personId: String): Boolean {
        val p = profileById(ctx, id) ?: return false
        if (personId.isBlank() || p.personId == personId) return false
        saveProfile(ctx, id, p.name, p.samples, p.micKey, personId)
        return true
    }

    /** 不能被绑定的键。[MicRoute.KEY_UNKNOWN] 的意思就是"不知道"，绑上去等于自欺 */
    private const val KEY_NOT_A_MIC = "unknown"

    fun deleteProfile(ctx: Context, id: String) {
        val p = profileById(ctx, id)
        runCatching { fileFor(ctx, id).delete() }
        if (selfId(ctx) == id) {
            // 删的是「我」：把名字留在 prefs 里，作为"未注册声纹时"的标签名兜底，
            // 否则删完立刻显示成默认的「我」，用户会以为改的名字也一起被删了
            if (p != null) prefs(ctx).edit().putString(KEY_NAME, p.name).apply()
            setSelfId(ctx, null)
        }
    }

    fun clearAll(ctx: Context) {
        runCatching {
            dir(ctx).listFiles()?.forEach { it.delete() }
        }
        setSelfId(ctx, null)
        /* 「人」这一层跟档案是一体的：只清档案不清人，设置页会留下一堆
           「没有声纹的空壳人」，而 F0 兜底还会继续往他们身上标名字。 */
        PersonStore.clearAll(ctx)
    }

    private fun readProfile(id: String, f: File): Profile? = runCatching {
        DataInputStream(f.inputStream().buffered()).use { inp ->
            if (inp.readInt() != MAGIC) return null
            val ver = inp.readInt()
            if (ver < MIN_READ_VERSION || ver > VERSION) return null
            val name = inp.readUTF()
            // v2 没有 micKey 字段：旧档案一律视为「来源未知」，不参与任何判定
            val micKey = if (ver >= 3) inp.readUTF().takeIf { it.isNotBlank() } else null
            // v4 起有 personId。v2/v3 读成 null，交给 PersonStore.ensureMigrated 按名字补
            val personId = if (ver >= 4) inp.readUTF().takeIf { it.isNotBlank() } else null
            val count = inp.readInt()
            val dim = inp.readInt()
            if (count <= 0 || dim <= 0 || count > 64 || dim > 4096) return null
            Profile(id, name, List(count) { FloatArray(dim) { inp.readFloat() } }, micKey, personId)
        }
    }.getOrNull()

    /** v1 单档案迁移。只在进程内做一次，之后靠文件是否存在兜底 */
    private fun migrateLegacy(ctx: Context) {
        if (legacyChecked) return
        legacyChecked = true
        val legacy = File(dir(ctx), "owner.bin")
        if (!legacy.isFile) return

        val samples = readLegacy(legacy)
        if (samples.isNotEmpty()) {
            val name = prefs(ctx).getString(KEY_NAME, DEFAULT_NAME)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_NAME
            saveProfile(ctx, "1", name, samples, null)
            if (selfId(ctx) == null) setSelfId(ctx, "1")
        }
        runCatching { legacy.renameTo(File(dir(ctx), "owner.bin.migrated")) }
    }

    private fun readLegacy(f: File): List<FloatArray> = runCatching {
        DataInputStream(f.inputStream().buffered()).use { inp ->
            if (inp.readInt() != LEGACY_MAGIC) return emptyList()
            if (inp.readInt() != 1) return emptyList()
            val count = inp.readInt()
            val dim = inp.readInt()
            if (count <= 0 || dim <= 0 || count > 64 || dim > 4096) return emptyList()
            List(count) { FloatArray(dim) { inp.readFloat() } }
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ 比对

    /** 余弦相似度。campplus 输出未归一化，必须走余弦而不是内积 */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom <= 1e-8f) 0f else (dot / denom).coerceIn(-1f, 1f)
    }

    /**
     * 一个人内部的最高相似度。
     *
     * 取最大值而非均值：注册样本之间本身有差异（语速、音高），
     * 均值会把边界样本拉向中心，导致"很像其中一个样本"的情况被压低。
     * 取最大更宽松，代价是误判率略升 —— 这正是阈值可调要解决的问题。
     */
    fun bestScore(samples: List<FloatArray>, embedding: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var best = 0f
        for (s in samples) {
            val v = cosine(s, embedding)
            if (v > best) best = v
        }
        return best
    }

    /**
     * 挑出**当前录音通路**可用的档案。这是整套声纹判定的前置闸门。
     *
     * 为什么必须过滤而不是"照样比、分数低就不落名字"：不同通路的相似度落在
     * 0.2~0.3，跟"另一个陌生人"的分数完全重叠，照样比会得到一堆看似合理、
     * 实则毫无意义的分数，再经过阈值就变成随机落名字。直接排除才是诚实的。
     *
     * [micKey] 为空（还没确定通路）时返回空列表 —— 不知道这条音是从哪支麦来的，
     * 就不该判人。
     */
    fun eligible(profiles: List<Profile>, micKey: String?): List<Profile> {
        if (micKey.isNullOrBlank()) return emptyList()
        return profiles.filter { it.micKey == micKey }
    }

    /**
     * 在全部档案里找最像的一个。
     *
     * 只返回"最像"，不返回所有分数：判定语义是「这句话是谁说的」，
     * 是单选问题。把第二第三名也暴露出去只会让界面变复杂，对决策没帮助。
     */
    fun match(profiles: List<Profile>, embedding: FloatArray): Match? {
        var best: Profile? = null
        var bestScore = 0f
        for (p in profiles) {
            val s = bestScore(p.samples, embedding)
            if (s > bestScore) {
                bestScore = s
                best = p
            }
        }
        val n = best ?: return null
        return Match(n.name, bestScore, n.personId)
    }

    /**
     * 注册质量自检：同一份档案里样本两两之间的平均相似度。
     *
     * 数值过低（<0.5）说明录入时环境噪声大、或本人念得忽快忽慢，声纹不可靠，应重录。
     * 返回 null 表示样本不足，无法评估。
     */
    fun consistency(samples: List<FloatArray>): Float? {
        if (samples.size < 2) return null
        var sum = 0f
        var n = 0
        for (i in samples.indices) {
            for (j in i + 1 until samples.size) {
                sum += cosine(samples[i], samples[j])
                n++
            }
        }
        return if (n == 0) null else sum / n
    }
}
