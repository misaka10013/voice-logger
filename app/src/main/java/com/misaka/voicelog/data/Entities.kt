package com.misaka.voicelog.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次监听的会话。
 *
 * 引入它的理由：录音笔的天然组织单位是「一次录音」，而用户回顾时问的是
 * 「我昨天上午开会那轮说了什么」。没有会话边界，几百条句子只能在一条时间线上
 * 滑到底，无法定位。会话也是后续同步到云端时的天然批次单元。
 */
@Entity(
    tableName = "session",
    // 记录页按 startWall 做日期区间筛选，没有索引时几百轮也要全表扫。
    // ⚠️ 索引必须同时在 MIGRATION_2_3 里建，否则 Room 启动的 schema 校验会失败。
    indices = [Index("startWall")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 本轮开始（墙钟） */
    val startWall: Long,
    /** 本轮结束（墙钟）。0 表示仍在进行或异常退出未收尾 */
    val endWall: Long = 0L,
    /** 累计写入的句子数，冗余存储，列表页不必再 count */
    val segmentCount: Int = 0,
    /** 累计语音时长（毫秒），不等同于会话时长，只统计有人声的部分 */
    val speechMs: Long = 0L,

    // ------------------------------------------------------------ v3：同步与场景

    /**
     * 云端文档 id。首次上传成功后回填，之后作为幂等键。
     *
     * 为什么不在本地生成 UUID：云端按 sid 去重，本地 id 是自增的、重装归零，
     * 换机或重装后若沿用本地 id 会覆盖别人的数据。首传时由服务端发一个，
     * 本地存下来，最省心。（服务端规则见 `vl.js` 的 sync：无 sid 则新建）
     */
    val cloudSid: String = "",

    /**
     * 同步状态：0=已同步 1=有改动待上传 2=已删除待同步。
     *
     * 历史数据迁移过来时一律置 1：它们确实还没上过云端，
     * 若置 0 就永远不会被上传（这是最容易埋下的静默丢失）。
     */
    val syncState: Int = SYNC_DIRTY,

    /** 云端版本号，每次成功同步 +1。用于排查「谁覆盖了谁」 */
    val cloudRev: Int = 0,

    /** 软删除时间戳，0=未删除。列表查询一律过滤 deletedAt = 0 */
    val deletedAt: Long = 0L,

    /**
     * 场景名，来自「时间记录」App 的事项（吃饭/睡觉/上班…）。
     *
     * ⚠️ 它描述的是「你当时在干什么」，不是「你们在聊什么」——所以叫场景不叫话题。
     * 只有使用时间记录 App 的人才有值，为空是正常状态，不是错误。
     */
    val scene: String? = null,

    /** 场景备注（时间记录里的事项备注名），可为空 */
    val sceneNote: String? = null,

    /**
     * 本轮录音实际用到的输入设备，形如 `USB 麦克风 · 2ch` / `内置麦克风 · 1ch`。
     *
     * 为什么要落库：接了外接麦之后，"这轮到底走的是哪个麦" 决定了说话人判定能不能
     * 用能量差（近场麦 vs 手机内置，信噪比差 20dB 以上）。只看当轮状态不够 ——
     * 事后回看「那天为什么标得准/不准」，必须知道当时的录音条件。
     *
     * 空串 = 老数据或读取失败（`getRoutedDevice()` 在部分机型上返回 null）。
     *
     * 显式声明 `defaultValue` 与 [MIGRATION_3_4] 里的 SQL 保持一致：
     * Room 会拿 entity 定义和实际表结构对账，两边 default 不一致会在启动时抛
     * `IllegalStateException`，表现为「升级后打不开 App」。
     */
    @ColumnInfo(defaultValue = "''")
    val inputDevice: String = "",

    /**
     * 本轮的**通路标识**，形如 `builtin` / `bt|OpenRun by Shokz`。
     *
     * 与 [inputDevice] 的区别：那个是给人看的描述，随时可以改文案；
     * 这个是被程序当键来比的（见 [com.misaka.voicelog.audio.MicRoute.keyOf]），
     * 声纹判定只认同通路的档案 —— 跨通路的相似度与陌生人无异（2026-11-11 实测）。
     *
     * 空串 = 老数据或该轮未能确定通路，此时不做声纹回溯判定。
     */
    @ColumnInfo(defaultValue = "''")
    val micKey: String = "",
) {
    companion object {
        const val SYNC_CLEAN = 0
        const val SYNC_DIRTY = 1
        const val SYNC_DELETED = 2
    }
}

/**
 * 一条转写结果。
 *
 * 音频本身默认不落盘（验证阶段只关心文字），但保留 [startMs]/[endMs] 相对时间，
 * 方便回头核对"这句话是第几分钟说的"。v3 起音频段可落盘，通过 [segmentId] 关联。
 */
@Entity(
    tableName = "transcript",
    // sessionId：详情页与列表聚合的主查询条件
    // wallClock：按天导出、按天同步的主查询条件
    indices = [Index("sessionId"), Index("wallClock")],
)
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 所属会话。0 表示迁移前的历史数据 */
    val sessionId: Long = 0L,
    /** 语音段起始，相对本次采集开始（毫秒） */
    val startMs: Long,
    /** 语音段结束，相对本次采集开始（毫秒） */
    val endMs: Long,
    /** 识别出的文字 */
    val text: String,
    /** SenseVoice 返回的语种标记，如 zh / en */
    val lang: String,
    /** 绝对墙钟时间，用于按天归档与显示 HH:mm:ss */
    val wallClock: Long,

    /**
     * 说话人标签。null = 未标注，界面显示"未标"。
     * 自动判断只会写入「我的标签名」或「他人」，用户可任意改写。
     */
    val speakerName: String? = null,

    // ------------------------------------------------------------ v7：质量度量

    /**
     * 自动判定结果：0=未判断（无模板或句子太短） 1=判定为我 2=判定为他人。
     *
     * 与 [speakerName] 分开存，是为了让界面能区分"机器猜的"和"人改的"：
     * 自动判定错的句子，用户改过之后可以保留 auto 值，用于事后评估声纹准确率。
     *
     * ⚠️ 2026-11-16 起这是**只读快照**，人工改判不再清零它（改用 [speakerEdited] 打标）。
     * 之前清成 0 的做法把证据一起销毁了，导致统计出来的「F0 准确率 100%」是假的 ——
     * 错的全掉出统计桶，剩下的当然全对。
     */
    val speakerAuto: Int = 0,

    /**
     * 机器判出的**名字**快照，机器没判出名字时为 null。
     *
     * 为什么不能靠 [speakerName]：人工改判会覆盖它。而「机器判的是说话人A、你改成了说话人B」
     * 这条信息是算改判率的唯一真值来源，覆盖掉就永远算不出来了。
     * 只增不改：写库时定一次，之后无论怎么改都不动它。
     */
    val autoSpeakerName: String? = null,

    /**
     * 说话人是否被人工改过。
     *
     * 有了它，界面才能在「名字是机器给的」和「名字是人给的」之间分开配色/导出，
     * 而不用像以前那样靠清空 [speakerAuto] 来暗示 —— 那是拿销毁证据换一个布尔位。
     */
    @ColumnInfo(defaultValue = "0")
    val speakerEdited: Boolean = false,

    /** 与我的声纹模板的最高余弦相似度，0~1。用于筛低置信度句子 */
    val speakerScore: Float = 0f,

    /**
     * 基频（Hz），0 = 没算或算不出来（句子太短、静音、无周期性）。
     *
     * 单独存而不是塞进 [speakerScore]：声纹是余弦相似度 0~1，基频是物理频率，
     * 混在一个字段里，事后就没法回答「到底靠哪个判据标错的」。
     * 存下来才能拿人工标注当真值去算 F0 判男女的真实准确率。
     */
    @ColumnInfo(defaultValue = "0")
    val f0Hz: Int = 0,

    /** 文字是否被用户手动改过。改过的句子在导出时优先采用 */
    val edited: Boolean = false,

    /**
     * ASR 原始输出，含标点。null = 从未被人类编辑过。
     *
     * 2026-11-16 之前只存 [text]，用户一改，识别原文就永远消失了 ——
     * 于是「字错率」这个最该看的指标根本算不出来，只能拿「改动句数」当替身，
     * 而删一个「嗯」和被整句听错在那个口径下完全等价。
     *
     * 写入时机是**第一次被编辑时**（不是写库时），理由：绝大多数句子永远不会被改，
     * 提前存一遍等于把整库文字翻一倍，换不到任何信息。
     */
    val asrRaw: String? = null,

    // ------------------------------------------------------------ v3：音频关联

    /**
     * 对应的音频段 id，0=无音频（音频已删，或来自旧版本、来自导入文件）。
     *
     * 「音频可缺」是刻意设计：音频只是素材，文字才是资产。
     * 一旦把文字的存在绑定到音频上，用户清理音频就会连带丢字。
     */
    val segmentId: Long = 0L,

    // ------------------------------------------------------------ v3：同步

    /** 同 [SessionEntity.syncState]：0=已同步 1=待上传 2=待删除 */
    val syncState: Int = SessionEntity.SYNC_DIRTY,

    /** 软删除，0=未删。删除永远先软删，7 天内可恢复 */
    val deletedAt: Long = 0L,

    // ------------------------------------------------------------ v3：AI 校对

    /**
     * AI 建议的文本。null=无建议。
     *
     * ⚠️ 它是**建议**不是事实：模型看不到音频，只能保证「读起来通顺」，
     * 不能保证「说得一样」。所以永远单独存，等用户点「应用」才写进 [text]。
     */
    val proofText: String? = null,

    /** 建议的风险等级：low / mid / high。high 默认不批量应用（很可能是脑补） */
    val proofRisk: String = "",

    /** 改动幅度（编辑距离 / 原句长度），0~1 */
    val proofRatio: Float = 0f,

    /** 最近一次校对时间，0=从未校对 */
    val proofAt: Long = 0L,
) {
    /**
     * 「机器判的标签还有效吗」的唯一判据。
     *
     * [speakerAuto] 自 v7 起是只读快照，人工改判不会动它。所以任何想表达
     * 「这是机器给的结论」的地方都必须过这一层 —— 直接读 [speakerAuto]
     * 会在人已经改过名字之后仍按机器的颜色/口径处理，看起来只是配色不对，
     * 实际是**用机器结论覆盖人的判断**。
     */
    val effectiveAuto: Int get() = if (speakerEdited) AUTO_NONE else speakerAuto

    companion object {
        const val AUTO_NONE = 0

        /** 命中「自己」那份声纹档案 */
        const val AUTO_ME = 1

        /** 旧语义：没有命中任何档案就标「他人」。**已废弃，不再写入**。
         *  保留常量只为兼容历史数据（库里已有 196 句这个值）。 */
        const val AUTO_OTHER = 2

        /** 命中某个具名档案（老婆、同事…）。引入多人声纹后新增，不改变已有取值 */
        const val AUTO_NAMED = 3

        /** 跑过声纹但没把握：**故意不给名字**（speakerName = null）。
         *
         *  与 AUTO_NONE 的区别是「试着判过但没敢下结论」还是「压根没判」，
         *  用于事后评估召回率；对界面都表现为「未判」。
         *
         *  为什么不沿用 AUTO_OTHER：远场下声纹相似度普遍掉到 0.4~0.5，
         *  当它标「他人」等于把「不知道」写成「确定不是说话人A」。这份转写会进
         *  长期档案被反复引用，错误标签会被当成事实 —— 宁可不知道，也不能编。 */
        const val AUTO_UNKNOWN = 4

        /**
         * 声纹**根本没跑**，名字是 F0（基频）猜出来的。v8 起单独记。
         *
         * ## 为什么必须和 AUTO_NAMED 分开
         * 改动前两件事都写 AUTO_NAMED(3)，事后只能靠 `speakerScore == 0` 反推 ——
         * 而「分数为 0」既可能是没跑，也可能是跑了恰好算出 0，语义模糊。
         * 2026-11-19 实测 3699 句：
         *   · 声纹命中（score > 0）2263 句，人工改判 **27 句 = 1.2%**；
         *   · F0 猜（score = 0）757 句，人工改判 **193 句 = 25.5%**。
         * 混在一起时，「已经标注好、可以放心引用」的句子和「四分之一是错的」句子
         * 在列表上长得一模一样，用户无法判断该信哪一行。
         *
         * ## 为什么不是直接不给名字（写 AUTO_UNKNOWN）
         * F0 的错误极不对称：猜「男声侧」327 句只错 3.7%（96.3% 是对的），
         * 猜「女声侧」425 句错 42.6% —— 因为女声有两个候选（说话人B本人 +
         * 说话人A提高音调/笑/唱歌时的 220–370Hz），F0 分不出来。
         * 砍掉名字等于把男声侧那 96.3% 的正确结果也一起丢掉，反而多出 327 句要手标。
         * 所以**保留名字、标记来源**，让人自己决定要不要复核 ——
         * 信息多一点总比替人做决定好。
         */
        const val AUTO_F0_GUESS = 5
    }
}

/**
 * 一段落盘的音频（人工声片段），v3 新增。
 *
 * ## 为什么要有它
 * v2 是「边说边转」，音频留在内存里、转完就扔。代价是 ASR 模型必须常驻
 * （PSS 里 309MB），耗电高。v3 把采集和转写拆开：监听时只跑 VAD + 编码落盘，
 * 用户点「转写」时才加载模型批量处理 —— 模型可以不常驻，手机能撑一整天。
 *
 * ## 一段音频 = 一句话
 * VAD 切出来的每一段单独存一个 m4a。不做「整轮一个长文件」的原因是：
 * 重试、删除、定位都要精确到句，切好的小文件最省事（代价是文件数多，
 * 但按 25% 有效语音算，一天几百个小文件，ext4 完全扛得住）。
 */
@Entity(
    tableName = "segment",
    // status：转写队列的取件条件（status=0 按 id 排序）
    // wallClock：素材页按天分组
    indices = [Index("sessionId"), Index("status"), Index("wallClock")],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 所属会话 */
    val sessionId: Long,
    /** 相对会话开始的起点/终点（毫秒），与 transcript 同一坐标系 */
    val startMs: Long,
    val endMs: Long,
    /** 绝对墙钟时间，列表按天归档用 */
    val wallClock: Long,

    /**
     * 音频文件绝对路径。指向 App 内部 `files/audio/<sessionId>/<id>.m4a`。
     *
     * 用内部存储不用外部：外部存储（/sdcard/Android/data）在部分机型上
     * 被 FUSE 视图延迟同步，`File.exists()` 会撒谎（v2 已踩过这个坑）。
     */
    val filePath: String,

    /** 文件字节数。用于「占用多少空间」的统计，避免每次去 stat 磁盘 */
    val sizeBytes: Long = 0L,

    /** 音频时长（毫秒），用于估算码率与转写进度 */
    val durMs: Long = 0L,

    /**
     * 状态：
     * - [ST_PENDING] 已落盘，等转写
     * - [ST_RUNNING] 正在转写（进程被杀会残留，启动时需重置为 PENDING）
     * - [ST_DONE] 已转写完，可安全删除
     * - [ST_FAILED] 转写失败，保留音频供重试
     */
    val status: Int = ST_PENDING,

    /** 失败原因，仅 status=FAILED 时有值 */
    val failReason: String = "",

    /**
     * 来源：
     * - [SRC_MIC] 手机麦克风实时监听
     * - [SRC_IMPORT] 用户从手机相册/文件里导入的音频
     * - [SRC_DEVICE] 未来随身硬件同步过来的音频
     *
     * 现在就写死三个取值，是为了将来接硬件时不必再迁移一次。
     */
    val source: String = SRC_MIC,

    /** 转写完成后回填的句子 id，0=还没转 */
    val transcriptId: Long = 0L,

    /** 软删除，0=未删 */
    val deletedAt: Long = 0L,
) {
    companion object {
        const val ST_PENDING = 0
        const val ST_RUNNING = 1
        const val ST_DONE = 2
        const val ST_FAILED = 3

        const val SRC_MIC = "mic"
        const val SRC_IMPORT = "import"
        const val SRC_DEVICE = "device"
    }
}

/**
 * 一轮会话里音频段的转写状态分布。
 *
 * 存在的理由：界面上「转写好了没」这件事之前只有一个句子数，
 * 而"有 3 句成功、1 段失败"和"全部完成"看起来一模一样 ——
 * 用户明明少听到一段，却没有任何地方告诉他少在哪。
 */
data class SegmentTally(
    val pending: Int = 0,
    val failed: Int = 0,
    val done: Int = 0,
    /** 失败段的第一个原因，让用户知道"为什么少了一句"，而不是只能反复点重试 */
    val failReason: String = "",
) {
    /** 还没处理完的段数（待转 + 失败），0 表示这一轮的音频已经全部消化 */
    val unfinished: Int get() = pending + failed
}

/**
 * 中断日志。
 *
 * 存在的意义：事后能回答"这段为什么没录到"。
 * 没有它，一旦漏录就完全无法归因。
 */
@Entity(tableName = "interrupt")
data class InterruptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 发生时间（墙钟） */
    val wallClock: Long,
    /** 类型：call / other_recorder / silent / error / manual */
    val kind: String,
    /** 补充说明 */
    val detail: String,
)

/**
 * 一次人工修订的事件记录。
 *
 * ## 为什么要单独一张表，而不是在 transcript 上加几个计数字段
 * 调参要知道的是**分布**，不是总量：
 * - 改的全是「嗯/啊」→ 该收敛填充词，不是模型问题
 * - 改的全是同音字 → 模型上限，调阈值无用，只能换模型或加语种
 * - 改的全是漏字 → VAD 切太早 / 增益太低
 * - 改的全是整句 → 串音、噪音被当人声
 *
 * 四个结论对应四个完全不同的动作，而它们在「改动句数」这一个数字里长得一模一样。
 * 多存一行事件（几十字节），换的是「下一步该动哪个旋钮」有据可查。
 *
 * ## 为什么不记「一次编辑一行」以外的粒度
 * 一次编辑的 before/after 已经完整，再拆成字符级行会把表撑大 10 倍，
 * 而字符级全部信息都能从 before/after 重算出来 —— 存派生数据迟早会漂移。
 */
@Entity(
    tableName = "transcript_edit",
    indices = [Index("transcriptId"), Index("editedAt"), Index("sessionId")],
)
data class TranscriptEditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val transcriptId: Long,
    /** 冗余存会话 id：按会话/按麦分组的统计不用再 join transcript */
    val sessionId: Long,
    /** 改的是哪个字段：text / speaker */
    val field: String,
    /** 原值。speaker 未标注时为 null —— 与空串区分开，「从无到有」和「从空到有」不同 */
    val beforeText: String?,
    val afterText: String?,
    /** 改动性质，取值见 [com.misaka.voicelog.core.TextDiff.Kind]；speaker 改动统一为 speaker_fix */
    val kind: String,
    /** 触发来源：user（手改）/ proof（应用AI建议）/ split / merge */
    val source: String,
    /** 字符级操作数，仅 text 有效 */
    val insCount: Int = 0,
    val delCount: Int = 0,
    val subCount: Int = 0,
    /**
     * 修改后文字的归一化字符数（去标点空白），字错率的分母。
     *
     * 不在查询时用 `LENGTH(after)` 现算：那个值含标点，会让分母虚胖、
     * 把字错率系统性压低一成以上。分母口径必须自始至终一致，否则趋势不可比。
     */
    val afterChars: Int = 0,
    val editedAt: Long,
) {
    companion object {
        const val FIELD_TEXT = "text"
        const val FIELD_SPEAKER = "speaker"

        const val SRC_USER = "user"
        const val SRC_PROOF = "proof"
        const val SRC_SPLIT = "split"
        const val SRC_MERGE = "merge"

        /** 说话人改判的 kind 固定值，不进 [com.misaka.voicelog.core.TextDiff.Kind] 是有意的：
         *  那个对象描述的是**文字**差异，混进说话人会误导后来人以为它也算字错率 */
        const val KIND_SPEAKER = "speaker_fix"

        /** 拆行 / 合并行：文字总量没变，只是行结构变了。
         *  单独成 kind 是为了让「结构整理」不计入字错率 —— 否则用户拆一次句子，
         *  那句的字错率会凭空涨一截，把模型的问题和排版的问题混成一锅。 */
        const val KIND_SPLIT = "split"
        const val KIND_MERGE = "merge"
    }
}
