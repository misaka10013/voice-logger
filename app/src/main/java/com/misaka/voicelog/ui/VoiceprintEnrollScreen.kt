package com.misaka.voicelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.misaka.voicelog.asr.SpeakerEngine
import com.misaka.voicelog.audio.BoundMicRecorder
import com.misaka.voicelog.audio.MicRoute
import com.misaka.voicelog.core.AppPrefs
import com.misaka.voicelog.core.ModelPaths
import com.misaka.voicelog.core.VoiceprintStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 声纹录入（支持多人）。
 *
 * 为什么用「念 4 句固定文本」而不是自由说话：
 *   · 固定文本能保证每句都覆盖到塞音、擦音、鼻音，embedding 才有区分度
 *   · 时长可控（每句 4.5 秒），不必做 VAD 切分，逻辑少一半
 *   · 用户知道什么时候该开口，不会出现"录了 5 秒全是环境音"
 *
 * 每句单独提取一个 embedding，全部保存而不是取平均 —— 见 VoiceprintStore 的说明。
 *
 * ⚠️ 必须和日常监听走**同一支麦**。实测（2026-11-11）同一个人跨麦相似度只有 0.25，
 * 与"陌生人"的 0.24 几乎无差别 —— 拿手机麦录的档案去比耳机麦的句子，等于没注册。
 * 所以录音走 [BoundMicRecorder]（跟着设置里绑定的麦走），并把通路键写进档案。
 */
private val PROMPTS = listOf(
    "今天天气不错，我准备出门走一走。",
    "这件事情需要仔细想一想再做决定。",
    "四川的火锅和串串，我都很喜欢吃。",
    "请把刚才说的那句话，再重复一遍。",
)

private const val RECORD_SECONDS = 4.5f

/** 一次录音的产物：音频 + 这条音频来自哪支麦 */
private class MicCapture(
    val pcm: FloatArray,
    val micKey: String,
    val micLabel: String,
    /** 需要提醒用户的情况（耳机没连、只有音乐通道…），正常时为空 */
    val note: String?,
)

/**
 * @param editProfileId 非空 = 重录已有的某个人（会覆盖其样本，名字沿用原值）
 */
@Composable
fun VoiceprintEnrollScreen(
    editProfileId: String? = null,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val existing = remember(editProfileId) {
        editProfileId?.let { VoiceprintStore.profileById(ctx, it) }
    }
    val allCount = remember { VoiceprintStore.profiles(ctx).size }
    val selfId = remember { VoiceprintStore.selfId(ctx) }

    val collected = remember { mutableStateListOf<FloatArray>() }
    var step by remember { mutableStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var consistency by remember { mutableStateOf<Float?>(null) }

    /* 本次录入实际走的麦。4 句必须来自同一条通路，否则拼出来的档案是混的 ——
       第 2 句换到耳机麦、第 3 句回到手机麦，这份档案跟谁都比不上。 */
    var micKey by remember { mutableStateOf<String?>(null) }
    var micLabel by remember { mutableStateOf("") }
    val boundName = remember { AppPrefs.inputDeviceName(ctx) }
    val noneBound = remember { AppPrefs.inputDeviceId(ctx) == 0 }

    // 名字先填好能省一次点击：新建时给出「我」或「说话人 2」，用户可以改
    var nameInput by remember {
        mutableStateOf(
            existing?.name ?: if (allCount == 0) VoiceprintStore.DEFAULT_NAME else "说话人 ${allCount + 1}",
        )
    }
    // 第一份档案默认就是「我」；重录「我」时锁定，重录别人时不改归属
    var asSelf by remember {
        mutableStateOf(
            when {
                existing != null -> selfId == existing.id
                else -> selfId == null
            },
        )
    }
    val selfLocked = existing != null && selfId == existing.id

    val spkReady = remember { ModelPaths.spkReady(ctx) }
    val overLimit = existing == null && allCount >= VoiceprintStore.MAX_PROFILES

    // 录制进度条：与真正的录音并行跑，纯视觉反馈
    LaunchedEffect(recording) {
        if (recording) {
            progress = 0f
            while (progress < 1f) {
                delay(90)
                progress = (progress + 0.09f / RECORD_SECONDS).coerceAtMost(1f)
            }
        } else {
            progress = 0f
        }
    }

    fun doRecord() {
        if (!spkReady) {
            message = "声纹模型未就绪，无法录入"
            messageIsError = true
            return
        }
        if (step >= PROMPTS.size) return
        if (overLimit) {
            message = "已达 ${VoiceprintStore.MAX_PROFILES} 人上限，请先删掉不用的"
            messageIsError = true
            return
        }
        recording = true
        message = null
        scope.launch {
            var failMsg: String? = null
            val cap = withContext(Dispatchers.IO) {
                val mic = BoundMicRecorder(ctx)
                try {
                    if (!mic.open()) {
                        failMsg = mic.note ?: "麦克风打不开"
                        return@withContext null
                    }
                    /* 蓝牙必须先等系统把通话通道建起来再采。
                       小米实测 5~8 秒（Pixel 1~2 秒），给 10 秒。
                       这一步在 open() 之后：系统只在已开始录音后才处理路由请求。 */
                    if (mic.wantsBluetooth && !mic.awaitBluetoothRoute(10_000L)) {
                        failMsg = mic.note ?: "蓝牙麦克风没接通"
                        return@withContext null
                    }
                    val pcm = mic.capture(RECORD_SECONDS)
                    if (pcm == null) {
                        failMsg = "没录到声音。检查麦克风是否被其他应用（通话、录音）占用。"
                        return@withContext null
                    }
                    MicCapture(pcm, mic.micKey, mic.displayName, mic.note)
                } finally {
                    mic.close()
                }
            }

            if (cap == null) {
                recording = false
                message = failMsg ?: "录音失败"
                messageIsError = true
                return@launch
            }

            /* 4 句必须同源。中途拔耳机 / 蓝牙断连会悄悄换麦，
               这是最容易发生、也最难事后发现的污染源 —— 当场作废这一句。 */
            val prevKey = micKey
            if (prevKey == null) {
                micKey = cap.micKey
                micLabel = cap.micLabel
            } else if (prevKey != cap.micKey) {
                recording = false
                message = "这一句的音源换成了「${MicRoute.keyLabel(cap.micKey)}」，和前面不是同一支麦，" +
                    "已作废。请保持麦克风不变，重录这一句。"
                messageIsError = true
                return@launch
            }

            val emb = withContext(Dispatchers.IO) {
                val engine = SpeakerEngine(ModelPaths.spkModel(ctx))
                try {
                    if (!engine.ready) null else engine.embed(cap.pcm)
                } finally {
                    engine.release()
                }
            }
            recording = false
            if (emb == null) {
                message = "这句话太短或太小声，没能提取出特征，请靠近一点重录。"
                messageIsError = true
                return@launch
            }
            collected.add(emb)
            /* 录成功了但通路不是用户以为的那支（耳机没连、只有音乐通道）——
               必须说出来。不说的话他会以为档案绑在耳机上，戴耳机录音时
               一句都认不出来，还查不出原因。 */
            if (cap.note != null) {
                message = "第 ${collected.size} 句录到了，但注意：${cap.note}"
                messageIsError = true
            } else {
                message = "第 ${collected.size} 句录入成功"
                messageIsError = false
            }

            if (collected.size >= PROMPTS.size) {
                val samples = collected.toList()
                val id = existing?.id ?: VoiceprintStore.newId()
                val key = micKey
                /* 通路没确定就不许存档案。存一份「不知道用哪支麦录的」档案，
                   判定时要么永远用不上、要么在错误的通路上瞎匹配 —— 两种都比没有更糟。 */
                if (key == null || key == MicRoute.KEY_UNKNOWN) {
                    finished = false
                    message = "没能确定这次用的是哪支麦克风，档案不可靠，请重新录一遍。"
                    messageIsError = true
                    collected.clear()
                    step = 0
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    VoiceprintStore.saveProfile(ctx, id, nameInput, samples, key)
                    if (asSelf) VoiceprintStore.setSelfId(ctx, id)
                }
                consistency = VoiceprintStore.consistency(samples)
                finished = true
            } else {
                step = collected.size
            }
        }
    }

    /* 同 SessionDetailScreen：这两个页面不走 Scaffold，必须自己让开状态栏，
       否则标题和返回按钮会被状态栏的下拉手势区吃掉，点不动。 */
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) {
                Text("← 返回", fontSize = 14.sp, color = VlColor.Accent)
            }
            Spacer(Modifier.weight(1f))
            Text("${collected.size} / ${PROMPTS.size}", fontSize = 12.sp, color = VlColor.Sub)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            if (existing != null) "重录「${existing.name}」的声纹" else "录入一个人的声纹",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = VlColor.Text,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "请让本人在安静室内，用平时说话的语速和音量念下面 4 句。每句约 4.5 秒。",
            fontSize = 12.sp,
            color = VlColor.Sub,
            lineHeight = 18.sp,
        )

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------- 用哪支麦录
        if (!finished) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = VlColor.Surface),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("这次用的麦克风", fontSize = 11.sp, color = VlColor.Sub)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            micLabel.isNotBlank() -> micLabel
                            noneBound -> "还没绑定麦克风 —— 会用手机内置麦"
                            else -> "绑定的「$boundName」（录音时确认实际通路）"
                        },
                        fontSize = 13.sp,
                        color = if (noneBound) VlColor.Amber else VlColor.Text,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "声纹只在这支麦上有效：同一句话换个麦克风，程序听起来就像换了一个人。" +
                            "所以换麦之后必须重新录一遍。",
                        fontSize = 10.sp,
                        color = VlColor.Dim,
                        lineHeight = 15.sp,
                    )
                    val oldKey = existing?.micKey
                    if (oldKey != null && micKey == null && oldKey != MicRoute.KEY_UNKNOWN) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "原来这份是用「${MicRoute.keyLabel(oldKey)}」录的，重录后会覆盖掉。",
                            fontSize = 10.sp,
                            color = VlColor.Amber,
                            lineHeight = 15.sp,
                        )
                    }
                    if (noneBound) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "建议先去「设置 → 录音输入」绑定你平时用的那支麦，再回来录。",
                            fontSize = 10.sp,
                            color = VlColor.Amber,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ---------------------------------------------- 名字与归属
        if (!finished) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = VlColor.Surface),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("这是谁", fontSize = 12.sp, color = VlColor.Sub)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it.take(12) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("如「老婆」「小王」", fontSize = 13.sp, color = VlColor.Dim) },
                        textStyle = TextStyle(fontSize = 14.sp, color = VlColor.Text),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VlColor.Accent,
                            unfocusedBorderColor = VlColor.Line,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = asSelf,
                            onCheckedChange = { if (!selfLocked) asSelf = it },
                            enabled = !selfLocked,
                            colors = CheckboxDefaults.colors(
                                checkedColor = VlColor.Accent,
                                uncheckedColor = VlColor.Dim,
                            ),
                        )
                        Column {
                            Text(
                                if (selfLocked) "这是「我」本人" else "标记为「我」本人",
                                fontSize = 13.sp,
                                color = VlColor.Text,
                            )
                            Text(
                                if (selfLocked) "本人声纹不能改归属，如需更换请另建一个「我」的档案"
                                else "勾选后，判到此人的句子在统计里计入「我」",
                                fontSize = 10.sp,
                                color = VlColor.Dim,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        if (!finished) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = VlColor.Surface),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("第 ${step + 1} 句", fontSize = 11.sp, color = VlColor.Accent)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        PROMPTS[step.coerceIn(0, PROMPTS.lastIndex)],
                        fontSize = 17.sp,
                        color = VlColor.Text,
                        lineHeight = 26.sp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (recording) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = VlColor.Accent,
                    trackColor = VlColor.Line,
                )
                Spacer(Modifier.height(8.dp))
                Text("正在录制，请念这句话…", fontSize = 12.sp, color = VlColor.Accent)
                Spacer(Modifier.height(4.dp))
                Text(
                    "用蓝牙耳机时，开头要等几秒把通话通道建起来，这几秒是正常的，别停。",
                    fontSize = 10.sp,
                    color = VlColor.Dim,
                    lineHeight = 14.sp,
                )
            } else {
                Button(
                    onClick = { doRecord() },
                    enabled = spkReady && nameInput.isNotBlank() && !overLimit,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VlColor.Accent,
                        contentColor = androidx.compose.ui.graphics.Color(0xFF00204A),
                        disabledContainerColor = VlColor.SurfaceVar,
                        disabledContentColor = VlColor.Dim,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        when {
                            collected.isEmpty() -> "开始录制第 1 句"
                            else -> "录制第 ${collected.size + 1} 句"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (!spkReady) {
                Text(
                    "声纹模型 spk_campplus.onnx 未就绪，请先推到应用模型目录。",
                    fontSize = 11.sp,
                    color = VlColor.Amber,
                )
            }
            if (overLimit) {
                Text(
                    "最多 ${VoiceprintStore.MAX_PROFILES} 人。先在设置里删掉不用的档案。",
                    fontSize = 11.sp,
                    color = VlColor.Amber,
                )
            }

            message?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    fontSize = 12.sp,
                    color = if (messageIsError) VlColor.Red else VlColor.Green,
                )
            }
        } else {
            // ---------------- 完成 ----------------
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = VlColor.Surface),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "「${nameInput.trim()}」录入完成",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = VlColor.Green,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("已保存 ${collected.size} 个声纹样本", fontSize = 12.sp, color = VlColor.Sub)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "绑在「${MicRoute.keyLabel(micKey)}」上 —— 之后只有用这支麦录的对话才会认这份档案。",
                        fontSize = 11.sp,
                        color = VlColor.Accent,
                        lineHeight = 16.sp,
                    )

                    Spacer(Modifier.height(12.dp))
                    val c = consistency
                    if (c != null) {
                        val (txt, color) = when {
                            c >= 0.70f -> "样本一致性 %.2f —— 良好，可以开始用了".format(c) to VlColor.Green
                            c >= 0.55f -> "样本一致性 %.2f —— 一般，实际效果不好可以重录".format(c) to VlColor.Amber
                            else -> "样本一致性 %.2f —— 偏低，四句差异太大，建议重录".format(c) to VlColor.Red
                        }
                        Text(txt, fontSize = 12.sp, color = color, lineHeight = 18.sp)
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "「样本一致性」是 4 段声纹两两之间的平均相似度。" +
                            "太低说明录音环境噪声大，或本人每句的语速/音高差异过大。",
                        fontSize = 10.sp,
                        color = VlColor.Dim,
                        lineHeight = 15.sp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = {
                        collected.clear()
                        step = 0
                        finished = false
                        consistency = null
                        message = null
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("重录", fontSize = 14.sp, color = VlColor.Sub)
                }
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1.6f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VlColor.Accent,
                        contentColor = androidx.compose.ui.graphics.Color(0xFF00204A),
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("完成", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = "提示：监听中录音会抢占麦克风，请先在「监听」页停止再录声纹。",
            fontSize = 10.sp,
            color = VlColor.Dim,
            lineHeight = 15.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}
