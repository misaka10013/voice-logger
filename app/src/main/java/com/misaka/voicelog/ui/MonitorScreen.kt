package com.misaka.voicelog.ui

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.misaka.voicelog.audio.TranscribeWorker
import com.misaka.voicelog.core.AppPrefs
import com.misaka.voicelog.core.ModelPaths
import com.misaka.voicelog.core.VoiceprintStore
import com.misaka.voicelog.service.CaptureService
import com.misaka.voicelog.service.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 监听页：只负责「开/停」与实时反馈。
 *
 * 刻意不放编辑能力 —— 边录边改会让人一直盯着屏幕核对，
 * 而这件事的正确时机是晚上回顾。这里只做到「确认它在工作」。
 */
@Composable
fun MonitorScreen(onOpenCurrentSession: (Long) -> Unit) {
    val ctx = LocalContext.current
    val meName = remember { VoiceprintStore.myName(ctx) }

    val running by SessionState.running.collectAsState()
    val status by SessionState.status.collectAsState()
    val items by SessionState.items.collectAsState()
    val speechMs by SessionState.totalSpeechMs.collectAsState()
    val segments by SessionState.totalSegments.collectAsState()
    val interrupts by SessionState.interruptCount.collectAsState()
    val lastInterrupt by SessionState.lastInterrupt.collectAsState()
    val queueDepth by SessionState.queueDepth.collectAsState()
    val error by SessionState.error.collectAsState()
    val meCount by SessionState.meCount.collectAsState()
    val otherCount by SessionState.otherCount.collectAsState()
    val vpActive by SessionState.voiceprintActive.collectAsState()
    val currentSession by SessionState.currentSessionId.collectAsState()
    val cachedSegs by SessionState.cachedSegments.collectAsState()
    val level by SessionState.level.collectAsState()
    val inputDevice by SessionState.inputDevice.collectAsState()

    /* 模式取自设置而不是 SessionState：未开始监听时 SessionState 是重置过的，
       若跟着它走，会出现「设置里关了实时转写，进页面还显示实时识别」的矛盾。 */
    var realtimeOn by remember { mutableStateOf(AppPrefs.realtimeAsr(ctx)) }
    val recordOnly = !realtimeOn

    val scope = rememberCoroutineScope()
    val txProgress by TranscribeWorker.state.collectAsState()

    var battery by remember { mutableIntStateOf(-1) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var modelsReady by remember { mutableStateOf(ModelPaths.ready(ctx)) }
    var spkModelReady by remember { mutableStateOf(ModelPaths.spkReady(ctx)) }

    LaunchedEffect(running) {
        while (true) {
            battery = readBattery(ctx)
            elapsed = if (running && SessionState.sessionStart.value > 0) {
                System.currentTimeMillis() - SessionState.sessionStart.value
            } else {
                0L
            }
            modelsReady = ModelPaths.ready(ctx)
            spkModelReady = ModelPaths.spkReady(ctx)
            // 从设置页改完开关返回时，这里要跟着变（否则界面还按旧模式渲染）
            realtimeOn = AppPrefs.realtimeAsr(ctx)
            delay(3000)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        Spacer(Modifier.height(14.dp))

        // ---------------- 状态卡 ----------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = VlColor.Surface),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(8.dp)
                            .height(8.dp)
                            .background(
                                if (running) VlColor.Green else VlColor.Dim,
                                RoundedCornerShape(4.dp),
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = status,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = VlColor.Text,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("运行", fmtDuration(elapsed))
                    /* 只录不转模式下没有「已识别」这回事，改显示已缓存段数；
                       两者混用会让用户以为转写在跑，其实只是存了音频。 */
                    if (recordOnly) {
                        Metric("已缓存", "$cachedSegs 段")
                        Metric("语音时长", fmtDuration(speechMs))
                    } else {
                        Metric("已识别", "$segments 段")
                        Metric("语音时长", fmtDuration(speechMs))
                    }
                    Metric("电量", if (battery < 0) "--" else "$battery%")
                }

                /* ---- 实时电平条 ----
                   解耦后不再实时刷文字，「它到底有没有在工作」失去了视觉证据。
                   这条条不是装饰，是唯一能证明麦克风还在收音的东西。 */
                if (running) {
                    Spacer(Modifier.height(10.dp))
                    LevelBar(level)
                }

                if (vpActive && (meCount > 0 || otherCount > 0)) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SpeakerChip(meName, 1, compact = true)
                        Spacer(Modifier.width(4.dp))
                        Text("$meCount", fontSize = 11.sp, color = VlColor.MeFg)
                        Spacer(Modifier.width(10.dp))
                        SpeakerChip("他人", 2, compact = true)
                        Spacer(Modifier.width(4.dp))
                        Text("$otherCount", fontSize = 11.sp, color = VlColor.OtherFg)
                    }
                }

                /* 录音来源必须常驻可见：接了外接麦之后，「这轮到底用的是哪个麦」
                   决定了说话人能不能靠音量差判出来。系统不会告诉用户，只能我们自己说。 */
                if (running && inputDevice.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "录音来源：$inputDevice",
                        fontSize = 10.sp,
                        color = VlColor.Sub,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }

                if (queueDepth > 0 || interrupts > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = buildString {
                            if (queueDepth > 0) append("转写队列 $queueDepth · ")
                            if (interrupts > 0) append("中断 $interrupts 次")
                            if (lastInterrupt.isNotBlank()) append("（$lastInterrupt）")
                        },
                        fontSize = 11.sp,
                        color = VlColor.Amber,
                    )
                }
            }
        }

        // ---------------- 准备状态检查 ----------------
        val warnings = buildList {
            if (!modelsReady) add("转写模型未就绪，缺少：" + ModelPaths.missing(ctx).joinToString(", "))
            if (!spkModelReady) add("声纹模型未就绪，本轮不做说话人判断（不影响转写）")
        }
        if (warnings.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color_WarnBg),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    warnings.forEachIndexed { i, w ->
                        if (i > 0) Spacer(Modifier.height(4.dp))
                        Text(w, fontSize = 11.sp, color = VlColor.Amber)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "目录：${ModelPaths.dir(ctx).absolutePath}",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = VlColor.Amber.copy(alpha = 0.6f),
                    )
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = VlColor.Red)
        }

        Spacer(Modifier.height(14.dp))

        // ---------------- 主按钮 ----------------
        Button(
            onClick = {
                if (running) CaptureService.stop(ctx) else CaptureService.start(ctx)
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) VlColor.Red else VlColor.Accent,
                contentColor = if (running) androidx.compose.ui.graphics.Color.White
                else androidx.compose.ui.graphics.Color(0xFF00204A),
            ),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                if (running) "停止监听" else "开始监听",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // ---------------- 本轮入口 ----------------
        if (currentSession > 0 && segments > 0) {
            TextButton(
                onClick = { onOpenCurrentSession(currentSession) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "查看本轮全部 $segments 句 · 标记与编辑",
                    fontSize = 13.sp,
                    color = VlColor.Accent,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        if (recordOnly) {
            // ------------------------------------------------ 只录不转模式
            SectionTitle("只录音，不转写")
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (running) {
                    "正在把检测到的人声存成音频（约 11MB/小时），文字等停止后点「转写」再生成。\n" +
                        "这样手机不必一直加载转写模型，耗电低得多。"
                } else {
                    "当前模式下监听只存音频、不加载转写模型。\n点「开始监听」录音，停止后可在这里转写。"
                },
                fontSize = 11.sp,
                color = VlColor.Sub,
                lineHeight = 17.sp,
            )

            if (txProgress.running) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = VlColor.Surface),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "正在转写 ${txProgress.done}/${txProgress.total}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = VlColor.Text,
                        )
                        Spacer(Modifier.height(6.dp))
                        LevelBar(txProgress.percent / 100f, tint = VlColor.Accent)
                        if (txProgress.lastText.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                txProgress.lastText,
                                fontSize = 12.sp,
                                color = VlColor.Sub,
                                maxLines = 2,
                            )
                        }
                    }
                }
            } else if (cachedSegs > 0 || currentSession > 0) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val sid = if (currentSession > 0) currentSession else TranscribeWorker.ALL_SESSIONS
                        scope.launch { runCatching { TranscribeWorker.run(ctx, sid) } }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VlColor.Accent,
                        contentColor = androidx.compose.ui.graphics.Color(0xFF00204A),
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        if (currentSession > 0) "转写本轮 $cachedSegs 段音频" else "转写全部待处理音频",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "转写在 App 前台执行，能看到进度；中途退出不会丢，未完成的段下次继续。",
                    fontSize = 10.sp,
                    color = VlColor.Dim,
                )
            }

            if (txProgress.finished && !txProgress.running && txProgress.total > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "上次转写：${txProgress.done} 段完成" +
                        if (txProgress.failed > 0) "，${txProgress.failed} 段失败（音频还在，可重试）" else "",
                    fontSize = 11.sp,
                    color = if (txProgress.failed > 0) VlColor.Amber else VlColor.Green,
                )
            }
        } else {
            // ------------------------------------------------ 实时转写模式（v2 老行为）
            SectionTitle("实时识别")

            if (items.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (running) "正在等待人声…" else "点「开始监听」后正常说话即可。",
                        fontSize = 12.sp,
                        color = VlColor.Dim,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.id to it.wallClock }) { item ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = fmtClockSec(item.wallClock),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = VlColor.Dim,
                                )
                                Spacer(Modifier.width(6.dp))
                                SpeakerChip(item.speakerName, item.speakerAuto, compact = true, meName = meName)
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(item.text, fontSize = 14.sp, color = VlColor.Text)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 电平条。输入是 0~1 的 RMS。
 *
 * 做了两点视觉处理，不是随手画的：
 * · 用平方根压缩动态范围 —— 线性映射下正常说话只到 10% 位置，看着像没声音；
 * · 分 24 格而不是一根连续条，格子跳动比连续条更容易一眼判断「有没有在收」。
 */
@Composable
private fun LevelBar(level: Float, tint: androidx.compose.ui.graphics.Color = VlColor.Green) {
    val shown = Math.sqrt(level.toDouble()).toFloat().coerceIn(0f, 1f)
    val cells = 24
    val lit = (shown * cells).toInt()
    Row(
        Modifier.fillMaxWidth().height(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 0 until cells) {
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .background(
                        if (i < lit) tint else VlColor.SurfaceVar,
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
    }
}

private val Color_WarnBg = androidx.compose.ui.graphics.Color(0xFF3A2418)

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = VlColor.Text)
        Text(label, fontSize = 10.sp, color = VlColor.Sub)
    }
}

fun fmtDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun fmtClockSec(wall: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(wall))

/**
 * 字节数 → 人能读的大小。
 *
 * 用 1024 进制而不是 1000：和系统设置里看到的一致，免得用户对不上数。
 * 只保留一位小数，且 0 显示为「0 B」而不是「0.0 MB」。
 */
fun fmtSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    return if (mb < 1024) "%.1f MB".format(mb) else "%.2f GB".format(mb / 1024.0)
}

private fun readBattery(ctx: Context): Int {
    val intent = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return -1
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return -1
    return level * 100 / scale
}
