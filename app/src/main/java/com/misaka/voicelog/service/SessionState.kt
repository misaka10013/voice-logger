package com.misaka.voicelog.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 采集会话的全局可见状态。
 *
 * 为什么用单例而不是 Binder：验证阶段只有"采集服务 → 界面"这一条单向数据流，
 * 用 StateFlow 比写 AIDL/Binder 少一半代码，且天然线程安全。
 */
object SessionState {

    data class Item(
        val id: Long,
        val wallClock: Long,
        val relMs: Long,
        val text: String,
        val lang: String,
        val durationMs: Long,
        /** 说话人标签，null = 未标注 */
        val speakerName: String? = null,
        /** 0=未判断 1=我 2=他人 */
        val speakerAuto: Int = 0,
    )

    /** 是否正在采集 */
    val running = MutableStateFlow(false)

    /** 当前状态描述，直接显示在界面上 */
    val status = MutableStateFlow("待启动")

    /** 本次采集开始时的墙钟时间 */
    val sessionStart = MutableStateFlow(0L)

    /** 本次采集对应的会话 ID，0 表示未开始。停止后保留，用于跳转查看 */
    val currentSessionId = MutableStateFlow(0L)

    /** 最近若干条识别结果，新的在前 */
    val items = MutableStateFlow<List<Item>>(emptyList())

    /** 累计语音时长（毫秒） */
    val totalSpeechMs = MutableStateFlow(0L)

    /** 累计语音段数 */
    val totalSegments = MutableStateFlow(0)

    /** 累计中断次数 */
    val interruptCount = MutableStateFlow(0)

    /** 最近一次中断原因 */
    val lastInterrupt = MutableStateFlow("")

    /** ASR 队列积压条数，用来观察算力是否跟得上 */
    val queueDepth = MutableStateFlow(0)

    /** 本轮判定为「我」的句数 */
    val meCount = MutableStateFlow(0)

    /** 本轮判定为「他人」的句数 */
    val otherCount = MutableStateFlow(0)

    /** 声纹是否已启用（有模板 + 有模型） */
    val voiceprintActive = MutableStateFlow(false)

    /**
     * 本轮是否「只录不转」。
     *
     * 界面必须能区分这两种状态：关掉实时转写后文字区不会再刷新，
     * 如果不给明确提示，用户会以为「它坏了」而不是「它在按新模式工作」。
     */
    val recordOnlyMode = MutableStateFlow(false)

    /** 本轮已落盘的音频段数（只录不转模式下代替「已识别句数」） */
    val cachedSegments = MutableStateFlow(0)

    /**
     * 实时输入电平，0~1。给界面画电平条用。
     *
     * 为什么必须有：不再实时刷文字之后，「它到底有没有在工作」这件事失去了唯一的视觉证据。
     * 电平条是解耦后唯一能证明「麦克风在收音」的东西，不是装饰。
     */
    val level = MutableStateFlow(0f)

    /** 初始化失败原因（模型缺失、权限等） */
    val error = MutableStateFlow<String?>(null)

    /**
     * 本轮实际使用的录音设备，形如 `USB 麦克风 · DJI Mic · 2ch`。空串 = 还没读到。
     *
     * 读到的时机在 `startRecording()` 之后（之前查不到），所以要能容忍先空后有。
     * 用户要判断「外接麦有没有接上」，只有这个字符串能回答。
     */
    val inputDevice = MutableStateFlow("")

    fun reset() {
        items.value = emptyList()
        totalSpeechMs.value = 0L
        totalSegments.value = 0
        interruptCount.value = 0
        lastInterrupt.value = ""
        queueDepth.value = 0
        meCount.value = 0
        otherCount.value = 0
        error.value = null
        recordOnlyMode.value = false
        cachedSegments.value = 0
        level.value = 0f
        inputDevice.value = ""
    }

    fun addItem(item: Item) {
        items.value = (listOf(item) + items.value).take(MAX_ITEMS)
        totalSpeechMs.value += item.durationMs
        totalSegments.value += 1
        when (item.speakerAuto) {
            1 -> meCount.value += 1
            2 -> otherCount.value += 1
        }
    }

    /**
     * 用户在多选里改了某句的说话人，同步更新实时页的缓存，
     * 否则从记录页返回监听页会看到旧标签。
     */
    fun renameSpeaker(ids: Set<Long>, name: String?) {
        if (ids.isEmpty()) return
        items.value = items.value.map { if (it.id in ids) it.copy(speakerName = name) else it }
    }

    fun noteInterrupt(reason: String) {
        interruptCount.value += 1
        lastInterrupt.value = reason
    }

    private const val MAX_ITEMS = 200
}
