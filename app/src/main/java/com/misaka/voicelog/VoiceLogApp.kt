package com.misaka.voicelog

import android.app.Application
import com.misaka.voicelog.core.AppPrefs
import com.misaka.voicelog.core.PersonStore
import com.misaka.voicelog.core.RecycleBin
import com.misaka.voicelog.core.VoiceprintStore
import com.misaka.voicelog.service.VoiceLogDatabaseHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VoiceLogApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 提前建库，避免首次识别时才初始化导致的卡顿
        VoiceLogDatabaseHolder.get(this)

        /* 回收站到期清理。放启动时跑一次，是因为它是唯一「随时间恶化」的动作：
           不做的话，删掉的音频永远躺在磁盘里占空间，而界面显示已经删了。 */
        scope.launch {
            runCatching { RecycleBin.expire(this@VoiceLogApp) }
        }

        /* 一次性把噪音标签的历史别名统一成当前名（电视外放/外放 → 视频噪音）。
           118 句旧数据不统一的话，界面配色虽然已经按别名组兜住了，
           但句子行上的标签字、导出文本、筛选统计里仍会出现两套名字。
           标志位保证只跑一次；改名即使中途失败，下次启动还会重试（标志未写入）。 */
        scope.launch {
            runCatching {
                if (!AppPrefs.noiseRenameDone(this@VoiceLogApp)) {
                    val dao = VoiceLogDatabaseHolder.get(this@VoiceLogApp).transcriptDao()
                    AppPrefs.NOISE_ALIASES
                        .filterNot { it == AppPrefs.NOISE_LABEL }
                        .forEach { dao.renameSpeakerAlias(it, AppPrefs.NOISE_LABEL) }
                    AppPrefs.markNoiseRenameDone(this@VoiceLogApp)
                }
            }
        }

        /* 「人」这一层的结构迁移（档案自带名字 → 人 + 档案）。
           放启动时跑，是为了让用户「打开就看见分组是对的」，而不是等他去设置页
           或者录完一轮才发现。迁移本身是幂等的，标志位在函数内部维护。 */
        scope.launch {
            runCatching {
                PersonStore.ensureMigrated(this@VoiceLogApp, VoiceprintStore.profiles(this@VoiceLogApp))
            }
        }
    }
}
