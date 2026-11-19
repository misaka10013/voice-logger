package com.misaka.voicelog.core

import android.content.Context
import java.io.File

/**
 * 模型文件位置。
 *
 * 模型约 260MB，不打进 APK。安装后由 adb 侧推入，按以下顺序查找：
 *
 *   1. 内部存储  /data/data/<pkg>/files/models          ← 首选
 *   2. 外部私有  /storage/emulated/0/Android/data/<pkg>/files/models
 *
 * 为什么内部优先：实测通过 adb push 写入外部私有目录后，
 * 应用侧因 FUSE 视图未同步而读不到文件（文件管理器可见、File.exists() 为 false）。
 * 内部存储走 run-as + /data/local/tmp 中转写入，没有这个问题，且不受分区存储策略影响。
 *
 * 声纹模型是「可选依赖」：没有它照样能录能转写，只是不做说话人自动判断。
 * 因此它不参与 [ready] 的判定，单独用 [spkReady] 查。
 */
object ModelPaths {

    /** 转写必需的三个文件 */
    private val REQUIRED = listOf("model.int8.onnx", "tokens.txt", "silero_vad.onnx")

    const val ASR_MODEL = "model.int8.onnx"
    const val ASR_TOKENS = "tokens.txt"
    const val VAD_MODEL = "silero_vad.onnx"

    /** 声纹模型（可选） */
    const val SPK_MODEL = "spk_campplus.onnx"

    private fun candidates(context: Context): List<File> = buildList {
        add(File(context.filesDir, "models"))
        context.getExternalFilesDir(null)?.let { add(File(it, "models")) }
    }

    /** 当前实际生效的目录（供界面展示，方便排查） */
    fun dir(context: Context): File {
        val list = candidates(context)
        list.firstOrNull { d -> REQUIRED.any { File(d, it).isFile } }?.let { return it }
        list.firstOrNull { it.isDirectory }?.let { return it }
        return list.first().apply { mkdirs() }
    }

    fun asrModel(context: Context): String = resolve(context, ASR_MODEL)
    fun asrTokens(context: Context): String = resolve(context, ASR_TOKENS)
    fun vadModel(context: Context): String = resolve(context, VAD_MODEL)
    fun spkModel(context: Context): String = resolve(context, SPK_MODEL)

    private fun resolve(context: Context, name: String): String {
        candidates(context)
            .map { File(it, name) }
            .firstOrNull { it.isFile }
            ?.let { return it.absolutePath }
        return File(dir(context), name).absolutePath
    }

    /** 转写链路是否可用（三个必需文件齐全，任意一个候选目录满足即可） */
    fun ready(context: Context): Boolean =
        candidates(context).any { d -> REQUIRED.all { File(d, it).isFile } }

    /** 声纹模型是否可用（可选能力） */
    fun spkReady(context: Context): Boolean =
        candidates(context).any { d -> File(d, SPK_MODEL).isFile }

    fun missing(context: Context): List<String> {
        val d = candidates(context).firstOrNull { dir ->
            REQUIRED.any { File(dir, it).isFile }
        } ?: candidates(context).first()
        return REQUIRED.filter { !File(d, it).isFile }
    }
}
