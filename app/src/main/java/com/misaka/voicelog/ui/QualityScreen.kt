package com.misaka.voicelog.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.misaka.voicelog.core.TextDiff
import com.misaka.voicelog.data.DayStat
import com.misaka.voicelog.data.KindStat
import com.misaka.voicelog.data.MicStat
import com.misaka.voicelog.data.QualityTotals
import com.misaka.voicelog.data.SourceStat
import com.misaka.voicelog.data.SpeakerFixStat
import com.misaka.voicelog.data.TranscriptEditEntity
import com.misaka.voicelog.service.VoiceLogDatabaseHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 识别质量面板。
 *
 * ## 这个页面存在的前提
 * 「识别准不准」过去只能靠感觉，于是「换模型还是调阈值」这种决策全靠猜。
 * 这里所有数字都是**从你自己做的编辑里反推出来的**，不需要额外标注任何东西 ——
 * 你本来就在改错字、改说话人，那些动作本身就是标注。
 *
 * ## 三条读数纪律（写在界面上，也约束这个文件）
 * 1. **不显示没有数据支撑的指标**。宁可写「暂无」并说明为什么，也不给一个
 *    看起来像结论其实是猜测的数。改判率就是从 v7 才开始积累的，之前的数据
 *    没有机器值快照，硬算出来的会是假的。
 * 2. **每个数字都要能回答「那我该动哪个旋钮」**。所以改动按性质分类显示，
 *    只给一个总「改动率」等于什么都没说 —— 删「嗯」和整句听错在总量里长得一样。
 * 3. **口径写在数字旁边**，不藏在注释里。用户看不到口径就会把
 *    「改动率」当成「错误率」，然后基于错的理解去调参数。
 */
@Composable
fun QualityScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { VoiceLogDatabaseHolder.get(ctx) }
    var data by remember { mutableStateOf<QualityData?>(null) }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(reloadTick) {
        data = withContext(Dispatchers.IO) {
            val dao = db.editDao()
            QualityData(
                totals = dao.totals(),
                kinds = dao.textKinds(),
                sources = dao.textSources(),
                fixes = dao.speakerFixes(),
                mics = dao.byMic(),
                days = dao.byDay(),
            )
        }
    }

    Column(Modifier.fillMaxSize().background(VlColor.Bg)) {
        // ------------------------------------------------ 顶栏
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("‹ 返回", fontSize = 14.sp, color = VlColor.Accent)
            }
            Spacer(Modifier.weight(1f))
            Text("识别质量", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = VlColor.Text)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { reloadTick++ }) {
                Text("刷新", fontSize = 14.sp, color = VlColor.Sub)
            }
        }

        val d = data
        if (d == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("统计中…", fontSize = 13.sp, color = VlColor.Dim)
            }
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "数字全部来自你自己的修改，不需要额外标注。",
                fontSize = 11.sp,
                color = VlColor.Dim,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(14.dp))

            OverviewCards(d.totals, d.kinds)

            Spacer(Modifier.height(18.dp))
            SectionTitle("改的是什么性质", "决定下一步该动哪个旋钮")
            KindBars(d.kinds)

            Spacer(Modifier.height(18.dp))
            SectionTitle("说话人：改判与补标", "机器给错的 vs 机器不敢给的")
            SpeakerSection(d.totals, d.fixes)

            Spacer(Modifier.height(18.dp))
            SectionTitle("按录音通路", "判断是不是麦克风的锅")
            MicSection(d.mics)

            Spacer(Modifier.height(18.dp))
            SectionTitle("按天", "换模型/改参数之后，这条线有没有往下走")
            DaySection(d.days)

            if (d.sources.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                SectionTitle("改动来源", "")
                SourceSection(d.sources)
            }

            Spacer(Modifier.height(28.dp))
            Text(
                "从 v7 开始，每次改文字/改说话人都会留一条记录。" +
                    "已识别原文只在你第一次修改时留存，所以更早的句子没有字错率可算。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- 概览

@Composable
private fun OverviewCards(t: QualityTotals, kinds: List<KindStat>) {
    /* 字错率口径：只统计「文字类」改动，且剔除结构整理（拆行/合并）。
       分母是这些句子的真值字数，分子是相对识别原文的错字数。 */
    val textKinds = kinds.filter {
        it.kind != TranscriptEditEntity.KIND_SPLIT && it.kind != TranscriptEditEntity.KIND_MERGE
    }
    val ops = textKinds.sumOf { it.ops }
    val fillerDel = kinds.firstOrNull { it.kind == TextDiff.Kind.FILLER }?.del ?: 0
    val cleanOps = (ops - fillerDel).coerceAtLeast(0)
    val chars = textKinds.sumOf { it.chars }
    val cer = if (chars > 0) cleanOps.toDouble() / chars else null

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigMetric(
            label = "文字改动率",
            value = pct(t.editedRows, t.total),
            hint = "${t.editedRows} / ${t.total} 句",
            tone = VlColor.Accent,
            modifier = Modifier.weight(1f),
        )
        BigMetric(
            label = "字错率",
            value = cer?.let { "%.1f%%".format(it * 100) } ?: "—",
            hint = if (chars > 0) "$cleanOps / $chars 字" else "还没有可算的样本",
            tone = if ((cer ?: 0.0) > 0.15) VlColor.Red else VlColor.Green,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigMetric(
            label = "说话人改判率",
            value = if (t.speakerAutoNamed > 0) "%.0f%%".format(t.relabelRate * 100) else "—",
            hint = if (t.speakerAutoNamed > 0) {
                "机器给了名字 ${t.speakerAutoNamed} 句"
            } else {
                "还没有机器给名字的样本"
            },
            tone = VlColor.Amber,
            modifier = Modifier.weight(1f),
        )
        BigMetric(
            label = "漏判率",
            value = pct(t.unlabeled, t.total),
            hint = "${t.unlabeled} 句没有名字",
            tone = VlColor.Sub,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BigMetric(
    label: String,
    value: String,
    hint: String,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(VlColor.Surface)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(label, fontSize = 10.sp, color = VlColor.Dim)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = tone,
        )
        Spacer(Modifier.height(2.dp))
        Text(hint, fontSize = 9.sp, color = VlColor.Dim)
    }
}

// ---------------------------------------------------------------- 改动性质

@Composable
private fun KindBars(kinds: List<KindStat>) {
    val shown = kinds.filter {
        it.kind != TranscriptEditEntity.KIND_SPLIT && it.kind != TranscriptEditEntity.KIND_MERGE
    }
    if (shown.isEmpty()) {
        EmptyHint(
            "还没有文字修改记录。改一句错字再回来看。",
        )
        return
    }
    val max = shown.maxOf { it.edits }.coerceAtLeast(1)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(VlColor.Surface)
            .padding(12.dp),
    ) {
        shown.forEach { k ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TextDiff.describe(k.kind),
                    fontSize = 11.sp,
                    color = VlColor.Text,
                    modifier = Modifier.width(64.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(VlColor.SurfaceVar),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(k.edits.toFloat() / max)
                            .height(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(kindColor(k.kind)),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${k.edits}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = VlColor.Sub,
                    modifier = Modifier.width(38.dp),
                )
                Text(
                    if (k.ops > 0) "${k.ops} 字" else "—",
                    fontSize = 9.sp,
                    color = VlColor.Dim,
                    modifier = Modifier.width(44.dp),
                )
            }
            Spacer(Modifier.height(7.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            kindAdvice(shown.map { it.kind }),
            fontSize = 10.sp,
            color = VlColor.Sub,
            lineHeight = 15.sp,
        )
    }
}

/** 把分布翻译成「该动哪个旋钮」。这是整个页面唯一真正的结论输出 */
private fun kindAdvice(kinds: List<String>): String {
    val top = kinds.firstOrNull() ?: return ""
    return when (top) {
        TextDiff.Kind.FILLER ->
            "主要在删口水词 → 不是模型不准，是 ASR 把语气词全留下来了。该收敛输出格式，而不是换模型。"
        TextDiff.Kind.INSERT ->
            "主要在补字 → 原文漏字占多数。多半是 VAD 切早了或增益太低，把字吃掉了，调参有用。"
        TextDiff.Kind.DELETE ->
            "主要在删字 → 原文多字。噪音被当成人声、或重复解码，先查 VAD 阈值。"
        TextDiff.Kind.SUBSTITUTE ->
            "主要是同音字 → 这是模型解码上限，调阈值没用。要么换更大模型，要么接受并靠校对补。"
        TextDiff.Kind.MIXED ->
            "多为整句重写 → 一句里错得太多。先看是不是串音或远场，那是录制端问题，不是模型问题。"
        TextDiff.Kind.SAME ->
            "只动了标点 → 文字本身是准的，问题在断句规则。"
        else -> ""
    }
}

private fun kindColor(kind: String): Color = when (kind) {
    TextDiff.Kind.FILLER -> VlColor.Amber
    TextDiff.Kind.INSERT -> VlColor.Accent
    TextDiff.Kind.DELETE -> VlColor.Red
    TextDiff.Kind.SUBSTITUTE -> Color(0xFFC3A6E0)
    TextDiff.Kind.MIXED -> Color(0xFFE07A5F)
    TextDiff.Kind.SAME -> VlColor.Dim
    else -> VlColor.Sub
}

// ---------------------------------------------------------------- 说话人

@Composable
private fun SpeakerSection(t: QualityTotals, fixes: List<SpeakerFixStat>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(VlColor.Surface)
            .padding(12.dp),
    ) {
        if (t.speakerAutoNamed == 0 && t.speakerFixed == 0) {
            EmptyHint("改判记录从这一版才开始积累。之前改过的名字没有留下机器值，算不出来。")
            return@Column
        }
        InfoLine("机器给了名字", "${t.speakerAutoNamed} 句")
        InfoLine("其中被你改掉", "${t.speakerFixOnNamed} 句 · ${pct(t.speakerFixOnNamed, t.speakerAutoNamed)}")
        InfoLine("机器没给名字", "${t.total - t.speakerAutoNamed} 句")
        InfoLine("其中你补标", "${(t.speakerFixed - t.speakerFixOnNamed).coerceAtLeast(0)} 句 · ${"%.0f%%".format(t.fillRate * 100)}")

        /* 这一行是必须的：旧数据里改判率必然是 0（历史没有机器值快照），
           不解释的话，用户会以为「统计坏了」或者「我明明改过很多」——
           而真实原因是那时候的改判把机器值一起抹掉了，无从回填。 */
        if (t.speakerFixOnNamed == 0 && t.speakerFixed == 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "改判是 0 —— 历史改过的名字没有留下机器当时的判断，回填不了。从今天起每改一次都会记下来。",
                fontSize = 10.sp,
                color = VlColor.Amber,
                lineHeight = 15.sp,
            )
        }

        if (fixes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("改判明细", fontSize = 10.sp, color = VlColor.Dim)
            Spacer(Modifier.height(4.dp))
            fixes.take(8).forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        "${f.fromName.ifBlank { "未判" }} → ${f.toName}",
                        fontSize = 11.sp,
                        color = VlColor.Sub,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${f.cnt} 句",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (f.cnt > 20) VlColor.Red else VlColor.Dim,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "某一对数字特别大，说明两人在那条通路上被系统认混了。先重录那一侧的声纹，再考虑改阈值。",
                fontSize = 10.sp,
                color = VlColor.Dim,
                lineHeight = 15.sp,
            )
        }
    }
}

// ---------------------------------------------------------------- 分组

@Composable
private fun MicSection(mics: List<MicStat>) {
    if (mics.isEmpty()) {
        EmptyHint("还没有数据。")
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(VlColor.Surface)
            .padding(12.dp),
    ) {
        mics.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    micLabel(m.micKey),
                    fontSize = 11.sp,
                    color = VlColor.Text,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${m.total} 句",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = VlColor.Dim,
                    modifier = Modifier.width(58.dp),
                )
                Text(
                    pct(m.editedRows, m.total),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = VlColor.Accent,
                    modifier = Modifier.width(52.dp),
                )
                Text(
                    if (m.speakerAutoNamed > 0) "%.0f%%".format(m.speakerFixed.toDouble() / m.speakerAutoNamed * 100) else "—",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = VlColor.Amber,
                    modifier = Modifier.width(48.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Text("句数", fontSize = 9.sp, color = VlColor.Dim, modifier = Modifier.width(58.dp))
            Text("改字", fontSize = 9.sp, color = VlColor.Dim, modifier = Modifier.width(52.dp))
            Text("改人", fontSize = 9.sp, color = VlColor.Dim, modifier = Modifier.width(48.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "同一批人、相近场景下，两条通路的差距就是麦克风本身的代价。" +
                "如果蓝牙那条明显更差，先换麦或改佩戴方式，调参数救不回来。",
            fontSize = 10.sp,
            color = VlColor.Dim,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun DaySection(days: List<DayStat>) {
    if (days.isEmpty()) {
        EmptyHint("还没有数据。")
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(VlColor.Surface)
            .padding(12.dp),
    ) {
        days.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    d.day,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = VlColor.Sub,
                    modifier = Modifier.width(48.dp),
                )
                Text(
                    "${d.total} 句",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = VlColor.Dim,
                    modifier = Modifier.width(60.dp),
                )
                Text(
                    pct(d.editedRows, d.total),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = VlColor.Accent,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (d.speakerAutoNamed > 0) "%.0f%%".format(d.speakerFixed.toDouble() / d.speakerAutoNamed * 100) else "—",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = VlColor.Amber,
                )
            }
        }
    }
}

@Composable
private fun SourceSection(sources: List<SourceStat>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(VlColor.Surface)
            .padding(12.dp),
    ) {
        sources.forEach { s ->
            InfoLine(sourceLabel(s.source), "${s.rows} 句")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "「应用 AI 建议」占比高，说明 ASR 出的字不对但模型能读出应该是哪个词 —— " +
                "此时换模型收益有限，把校对流程用顺更划算。",
            fontSize = 10.sp,
            color = VlColor.Dim,
            lineHeight = 15.sp,
        )
    }
}

// ---------------------------------------------------------------- 零件

@Composable
private fun SectionTitle(title: String, hint: String) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = VlColor.Text)
    if (hint.isNotBlank()) {
        Text(hint, fontSize = 10.sp, color = VlColor.Dim)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 11.sp, color = VlColor.Sub, modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = VlColor.Text)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = VlColor.Dim,
        lineHeight = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(VlColor.Surface)
            .padding(12.dp),
    )
}

private fun pct(n: Int, d: Int): String = if (d <= 0) "—" else "%.1f%%".format(n * 100.0 / d)

private fun micLabel(micKey: String): String = when {
    micKey.isBlank() -> "未知通路"
    micKey.startsWith("bt|") -> "蓝牙 " + micKey.removePrefix("bt|")
    micKey.startsWith("wired|") -> "有线"
    micKey.startsWith("usb|") -> "USB " + micKey.removePrefix("usb|")
    micKey == "builtin" -> "手机内置"
    else -> micKey
}

private fun sourceLabel(source: String): String = when (source) {
    TranscriptEditEntity.SRC_USER -> "手动改"
    TranscriptEditEntity.SRC_PROOF -> "应用 AI 建议"
    TranscriptEditEntity.SRC_SPLIT -> "拆行"
    TranscriptEditEntity.SRC_MERGE -> "合并行"
    else -> source
}

private data class QualityData(
    val totals: QualityTotals,
    val kinds: List<KindStat>,
    val sources: List<SourceStat>,
    val fixes: List<SpeakerFixStat>,
    val mics: List<MicStat>,
    val days: List<DayStat>,
)
