package com.misaka.voicelog.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.misaka.voicelog.audio.AudioFileImporter
import com.misaka.voicelog.audio.AudioStore
import com.misaka.voicelog.audio.TranscribeWorker
import com.misaka.voicelog.core.RecordExporter
import com.misaka.voicelog.core.SyncManager
import com.misaka.voicelog.core.VoiceprintStore
import com.misaka.voicelog.data.SessionEntity
import com.misaka.voicelog.data.SessionPreview
import com.misaka.voicelog.data.SpeakerBucket
import com.misaka.voicelog.service.SessionState
import com.misaka.voicelog.service.VoiceLogDatabaseHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 记录页：所有历史会话，按轮次倒序。
 *
 * 为什么按「轮」而不是按「天」：一轮监听就是一个完整的场景边界
 * （一次会议、一趟通勤、一场谈话），按天聚合会把互不相关的对话揉成一坨。
 * 跨天的归属问题交给导出时按 wallClock 分组解决，不在列表层做。
 *
 * ## 删除为什么这么绕
 * 因为删对话记录是不可撤销的心理预期 —— 一旦误删又没有退路，用户就不敢再用。
 * 所以：长按进多选（避免单击误触）→ 弹窗先报清楚删掉什么 → 本地只打标记 →
 * 云端也只打标记（AI 默认读不到）→ 回收站里能恢复。
 * **任何一步都不做「一键清空」入口。**
 */
@Composable
fun RecordsScreen(onOpenSession: (Long, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val meName = remember { VoiceprintStore.myName(ctx) }
    val db = remember { VoiceLogDatabaseHolder.get(ctx) }
    val dao = remember { db.sessionDao() }
    val sessions by remember(meName, dao) { dao.previewsFlow(meName, 0L, 0L) }
        .collectAsState(initial = emptyList<SessionPreview>())

    /* 按标签名的句数分布。卡片上「说话人A 168 / 说话人B 53 / 未判 22」由它来，
       原来的 meCount/otherCount 只能分成「我 / 他人」两类，标了两个人也看不出来。 */
    val buckets by remember(dao) { dao.speakerBucketsFlow() }
        .collectAsState(initial = emptyList())
    val bucketsBySession = remember(buckets) { buckets.groupBy { it.sessionId } }

    var dayFilter by remember { mutableStateOf("") }        // "" = 全部
    /* 时期筛选：rangeFrom / rangeTo 是**本地自然日的起止毫秒**，取半开区间
       [from, to)。用半开而不是闭区间，是为了不用去算「那天的 23:59:59.999」
       这种边界 —— 少一个能算错的地方。0 表示没设。 */
    var rangeFrom by remember { mutableStateOf(0L) }
    var rangeTo by remember { mutableStateOf(0L) }
    var showRange by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    var askDelete by remember { mutableStateOf(false) }
    /* 「仅清空音频」：只删音频文件，文字全留。
       它的存在理由很具体——音频占空间（一小时约 60MB），但句子是要长期留的。
       删会话能把音频带走，可那会连文字一起进回收站，代价太大。 */
    var askClearAudio by remember { mutableStateOf(false) }
    var clearAudioBytes by remember { mutableStateOf(-1L) }
    var showTrash by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }

    /* 关键词检索。命中结果按「轮」聚合，点一条直接进那一轮、滚到第一个命中处。
       输入停顿 350ms 才查：每敲一个字就扫一遍全表，长会话一多会明显拖手感。 */
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<com.misaka.voicelog.data.SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        val kw = query.trim()
        if (kw.isEmpty()) {
            hits = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        hits = runCatching {
            withContext(Dispatchers.IO) {
                db.transcriptDao().search("%" + kw.replace("%", "").replace("_", "") + "%")
            }
        }.getOrDefault(emptyList())
        searching = false
    }

    /* 导入外部音频（录音笔、其他手机录的文件）。
       选文件用系统的内容选择器，不需要任何存储权限 —— 用户选了什么，
       我们才拿到那一个文件的临时读取权，比申请一堆权限干净得多。

       ⚠️ 用 GetContent 而不是 OpenDocument：我们拿到 Uri 后会立刻复制进私有目录，
       不需要「可持久化的 URI 授权」。而 OpenDocument 会把用户绑死在
       DocumentsUI 上 —— 某个 ROM 把这个组件限制住（比如小米应用锁），
       整个导入功能就直接不可用了。GetContent 能出相册、文件管理、网盘等多条路径。 */
    var importJob by remember { mutableStateOf<Job?>(null) }
    val imp by AudioFileImporter.state.collectAsState()

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            importJob = scope.launch {
                try {
                    val r = AudioFileImporter.import(ctx, uri)
                    AudioFileImporter.clear()
                    toast = "已导入 ${r.segments} 段语音" +
                        if (r.archivedByFileTime) "（按文件时间归档）" else "（按当前时间归档）"
                    onOpenSession(r.sessionId, "")
                } catch (e: CancellationException) {
                    AudioFileImporter.clear()
                    toast = "已取消导入"
                } catch (e: Exception) {
                    AudioFileImporter.clear()
                    toast = "导入失败：" + (e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /* 筛选状态只有一个来源：rangeFrom/rangeTo（半开区间）。dayFilter 是早期「点某一天」
       版本留下的，已无入口可设，留着只为兼容跳转时的清理调用。 */
    val shown = remember(sessions, dayFilter, rangeFrom, rangeTo) {
        when {
            rangeFrom > 0L && rangeTo > rangeFrom ->
                sessions.filter { it.startWall >= rangeFrom && it.startWall < rangeTo }
            dayFilter.isBlank() -> sessions
            else -> sessions.filter { dayKey(it.startWall) == dayFilter }
        }
    }

    val pickedSessions = remember(shown, picked) { shown.filter { picked.contains(it.id) } }
    val pickedItems = pickedSessions.sumOf { it.segmentCount }
    val pickedUnwritten = pickedSessions.sumOf { it.pendingSegments }

    LaunchedEffect(toast) {
        if (toast.isNotEmpty()) { delay(2600); toast = "" }
    }

    /* 多选状态下返回键先退出多选。回收站 / 删除确认是 AlertDialog，
       返回键由 Dialog 自己消费，不在这儿处理。 */
    BackHandler(enabled = selecting) {
        selecting = false
        picked = emptySet()
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("历史记录", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = VlColor.Text)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (selecting) "已选 ${picked.size} 轮 · ${pickedItems} 句"
                    else "共 ${sessions.size} 轮 · 长按可多选、导出或删除",
                    fontSize = 11.sp,
                    color = if (selecting) VlColor.Accent else VlColor.Sub,
                )
            }
            if (selecting) {
                TextButton(onClick = {
                    selecting = false; picked = emptySet()
                }) { Text("取消", fontSize = 12.sp, color = VlColor.Sub) }
            } else {
                TextButton(
                    onClick = { pickAudio.launch("*/*") },
                    enabled = imp == null,
                ) { Text("导入", fontSize = 12.sp, color = VlColor.Accent) }
                TextButton(onClick = { showTrash = true }) {
                    Text("回收站", fontSize = 12.sp, color = VlColor.Sub)
                }
            }
        }

        /* 筛选只留「时期」一个入口。
           原来这里还有「全部 / 昨天 / 11-07」一排单日快捷按钮，跟时期选择器功能重叠：
           点单日就是时期选择器点一下，点两天就是点两下。两套并存时用户分不清哪个在生效
           （选完时期，「全部」还高亮着），所以整排删掉。 */
        /* 检索框常驻，不藏在放大镜图标后面：
           会翻历史记录的人，十次里有八次是「找那句话」，而不是「随便看看」。 */
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text("搜句子内容，如「蜂蜜水」「加班」", fontSize = 12.sp, color = VlColor.Dim)
            },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = VlColor.Text),
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(
                        onClick = { query = "" },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) { Text("清除", fontSize = 11.sp, color = VlColor.Sub) }
                }
            },
            shape = RoundedCornerShape(10.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = VlColor.Accent,
                unfocusedBorderColor = VlColor.Line,
                cursorColor = VlColor.Accent,
            ),
        )

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DayChip(
                label = if (rangeFrom > 0L && rangeTo > rangeFrom) {
                    "${shortDay(dayKey(rangeFrom))} ~ ${shortDay(dayKey(rangeTo - 1L))}"
                } else {
                    "选时期"
                },
                count = if (rangeFrom > 0L) shown.size else -1,
                active = rangeFrom > 0L && rangeTo > rangeFrom,
            ) { showRange = true }
            if (rangeFrom > 0L && rangeTo > rangeFrom) {
                Spacer(Modifier.width(6.dp))
                TextButton(
                    onClick = {
                        rangeFrom = 0L
                        rangeTo = 0L
                        dayFilter = ""
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("清除", fontSize = 11.sp, color = VlColor.Sub)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        /* ---------------- 检索结果 ----------------
           结果按「轮」聚合而不是按句：用户找的是某次谈话，孤立的句子没有上下文价值。
           点一条直接进那一轮并定位到命中处，不再多垫一个中间页。 */
        val kw = query.trim()
        if (kw.isNotEmpty()) {
            when {
                searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("搜索中…", fontSize = 12.sp, color = VlColor.Dim)
                }

                hits.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有句子包含「$kw」", fontSize = 12.sp, color = VlColor.Dim)
                }

                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(
                            "命中 ${hits.size} 轮" + if (hits.size >= 100) "（只显示最近 100 轮）" else "",
                            fontSize = 11.sp,
                            color = VlColor.Sub,
                        )
                    }
                    items(hits, key = { "hit" + it.sessionId }) { h ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(VlColor.Surface, RoundedCornerShape(10.dp))
                                .clickable { onOpenSession(h.sessionId, kw) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    dayLabel(h.startWall) + "  " + fmtClockShort(h.startWall),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = VlColor.Sub,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = markedSnippet(h.snippet.orEmpty(), kw),
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = VlColor.Text,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("${h.cnt} 句", fontSize = 10.sp, color = VlColor.Accent)
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
            return@Column
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (sessions.isEmpty()) "还没有记录。\n去「监听」页开一轮试试。"
                    else "这一天没有记录。",
                    fontSize = 12.sp,
                    color = VlColor.Dim,
                )
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.id }) { s ->
                val on = picked.contains(s.id)
                SessionCard(
                    s = s,
                    meName = meName,
                    speakers = bucketsBySession[s.id].orEmpty(),
                    selecting = selecting,
                    picked = on,
                    onClick = {
                        if (selecting) {
                            picked = if (on) picked - s.id else picked + s.id
                            if (picked.isEmpty()) selecting = false
                        } else onOpenSession(s.id, "")
                    },
                    onLongClick = {
                        if (!selecting) { selecting = true; picked = setOf(s.id) }
                    },
                )
            }
            item { Spacer(Modifier.height(if (selecting) 76.dp else 12.dp)) }
        }
    }

    /* ---------------- 选择工具条（贴底，盖在列表上） ---------------- */
    if (selecting) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(VlColor.SurfaceVar)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                if (picked.size == shown.size) picked = emptySet() else picked = shown.map { it.id }.toSet()
            }) {
                Text(if (picked.size == shown.size) "取消全选" else "全选", fontSize = 12.sp, color = VlColor.Accent)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    toast = runCatching {
                        val f = RecordExporter.exportMarkdown(ctx, db, picked.toList())
                        shareFile(ctx, f)
                        "已导出 ${picked.size} 轮：${f.name}"
                    }.getOrElse { "导出失败：${it.message ?: it.javaClass.simpleName}" }
                }
            }) { Text("导出 md", fontSize = 12.sp, color = VlColor.Accent) }
            /* 空间不够又舍不得删文字时走这条。先算一遍能腾多少，再弹窗确认 */
            TextButton(onClick = {
                scope.launch {
                    clearAudioBytes = withContext(Dispatchers.IO) {
                        picked.sumOf { AudioStore.sessionBytes(ctx, it) }
                    }
                    askClearAudio = true
                }
            }) { Text("清空音频", fontSize = 12.sp, color = VlColor.Sub) }
            TextButton(onClick = { askDelete = true }) {
                Text("删除 ${picked.size} 轮", fontSize = 12.sp, color = VlColor.Red)
            }
        }
    }

    /* 提示必须有底色。不带背景直接铺在列表上，会跟卡片文字叠在一起 ——
       截图里就出现过「已在云端标记删除」和会话时间糊成一团的情况。 */
    if (toast.isNotEmpty()) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (selecting) 72.dp else 16.dp)
                .background(VlColor.SurfaceVar, RoundedCornerShape(10.dp))
                .border(1.dp, VlColor.Line, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(toast, fontSize = 11.sp, color = VlColor.Amber) }
    }

    }   // ← 关闭 Box

    /* ---------------- 删除预演 ---------------- */
    if (showRange) {
        RangePickerDialog(
            initialStart = rangeFrom,
            initialEnd = rangeTo,
            onDismiss = { showRange = false },
            onClear = {
                rangeFrom = 0L
                rangeTo = 0L
                dayFilter = ""
                showRange = false
            },
            onApply = { from, to ->
                rangeFrom = from
                rangeTo = to
                dayFilter = ""
                showRange = false
            },
        )
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            containerColor = VlColor.Surface,
            title = { Text("删除 ${picked.size} 轮对话？", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Column {
                    Text("$pickedItems 句文字将被标记删除。", fontSize = 12.sp, color = VlColor.Text)
                    Spacer(Modifier.height(8.dp))
                    Bullet("云端同步打同一份标记，外部 AI 默认读不到这些内容")
                    Bullet("7 天内在「回收站」可以恢复")
                    if (pickedUnwritten > 0) {
                        Bullet("其中 $pickedUnwritten 段音频还没转写，删掉文字后就再也转不出来了")
                    }
                    Bullet("音频一起进回收站，7 天后连文件一起彻底清除")
                    Bullet("期间恢复会话，音频还能接着回听")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = picked.toList()
                    /* 正在录的这一轮不能删。删了以后采集服务还在往里写段落和文字：
                       文件被删 → 句子落在界面上永远看不见；会话行被标删 → 恢复后是残缺的。
                       表现上就是「我明明删了它还在跑、还占空间」，极难自查。 */
                    val live = SessionState.currentSessionId.value
                    if (SessionState.running.value && live > 0L && live in ids) {
                        toast = "这一轮正在录音，先停止监听再删"
                        askDelete = false
                        return@TextButton
                    }
                    askDelete = false
                    selecting = false
                    picked = emptySet()
                    scope.launch {
                        val reply = runCatching { SyncManager.deleteSessions(ctx, db, ids) }.getOrNull()
                        toast = reply?.message?.takeIf { it.isNotBlank() } ?: "已删除 ${ids.size} 轮（本地）"
                    }
                }) { Text("删除", color = VlColor.Red) }
            },
            dismissButton = {
                TextButton(onClick = { askDelete = false }) { Text("取消", color = VlColor.Sub) }
            },
        )
    }

    /* ---------------- 仅清空音频 ----------------
       和删除会话的区别必须写在弹窗里：一个只掉素材，一个连文字一起掉。
       用户点错一次就是几百句记录进回收站，所以文案不能含糊。 */
    if (askClearAudio) {
        AlertDialog(
            onDismissRequest = { askClearAudio = false },
            containerColor = VlColor.Surface,
            title = { Text("清空 ${picked.size} 轮的音频？", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Column {
                    Text(
                        if (clearAudioBytes >= 0) "可释放约 ${fmtSize(clearAudioBytes)}。"
                        else "正在统计占用…",
                        fontSize = 12.sp,
                        color = VlColor.Text,
                    )
                    Spacer(Modifier.height(8.dp))
                    Bullet("$pickedItems 句文字全部保留，一条都不删")
                    Bullet("清掉后这些会话不能再重新转录、不能回听")
                    Bullet("云端记录不受影响")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = picked.toList()
                    /* 同删会话：正在录的那一轮音频正在被写，清掉它等于把正在录的东西删了 */
                    val live = SessionState.currentSessionId.value
                    if (SessionState.running.value && live > 0L && live in ids) {
                        toast = "这一轮正在录音，先停止监听再清空音频"
                        askClearAudio = false
                        return@TextButton
                    }
                    askClearAudio = false
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            var segs = 0
                            var freed = 0L
                            ids.forEach { sid ->
                                val (n, b) = TranscribeWorker.clearAudioOnly(ctx, sid)
                                segs += n
                                freed += b
                            }
                            segs to freed
                        }
                        toast = "已清空 ${r.first} 段音频，释放 ${fmtSize(r.second)}；文字都还在"
                        selecting = false
                        picked = emptySet()
                    }
                }) { Text("清空音频", color = VlColor.Red) }
            },
            dismissButton = {
                TextButton(onClick = { askClearAudio = false }) { Text("取消", color = VlColor.Sub) }
            },
        )
    }

    /* ---------------- 回收站 ---------------- */
    if (showTrash) {
        val deleted by remember { dao.deletedFlow() }.collectAsState(initial = emptyList<SessionEntity>())
        AlertDialog(
            onDismissRequest = { showTrash = false },
            containerColor = VlColor.Surface,
            title = { Text("回收站（${deleted.size}）", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                if (deleted.isEmpty()) {
                    Text("没有已删除的记录。", fontSize = 12.sp, color = VlColor.Dim)
                } else {
                    LazyColumn(Modifier.height(300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(deleted, key = { it.id }) { s ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(VlColor.SurfaceVar, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        dayLabel(s.startWall) + "  " + fmtClockShort(s.startWall),
                                        fontSize = 12.sp, color = VlColor.Text,
                                    )
                                    Text(
                                        "删除于 " + dayLabel(s.deletedAt) + " " + fmtClockShort(s.deletedAt),
                                        fontSize = 10.sp, color = VlColor.Dim,
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        val reply = runCatching {
                                            SyncManager.restoreSessions(ctx, db, listOf(s.id))
                                        }.getOrNull()
                                        toast = reply?.message?.takeIf { it.isNotBlank() } ?: "已恢复"
                                    }
                                }) { Text("恢复", fontSize = 11.sp, color = VlColor.Accent) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTrash = false }) { Text("关闭", color = VlColor.Sub) }
            },
        )
    }

    /* ---------------- 导入进度 ----------------
       进度必须看得见：一小时录音要跑一两分钟，中间没有反馈的话用户只会以为卡死了，
       然后强杀进程 —— 那才是真的丢东西。 */
    imp?.let { p ->
        AlertDialog(
            // 不响应点外部关闭：导入真在跑，关掉弹窗不会停止任务，只会让用户误以为停了
            onDismissRequest = { },
            containerColor = VlColor.Surface,
            title = { Text("导入音频", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Column {
                    Text(p.stage, fontSize = 12.sp, color = VlColor.Sub)
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(VlColor.SurfaceVar, RoundedCornerShape(3.dp)),
                    ) {
                        if (p.percent > 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth(p.percent / 100f)
                                    .height(6.dp)
                                    .background(VlColor.Accent, RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("已切出 ${p.segCount} 段语音", fontSize = 11.sp, color = VlColor.Dim)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "长录音需要几分钟，请留在本页。取消会丢弃这一轮，不留半截记录。",
                        fontSize = 10.sp,
                        color = VlColor.Dim,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { importJob?.cancel() }) {
                    Text("取消", color = VlColor.Red)
                }
            },
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(top = 4.dp)) {
        Text("· ", fontSize = 12.sp, color = VlColor.Dim)
        Text(text, fontSize = 12.sp, color = VlColor.Sub)
    }
}

@Composable
private fun DayChip(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(
                if (active) VlColor.Accent.copy(alpha = 0.16f) else VlColor.Surface,
                RoundedCornerShape(14.dp),
            )
            .border(
                1.dp,
                if (active) VlColor.Accent.copy(alpha = 0.55f) else VlColor.Line,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, color = if (active) VlColor.Accent else VlColor.Sub)
        /* count < 0 = 这个 chip 不显示数量（「选时期」未选中时没什么可显示的，
           显示一个总轮数反而会被误读成「时期内的轮数」）。 */
        if (count >= 0) {
            Spacer(Modifier.width(4.dp))
            Text("$count", fontSize = 10.sp, color = VlColor.Dim)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(
    s: SessionPreview,
    meName: String = "我",
    speakers: List<SpeakerBucket> = emptyList(),
    selecting: Boolean = false,
    picked: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val open = s.endWall <= 0L
    val duration = if (s.endWall > s.startWall) s.endWall - s.startWall else 0L

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (picked) Modifier.border(1.dp, VlColor.Accent, RoundedCornerShape(12.dp))
                else Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (picked) VlColor.Accent.copy(alpha = 0.08f) else VlColor.Surface,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selecting) {
                    Box(
                        Modifier
                            .width(16.dp).height(16.dp)
                            .background(
                                if (picked) VlColor.Accent else VlColor.SurfaceVar,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (picked) Text("✓", fontSize = 10.sp, color = VlColor.Bg)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = dayLabel(s.startWall),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = VlColor.Text,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${fmtClockShort(s.startWall)} – ${if (open) "未结束" else fmtClockShort(s.endWall)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (open) VlColor.Amber else VlColor.Sub,
                )
                Spacer(Modifier.weight(1f))
                /* 句数后面必须能看出「还有音频没消化」。
                   之前只在整轮一句文字都没有时才提示，于是"3 句成功 + 1 段失败"
                   在列表上和"全部转完了"长得一模一样，用户永远不知道少了一段。 */
                if (s.pendingSegments > 0) {
                    Text(
                        text = if (s.preview.isNullOrBlank()) {
                            "${s.pendingSegments} 段待转写"
                        } else {
                            "${s.textCount} 句 · ${s.pendingSegments} 段未转"
                        },
                        fontSize = 11.sp,
                        color = VlColor.Amber,
                    )
                } else {
                    /* 用 textCount（实际文字行数）而不是 segmentCount（音频段数）：
                       断句之后一行音频会被拆成好几行文字，两者不再相等。
                       原来写 segmentCount，卡片显示「221 句」而下面标签加起来是 336，
                       用户第一反应是统计坏了。 */
                    Text(
                        text = "${s.textCount} 句",
                        fontSize = 11.sp,
                        color = VlColor.Sub,
                    )
                }
            }

            val scene = s.scene?.takeIf { it.isNotBlank() }
            if (scene != null) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "场景：" + scene + (s.sceneNote?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""),
                    fontSize = 10.sp,
                    color = VlColor.NamedFg,
                )
            }

            if (!s.preview.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = s.preview.take(60) + if (s.preview.length > 60) "…" else "",
                    fontSize = 12.sp,
                    color = VlColor.Sub,
                    maxLines = 2,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                /* 按**标签名**逐个列出来：说话人A 168 / 说话人B 53 / 未判 22。
                   原来是「我 / 他人 / 未判」三档，用户标了两个人之后
                   两个人都会落进「他人」，看起来像统计错了。 */
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    speakers.forEach { b ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SpeakerChip(
                                name = b.speakerName,
                                auto = 0,
                                compact = true,
                                meName = meName,
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "${b.cnt}",
                                fontSize = 10.sp,
                                color = speakerCountColor(b.speakerName, meName),
                            )
                        }
                    }
                }
                if (duration > 0) {
                    Text(
                        text = "跨度 " + fmtDuration(duration),
                        fontSize = 10.sp,
                        color = VlColor.Dim,
                    )
                }
            }
        }
    }
}

/** 「今天 / 昨天 / 10月30日 周三」——比裸日期好定位，比系统相对时间好核对 */
fun dayLabel(wall: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = wall }

    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = run {
        val a = now.clone() as Calendar
        val b = then.clone() as Calendar
        a.set(Calendar.HOUR_OF_DAY, 0); a.set(Calendar.MINUTE, 0)
        a.set(Calendar.SECOND, 0); a.set(Calendar.MILLISECOND, 0)
        b.set(Calendar.HOUR_OF_DAY, 0); b.set(Calendar.MINUTE, 0)
        b.set(Calendar.SECOND, 0); b.set(Calendar.MILLISECOND, 0)
        ((a.timeInMillis - b.timeInMillis) / 86_400_000L).toInt()
    }

    return when {
        dayDiff == 0 -> "今天"
        dayDiff == 1 -> "昨天"
        sameYear -> SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(Date(wall))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(wall))
    }
}

fun fmtClockShort(wall: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(wall))

fun fmtClockFull(wall: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(wall))

/** 分组的键：同一自然日。用 yyyy-MM-dd 而不是时间戳，排序和比较都直观 */
private fun dayKey(wall: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(wall))

/** chips 上的短标签：今天 / 昨天 / 11-06 */
private fun shortDay(key: String): String {
    val today = dayKey(System.currentTimeMillis())
    val yesterday = dayKey(System.currentTimeMillis() - 86_400_000L)
    return when (key) {
        today -> "今天"
        yesterday -> "昨天"
        else -> key.substring(5)   // MM-dd
    }
}

/** 走 FileProvider + 系统分享面板：不申请任何存储权限，用户想存哪存哪 */
private fun shareFile(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "导出到").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(chooser)
}

/* ---------------------------------------------------------------- 时期筛选 */

/**
 * 「本地自然日零点」→「UTC 零点毫秒」。
 *
 * 为什么需要这一步：Material3 的 DateRangePicker 内部一律按 **UTC 自然日**
 * 计算选中值。我们存的是本地时区的时间戳，直接互传会让结果整体偏移 8 小时
 * （UTC+8）—— 表现为「选了 11 月 7 日，筛出来的是 11 月 6 日晚上到 7 日早上」。
 * 所以两个方向各要一次转换，且在日界上做，不在时刻上做。
 */
private fun localDayToUtcMillis(localMs: Long): Long =
    java.time.Instant.ofEpochMilli(localMs)
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcDayToLocalMillis(utcMs: Long): Long =
    java.time.Instant.ofEpochMilli(utcMs)
        .atZone(java.time.ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * 时期选择弹窗。
 *
 * 用自绘 Dialog + Surface 而不是 Material 的 `DatePickerDialog`：后者按内容定宽，
 * 而 DateRangePicker 在窄屏上会被压成两列挤在一起，日期格子小到点不准。
 * 这里固定 92% 高度、铺满宽度，保证日历本身占满可用空间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(
    initialStart: Long,
    initialEnd: Long,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onApply: (from: Long, to: Long) -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = if (initialStart > 0L) localDayToUtcMillis(initialStart) else null,
        // rangeTo 是半开区间的右端，展示给用户时要减一天才是他真正选的最后一天
        initialSelectedEndDateMillis = if (initialEnd > initialStart) localDayToUtcMillis(initialEnd - 86_400_000L) else null,
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(10.dp),
            shape = RoundedCornerShape(16.dp),
            color = VlColor.Surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "筛选时期",
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = VlColor.Text,
                )
                DateRangePicker(
                    state = state,
                    modifier = Modifier.weight(1f),
                    title = null,
                    headline = null,
                    showModeToggle = false,
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClear) {
                        Text("清除", fontSize = 13.sp, color = VlColor.Sub)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("取消", fontSize = 13.sp, color = VlColor.Sub)
                    }
                    val s = state.selectedStartDateMillis
                    val e = state.selectedEndDateMillis
                    TextButton(
                        onClick = {
                            if (s != null && e != null) {
                                /* 右端 +1 天：picker 给的是「最后一天的 UTC 零点」，
                                   而筛选用半开区间 [from, to)，to 要指到次日零点，
                                   否则最后一天的记录一条都进不来。 */
                                onApply(utcDayToLocalMillis(s), utcDayToLocalMillis(e) + 86_400_000L)
                            }
                        },
                        enabled = s != null && e != null,
                    ) {
                        Text(
                            "确定",
                            fontSize = 13.sp,
                            color = if (s != null && e != null) VlColor.Accent else VlColor.Dim,
                        )
                    }
                }
            }
        }
    }
}

/** 统计数字的颜色跟 chip 保持同一套语义，扫一眼就能对上哪个数是谁的 */
private fun speakerCountColor(name: String?, meName: String) = when {
    name == null -> VlColor.Dim
    name == meName -> VlColor.MeFg
    name == "他人" -> VlColor.OtherFg
    else -> VlColor.NamedFg
}

/**
 * 把检索结果里的关键词标出来，并只保留命中处前后一小段。
 *
 * 为什么要截：一句话可能有 80 个字，关键词在末尾时列表里根本看不见，
 * 用户只能看到一堆和搜索无关的开头。截到命中处，一行就能判断「是不是这轮」。
 */
private fun markedSnippet(snippet: String, kw: String): androidx.compose.ui.text.AnnotatedString {
    if (kw.isBlank() || snippet.isEmpty()) return androidx.compose.ui.text.AnnotatedString(snippet)

    val hit = snippet.indexOf(kw, ignoreCase = true)
    val from = if (hit > 18) hit - 18 else 0
    var body = if (from > 0) "…" + snippet.substring(from) else snippet
    if (body.length > 100) body = body.take(100) + "…"

    val b = androidx.compose.ui.text.AnnotatedString.Builder()
    var cursor = 0
    while (true) {
        val i = body.indexOf(kw, cursor, ignoreCase = true)
        if (i < 0) {
            b.append(body.substring(cursor))
            break
        }
        b.append(body.substring(cursor, i))
        b.withStyle(
            androidx.compose.ui.text.SpanStyle(
                color = VlColor.Accent,
                fontWeight = FontWeight.SemiBold,
            ),
        ) {
            append(body.substring(i, i + kw.length))
        }
        cursor = i + kw.length
    }
    return b.toAnnotatedString()
}
