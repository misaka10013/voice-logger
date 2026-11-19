package com.misaka.voicelog.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.misaka.voicelog.audio.MicRoute
import com.misaka.voicelog.core.AppPrefs
import com.misaka.voicelog.core.ModelPaths
import com.misaka.voicelog.core.PersonTags
import com.misaka.voicelog.core.ProofreadClient
import com.misaka.voicelog.core.SyncManager
import com.misaka.voicelog.core.TtsProbe
import com.misaka.voicelog.core.VoiceprintStore
import com.misaka.voicelog.service.VoiceLogDatabaseHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页。
 *
 * 声纹部分的组织原则：一个说话人一行，行内直接给出「样本数 + 一致性」两个判断依据，
 * 重录和删除都在行上。不做「点进详情再操作」的两级结构 —— 声纹最多 8 个人，
 * 一屏能看全，多一层跳转只会增加操作次数。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onEnroll: (String?) -> Unit, onOpenQuality: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { VoiceLogDatabaseHolder.get(ctx) }

    var profiles by remember { mutableStateOf<List<VoiceprintStore.Profile>>(emptyList()) }
    var consistencies by remember { mutableStateOf<Map<String, Float?>>(emptyMap()) }
    var selfId by remember { mutableStateOf<String?>(null) }
    var meName by remember { mutableStateOf(VoiceprintStore.myName(ctx)) }

    var renameTarget by remember { mutableStateOf<VoiceprintStore.Profile?>(null) }
    var editFallbackName by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<VoiceprintStore.Profile?>(null) }
    var showClearVp by remember { mutableStateOf(false) }

    var threshold by remember { mutableStateOf(VoiceprintStore.threshold(ctx)) }
    var tags by remember { mutableStateOf(PersonTags.list(ctx)) }
    var tagInput by remember { mutableStateOf("") }
    var totalSegments by remember { mutableStateOf(0) }

    var spkReady by remember { mutableStateOf(ModelPaths.spkReady(ctx)) }
    var asrReady by remember { mutableStateOf(ModelPaths.ready(ctx)) }
    var exportMsg by remember { mutableStateOf<String?>(null) }

    var realtimeAsr by remember { mutableStateOf(AppPrefs.realtimeAsr(ctx)) }
    var autoDeleteDays by remember { mutableStateOf(AppPrefs.autoDeleteDays(ctx)) }
    var audioBytes by remember { mutableStateOf(0L) }
    var pendingSegs by remember { mutableStateOf(0) }

    // ---- 转写参数：语种 / ITN / VAD 灵敏度 / 断句时长 / 码率
    var asrLang by remember { mutableStateOf(AppPrefs.asrLang(ctx)) }
    var asrItn by remember { mutableStateOf(AppPrefs.asrItn(ctx)) }
    var vadThreshold by remember { mutableStateOf(AppPrefs.vadThreshold(ctx)) }
    var vadMinSilence by remember { mutableStateOf(AppPrefs.vadMinSilence(ctx)) }
    var aacBitRate by remember { mutableStateOf(AppPrefs.aacBitRate(ctx)) }
    /* 2026-11-10 从硬编码放出来的两个参数 + 基频判男女 + A/B 标签 + 输入设备 */
    var vadMinSpeech by remember { mutableStateOf(AppPrefs.vadMinSpeech(ctx)) }
    var vadMaxSpeech by remember { mutableStateOf(AppPrefs.vadMaxSpeech(ctx)) }
    var f0On by remember { mutableStateOf(AppPrefs.f0Enabled(ctx)) }
    var f0MaleMax by remember { mutableStateOf(AppPrefs.f0MaleMax(ctx)) }
    var f0FemaleMin by remember { mutableStateOf(AppPrefs.f0FemaleMin(ctx)) }
    var f0MaleName by remember { mutableStateOf(AppPrefs.f0MaleLabel(ctx)) }
    var f0FemaleName by remember { mutableStateOf(AppPrefs.f0FemaleLabel(ctx)) }
    var abAName by remember { mutableStateOf(AppPrefs.abA(ctx)) }
    var abBName by remember { mutableStateOf(AppPrefs.abB(ctx)) }
    var inputDeviceId by remember { mutableStateOf(AppPrefs.inputDeviceId(ctx)) }
    var inputDeviceName by remember { mutableStateOf(AppPrefs.inputDeviceName(ctx)) }
    var showDevicePick by remember { mutableStateOf(false) }
    /* 可用输入设备列表。每次打开选择弹窗时现查 —— 拔插之后列表会变，
       缓存下来只会给出一个点不动的死选项。 */
    var deviceList by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }

    // ---- 云端同步 ----
    val syncState by SyncManager.state.collectAsState()
    var cloudUrl by remember { mutableStateOf(AppPrefs.cloudUrl(ctx)) }
    var cloudToken by remember { mutableStateOf(AppPrefs.cloudToken(ctx)) }
    var syncEnabled by remember { mutableStateOf(AppPrefs.syncEnabled(ctx)) }
    var syncProbeMsg by remember { mutableStateOf<String?>(null) }

    // ---- AI 校对（配置存在云端 vl_meta，本机只做展示与编辑）
    var pfCfg by remember { mutableStateOf<ProofreadClient.Config?>(null) }
    var pfMsg by remember { mutableStateOf<String?>(null) }
    var pfLoading by remember { mutableStateOf(false) }
    var showWords by remember { mutableStateOf(false) }
    var wordsInput by remember { mutableStateOf("") }
    /* 校对提示词：整段系统指令可改。这是「为什么校对没用」最可能的调节点，
       用户的场景/口音/词表只有他知道，把指令锁死在代码里等于让他没法调。 */
    var showPrompt by remember { mutableStateOf(false) }
    var promptInput by remember { mutableStateOf("") }
    /* 模型通道：cloudbase 免 key 但慢（混元带思考链），openai 兼容端点快（需自己的 key） */
    var showChan by remember { mutableStateOf(false) }
    var chProvider by remember { mutableStateOf("cloudbase") }
    var chBase by remember { mutableStateOf("") }
    var chKey by remember { mutableStateOf("") }
    var chModel by remember { mutableStateOf("") }

    val batteryWhitelisted = remember { isIgnoringBatteryOptimizations(ctx) }

    suspend fun reload() {
        val ps = withContext(Dispatchers.IO) { VoiceprintStore.profiles(ctx) }
        // 一致性要读全部样本算两两余弦，放在 IO 里做，别卡住界面
        val cs = withContext(Dispatchers.IO) {
            ps.associate { it.id to VoiceprintStore.consistency(it.samples) }
        }
        profiles = ps
        consistencies = cs
        selfId = VoiceprintStore.selfId(ctx)
        meName = VoiceprintStore.myName(ctx)
        threshold = VoiceprintStore.threshold(ctx)
        tags = PersonTags.list(ctx)
        spkReady = ModelPaths.spkReady(ctx)
        asrReady = ModelPaths.ready(ctx)
        totalSegments = withContext(Dispatchers.IO) { db.transcriptDao().all().size }
        realtimeAsr = AppPrefs.realtimeAsr(ctx)
        autoDeleteDays = AppPrefs.autoDeleteDays(ctx)
        asrLang = AppPrefs.asrLang(ctx)
        asrItn = AppPrefs.asrItn(ctx)
        vadThreshold = AppPrefs.vadThreshold(ctx)
        vadMinSilence = AppPrefs.vadMinSilence(ctx)
        aacBitRate = AppPrefs.aacBitRate(ctx)
        audioBytes = withContext(Dispatchers.IO) { db.segmentDao().totalBytes() }
        pendingSegs = withContext(Dispatchers.IO) { db.segmentDao().pendingCount() }
        SyncManager.refresh(ctx, db)
    }

    // 每次进入设置页都重新读一遍状态：从声纹录入页回来时，
    // 这里必须立刻反映出新加的那个人，而不是停留打开前的旧值。
    LaunchedEffect(Unit) { reload() }

    /* 校对配置存在云端，进页面拉一次。
       失败不阻塞界面 —— 拿不到配置只是看不到词表，校对本身仍然可用
       （服务端会用自己存的旧值）。 */
    LaunchedEffect(Unit) {
        pfLoading = true
        val r = ProofreadClient.getConfig(AppPrefs.cloudUrl(ctx), AppPrefs.cloudToken(ctx))
        if (r.ok) {
            pfCfg = r.config
            wordsInput = r.config.words.joinToString("\n")
        } else {
            pfMsg = r.message
        }
        pfLoading = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text("设置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = VlColor.Text)
        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------- 识别质量
        /* 放在最顶上：这是目前唯一能回答「现在到底准不准」的地方，
           而用户每次来设置页几乎都是因为「识别效果好像不太行」。
           藏到最后的「数据」区等于让最该看的东西最难找。 */
        SettingsCard("识别质量 · 用你自己的修改反推") {
            Text(
                "每次改错字、改说话人，都会自动记一条。这里把它们换算成改动率、字错率和改判率，" +
                    "按麦克风和日期分组 —— 判断该换麦、该调参，还是该换模型。",
                fontSize = 11.sp,
                color = VlColor.Sub,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenQuality,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VlColor.SurfaceVar,
                    contentColor = VlColor.Accent,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("查看识别质量", fontSize = 12.sp)
            }
        }

        // ---------------------------------------------- 声纹档案
        SettingsCard("声纹 · 已注册 ${profiles.size} 人") {
            /* 这一段是整套声纹能不能用的前提，必须写在最显眼的位置：
               实测同一个人换个麦克风，相似度从 0.65 掉到 0.25，与陌生人（0.24）
               没有区别。不解释这一点，用户会以为是识别不准，其实是音源换了。 */
            Text(
                "声纹绑在「录音输入」里选的那支麦克风上。换麦之后要重新录 —— " +
                    "同一句话换个麦克风，程序听起来就像换了一个人。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(10.dp))
            if (profiles.isEmpty()) {
                Text(
                    "还没有任何声纹。注册后，说话人会被自动标上名字，不用事后一句句改。",
                    fontSize = 12.sp,
                    color = VlColor.Sub,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "我的标签名（未注册声纹时使用）：",
                    fontSize = 11.sp,
                    color = VlColor.Dim,
                )
                Text(
                    meName,
                    fontSize = 13.sp,
                    color = VlColor.Accent,
                    modifier = Modifier.clickable { editFallbackName = true },
                )

                // 空状态必须留入口。v5 这里漏了按钮，导致删光档案后再也进不了注册页。
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onEnroll(null) },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VlColor.Accent,
                        contentColor = androidx.compose.ui.graphics.Color(0xFF00204A),
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("录入第一个人的声纹", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                profiles.forEachIndexed { i, p ->
                    if (i > 0) {
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(VlColor.Line))
                        Spacer(Modifier.height(8.dp))
                    }
                    val isSelf = p.id == selfId
                    val c = consistencies[p.id]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .width(7.dp).height(7.dp)
                                .background(VlColor.Green, RoundedCornerShape(4.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    p.name,
                                    fontSize = 14.sp,
                                    color = VlColor.Text,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clickable { renameTarget = p },
                                )
                                if (isSelf) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        Modifier
                                            .background(VlColor.MeBg, RoundedCornerShape(3.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                    ) {
                                        Text("我", fontSize = 9.sp, color = VlColor.MeFg)
                                    }
                                }
                                if (!isSelf) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "设为「我」",
                                        fontSize = 9.sp,
                                        color = VlColor.Accent,
                                        modifier = Modifier.clickable {
                                            VoiceprintStore.setSelfId(ctx, p.id)
                                            scope.launch { reload() }
                                        },
                                    )
                                }
                            }
                            Text(
                                text = buildString {
                                    append("${p.sampleCount} 句样本")
                                    if (c != null) append(" · 一致性 %.2f".format(c))
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    c == null -> VlColor.Dim
                                    c >= 0.70f -> VlColor.Green.copy(alpha = 0.85f)
                                    c >= 0.55f -> VlColor.Amber
                                    else -> VlColor.Red
                                },
                            )
                            /* 哪支麦录的。旧版档案没有这个字段（null）→ 不参与判定。
                               旧版注册代码没指定输入设备，走系统默认（= 内置麦，除非当时插着耳机），
                               所以给一个一键补绑入口，而不是拿"重录 4 句"当唯一出路。 */
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "麦：" + MicRoute.keyLabel(p.micKey),
                                    fontSize = 10.sp,
                                    color = if (p.micKey.isNullOrBlank()) VlColor.Amber else VlColor.Dim,
                                    lineHeight = 14.sp,
                                )
                                if (p.micKey.isNullOrBlank()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "补绑内置麦",
                                        fontSize = 10.sp,
                                        color = VlColor.Accent,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(VlColor.SurfaceVar)
                                            .clickable {
                                                VoiceprintStore.bindMic(ctx, p.id, MicRoute.KEY_BUILTIN)
                                                scope.launch { reload() }
                                            }
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { onEnroll(p.id) }) {
                            Text("重录", fontSize = 11.sp, color = VlColor.Accent)
                        }
                        TextButton(onClick = { pendingDelete = p }) {
                            Text("删除", fontSize = 11.sp, color = VlColor.Red)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onEnroll(null) },
                    enabled = profiles.size < VoiceprintStore.MAX_PROFILES,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VlColor.SurfaceVar,
                        contentColor = VlColor.Text,
                        disabledContainerColor = VlColor.SurfaceVar,
                        disabledContentColor = VlColor.Dim,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        if (profiles.size < VoiceprintStore.MAX_PROFILES) "＋ 添加一个说话人"
                        else "已达 ${VoiceprintStore.MAX_PROFILES} 人上限",
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("判定阈值", fontSize = 12.sp, color = VlColor.Sub)
                Spacer(Modifier.weight(1f))
                Text(
                    "%.2f".format(threshold),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = VlColor.Accent,
                )
            }
            Slider(
                value = threshold.coerceIn(VoiceprintStore.MIN_THRESHOLD, VoiceprintStore.MAX_THRESHOLD),
                onValueChange = {
                    threshold = it
                    VoiceprintStore.setThreshold(ctx, it)
                },
                valueRange = VoiceprintStore.MIN_THRESHOLD..VoiceprintStore.MAX_THRESHOLD,
                steps = 7,
                colors = SliderDefaults.colors(
                    thumbColor = VlColor.Accent,
                    activeTrackColor = VlColor.Accent,
                    inactiveTrackColor = VlColor.Line,
                ),
            )
            Text(
                text = "推荐 0.40，不用改。与哪份档案都不够像时不给名字（显示「未判」）。\n" +
                    "这个值是量出来的，不是拍的：同一支麦上，你自己的句子互相 0.43~0.65，" +
                    "而你和另一个人之间最高只有 0.29。0.40 正好卡在中间。\n" +
                    "调高 → 宁可不判，标出来的少但更准；调低 → 标得多，但会把别人认成你。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 15.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------- 常用说话人标签
        SettingsCard("常用说话人 · 打标时的快捷选项") {
            if (tags.isEmpty()) {
                Text(
                    "把经常和你说话的人名加进来。以后多选句子标人时，点一下就选中，不用每次手打。",
                    fontSize = 11.sp,
                    color = VlColor.Dim,
                    lineHeight = 16.sp,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tags.forEach { t ->
                        // 整个标签就是一个删除按钮。不用 AssistChip 的 trailingIcon：
                        // chip 自身吸走了点击事件，图标上的 clickable 不一定能收到。
                        Row(
                            modifier = Modifier
                                .background(VlColor.SurfaceVar, RoundedCornerShape(8.dp))
                                .clickable { tags = PersonTags.remove(ctx, t) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(t, fontSize = 12.sp, color = VlColor.Text)
                            Spacer(Modifier.width(5.dp))
                            Text("×", fontSize = 12.sp, color = VlColor.Dim)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("点标签可删除", fontSize = 9.sp, color = VlColor.Dim)
                Spacer(Modifier.height(10.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it.take(12) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("加一个人名，如「老婆」", fontSize = 12.sp, color = VlColor.Dim) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = VlColor.Text),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VlColor.Accent,
                        unfocusedBorderColor = VlColor.Line,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (tagInput.isNotBlank()) {
                            tags = PersonTags.add(ctx, tagInput)
                            tagInput = ""
                        }
                    },
                    enabled = tagInput.isNotBlank(),
                ) {
                    Text("添加", fontSize = 13.sp, color = if (tagInput.isNotBlank()) VlColor.Accent else VlColor.Dim)
                }
            }

            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = {
                    scope.launch {
                        val hist = withContext(Dispatchers.IO) { db.transcriptDao().knownSpeakers() }
                        tags = PersonTags.addAll(ctx, hist)
                    }
                },
            ) {
                Text("从历史记录收进出现过的名字", fontSize = 11.sp, color = VlColor.Accent)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------- 采集与转写
        SettingsCard("采集与转写") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("实时转写", fontSize = 13.sp, color = VlColor.Text)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (realtimeAsr) {
                            "边说边出字。转写模型常驻内存（约 300MB），耗电明显。"
                        } else {
                            "只录音不转写。模型不加载，耗电低；停止后到素材页批量转写。"
                        },
                        fontSize = 10.sp,
                        color = VlColor.Dim,
                        lineHeight = 14.sp,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = realtimeAsr,
                    onCheckedChange = {
                        realtimeAsr = it
                        AppPrefs.setRealtimeAsr(ctx, it)
                    },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = VlColor.Accent,
                        checkedTrackColor = VlColor.Accent.copy(alpha = 0.35f),
                    ),
                )
            }

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VlColor.Line))
            Spacer(Modifier.height(12.dp))

            Text("已缓存音频：${fmtSize(audioBytes)} · $pendingSegs 段待转写", fontSize = 12.sp, color = VlColor.Sub)
            Spacer(Modifier.height(8.dp))
            Text(
                "自动清理已转写的音频",
                fontSize = 11.sp,
                color = VlColor.Dim,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 3, 7, 30).forEach { d ->
                    val sel = autoDeleteDays == d
                    androidx.compose.material3.FilterChip(
                        selected = sel,
                        onClick = {
                            autoDeleteDays = d
                            AppPrefs.setAutoDeleteDays(ctx, d)
                        },
                        label = { Text(if (d == 0) "不清理" else "${d}天", fontSize = 11.sp) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = VlColor.SurfaceVar,
                            labelColor = VlColor.Sub,
                            selectedContainerColor = VlColor.MeBg,
                            selectedLabelColor = VlColor.MeFg,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "只清已经转成文字的音频；还没转写的绝不自动删（删了文字就永远没了）。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 14.sp,
            )

            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VlColor.Line))
            Spacer(Modifier.height(12.dp))

            /* 自检入口：不为了「好玩」，是为了把「录不到」这个笼统现象劈成两半 ——
               播放有声 = 扬声器与 TTS 正常，问题在采集侧；播放无声 = 先修系统语音。
               换个手机、换套系统、怀疑麦克风坏了的时候，这个按钮能省半小时。 */
            TextButton(
                onClick = { TtsProbe.speak(ctx) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("播放测试语音（检查麦克风）", fontSize = 12.sp, color = VlColor.Accent)
            }
            Text(
                text = "需要系统装了语音引擎（TTS）。小米部分机型精简掉了，点了没反应就是没装，" +
                    "直接自己说句话效果一样。有声音时看监听页电平条是否跳动。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 14.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------- 转写参数
        /* 这一整块的存在理由：识别效果差的时候，用户能自己调，而不是只能反馈给开发者。
           每个参数都写清「改了以后会影响什么」—— 只给档位不讲后果，等于让用户瞎试。 */
        SettingsCard("转写参数 · 下次录音 / 重新转录生效") {
            Text("转写语种", fontSize = 12.sp, color = VlColor.Sub)
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppPrefs.ASR_LANG_LABELS.forEach { (value, label) ->
                    val sel = asrLang == value
                    androidx.compose.material3.FilterChip(
                        selected = sel,
                        onClick = {
                            asrLang = value
                            AppPrefs.setAsrLang(ctx, value)
                        },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = VlColor.SurfaceVar,
                            labelColor = VlColor.Sub,
                            selectedContainerColor = VlColor.MeBg,
                            selectedLabelColor = VlColor.MeFg,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "默认中文。录外语才改；改成自动识别时短句容易跳语种，识别出英文单词。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 14.sp,
            )

            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VlColor.Line))
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("数字与日期转成阿拉伯数字", fontSize = 13.sp, color = VlColor.Text)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "「二零二六年三月」→「2026年3月」。关掉保留读音原样。",
                        fontSize = 10.sp,
                        color = VlColor.Dim,
                        lineHeight = 14.sp,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = asrItn,
                    onCheckedChange = {
                        asrItn = it
                        AppPrefs.setAsrItn(ctx, it)
                    },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = VlColor.Accent,
                        checkedTrackColor = VlColor.Accent.copy(alpha = 0.35f),
                    ),
                )
            }

            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VlColor.Line))
            Spacer(Modifier.height(10.dp))

            Text("人声判定灵敏度", fontSize = 11.sp, color = VlColor.Sub)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    0.40f to "灵敏",
                    0.50f to "标准",
                    0.65f to "保守",
                ).forEach { (v, label) ->
                    val sel = kotlin.math.abs(vadThreshold - v) < 0.01f
                    androidx.compose.material3.FilterChip(
                        selected = sel,
                        onClick = {
                            vadThreshold = v
                            AppPrefs.setVadThreshold(ctx, v)
                        },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = VlColor.SurfaceVar,
                            labelColor = VlColor.Sub,
                            selectedContainerColor = VlColor.MeBg,
                            selectedLabelColor = VlColor.MeFg,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "越低越能切到远处、小声的说话，代价是空调声、键盘声更容易被当成人在说话。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 14.sp,
            )

            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VlColor.Line))
            Spacer(Modifier.height(10.dp))

            Text("断句停顿时长", fontSize = 11.sp, color = VlColor.Sub)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    0.50f to "0.5 秒",
                    0.80f to "0.8 秒",
                    1.50f to "1.5 秒",
                ).forEach { (v, label) ->
                    val sel = kotlin.math.abs(vadMinSilence - v) < 0.01f
                    androidx.compose.material3.FilterChip(
                        selected = sel,
                        onClick = {
                            vadMinSilence = v
                            AppPrefs.setVadMinSilence(ctx, v)
                        },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = VlColor.SurfaceVar,
                            labelColor = VlColor.Sub,
                            selectedContainerColor = VlColor.MeBg,
                            selectedLabelColor = VlColor.MeFg,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "越短切得越碎。转写是按段独立识别的，段太碎会丢上下文（实测 0.25 秒时" +
                    "同一个词前后两次转出不同结果），所以别调太短。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 14.sp,
            )

            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VlColor.Line))
            Spacer(Modifier.height(10.dp))

            /* ---- 最短语音：滤掉碗筷碰撞、键盘声这类瞬态 ---- */
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最短语音", fontSize = 11.sp, color = VlColor.Sub)
                Spacer(Modifier.weight(1f))
                Text(
                    "%.2f 秒".format(vadMinSpeech),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = VlColor.Accent,
                )
            }
            Slider(
                value = vadMinSpeech,
                onValueChange = {
                    vadMinSpeech = it
                    AppPrefs.setVadMinSpeech(ctx, it)
                },
                valueRange = 0.15f..1.5f,
                colors = SliderDefaults.colors(
                    thumbColor = VlColor.Accent,
                    activeTrackColor = VlColor.Accent,
                ),
            )
            Text(
                "短于这个时长的声音不算一句话。调大能滤掉碗筷、键盘的瞬态噪声，" +
                    "代价是很短的「嗯」「对」也会一并丢掉。默认 0.25 秒。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 14.sp,
            )

            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VlColor.Line))
            Spacer(Modifier.height(10.dp))

            /* ---- 最长单段：长独白切细，方便一段标一个人 ---- */
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最长单段", fontSize = 11.sp, color = VlColor.Sub)
                Spacer(Modifier.weight(1f))
                Text(
                    "%.0f 秒".format(vadMaxSpeech),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = VlColor.Accent,
                )
            }
            Slider(
                value = vadMaxSpeech,
                onValueChange = {
                    vadMaxSpeech = it
                    AppPrefs.setVadMaxSpeech(ctx, it)
                },
                valueRange = 5f..60f,
                colors = SliderDefaults.colors(
                    thumbColor = VlColor.Accent,
                    activeTrackColor = VlColor.Accent,
                ),
            )
            Text(
                "一个人连说不停时，超过这个长度就强制切断。默认 30 秒。" +
                    "切成更短能让一段只属于一个人，标人更准；代价是跨段的上下文变少。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 14.sp,
            )

            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VlColor.Line))
            Spacer(Modifier.height(10.dp))

            Text("录音码率", fontSize = 11.sp, color = VlColor.Sub)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    32_000 to "32k",
                    48_000 to "48k",
                    64_000 to "64k",
                ).forEach { (v, label) ->
                    val sel = aacBitRate == v
                    androidx.compose.material3.FilterChip(
                        selected = sel,
                        onClick = {
                            aacBitRate = v
                            AppPrefs.setAacBitRate(ctx, v)
                        },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = VlColor.SurfaceVar,
                            labelColor = VlColor.Sub,
                            selectedContainerColor = VlColor.MeBg,
                            selectedLabelColor = VlColor.MeFg,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "48k 是默认。32k 省三分之一空间，但 s/sh/x 这类高频音容易丢，会直接影响识别。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 14.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------- 说话人名字
        SettingsCard("说话人名字") {
            Text(
                "听标底部的两个按钮、以及下面的基频判男女，都用这两个名字。" +
                    "固定下来后不用每轮进听标再手改一遍。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = abAName,
                    onValueChange = {
                        abAName = it.take(12)
                        AppPrefs.setAbA(ctx, it)
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("男声 / A", fontSize = 10.sp, color = VlColor.Dim) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = VlColor.Text),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VlColor.Accent,
                        unfocusedBorderColor = VlColor.Line,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = abBName,
                    onValueChange = {
                        abBName = it.take(12)
                        AppPrefs.setAbB(ctx, it)
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("女声 / B", fontSize = 10.sp, color = VlColor.Dim) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = VlColor.Text),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VlColor.Accent,
                        unfocusedBorderColor = VlColor.Line,
                    ),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------- 自动标人（基频判男女）
        SettingsCard("自动标人 · 基频判男女") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("转写时按基频标人", fontSize = 13.sp, color = VlColor.Text)
                    Text(
                        "男声→$abAName，女声→$abBName。只作用于还没标的句子，不覆盖你手标的。",
                        fontSize = 10.sp,
                        color = VlColor.Dim,
                        lineHeight = 13.sp,
                    )
                }
                Switch(
                    checked = f0On,
                    onCheckedChange = {
                        f0On = it
                        AppPrefs.setF0Enabled(ctx, it)
                    },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = VlColor.Accent,
                        checkedTrackColor = VlColor.Accent.copy(alpha = 0.35f),
                    ),
                )
            }

            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(VlColor.Line))
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("男声上限", fontSize = 11.sp, color = VlColor.Sub)
                Spacer(Modifier.weight(1f))
                Text(
                    "%.0f Hz".format(f0MaleMax),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = VlColor.Accent,
                )
            }
            Slider(
                value = f0MaleMax,
                onValueChange = {
                    f0MaleMax = it
                    AppPrefs.setF0MaleMax(ctx, it)
                },
                valueRange = 100f..200f,
                colors = SliderDefaults.colors(
                    thumbColor = VlColor.Accent,
                    activeTrackColor = VlColor.Accent,
                ),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("女声下限", fontSize = 11.sp, color = VlColor.Sub)
                Spacer(Modifier.weight(1f))
                Text(
                    "%.0f Hz".format(f0FemaleMin),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = VlColor.Accent,
                )
            }
            Slider(
                value = f0FemaleMin,
                onValueChange = {
                    f0FemaleMin = it
                    AppPrefs.setF0FemaleMin(ctx, it)
                },
                valueRange = 160f..300f,
                colors = SliderDefaults.colors(
                    thumbColor = VlColor.Accent,
                    activeTrackColor = VlColor.Accent,
                ),
            )

            Text(
                "实测双峰：男 100–160Hz / 女 200–280Hz。落在 " +
                    "${"%.0f".format(f0MaleMax)}–${"%.0f".format(f0FemaleMin)}Hz 之间的句子" +
                    "一律留「未判」，不猜 —— 那段边界实测 15.6% 判不准，猜错比空着更费你的时间。" +
                    "另外：电视里的女播音会被判成「$abBName」，那几句要手工改。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 14.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------- 录音输入
        SettingsCard("录音输入") {
            Text(
                "先在这里定好用哪支麦，再去录音。声纹认不认得出人，取决于录音和注册用的是不是同一支麦。",
                fontSize = 10.sp,
                color = VlColor.Amber,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(inputDeviceName, fontSize = 13.sp, color = VlColor.Text)
                    Text(
                        "系统可能不理会这个请求，所以每次开录后都会复核实际走了哪一路，" +
                            "不一致会提示你，不会静默退回内置麦。",
                        fontSize = 10.sp,
                        color = VlColor.Dim,
                        lineHeight = 13.sp,
                    )
                }
                TextButton(
                    onClick = {
                        deviceList = queryInputDevices(ctx)
                        showDevicePick = true
                    },
                ) { Text("更换", fontSize = 12.sp, color = VlColor.Accent) }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------- 模型
        SettingsCard("模型") {
            ModelRow("中文转写 SenseVoice", asrReady, "228 MB")
            Spacer(Modifier.height(8.dp))
            ModelRow("语音端点检测 Silero VAD", asrReady, "629 KB")
            Spacer(Modifier.height(8.dp))
            ModelRow("声纹 campplus（可选）", spkReady, "27 MB")

            if (!asrReady) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "缺少：" + ModelPaths.missing(ctx).joinToString(", "),
                    fontSize = 10.sp,
                    color = VlColor.Amber,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = ModelPaths.dir(ctx).absolutePath,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = VlColor.Dim,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------- 后台存活
        SettingsCard("后台存活") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (batteryWhitelisted) "已加入电池优化白名单" else "未加入白名单，可能被系统杀掉",
                    fontSize = 12.sp,
                    color = if (batteryWhitelisted) VlColor.Green else VlColor.Amber,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "MIUI / HyperOS 还需手动允许：后台弹出界面、显示在其他应用上层、自启动。" +
                    "这些开关第三方应用无法代劳。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 15.sp,
            )
            if (!batteryWhitelisted) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { openBatterySettings(ctx) }) {
                    Text("去设置", fontSize = 12.sp, color = VlColor.Accent)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------- 云端同步
        /* 这块的定位：把「本地转写文字」送到云端，**不送音频**。
           界面刻意把「待同步几轮 / 上次成功时间 / 失败原因」三样都摆出来 ——
           同步是后台行为，一旦静默失败，用户只会看到「外部 AI 说没数据」，
           而问题可能只是 token 抄错一位。三样信息正是定位这类问题的最小集合。 */
        SettingsCard("云端同步") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                /* 删除也走云同步，所以「待同步」要能分出是活数据还是删除 ——
                   两者的失败原因和排查方向完全不同（一个是内容有问题，一个是权限有问题）。 */
                Text(
                    when {
                        syncState.dirty > 0 && syncState.pendingDel > 0 ->
                            "待同步 ${syncState.dirty} 轮 · 待删除 ${syncState.pendingDel} 轮"
                        syncState.dirty > 0 -> "待同步 ${syncState.dirty} 轮"
                        syncState.pendingDel > 0 -> "待同步删除 ${syncState.pendingDel} 轮"
                        else -> "已全部同步"
                    },
                    fontSize = 12.sp,
                    color = if (syncState.dirty > 0 || syncState.pendingDel > 0) VlColor.Text else VlColor.Green,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (syncState.lastAt > 0) "上次成功 ${fmtClock(syncState.lastAt)}" else "从未成功同步",
                    fontSize = 10.sp,
                    color = VlColor.Dim,
                )
            }
            Spacer(Modifier.height(10.dp))

            /* 地址很长，单行输入框会横向滚到尾部，用户看到的是「后半截」，
               容易以为自己填的就是那一截。所以：填过就在下面显示「实际请求的完整地址」。
               后端自建，代码里不预置任何地址，placeholder 只给格式示例。 */
            OutlinedTextField(
                value = cloudUrl,
                onValueChange = {
                    cloudUrl = it; AppPrefs.setCloudUrl(ctx, it)
                    /* 配置变了，上一次的失败原因就失去意义了 —— 见 SyncManager.clearError */
                    SyncManager.clearError(ctx); syncProbeMsg = null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("云端地址（到 /api 结尾）", fontSize = 11.sp, color = VlColor.Dim) },
                placeholder = {
                    Text(
                        "https://<env-id>.<region>.app.tcloudbase.com/api",
                        fontSize = 10.sp, color = VlColor.Dim,
                    )
                },
                textStyle = TextStyle(fontSize = 12.sp, color = VlColor.Text, fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VlColor.Accent,
                    unfocusedBorderColor = VlColor.Line,
                ),
            )
            if (cloudUrl.isNotBlank()) {
                Text(
                    "实际请求：${cloudUrl.trimEnd('/')}/vl/sync",
                    fontSize = 9.sp,
                    color = VlColor.Dim,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 13.sp,
                    modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = cloudToken,
                onValueChange = {
                    cloudToken = it; AppPrefs.setCloudToken(ctx, it)
                    SyncManager.clearError(ctx); syncProbeMsg = null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("写 token（vl_writetoken/app）", fontSize = 11.sp, color = VlColor.Dim) },
                textStyle = TextStyle(fontSize = 12.sp, color = VlColor.Text, fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VlColor.Accent,
                    unfocusedBorderColor = VlColor.Line,
                ),
            )
            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        syncProbeMsg = "连接中…"
                        scope.launch {
                            val r = SyncManager.testConnection(ctx)
                            syncProbeMsg = r.message
                        }
                    },
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VlColor.SurfaceVar,
                        contentColor = VlColor.Text,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("测试连接", fontSize = 13.sp)
                }
                Button(
                    onClick = { scope.launch { SyncManager.syncNow(ctx, db) } },
                    enabled = !syncState.running,
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VlColor.Accent,
                        contentColor = VlColor.Surface,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(if (syncState.running) "同步中…" else "立即同步", fontSize = 13.sp)
                }
            }

            /* 失败原因用红字、结论用绿字：这两条必须能一眼分开。
               失败时不要把成功提示也一起显示，否则用户会以为「说成功又报错」。 */
            val err = syncState.lastError
            val note = syncProbeMsg ?: syncState.lastMessage
            if (err.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(err, fontSize = 11.sp, color = VlColor.Red)
            } else if (!note.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(note, fontSize = 11.sp, color = VlColor.Sub)
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("录完自动同步", fontSize = 12.sp, color = VlColor.Text)
                    Text(
                        "默认关。开着时每轮录音结束自动推送，出错不会有提示 —— " +
                            "调试阶段建议手动，等稳定了再开。",
                        fontSize = 10.sp,
                        color = VlColor.Dim,
                    )
                }
                Switch(
                    checked = syncEnabled,
                    onCheckedChange = {
                        syncEnabled = it
                        AppPrefs.setSyncEnabled(ctx, it)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = VlColor.Accent),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "只同步文字，音频永远留在本机。未转写的会话不会上传。",
                fontSize = 10.sp,
                color = VlColor.Dim,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------- AI 校对
        SettingsCard("AI 校对 · 只改错字，不动意思") {
            val cfg = pfCfg
            val wordN = cfg?.words?.size ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        pfLoading -> "正在读取云端配置…"
                        cfg == null -> "读不到云端配置（不影响校对，服务端会用上次存的值）"
                        cfg.model.isBlank() -> "模型：服务端默认"
                        cfg.provider == "openai" -> "模型：${cfg.model}（外部 API）"
                        else -> "模型：${cfg.group}/${cfg.model}（云开发内置）"
                    },
                    fontSize = 11.sp,
                    color = VlColor.Sub,
                )
                Spacer(Modifier.weight(1f))
                if (cfg != null) {
                    Text(
                        "$wordN 个常用词",
                        fontSize = 11.sp,
                        color = if (wordN > 0) VlColor.Green else VlColor.Dim,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    chProvider = cfg?.provider ?: "cloudbase"
                    chBase = cfg?.apiBase ?: ""
                    chModel = cfg?.model ?: ""
                    chKey = ""
                    showChan = true
                },
                enabled = !pfLoading,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VlColor.SurfaceVar,
                    contentColor = VlColor.Text,
                    disabledContainerColor = VlColor.SurfaceVar,
                    disabledContentColor = VlColor.Dim,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    if (cfg?.provider == "openai") "模型通道：外部 API" else "模型通道：云开发内置",
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "云开发内置免配置，但混元带思考链，一轮 1 万字要跑十几分钟（后台跑，不影响用）。" +
                    "换成外部 API（如 DeepSeek）同样一轮约 1 分钟。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "词表里的人和专有词会被优先采用。这是目前唯一被实测证明有效的降错手段 —— " +
                    "转写错误绝大多数是同音字（28星宿→28新秀、剪映→全民K歌），" +
                    "把正确写法喂进去，模型才知道该往哪儿改。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showWords = true },
                enabled = !pfLoading,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VlColor.SurfaceVar,
                    contentColor = VlColor.Text,
                    disabledContainerColor = VlColor.SurfaceVar,
                    disabledContentColor = VlColor.Dim,
                ),
                shape = RoundedCornerShape(10.dp),
            ) { Text("编辑常用词表", fontSize = 14.sp) }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    promptInput = cfg?.sysPrompt?.takeIf { it.isNotBlank() }
                        ?: cfg?.defaultSys.orEmpty()
                    showPrompt = true
                },
                enabled = !pfLoading && cfg != null,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VlColor.SurfaceVar,
                    contentColor = VlColor.Text,
                    disabledContainerColor = VlColor.SurfaceVar,
                    disabledContentColor = VlColor.Dim,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    if (cfg?.sysPrompt?.isNotBlank() == true) "校对提示词（已自定义）" else "校对提示词（默认）",
                    fontSize = 14.sp,
                )
            }

            /* 人名不用用户手填：注册过声纹的人和「常用说话人」标签本来就是事实来源，
               让用户再抄一遍必然抄漏。这里只做展示，保存时一起推上去。 */
            if ((cfg?.names?.size ?: 0) > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "人名（自动取自声纹与常用说话人）：${cfg?.names?.joinToString("、")}",
                    fontSize = 10.sp,
                    color = VlColor.Dim,
                )
            }
            pfMsg?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 10.sp, color = VlColor.Amber)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------- 数据
        SettingsCard("数据") {
            Text("累计 $totalSegments 句", fontSize = 12.sp, color = VlColor.Sub)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { exportAllAsText(ctx) }
                        exportMsg = if (f != null) "已导出到 ${f.name}" else "导出失败"
                    }
                },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VlColor.SurfaceVar,
                    contentColor = VlColor.Text,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("导出全部为文本", fontSize = 13.sp)
            }
            exportMsg?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 10.sp, color = VlColor.Dim)
            }
            if (profiles.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { showClearVp = true }) {
                    Text("清除全部声纹", fontSize = 11.sp, color = VlColor.Dim)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            /* 原文写的是「音频不落盘」，与事实相反：v3 起每段音频都按
               audio/<sid>/<segId>.m4a 存到本机，为的是能回听、能重新转写。
               这句话会让人误以为不必管理存储，所以必须改准。 */
            text = "语音记录 v3 · 端侧转写 · 音频按段存本机，只同步文字",
            fontSize = 10.sp,
            color = VlColor.Dim,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
    }

    // ---------------------------------------------- 录音输入设备选择
    if (showDevicePick) {
        AlertDialog(
            onDismissRequest = { showDevicePick = false },
            containerColor = VlColor.Surface,
            title = { Text("选择录音输入", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Column {
                    Text(
                        "下次开录时请求这个设备，实际用了哪一路会在监听页显示。" +
                            "蓝牙耳机必须在系统蓝牙设置里勾上「通话音频」，只连音乐通道（A2DP）是拿不到麦克风的。",
                        fontSize = 11.sp,
                        color = VlColor.Dim,
                        lineHeight = 15.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    (listOf(0 to "自动（系统默认）") + deviceList).forEach { (id, name) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    inputDeviceId = id
                                    inputDeviceName = name
                                    AppPrefs.setInputDevice(ctx, id, name)
                                    showDevicePick = false
                                }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                name,
                                fontSize = 13.sp,
                                color = if (id == inputDeviceId) VlColor.Accent else VlColor.Text,
                                modifier = Modifier.weight(1f),
                            )
                            if (id == inputDeviceId) {
                                Text("✓", fontSize = 13.sp, color = VlColor.Accent)
                            }
                        }
                    }
                    if (deviceList.isEmpty()) {
                        Text(
                            "除内置麦克风外没检测到其它输入设备。接上 USB 麦或领夹麦接收器后再进来。",
                            fontSize = 10.sp,
                            color = VlColor.Dim,
                            lineHeight = 14.sp,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDevicePick = false }) {
                    Text("取消", fontSize = 12.sp, color = VlColor.Sub)
                }
            },
        )
    }

    // ---------------------------------------------- 改名（档案）
    renameTarget?.let { target ->
        var input by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = VlColor.Surface,
            title = { Text("改名字", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Column {
                    Text("改名会影响之后的自动判定标签，已经标好的句子不会变。", fontSize = 11.sp, color = VlColor.Dim)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.take(12) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 14.sp, color = VlColor.Text),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VlColor.Accent,
                            unfocusedBorderColor = VlColor.Line,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        VoiceprintStore.renameProfile(ctx, target.id, input)
                        renameTarget = null
                        scope.launch { reload() }
                    },
                    enabled = input.isNotBlank(),
                ) {
                    Text("保存", color = if (input.isNotBlank()) VlColor.Accent else VlColor.Dim)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("取消", color = VlColor.Sub)
                }
            },
        )
    }

    // ---------------------------------------------- 改名（无档案时的兜底）
    if (editFallbackName) {
        var input by remember { mutableStateOf(meName) }
        AlertDialog(
            onDismissRequest = { editFallbackName = false },
            containerColor = VlColor.Surface,
            title = { Text("我的标签名", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Column {
                    Text("注册本人声纹后，名字以声纹档案为准。", fontSize = 11.sp, color = VlColor.Dim)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.take(12) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 14.sp, color = VlColor.Text),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VlColor.Accent,
                            unfocusedBorderColor = VlColor.Line,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        VoiceprintStore.setMyName(ctx, input)
                        meName = VoiceprintStore.myName(ctx)
                        editFallbackName = false
                    },
                    enabled = input.isNotBlank(),
                ) {
                    Text("保存", color = if (input.isNotBlank()) VlColor.Accent else VlColor.Dim)
                }
            },
            dismissButton = {
                TextButton(onClick = { editFallbackName = false }) {
                    Text("取消", color = VlColor.Sub)
                }
            },
        )
    }

    // ---------------------------------------------- 删除某人
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = VlColor.Surface,
            title = { Text("删除「${target.name}」的声纹？", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Text(
                    "之后这个人的声音会被判成「他人」，已标好的句子不受影响。想恢复只能重新录 4 句。",
                    fontSize = 12.sp,
                    color = VlColor.Sub,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        VoiceprintStore.deleteProfile(ctx, target.id)
                        pendingDelete = null
                        scope.launch { reload() }
                    },
                ) {
                    Text("删除", color = VlColor.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = VlColor.Sub)
                }
            },
        )
    }

    // ---------------------------------------------- 清除全部声纹
    if (showClearVp) {
        AlertDialog(
            onDismissRequest = { showClearVp = false },
            containerColor = VlColor.Surface,
            title = { Text("清除全部声纹？", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Text(
                    "已识别的内容不受影响，但之后的句子不再自动判断说话人。已标注的标签会保留。",
                    fontSize = 12.sp,
                    color = VlColor.Sub,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        VoiceprintStore.clearAll(ctx)
                        showClearVp = false
                        scope.launch { reload() }
                    },
                ) {
                    Text("清除", color = VlColor.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearVp = false }) {
                    Text("取消", color = VlColor.Sub)
                }
            },
        )
    }

    // ---------------------------------------------- 模型通道
    /* 为什么要让人选：云开发内置走混元（带思考链），一轮 1 万字实测要十几分钟；
       外部 API（DeepSeek 之类）同样内容约 1 分钟。个人版套餐不能给云开发加模型
       （UpdateAIModel 报「当前环境的套餐不支持」），所以想快只能走外部通道。 */
    if (showChan) {
        AlertDialog(
            onDismissRequest = { showChan = false },
            containerColor = VlColor.Surface,
            title = { Text("校对用的模型", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Column {
                    Text(
                        "云开发内置：不用配置，但只能用混元，一轮 1 万字要十几分钟。\n" +
                            "外部 API：任何 OpenAI 兼容地址（DeepSeek / 硅基流动 / 阿里百炼等），" +
                            "同样内容约 1 分钟。key 只存在你自己的云端，界面只显示末 4 位。",
                        fontSize = 11.sp,
                        color = VlColor.Dim,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        listOf("cloudbase" to "云开发内置", "openai" to "外部 API").forEach { (v, label) ->
                            TextButton(
                                onClick = { chProvider = v },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 6.dp, vertical = 0.dp,
                                ),
                            ) {
                                Text(
                                    if (chProvider == v) "● $label" else "○ $label",
                                    fontSize = 12.sp,
                                    color = if (chProvider == v) VlColor.Accent else VlColor.Sub,
                                )
                            }
                        }
                    }
                    if (chProvider == "openai") {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = chBase,
                            onValueChange = { chBase = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("接口地址", fontSize = 11.sp) },
                            placeholder = { Text("https://api.deepseek.com/v1", fontSize = 11.sp) },
                            textStyle = TextStyle(fontSize = 12.sp, color = VlColor.Text),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VlColor.Accent,
                                unfocusedBorderColor = VlColor.Line,
                            ),
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = chModel,
                            onValueChange = { chModel = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("模型名", fontSize = 11.sp) },
                            placeholder = { Text("deepseek-chat", fontSize = 11.sp) },
                            textStyle = TextStyle(fontSize = 12.sp, color = VlColor.Text),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VlColor.Accent,
                                unfocusedBorderColor = VlColor.Line,
                            ),
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = chKey,
                            onValueChange = { chKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("API Key", fontSize = 11.sp) },
                            placeholder = {
                                Text(
                                    pfCfg?.apiKeyMasked?.takeIf { it.isNotBlank() } ?: "sk-...",
                                    fontSize = 11.sp,
                                )
                            },
                            textStyle = TextStyle(fontSize = 12.sp, color = VlColor.Text),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VlColor.Accent,
                                unfocusedBorderColor = VlColor.Line,
                            ),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("留空 = 不改动已保存的 key", fontSize = 10.sp, color = VlColor.Dim)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val r = ProofreadClient.saveConfig(
                                AppPrefs.cloudUrl(ctx),
                                AppPrefs.cloudToken(ctx),
                                provider = chProvider,
                                apiBase = chBase.trim(),
                                apiKey = if (chProvider == "openai" && chKey.trim().isNotEmpty()) chKey.trim() else null,
                                model = chModel.trim().ifBlank { null },
                            )
                            if (r.ok) {
                                val g = ProofreadClient.getConfig(AppPrefs.cloudUrl(ctx), AppPrefs.cloudToken(ctx))
                                if (g.ok) pfCfg = g.config
                                pfMsg = "已保存模型通道"
                                showChan = false
                            } else {
                                pfMsg = r.message
                            }
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showChan = false }) { Text("取消") }
            },
        )
    }

    // ---------------------------------------------- 常用词表编辑
    if (showWords) {
        AlertDialog(
            onDismissRequest = { showWords = false },
            containerColor = VlColor.Surface,
            title = { Text("常用词表", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Column {
                    Text(
                        "一行一个。填人名、软件名、地名，以及你们常聊话题里的专有词。" +
                            "校对时模型会优先按这些写法去修同音字。",
                        fontSize = 11.sp,
                        color = VlColor.Dim,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = wordsInput,
                        onValueChange = { wordsInput = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 280.dp),
                        textStyle = TextStyle(fontSize = 13.sp, color = VlColor.Text, lineHeight = 19.sp),
                        placeholder = {
                            Text(
                                "28星宿\n剪映\n凉宫春日\n水弹枪\n紅霉素软膏",
                                fontSize = 12.sp,
                                color = VlColor.Dim,
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VlColor.Accent,
                            unfocusedBorderColor = VlColor.Line,
                        ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "当前 ${wordsInput.lines().count { it.isNotBlank() }} 个 · 上限 2000",
                        fontSize = 10.sp,
                        color = VlColor.Dim,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val list = wordsInput.lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                            .take(2000)
                        scope.launch {
                            /* 人名不由输入框决定，从本机的事实来源取：
                               声纹档案 + 「常用说话人」标签。让用户再手打一遍必然抄漏。 */
                            val profs = withContext(Dispatchers.IO) { VoiceprintStore.profiles(ctx) }
                            val names = buildList {
                                VoiceprintStore.myName(ctx).takeIf { it.isNotBlank() }?.let { add(it) }
                                addAll(profs.map { it.name })
                                addAll(PersonTags.list(ctx))
                            }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(50)

                            pfMsg = "正在保存…"
                            val r = ProofreadClient.saveConfig(
                                baseUrl = AppPrefs.cloudUrl(ctx),
                                token = AppPrefs.cloudToken(ctx),
                                names = names,
                                words = list,
                            )
                            pfMsg = r.message
                            if (r.ok) {
                                pfCfg = (pfCfg ?: ProofreadClient.Config())
                                    .copy(names = names, words = list)
                                showWords = false
                            }
                        }
                    },
                ) { Text("保存", color = VlColor.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showWords = false }) {
                    Text("取消", color = VlColor.Sub)
                }
            },
        )
    }

    // ---------------------------------------------- 校对提示词编辑
    if (showPrompt) {
        AlertDialog(
            onDismissRequest = { showPrompt = false },
            containerColor = VlColor.Surface,
            title = { Text("校对提示词", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Column {
                    Text(
                        "这是发给模型的系统指令，决定它「改什么、改到什么程度」。留空则用默认。" +
                            "校对效果不满意时，先改这里 —— 比换模型见效快。",
                        fontSize = 11.sp,
                        color = VlColor.Dim,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "⚠️ 必须保留最后的输出格式说明（含 \"fix\" 字样），" +
                            "删掉之后模型返回的东西解析不出来，整轮校对会白跑。",
                        fontSize = 11.sp,
                        color = VlColor.Amber,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 320.dp),
                        textStyle = TextStyle(fontSize = 12.sp, color = VlColor.Text, lineHeight = 17.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VlColor.Accent,
                            unfocusedBorderColor = VlColor.Line,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { promptInput = pfCfg?.defaultSys.orEmpty() },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 4.dp, vertical = 0.dp,
                            ),
                        ) { Text("恢复默认", fontSize = 12.sp, color = VlColor.Sub) }
                        Spacer(Modifier.weight(1f))
                        Text("${promptInput.length} 字", fontSize = 10.sp, color = VlColor.Dim)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val txt = promptInput.trim()
                        val def = pfCfg?.defaultSys.orEmpty().trim()
                        if (txt.isNotEmpty() && !txt.contains("fix")) {
                            pfMsg = "提示词里必须保留输出格式说明（含 \"fix\"），否则结果解析不出来"
                        } else {
                            scope.launch {
                                /* 与默认一致就存空串：等价于「用默认」，
                                   默认文案将来改了会自动跟上，而不是被一份旧快照钉死 */
                                val payload = if (txt == def) "" else txt
                                pfMsg = "正在保存…"
                                val r = ProofreadClient.saveConfig(
                                    baseUrl = AppPrefs.cloudUrl(ctx),
                                    token = AppPrefs.cloudToken(ctx),
                                    sysPrompt = payload,
                                )
                                pfMsg = r.message
                                if (r.ok) {
                                    pfCfg = (pfCfg ?: ProofreadClient.Config())
                                        .copy(sysPrompt = payload)
                                    showPrompt = false
                                }
                            }
                        }
                    },
                ) { Text("保存", color = VlColor.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showPrompt = false }) {
                    Text("取消", color = VlColor.Sub)
                }
            },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VlColor.Surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = VlColor.Text)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ModelRow(name: String, ok: Boolean, size: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(6.dp).height(6.dp)
                .background(if (ok) VlColor.Green else VlColor.Red, RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(name, fontSize = 12.sp, color = VlColor.Text)
        Spacer(Modifier.weight(1f))
        Text(
            if (ok) size else "缺失",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = if (ok) VlColor.Dim else VlColor.Red,
        )
    }
}

// ---------------------------------------------------------------------- 工具

/**
 * 同步时间显示。
 *
 * 今天只给时分（"13:20"），不是今天才给月日 —— 因为用户看这个数字时关心的是
 * 「刚才那次点成功了吗」，跨天信息在这个场景里没有价值，反而占宽度。
 * 用「同一天」判断而不是「24 小时内」：凌晨看昨天的 23:00 不该显示成今天。
 */
private fun fmtClock(ms: Long): String {
    if (ms <= 0) return "—"
    val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.CHINA)
    val now = Date()
    val target = Date(ms)
    val pattern = if (dayFmt.format(now) == dayFmt.format(target)) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(pattern, Locale.CHINA).format(target)
}

/**
 * 查询当前可用的录音输入设备，返回 (设备id, 人话描述)。
 *
 * 为什么现查而不是缓存：拔插之后 id 会失效，缓存下来会给出一个点不动、
 * 且录音时静默退回内置麦的死选项 —— 那比不给选择更糟。
 */
private fun queryInputDevices(ctx: Context): List<Pair<Int, String>> = runCatching {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    /* 同一个物理麦克风会被系统上报**多次** —— 每种支持的采样率/通道组合各一条，
       id 不同但 type 和产品名完全一样。真机实测（Redmi 23113RKC6C）内置麦报了 2 条，
       界面上就是两个一模一样的「内置麦克风 · 23113RKC6C」，用户以为有得选，
       其实选哪条都走同一个物理麦。按 类型+产品名 合并，保留先出现的那条。
       取舍：两个同型号外接麦会被并成一条 —— 但那种情况下用户本来也分不出选哪个，
       而且本 App 同一时刻只能录一路，合并比给出两个一样的选项更无害。 */
    val seen = HashSet<String>()
    am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).mapNotNull { dev ->
        val type = when (dev.type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 设备"
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳麦"
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话麦"
            android.media.AudioDeviceInfo.TYPE_BLE_HEADSET -> "蓝牙 LE 麦"
            else -> null
        } ?: return@mapNotNull null
        /* 产品名对领夹麦很关键：DJI 的接收器会自带名字，
           光看「USB 设备」根本分不清插的是哪个 */
        val name = runCatching { dev.productName?.toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
        val desc = if (name != null) "$type · $name" else type
        if (!seen.add(desc)) return@mapNotNull null
        dev.id to desc
    }
}.getOrDefault(emptyList())

private fun isIgnoringBatteryOptimizations(ctx: Context): Boolean = runCatching {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    pm.isIgnoringBatteryOptimizations(ctx.packageName)
}.getOrDefault(false)

private fun openBatterySettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", ctx.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

/**
 * 导出全部为可直接粘进日记的纯文本，按会话分段。
 *
 * 一次 `all()` 把所有句子捞出来再按 sessionId 分组，而不是逐会话查一次：
 * 几千句的量级下，单次读 + 内存分组远快于 N 次查询。
 */
private fun exportAllAsText(ctx: Context): File? = runCatching {
    val db = VoiceLogDatabaseHolder.get(ctx)
    val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    val all = kotlinx.coroutines.runBlocking { db.transcriptDao().all() }

    val sb = StringBuilder()
    sb.appendLine("# 语音记录导出")
    sb.appendLine()
    sb.appendLine("导出时间: " + df.format(Date()))
    sb.appendLine("总条数: " + all.size)
    sb.appendLine()

    all.groupBy { it.sessionId }
        .toSortedMap()
        .forEach { (sid, items) ->
            val first = items.firstOrNull() ?: return@forEach
            sb.appendLine("## ${df.format(Date(first.wallClock))}  （${items.size} 句 · 会话 $sid）")
            /* 按 startMs 排，不能沿用 all() 的 id 序：拆行会插入 id 更大的记录，
               直接按 id 输出会把「后半句」印到整段最后一行去。 */
            items.sortedWith(compareBy({ it.startMs }, { it.id })).forEach { t ->
                val who = t.speakerName?.let { "[$it] " } ?: ""
                sb.appendLine("${fmtClockFull(t.wallClock)} $who${t.text}")
            }
            sb.appendLine()
        }

    val f = File(File(ctx.cacheDir, "exports"), "voicelog-${System.currentTimeMillis()}.txt")
    f.parentFile?.mkdirs()
    f.writeText(sb.toString())
    f
}.getOrNull()
