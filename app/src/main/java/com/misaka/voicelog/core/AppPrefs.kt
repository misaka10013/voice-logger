package com.misaka.voicelog.core

import android.content.Context
import android.content.SharedPreferences

/**
 * App 级运行参数。与 [VoiceprintStore] 的自有 prefs 分开：
 * 声纹是「档案数据」，这里是「行为偏好」，混在一起会让导出/备份语义变糊。
 *
 * ## 默认值的原则
 * 每一项的默认值都选「对手机负担最小、且不会让用户丢数据」的那一侧：
 * - 实时转写默认**关**：开了模型常驻，PSS 多 300MB，一天下来电量差一倍
 * - 音频自动清理默认**开 7 天**：不清会涨到 GB 级；7 天足够回听核对
 * - 自动清理只清「已转写」的音频，没转的绝不动（删了就永久没了）
 */
object AppPrefs {

    private const val FILE = "vl_app_prefs"

    private const val KEY_REALTIME_ASR = "realtime_asr"
    private const val KEY_AUTO_DELETE_DAYS = "auto_delete_days"
    private const val KEY_CLOUD_URL = "cloud_url"
    private const val KEY_CLOUD_TOKEN = "cloud_token"
    private const val KEY_SYNC_ENABLED = "sync_enabled"
    private const val KEY_SYNC_LAST_AT = "sync_last_at"
    private const val KEY_SYNC_LAST_ERROR = "sync_last_error"
    private const val KEY_DEVICE_ID = "device_id"

    // 转写/采集相关（2026-11-09 起可调）
    private const val KEY_ASR_LANG = "asr_lang"
    private const val KEY_ASR_ITN = "asr_itn"
    private const val KEY_VAD_THRESHOLD = "vad_threshold"
    private const val KEY_VAD_MIN_SILENCE = "vad_min_silence"
    private const val KEY_AAC_BITRATE = "aac_bitrate"

    // 2026-11-10：断句的另两个参数从硬编码改成可调
    private const val KEY_VAD_MIN_SPEECH = "vad_min_speech"
    private const val KEY_VAD_MAX_SPEECH = "vad_max_speech"

    // 2026-11-10：听标 A/B 固定标签
    private const val KEY_AB_A = "ab_a"
    private const val KEY_AB_B = "ab_b"

    // 2026-11-10：基频（F0）判男女
    private const val KEY_F0_ENABLED = "f0_enabled"
    private const val KEY_F0_MALE_MAX = "f0_male_max"
    private const val KEY_F0_FEMALE_MIN = "f0_female_min"
    private const val KEY_F0_MALE_LABEL = "f0_male_label"
    private const val KEY_F0_FEMALE_LABEL = "f0_female_label"

    // 2026-11-10：录音输入设备偏好
    private const val KEY_INPUT_DEVICE_ID = "input_device_id"
    private const val KEY_INPUT_DEVICE_NAME = "input_device_name"

    /** 2026-11-12：噪音旧标签一次性统一成 [NOISE_LABEL] 是否已执行（跑一次就够） */
    private const val KEY_NOISE_RENAME_DONE = "noise_rename_done_v1"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 实时转写（边说边出字）。
     *
     * 关 = 只做 VAD + 编码落盘，模型不加载；点「转写」才批量处理。
     * 开 = v2 的老行为，适合当场要看字、且不介意耗电。
     */
    fun realtimeAsr(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_REALTIME_ASR, false)

    fun setRealtimeAsr(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_REALTIME_ASR, v).apply()
    }

    /**
     * 已转写音频的自动清理天数。0 = 永不自动删。
     * 只作用于 status=DONE 的段；PENDING / FAILED 一律不碰。
     */
    fun autoDeleteDays(ctx: Context): Int =
        prefs(ctx).getInt(KEY_AUTO_DELETE_DAYS, DEFAULT_AUTO_DELETE_DAYS)

    fun setAutoDeleteDays(ctx: Context, days: Int) {
        prefs(ctx).edit().putInt(KEY_AUTO_DELETE_DAYS, days.coerceIn(0, 365)).apply()
    }

    // ------------------------------------------------------------ 转写参数

    /**
     * 转写语种。取值即 SenseVoice 的 language 参数。
     *
     * 默认 `zh` 而不是 `auto`：模型自带的语种识别在短句上会跳（同一个人的两句话
     * 一句判中文一句判英文），而用户录的绝大多数是中文对话。
     * 真录外语时到设置里改，改完点会话里的「重新转录」即可生效。
     */
    fun asrLang(ctx: Context): String = prefs(ctx).getString(KEY_ASR_LANG, ASR_LANG_ZH) ?: ASR_LANG_ZH

    fun setAsrLang(ctx: Context, lang: String) {
        prefs(ctx).edit().putString(KEY_ASR_LANG, lang).apply()
    }

    /**
     * 是否做逆文本规整（ITN）：「二零二六年三月」→「2026年3月」。
     *
     * 默认开。关掉只在这类场景有意义：要让文字保持「说的原样」用于逐字核对。
     */
    fun asrItn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ASR_ITN, true)

    fun setAsrItn(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ASR_ITN, v).apply()
    }

    /** VAD 人声判定阈值。越低越灵敏，也越容易把噪声当人声 */
    fun vadThreshold(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_VAD_THRESHOLD, VadDefaults.THRESHOLD)

    fun setVadThreshold(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(KEY_VAD_THRESHOLD, v.coerceIn(0.2f, 0.9f)).apply()
    }

    /** 停顿多久算断句（秒）。越长越不容易把一句话切成碎片 */
    fun vadMinSilence(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_VAD_MIN_SILENCE, VadDefaults.MIN_SILENCE_SEC)

    fun setVadMinSilence(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(KEY_VAD_MIN_SILENCE, v.coerceIn(0.3f, 2.0f)).apply()
    }

    /** AAC 码率（bps）。体积与转写准确率的直接权衡点 */
    fun aacBitRate(ctx: Context): Int =
        prefs(ctx).getInt(KEY_AAC_BITRATE, 48_000).let { if (it in 16_000..128_000) it else 48_000 }

    fun setAacBitRate(ctx: Context, bps: Int) {
        prefs(ctx).edit().putInt(KEY_AAC_BITRATE, bps.coerceIn(16_000, 128_000)).apply()
    }

    /**
     * 最短语音时长（秒）。短于它的声音不当成一句话。
     *
     * 从 0.25s 硬编码放出来，是因为它直接决定「咔哒声、碗筷碰撞」会不会被切成一段
     * 送进 ASR 变成一行莫名其妙的字。调大能滤掉噪声，代价是很短的「嗯」也会丢。
     */
    fun vadMinSpeech(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_VAD_MIN_SPEECH, VadDefaults.MIN_SPEECH_SEC)

    fun setVadMinSpeech(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(KEY_VAD_MIN_SPEECH, v.coerceIn(0.15f, 1.5f)).apply()
    }

    /**
     * 最长单段时长（秒）。超过就强制切断。
     *
     * 一个人连着说三分钟时，如果不切会得到一个巨长的段：ASR 变慢、内存涨，
     * 而且一期只能标同一个人。调小能让长独白拆得更细、标人更准，代价是上下文变少。
     */
    fun vadMaxSpeech(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_VAD_MAX_SPEECH, VadDefaults.MAX_SPEECH_SEC)

    fun setVadMaxSpeech(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(KEY_VAD_MAX_SPEECH, v.coerceIn(5f, 60f)).apply()
    }

    // ------------------------------------------------------------ 听标 A/B 标签

    /**
     * 听标底部两个按钮上固定显示的名字。
     *
     * 之前是每轮进听标都重置成「我 / 对方」，用户每轮都要手改一次。
     * 固定下来之后，进去就能直接点。
     */
    fun abA(ctx: Context): String =
        prefs(ctx).getString(KEY_AB_A, DEFAULT_AB_A)?.takeIf { it.isNotBlank() } ?: DEFAULT_AB_A

    fun setAbA(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_AB_A, v.trim().take(12)).apply()
    }

    fun abB(ctx: Context): String =
        prefs(ctx).getString(KEY_AB_B, DEFAULT_AB_B)?.takeIf { it.isNotBlank() } ?: DEFAULT_AB_B

    fun setAbB(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_AB_B, v.trim().take(12)).apply()
    }

    /** 听标里给「视频噪音」这个标签用的固定名字。不是人名，所以单独存 */
    const val NOISE_LABEL = "视频噪音"

    /**
     * 噪音标签的历史别名。
     *
     * 必须兼容到底：写进库的是**当时的字面值**，常量改了不会回头改数据。
     * 实测 2026-11-12 库里还有 118 句标成「电视外放」——只认当前名字的话，
     * 这批句子会掉回「具名说话人」那一档配色，和真人共用绿色，等于优化白做。
     *
     * 非 private：启动时的一次性改名（[VoiceLogApp]）要遍历它把旧名统一成 [NOISE_LABEL]，
     * 别名清单只在这里维护一份，改名逻辑引用它，避免两处清单漂移。
     */
    val NOISE_ALIASES = setOf("视频噪音", "电视外放", "外放")

    /** 这个标签是不是「不是人在说话」。配色和筛选都走这里，别再各自比字符串 */
    fun isNoise(name: String?): Boolean = name != null && name in NOISE_ALIASES

    // ------------------------------------------------------------ 基频判男女

    /**
     * 是否用基频（F0）兜底判人。
     *
     * 落谁的名字不由这里决定：判出性别后去 [PersonStore] 查这个性别下的人，
     * **恰好一个人**才落名字，0 人或 ≥2 人都留「未判」——
     * F0 只能证明"是哪个性别"，证不出"是哪个具体的人"。
     *
     * ⚠️ 这是**概率判定**，不是事实：11-09 实测 F0 双峰分离度 3.02，
     * 但 160~200Hz 之间的边界区间有 15.6% 判不准，这类一律留「未判」不猜。
     * 电视里的女播音也会被判成该性别那个人 —— 那几句得人工改。
     */
    fun f0Enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_F0_ENABLED, true)

    fun setF0Enabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_F0_ENABLED, v).apply()
    }

    /** 低于此值判为男声。默认 160Hz（实测男声 100–160Hz） */
    fun f0MaleMax(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_F0_MALE_MAX, F0Defaults.MALE_MAX_HZ)

    fun setF0MaleMax(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(KEY_F0_MALE_MAX, v.coerceIn(80f, 220f)).apply()
    }

    /** 高于此值判为女声。默认 200Hz（实测女声 200–280Hz） */
    fun f0FemaleMin(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_F0_FEMALE_MIN, F0Defaults.FEMALE_MIN_HZ)

    fun setF0FemaleMin(ctx: Context, v: Float) {
        prefs(ctx).edit().putFloat(KEY_F0_FEMALE_MIN, v.coerceIn(160f, 320f)).apply()
    }

    /* ⚠️ 2026-11-21 删掉了 f0MaleLabel / f0FemaleLabel 两组读写函数。
       它们从「基频判男女」改走 PersonStore 之后就没用了，但一直留在文件里 ——
       两套配置源并存，改了一个另一个没变，正是这次「说话人A被劈成说话人A + 我」的成因之一。
       旧 prefs 里的 KEY_F0_MALE_LABEL / KEY_F0_FEMALE_LABEL 不再读取，留着不影响启动。 */

    // ------------------------------------------------------------ 录音输入设备

    /**
     * 偏好的输入设备 id。0 = 跟随系统默认。
     *
     * ⚠️ `AudioRecord.setPreferredDevice()` 只是**请求**，系统可以忽略
     * （尤其是蓝牙和部分 USB 声卡）。所以每次开录后必须用 `getRoutedDevice()`
     * 复核实际走了哪一路，不符要告诉用户，不能静默降级 —— 否则用户以为在用外接麦，
     * 实际在用内置麦，录出来的东西信噪比差 20dB。
     */
    fun inputDeviceId(ctx: Context): Int = prefs(ctx).getInt(KEY_INPUT_DEVICE_ID, 0)

    fun inputDeviceName(ctx: Context): String =
        prefs(ctx).getString(KEY_INPUT_DEVICE_NAME, "自动（系统默认）") ?: "自动（系统默认）"

    fun setInputDevice(ctx: Context, id: Int, name: String) {
        prefs(ctx).edit()
            .putInt(KEY_INPUT_DEVICE_ID, id)
            .putString(KEY_INPUT_DEVICE_NAME, name)
            .apply()
    }

    fun noiseRenameDone(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_NOISE_RENAME_DONE, false)

    fun markNoiseRenameDone(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_NOISE_RENAME_DONE, true).apply()
    }

    const val ASR_LANG_AUTO = "auto"
    const val ASR_LANG_ZH = "zh"
    const val ASR_LANG_EN = "en"
    const val ASR_LANG_JA = "ja"
    const val ASR_LANG_KO = "ko"
    const val ASR_LANG_YUE = "yue"

    /** 给界面用的语种表：值 → 显示名。顺序即顺序，中文在最前 */
    val ASR_LANG_LABELS: List<Pair<String, String>> = listOf(
        ASR_LANG_ZH to "中文",
        ASR_LANG_AUTO to "自动识别",
        ASR_LANG_EN to "英语",
        ASR_LANG_JA to "日语",
        ASR_LANG_KO to "韩语",
        ASR_LANG_YUE to "粤语",
    )

    fun asrLangLabel(value: String): String =
        ASR_LANG_LABELS.firstOrNull { it.first == value }?.second ?: value

    /** 与 VadSegmenter 的默认值对齐（那里是音频包内部常量，避免反向依赖） */
    private object VadDefaults {
        const val THRESHOLD = 0.5f
        const val MIN_SILENCE_SEC = 0.8f
        const val MIN_SPEECH_SEC = 0.25f
        const val MAX_SPEECH_SEC = 30f
    }

    /** 与 11-09 离线实验的双峰位置对齐：男 100–160Hz / 女 200–280Hz */
    private object F0Defaults {
        const val MALE_MAX_HZ = 160f
        const val FEMALE_MIN_HZ = 200f
    }

    /* 说话人 A / B 的默认显示名。用户可以在设置里改成任何称呼。
       开源版保持中性 —— 这两个值会直接印在界面和导出文件上。 */
    const val DEFAULT_AB_A = "我"
    const val DEFAULT_AB_B = "对方"

    // ------------------------------------------------------------------ 云端

    /* 后端地址由使用者自建（一个 CloudBase 云函数，接口见 README 第八节）。
       这里**不预置任何默认地址**：预置等于把环境 ID 和账号 ID 一起开源。
       用户在设置页手填，输入框 placeholder 给了格式示例。 */
    fun cloudUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_CLOUD_URL, "") ?: ""

    fun setCloudUrl(ctx: Context, url: String) {
        prefs(ctx).edit().putString(KEY_CLOUD_URL, url.trim().trimEnd('/')).apply()
    }

    fun cloudToken(ctx: Context): String =
        prefs(ctx).getString(KEY_CLOUD_TOKEN, "") ?: ""

    fun setCloudToken(ctx: Context, token: String) {
        prefs(ctx).edit().putString(KEY_CLOUD_TOKEN, token.trim()).apply()
    }

    fun syncEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SYNC_ENABLED, false)

    fun setSyncEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SYNC_ENABLED, v).apply()
    }

    /**
     * 上次同步成功的时刻（0 = 从未成功）。
     * 存下来是为了让用户能回答「我最后一次同步是什么时候」——
     * 没有这个数字，同步失败时他无法判断是「今天没同步」还是「同步功能坏了」。
     */
    fun syncLastAt(ctx: Context): Long =
        prefs(ctx).getLong(KEY_SYNC_LAST_AT, 0L)

    fun setSyncLastAt(ctx: Context, v: Long) {
        prefs(ctx).edit().putLong(KEY_SYNC_LAST_AT, v).apply()
    }

    /** 上次同步的失败原因。成功后要清空，否则界面会一直挂着一条过期的红字 */
    fun syncLastError(ctx: Context): String =
        prefs(ctx).getString(KEY_SYNC_LAST_ERROR, "") ?: ""

    fun setSyncLastError(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_SYNC_LAST_ERROR, v.take(300)).apply()
    }

    /**
     * 本机的稳定标识，用于服务端排查「谁覆盖了谁」。
     * 每次装 App 随机生成一次 —— 不用 ANDROID_ID 之类的硬件标识，那是隐私信息，
     * 而这里只需要「区分设备」这一个功能，随机串完全够。
     */
    fun deviceId(ctx: Context): String {
        val cur = prefs(ctx).getString(KEY_DEVICE_ID, "") ?: ""
        if (cur.isNotEmpty()) return cur
        val gen = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        prefs(ctx).edit().putString(KEY_DEVICE_ID, gen).apply()
        return gen
    }

    const val DEFAULT_AUTO_DELETE_DAYS = 7
}
