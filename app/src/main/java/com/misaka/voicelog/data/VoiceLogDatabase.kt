package com.misaka.voicelog.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * ⚠️ 全库约定：**所有面向用户的查询都必须带 `deletedAt = 0`**。
 *
 * 删除一律是「先软删、7 天内可恢复」（见 `Retention`）。漏加过滤的表现是
 * 「删了还在」——不报错，只是数据看着诡异，比崩溃更难查。
 */
@Dao
interface TranscriptDao {

    @Insert
    suspend fun insert(item: TranscriptEntity): Long

    /**
     * 同步插入，供**裸线程**（采集服务的 asrLoop）使用。
     *
     * ⚠️ 裸线程里绝对不能用 `runBlocking { insert(...) }` 调上面的挂起版：
     * 实测会在写入时永久阻塞，线程静默卡死、不抛异常、CPU 也不涨。
     * 详见 [SegmentDao.insertBlocking] 上的说明。
     */
    @Insert
    fun insertBlocking(item: TranscriptEntity): Long

    @Query("SELECT * FROM transcript WHERE deletedAt = 0 ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<TranscriptEntity>>

    @Query("SELECT COUNT(*) FROM transcript WHERE deletedAt = 0")
    fun countFlow(): Flow<Int>

    @Query("SELECT * FROM transcript WHERE deletedAt = 0 ORDER BY id ASC")
    suspend fun all(): List<TranscriptEntity>

    /**
     * 物理清空（设置页「清空本地数据」专用）。
     * 注意：这是唯一不走软删的入口，所以调用方必须先让用户手输确认词。
     */
    @Query("DELETE FROM transcript")
    suspend fun clearAll()

    // ---------------------------------------------------------------- 会话维度

    /* ⚠️ 排序必须用 `startMs` 主键、id 兜底，**不能只用 id**。
       拆行会插入一条新记录，它的自增 id 是全局最大的 —— 只用 id 排序的话，
       从第 50 行拆出来的下半句会跑到整轮的最后一行去。
       startMs 在拆行时按字符比例重新分配，同段内严格递增；不同 VAD 段本身
       不重叠，所以 `startMs ASC` 恰好等于「说话顺序」。id 兜底处理 startMs
       相同的情况（段时长为 0 的老数据）。 */
    @Query(
        "SELECT * FROM transcript WHERE sessionId = :sessionId AND deletedAt = 0 " +
            "ORDER BY startMs ASC, id ASC",
    )
    fun bySession(sessionId: Long): Flow<List<TranscriptEntity>>

    @Query(
        "SELECT * FROM transcript WHERE sessionId = :sessionId AND deletedAt = 0 " +
            "ORDER BY startMs ASC, id ASC",
    )
    suspend fun listBySession(sessionId: Long): List<TranscriptEntity>

    @Query("SELECT * FROM transcript WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<TranscriptEntity>

    /** 按墙钟区间取句子，导出「某一天」用 */
    @Query(
        "SELECT * FROM transcript WHERE deletedAt = 0 AND wallClock >= :from AND wallClock < :to " +
            "ORDER BY wallClock ASC",
    )
    suspend fun between(from: Long, to: Long): List<TranscriptEntity>

    /**
     * 批量按会话取句子，同步时一次捞一批 20 轮用。
     *
     * 为什么不做成 20 次 [listBySession]：每次都要一次 Room 查询往返，
     * 而同步在后台跑、期间还在录音，往返越少对主流程干扰越小。
     */
    @Query(
        "SELECT * FROM transcript WHERE sessionId IN (:ids) AND deletedAt = 0 " +
            "ORDER BY sessionId ASC, startMs ASC, id ASC",
    )
    suspend fun listBySessions(ids: List<Long>): List<TranscriptEntity>

    // ---------------------------------------------------------------- 编辑标注

    @Query("UPDATE transcript SET speakerName = :name WHERE id IN (:ids)")
    suspend fun setSpeaker(ids: List<Long>, name: String?)

    /**
     * 撤销批量标注：把名字写回快照值，并**把 [speakerEdited] 清回 0**。
     *
     * 撤销的语义是「那些人工操作当作没发生」，所以要连「人改过」这个标记一起抹掉。
     * 只用 [setSpeaker] 的话，名字回去了、标记还留着，改判率会凭空多算一批
     * 「从机器值改成同一个机器值」的假改判 —— 面板上的数字会越用越假。
     */
    @Query("UPDATE transcript SET speakerName = :name, speakerEdited = 0 WHERE id IN (:ids)")
    suspend fun revertSpeaker(ids: List<Long>, name: String?)

    @Query("UPDATE transcript SET speakerName = :name WHERE sessionId = :sessionId")
    suspend fun setSpeakerForSession(sessionId: Long, name: String?)

    /**
     * 某人改名后，把历史句子里那个旧名一起改掉。
     *
     * 为什么必须级联：transcript 存的是**名字字符串**，不是 personId。
     * 不级联的话，改名之后同一个人在新句子里叫新名、老句子里叫旧名，
     * 统计里就会裂成两个人 —— 这正是 2026-11-21 那 5 + 30 句 chip 分裂的成因。
     *
     * 只改机器判的句子（`speakerEdited = 0`）：人工亲手标的名字可能另有意图
     * （比如故意把一句电视声标成「电视噪音」），不能被一次改名冲掉。
     *
     * 不改 [TranscriptEntity.autoSpeakerName]：那是「机器当时判的是谁」的审计快照，
     * 后期操作去改它，等于篡改证据 —— 改判率就再也算不准了。
     */
    @Query(
        "UPDATE transcript SET speakerName = :newName " +
            "WHERE speakerName = :oldName AND speakerEdited = 0 AND deletedAt = 0",
    )
    suspend fun renameSpeakerEverywhere(oldName: String, newName: String): Int

    @Query("UPDATE transcript SET text = :text, edited = 1 WHERE id = :id")
    suspend fun updateText(id: Long, text: String)

    @Query("UPDATE transcript SET speakerName = :name WHERE id = :id")
    suspend fun setSpeakerOne(id: Long, name: String?)

    /**
     * 人工标注说话人。
     *
     * ⚠️ 2026-11-16 起**不再把 [speakerAuto] 清零**，改用 [speakerEdited] 打标。
     *
     * 原来清 0 的理由是「不清的话，依赖『这行是不是机器标的』的判断会把它当机器结论」。
     * 但这个理由用一个布尔位就能满足，代价却是销毁证据：错判的句子 auto 被清零后
     * 全部掉出统计桶，于是算出「F0 准确率 100%」——错的全不见了，剩下的当然全对。
     * 现在判断「机器值还有效吗」统一看 `speakerEdited`。
     *
     * 同时置 `syncState = 1`：名字变了，云端那份必须跟上。
     */
    @Query(
        "UPDATE transcript SET speakerName = :name, speakerEdited = 1, syncState = 1 " +
            "WHERE id IN (:ids)",
    )
    suspend fun setSpeakerManual(ids: List<Long>, name: String?)

    /**
     * 确认机器猜的名字（名字不变，只把它从「机器猜」转成「人工确认」）。
     *
     * 为什么不复用 [setSpeakerManual]：那条 SQL 会把 `syncState` 置 1，
     * 而确认时 speakerName 没变、payload 里也没有 speakerEdited 这一位，
     * 云端那份内容完全一样 —— 标脏只会白跑一次上行。
     */
    @Query("UPDATE transcript SET speakerEdited = 1 WHERE id IN (:ids)")
    suspend fun markSpeakerConfirmed(ids: List<Long>)

    /**
     * 首次编辑时留存 ASR 原文。
     *
     * 带 `asrRaw IS NULL` 条件而不是先读后写：并发的两次编辑都读到 null 时，
     * 先到的写入原文、后到的被这个条件挡住，不会把「上次改后的文本」当成原文覆盖上去。
     */
    @Query("UPDATE transcript SET asrRaw = :raw WHERE id = :id AND asrRaw IS NULL")
    suspend fun setAsrRawIfNull(id: Long, raw: String)

    /**
     * 一次性把某个历史标签名统一成当前名（2026-11-12「电视外放」→「视频噪音」）。
     *
     * 与 [setSpeakerManual] 的区别：这批本来就是手动标的，`speakerAuto` 不动；
     * 置 `syncState = 1` 是因为名字变了，云端那份必须跟上 ——
     * 漏置脏的话，改名只在本地生效，下次同步又会被云端旧值盖回去。
     * 不带 `deletedAt = 0` 条件：回收站里的恢复出来也该是新名字。
     */
    @Query(
        "UPDATE transcript SET speakerName = :to, syncState = 1 " +
            "WHERE speakerName = :from AND deletedAt = 0",
    )
    suspend fun renameSpeakerAlias(from: String, to: String): Int

    // ---------------------------------------------------------------- 拆行 / 合并行

    @Query("SELECT * FROM transcript WHERE id = :id")
    suspend fun byId(id: Long): TranscriptEntity?

    /**
     * 同一会话里紧挨在 (startMs, id) 前面的那一行。
     *
     * ⚠️ 必须和列表页用**完全一样的**排序口径（`startMs ASC, id ASC`），
     * 否则界面上看到的「上一行」和这里取到的不一定是同一行 ——
     * 退格合并会并到一个用户看不见的地方去，比崩溃更难发现。
     */
    @Query(
        "SELECT * FROM transcript WHERE sessionId = :sessionId AND deletedAt = 0 " +
            "AND (startMs < :startMs OR (startMs = :startMs AND id < :id)) " +
            "ORDER BY startMs DESC, id DESC LIMIT 1",
    )
    suspend fun prevInSession(sessionId: Long, startMs: Long, id: Long): TranscriptEntity?

    /**
     * 拆行/合并行后写回一行文本。
     *
     * `endMs` 一起改的理由：拆开后前半行只占原时间段的前一段，不改的话两行显示
     * 完全相同的结束时间，界面上分不清哪句先说。合并则相反，要吞掉后一行的时间。
     *
     * 同时清空 AI 建议：建议是针对**原来那句话**给的，文字结构变了之后它必然对不上，
     * 留在那里只会让人误以为「这句 AI 看过了」。
     */
    @Query(
        "UPDATE transcript SET text = :text, endMs = :endMs, edited = 1, syncState = 1, " +
            "proofText = NULL, proofRisk = '', proofRatio = 0 WHERE id = :id",
    )
    suspend fun applySplitEdit(id: Long, text: String, endMs: Long)

    /**
     * 只改文本与结束时间，**不**置 `edited`。
     *
     * 自动断句不是用户的手动编辑：如果把它标成「已改」，一来导出时会被当成人工修订版、
     * 二来下次「重新断句」会因为这些行「已改」而跳过它们 —— 结果是一次性动作，做错了无法重来。
     */
    @Query("UPDATE transcript SET text = :text, endMs = :endMs, syncState = 1 WHERE id = :id")
    suspend fun setTextSpan(id: Long, text: String, endMs: Long)


    /** 已标注的历史人名，供批量标注时做快捷选项（去重、按使用频次排序） */
    @Query(
        "SELECT speakerName FROM transcript WHERE speakerName IS NOT NULL AND deletedAt = 0 " +
            "AND speakerName != '' GROUP BY speakerName ORDER BY COUNT(*) DESC LIMIT 12",
    )
    suspend fun knownSpeakers(): List<String>

    @Query("SELECT COUNT(*) FROM transcript WHERE sessionId = :sessionId AND deletedAt = 0")
    suspend fun countNow(sessionId: Long): Int

    /** 用户亲手改过的句子数。重新转录前要报给用户，那是真正会心疼的部分 */
    @Query("SELECT COUNT(*) FROM transcript WHERE sessionId = :sessionId AND deletedAt = 0 AND edited = 1")
    suspend fun editedCountNow(sessionId: Long): Int

    /**
     * 断开会话里所有句子与音频段的关联。
     *
     * 只在「仅清空音频」时用：音频文件已经删了，还留着 segmentId 会让详情页
     * 去推算一个不存在的文件、提示「音频已不在本地」——用户明明是自己清的，
     * 却被系统当成故障报告。
     */
    @Query("UPDATE transcript SET segmentId = 0 WHERE sessionId = :sessionId AND deletedAt = 0 AND segmentId != 0")
    suspend fun clearSegmentLinks(sessionId: Long)

    /**
     * 按关键词搜句子，聚合成「哪几轮里有」。
     *
     * ## 为什么在 SQL 里做而不是取回来在 Kotlin 里 filter
     * 1900 多行现在还好说，但它是会长的；而 LIKE 是全表扫，SQLite 在 native 侧做
     * 比把全部行塞进 JVM、再构造一堆对象快一个量级，还省内存。
     *
     * ## 为什么带 snippet 子查询
     * 只给「命中 3 句」用户无法判断相关性，得点进去才知道是不是他要的那句。
     * 给一句原文片段，八成的情况下他自己就能认出「就是这轮」。
     *
     * ## 为什么 LIMIT 100
     * 搜「我」这种字会命中几乎所有会话，结果列表本身就没有意义了。
     * 100 条封顶，同时在界面上提示「只显示前 100 轮」。
     */
    @Query(
        """
        SELECT s.id AS sessionId,
               s.startWall AS startWall,
               COUNT(t.id) AS cnt,
               (SELECT t2.text FROM transcript t2
                 WHERE t2.sessionId = s.id AND t2.deletedAt = 0 AND t2.text LIKE :like
                 ORDER BY t2.startMs ASC, t2.id ASC LIMIT 1) AS snippet
        FROM session s JOIN transcript t ON t.sessionId = s.id
        WHERE s.deletedAt = 0 AND t.deletedAt = 0 AND t.text LIKE :like
        GROUP BY s.id
        ORDER BY s.startWall DESC
        LIMIT 100
        """,
    )
    suspend fun search(like: String): List<SearchHit>

    @Query("SELECT COUNT(*) FROM transcript WHERE sessionId = :sessionId AND speakerName IS NULL AND deletedAt = 0")
    fun unlabeledCount(sessionId: Long): Flow<Int>

    // ---------------------------------------------------------------- 软删除

    @Query("UPDATE transcript SET deletedAt = :at, syncState = 2 WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<Long>, at: Long)

    @Query("UPDATE transcript SET deletedAt = :at, syncState = 2 WHERE sessionId = :sessionId")
    suspend fun softDeleteBySession(sessionId: Long, at: Long)

    @Query("UPDATE transcript SET deletedAt = 0, syncState = 1 WHERE id IN (:ids)")
    suspend fun restore(ids: List<Long>)

    @Query("UPDATE transcript SET deletedAt = 0, syncState = 1 WHERE sessionId = :sessionId")
    suspend fun restoreBySession(sessionId: Long)

    /** 最近删除列表，按删除时间倒序 */
    @Query("SELECT * FROM transcript WHERE deletedAt > 0 ORDER BY deletedAt DESC LIMIT 200")
    fun deletedFlow(): Flow<List<TranscriptEntity>>

    /** 真删（回收站过期清理用）。调用前请确认这些 id 的 deletedAt 已够久 */
    @Query("DELETE FROM transcript WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun purgeDeletedBefore(before: Long): Int

    /** 真删一整轮的句子。仅在回收站到期、或会话已被硬删时使用 */
    @Query("DELETE FROM transcript WHERE sessionId = :sessionId")
    suspend fun hardDeleteBySession(sessionId: Long)

    @Query("SELECT COUNT(*) FROM transcript WHERE deletedAt > 0")
    fun deletedCountFlow(): Flow<Int>

    // ---------------------------------------------------------------- AI 校对

    /** 写入建议（不碰原文）。risk 与 ratio 一起存，便于列表按风险排序 */
    @Query(
        "UPDATE transcript SET proofText = :suggest, proofRisk = :risk, " +
            "proofRatio = :ratio, proofAt = :at WHERE id = :id",
    )
    suspend fun setProof(id: Long, suggest: String, risk: String, ratio: Float, at: Long)

    /** 应用建议：原文替换、标记已改、清掉建议、同步置脏 */
    @Query(
        "UPDATE transcript SET text = :suggest, edited = 1, proofText = NULL, " +
            "proofAt = :at, syncState = 1 WHERE id = :id",
    )
    suspend fun applyProof(id: Long, suggest: String, at: Long)

    /**
     * 采纳建议后的收尾：清掉建议、打时间戳、置脏。
     *
     * 与 [applyProof] 的分工：那个连 text 一起替换，会绕开修订记账；
     * 这个只做收尾，文本替换交给 [applyTextEdit]（带 source = proof），
     * 于是「AI 建议被采纳了多少句」也能进统计 —— 那个比例直接说明
     * ASR 弱到什么程度、而语言模型能补回来多少。
     */
    @Query("UPDATE transcript SET proofText = NULL, proofAt = :at, syncState = 1 WHERE id = :id")
    suspend fun markProofApplied(id: Long, at: Long)

    /** 丢弃建议（保留 proofAt 表示「校对过但没采纳」） */
    @Query("UPDATE transcript SET proofText = NULL WHERE id = :id")
    suspend fun dropProof(id: Long)

    /** 有建议待处理的句子数，用于会话卡片角标 */
    @Query("SELECT COUNT(*) FROM transcript WHERE sessionId = :sessionId AND proofText IS NOT NULL")
    fun pendingProofCount(sessionId: Long): Flow<Int>

    /**
     * 标记「校对过但无需修改」：只打时间戳，不动原文与建议。
     *
     * 少这一条会浪费钱 —— `proofCandidates` 用 `proofAt = 0` 排除已校对句，
     * 而「无需修改」的句子 proofText 仍是 null，如果不打时间戳，
     * 下次重跑会把它们再送一遍模型。
     */
    @Query("UPDATE transcript SET proofAt = :at WHERE id = :id")
    suspend fun markProofChecked(id: Long, at: Long)

    /** 同 [pendingProofCount] 的挂起版本，供后台逻辑同步取数（Flow 版给界面用） */
    @Query("SELECT COUNT(*) FROM transcript WHERE sessionId = :sessionId AND proofText IS NOT NULL")
    suspend fun pendingProofCountNow(sessionId: Long): Int

    /**
     * 待校对的句子（按时间正序，与界面顺序一致）。
     *
     * 三个排除条件都是花钱的：
     * · `proofAt = 0` —— 校对过就不重复送。这是幂等的基础：中途断掉再跑，
     *   已处理的批不会重花一次钱。注意「校对过但无需修改」的句子 proofText 仍是 null，
     *   所以不能用 `proofText IS NULL` 代替这一条。
     * · `edited = 0` —— 用户手改过的句子不再让模型碰。
     * · `deletedAt = 0` —— 已删句不送。
     */
    @Query(
        "SELECT * FROM transcript WHERE sessionId = :sessionId AND deletedAt = 0 " +
            "AND edited = 0 AND proofAt = 0 AND text != '' ORDER BY startMs ASC, id ASC",
    )
    suspend fun proofCandidates(sessionId: Long): List<TranscriptEntity>

    /** 取某一风险等级的建议，供「采纳全部低风险」用 */
    @Query(
        "SELECT * FROM transcript WHERE sessionId = :sessionId AND deletedAt = 0 " +
            "AND proofText IS NOT NULL AND proofRisk = :risk ORDER BY startMs ASC, id ASC",
    )
    suspend fun proofsByRisk(sessionId: Long, risk: String): List<TranscriptEntity>

    /**
     * 清空本轮所有建议并把 proofAt 归零 —— 让它们重新进入待校对队列。
     * 用途：用户改了词表/人名后想用新配置重跑。**手改过的句子不受影响**（edited=1 仍在）。
     */
    @Query(
        "UPDATE transcript SET proofText = NULL, proofRisk = '', proofRatio = 0, proofAt = 0 " +
            "WHERE sessionId = :sessionId AND edited = 0",
    )
    suspend fun resetProofs(sessionId: Long): Int

    // ---------------------------------------------------------------- 同步

    @Query("UPDATE transcript SET syncState = :state WHERE id IN (:ids)")
    suspend fun setSyncState(ids: List<Long>, state: Int)

    /**
     * 一轮同步成功后，把这一轮里**已删除**的句子标记清掉。
     *
     * 为什么要单独清 `syncState = 2`：删句子走的是软删（`syncState = 2`），
     * 但真正上传的 items 只含活句子（[listBySessions] 过滤 `deletedAt = 0`），
     * 云端每次都是整份替换（`items = normItems(s.items)`），
     * 所以同步成功后云端**已经**没有这些句子了 —— 本地这条标记却没人清，
     * 于是 [SessionDao.dirtyIds] 每次都把它们数成「待同步」，永远清不掉。
     * 2026-11-19 实测：1659 条软删句子把 11 轮早已同步完的会话永久钉在待同步队列里。
     *
     * ⚠️ 条件必须限定 `syncState = 2`：
     * 同步请求在途时用户可能又编辑了某句（`syncState = 1`），那是新改动，
     * 不在本次 payload 里，清掉就等于丢改动。只清「删除」这一种。
     */
    @Query("UPDATE transcript SET syncState = 0 WHERE sessionId = :sessionId AND syncState = 2")
    suspend fun clearDeletedSyncState(sessionId: Long)

    @Query("SELECT COUNT(*) FROM transcript WHERE syncState != 0 AND deletedAt = 0")
    fun dirtyCountFlow(): Flow<Int>
}

/** [SessionDao.allMicKeys] 的投影：只取匹配声纹档案要用的那两个字段 */
data class SessionMicKeyRow(val id: Long, val micKey: String)

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(item: SessionEntity): Long

    /**
     * 全部会话的通路标识，供批量转写时按段所属会话过滤声纹档案。
     *
     * 一次全取而不是逐段查询：一段一句 SQL 在几百段的批次里会白白压出几百次
     * 磁盘访问，而会话只有几十条、每条两个短字段，全量读的代价可以忽略。
     */
    @Query("SELECT id, micKey FROM session")
    suspend fun allMicKeys(): List<SessionMicKeyRow>

    @Query("UPDATE session SET endWall = :endWall WHERE id = :id")
    suspend fun close(id: Long, endWall: Long)

    @Query("UPDATE session SET segmentCount = :count, speechMs = :speechMs WHERE id = :id")
    suspend fun updateStats(id: Long, count: Int, speechMs: Long)

    // ---------------------------------------------------------------- 同步版
    /* 采集服务的 encodeLoop / asrLoop 跑在裸 Thread 上，不能用 runBlocking 调挂起 DAO。
       2026-11-07 实测：一段 13 分钟、64 段的录音里，编码线程在**第一段之后永久卡死**
       （updateFile 已写入，updateStats 从未执行，会话统计停在 0/0），
       队列里 63 段没有任何人消费，直到停止时才被标成「未编码完成」——
       用户看到的就是「录了 13 分钟，只有一条记录还转写失败」。
       所以这些调用点一律改成同步方法。 */

    @Insert
    fun insertBlocking(item: SessionEntity): Long

    @Query("UPDATE session SET endWall = :endWall WHERE id = :id")
    fun closeBlocking(id: Long, endWall: Long)

    /**
     * 回填本轮录音设备（同步版）。
     *
     * 调用点在采集线程里（`startRecording()` 之后立刻读 `routedDevice`），
     * 那条线程是裸 Thread 不是协程，所以只能走 blocking 版本 ——
     * 详见本节开头那条「编码线程永久卡死」的记录。
     */
    @Query("UPDATE session SET inputDevice = :device WHERE id = :id")
    fun updateInputDeviceBlocking(id: Long, device: String)

    /**
     * 记录本轮录音的**通路标识**（[com.misaka.voicelog.audio.MicRoute.keyOf]）。
     *
     * 和 [updateInputDeviceBlocking] 分开而不合并：那个是给人看的描述、
     * 这个是被程序当键用的值（比如 `bt|OpenRun by Shokz`），
     * 混在一起以后有人改了显示文案就会把声纹匹配悄悄弄坏。
     */
    @Query("UPDATE session SET micKey = :key WHERE id = :id")
    fun updateMicKeyBlocking(id: Long, key: String)

    /**
     * 段数 / 语音时长**原子自增**，不做「先读再写」。
     *
     * 读-改-写的写法在「停止时重算覆盖」与「编码线程自增」并发时会互相盖掉，
     * 表现为列表页句数忽然从 N 掉回 0。自增只有一条 SQL，天然安全。
     */
    @Query("UPDATE session SET segmentCount = segmentCount + 1, speechMs = speechMs + :addMs WHERE id = :id")
    fun bumpStatsBlocking(id: Long, addMs: Long)

    @Query("SELECT * FROM session WHERE id = :id")
    fun byIdBlocking(id: Long): SessionEntity?

    @Query("SELECT * FROM session WHERE deletedAt = 0 ORDER BY id DESC")
    fun allFlow(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun byId(id: Long): SessionEntity?

    /** 批量取会话，同步时按批组装载荷用 */
    @Query("SELECT * FROM session WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<SessionEntity>

    /**
     * 首次同步前写入本地生成的 cloudSid。
     *
     * ⚠️ sid 由**客户端**生成并立即持久化，不是等服务端发：
     * 1) 服务端 checkSession 要求 sid 非空，根本不生成；
     * 2) 若用「本地自增 id 拼 sid」，卸载重装后 id 归零会撞上历史文档，
     *    表现为旧对话被新对话覆盖 —— 不报错、只丢数据。
     * 随机 UUID 让重装后的新数据必然落在新文档上。
     */
    @Query("UPDATE session SET cloudSid = :sid WHERE id = :id")
    suspend fun setCloudSid(id: Long, sid: String)

    @Query("SELECT * FROM session WHERE deletedAt = 0 ORDER BY id DESC LIMIT 1")
    suspend fun latest(): SessionEntity?

    /**
     * 物理删除一轮会话。
     *
     * ⚠️ 只用于「导入中途取消」这种会话从未对外存在过的情况。
     * 正常删除记录一律走软删（[deletedAt]），因为外部 AI 需要看到删除标记，
     * 而且 7 天内要能恢复。这里硬删的前提是：这轮还不存在于任何人的视野里。
     */
    @Query("DELETE FROM session WHERE id = :id")
    suspend fun hardDelete(id: Long)

    @Query("SELECT * FROM session WHERE deletedAt > 0 ORDER BY deletedAt DESC LIMIT 200")
    fun deletedFlow(): Flow<List<SessionEntity>>

    /**
     * 会话列表用的聚合查询。
     *
     * 把「首句预览」「我/他人/未标」三项统计一次性拉出来，避免列表滚动时
     * 每行再查一次数据库。这些子查询在几百段规模下开销可忽略。
     *
     * 统计口径是 speakerName 而非 speakerAuto：用户手动标注之后，
     * 自动判定字段就不再代表最终事实了，只有标签名才是。
     *
     * [fromWall] / [toWall] 传 0 表示不限，用于记录页的日期筛选。
     * 用「区间 + 0 表示不限」而不是两个方法，是为了只有一份 SQL 要维护。
     */
    @Query(
        """
        SELECT s.id AS id,
               s.startWall AS startWall,
               s.endWall AS endWall,
               s.segmentCount AS segmentCount,
               s.speechMs AS speechMs,
               s.scene AS scene,
               s.sceneNote AS sceneNote,
               s.syncState AS syncState,
               (SELECT t.text FROM transcript t WHERE t.sessionId = s.id AND t.deletedAt = 0 ORDER BY t.id ASC LIMIT 1) AS preview,
               (SELECT COUNT(*) FROM transcript t WHERE t.sessionId = s.id AND t.deletedAt = 0) AS textCount,
               (SELECT COUNT(*) FROM transcript t WHERE t.sessionId = s.id AND t.deletedAt = 0 AND t.speakerName = :meName) AS meCount,
               (SELECT COUNT(*) FROM transcript t WHERE t.sessionId = s.id AND t.deletedAt = 0 AND t.speakerName IS NOT NULL AND t.speakerName != :meName) AS otherCount,
               (SELECT COUNT(*) FROM transcript t WHERE t.sessionId = s.id AND t.deletedAt = 0 AND t.speakerName IS NULL) AS unlabeledCount,
               (SELECT COUNT(*) FROM transcript t WHERE t.sessionId = s.id AND t.deletedAt = 0 AND t.proofText IS NOT NULL) AS proofCount,
               (SELECT COUNT(*) FROM segment g WHERE g.sessionId = s.id AND g.deletedAt = 0 AND g.status IN (0, 3)) AS pendingSegments
        FROM session s
        WHERE s.deletedAt = 0
          AND (:fromWall <= 0 OR s.startWall >= :fromWall)
          AND (:toWall <= 0 OR s.startWall < :toWall)
        ORDER BY s.id DESC
        """,
    )
    fun previewsFlow(meName: String, fromWall: Long, toWall: Long): Flow<List<SessionPreview>>

    /**
     * 每轮会话的**按标签名**句数分布。
     *
     * 为什么不能由 [previewsFlow] 里的 meCount / otherCount 顶上：那三个字段是
     * 「我 / 他人 / 未判」的固定三分类，用户标了「说话人A」和「说话人B」之后，
     * 卡片上仍然只写「说话人A 168 / 他人 168」—— 看起来像统计错了，
     * 实际是把两个不同的人合并进了「他人」。
     *
     * 一次查全表再在内存里按 sessionId 分组，避免列表每行再查一次
     * （几十轮 × 每轮一次聚合，滚动时会抖）。
     */
    @Query(
        """
        SELECT sessionId AS sessionId, speakerName AS speakerName, COUNT(*) AS cnt
        FROM transcript
        WHERE deletedAt = 0
        GROUP BY sessionId, speakerName
        ORDER BY sessionId ASC, cnt DESC
        """,
    )
    fun speakerBucketsFlow(): Flow<List<SpeakerBucket>>

    @Query("UPDATE session SET scene = :scene, sceneNote = :note WHERE id = :id")
    suspend fun setScene(id: Long, scene: String?, note: String?)

    // ---------------------------------------------------------------- 软删除

    @Query("UPDATE session SET deletedAt = :at, syncState = 2 WHERE id = :id")
    suspend fun softDelete(id: Long, at: Long)

    /** 批量软删。整轮删除时和子句一起删，不留「孤儿句子」在导出里冒出来 */
    @Query("UPDATE session SET deletedAt = :at, syncState = 2 WHERE id IN (:ids)")
    suspend fun softDeleteMany(ids: List<Long>, at: Long)

    @Query("UPDATE session SET deletedAt = 0, syncState = 1 WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("UPDATE session SET deletedAt = 0, syncState = 1 WHERE id IN (:ids)")
    suspend fun restoreMany(ids: List<Long>)

    /**
     * 待同步删除的会话。
     *
     * ⚠️ 不能复用 [dirtyIds]：那条查询排除了 deletedAt>0，
     * 正是「活数据」该有的行为 —— 但删除本身也需要一条通道，
     * 否则离线删掉的会话，云端那份会永远留着，而用户以为已经删了。
     */
    @Query("SELECT * FROM session WHERE deletedAt > 0 AND syncState = 2 ORDER BY deletedAt ASC LIMIT 100")
    suspend fun pendingDeletes(): List<SessionEntity>

    /**
     * 回收站里已经过了保留期的会话。给 [com.misaka.voicelog.core.RecycleBin] 用。
     *
     * 不带 LIMIT：过期项正常情况下是个位数，而漏掉一批就意味着那批音频
     * 继续占着空间、且下次启动才会被处理 —— 宁可一次全清。
     */
    @Query("SELECT * FROM session WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun expired(before: Long): List<SessionEntity>

    @Query("UPDATE session SET syncState = 0 WHERE id IN (:ids)")
    suspend fun markDeletesSynced(ids: List<Long>)

    @Query("DELETE FROM session WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun purgeDeletedBefore(before: Long): Int

    @Query("DELETE FROM session")
    suspend fun clearAll()

    // ---------------------------------------------------------------- 同步

    @Query("UPDATE session SET cloudSid = :sid, cloudRev = :rev, syncState = 0 WHERE id = :id")
    suspend fun markSynced(id: Long, sid: String, rev: Int)

    @Query("UPDATE session SET syncState = :state WHERE id = :id")
    suspend fun setSyncState(id: Long, state: Int)

    /** 待同步的会话 id（有改动或有句子改动）。导出上传队列用 */
    @Query(
        "SELECT DISTINCT s.id FROM session s WHERE s.deletedAt = 0 AND (" +
            "s.syncState != 0 OR EXISTS(SELECT 1 FROM transcript t WHERE t.sessionId = s.id AND t.syncState != 0)) " +
            "ORDER BY s.id ASC",
    )
    suspend fun dirtyIds(): List<Long>
}

/**
 * 关键词检索的一条结果：某一轮会话 + 命中了多少句 + 第一处命中的原文。
 *
 * 不做成「一句一条」：用户要找的是「那次谈话」，不是孤立的句子。
 * 一轮里命中 5 句，他点进去看上下文才有意义。
 */
data class SearchHit(
    val sessionId: Long,
    val startWall: Long,
    val cnt: Int,
    val snippet: String?,
)

/** 会话列表行，非实体表，仅用于查询结果映射 */
data class SessionPreview(    val id: Long,
    val startWall: Long,
    val endWall: Long,
    val segmentCount: Int,
    val speechMs: Long,
    val scene: String?,
    val sceneNote: String?,
    val syncState: Int,
    val preview: String?,
    /** 实际文字行数。拆行之后它和 [segmentCount]（音频段数）会不一样，列表要显示这个 */
    val textCount: Int,
    val meCount: Int,
    val otherCount: Int,
    val unlabeledCount: Int,
    /** 有 AI 建议待处理的句数 */
    val proofCount: Int,
    /**
     * 还没转写的音频段数。
     *
     * 存在的理由：只录不转模式下 [segmentCount] 存的是「音频段数」，
     * 列表里直接写成「5 句」会让用户点进去发现一个字都没有。有这一项才能
     * 区分「5 句文字」和「5 段待转写的音频」。
     */
    val pendingSegments: Int,
)

/**
 * 一轮会话里某个说话人（按**标签名**）占多少句。
 *
 * `speakerName` 为 null 表示「未判」—— 这是机器没把握时的结论，不是用户没填。
 */
data class SpeakerBucket(
    val sessionId: Long,
    val speakerName: String?,
    val cnt: Int,
)

@Dao
interface SegmentDao {

    @Insert
    suspend fun insert(item: SegmentEntity): Long

    @Query("SELECT * FROM segment WHERE id = :id")
    suspend fun byId(id: Long): SegmentEntity?

    @Query("SELECT * FROM segment WHERE sessionId = :sessionId AND deletedAt = 0 ORDER BY startMs ASC")
    fun bySession(sessionId: Long): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segment WHERE sessionId = :sessionId AND deletedAt = 0 ORDER BY startMs ASC")
    suspend fun listBySession(sessionId: Long): List<SegmentEntity>

    /** 同步版，供裸线程（停止收尾时重算统计）使用 */
    @Query("SELECT * FROM segment WHERE sessionId = :sessionId AND deletedAt = 0 ORDER BY startMs ASC")
    fun listBySessionBlocking(sessionId: Long): List<SegmentEntity>

    /** 待转写队列：先到先转 */
    @Query("SELECT * FROM segment WHERE status = 0 AND deletedAt = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<SegmentEntity>

    @Query("SELECT COUNT(*) FROM segment WHERE status = 0 AND deletedAt = 0")
    fun pendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM segment WHERE status = 0 AND deletedAt = 0")
    suspend fun pendingCount(): Int

    /**
     * 合计占用字节。**包含软删除但未清空的**，因为磁盘确实还占着，
     * 界面显示的空间必须和系统设置里看到的一致，否则用户会怀疑统计造假。
     */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM segment")
    fun totalBytesFlow(): Flow<Long>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM segment")
    suspend fun totalBytes(): Long

    @Query("SELECT COUNT(*) FROM segment WHERE deletedAt = 0")
    fun countFlow(): Flow<Int>

    @Query("UPDATE segment SET status = 0, failReason = '' WHERE sessionId = :sessionId AND status = 3 AND deletedAt = 0")
    suspend fun requeueFailed(sessionId: Long): Int

    /**
     * 把一轮里所有未删除的段落重新排回待转写队列（重新转录用）。
     *
     * 顺手清掉 transcriptId：它指向的是即将被软删的旧句子，留着就是一条
     * 指向已删数据的悬空引用，日后排查「这句是哪段音频转的」会指向错误对象。
     */
    @Query(
        "UPDATE segment SET status = 0, failReason = '', transcriptId = 0 " +
            "WHERE sessionId = :sessionId AND deletedAt = 0",
    )
    suspend fun requeueAll(sessionId: Long): Int

    /**
     * 只把**指定 id** 的失败段重新排队。
     *
     * 调用方必须先确认这些段的音频文件真的还在（见 `TranscribeWorker.retryFailed`）：
     * 无差别 requeue 会把「音频从没写成功」的段也塞回队列，
     * 转一遍再失败、又变回失败，用户点多少次都是同一个结果，纯粹浪费他的时间。
     */
    @Query("UPDATE segment SET status = 0, failReason = '' WHERE id IN (:ids)")
    suspend fun requeueFailedByIds(ids: List<Long>): Int

    /** 某轮的全部失败段，供「重试前先验文件」与「清理无法恢复的段」使用 */
    @Query("SELECT * FROM segment WHERE sessionId = :sessionId AND status = 3 AND deletedAt = 0 ORDER BY id ASC")
    suspend fun failedSegments(sessionId: Long): List<SegmentEntity>

    /** 物理删除一轮的全部音频段。仅用于导入中途取消（见 [SessionDao.hardDelete] 的说明） */
    @Query("DELETE FROM segment WHERE sessionId = :sessionId")
    suspend fun hardDeleteBySession(sessionId: Long)

    /**
     * 一轮会话里段落的状态分布，供详情页显示「还有几段没转 / 几段失败」。
     *
     * 不做成三个 count 查询：详情页要随转写进度实时刷新，三个 Flow 各自发一次，
     * 界面会闪三次。一个查询出三个数，一次重组。
     */
    @Query(
        """
        SELECT COALESCE(SUM(status = 0), 0) AS pending,
               COALESCE(SUM(status = 3), 0) AS failed,
               COALESCE(SUM(status = 2), 0) AS done,
               COALESCE(MAX(CASE WHEN status = 3 THEN failReason END), '') AS failReason
        FROM segment WHERE sessionId = :sessionId AND deletedAt = 0
        """,
    )
    fun tallyFlow(sessionId: Long): Flow<SegmentTally>

    @Query("UPDATE segment SET status = :status, failReason = :reason WHERE id = :id")
    suspend fun updateStatus(id: Long, status: Int, reason: String = "")

    /** 编码完成后回填真实路径与字节数（插入时还不知道文件多大） */
    @Query("UPDATE segment SET filePath = :path, sizeBytes = :bytes WHERE id = :id")
    suspend fun updateFile(id: Long, path: String, bytes: Long)

    /* ---------------------------------------------------------- 阻塞版（导入专用）
       导入跑在独立线程上，一侧是 MediaExtractor/MediaCodec 的同步阻塞调用，
       另一侧是数据库写。在裸线程里再套 `runBlocking` 去调挂起版 DAO，
       实测会在写入时**永久阻塞**：进度冻结、页面不报错、进程 CPU 也不涨 ——
       是三种失败里最难查的一种（看起来像卡死，实际是协程调度里的等待）。
       用同步方法把它压成同一条线程上的顺序调用，就没有这层调度了。

       ⚠️ 只能在后台线程调用。Room 在主线程会直接抛 IllegalStateException。 */

    @Insert
    fun insertBlocking(item: SegmentEntity): Long

    @Query("UPDATE segment SET status = :status, failReason = :reason WHERE id = :id")
    fun updateStatusBlocking(id: Long, status: Int, reason: String = "")

    @Query("UPDATE segment SET filePath = :path, sizeBytes = :bytes WHERE id = :id")
    fun updateFileBlocking(id: Long, path: String, bytes: Long)

    @Query("UPDATE segment SET status = :status, transcriptId = :tid, failReason = :reason WHERE id = :id")
    suspend fun markDone(id: Long, status: Int, tid: Long, reason: String = "")

    /**
     * 进程被杀会留下 status=RUNNING 的僵尸段。启动时必须重置，
     * 否则这些段永远不会再进队列（既不在待转，也不在完成）。
     */
    @Query("UPDATE segment SET status = 0 WHERE status = 1")
    suspend fun resetRunning(): Int

    @Query("UPDATE segment SET deletedAt = :at WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<Long>, at: Long)

    @Query("UPDATE segment SET deletedAt = :at WHERE sessionId = :sessionId")
    suspend fun softDeleteBySession(sessionId: Long, at: Long)

    @Query("DELETE FROM segment WHERE id IN (:ids)")
    suspend fun purge(ids: List<Long>)

    @Query("DELETE FROM segment WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun purgeDeletedBefore(before: Long): Int

    /** 按天列已落盘音频，素材页分组用 */
    @Query(
        "SELECT * FROM segment WHERE deletedAt = 0 AND wallClock >= :from AND wallClock < :to " +
            "ORDER BY wallClock ASC",
    )
    fun between(from: Long, to: Long): Flow<List<SegmentEntity>>
}

@Dao
interface InterruptDao {

    @Insert
    suspend fun insert(item: InterruptEntity): Long

    /** 同步版。`persistInterrupt` 会在编码/采集线程里被调用，那里同样不能套 runBlocking */
    @Insert
    fun insertBlocking(item: InterruptEntity): Long

    @Query("SELECT * FROM interrupt ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<InterruptEntity>>

    @Query("SELECT COUNT(*) FROM interrupt")
    fun countFlow(): Flow<Int>

    /**
     * 某一轮录音期间发生的中断。
     *
     * interrupt 表没有 sessionId（它记的是「机器层面的意外」，会话是后来才有的概念），
     * 只能按墙钟落在会话起止区间里来归属。这个近似是成立的：一轮录音期间写的中断，
     * 时间必然落在该轮区间内。代价是**并行两轮**时会各算一遍 —— 而并发录音本来就不支持。
     *
     * 为什么要在详情页显示：2026-11-15 实测有一轮被占用 153 分钟，音频完全没录到，
     * 但界面上没有任何痕迹，用户只看到「句子怎么突然跳到几个小时前」，
     * 无法判断是「当时没人说话」还是「录音坏了」。这是两种完全不同的结论。
     */
    @Query("SELECT * FROM interrupt WHERE wallClock >= :from AND wallClock <= :to ORDER BY wallClock ASC")
    fun inRange(from: Long, to: Long): Flow<List<InterruptEntity>>
}

/**
 * v1 → v2：引入会话概念，并给转写表加上说话人标注字段。
 *
 * 迁移策略是「不丢数据」：旧句子全部归入 id=1 的「迁移前记录」会话。
 * 这段 SQL 必须与 Room 从实体类推导出的建表语句严格一致，
 * 否则启动时 Room 的 schema 校验会直接抛 IllegalStateException。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `session` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`startWall` INTEGER NOT NULL, " +
                "`endWall` INTEGER NOT NULL, " +
                "`segmentCount` INTEGER NOT NULL, " +
                "`speechMs` INTEGER NOT NULL)",
        )

        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `sessionId` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `speakerName` TEXT")
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `speakerAuto` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `speakerScore` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `edited` INTEGER NOT NULL DEFAULT 0")

        // 只为「确实存在旧数据」的情况造一个历史会话，避免出现空会话
        db.execSQL(
            "INSERT INTO `session` (`id`, `startWall`, `endWall`, `segmentCount`, `speechMs`) " +
                "SELECT 1, MIN(`wallClock`), MAX(`wallClock`), COUNT(*), 0 " +
                "FROM `transcript` HAVING COUNT(*) > 0",
        )
        db.execSQL("UPDATE `transcript` SET `sessionId` = 1 WHERE `sessionId` = 0")
    }
}

/**
 * v2 → v3：采集/转写解耦 + 云端同步 + AI 校对 + 软删除。
 *
 * ## 三条必须守住的原则
 * 1. **旧数据一律置为「待同步」**（syncState=1）。它们确实没上过云端，
 *    置 0 就永远不会被上传 —— 这是最典型的静默丢数据。
 * 2. **软删除字段默认 0**（未删）。若给旧数据填了非 0，用户会看到
 *    「升级后记录全没了」。
 * 3. **新列必须给 DEFAULT**，且与实体推导的 `NOT NULL` 对上，
 *    否则 Room 启动时校验 schema 会抛 IllegalStateException。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ---------------- session：同步 + 场景
        db.execSQL("ALTER TABLE `session` ADD COLUMN `cloudSid` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `session` ADD COLUMN `syncState` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `session` ADD COLUMN `cloudRev` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `session` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `session` ADD COLUMN `scene` TEXT")
        db.execSQL("ALTER TABLE `session` ADD COLUMN `sceneNote` TEXT")

        // ---------------- transcript：音频关联 + 同步 + 软删除 + 校对
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `segmentId` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `syncState` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `proofText` TEXT")
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `proofRisk` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `proofRatio` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `proofAt` INTEGER NOT NULL DEFAULT 0")

        // ---------------- segment：新建
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `segment` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` INTEGER NOT NULL, " +
                "`startMs` INTEGER NOT NULL, " +
                "`endMs` INTEGER NOT NULL, " +
                "`wallClock` INTEGER NOT NULL, " +
                "`filePath` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, " +
                "`durMs` INTEGER NOT NULL, " +
                "`status` INTEGER NOT NULL, " +
                "`failReason` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`transcriptId` INTEGER NOT NULL, " +
                "`deletedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_segment_sessionId` ON `segment` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_segment_status` ON `segment` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_segment_wallClock` ON `segment` (`wallClock`)")

        // 列表页按 session.startWall 过滤日期、按 transcript.sessionId 聚合
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcript_sessionId` ON `transcript` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcript_wallClock` ON `transcript` (`wallClock`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_startWall` ON `session` (`startWall`)")
    }
}

/**
 * v3 → v4：记录本轮实际使用的录音设备。
 *
 * 只加一列、默认空串。老数据不补值 —— 当时的录音条件已经不可知，
 * 编一个默认值等于在事后伪造证据，而这一列将来是要用来解释
 * 「为什么这一轮标得准 / 不准」的，假值比空值更有害。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `session` ADD COLUMN `inputDevice` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v4 → v5：记录每句算出的基频。
 *
 * 加这一列的目的是**让 F0 判男女这件事可被验证**：将来把人工改过的句子当真值，
 * 就能算出「判了多少、对多少、边界区间有多宽」，而不是只能凭观感说好不好用。
 * 0 表示没算或算不出来，不写任何猜测值。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `f0Hz` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v5 → v6：记录每轮的录音**通路标识**。
 *
 * 为什么非加不可：说话人 embedding 对信道极端敏感。2026-11-11 实测同一个人
 * 同一场录音里两两相似度 0.65，而拿手机麦录的档案去比耳机麦录的句子只有 0.25
 * —— 与"另一个人"的 0.24 几乎相同。判定必须只认同通路的档案，
 * 那就得知道每条会话当时用的是哪条通路。
 *
 * 不回填历史值：老会话的通路已无从得知，留空串（= 不做回溯声纹判定）比猜一个更诚实。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `session` ADD COLUMN `micKey` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v6 → v7：让「识别准不准」变成一个可计算的数。
 *
 * ## 三个字段补的是三个无法回答的问题
 * 1. `asrRaw` —— 之前只存 `text`，用户一改，识别原文就没了，**字错率永远算不出来**。
 * 2. `autoSpeakerName` —— 之前靠 `speakerName` 兼任「机器值」和「当前值」两个角色，
 *    人工改判一覆盖，机器原本判的是谁就丢了，**说话人改判率算不出来**。
 * 3. `speakerEdited` —— 之前靠把 `speakerAuto` 清 0 来标记人工改过。
 *    代价是错判的句子全被踢出统计桶，于是算出「F0 准确率 100%」这种幸存者偏差。
 *
 * ## 回填能做什么、不能做什么
 * - `autoSpeakerName` 可以回填：`speakerAuto != 0` 的行，名字一定是机器写的
 *   （历史路径里人工改判一定会把 auto 清 0）。这批是准确的。
 * - `asrRaw` / `speakerEdited` **不回填**：历史文本早被覆盖、历史改判无痕可查，
 *   任何猜测都会让统计看起来有数据却是错的 —— 这比空着有害得多。
 *   所以改判率从 v7 当天开始积累，之前的数据只对「总改动率」有效。
 *
 * ## 为什么单独一张 transcript_edit 而不是加计数字段
 * 调参要知道的是**分布**不是总量：删「嗯」、补漏字、换同音字、整句重写，
 * 四种情况在「改动句数」里长得一模一样，但对应该动的旋钮完全不同。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `asrRaw` TEXT")
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `autoSpeakerName` TEXT")
        db.execSQL("ALTER TABLE `transcript` ADD COLUMN `speakerEdited` INTEGER NOT NULL DEFAULT 0")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `transcript_edit` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `transcriptId` INTEGER NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `field` TEXT NOT NULL,
                `beforeText` TEXT,
                `afterText` TEXT,
                `kind` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `insCount` INTEGER NOT NULL DEFAULT 0,
                `delCount` INTEGER NOT NULL DEFAULT 0,
                `subCount` INTEGER NOT NULL DEFAULT 0,
                `afterChars` INTEGER NOT NULL DEFAULT 0,
                `editedAt` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcript_edit_transcriptId` ON `transcript_edit` (`transcriptId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcript_edit_editedAt` ON `transcript_edit` (`editedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcript_edit_sessionId` ON `transcript_edit` (`sessionId`)")

        // 机器判出过名字、且没被人工改判标记过的行 —— 名字一定来自机器
        db.execSQL(
            "UPDATE `transcript` SET `autoSpeakerName` = `speakerName` " +
                "WHERE `speakerAuto` != 0 AND `speakerName` IS NOT NULL",
        )
    }
}

/**
 * v8：把「F0 猜的名字」和「声纹命中的名字」分开记。
 *
 * 两者的可靠性差两个数量级（实测 3699 句：声纹命中 2263 句改判 0 句；
 * F0 猜 757 句改判 211 句 = 27.9%），却共用同一个 `speakerAuto = 3`。
 * 结果是列表上「可以放心引用」和「两成多是错的」长得完全一样。
 *
 * 判据只用 `speakerScore`：F0 兜底分支从不赋值 score，保持 0；
 * 声纹命中必然 score > 0（match() 返回的相似度）。
 * 注意用 COALESCE 兜 NULL —— 老行可能是 NULL 而不是 0。
 *
 * 不碰 `speakerName`：名字本身可能对（F0 判「说话人A」那 487 句只错 11 句），
 * 这次只改「来源标记」，不动结论。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE `transcript` SET `speakerAuto` = 5 " +
                "WHERE `speakerAuto` = 3 AND COALESCE(`speakerScore`, 0) <= 0",
        )
    }
}

@Database(
    entities = [
        TranscriptEntity::class,
        SessionEntity::class,
        InterruptEntity::class,
        SegmentEntity::class,
        TranscriptEditEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class VoiceLogDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao
    abstract fun sessionDao(): SessionDao
    abstract fun interruptDao(): InterruptDao
    abstract fun segmentDao(): SegmentDao
    abstract fun editDao(): EditDao
}

// ---------------------------------------------------------------- 修订记账
//
// 所有「人工改动」都必须经过下面两个函数，否则质量面板就漏账。
// 它们开事务的理由：改文本、存原文、落事件三件事必须同生同死 ——
// 只落了事件没改文本（或反之）会让统计和事实长期错位，而且这种错位不会报错。

/**
 * 改一句的文字，并记录这次修订。返回是否真的发生了改动。
 *
 * ## 为什么基准是 [TranscriptEntity.asrRaw] 而不是当前 text
 * 一句话可能被改好几轮（先删口水词，后来发现还有同音字）。如果每次都拿「上一版」
 * 做基准，事件表里就只有零碎的增量，永远算不出「这句最终错了多少」。
 * 统一用识别原文做基准，每条事件都是**相对原文的累计偏差**，
 * 取该句最后一条事件就等于它的总错误量 —— 面板不用再做二次汇总。
 *
 * ## 为什么文字没变就返回 false、不落事件
 * 行内编辑在失焦时会提交一次，用户只是点开看看也会触发。把「没改」也记成事件，
 * 事件量会翻几倍且全是噪声，之后任何「改动率」都会被这些空事件稀释。
 */
suspend fun VoiceLogDatabase.applyTextEdit(
    id: Long,
    newText: String,
    source: String = TranscriptEditEntity.SRC_USER,
    at: Long = System.currentTimeMillis(),
): Boolean = withTransaction {
    val dao = transcriptDao()
    val row = dao.byId(id) ?: return@withTransaction false
    if (row.deletedAt > 0L) return@withTransaction false
    if (row.text == newText) return@withTransaction false

    // 第一次被改：把识别原文留下来。之后永远不再覆盖
    if (row.asrRaw == null) dao.setAsrRawIfNull(id, row.text)

    val base = row.asrRaw ?: row.text
    val diff = com.misaka.voicelog.core.TextDiff.compare(base, newText)
    dao.updateText(id, newText)

    /* 归一化后一字不差（只动了标点/空格）时仍然落事件，kind = same。
       这类「编辑」不该计入字错率，但「用户动过这句」本身是事实，
       漏记会让「改了 20 句，统计只有 12 句」对不上账。 */
    editDao().insert(
        TranscriptEditEntity(
            transcriptId = id,
            sessionId = row.sessionId,
            field = TranscriptEditEntity.FIELD_TEXT,
            beforeText = base,
            afterText = newText,
            kind = diff.kind,
            source = source,
            insCount = diff.ins,
            delCount = diff.del,
            subCount = diff.sub,
            afterChars = com.misaka.voicelog.core.TextDiff.normalize(newText).length,
            editedAt = at,
        ),
    )
    true
}

/**
 * 改一批句子的说话人，返回实际改变的行数。
 *
 * 逐行读旧值是为了记下「机器判的是谁 → 人改成谁」这条对照：
 * 只改不记的话，改判率和「机器错在哪儿」都无从查起。
 * 同值跳过，避免批量重铺时把没变的行也记一笔。
 *
 * **同值不等于无事可做**：F0 兜底猜出来的名字本身就写在 `speakerName` 上，
 * 用户点标签选「就是这个名字」时，传进来的 name 与旧值相同 —— 若在这里直接跳过，
 * `speakerEdited` 永远置不上，「?」标记永远消不掉，这句也就永远留在「机器猜」筛选里。
 * 所以同值分支要先判是不是「机器猜待确认」：是则转成人工确认（不记账、不算改判），
 * 否则才真的跳过。
 */
suspend fun VoiceLogDatabase.applySpeakerEdit(
    ids: List<Long>,
    name: String?,
    at: Long = System.currentTimeMillis(),
): Int = withTransaction {
    if (ids.isEmpty()) return@withTransaction 0
    val dao = transcriptDao()
    val edits = editDao()
    var changed = 0
    ids.forEach { id ->
        val row = dao.byId(id) ?: return@forEach
        if (row.deletedAt > 0L) return@forEach
        if (row.speakerName == name) {
            /* 确认：名字没变，但这行是机器猜的、还没被人工确认过。
               不写审计 —— 审计表记的是「机器判成什么、人改成什么」，
               名字没动就没有改判可言，写进去会把改判率统计污染成假的。 */
            if (!row.speakerEdited && row.speakerAuto == TranscriptEntity.AUTO_F0_GUESS) {
                dao.markSpeakerConfirmed(listOf(id))
                changed++
            }
            return@forEach
        }
        dao.setSpeakerManual(listOf(id), name)
        edits.insert(
            TranscriptEditEntity(
                transcriptId = id,
                sessionId = row.sessionId,
                field = TranscriptEditEntity.FIELD_SPEAKER,
                beforeText = row.speakerName,
                afterText = name,
                kind = TranscriptEditEntity.KIND_SPEAKER,
                source = TranscriptEditEntity.SRC_USER,
                editedAt = at,
            ),
        )
        changed++
    }
    changed
}

/**
 * 采纳一条 AI 建议并记账，返回是否真的写入了。
 *
 * 单独包一层是因为「替换文本」和「清掉建议」必须都做：
 * 只替换不清，建议会一直挂着、下次还会被当成待确认；
 * 只清不替换，用户看到的文字没变却再也找不到那条建议了。
 * 顺序上先替换再收尾 —— 中途失败时宁可留着建议（可重试），也不要留下建议已消失、
 * 文字却没改的中间态。
 */
suspend fun VoiceLogDatabase.applyProofTracked(id: Long, suggest: String, at: Long): Boolean {
    val changed = applyTextEdit(id, suggest, TranscriptEditEntity.SRC_PROOF, at)
    transcriptDao().markProofApplied(id, at)
    return changed
}

// ---------------------------------------------------------------- 拆行 / 合并行
//
// 为什么是扩展函数而不是 DAO 方法：这两件事都是「读一行 → 算 → 改一行 → 插/删一行」，
// 必须整体成功。Room 的 @Transaction 只能标注在 DAO 的抽象方法上，而 DAO 是 interface
// 放不了实现；改成抽象类要动整个 DAO 结构，不值当。用 `withTransaction` 达到同样效果。

/**
 * 在 [at]（字符下标）处把一行拆成两行，返回新行的 id。参数不合法返回 0。
 *
 * ## 时间怎么分
 * 按字符占比切分原时间段。假设一段 8.4 秒的录音被切成 12 字 + 18 字，
 * 前半行就给 `startMs ~ startMs + 8.4s × 12/30`，后半行接着到 `endMs`。
 * 这不是精确时间戳（ASR 没给逐字时间），但比「两行显示同一个时间」有用得多：
 * 至少顺序和先后位置是对的。
 *
 * ## 音频切不开
 * 一个 VAD 段就是一个 m4a 文件，文字能按字切，音频不能。拆出来的多行共享同一个
 * [TranscriptEntity.segmentId]，点播放播的是整段。界面用「↳ 续段」提示这件事。
 */
suspend fun VoiceLogDatabase.splitTranscriptLine(id: Long, at: Int): Long = withTransaction {
    val src = transcriptDao().byId(id) ?: return@withTransaction 0L
    if (src.deletedAt > 0L) return@withTransaction 0L
    val raw = src.text
    if (raw.length < 2) return@withTransaction 0L
    val cut = at.coerceIn(1, raw.length - 1)
    doSplit(src, raw.substring(0, cut).trim(), raw.substring(cut).trim())
}

/**
 * 按调用方给定的两段文字拆行。
 *
 * 与按字符下标切分的版本并存，因为**界面上的文本可能还没落盘**：用户在行内
 * 敲完字立刻按回车，数据库里还是旧文本，此时只能相信调用方传进来的 head/tail。
 * 少这一次读写往返，也避免了「先写后读」中间态被别的查询看到。
 */
suspend fun VoiceLogDatabase.splitTranscriptLineAt(id: Long, head: String, tail: String): Long =
    withTransaction {
        val src = transcriptDao().byId(id) ?: return@withTransaction 0L
        if (src.deletedAt > 0L) return@withTransaction 0L
        doSplit(src, head.trim(), tail.trim())
    }

/** 拆行的公共实现。**不自己开事务**，由调用方保证——嵌套 withTransaction 语义容易出错 */
private suspend fun VoiceLogDatabase.doSplit(src: TranscriptEntity, head: String, tail: String): Long {
    if (head.isEmpty() || tail.isEmpty()) return 0L
    val dao = transcriptDao()

    val total = (head.length + tail.length).coerceAtLeast(1)
    val span = (src.endMs - src.startMs).coerceAtLeast(0L)
    /* 必须**严格大于** startMs：列表按 startMs 排序，若 mid 等于 startMs，
       下半句就只能靠 id 兜底排在整段之后 —— 从中间某行拆开的话会跑到列表末尾。
       段时长为 0 的老数据给 1ms，保证顺序可判。 */
    val mid = (src.startMs + span * head.length / total).coerceAtLeast(src.startMs + 1)

    dao.applySplitEdit(src.id, head, mid)

    /* 记一笔「结构整理」，但 ins/del/sub 用真实差异算。
       拆分不改变文字内容，所以这三个数通常是 0 —— 字错率不会因为拆句子而虚高。
       若用户拆的同时顺手改了字，差异会如实记下来，不会被 split 这个标签掩盖。 */
    val joined = head + tail
    val diff = com.misaka.voicelog.core.TextDiff.compare(src.text, joined)
    editDao().insert(
        TranscriptEditEntity(
            transcriptId = src.id,
            sessionId = src.sessionId,
            field = TranscriptEditEntity.FIELD_TEXT,
            beforeText = src.text,
            afterText = joined,
            kind = TranscriptEditEntity.KIND_SPLIT,
            source = TranscriptEditEntity.SRC_SPLIT,
            insCount = diff.ins,
            delCount = diff.del,
            subCount = diff.sub,
            afterChars = com.misaka.voicelog.core.TextDiff.normalize(joined).length,
            editedAt = System.currentTimeMillis(),
        ),
    )

    return dao.insert(
        src.copy(
            id = 0L,
            text = tail,
            startMs = mid,
            endMs = maxOf(src.endMs, mid),
            wallClock = src.wallClock + (mid - src.startMs),
            edited = true,
            syncState = SessionEntity.SYNC_DIRTY,
            deletedAt = 0L,
            proofText = null,
            proofRisk = "",
            proofRatio = 0f,
            proofAt = 0L,
        ),
    )
}

/**
 * 把 [id] 这一行并入它前面的那一行，返回前一行 id。没有上一行返回 0。
 *
 * 合并是拆分的反向操作，必须一起提供：只给拆不给并，误拆一次就永久多一行，
 * 用户只能删了重录 —— 那比不做还糟。
 */
suspend fun VoiceLogDatabase.mergeTranscriptLine(id: Long, textOverride: String? = null): Long = withTransaction {
    val dao = transcriptDao()
    val cur = dao.byId(id) ?: return@withTransaction 0L
    if (cur.deletedAt > 0L) return@withTransaction 0L
    val prev = dao.prevInSession(cur.sessionId, cur.startMs, id) ?: return@withTransaction 0L

    /* 用界面上**当前显示**的文字，而不是库里的旧值。
       用户在行内改完还没到 1 秒就点了并入，库里还是旧文本 ——
       照旧值合并会把刚敲的那几个字直接抹掉，而行随后被软删，无从找回。 */
    val curText = (textOverride ?: cur.text).trim()

    /* 直接拼，不加空格：中文不需要，英文断在词中间加空格反而是错的。
       用户真想加空格，合并后再编辑即可。 */
    val merged = (prev.text.trimEnd() + curText.trimStart()).trim()
    if (merged.isEmpty()) return@withTransaction 0L

    /* 时间与音频归属都留在前一行。跨段合并时（ASR 把一句话拆进了两个 VAD 段），
       音频只能指向前一段 —— 后一段的音频仍在库里，只是没有文字指向它了。 */
    dao.applySplitEdit(prev.id, merged, maxOf(prev.endMs, cur.endMs))
    dao.softDelete(listOf(id), System.currentTimeMillis())

    /* 合并同样是结构整理：文字没变，只是两行变一行。
       拿「两行原文拼接」当 before 比，diff 才会是 0 —— 若拿 prev.text 单行比，
       等于把后一行的内容算成「用户凭空补进来的字」，字错率会被合并动作污染。 */
    val beforeJoined = prev.text.trimEnd() + curText.trimStart()
    val diff = com.misaka.voicelog.core.TextDiff.compare(beforeJoined, merged)
    editDao().insert(
        TranscriptEditEntity(
            transcriptId = prev.id,
            sessionId = prev.sessionId,
            field = TranscriptEditEntity.FIELD_TEXT,
            beforeText = beforeJoined,
            afterText = merged,
            kind = TranscriptEditEntity.KIND_MERGE,
            source = TranscriptEditEntity.SRC_MERGE,
            insCount = diff.ins,
            delCount = diff.del,
            subCount = diff.sub,
            afterChars = com.misaka.voicelog.core.TextDiff.normalize(merged).length,
            editedAt = System.currentTimeMillis(),
        ),
    )
    prev.id
}

/**
 * 对一轮会话里**没被手动改过**的句子重新按标点断句，返回新增的行数。
 *
 * ## 为什么需要这个补充动作
 * 自动断句发生在「写库那一刻」。已经转写完的历史会话文字还是老样子，
 * 光升级 App 看不到任何变化 —— 用户会以为功能没做。重跑 ASR 也能解决，
 * 但要多花十几分钟、还重新引入一遍识别错误，不如就地重切文本。
 *
 * ## 跳过哪些行（两个条件都不能省）
 * - `edited = 1`：用户手动改过（含手动拆/并），重切会毁掉他的工作
 * - 带 AI 建议的：建议锚定在原句上，行结构一变全部对不上号
 *
 * ## 幂等
 * 可以反复点。已经切过的句子再切一次还是会得到同样的结果（各段内部没有句末标点，
 * `split` 返回单句，直接跳过）。
 */
suspend fun VoiceLogDatabase.resplitSessionLines(sessionId: Long): Int = withTransaction {
    val dao = transcriptDao()
    val list = dao.listBySession(sessionId)
    var added = 0

    list.forEach { t ->
        if (t.edited || !t.proofText.isNullOrBlank()) return@forEach
        val parts = com.misaka.voicelog.core.SentenceSplitter.split(t.text)
        if (parts.size <= 1) return@forEach

        val totalLen = parts.sumOf { it.length }.coerceAtLeast(1)
        val span = (t.endMs - t.startMs).coerceAtLeast(0L)
        var cursor = t.startMs

        parts.forEachIndexed { i, p ->
            val s = cursor
            /* 每段至少占 1ms：startMs 相同的话列表就分不清先后，
               会退化成按 id 排 —— 而新插入的行 id 最大，必然排到最后。 */
            val e = if (i == parts.lastIndex) {
                maxOf(t.endMs, s + 1)
            } else {
                (s + span * p.length / totalLen).coerceAtLeast(s + 1)
            }
            cursor = e

            if (i == 0) {
                dao.setTextSpan(t.id, p, e)
            } else {
                dao.insert(
                    t.copy(
                        id = 0L,
                        text = p,
                        startMs = s,
                        endMs = e,
                        edited = false,
                        proofText = null,
                        proofRisk = "",
                        proofRatio = 0f,
                        proofAt = 0L,
                        syncState = SessionEntity.SYNC_DIRTY,
                    ),
                )
                added++
            }
        }
    }
    added
}
