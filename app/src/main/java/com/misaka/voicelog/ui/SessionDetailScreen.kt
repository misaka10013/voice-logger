package com.misaka.voicelog.ui

import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.misaka.voicelog.audio.AudioStore
import com.misaka.voicelog.audio.TranscribeWorker
import com.misaka.voicelog.core.AppPrefs
import com.misaka.voicelog.core.PersonStore
import com.misaka.voicelog.core.PersonTags
import com.misaka.voicelog.core.ProofreadManager
import com.misaka.voicelog.core.SpeakerUndo
import com.misaka.voicelog.core.VoiceprintStore
import com.misaka.voicelog.data.SegmentTally
import com.misaka.voicelog.data.InterruptEntity
import com.misaka.voicelog.data.SessionEntity
import com.misaka.voicelog.data.TranscriptEntity
import com.misaka.voicelog.data.applySpeakerEdit
import com.misaka.voicelog.data.applyTextEdit
import com.misaka.voicelog.data.mergeTranscriptLine
import com.misaka.voicelog.data.resplitSessionLines
import com.misaka.voicelog.data.splitTranscriptLineAt
import com.misaka.voicelog.service.SessionState
import com.misaka.voicelog.service.VoiceLogDatabaseHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话详情：一轮监听的全部句子。
 *
 * 三种操作共用同一份列表，靠「多选模式」区分：
 *   · 普通模式：点一行 = 打开编辑
 *   · 多选模式：点一行 = 勾选，底部出现批量操作栏
 *
 * 为什么不做成长按多选：长按在滚动列表里容易误触，而且和系统的文本选择手势打架。
 * 一个显式的「多选」按钮更笨，但不会出错。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
    /**
     * 从检索结果进来时要高亮并定位的关键词。空 = 正常打开。
     *
     * 为什么不另做一个「搜索结果页」：结果页只能看到片段，判断相关性还得点进去；
     * 直接进这一轮、滚到第一个命中处，用户立刻能读上下文 —— 少一次跳转。
     */
    highlight: String = "",
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val db = remember { VoiceLogDatabaseHolder.get(ctx) }

    val transcripts by remember(sessionId) {
        db.transcriptDao().bySession(sessionId)
    }.collectAsState(initial = emptyList())

    var session by remember { mutableStateOf<SessionEntity?>(null) }

    /**
     * 这一轮期间的录音中断。
     *
     * 为什么必须显示：2026-11-15 实测有一轮被别的应用占用 153 分钟，音频**完全没录到**，
     * 而界面上没有任何痕迹 —— 用户只看到「句子时间突然跳跃」，无法判断是
     * 「那段时间没人说话」还是「录音坏了」。这两件事的处置方式完全不同：
     * 前者什么都不用做，后者得去查谁抢了麦克风。
     */
    val interrupts by remember(sessionId, session?.startWall, session?.endWall) {
        val s = session
        if (s == null) {
            kotlinx.coroutines.flow.flowOf(emptyList())
        } else {
            db.interruptDao().inRange(
                from = s.startWall,
                to = if (s.endWall > 0L) s.endWall else System.currentTimeMillis(),
            )
        }
    }.collectAsState(initial = emptyList())
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var editing by remember { mutableStateOf<TranscriptEntity?>(null) }
    var bulkSpeaker by remember { mutableStateOf(false) }
    var knownSpeakers by remember { mutableStateOf<List<String>>(emptyList()) }

    /* 批删确认。删句是软删、能恢复，但一口气删错十几句还是要进回收站翻 ——
       一次确认的成本远低于找回的成本。 */
    var deleteConfirm by remember { mutableStateOf(false) }

    /* A/B 交替标注的弹窗与两个名字。不再要求「先手动标好前两行」——
       350 行全显示「未判」时那个前置条件等于把功能锁死。 */
    var abDialog by remember { mutableStateOf(false) }
    /* A/B 名字从设置里读固定值（默认说话人A/说话人B），不再每轮重置成「我/对方」。
       之前每进一次听标都要手改一遍两个名字，一轮下来光这个就几十次点击。 */
    var abA by remember { mutableStateOf(AppPrefs.abA(ctx)) }
    var abB by remember { mutableStateOf(AppPrefs.abB(ctx)) }
    /** 顶栏菜单展开态。断句/复制/撤销都是低频动作，塞进菜单才腾得出「听标」的位置 */
    var menuOpen by remember { mutableStateOf(false) }
    /**
     * 列表筛选。「AI 建议」是最常用的一个 ——
     * 322 句里可能只有 5 条待确认，原来只能一屏屏翻着找，翻完还不知道有没有找漏。
     */
    var filter by remember(sessionId) { mutableStateOf(RowFilter.ALL) }
    /** 有可撤销的 A/B 交替快照 */
    var canUndoAb by remember(sessionId) { mutableStateOf(false) }

    /* 会话头折叠。默认只留「日期 · 时间 · 句数」+ 统计条两行，
       来源/音频/重新转录/AI 校对收进展开区 —— 350 句的会话里这些行
       常驻会吃掉近半屏的列表空间，而它们一天也用不上几次。 */
    var headerExpanded by remember(sessionId) { mutableStateOf(false) }

    /** 拆行后要把输入焦点落到新生成的那一行，否则用户拆完还得再点一次 */
    var focusId by remember { mutableStateOf(0L) }

    /* 列表滚动状态。存在的唯一理由：拆行/并行之后必须滚到目标行。
       列表的滚动位置是按「第几项」记的，行数一变，眼前这一屏就换内容了 ——
       376 行的会话里拆一句，用户得自己往下翻十几屏才能找到刚拆出来的那半句。 */
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    /* ---- 长按刷选 / 范围连选 ----
       锚点 tapAnchorIdx = 最近一次点击（或上次刷选结束）的行下标。
       · 点击行：勾选该行，并把锚点移过去（普通用法不变）
       · 长按拖动：从锚点到手指当前行的区间全部勾选 —— 点第 1 行，拖/长按到第 20 行，
         前 20 行全选，不需要一屏屏翻。 */
    var tapAnchorIdx by remember { mutableStateOf(-1) }
    var dragLastIdx by remember { mutableStateOf(-1) }
    /** 刷选手势里读列表必须拿最新值：pointerInput 不随重组重启，直接捕获会读到旧列表 */
    val shownRef = remember { mutableStateOf<List<TranscriptEntity>>(emptyList()) }

    /** 手指 y 坐标 → 可见项下标。用「中心最近」而不是区间命中，避免 contentPadding 偏差 */
    fun itemIdxAt(y: Float): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        listState.layoutInfo.visibleItemsInfo.forEach { info ->
            val d = kotlin.math.abs(info.offset + info.size / 2f - y)
            if (d < bestDist) { bestDist = d; best = info.index }
        }
        return best
    }

    /** 把锚点行到 [idx] 之间的全部行勾上。筛选态下按当前列表的下标算 */
    fun rangeSelectTo(idx: Int) {
        if (idx < 0) return
        val list = shownRef.value
        if (idx >= list.size) return
        if (tapAnchorIdx < 0) tapAnchorIdx = idx
        val lo = minOf(tapAnchorIdx, idx).coerceAtLeast(0)
        val hi = maxOf(tapAnchorIdx, idx).coerceAtMost(list.size - 1)
        if (hi < lo) return
        selected = selected + list.subList(lo, hi + 1).map { it.id }.toSet()
        selectionMode = true
    }
    /** 单击说话人标签时的轻量选择器（比打开整个编辑弹窗快一步） */
    var speakerTarget by remember { mutableStateOf<TranscriptEntity?>(null) }

    /** 本轮段落状态分布：待转 / 失败 / 已完成。失败段必须算进来 ——
     *  它是"少了一段却不知道为什么"的唯一线索。 */
    val tally by remember(sessionId) {
        db.segmentDao().tallyFlow(sessionId)
    }.collectAsState(initial = SegmentTally())
    val pendingSegs = tally.pending

    /** 失败段里有多少是「音频已经不在磁盘上」的。点了重试才知道的话太晚，
     *  这里在渲染后就探一次，好把「清理」入口直接摆出来 */
    var deadSegs by remember(sessionId) { mutableStateOf(0) }

    /* 「重新转录」前的盘点结果（音频段数/字节数/会被清空的文字数）。
       扫文件系统有成本，所以只在段落统计变化时算一次，不放进重组。 */
    var rePlan by remember(sessionId) { mutableStateOf<TranscribeWorker.RetranscribePlan?>(null) }
    var reConfirm by remember(sessionId) { mutableStateOf(false) }
    /** 重新校对的确认弹窗。跑过一次之后再点就是「重跑」，同样要花一次钱 */
    var proofReConfirm by remember(sessionId) { mutableStateOf(false) }

    /** 当前正在播放的段落 id，-1 = 没在播 */
    var playingSeg by remember { mutableStateOf(-1L) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    /* ---------------- 听标模式 ----------------
       标人真正费时的不是「判断谁说的」，是每句要「点播放→听→关掉→点标签」四个动作。
       听标把播放和标注缝在一起：播完自动跳下一行，点 A/B 就自动前进，只剩一个动作。 */
    var listenMode by remember { mutableStateOf(false) }
    /** 听标当前停在哪一行 */
    var listenId by remember { mutableStateOf(0L) }
    /** 播完是否自动跳下一行。关掉就纯手动，适合需要反复回听某一句的时候 */
    var listenAuto by remember { mutableStateOf(true) }
    /** 最近播放过的段 id：拆出来的多行共用一段音频，自动前进时不该把同一段播三遍 */
    var lastPlayedSeg by remember { mutableStateOf(-1L) }

    // 离开页面必须释放播放器，否则会一直占着音频焦点
    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }
    // 用户在设置里预置的常用人名，作为选人时的第一优先级快捷项
    var tags by remember { mutableStateOf(PersonTags.list(ctx)) }
    val meName = remember { VoiceprintStore.myName(ctx) }

    /* AI 校对的状态。它是进程级单例 —— 用户点完校对直接退出页面，
       协程也不会被取消，回来时进度还在。 */
    val pf by ProofreadManager.state.collectAsState()

    /* 转写进度。只为了两件事：转写中禁掉「重新转录」、以及提示用户先等它跑完。
       必须走 collectAsState —— 直接读 state.value 在重组时不会刷新。 */
    val tx by TranscribeWorker.state.collectAsState()
    val txRunning = tx.running

    suspend fun refreshSpeakers() {
        knownSpeakers = withContext(Dispatchers.IO) { db.transcriptDao().knownSpeakers() }
        tags = PersonTags.list(ctx)
    }

    LaunchedEffect(sessionId) {
        session = withContext(Dispatchers.IO) { db.sessionDao().byId(sessionId) }
        refreshSpeakers()
        ProofreadManager.refresh(db, sessionId)
        canUndoAb = SpeakerUndo.has(ctx, sessionId)
    }

    /* 建议数量变化后刷新一次计数（采纳/忽略都会让 pending 变），
       这样统计条上的数字不会停在校对完成那一刻。 */
    LaunchedEffect(transcripts.size, transcripts.count { it.proofText != null }) {
        ProofreadManager.refresh(db, sessionId)
    }

    /* 失败段数变化时探一次「其中多少段的音频真的没了」。
       只在数字变了才查，避免每次重组都扫一遍文件系统。 */
    LaunchedEffect(sessionId, tally.failed) {
        deadSegs = if (tally.failed > 0) {
            runCatching { TranscribeWorker.countUnrecoverable(ctx, sessionId) }.getOrDefault(0)
        } else {
            0
        }
    }

    /* 段落状态一变就重盘一次「还有多少音频能重转」。
       转写过程中段落状态会连续变化（每转完一段就变一次），此时盘点没有意义——
       按钮本来就因为「转写中」禁着。跳过这一档，避免几百段规模下把主线程以外的
       IO 排队堆满；转完之后那一刻仍需重算，而那时 tally 还会再变一次。 */
    LaunchedEffect(sessionId, tally.pending, tally.done, tally.failed) {
        if (txRunning) return@LaunchedEffect
        rePlan = runCatching { TranscribeWorker.planRetranscribe(ctx, sessionId) }.getOrNull()
    }

    // 退出多选时清空选中，避免下次进来还残留上一次的选择
    LaunchedEffect(selectionMode) {
        if (!selectionMode) {
            selected = emptySet()
            tapAnchorIdx = -1
        }
    }

    /* 拆/并之后滚到目标行。等 transcripts 变化再找：拆行是「先插库、再刷新列表」，
       列表还没更新时按 id 找会找不到，白等一次。 */
    LaunchedEffect(focusId, transcripts.size) {
        if (focusId <= 0L) return@LaunchedEffect
        val idx = transcripts.indexOfFirst { it.id == focusId }
        if (idx >= 0) runCatching { listState.animateScrollToItem(idx) }
    }

    /* 从检索结果进来：滚到第一个命中的句子。只跳一次 ——
       用户开始往下读之后，任何重组都不该把他拽回去。 */
    var jumpedToHit by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId, highlight, transcripts.size) {
        if (jumpedToHit || highlight.isBlank() || transcripts.isEmpty()) return@LaunchedEffect
        val idx = transcripts.indexOfFirst { it.text.contains(highlight, ignoreCase = true) }
        if (idx >= 0) {
            runCatching { listState.animateScrollToItem(idx) }
            jumpedToHit = true
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun copyCurrent(ids: Set<Long>?) {
        val list = if (ids == null) transcripts else transcripts.filter { it.id in ids }
        if (list.isEmpty()) return
        val text = list.joinToString("\n") { t ->
            val who = t.speakerName?.let { "[$it] " } ?: ""
            "${fmtClockFull(t.wallClock)} $who${t.text}"
        }
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(ctx, "已复制 ${list.size} 句", Toast.LENGTH_SHORT).show()
    }

    /* 找这一句对应的音频文件。主路径用 segment.filePath；
       为空或文件不在时按 audio/<sessionId>/<segmentId>.m4a 推算 ——
       文件名本来就是拿 segment.id 命名的，这是约定不是猜测。 */
    suspend fun audioFileOf(t: TranscriptEntity): java.io.File? = withContext(Dispatchers.IO) {
        if (t.segmentId <= 0L) return@withContext null
        val seg = db.segmentDao().byId(t.segmentId)
        val direct = seg?.filePath?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
        if (direct != null && direct.exists() && direct.length() > 0L) return@withContext direct
        val guess = AudioStore.fileFor(ctx, t.sessionId, t.segmentId)
        if (guess.exists() && guess.length() > 0L) guess else null
    }

    /**
     * 播放这一行对应的音频。
     *
     * @param onDone 播完（或压根没播成）时回调。听标模式靠它在播完后自动跳下一行；
     *               失败也回调，否则一句播不出来整个听标流程就卡死在那里。
     */
    fun playSegment(t: TranscriptEntity, onDone: (() -> Unit)? = null) {
        scope.launch {
            val file = audioFileOf(t)
            if (file == null) {
                Toast.makeText(ctx, "这一段的音频已不在本地", Toast.LENGTH_SHORT).show()
                onDone?.invoke()
                return@launch
            }
            runCatching {
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener {
                        playingSeg = -1L
                        onDone?.invoke()
                    }
                }
                playingSeg = t.segmentId
                lastPlayedSeg = t.segmentId
            }.onFailure {
                playingSeg = -1L
                player = null
                Toast.makeText(ctx, "播放失败：${it.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
                onDone?.invoke()
            }
        }
    }

    fun stopPlayback() {
        runCatching { player?.release() }
        player = null
        playingSeg = -1L
    }

    /* ---------------- 听标流程 ----------------
       退出条件只有三个：走到头、用户点退出、列表被外部改动（找不到那一行）。
       任何一种都要把播放器停掉，否则退出页面后声音还在响。 */
    fun listenStop() {
        listenMode = false
        listenId = 0L
        stopPlayback()
    }

    /**
     * 跳到某一行并（可选）播放。
     *
     * @param skipReplay 自动前进时传 true：拆行会让连续几行共用同一段音频，
     *                   同一段播完再播一遍纯属浪费，直接停在新行等用户标。
     */
    fun listenGoById(id: Long, autoplay: Boolean, skipReplay: Boolean) {
        val idx = transcripts.indexOfFirst { it.id == id }
        if (idx < 0) { listenStop(); return }
        val t = transcripts[idx]
        listenId = t.id
        scope.launch { runCatching { listState.animateScrollToItem(idx) } }
        if (!autoplay) return

        val advance = {
            if (listenAuto) {
                /* 播放要几秒，这期间列表可能被改过（删句、重新断句）。
                   所以这里按 id 重新定位一次，不能复用上面算好的 idx。 */
                val cur = transcripts.indexOfFirst { it.id == id }
                val next = if (cur >= 0) transcripts.getOrNull(cur + 1) else null
                if (next != null) listenGoById(next.id, autoplay = true, skipReplay = true)
                else listenStop()
            }
        }
        if (t.segmentId <= 0L) { advance(); return }
        if (skipReplay && t.segmentId == lastPlayedSeg) {
            /* 这一段刚播过 —— 停在当前行等标注，不重播。
               注意此时不能自动再前进，否则一段拆成的十行会被一口气跳过去。 */
            return
        }
        /* ⚠️ 播完**不自动跳**，onDone 传 null。
           上一版这里传的是 advance，结果 96 句在 289 秒有效语音里播完跳完，
           人工完全跟不上（实测 2.5 秒连跳 3 句）。真正该触发前进的动作是「标注」，
           不是「播放结束」—— 播完就停在这一行等用户点 A/B。
           想只听说不标的场景，用 › 手动步进。 */
        playSegment(t, null)
    }

    /** 开始听标：从第一个未标的句子起，全标过就从头 */
    fun listenStart() {
        val idx = transcripts.indexOfFirst { it.speakerName == null }.takeIf { it >= 0 }
            ?: transcripts.indexOfFirst { it.segmentId > 0L }.takeIf { it >= 0 }
            ?: 0
        val t = transcripts.getOrNull(idx) ?: return
        /* A/B 名字没填过就用设置里的固定值，不然底部两个按钮是空的、点了也不知道会标成谁。
           已注册声纹时直接给声纹里的名字 —— 用户听到的两个人就是这两个，
           让他对着「我 / 对方」这种抽象词去点，等于每轮都要在脑子里翻译一次。 */
        if (abA.isBlank()) {
            val people = PersonStore.list(ctx)
            abA = people.firstOrNull { it.isSelf }?.name
                ?: AppPrefs.abA(ctx).ifBlank { meName.ifBlank { "我" } }
        }
        if (abB.isBlank() || abB == abA) {
            val other = PersonStore.list(ctx).firstOrNull { !it.isSelf }?.name
            val b = AppPrefs.abB(ctx)
            abB = other ?: if (b == abA) "他人" else b
        }
        listenMode = true
        selectionMode = false
        lastPlayedSeg = -1L
        listenGoById(t.id, autoplay = true, skipReplay = false)
    }

    /** 听标里点 A / B：标完这一行就前进。autoAdvance 关掉时留在原地 */
    fun listenMark(name: String?) {
        val id = listenId
        if (id <= 0L) return
        scope.launch {
            withContext(Dispatchers.IO) {
                db.applySpeakerEdit(listOf(id), name)
                rememberName(ctx, name, meName)
            }
            SessionState.renameSpeaker(setOf(id), name)
            if (listenAuto) {
                val idx = transcripts.indexOfFirst { it.id == id }
                val next = transcripts.getOrNull(idx + 1)
                if (next != null) listenGoById(next.id, autoplay = true, skipReplay = true)
                else listenStop()
            }
        }
    }

    fun listenStep(delta: Int) {
        val idx = transcripts.indexOfFirst { it.id == listenId }
        val target = transcripts.getOrNull(idx + delta) ?: return
        lastPlayedSeg = -1L   // 手动切换时允许重听
        listenGoById(target.id, autoplay = true, skipReplay = false)
    }

    /* 返回键分两级：先退多选 / 先退听标，再退页面。
       勾了十几句或听到第 40 句时误触一下全部丢掉，比多点一次返回更让人恼火。
       单句编辑 / 批量标注是 Dialog，系统返回由它们自己消费，这里不用管。 */
    BackHandler(enabled = selectionMode || listenMode) {
        when {
            selectionMode -> {
                selectionMode = false
                selected = emptySet()
            }
            else -> listenStop()
        }
    }

    /**
     * 双人对话的「一人一句」快速标注：第 1 行给 A、第 2 行给 B、第 3 行给 A……
     * 名字由弹窗传入，不再依赖「前两行已经标好」。
     *
     * 严格按行交替，不按音频段分组。上一版我加过一条「同一段音频拆出来的
     * 连续行跟着上一行走」，那是把因果搞反了：用户拆行**正是因为**这几句是不同人
     * 说的、需要分别标人；如果本来就是同一个人，他根本不会去拆。
     * 按段不交替，等于把拆行的收益全部吃掉。
     *
     * **会覆盖已有标注**，所以执行前把原状存进 [SpeakerUndo]，给一次反悔机会。
     * 原来的注释写着「全覆盖，不保留人工标注，不可撤销」—— 那句话在 11-09 造成了
     * 一轮 336 行的误标且无法恢复，撤回能力是这一版的必要部分，不是可选项。
     */
    fun abAlternate(nameA: String, nameB: String) {
        val list = transcripts
        if (list.size < 2) {
            Toast.makeText(ctx, "这轮只有 ${list.size} 句，不用交替", Toast.LENGTH_SHORT).show()
            return
        }

        val idsA = mutableListOf<Long>()
        val idsB = mutableListOf<Long>()
        list.forEachIndexed { i, t ->
            if (i % 2 == 0) idsA += t.id else idsB += t.id
        }

        scope.launch {
            withContext(Dispatchers.IO) {
                SpeakerUndo.save(ctx, sessionId, list.map { it.id to it.speakerName })
                if (idsA.isNotEmpty()) db.applySpeakerEdit(idsA, nameA)
                if (idsB.isNotEmpty()) db.applySpeakerEdit(idsB, nameB)
            }
            canUndoAb = true
            Toast.makeText(
                ctx,
                "已交替：$nameA ${idsA.size} 句 / $nameB ${idsB.size} 句 · 可撤销",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** 还原上一次 A/B 交替。逐行写回，保留快照里为空（未标）的那些行 */
    fun undoAbAlternate() {
        scope.launch {
            val snap = withContext(Dispatchers.IO) { SpeakerUndo.load(ctx, sessionId) }
            if (snap == null) {
                canUndoAb = false
                Toast.makeText(ctx, "没有可撤销的交替记录", Toast.LENGTH_SHORT).show()
                return@launch
            }
            withContext(Dispatchers.IO) {
                /* 按名字分组批量写，几百行逐条 update 会卡住主线程的数据库队列 */
                /* 撤销走 revertSpeaker 而不是 applySpeakerEdit：
                   撤销不是「改判」，写进事件表会把改判率抬成假的 */
                snap.groupBy { it.second }.forEach { (name, rows) ->
                    db.transcriptDao().revertSpeaker(rows.map { it.first }, name)
                }
                SpeakerUndo.clear(ctx, sessionId)
            }
            canUndoAb = false
            Toast.makeText(ctx, "已还原 ${snap.size} 行的原标注", Toast.LENGTH_SHORT).show()
        }
    }

    /* statusBarsPadding 不能省：这个页面绕过了 Scaffold 直接全屏渲染，
       没有它顶栏会压进状态栏里 —— 实测「听标 / A-B / ⋯ / 多选」四个按钮
       全部落在系统状态栏的下拉手势区，手指和 adb 都点不动。
       tab 页有 Scaffold 兜着所以看不出问题，只有此处和声纹录入页中招。 */
    Column(Modifier.fillMaxSize().statusBarsPadding()) {

        // ------------------------------------------------ 顶栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", fontSize = 14.sp, color = VlColor.Accent)
            }
            Spacer(Modifier.weight(1f))
            if (selectionMode) {
                Text(
                    "已选 ${selected.size}",
                    fontSize = 12.sp,
                    color = VlColor.Sub,
                )
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { selectionMode = false }) {
                    Text("完成", fontSize = 14.sp, color = VlColor.Accent, fontWeight = FontWeight.SemiBold)
                }
            } else {
                /* 听标：连续播放 + 点 A/B 自动前进。放在最左、字号最大，
                   因为它是「整理一轮会话」的主入口，其余都是修修补补。 */
                TextButton(onClick = { if (listenMode) listenStop() else listenStart() }) {
                    Text(
                        if (listenMode) "退出听标" else "听标",
                        fontSize = 14.sp,
                        color = VlColor.Accent,
                        fontWeight = if (listenMode) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                /* 双人对话一键铺 A/B。放顶栏而不是藏在菜单里，是因为它要
                   在「刚通读完一遍」的时候点，多一层点击就断了节奏。 */
                TextButton(onClick = {
                    /* 预填：优先沿用前两行已标的名字；没有就用声纹里注册的那两个人。
                       都没有才退回「我 / 他人」—— 抽象词只是最后的兜底，
                       不该是默认值的常态。 */
                    val first = transcripts.firstOrNull()?.speakerName?.takeIf { it.isNotBlank() }
                    val second = transcripts.getOrNull(1)?.speakerName
                        ?.takeIf { it.isNotBlank() && it != first }
                    val people = PersonStore.list(ctx)
                    val selfName = people.firstOrNull { it.isSelf }?.name
                    val otherName = people.firstOrNull { !it.isSelf }?.name
                    abA = first ?: selfName ?: meName.ifBlank { "我" }
                    abB = second ?: otherName ?: "他人"
                    abDialog = true
                }) {
                    Text("A/B", fontSize = 14.sp, color = VlColor.Sub)
                }
                /* 断句、复制全文、撤销交替都是低频动作，收进菜单 ——
                   顶栏放五个按钮会挤到「多选」点不到，而这三个每月也用不了几次。 */
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text("⋯", fontSize = 14.sp, color = VlColor.Sub)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = VlColor.Surface,
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("按句号重新断句", fontSize = 13.sp, color = VlColor.Text) },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    val n = withContext(Dispatchers.IO) { db.resplitSessionLines(sessionId) }
                                    Toast.makeText(
                                        ctx,
                                        if (n > 0) "已按句号断开，新增 $n 句" else "没有需要断开的句子",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("复制全文", fontSize = 13.sp, color = VlColor.Text) },
                            onClick = { menuOpen = false; copyCurrent(null) },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Text(
                                    if (canUndoAb) "撤销上次 A/B 交替" else "撤销上次 A/B 交替（无记录）",
                                    fontSize = 13.sp,
                                    color = if (canUndoAb) VlColor.Amber else VlColor.Dim,
                                )
                            },
                            enabled = canUndoAb,
                            onClick = { menuOpen = false; undoAbAlternate() },
                        )
                    }
                }
                TextButton(onClick = {
                    listenStop()
                    selectionMode = true
                    Toast.makeText(ctx, "点行勾选 · 长按拖动可连选", Toast.LENGTH_SHORT).show()
                }) {
                    Text("多选", fontSize = 14.sp, color = VlColor.Accent)
                }
            }
        }

        // ------------------------------------------------ 会话头
        /* 默认压到 2 行：行1 = 日期 · 时间 · 句数 · 展开开关；行2 = 说话人统计条。
           来源/音频/重新转录/AI 校对都是「这一轮」的低频管理动作，收进展开区；
           但「出了事」的状态（待转写、校对中、待确认建议）折叠时仍露一行摘要 ——
           收起来不等于看不见。 */
        Column(Modifier.padding(horizontal = 16.dp)) {
            val start = session?.startWall ?: 0L
            val end = session?.endWall ?: 0L
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dayLabel(session?.startWall ?: transcripts.firstOrNull()?.wallClock ?: 0L),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = VlColor.Text,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = buildString {
                        append(fmtClockShort(start))
                        append("–")
                        append(if (end > 0) fmtClockShort(end) else "未正常结束")
                        append(" · ${transcripts.size} 句")
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (end > 0) VlColor.Sub else VlColor.Amber,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (headerExpanded) "▴ 收起" else "▾ 详情",
                    fontSize = 11.sp,
                    color = VlColor.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { headerExpanded = !headerExpanded }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            /* 统计条按**实际标签**分组，不再用「我 / 他人」两个桶。
               原来「他人 156」实际是「说话人B 39」+「视频噪音 117」加在一起 ——
               3/4 是电视却说成"别人说的"，这个数字每天都在误导判断。
               噪音的各种历史名字合并成一个计数，否则「电视外放 118」和
               「视频噪音 3」会并排出现两个都表示同一件事的标签。 */
            val noiseN = transcripts.count { AppPrefs.isNoise(it.speakerName) }
            val byTag = transcripts.mapNotNull { it.speakerName }
                .filterNot { AppPrefs.isNoise(it) }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
            val unlabeled = transcripts.count { it.speakerName == null }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                byTag.take(4).forEach { (name, n) ->
                    SpeakerChip(name, 0, compact = true, meName = meName)
                    Spacer(Modifier.width(3.dp))
                    Text("$n", fontSize = 10.sp, color = speakerFg(name, 0, meName))
                    Spacer(Modifier.width(8.dp))
                }
                if (byTag.size > 4) {
                    Text("+${byTag.size - 4}", fontSize = 10.sp, color = VlColor.Dim)
                    Spacer(Modifier.width(6.dp))
                }
                if (noiseN > 0) {
                    SpeakerChip(AppPrefs.NOISE_LABEL, 0, compact = true, meName = meName)
                    Spacer(Modifier.width(3.dp))
                    Text("$noiseN", fontSize = 10.sp, color = VlColor.NoiseFg)
                    Spacer(Modifier.width(8.dp))
                }
                if (unlabeled > 0) {
                    Text("$unlabeled 句未判", fontSize = 10.sp, color = VlColor.Dim)
                } else if (transcripts.isNotEmpty()) {
                    Text("全部已标", fontSize = 10.sp, color = VlColor.Green.copy(alpha = 0.8f))
                }
            }

            /* ---- 折叠时的动态摘要：只在「有事」时露头，平时不占一行。
               逐项加 · 分隔，一屏装下；点「详情」才能动这些事。 */
            val mine = pf.sessionId == sessionId
            if (!headerExpanded) {
                val pendN = transcripts.count { it.proofText != null }
                val hints = buildList {
                    if (tally.unfinished > 0) {
                        add(
                            buildString {
                                if (tally.pending > 0) append("${tally.pending} 段待转写")
                                if (tally.pending > 0 && tally.failed > 0) append(" · ")
                                if (tally.failed > 0) append("${tally.failed} 段失败")
                            },
                        )
                    }
                    if (pf.running && mine) add("校对中 ${pf.elapsedSec}s · ${pf.total} 句")
                    if (pendN > 0) add("$pendN 条建议待确认")
                    /* 中断条数在折叠时也要露：丢了一段时间的录音是「数据缺了」，
                       不是「功能没做完」，两者的紧急程度完全不同。 */
                    val stallSpans = mergeStalls(interrupts)
                    if (stallSpans.isNotEmpty()) {
                        val lostMs = stallSpans.sumOf { (a, b) -> b - a }
                        add("录音中断 ${stallSpans.size} 次 / 约 ${fmtSpanShort(lostMs)}")
                    }
                }
                if (hints.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hints.joinToString(" · ") + "（点右上「详情」处理）",
                        fontSize = 10.sp,
                        color = VlColor.Dim,
                    )
                }
            } else {
            Spacer(Modifier.height(10.dp))

            /* ---- 录音中断清单 ----
               这一轮有没有「没录到」的时段，必须能看见。没有它，用户看到句子时间
               从 16:51 直接跳到 19:26，只能猜是同步坏了还是当时没人说话。 */
            run {
                val spans = mergeStalls(interrupts)
                if (spans.isNotEmpty()) {
                    val lostMs = spans.sumOf { (a, b) -> b - a }
                    Text(
                        "录音中断 ${spans.size} 次 · 累计约 ${fmtSpanShort(lostMs)}，这段音频没有录到",
                        fontSize = 10.sp,
                        color = VlColor.Amber,
                    )
                    spans.takeLast(6).forEach { span ->
                        Text(
                            "${fmtClockShort(span.first)}–${fmtClockShort(span.second)}" +
                                "  ${fmtSpanShort(span.second - span.first)}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = VlColor.Dim,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            /* 未消化完的音频必须在这里露头。
               之前只在「一句文字都没有」时才提示，于是"3 句成功 + 1 段失败"
               看起来和"全部完成"一模一样，用户没有任何入口去补那一段。 */
            if (tally.unfinished > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = buildString {
                            if (tally.pending > 0) append("${tally.pending} 段待转写")
                            if (tally.pending > 0 && tally.failed > 0) append(" · ")
                            if (tally.failed > 0) append("${tally.failed} 段失败")
                        },
                        fontSize = 10.sp,
                        color = if (tally.failed > 0) VlColor.Amber else VlColor.Sub,
                    )
                    Spacer(Modifier.width(6.dp))
                    if (tally.pending > 0) {
                        TextButton(
                            onClick = {
                                scope.launch { runCatching { TranscribeWorker.run(ctx, sessionId) } }
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 6.dp, vertical = 0.dp,
                            ),
                        ) { Text("继续转写", fontSize = 11.sp, color = VlColor.Accent) }
                    }
                    if (tally.failed > 0) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val n = runCatching {
                                        TranscribeWorker.retryFailed(ctx, sessionId)
                                    }.getOrDefault(0)
                                    if (n > 0) {
                                        Toast.makeText(ctx, "重试 $n 段", Toast.LENGTH_SHORT).show()
                                        runCatching { TranscribeWorker.run(ctx, sessionId) }
                                    } else {
                                        /* 一段都救不回来时，必须说清原因并给出口：
                                           只说「没有可重试的段落」，用户会以为是自己点错了。 */
                                        val dead = runCatching {
                                            TranscribeWorker.countUnrecoverable(ctx, sessionId)
                                        }.getOrDefault(0)
                                        Toast.makeText(
                                            ctx,
                                            if (dead > 0) "$dead 段音频已经不在了，无法重试" else "没有可重试的段落",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        deadSegs = dead
                                    }
                                }
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 6.dp, vertical = 0.dp,
                            ),
                        ) { Text("重试失败段", fontSize = 11.sp, color = VlColor.Amber) }
                    }
                    /* 音频真的没了：给「清理」而不是让那串数字永久挂在列表上。
                       这些段的音频从未写进磁盘，重试无意义，留着只会让人以为还能补。 */
                    if (deadSegs > 0) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val n = runCatching {
                                        TranscribeWorker.purgeUnrecoverable(ctx, sessionId)
                                    }.getOrDefault(0)
                                    if (n > 0) {
                                        deadSegs = 0
                                        Toast.makeText(ctx, "已清理 $n 段无音频记录", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 6.dp, vertical = 0.dp,
                            ),
                        ) { Text("清理 $deadSegs 段无音频", fontSize = 11.sp, color = VlColor.Dim) }
                    }
                }
                /* 把失败原因摊开写。不写的话，用户点十次重试都是同样结果，
                   还以为是自己操作错了 —— 其实是那段音频本身就坏了。 */
                if (tally.failed > 0 && tally.failReason.isNotBlank()) {
                    Text(
                        text = tally.failReason,
                        fontSize = 9.sp,
                        color = VlColor.Dim,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
            /* ---------------- 重新转录 ----------------
               音频还在，这个入口就一直在。
               用户改了语种、改了声纹、或者单纯觉得上次识别得烂，都该能重来一遍，
               而不是「重录一次」——录音是不可重复的，文字可以重算。
               音频被清掉后按钮置灰而不是隐藏：消失会让人以为功能没了，灰着才说明
               「不是不给你用，是素材没了」。 */
            val plan = rePlan
            val audioN = plan?.audioSegs ?: 0
            /* 本轮录音来源。为空的老数据不显示这一行 ——
               补一句「内置麦克风」等于伪造当时的录音条件，而这一行存在的意义
               恰恰是事后解释「为什么这一轮标得准 / 不准」。 */
            val dev = session?.inputDevice.orEmpty()
            if (dev.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "来源：$dev",
                    fontSize = 10.sp,
                    color = VlColor.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (audioN > 0) {
                        "音频 $audioN 段 · ${fmtSize(plan?.audioBytes ?: 0L)}"
                    } else {
                        "音频已清空，无法重新转录"
                    },
                    fontSize = 10.sp,
                    color = VlColor.Dim,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        /* 点开前重盘一次：进页面时的数字可能已经被别的操作改掉了
                           （比如刚清空过音频），弹窗里报的数必须是当下的 */
                        scope.launch {
                            rePlan = runCatching {
                                TranscribeWorker.planRetranscribe(ctx, sessionId)
                            }.getOrNull()
                            reConfirm = true
                        }
                    },
                    enabled = audioN > 0 && !txRunning,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 6.dp, vertical = 0.dp,
                    ),
                ) {
                    Text(
                        "重新转录",
                        fontSize = 11.sp,
                        color = if (audioN > 0) VlColor.Accent else VlColor.Dim.copy(alpha = 0.6f),
                    )
                }
            }

            /* ---------------- AI 校对 ----------------
               放在会话头而不是顶栏：它作用于「这一轮」，和音频状态、说话人统计
               属于同一层级。用户扫一眼就知道这轮「转完没有、标完没有、校对完没有」。

               ★ 只做按钮，绝不自动跑。这是花钱操作（每轮十几批 AI 调用），
                 而且模型看不到音频 —— 自动跑会让人以为结果已经被采纳了。 */
            val lowN = transcripts.count { it.proofText != null && it.proofRisk == "low" }
            val midN = transcripts.count { it.proofText != null && it.proofRisk == "mid" }
            val highN = transcripts.count { it.proofText != null && it.proofRisk == "high" }
            val proofPending = lowN + midN + highN
            val provedN = transcripts.count { it.proofAt > 0 }
            // mine 已在上方折叠摘要处声明（校对进度只在本人操作时显示）

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pf.running && mine) {
                    Text(
                        "校对中 ${pf.elapsedSec}s · ${pf.total} 句",
                        fontSize = 10.sp,
                        color = VlColor.Accent,
                    )
                    if (pf.suggested > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text("· ${pf.suggested} 条建议", fontSize = 10.sp, color = VlColor.Dim)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { ProofreadManager.cancel() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 6.dp, vertical = 0.dp,
                        ),
                    ) { Text("中止", fontSize = 11.sp, color = VlColor.Sub) }
                } else if (proofPending > 0) {
                    Text(
                        buildString {
                            append("$proofPending 条建议待确认")
                            if (highN > 0) append("（$highN 条改动过大已忽略）")
                        },
                        fontSize = 10.sp,
                        color = VlColor.Green.copy(alpha = 0.9f),
                    )
                    Spacer(Modifier.weight(1f))
                    /* 「全部接受」＝把这一轮所有待确认建议一次性落到原文，
                       包括改动率 >35% 的高风险项。这是用户明确要的快捷路径
                       （拿去做日记素材，不逐句抠）；要保守就点旁边的「低风险」。
                       两者都是「用户点了才写库」，不违背「模型不能直接改」这条底线。 */
                    TextButton(
                        onClick = {
                            scope.launch {
                                val n = ProofreadManager.applyAll(db, sessionId)
                                Toast.makeText(ctx, "已接受 $n 条修改", Toast.LENGTH_SHORT).show()
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 6.dp, vertical = 0.dp,
                        ),
                    ) { Text("全部接受 $proofPending", fontSize = 11.sp, color = VlColor.Accent) }
                    if (lowN > 0) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val n = ProofreadManager.applyByRisk(db, sessionId, "low")
                                    Toast.makeText(ctx, "已采纳 $n 条低风险修改", Toast.LENGTH_SHORT).show()
                                }
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 6.dp, vertical = 0.dp,
                            ),
                        ) { Text("低风险 $lowN", fontSize = 11.sp, color = VlColor.Sub) }
                    }
                    /* 建议不满意就整轮重跑，不再有「跑过就不让跑」这件事。
                       放在最右、字号最小：它不是主路径，但必须随时够得着。 */
                    TextButton(
                        onClick = { proofReConfirm = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 6.dp, vertical = 0.dp,
                        ),
                    ) { Text("重跑", fontSize = 11.sp, color = VlColor.Dim) }
                } else {
                    Text(
                        if (provedN > 0) "已校对 $provedN 句" else "还没做 AI 校对",
                        fontSize = 10.sp,
                        color = VlColor.Dim,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            /* 已经跑过的，点下去是「整轮重跑」而不是「补校对」——
                               补校对在全部校对过之后必然什么都没得送，用户看到的是
                               「没有可校对的句子」，像是功能坏了。重跑要花一次钱，
                               所以先弹窗说清楚。 */
                            if (provedN > 0) {
                                proofReConfirm = true
                            } else {
                                val started = ProofreadManager.start(ctx, db, sessionId)
                                if (!started) {
                                    Toast.makeText(ctx, "已有校对在跑，等它结束", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = transcripts.isNotEmpty(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 6.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(
                            if (provedN > 0) "重新校对" else "AI 校对",
                            fontSize = 11.sp,
                            color = if (transcripts.isEmpty()) VlColor.Dim else VlColor.Accent,
                        )
                    }
                }
            }
            if (mine && pf.error.isNotBlank()) {
                Text(pf.error, fontSize = 9.sp, color = VlColor.Amber)
            } else if (mine && !pf.running && pf.message.isNotBlank()) {
                Text(pf.message, fontSize = 9.sp, color = VlColor.Dim)
            }
            } // headerExpanded 展开区结束

            Spacer(Modifier.height(4.dp))
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(VlColor.Line))

        /* ---------------- 筛选条 ----------------
           放在列表正上方而不是会话头里：它作用于「下面这一列句子」，
           跟着列表固定，滚到第 200 句时仍然够得着。 */
        if (transcripts.isNotEmpty()) {
            RowFilterBar(
                current = filter,
                counts = mapOf(
                    RowFilter.ALL to transcripts.size,
                    RowFilter.PROOF to transcripts.count { it.proofText != null },
                    RowFilter.UNLABELED to transcripts.count { it.speakerName == null },
                    RowFilter.GUESSED to transcripts.count {
                        it.effectiveAuto == TranscriptEntity.AUTO_F0_GUESS
                    },
                    RowFilter.NOISE to transcripts.count { AppPrefs.isNoise(it.speakerName) },
                ),
                onPick = { filter = it },
            )
        }

        /* 筛选后的列表。
           筛选态下必须关掉「并入上一句」：这个动作指的是**数据库里真实的前一行**，
           而筛掉中间的行之后，界面上看见的「上一句」和实际上一句不是同一句 ——
           照点就会把两句不相干的话粘在一起，而且合并是软删，很难还原。 */
        val shown = when (filter) {
            RowFilter.ALL -> transcripts
            RowFilter.PROOF -> transcripts.filter { it.proofText != null }
            RowFilter.UNLABELED -> transcripts.filter { it.speakerName == null }
            RowFilter.GUESSED -> transcripts.filter {
                it.effectiveAuto == TranscriptEntity.AUTO_F0_GUESS
            }
            RowFilter.NOISE -> transcripts.filter { AppPrefs.isNoise(it.speakerName) }
        }
        shownRef.value = shown

        // ------------------------------------------------ 句子列表
        if (transcripts.isEmpty()) {
            /* 只录不转模式下，一轮会话先有音频、没有文字。
               此时必须给出「去转写」的出口 —— 直接说「没有识别到内容」会让人以为丢了。 */
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when {
                            /* ⚠️ 必须区分「有待转的音频」和「有失败的音频」：
                               failed 段大多是因为音频文件根本没写成功（编码环节就断了），
                               说成「在本地缓存」等于骗人 —— 用户会以为还能转，反复点转写。
                               2026-11-07 就这样误导过一次（64 段失败却写「在本地缓存」）。 */
                            tally.pending > 0 ->
                                "这一轮还没转写，共 ${tally.pending} 段音频在本地缓存"
                            tally.failed > 0 ->
                                "这一轮的 ${tally.failed} 段音频没能保存下来"
                            tally.done > 0 -> "这一轮 ${tally.done} 段音频都没识别出文字"
                            else -> "这一轮没有录到人声"
                        },
                        fontSize = 12.sp,
                        color = VlColor.Dim,
                    )
                    if (pendingSegs > 0) {
                        Spacer(Modifier.height(10.dp))
                        TextButton(
                            onClick = {
                                scope.launch {
                                    runCatching { TranscribeWorker.run(ctx, sessionId) }
                                }
                            },
                        ) {
                            Text("现在转写", fontSize = 13.sp, color = VlColor.Accent)
                        }
                    }
                }
            }
            return@Column
        }

        /* 选中的筛选档位变空（比如刚采纳完最后一条建议）。
           给个说明而不是留一片黑 —— 空屏幕和「加载失败」在用户眼里是一样的。 */
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这个筛选下没有句子了", fontSize = 12.sp, color = VlColor.Dim)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .pointerInput(selectionMode) {
                    /* 只在多选模式注册刷选手势：非多选时长按说话人标签是「打开编辑」入口，
                       两套长按手势叠在一起会互相触发。 */
                    if (!selectionMode) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { off ->
                            dragLastIdx = itemIdxAt(off.y)
                            rangeSelectTo(dragLastIdx)
                        },
                        onDrag = { change, _ ->
                            val idx = itemIdxAt(change.position.y)
                            if (idx != dragLastIdx) {
                                dragLastIdx = idx
                                rangeSelectTo(idx)
                            }
                        },
                        onDragEnd = { tapAnchorIdx = dragLastIdx },
                        onDragCancel = { },
                    )
                },
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(shown, key = { _, it -> it.id }) { idx, t ->
                val prev = shown.getOrNull(idx - 1)
                TranscriptRow(
                    t = t,
                    highlight = highlight,
                    selectionMode = selectionMode,
                    checked = t.id in selected,
                    meName = meName,
                    listening = listenMode && listenId == t.id,
                    playing = playingSeg == t.segmentId && t.segmentId > 0L,
                    /* 与上一行共用同一段音频：拆出来的后半句、或 ASR 把一句拆进两个 VAD 段。
                       此时播放按钮显示「↳」，提醒用户点下去听到的是整段而不是这半句。 */
                    continuing = prev != null && t.segmentId > 0L && prev.segmentId == t.segmentId,
                    requestFocus = focusId == t.id,
                    onFocusHandled = { focusId = 0L },
                    onPlay = if (t.segmentId > 0L && !selectionMode) {
                        {
                            if (listenMode) {
                                /* 听标模式下点播放 = 从这一行开始听，而不是孤立的播一下 */
                                lastPlayedSeg = -1L
                                listenGoById(t.id, autoplay = true, skipReplay = false)
                            } else if (playingSeg == t.segmentId) {
                                stopPlayback()
                            } else {
                                playSegment(t)
                            }
                        }
                    } else null,
                    onToggleCheck = {
                        val ns = if (t.id in selected) selected - t.id else selected + t.id
                        selected = ns
                        selectionMode = ns.isNotEmpty()
                        /* 点击行的位置成为范围选锚点：点第 1 行后再长按第 20 行 = 前 20 行全选 */
                        tapAnchorIdx = idx
                    },
                    onSpeaker = { speakerTarget = t },
                    onCommitText = { nt ->
                        if (nt != t.text) {
                            scope.launch {
                                withContext(Dispatchers.IO) { db.applyTextEdit(t.id, nt) }
                            }
                        }
                    },
                    onSplit = { head, tail ->
                        if (head.isNotBlank() && tail.isNotBlank()) {
                            scope.launch {
                                val nid = withContext(Dispatchers.IO) {
                                    db.splitTranscriptLineAt(t.id, head, tail)
                                }
                                if (nid > 0L) focusId = nid
                            }
                        }
                    },
                    onMergePrev = if (prev != null && !selectionMode && filter == RowFilter.ALL) {
                        { curText ->
                            scope.launch {
                                val pid = withContext(Dispatchers.IO) {
                                    db.mergeTranscriptLine(t.id, curText)
                                }
                                if (pid <= 0L) {
                                    Toast.makeText(
                                        ctx,
                                        "并入失败：这一行已经不在库里了（可能被重复点击或已删除）",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    } else null,
                    onLongClick = { if (!selectionMode) editing = t },
                    onProofApply = if (!selectionMode) {
                        t.proofText?.let { sg ->
                            {
                                scope.launch {
                                    ProofreadManager.applyOne(db, t.id, sg)
                                }
                            }
                        }
                    } else null,
                    onProofDrop = if (!selectionMode) {
                        {
                            scope.launch { ProofreadManager.dropOne(db, t.id) }
                        }
                    } else null,
                )
            }
        }

        // ------------------------------------------------ 听标控制条
        /* 这是整个标人流程的操作台：一次听、一次点、自动到下一句。
           把它做成常驻底栏而不是弹窗，是因为弹窗会遮住刚播的那句话 ——
           用户需要一边听一边看着文字确认是谁说的。 */
        if (listenMode) {
            val curIdx = transcripts.indexOfFirst { it.id == listenId }
            val doneN = transcripts.count { it.speakerName != null }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(VlColor.Surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (curIdx >= 0) "第 ${curIdx + 1}/${transcripts.size} 句" else "已到末尾",
                        fontSize = 11.sp,
                        color = VlColor.Accent,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("已标 $doneN", fontSize = 10.sp, color = VlColor.Dim)
                    Spacer(Modifier.weight(1f))
                    /* 「视频噪音」：电视、短视频里的人声。
                       你已经能靠文字内容认出这类句子，所以不做自动判定，
                       只给一个一键标注出口 —— 否则为了标它还得打开选人弹窗。
                       点一下标当前行，并跟 A/B 一样自动前进。 */
                    TextButton(
                        onClick = { listenMark(AppPrefs.NOISE_LABEL) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 6.dp, vertical = 0.dp,
                        ),
                    ) { Text(AppPrefs.NOISE_LABEL, fontSize = 10.sp, color = VlColor.Dim) }
                    TextButton(
                        onClick = { listenAuto = !listenAuto },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 6.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(
                            if (listenAuto) "自动前进 · 开" else "自动前进 · 关",
                            fontSize = 10.sp,
                            color = if (listenAuto) VlColor.Green.copy(alpha = 0.9f) else VlColor.Dim,
                        )
                    }
                    TextButton(
                        onClick = { listenStop() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 6.dp, vertical = 0.dp,
                        ),
                    ) { Text("退出", fontSize = 11.sp, color = VlColor.Sub) }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallStepButton("‹") { listenStep(-1) }
                    Button(
                        onClick = { listenMark(abA.trim().ifBlank { "我" }) },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VlColor.MeBg,
                            contentColor = VlColor.MeFg,
                        ),
                    ) { Text(abA.ifBlank { "我" }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Button(
                        onClick = { listenMark(abB.trim().ifBlank { "对方" }) },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VlColor.OtherBg,
                            contentColor = VlColor.OtherFg,
                        ),
                    ) { Text(abB.ifBlank { "对方" }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    /* 「未判」不是凑数：听标时只想听不想立刻决定、或者发现自己上一句标错，
                       都需要一个「把这一行清空」的出口，否则只能事后去多选里改。 */
                    Button(
                        onClick = { listenMark(null) },
                        modifier = Modifier.height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VlColor.SurfaceVar,
                            contentColor = VlColor.Sub,
                        ),
                    ) { Text("清空", fontSize = 12.sp) }
                    SmallStepButton("›") { listenStep(1) }
                }
            }
        }

        // ------------------------------------------------ 批量操作栏
        if (selectionMode) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(VlColor.Surface)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { bulkSpeaker = true },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VlColor.Accent,
                        contentColor = androidx.compose.ui.graphics.Color(0xFF00204A),
                        disabledContainerColor = VlColor.SurfaceVar,
                        disabledContentColor = VlColor.Dim,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("标记说话人", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Button(
                    onClick = { copyCurrent(selected) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VlColor.SurfaceVar,
                        contentColor = VlColor.Text,
                        disabledContainerColor = VlColor.SurfaceVar,
                        disabledContentColor = VlColor.Dim,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("复制", fontSize = 14.sp)
                }
                /* 删句走软删（deletedAt + syncState=2），和删会话语义一致：
                   进回收站而不是物理抹掉，云端也能收到删除标记。
                   这才是「选中一句话删掉」——左侧的 ⌫ 是把它并进上一行，不是删。
                   先确认再删：多选一次勾十几句，误触一下就全进回收站，翻找的成本
                   远大于多点一次。 */
                Button(
                    onClick = { deleteConfirm = true },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VlColor.SurfaceVar,
                        contentColor = VlColor.Amber,
                        disabledContainerColor = VlColor.SurfaceVar,
                        disabledContentColor = VlColor.Dim,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("删除", fontSize = 14.sp)
                }
            }
        }
    }

    /* 批量删句的确认弹窗。说清去向（回收站、可恢复）而不是只写「删除」，
       否则用户会当成不可逆操作不敢用。 */
    if (deleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            containerColor = VlColor.Surface,
            title = { Text("删除选中的 ${selected.size} 句？", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Text(
                    "会进入回收站，7 天内可恢复；正在同步的设备也会收到删除标记。",
                    fontSize = 12.sp,
                    color = VlColor.Sub,
                    lineHeight = 17.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirm = false
                        val ids = selected.toList()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                db.transcriptDao().softDelete(ids, System.currentTimeMillis())
                            }
                            selected = emptySet()
                            selectionMode = false
                            Toast.makeText(ctx, "已删除 ${ids.size} 句", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("删除", color = VlColor.Red) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = false }) { Text("取消", color = VlColor.Sub) }
            },
        )
    }

    // ------------------------------------------------ 单句编辑 Sheet
    editing?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            sheetState = sheetState,
            containerColor = VlColor.Surface,
        ) {
            TranscriptEditor(
                target = target,
                meName = meName,
                knownSpeakers = knownSpeakers,
                tags = tags,
                onDismiss = { editing = null },
                onSave = { newText, newSpeaker ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (newText != target.text) {
                                db.applyTextEdit(target.id, newText)
                            }
                            if (newSpeaker != target.speakerName) {
                                db.applySpeakerEdit(listOf(target.id), newSpeaker)
                                SessionState.renameSpeaker(setOf(target.id), newSpeaker)
                            }
                            // 手打的新名字自动收进标签库：用户既然用了一次，
                            // 大概率还会用第二次，下次不该再让他手打一遍
                            rememberName(ctx, newSpeaker, meName)
                        }
                        refreshSpeakers()
                        editing = null
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // ------------------------------------------------ 批量标注 Dialog
    if (bulkSpeaker) {
        /* 多选后常常就是想「这批机器猜的都对」，所以批量也要支持确认。
           只在选中句名字全一致时才认 current：混杂时传空，
           否则弹窗会写着「当前标的是 X」而选中里其实还有别的名字。 */
        val bulkCurrent = selected
            .mapNotNull { id -> transcripts.firstOrNull { it.id == id }?.speakerName }
            .distinct()
            .singleOrNull()
        SpeakerPickerDialog(
            title = "为选中的 ${selected.size} 句标记说话人",
            meName = meName,
            knownSpeakers = knownSpeakers,
            tags = tags,
            current = bulkCurrent,
            onDismiss = { bulkSpeaker = false },
            onPick = { name ->
                val ids = selected
                scope.launch {
                    withContext(Dispatchers.IO) {
                        db.applySpeakerEdit(ids.toList(), name)
                        rememberName(ctx, name, meName)
                    }
                    SessionState.renameSpeaker(ids, name)
                    refreshSpeakers()
                    bulkSpeaker = false
                    selectionMode = false
                    Toast.makeText(ctx, "已标记 ${ids.size} 句为「${name ?: "未标"}」", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    /* 单击说话人标签 → 轻量选择器。
       比打开整个编辑弹窗少一次点击、不遮住上下文；改文字已经在行内做了，
       所以右下角这个入口唯一的职责就是「这行是谁说的」。 */
    speakerTarget?.let { target ->
        /* 机器猜的依据：音高落在男声区间（≤160Hz）实测改判率约 3.7%，
           偏高音区间（≥200Hz）约 41% —— 把区间说出来，用户才有判断依据，
           否则「B?」只是个无法验证的标签。 */
        val guessNote = if (target.effectiveAuto == TranscriptEntity.AUTO_F0_GUESS) {
            when {
                target.f0Hz <= 0 -> "机器猜的，没有音高数据作依据"
                target.f0Hz <= 160 -> "机器按音高猜的 ${target.f0Hz}Hz，男声区间，通常准"
                else -> "机器按音高猜的 ${target.f0Hz}Hz，偏高音区间，容易错"
            }
        } else null
        SpeakerPickerDialog(
            title = "这一句是谁说的",
            meName = meName,
            knownSpeakers = knownSpeakers,
            tags = tags,
            current = target.speakerName,
            guessNote = guessNote,
            onDismiss = { speakerTarget = null },
            onPick = { name ->
                /* 确认（选了同名）时这句会立刻掉出「机器猜」筛选、
                   从当前列表里消失 —— 不给一句提示，会像被误删。 */
                val confirming = name != null && name == target.speakerName &&
                    target.effectiveAuto == TranscriptEntity.AUTO_F0_GUESS
                scope.launch {
                    withContext(Dispatchers.IO) {
                        /* 走 applySpeakerEdit：它把 speakerAuto 留作快照、只置 speakerEdited，
                           改判率才算得出来（旧注释说的「要清 auto」已于 2026-11-16 作废）。
                           同名时它会走「确认」分支，把机器猜转成人工确认。 */
                        db.applySpeakerEdit(listOf(target.id), name)
                        rememberName(ctx, name, meName)
                    }
                    SessionState.renameSpeaker(setOf(target.id), name)
                    refreshSpeakers()
                    speakerTarget = null
                    if (confirming) {
                        Toast.makeText(ctx, "已确认是「$name」说的", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    if (abDialog) {
        AbAlternateDialog(
            nameA = abA,
            nameB = abB,
            meName = meName,
            knownSpeakers = knownSpeakers,
            tags = tags,
            lineCount = transcripts.size,
            labeledCount = transcripts.count { it.speakerName != null },
            onNameA = { abA = it },
            onNameB = { abB = it },
            onDismiss = { abDialog = false },
            onApply = {
                abDialog = false
                rememberName(ctx, abA, meName)
                rememberName(ctx, abB, meName)
                /* 同步到设置里的「说话人名字」：F0 自动标人用的是那一份。
                   不同步的话，在听标这里把 A 改成别的名字之后，
                   以后新转写的句子仍会被自动标成旧名字，两处对不上。 */
                abA.trim().takeIf { it.isNotBlank() }?.let { AppPrefs.setAbA(ctx, it) }
                abB.trim().takeIf { it.isNotBlank() }?.let { AppPrefs.setAbB(ctx, it) }
                abAlternate(abA.trim(), abB.trim())
            },
        )
    }

    /* ---------------- 重新转录确认 ----------------
       这是全 App 唯一一个「一口气抹掉几百句」的按钮，必须先把数字摆出来。
       只说「不可恢复」没有说服力；说清「其中 12 句是你手改的」才有。 */
    if (reConfirm) {
        val p = rePlan
        val busy = txRunning
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { reConfirm = false },
            containerColor = VlColor.Surface,
            title = { Text("重新转录这一轮？", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Column {
                    Text(
                        "将用当前设置（语种、断句、声纹）重新识别 ${p?.audioSegs ?: 0} 段音频。",
                        fontSize = 12.sp,
                        color = VlColor.Sub,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "本会话 ${p?.textRows ?: 0} 句文字将被清除，不进回收站" +
                            if ((p?.editedRows ?: 0) > 0) "，其中 ${p?.editedRows} 句是你手动改过的" else "" +
                            "。",
                        fontSize = 12.sp,
                        color = VlColor.Sub,
                        lineHeight = 17.sp,
                    )
                    if ((p?.editedRows ?: 0) > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "手改内容不会自动还原 —— 重转后要重新改一遍。音频不受影响，" +
                                "随时可以再转。",
                            fontSize = 11.sp,
                            color = VlColor.Amber,
                            lineHeight = 16.sp,
                        )
                    }
                    if (busy) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "当前有转写任务在跑，等它结束再来。",
                            fontSize = 11.sp,
                            color = VlColor.Amber,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        reConfirm = false
                        if (busy) return@TextButton
                        scope.launch {
                            val n = runCatching {
                                TranscribeWorker.retranscribe(ctx, sessionId)
                            }.getOrDefault(0)
                            Toast.makeText(
                                ctx,
                                if (n > 0) "重新转录完成，共 $n 句" else "没有可转的音频",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    enabled = (p?.audioSegs ?: 0) > 0 && !busy,
                ) { Text("重新转录", color = VlColor.Red) }
            },
            dismissButton = {
                TextButton(onClick = { reConfirm = false }) { Text("取消", color = VlColor.Sub) }
            },
        )
    }

    /* ---------------- 重新校对确认 ----------------
       重跑＝把已有建议清空、全部句子重新送一遍，是又花一次钱的动作。
       手改过的句子仍然不送（这条底线不能因为「重跑」就破）。 */
    if (proofReConfirm) {
        val todo = transcripts.count { !it.edited && it.text.isNotBlank() }
        val skipped = transcripts.count { it.edited }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { proofReConfirm = false },
            containerColor = VlColor.Surface,
            title = { Text("重新跑一遍 AI 校对？", fontSize = 15.sp, color = VlColor.Text) },
            text = {
                Column {
                    Text(
                        "会先清空本轮已有的建议，再把 $todo 句重新送模型；" +
                            "按设置里的模型、提示词和词表执行，会再花一次 AI 调用额度。",
                        fontSize = 12.sp,
                        color = VlColor.Sub,
                        lineHeight = 17.sp,
                    )
                    if (skipped > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "你手改过的 $skipped 句不会被送进去（模型不许碰人工结果）。",
                            fontSize = 11.sp,
                            color = VlColor.Dim,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已采纳的修改不会回退，只会在新文字上重新给建议。",
                        fontSize = 11.sp,
                        color = VlColor.Dim,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        proofReConfirm = false
                        val started = ProofreadManager.start(ctx, db, sessionId, force = true)
                        if (!started) {
                            Toast.makeText(ctx, "已有校对在跑，等它结束", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = todo > 0,
                ) { Text("开始重跑", color = VlColor.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { proofReConfirm = false }) { Text("取消", color = VlColor.Sub) }
            },
        )
    }
}

/**
 * A/B 交替标注的入口弹窗。
 *
 * 为什么要有这个弹窗，而不是直接拿「前两行已标的名字」跑：
 * 一轮 350 行的会话打开时几乎全是声纹自动标的「他人」，要求用户先去列表里
 * 找到第 1、2 行各点一次标签才能用这个功能 —— 那个前置条件实际上把功能锁死了。
 * 在这里填两个名字、点一下铺满，标完再改个别行，才是可用的顺序。
 */
@Composable
private fun AbAlternateDialog(
    nameA: String,
    nameB: String,
    meName: String,
    knownSpeakers: List<String>,
    tags: List<String>,
    lineCount: Int,
    /** 这一轮已有多少行被标过。用来在弹窗里把「会覆盖什么」说成具体数字 */
    labeledCount: Int,
    onNameA: (String) -> Unit,
    onNameB: (String) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    val options = remember(meName, knownSpeakers, tags) {
        speakerOptions(meName, tags, knownSpeakers)
    }
    val valid = nameA.isNotBlank() && nameB.isNotBlank() && nameA.trim() != nameB.trim()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VlColor.Surface,
        title = { Text("A / B 交替标注", fontSize = 15.sp, color = VlColor.Text) },
        text = {
            Column {
                Text(
                    "按行序铺满这一轮：第 1 行 = A，第 2 行 = B，第 3 行 = A……共 $lineCount 行。",
                    fontSize = 11.sp,
                    color = VlColor.Sub,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (labeledCount > 0) {
                        "⚠️ 这一轮已经有 $labeledCount 行标过，会被覆盖。标完可以在右上「⋯」里撤销一次。"
                    } else {
                        "这一轮还没有标注，放心铺。"
                    },
                    fontSize = 11.sp,
                    color = if (labeledCount > 0) VlColor.Amber else VlColor.Dim,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nameA,
                    onValueChange = onNameA,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("A（第 1、3、5… 行）", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = VlColor.Text),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VlColor.Accent,
                        unfocusedBorderColor = VlColor.Line,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    options.forEach { opt ->
                        FilterChip(
                            selected = nameA == opt,
                            onClick = { onNameA(opt) },
                            label = { Text(opt, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = VlColor.SurfaceVar,
                                labelColor = VlColor.Text,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nameB,
                    onValueChange = onNameB,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("B（第 2、4、6… 行）", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = VlColor.Text),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VlColor.Accent,
                        unfocusedBorderColor = VlColor.Line,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    options.forEach { opt ->
                        FilterChip(
                            selected = nameB == opt,
                            onClick = { onNameB(opt) },
                            label = { Text(opt, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = VlColor.SurfaceVar,
                                labelColor = VlColor.Text,
                            ),
                        )
                    }
                }
                if (nameA.isNotBlank() && nameA.trim() == nameB.trim()) {
                    Spacer(Modifier.height(8.dp))
                    Text("A 和 B 不能是同一个名字", fontSize = 11.sp, color = VlColor.Red)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply, enabled = valid) {
                Text(
                    "铺满 $lineCount 行",
                    color = if (valid) VlColor.Accent else VlColor.Dim,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = VlColor.Sub, fontSize = 12.sp)
            }
        },
    )
}

/** 手打的名字自动进标签库（「我」和「他人」是内置语义，不占位） */
private fun rememberName(ctx: Context, name: String?, meName: String) {
    if (name.isNullOrBlank()) return
    if (name == meName || name == "他人") return
    PersonTags.add(ctx, name)
}

// ---------------------------------------------------------------------- 行

/**
 * 一行转写记录 +（可选）紧跟其下的 AI 建议行。
 *
 * ## 为什么改成左右分栏
 * 左边是**元数据与控制**（勾选、播放、说话人），右边是**内容**（时间、文字）。
 * 连续扫读时视线只走右栏，做标注时视线只走左栏；原先把时间、说话人、文字
 * 叠成三层，翻二十句要滚三屏，扫读成本全耗在找「哪一行是哪一行」上。
 *
 * ## 为什么文字改成行内编辑
 * 改错字是高频小动作，弹窗要多两次点击还遮住上下文。行内改只多一次点击。
 * 长按仍然可以打开弹窗（那里能选说话人、插备注），两条路并存。
 *
 * ## 回车与退格的语义
 * - 回车 = 在此处断成两句（ASR 把两个人的话黏成一行时，这是唯一能分开的办法）
 * - 行首退格 = 并回上一行（拆多了能撤回）
 * 只给拆不给并，误拆一次就永久多一行，用户只能删了重录 —— 那比不做更糟。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptRow(
    t: TranscriptEntity,
    /** 检索关键词。非空时把命中处涂底色，让用户在一屏里立刻看到「命中的是哪几个字」 */
    highlight: String = "",
    selectionMode: Boolean,
    checked: Boolean,
    meName: String,
    /** 听标模式下光标停在这一行：整行描边高亮，用户不用再找「刚播的是哪一句」 */
    listening: Boolean = false,
    playing: Boolean,
    /** 与上一行共享同一段音频（拆行或 ASR 一句跨段）。播放按钮显示为「↳」 */
    continuing: Boolean,
    /** 拆行后需要把焦点落在这行 */
    requestFocus: Boolean,
    onFocusHandled: () -> Unit,
    onPlay: (() -> Unit)?,
    onToggleCheck: () -> Unit,
    onSpeaker: () -> Unit,
    onCommitText: (String) -> Unit,
    onSplit: (head: String, tail: String) -> Unit,
    /**
     * 并入上一行。只由左侧 ⌫ 触发。
     * @param curText 界面**当前**文字（可能还没落库）；缺了它会丢掉未提交的编辑
     */
    onMergePrev: ((curText: String) -> Unit)?,
    onLongClick: () -> Unit,
    onProofApply: (() -> Unit)? = null,
    onProofDrop: (() -> Unit)? = null,
) {
    var value by remember(t.id) { mutableStateOf(TextFieldValue(t.text)) }
    var focused by remember(t.id) { mutableStateOf(false) }
    val fr = remember(t.id) { FocusRequester() }

    /* 本行**最后一次交给数据库**的文本，用来区分两种「库里的 t.text 变了」：
       - 变的是我自己刚提交的内容 → 只是回流，不要动本地光标
       - 变的是别的东西（合并、采纳建议、重新断句）→ 必须跟着变

       原来这里只判断 `!focused`，于是出现了这个 bug：
       点 A 行左侧的「并入上一行」→ 库里 A 行文本变成 A+B、B 行被软删 →
       界面把焦点交给 A 行（focused=true）→ 同步被跳过，屏幕上的 A 还是老的 →
       1 秒后自动保存发现「本地 != 库」→ 把老的 A 写回数据库。
       B 已经删了，合并结果就这么永久丢了。
       所以判断依据必须是「这次变化是不是我造成的」，不能是「我有没有焦点」。 */
    var lastCommit by remember(t.id) { mutableStateOf(t.text) }

    LaunchedEffect(t.text) {
        if (t.text == value.text) {
            lastCommit = t.text
            return@LaunchedEffect
        }
        if (t.text != lastCommit) {
            /* 外部改动：即使正在编辑也要覆盖，并把光标放到末尾 */
            value = TextFieldValue(t.text, TextRange(t.text.length))
            lastCommit = t.text
        }
    }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            runCatching { fr.requestFocus() }
            onFocusHandled()
        }
    }
    /* 停顿即存：用户改完常常直接点下一条，不一定走失焦回调。
       1 秒是「一句话敲完」的中间停顿，比逐字写库省 IO，比只在失焦写安全。
       多一道 `value.text != lastCommit`：刚被外部改完的那一瞬 value 还没跟上，
       此时进这里就等于拿旧文本覆盖合并结果。 */
    LaunchedEffect(value.text, focused) {
        if (focused && value.text != t.text && value.text != lastCommit) {
            kotlinx.coroutines.delay(1000)
            lastCommit = value.text
            onCommitText(value.text)
        }
    }

    Column(Modifier.fillMaxWidth()) {

        // ------------------------------------------------------------ 转录行
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        listening -> VlColor.Accent.copy(alpha = 0.10f)
                        checked -> VlColor.Accent.copy(alpha = 0.07f)
                        else -> Color.Transparent
                    },
                )
                .then(
                    if (listening) {
                        Modifier.border(
                            1.dp,
                            VlColor.Accent.copy(alpha = 0.55f),
                            RoundedCornerShape(6.dp),
                        )
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (selectionMode) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onToggleCheck,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.Top,
        ) {
            /* ---------------- 勾选列（只在多选模式出现） ----------------
               原来这个框是常显的、20dp、未选中时**完全透明** —— 屏幕上没有任何
               东西提示可以点，手指按上去大概率落空。现在：只在多选模式出现、
               未选中时画描边、整列 40×48dp 都能点。 */
            if (selectionMode) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(48.dp)
                        .clickable(onClick = onToggleCheck),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (checked) VlColor.Accent else Color.Transparent)
                            .then(
                                if (checked) {
                                    Modifier
                                } else {
                                    Modifier.border(
                                        1.5.dp,
                                        VlColor.Sub.copy(alpha = 0.55f),
                                        RoundedCornerShape(6.dp),
                                    )
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (checked) {
                            Text("✓", fontSize = 14.sp, color = Color(0xFF00204A), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ---------------- 左栏：控制 ----------------
            Column(Modifier.width(64.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    /* 播放。30dp 是「不撑破 64dp 栏」和「手指点得中」之间的折中 ——
                       原版 26dp，实测在走动时按空。 */
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (playing) VlColor.Accent.copy(alpha = 0.18f) else VlColor.SurfaceVar)
                            .clickable(enabled = onPlay != null) { onPlay?.invoke() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = when {
                                playing -> "■"
                                continuing -> "↳"
                                onPlay == null -> "·"
                                else -> "▶"
                            },
                            fontSize = if (playing) 10.sp else 11.sp,
                            color = when {
                                playing -> VlColor.Accent
                                continuing -> VlColor.Dim
                                else -> VlColor.Sub
                            },
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    /* 「并入上一行」。首行没有上一行，留空位不显示，避免每行宽度跳动。
                       18dp → 26dp：原尺寸的触摸区比指尖小一半，实测按不准。
                       ⚠️ 触发前提是 onMergePrev 非空，调用处已按「有上一行、非多选、非筛选」过滤。 */
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .then(
                                if (onMergePrev != null) {
                                    Modifier
                                        .background(VlColor.SurfaceVar)
                                        .clickable { onMergePrev.invoke(value.text) }
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (onMergePrev != null) {
                            Text("⌫", fontSize = 12.sp, color = VlColor.Sub)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                /* 声纹没跑、名字是 F0 猜的句子，标签降透明度并加「?」。
                   实测 3699 句：声纹命中 2263 句改判 27 句（1.2%），F0 猜 757 句改判 193 句（25.5%）。
                   两者原本同一套配色，怀疑某句话判错时根本无从下手；
                   标出来之后，复核只需要点筛选条的「机器猜」。 */
                val guessed = t.effectiveAuto == TranscriptEntity.AUTO_F0_GUESS
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            speakerBg(t.speakerName, t.effectiveAuto, meName)
                                .copy(alpha = if (guessed) 0.45f else 1f),
                        )
                        .combinedClickable(
                            enabled = !selectionMode,
                            onClick = onSpeaker,
                            onLongClick = onLongClick,
                        )
                        .padding(horizontal = 3.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = (t.speakerName ?: "未判") + if (guessed) "?" else "",
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = speakerFg(t.speakerName, t.effectiveAuto, meName)
                            .copy(alpha = if (guessed) 0.85f else 1f),
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // ---------------- 右栏：内容 ----------------
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = fmtClockFull(t.wallClock),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = VlColor.Dim,
                    )
                    if (continuing) {
                        Spacer(Modifier.width(5.dp))
                        Text("续段", fontSize = 8.sp, color = VlColor.Dim.copy(alpha = 0.8f))
                    }
                    if (t.edited) {
                        Spacer(Modifier.width(5.dp))
                        Text("已改", fontSize = 8.sp, color = VlColor.Amber.copy(alpha = 0.85f))
                    }
                }
                Spacer(Modifier.height(2.dp))
                BasicTextField(
                    value = value,
                    onValueChange = { nv ->
                        val nl = nv.text.indexOf('\n')
                        if (nl >= 0) {
                            /* 回车 = 在此处断句。Compose 没有「只拦回车」的钩子，
                               只能从文本变化里发现换行并立刻吃掉它。 */
                            val head = nv.text.substring(0, nl)
                            val tail = nv.text.substring(nl + 1).replace("\n", "")
                            value = TextFieldValue(head, TextRange(head.length))
                            onSplit(head.trim(), tail.trim())
                        } else {
                            /* 已去掉「光标在行首按退格 = 并回上一行」这个隐形快捷键：
                               手机上把光标挪到行首本身就是精细活，实测远不如点左侧的 ⌫；
                               而且它会吃掉「误触退格」的退路 —— 一次误按就直接改库。
                               合并只留一个入口，见左侧按钮。 */
                            value = nv
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fr)
                        .onFocusChanged { st ->
                            val was = focused
                            focused = st.isFocused
                            if (was && !st.isFocused && value.text != t.text) {
                                onCommitText(value.text)
                            }
                        },
                    enabled = !selectionMode,
                    /* 高亮走 VisualTransformation 而不是往字符串里插标记：
                       这里同时是可编辑输入框，改文本内容会让光标位置和真实文本错位。 */
                    visualTransformation = remember(highlight) {
                        if (highlight.isBlank()) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            KeywordHighlight(highlight)
                        }
                    },
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = VlColor.Text,
                        lineHeight = 20.sp,
                    ),
                    cursorBrush = SolidColor(VlColor.Accent),
                    decorationBox = { inner ->
                        Box {
                            inner()
                            if (value.text.isEmpty()) {
                                Text(
                                    "空行 · 点这里输入，或在此行按退格并回上一行",
                                    fontSize = 12.sp,
                                    color = VlColor.Dim.copy(alpha = 0.7f),
                                )
                            }
                        }
                    },
                )
            }
        }

        // ------------------------------------------------------------ AI 建议行
        /* 建议**另起一行**而不是挤在原文下面一小块：
           它能被单独勾选/丢弃，和转录行的交互模型才是一致的（左控制、右内容）。 */
        val suggest = t.proofText
        if (!suggest.isNullOrBlank() && !selectionMode) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(VlColor.SurfaceVar.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                /* 宽度必须和上面控制区一致（64dp），否则建议行和原文行的左边缘错开，
                   一屏里会出现两套对齐线，扫读时眼睛一直在跳。 */
                Row(
                    Modifier.width(64.dp).padding(start = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (t.proofRisk == "high") VlColor.Amber.copy(alpha = 0.18f) else VlColor.Accent.copy(alpha = 0.16f))
                            .clickable(enabled = onProofApply != null) { onProofApply?.invoke() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "✓",
                            fontSize = 11.sp,
                            color = if (t.proofRisk == "high") VlColor.Amber else VlColor.Accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(VlColor.SurfaceVar)
                            .clickable(enabled = onProofDrop != null) { onProofDrop?.invoke() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", fontSize = 10.sp, color = VlColor.Sub)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = suggest,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = when (t.proofRisk) {
                            "high" -> VlColor.Amber
                            "mid" -> VlColor.Text
                            else -> VlColor.Green
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append("AI 建议 · ")
                            append(
                                when (t.proofRisk) {
                                    "high" -> "整句重写"
                                    "mid" -> "改动较大"
                                    else -> "小改动"
                                }
                            )
                            append(" ${(t.proofRatio * 100).toInt()}%")
                        },
                        fontSize = 9.sp,
                        color = VlColor.Dim,
                    )
                }
            }
        }
    }
}

/** 说话人标签底色。与 [SpeakerChip] 同一套语义色，但这里是定宽单行，长名字省略 */
private fun speakerBg(name: String?, auto: Int, meName: String) = when {
    name == null -> VlColor.NoneBg
    /* 噪音必须在「具名说话人」之前判：它也是一个有名字的标签，
       落到 else 分支就会和真人共用绿色。 */
    AppPrefs.isNoise(name) -> VlColor.NoiseBg
    name == meName || auto == 1 -> VlColor.MeBg
    name == "他人" || auto == 2 -> VlColor.OtherBg
    else -> VlColor.NamedBg
}

private fun speakerFg(name: String?, auto: Int, meName: String) = when {
    name == null -> VlColor.NoneFg
    AppPrefs.isNoise(name) -> VlColor.NoiseFg
    name == meName || auto == 1 -> VlColor.MeFg
    name == "他人" || auto == 2 -> VlColor.OtherFg
    else -> VlColor.NamedFg
}

// ---------------------------------------------------------------------- 编辑

/**
 * 说话人候选列表。
 *
 * 顺序即优先级：我自己 → 他人 → 设置里预置的常用人 → 历史记录里出现过的名字。
 * 去重是因为同一个名字常常同时存在于预置库和历史记录里，
 * 界面上出现两个一样的 chip 会让人怀疑哪个才是对的。
 */
private fun speakerOptions(meName: String, tags: List<String>, known: List<String>): List<String> {
    val out = mutableListOf(meName, "他人")
    (tags + known).forEach { raw ->
        val n = raw.trim()
        if (n.isNotEmpty() && n !in out) out.add(n)
    }
    return out
}

@Composable
private fun TranscriptEditor(
    target: TranscriptEntity,
    meName: String,
    knownSpeakers: List<String>,
    tags: List<String>,
    onDismiss: () -> Unit,
    onSave: (text: String, speaker: String?) -> Unit,
) {
    // 用 TextFieldValue 而不是 String：备注按钮要把光标插到「【备注：】」中间，
    // 纯 String 状态无法控制光标位置，点了按钮用户还得自己挪光标。
    var value by remember { mutableStateOf(TextFieldValue(target.text)) }
    var speaker by remember { mutableStateOf(target.speakerName) }
    var custom by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    val options = remember(meName, knownSpeakers, tags) {
        speakerOptions(meName, tags, knownSpeakers)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding()) {
        Text("编辑这一句", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = VlColor.Text)
        Spacer(Modifier.height(2.dp))
        Text(
            "${fmtClockFull(target.wallClock)} · 时长 ${fmtDuration(target.endMs - target.startMs)}",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = VlColor.Dim,
        )

        Spacer(Modifier.height(14.dp))
        SectionTitle("说话人")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { opt ->
                val sel = speaker == opt
                FilterChip(
                    selected = sel,
                    onClick = { speaker = if (sel) null else opt },
                    label = { Text(opt, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = VlColor.SurfaceVar,
                        labelColor = VlColor.Sub,
                        selectedContainerColor = VlColor.MeBg,
                        selectedLabelColor = VlColor.MeFg,
                    ),
                )
            }
            TextButton(onClick = { speaker = null }) {
                Text("清除", fontSize = 11.sp, color = VlColor.Dim)
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入新名字", fontSize = 12.sp, color = VlColor.Dim) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = VlColor.Text),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VlColor.Accent,
                    unfocusedBorderColor = VlColor.Line,
                ),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { if (custom.isNotBlank()) { speaker = custom.trim(); custom = "" } },
                enabled = custom.isNotBlank(),
            ) {
                Text("用这个名字", fontSize = 12.sp, color = if (custom.isNotBlank()) VlColor.Accent else VlColor.Dim)
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 文本内容 + 备注按钮 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("文字内容")
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    val marker = "【备注：】"
                    val base = value.text
                    // 已经以备注结尾就不再叠加，避免连点变成一堆空备注
                    if (!base.endsWith(marker)) {
                        val prefix = when {
                            base.isEmpty() -> ""
                            base.endsWith("\n") -> ""
                            else -> "\n"
                        }
                        val next = base + prefix + marker
                        // 光标落在「：」之后、「】」之前，用户直接就能打字
                        value = TextFieldValue(next, TextRange(next.length - 1))
                        runCatching { focus.requestFocus() }
                    }
                },
            ) {
                Text("＋备注", fontSize = 12.sp, color = VlColor.Accent)
            }
        }

        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth().height(140.dp).focusRequester(focus),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = VlColor.Text, lineHeight = 20.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = VlColor.Accent,
                unfocusedBorderColor = VlColor.Line,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "「＋备注」用于补充这句话之外的信息，比如当时的动作或想法：",
            fontSize = 10.sp,
            color = VlColor.Dim,
        )
        Text(
            "【备注：他边说边把水杯推过来】",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = VlColor.Dim.copy(alpha = 0.8f),
        )

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("取消", fontSize = 14.sp, color = VlColor.Sub)
            }
            Button(
                onClick = { onSave(value.text.trim(), speaker) },
                enabled = value.text.isNotBlank(),
                modifier = Modifier.weight(1.6f).height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VlColor.Accent,
                    contentColor = androidx.compose.ui.graphics.Color(0xFF00204A),
                    disabledContainerColor = VlColor.SurfaceVar,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("保存", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---------------------------------------------------------------------- 批量选人

@Composable
private fun SpeakerPickerDialog(
    title: String,
    meName: String,
    knownSpeakers: List<String>,
    tags: List<String>,
    /* 这行当前已标的说话人。非空时「点同一个名字」＝确认，而不是「没变化」。
       机器猜出来的名字也写在 speakerName 上，所以不给这一层，用户就只能改判、
       没法确认 —— 那一整批「名字?」会永远挂着问号。 */
    current: String? = null,
    /* 当前名字是机器猜的时，把猜的依据（音高）一并说出来：
       不给依据的话，「A?」和「A」在用户眼里没有信息差，确认也无从判断。 */
    guessNote: String? = null,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    var custom by remember { mutableStateOf("") }

    val options = remember(meName, knownSpeakers, tags, current) {
        val base = speakerOptions(meName, tags, knownSpeakers)
        /* 当前名字可能不在候选里（声纹档案删了、或打的是临时名字），
           不补进来的话用户连「确认」这个动作都点不到。 */
        if (current != null && base.none { it == current }) listOf(current) + base else base
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VlColor.Surface,
        title = { Text(title, fontSize = 15.sp, color = VlColor.Text) },
        text = {
            Column {
                if (current != null) {
                    Text(
                        "当前标的是「$current」" + (guessNote?.let { "（$it）" } ?: "") +
                            "。点同一个名字＝确认这条判断，点别的＝改判",
                        fontSize = 11.sp,
                        color = VlColor.Dim,
                        lineHeight = 16.sp,
                    )
                } else {
                    Text("点一个直接应用", fontSize = 11.sp, color = VlColor.Dim)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    options.forEach { opt ->
                        val isCurrent = current != null && opt == current
                        FilterChip(
                            selected = isCurrent,
                            onClick = { onPick(opt) },
                            label = {
                                Text(
                                    if (isCurrent) "$opt · 确认" else opt,
                                    fontSize = 12.sp,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = VlColor.SurfaceVar,
                                labelColor = VlColor.Text,
                                selectedContainerColor = VlColor.Accent,
                                selectedLabelColor = VlColor.Surface,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("或输入新名字，如「张三」", fontSize = 12.sp, color = VlColor.Dim) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = VlColor.Text),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VlColor.Accent,
                        unfocusedBorderColor = VlColor.Line,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(custom.trim().takeIf { it.isNotBlank() }) },
                enabled = custom.isNotBlank(),
            ) {
                Text("用这个", color = if (custom.isNotBlank()) VlColor.Accent else VlColor.Dim)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) {
                    Text("清除标签", color = VlColor.Dim, fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("取消", color = VlColor.Sub, fontSize = 12.sp)
                }
            }
        },
    )
}

/**
 * 关键词涂色。
 *
 * 用 [androidx.compose.ui.text.input.VisualTransformation] 而不是在文本里插标记：
 * 这行文字同时是**可编辑输入框**，改动文本内容会让光标位置、字符串长度、
 * 自动保存的比对全部错位 —— 只有视觉层能改。
 */
private class KeywordHighlight(private val keyword: String) :
    androidx.compose.ui.text.input.VisualTransformation {

    override fun filter(text: AnnotatedString):
        androidx.compose.ui.text.input.TransformedText {
        if (keyword.isBlank()) {
            return androidx.compose.ui.text.input.TransformedText(
                text,
                androidx.compose.ui.text.input.OffsetMapping.Identity,
            )
        }
        val raw = text.text
        val builder = AnnotatedString.Builder(text)
        var from = 0
        var hits = 0
        while (hits < 50) {
            val i = raw.indexOf(keyword, from, ignoreCase = true)
            if (i < 0) break
            builder.addStyle(
                androidx.compose.ui.text.SpanStyle(
                    background = VlColor.Accent.copy(alpha = 0.30f),
                    fontWeight = FontWeight.Medium,
                ),
                i, i + keyword.length,
            )
            from = i + keyword.length
            hits++
        }
        return androidx.compose.ui.text.input.TransformedText(
            builder.toAnnotatedString(),
            androidx.compose.ui.text.input.OffsetMapping.Identity,
        )
    }
}

/** 听标条上的「上一个 / 下一个」。定宽方形，避免 A/B 名字长短把整行挤变形 */
@Composable
private fun SmallStepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(VlColor.SurfaceVar)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 18.sp, color = VlColor.Sub)
    }
}

// ---------------------------------------------------------------------- 筛选

/** 列表筛选档位。数量取自本轮会话的实时统计，不是全库 */
private enum class RowFilter(val label: String) {
    ALL("全部"),
    PROOF("AI 建议"),
    UNLABELED("未判"),

    /** 名字是 F0 猜的，声纹没跑（或跑不出结果）。实测这一档错 25.5%，最该先复核 */
    GUESSED("机器猜"),
    NOISE("噪音"),
}

/**
 * 列表筛选条。
 *
 * 为什么不做成下拉菜单：四档要**同时看到各自数量**才有决策价值 ——
 * 「AI 建议 5」这个数字一露头用户才会想去点它，藏进菜单里就永远发现不了。
 * 数量为 0 的档位直接隐藏：一排「AI 建议 0 / 未判 0 / 噪音 0」只占地方没有信息。
 *
 * 「AI 建议」筛的是**还有待确认建议**的句子（proofText 非空），
 * 采纳或忽略之后就不再出现 —— 那是「复核一遍 AI 到底想改什么」的动作，
 * 不是「查历史改动」。这两件事用的是不同的字段，不要混。
 */
@Composable
private fun RowFilterBar(
    current: RowFilter,
    counts: Map<RowFilter, Int>,
    onPick: (RowFilter) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowFilter.values().forEach { f ->
            val n = counts[f] ?: 0
            if (f != RowFilter.ALL && n == 0) return@forEach
            val on = f == current
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) VlColor.Accent.copy(alpha = 0.16f) else VlColor.SurfaceVar)
                    .then(
                        if (on) {
                            Modifier.border(
                                1.dp,
                                VlColor.Accent.copy(alpha = 0.55f),
                                RoundedCornerShape(14.dp),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onPick(f) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    f.label,
                    fontSize = 12.sp,
                    color = if (on) VlColor.Accent else VlColor.Sub,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                )
                if (n > 0) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "$n",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (on) VlColor.Accent.copy(alpha = 0.85f) else VlColor.Dim,
                    )
                }
            }
        }
    }
}

/**
 * 把散碎的中断记录合并成「停摆区间」。
 *
 * ## 为什么需要
 * 2026-11-16 之前的版本，一轮停摆里每 1.5~3.6 秒就落一条 interrupt。
 * 11-15 那轮被占用 153 分钟，库里有 **2571 条**记录 —— 直接展示就是 2571 行噪音，
 * 而用户真正要的信息只有一句「哪几个小时没录到」。
 *
 * ## 合并规则
 * 相邻两条间隔 ≤ 2 分钟即视为同一段停摆。停摆期间 App 最长 30 秒探测一次，
 * 恢复后立即停止记录，所以真实的两段停摆之间必然隔着一段正常录音（数十秒以上都算），
 * 2 分钟这个阈值在两边都不会误并。
 *
 * @return 每段停摆的 (起始墙钟, 最后一条记录墙钟)，已剔除长度为 0 的单点记录 ——
 *         「16:15 中断 0 秒」这种行对用户没有信息量，还会让真实停摆淹在噪音里
 */
private fun mergeStalls(items: List<InterruptEntity>): List<Pair<Long, Long>> {
    if (items.isEmpty()) return emptyList()
    val sorted = items.sortedBy { it.wallClock }
    val out = ArrayList<Pair<Long, Long>>()
    var start = sorted[0].wallClock
    var end = start
    for (i in 1 until sorted.size) {
        val t = sorted[i].wallClock
        if (t - end <= 120_000L) {
            end = t
        } else {
            out.add(start to end)
            start = t
            end = t
        }
    }
    out.add(start to end)
    /* 只留真的持续了时间的。单条记录（start == end）多半是「请求设备未生效」这类
       一次性通知，把它们算成「中断 0 秒」既不准也没用。 */
    return out.filter { it.second > it.first }
}

/**
 * 紧凑时长（`45s` / `12分` / `2小时33分`）。
 *
 * 与 `CaptureService.fmtSpan` 的区别：那个写进库给人读，带空格更顺；
 * 这个塞在会话头的 10sp 单行里，必须短。
 */
private fun fmtSpanShort(ms: Long): String {
    val sec = (ms / 1000L).coerceAtLeast(0L)
    if (sec < 60L) return "${sec}s"
    val m = sec / 60L
    if (m < 60L) return "${m}分"
    val h = m / 60L
    val rest = m % 60L
    return if (rest == 0L) "${h}小时" else "${h}小时${rest}分"
}
