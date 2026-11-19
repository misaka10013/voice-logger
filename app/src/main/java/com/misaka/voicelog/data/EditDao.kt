package com.misaka.voicelog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * 人工修订事件的读写，以及由它派生的质量指标。
 *
 * ## 为什么统计放在 DAO 里用 SQL 算
 * 全库 5000+ 句、事件表只会更大。取回 JVM 再聚合看起来更「优雅」，
 * 但每次打开面板要搬几万行过来，手机上就是一次可见的卡顿。
 * 聚合本来就是 SQLite 的本职工作。
 *
 * ## 所有 SUM 都套 IFNULL
 * 空表时 `SUM(...)` 返回 NULL，Room 往非空 Int 上映射会直接抛异常 ——
 * 也就是「新装 App 第一次打开质量页就崩」。这个是踩过的坑，不是防御性编程。
 */
@Dao
interface EditDao {

    @Insert
    suspend fun insert(e: TranscriptEditEntity): Long

    /** 某一行的修改历史，按时间倒序。用于「这句为什么变成这样」的追溯 */
    @Query("SELECT * FROM transcript_edit WHERE transcriptId = :id ORDER BY editedAt DESC")
    suspend fun byTranscript(id: Long): List<TranscriptEditEntity>

    @Query("SELECT COUNT(*) FROM transcript_edit")
    suspend fun count(): Int

    // ------------------------------------------------------------------ 总量

    @Query(
        """
        SELECT
            COUNT(*) AS total,
            IFNULL(SUM(CASE WHEN edited = 1 THEN 1 ELSE 0 END), 0) AS editedRows,
            IFNULL(SUM(CASE WHEN asrRaw IS NOT NULL THEN 1 ELSE 0 END), 0) AS trackedRows,
            IFNULL(SUM(CASE WHEN speakerEdited = 1 THEN 1 ELSE 0 END), 0) AS speakerFixed,
            IFNULL(SUM(CASE WHEN autoSpeakerName IS NOT NULL THEN 1 ELSE 0 END), 0) AS speakerAutoNamed,
            IFNULL(SUM(CASE WHEN speakerEdited = 1 AND autoSpeakerName IS NOT NULL THEN 1 ELSE 0 END), 0) AS speakerFixOnNamed,
            IFNULL(SUM(CASE WHEN speakerName IS NULL THEN 1 ELSE 0 END), 0) AS unlabeled,
            IFNULL(SUM(CASE WHEN speakerAuto != 0 THEN 1 ELSE 0 END), 0) AS autoDecided
        FROM transcript WHERE deletedAt = 0
        """,
    )
    suspend fun totals(): QualityTotals

    // ------------------------------------------------------------ 改动类型分布

    /**
     * 按改动性质分组。
     *
     * ⚠️ 每个 transcriptId **只取最后一条事件**。
     * 事件表里一句可能有多次修订记录（基准都是识别原文），全量 GROUP BY
     * 会让改过三次的句子被统计三遍，得到一个既不是「句数」也不是「错误量」的数。
     * 子查询用 `editedAt DESC, id DESC` 双排序：同一毫秒连改两次时只靠时间会取到两条。
     *
     * `chars` 是这些编辑涉及的真值字符总数，用作字错率的分母 ——
     * 拿它算出来的 CER 和「改了几句」是两个完全不同的量，别混着看。
     */
    @Query(
        """
        SELECT te.kind AS kind,
               COUNT(*) AS edits,
               COUNT(*) AS rows,
               IFNULL(SUM(te.insCount), 0) AS ins,
               IFNULL(SUM(te.delCount), 0) AS del,
               IFNULL(SUM(te.subCount), 0) AS sub,
               IFNULL(SUM(te.afterChars), 0) AS chars
        FROM transcript_edit te
        WHERE te.field = 'text'
          AND te.id = (
              SELECT x.id FROM transcript_edit x
              WHERE x.transcriptId = te.transcriptId AND x.field = 'text'
              ORDER BY x.editedAt DESC, x.id DESC LIMIT 1
          )
        GROUP BY te.kind
        ORDER BY edits DESC
        """,
    )
    suspend fun textKinds(): List<KindStat>

    /** 按触发来源分组：手改 / 应用AI建议 / 拆行 / 合并。AI 建议被大量采纳说明 ASR 弱但 LLM 能补 */
    @Query(
        """
        SELECT te.source AS source, COUNT(*) AS edits, COUNT(*) AS rows
        FROM transcript_edit te
        WHERE te.field = 'text'
          AND te.id = (
              SELECT x.id FROM transcript_edit x
              WHERE x.transcriptId = te.transcriptId AND x.field = 'text'
              ORDER BY x.editedAt DESC, x.id DESC LIMIT 1
          )
        GROUP BY te.source ORDER BY edits DESC
        """,
    )
    suspend fun textSources(): List<SourceStat>

    // ------------------------------------------------------------ 说话人改判

    /** 机器判 A、被人改成 B 的对照表（只统计机器给过名字的，未判的不算改判） */
    @Query(
        """
        SELECT IFNULL(autoSpeakerName, '') AS fromName,
               IFNULL(speakerName, '(清空)') AS toName,
               COUNT(*) AS cnt
        FROM transcript
        WHERE deletedAt = 0 AND speakerEdited = 1 AND autoSpeakerName IS NOT NULL
        GROUP BY 1, 2 ORDER BY cnt DESC LIMIT 20
        """,
    )
    suspend fun speakerFixes(): List<SpeakerFixStat>

    // ------------------------------------------------------------------ 分组

    /**
     * 按录音通路分组。
     *
     * 这是判断「是不是麦的锅」最直接的一张表：同一批人、相近场景，
     * 骨传导耳机和内置麦的改动率差多少，一眼就能看出来。
     */
    @Query(
        """
        SELECT s.micKey AS micKey,
               COUNT(*) AS total,
               IFNULL(SUM(CASE WHEN t.edited = 1 THEN 1 ELSE 0 END), 0) AS editedRows,
               IFNULL(SUM(CASE WHEN t.asrRaw IS NOT NULL THEN 1 ELSE 0 END), 0) AS trackedRows,
               IFNULL(SUM(CASE WHEN t.speakerEdited = 1 THEN 1 ELSE 0 END), 0) AS speakerFixed,
               IFNULL(SUM(CASE WHEN t.autoSpeakerName IS NOT NULL THEN 1 ELSE 0 END), 0) AS speakerAutoNamed
        FROM transcript t JOIN session s ON s.id = t.sessionId
        WHERE t.deletedAt = 0
        GROUP BY s.micKey
        ORDER BY total DESC
        """,
    )
    suspend fun byMic(): List<MicStat>

    /** 按天分组。看趋势用 —— 换模型/改参数之后，这条线有没有往下走 */
    @Query(
        """
        SELECT strftime('%m-%d', wallClock / 1000, 'unixepoch', '+8 hours') AS day,
               COUNT(*) AS total,
               IFNULL(SUM(CASE WHEN edited = 1 THEN 1 ELSE 0 END), 0) AS editedRows,
               IFNULL(SUM(CASE WHEN speakerEdited = 1 THEN 1 ELSE 0 END), 0) AS speakerFixed,
               IFNULL(SUM(CASE WHEN autoSpeakerName IS NOT NULL THEN 1 ELSE 0 END), 0) AS speakerAutoNamed
        FROM transcript
        WHERE deletedAt = 0
        GROUP BY day ORDER BY day DESC LIMIT 14
        """,
    )
    suspend fun byDay(): List<DayStat>
}

// ---------------------------------------------------------------- 聚合结果

data class QualityTotals(
    val total: Int = 0,
    val editedRows: Int = 0,
    /** 有识别原文留存的句子数 = 从 v7 起被编辑过的句子 */
    val trackedRows: Int = 0,
    /** 所有被人工改过说话人的句子。含「机器没给名字、人补标」的那部分 */
    val speakerFixed: Int = 0,
    /** 机器给出过名字的句子数，改判率的分母 */
    val speakerAutoNamed: Int = 0,
    /**
     * 机器给过名字、又被人工改掉的句子数 —— **这才是改判率的分母该配对的分子**。
     *
     * 不能拿 [speakerFixed] 当分子：它还包括「机器压根没给名字，人补标上去」的句子。
     * 把补标算成改判，会让机器「不敢下结论」的保守策略看起来像「经常判错」，
     * 而我们正是在权衡这两件事 —— 口径混了，这个权衡就没法做。
     */
    val speakerFixOnNamed: Int = 0,
    val unlabeled: Int = 0,
    val autoDecided: Int = 0,
) {
    /** 机器给了名字的句子里，被改掉的比例 */
    val relabelRate: Double get() = if (speakerAutoNamed > 0) speakerFixOnNamed.toDouble() / speakerAutoNamed else 0.0

    /** 机器没给名字、由人补标的比例 */
    val fillRate: Double
        get() {
            val miss = (total - speakerAutoNamed).coerceAtLeast(0)
            return if (miss > 0) (speakerFixed - speakerFixOnNamed).coerceAtLeast(0).toDouble() / miss else 0.0
        }
}

data class KindStat(
    val kind: String,
    val edits: Int,
    val rows: Int,
    val ins: Int,
    val del: Int,
    val sub: Int,
    val chars: Int,
) {
    val ops: Int get() = ins + del + sub
}

data class SourceStat(val source: String, val edits: Int, val rows: Int)

data class SpeakerFixStat(val fromName: String, val toName: String, val cnt: Int)

data class MicStat(
    val micKey: String,
    val total: Int,
    val editedRows: Int,
    val trackedRows: Int,
    val speakerFixed: Int,
    val speakerAutoNamed: Int,
)

data class DayStat(
    val day: String,
    val total: Int,
    val editedRows: Int,
    val speakerFixed: Int,
    val speakerAutoNamed: Int,
)
