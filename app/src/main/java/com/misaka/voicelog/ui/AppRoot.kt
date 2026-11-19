package com.misaka.voicelog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.misaka.voicelog.core.AppPrefs

/**
 * 应用根节点与三个主页面之间的切换。
 *
 * 没有引入 navigation-compose：全部路由状态就是「当前 tab」+「打开哪个会话」两个变量，
 * 用一个 Compose 状态就够了。多引一个库换来的是版本冲突和额外 300KB 体积，不值。
 */
@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openSession by rememberSaveable { mutableLongStateOf(-1L) }

    /** 从检索结果进入会话时要高亮的关键词。空 = 正常打开 */
    var openHighlight by rememberSaveable { mutableStateOf("") }
    var showEnroll by rememberSaveable { mutableStateOf(false) }

    /** 识别质量面板。是全屏子页，和声纹录入同级 */
    var showQuality by rememberSaveable { mutableStateOf(false) }

    /** 要重录的人。null = 新增一个说话人 */
    var enrollTarget by rememberSaveable { mutableStateOf<String?>(null) }

    VoiceLogTheme {
        /* 系统返回键 / 边缘滑动手势必须「逐层回退」，不能直接 finish 掉 Activity。
           没接这个回调时，在会话详情里从屏幕边缘划一下整个 app 就退出了，
           用户还得重进记录页找刚才那一轮 —— 这是纯 bug，不是设计取舍。

           只在「子页面开着」时拦截：停在底部 tab 页上不拦，否则连退出 app 都做不到。
           注册位置刻意放在 when 之前：OnBackPressedDispatcher 是后注册先执行，
           这样详情页 / 录入页内部的回调（如退出多选）会先于这里被调用。 */
        BackHandler(enabled = showEnroll || showQuality || openSession > 0) {
            when {
                showEnroll -> {
                    showEnroll = false
                    enrollTarget = null
                }
                showQuality -> showQuality = false
                else -> openSession = -1L
            }
        }

        when {
            // 声纹录入必须全屏：它是一段「不能被打断」的录音流程，
            // 留着底部 tab 会让人误触跳到记录页，4 句录到一半就白费了。
            showEnroll -> VoiceprintEnrollScreen(
                editProfileId = enrollTarget,
                onDone = {
                    showEnroll = false
                    enrollTarget = null
                },
                onCancel = {
                    showEnroll = false
                    enrollTarget = null
                },
            )

            showQuality -> QualityScreen(onBack = { showQuality = false })

            openSession > 0 -> SessionDetailScreen(
                sessionId = openSession,
                highlight = openHighlight,
                onBack = {
                    openSession = -1L
                    openHighlight = ""
                },
            )

            else -> Scaffold(
                containerColor = VlColor.Bg,
                bottomBar = { BottomBar(current = tab, onSelect = { tab = it }) },
            ) { inset ->
                Box(Modifier.fillMaxSize().padding(inset)) {
                    when (tab) {
                        0 -> MonitorScreen(onOpenCurrentSession = { id ->
                            openHighlight = ""
                            openSession = id
                        })
                        1 -> RecordsScreen(onOpenSession = { id, kw ->
                            openHighlight = kw
                            openSession = id
                        })
                        else -> SettingsScreen(
                            onEnroll = { id ->
                                enrollTarget = id
                                showEnroll = true
                            },
                            onOpenQuality = { showQuality = true },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 自绘底部栏。
 *
 * 不用 NavigationBar + Icons 的原因：Material 的 core 图标集里没有麦克风，
 * 拉 extended 会多 4MB 依赖。中文两字在这儿比图标更清楚，也就不必为图标付代价。
 */
@Composable
private fun BottomBar(current: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("监听" to "实时转写", "记录" to "历史会话", "设置" to "声纹与模型")

    Column(
        Modifier
            .fillMaxWidth()
            .background(VlColor.Surface)
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(VlColor.Line))
        Row(
            Modifier.fillMaxWidth().height(58.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, (label, hint) ->
                val active = i == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) VlColor.Accent else VlColor.Dim,
                    )
                    Text(
                        text = hint,
                        fontSize = 9.sp,
                        color = if (active) VlColor.Accent.copy(alpha = 0.6f) else VlColor.Dim.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- 公共小件

/**
 * 说话人标签。三种来源用三种颜色，一眼能分清「我」「他人」「你标的具名人物」。
 *
 * 颜色判定以「名字」为准而不是「auto 字段」：用户批量改成「我」之后，
 * auto 仍然是原来的值（保留它是为了评估声纹准确率），但显示上必须立刻变成我。
 */
@Composable
fun SpeakerChip(
    name: String?,
    auto: Int,
    score: Float = 0f,
    compact: Boolean = false,
    meName: String = "我",
) {
    val kind = when {
        name == null -> 0
        /* 噪音要排在具名之前：它本身就有名字，落到 else 会和真人同色 */
        AppPrefs.isNoise(name) -> 4
        name == meName || auto == 1 -> 1
        name == "他人" || auto == 2 -> 2
        else -> 3
    }
    val (bg, fg) = when (kind) {
        0 -> VlColor.NoneBg to VlColor.NoneFg
        1 -> VlColor.MeBg to VlColor.MeFg
        2 -> VlColor.OtherBg to VlColor.OtherFg
        4 -> VlColor.NoiseBg to VlColor.NoiseFg
        else -> VlColor.NamedBg to VlColor.NamedFg
    }
    /* 「未判」不是「未标」：名字为空是**机器的结论**（没把握就不猜），
       不是「用户还没填」。措辞要让用户一眼看出这是系统行为，不用去补。 */
    val text = name ?: "未判"

    Row(
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = if (compact) 4.dp else 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = if (compact) 9.sp else 10.sp, color = fg, fontWeight = FontWeight.Medium)
        if (score > 0f && name != null) {
            Text(
                text = " %.0f".format(score * 100),
                fontSize = 8.sp,
                color = fg.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = VlColor.Sub,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
