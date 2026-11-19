package com.misaka.voicelog.core

import android.content.Context
import com.misaka.voicelog.audio.AudioStore
import com.misaka.voicelog.service.VoiceLogDatabaseHolder

/**
 * 回收站的到期清理。
 *
 * ## 为什么必须有这个东西
 * 「删除」在本地只是打标记：会话、句子、段落一起标上 `deletedAt`，文件留着 ——
 * 这样 7 天内点恢复是真的能恢复（文字和音频都回来了，不是一个空壳）。
 * 但「能恢复」的另一面是「不会自动消失」：没有这段代码，标了删除的音频会
 * 一直躺在 `files/audio/<sid>/` 里，空间只涨不降，而界面上的删除看起来生效了。
 *
 * ## 保留期
 * [KEEP_DAYS] = 7 天，与界面上写的「7 天内可在回收站恢复」严格一致。
 * 改这个常量必须同步改 `RecordsScreen` 里的文案，否则就是欺骗用户。
 *
 * ## 清理时机
 * 只在 App 启动时跑一次（见 `VoiceLogApp`）。不做定时任务：这个动作对实时性
 * 没有任何要求，早一小时晚一小时都无所谓，而常驻定时器要额外付出电量和唤醒。
 */
object RecycleBin {

    const val KEEP_DAYS = 7

    private const val KEEP_MS = KEEP_DAYS * 24L * 60 * 60 * 1000

    /**
     * 清掉过期的东西。返回清掉的会话轮数（仅供日志）。
     *
     * 顺序很重要：**先删这一轮的音频文件，再删数据库行**。
     * 反过来的话，一旦在两步之间被杀进程，文件就成了没人认领的孤儿 ——
     * 库里没有记录指向它，界面永远看不见，也永远不会被删。
     */
    suspend fun expire(ctx: Context): Int {
        val db = VoiceLogDatabaseHolder.get(ctx)
        val cutoff = System.currentTimeMillis() - KEEP_MS
        var sessions = 0

        runCatching {
            val expired = db.sessionDao().expired(cutoff)
            for (s in expired) {
                runCatching { AudioStore.deleteSessionAudio(ctx, s.id) }
                db.transcriptDao().hardDeleteBySession(s.id)
                db.segmentDao().hardDeleteBySession(s.id)
                db.sessionDao().hardDelete(s.id)
                sessions++
            }
        }

        /* 单独删掉的句子（会话还在）没有对应的音频要清，直接按时间戳真删。
           放在会话循环之后：会话删掉的行已经在上面清干净了，这里只处理剩下的。 */
        runCatching { db.transcriptDao().purgeDeletedBefore(cutoff) }
        runCatching { db.segmentDao().purgeDeletedBefore(cutoff) }
        runCatching { AudioStore.pruneEmptyDirs(ctx) }

        return sessions
    }
}
