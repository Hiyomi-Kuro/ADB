package com.kaori.adb.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.kaori.adb.R
import com.kaori.adb.acs.AcsAdbTransport
import com.kaori.adb.accessibility.AgentAccessibilityService
import com.kaori.adb.agent.AgentEngine
import com.kaori.adb.agent.ToolCall
import com.kaori.adb.chat.CodexBridgeClient
import com.kaori.adb.chat.CodexConversation
import com.kaori.adb.chat.CodexConversationSummary
import com.kaori.adb.chat.CodexMessage
import com.kaori.adb.tools.AppResolver
import com.kaori.adb.tools.LocalControlSuggestions
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingBubbleService : Service() {
    private data class OverlayAction(
        val label: String,
        val onClick: () -> Unit
    )

    private enum class ConversationMode(
        val label: String,
        val description: String
    ) {
        LOCAL(
            label = "本地",
            description = "仅使用 ADB 应用内置本地工具操控手机，不调用 Codex。"
        ),
        CHAT(
            label = "聊天",
            description = "通过 Codex 聊天或执行任务，不走本地快速控制。"
        ),
        CONTROL(
            label = "操控",
            description = "所有手机操控都必须经过 Codex 工具链，禁止本地快速控制。"
        )
    }

    private data class PanelState(
        val focusable: Boolean,
        val tag: String? = null,
        val builder: () -> View
    )

    private lateinit var windowManager: WindowManager
    private lateinit var engine: AgentEngine
    private lateinit var acsAdb: AcsAdbTransport
    private lateinit var codexClient: CodexBridgeClient
    private val worker = Executors.newSingleThreadExecutor()
    private val harnessPollWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val panelStack = ArrayDeque<PanelState>()

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var toolSequence = 0L
    private var barLengthDp = DEFAULT_BAR_LENGTH_DP
    private var barWidthDp = DEFAULT_BAR_WIDTH_DP
    private var barAlphaPercent = DEFAULT_BAR_ALPHA_PERCENT
    private var panelAlphaPercent = DEFAULT_PANEL_ALPHA_PERCENT
    private val chatMessages = mutableListOf<CodexMessage>()
    private val localMessages = mutableListOf<CodexMessage>()
    private var currentConversationId: String? = null
    private var currentConversationTitle = "新对话"
    private var conversationMode = ConversationMode.CHAT
    private var chatBusy = false
    private var chatStatus = ""
    @Volatile private var harnessPollGeneration = 0L

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        engine = AgentEngine(applicationContext)
        acsAdb = AcsAdbTransport(applicationContext)
        codexClient = CodexBridgeClient(applicationContext)
        loadBarPreferences()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubble == null && Settings.canDrawOverlays(this)) {
            showBubble()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        closePanel()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        bubbleParams = null
        worker.shutdownNow()
        harnessPollWorker.shutdownNow()
        super.onDestroy()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.overlay_notification))
        .setOngoing(true)
        .build()

    private fun showBubble() {
        if (bubble != null) return

        val view = TextView(this).apply {
            text = ""
            gravity = Gravity.CENTER
            alpha = barAlphaPercent / 100f
            background = barBackground()
        }
        val layoutParams = WindowManager.LayoutParams(
            dp(barWidthDp),
            dp(barLengthDp),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(180)
        }
        attachTouch(view, layoutParams)
        windowManager.addView(view, layoutParams)
        bubble = view
        bubbleParams = layoutParams
    }

    private fun attachTouch(view: View, layoutParams: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialPanelX = 0
        var initialPanelY = 0
        var downX = 0f
        var downY = 0f
        var moved = false
        var longPressed = false
        lateinit var longPressAction: Runnable

        longPressAction = Runnable {
            if (!moved) {
                longPressed = true
                stopSelf()
            }
        }

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialPanelX = panelParams?.x ?: 0
                    initialPanelY = panelParams?.y ?: 0
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    longPressed = false
                    main.postDelayed(longPressAction, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > dp(4) || abs(dy) > dp(4))) {
                        moved = true
                        main.removeCallbacks(longPressAction)
                    }
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(view, layoutParams)

                    val currentPanel = panel
                    val currentPanelParams = panelParams
                    if (currentPanel != null && currentPanelParams != null) {
                        currentPanelParams.x = initialPanelX + dx.toInt()
                        currentPanelParams.y = initialPanelY + dy.toInt()
                        windowManager.updateViewLayout(currentPanel, currentPanelParams)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPressAction)
                    if (longPressed) return@setOnTouchListener true

                    if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        toggleRootPanel()
                    } else if (moved) {
                        snapBubbleToEdge(view, layoutParams, event.rawX)
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun snapBubbleToEdge(
        view: View,
        layoutParams: WindowManager.LayoutParams,
        rawX: Float
    ) {
        val width = resources.displayMetrics.widthPixels
        layoutParams.x = if (rawX < width / 2f) 0 else width - view.width
        layoutParams.y = layoutParams.y.coerceIn(0, max(0, resources.displayMetrics.heightPixels - view.height))
        windowManager.updateViewLayout(view, layoutParams)
        repositionPanelNextToBubble()
    }

    private fun toggleRootPanel() {
        if (panel != null) {
            collapsePanel()
        } else if (panelStack.isNotEmpty()) {
            renderCurrentPanel()
        } else {
            openChatPanel()
        }
    }

    private fun openChatPanel() {
        panelStack.clear()
        pushPanel(focusable = true, tag = CHAT_PANEL_TAG) {
            buildChatPanel()
        }
    }

    private fun openSettingsPanel() {
        pushPanel {
            buildMenuPanel(
                title = "设置",
                subtitle = "Codex / ADB / 悬浮窗",
                actions = listOf(
                    OverlayAction("Codex") { showCodexMenu() },
                    OverlayAction("连接 / 状态") { showConnectionMenu() },
                    OverlayAction("设备信息") { showDeviceMenu() },
                    OverlayAction("应用控制") { showAppMenu() },
                    OverlayAction("悬浮条外观") { showOverlayAppearanceMenu() },
                    OverlayAction("界面控制") { showUiMenu() },
                    OverlayAction("权限中心") { showPermissionCenter() },
                    OverlayAction("原始 Shell / 风险边界") { showShellMenu() },
                    OverlayAction("Tools") {
                        pushInfo("Tools", engine.toolSummary())
                    },
                    OverlayAction("Trace") {
                        pushInfo("Trace", engine.recentTrace().ifBlank { "暂无 Trace。" })
                    }
                )
            )
        }
    }

    private fun showCodexMenu() {
        pushPanel {
            buildMenuPanel(
                title = "Codex",
                subtitle = "本机 HTTPS 桥接 · Codex Web GPT",
                actions = listOf(
                    OverlayAction("连接状态") { runCodexHealthCheck() },
                    OverlayAction("桥接地址\n${codexClient.baseUrl}") {
                        pushInputPanel(
                            "桥接地址",
                            CodexBridgeClient.DEFAULT_BASE_URL
                        ) { value ->
                            codexClient.baseUrl = value
                            pushInfo("已保存", "Codex 地址：${codexClient.baseUrl}")
                        }
                    }
                )
            )
        }
    }

    private fun runCodexHealthCheck() {
        val tag = "harness-health:${++toolSequence}"
        pushPanel(tag = tag) {
            buildInfoPanel("连接中", "正在检查 Codex…")
        }
        worker.execute {
            val result = runCatching { codexClient.health() }
            main.post {
                if (panelStack.peekLast()?.tag != tag) return@post
                replaceTopPanel(tag = tag) {
                    buildInfoPanel(
                        title = if (result.isSuccess) "Codex" else "连接失败",
                        message = result.getOrElse { it.message ?: it.javaClass.simpleName }
                    )
                }
            }
        }
    }

    private fun startNewConversation() {
        if (chatBusy) return
        harnessPollGeneration += 1
        if (conversationMode == ConversationMode.LOCAL) {
            localMessages.clear()
        } else {
            currentConversationId = null
            currentConversationTitle = "新对话"
            chatMessages.clear()
        }
        chatStatus = ""
        renderChatPanel()
    }

    private fun setConversationMode(mode: ConversationMode) {
        if (chatBusy || conversationMode == mode) return
        conversationMode = mode
        chatStatus = ""
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_CHAT_MODE, mode.name)
            .apply()
        renderChatPanel()
    }

    private fun activeMessages(): List<CodexMessage> =
        if (conversationMode == ConversationMode.LOCAL) localMessages else chatMessages

    private fun adoptConversation(conversation: CodexConversation): Boolean {
        val changed = currentConversationId != conversation.id ||
            currentConversationTitle != conversation.title ||
            chatMessages != conversation.messages
        currentConversationId = conversation.id
        currentConversationTitle = conversation.title
        chatMessages.clear()
        chatMessages.addAll(conversation.messages)
        return changed
    }

    private fun renderChatPanel() {
        if (panelStack.peekLast()?.tag != CHAT_PANEL_TAG || panel == null) return
        val currentRoot = panel as? LinearLayout ?: return
        val updatedRoot = buildChatPanel() as LinearLayout
        currentRoot.removeAllViews()
        while (updatedRoot.childCount > 0) {
            val child = updatedRoot.getChildAt(0)
            updatedRoot.removeViewAt(0)
            currentRoot.addView(child)
        }
    }

    private fun sendChatMessage(message: String) {
        val text = message.trim()
        if (text.isBlank() || chatBusy) return
        when (conversationMode) {
            ConversationMode.LOCAL -> sendLocalControlMessage(text)
            ConversationMode.CHAT, ConversationMode.CONTROL -> sendCodexMessage(text)
        }
    }

    private fun sendCodexMessage(text: String) {
        chatBusy = true
        chatStatus = if (conversationMode == ConversationMode.CONTROL) {
            "Codex 正在处理…"
        } else {
            "Codex 正在处理…"
        }
        val pollGeneration = harnessPollGeneration + 1
        harnessPollGeneration = pollGeneration
        val harnessMessage = codexClient.prepareMessageForCodex(text)
        val existingConversationId = currentConversationId
        chatMessages.add(
            CodexMessage(
                role = "user",
                content = text,
                createdAt = ""
            )
        )
        renderChatPanel()

        worker.execute {
            var conversationId: String? = null
            val result = runCatching {
                val conversation = if (existingConversationId == null) {
                    codexClient.createConversation()
                } else {
                    codexClient.loadConversation(existingConversationId)
                }
                conversationId = conversation.id
                main.post {
                    if (pollGeneration != harnessPollGeneration) return@post
                    currentConversationId = conversation.id
                    currentConversationTitle = conversation.title
                    renderChatPanel()
                }
                startCodexConversationPolling(conversation.id, pollGeneration)
                codexClient.sendMessage(conversation.id, harnessMessage)
            }
            val failureSnapshot = if (result.isFailure) {
                conversationId?.let { id -> runCatching { codexClient.loadConversation(id) }.getOrNull() }
            } else {
                null
            }
            main.post {
                if (pollGeneration != harnessPollGeneration) return@post
                harnessPollGeneration += 1
                chatBusy = false
                result.onSuccess { conversation ->
                    adoptConversation(conversation)
                    chatStatus = ""
                }.onFailure { error ->
                    failureSnapshot?.let { adoptConversation(it) }
                    chatStatus = "发送失败：${error.message ?: error.javaClass.simpleName}"
                }
                renderChatPanel()
            }
        }
    }

    private fun startCodexConversationPolling(conversationId: String, generation: Long) {
        harnessPollWorker.execute {
            try {
                Thread.sleep(HARNESS_MESSAGE_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@execute
            }
            while (harnessPollGeneration == generation && !Thread.currentThread().isInterrupted) {
                val snapshot = runCatching { codexClient.loadConversation(conversationId) }.getOrNull()
                if (snapshot != null) {
                    main.post {
                        if (harnessPollGeneration != generation) return@post
                        if (adoptConversation(snapshot)) renderChatPanel()
                    }
                }
                try {
                    Thread.sleep(HARNESS_MESSAGE_POLL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }
        }
    }

    private fun copyActiveConversation() {
        val messages = activeMessages()
        if (messages.isEmpty()) {
            chatStatus = "当前对话暂无可复制内容"
            renderChatPanel()
            return
        }
        val title = if (conversationMode == ConversationMode.LOCAL) "本地控制" else currentConversationTitle
        val transcript = buildString {
            append(title)
            append("\n\n")
            messages.forEachIndexed { index, message ->
                val label = if (message.role == "user") "你" else if (message.failed) "系统" else "ChatGPT"
                append(label)
                append("：\n")
                append(message.content)
                if (index != messages.lastIndex) append("\n\n")
            }
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(title, transcript))
        chatStatus = "已复制当前对话的 ${messages.size} 条消息"
        renderChatPanel()
    }


    private fun sendLocalControlMessage(text: String, executionText: String = text) {
        val plan = engine.plan(executionText)
        chatBusy = true
        chatStatus = "本地模式 · 正在执行…"
        localMessages.add(
            CodexMessage(
                role = "user",
                content = text,
                createdAt = ""
            )
        )
        renderChatPanel()

        worker.execute {
            val results = plan.calls.map { call -> call to engine.execute(call, false) }
            main.post {
                chatBusy = false
                val failed = results.isEmpty() || results.any { (_, result) -> !result.success }
                val reply = if (results.isEmpty()) {
                    "未识别可执行的本地控制命令。请切换到“聊天”或“操控”模式处理此请求。"
                } else if (results.size == 1) {
                    results.single().second.message
                } else {
                    results.joinToString("\n") { (call, result) ->
                        "${if (result.success) "✓" else "×"} ${call.action}：${result.message}"
                    }
                }
                localMessages.add(
                    CodexMessage(
                        role = "assistant",
                        content = "本地模式：$reply",
                        createdAt = "",
                        failed = failed
                    )
                )
                chatStatus = when {
                    results.isEmpty() -> "本地模式未执行任何动作"
                    failed -> "本地模式有动作未完成"
                    else -> ""
                }
                renderChatPanel()
            }
        }
    }

    private fun showConversationHistory() {
        if (chatBusy) return
        val tag = "chat-history:${++toolSequence}"
        pushPanel(tag = tag) {
            buildInfoPanel("历史对话", "正在加载…")
        }
        worker.execute {
            val result = runCatching { codexClient.listConversations() }
            main.post {
                if (panelStack.peekLast()?.tag != tag) return@post
                replaceTopPanel(tag = HISTORY_PANEL_TAG) {
                    result.fold(
                        onSuccess = { buildHistoryPanel(it) },
                        onFailure = { buildInfoPanel("历史读取失败", it.message ?: it.javaClass.simpleName) }
                    )
                }
            }
        }
    }
    private fun openConversationFromHistory(id: String) {
        if (chatBusy) return
        val tag = "chat-open:${++toolSequence}"
        replaceTopPanel(tag = tag) {
            buildInfoPanel("加载对话", "正在读取历史对话…")
        }
        worker.execute {
            val result = runCatching { codexClient.loadConversation(id) }
            main.post {
                if (panelStack.peekLast()?.tag != tag) return@post
                result.onSuccess { conversation ->
                    adoptConversation(conversation)
                    chatStatus = ""
                    popPanel()
                }.onFailure { error ->
                    replaceTopPanel(tag = tag) {
                        buildInfoPanel("读取失败", error.message ?: error.javaClass.simpleName)
                    }
                }
            }
        }
    }

    private fun confirmDeleteConversation(summary: CodexConversationSummary) {
        pushPanel {
            buildInfoPanel(
                title = "删除历史对话",
                message = "确定删除“${summary.title}”？此操作会删除本机保存的这条聊天记录。",
                actions = listOf(
                    OverlayAction("确认删除") { deleteConversationFromHistory(summary.id) }
                )
            )
        }
    }

    private fun deleteConversationFromHistory(id: String) {
        if (chatBusy) return
        val tag = "chat-delete:${++toolSequence}"
        replaceTopPanel(tag = tag) {
            buildInfoPanel("删除中", "正在删除历史对话…")
        }
        worker.execute {
            val result = runCatching {
                codexClient.deleteConversation(id)
                codexClient.listConversations()
            }
            main.post {
                if (panelStack.peekLast()?.tag != tag) return@post
                result.onSuccess { conversations ->
                    if (currentConversationId == id) {
                        currentConversationId = null
                        currentConversationTitle = "新对话"
                        chatMessages.clear()
                        chatStatus = ""
                    }
                    while (panelStack.size > 1) panelStack.removeLast()
                    pushPanel(tag = HISTORY_PANEL_TAG) { buildHistoryPanel(conversations) }
                }.onFailure { error ->
                    replaceTopPanel(tag = tag) {
                        buildInfoPanel("删除失败", error.message ?: error.javaClass.simpleName)
                    }
                }
            }
        }
    }

    private fun clearAllConversationHistory() {
        if (chatBusy) return
        val tag = "chat-clear-all:${++toolSequence}"
        replaceTopPanel(tag = tag) {
            buildInfoPanel("清空中", "正在删除全部历史对话…")
        }
        worker.execute {
            val result = runCatching { codexClient.deleteAllConversations() }
            main.post {
                if (panelStack.peekLast()?.tag != tag) return@post
                result.onSuccess { deletedCount ->
                    currentConversationId = null
                    currentConversationTitle = "新对话"
                    chatMessages.clear()
                    chatStatus = ""
                    replaceTopPanel(tag = HISTORY_PANEL_TAG) {
                        buildInfoPanel("历史对话", "已清除 $deletedCount 条历史对话。")
                    }
                }.onFailure { error ->
                    replaceTopPanel(tag = HISTORY_PANEL_TAG) {
                        buildInfoPanel("清空失败", error.message ?: error.javaClass.simpleName)
                    }
                }
            }
        }
    }

    private fun buildHistoryPanel(conversations: List<CodexConversationSummary>): View {
        if (conversations.isEmpty()) {
            return buildInfoPanel("历史对话", "暂无历史对话。")
        }
        val actions = buildList {
            add(OverlayAction("清空全部历史（不可恢复）") { clearAllConversationHistory() })
            conversations.forEach { conversation ->
                val preview = conversation.preview.replace('\n', ' ').take(72)
                val openLabel = buildString {
                    append(conversation.title)
                    if (preview.isNotBlank()) append("\n$preview")
                }
                add(OverlayAction(openLabel) { openConversationFromHistory(conversation.id) })
                add(OverlayAction("删除：${conversation.title}") { confirmDeleteConversation(conversation) })
            }
        }
        return buildMenuPanel(
            title = "历史对话",
            subtitle = "点击标题选择对话；可复制当前对话，或一键清空全部历史。",
            actions = actions
        )
    }

    private fun buildChatPanel(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = panelAlphaPercent / 100f
            background = roundedBackground(SURFACE_COLOR, PANEL_RADIUS_DP)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            textView("关闭", SECONDARY_TEXT_SP).apply {
                gravity = Gravity.CENTER
                minWidth = dp(52)
                minHeight = dp(40)
                isClickable = true
                contentDescription = "关闭对话"
                setOnClickListener { closePanel() }
            }
        )
        header.addView(
            textView(if (conversationMode == ConversationMode.LOCAL) "本地控制" else currentConversationTitle, BASE_TEXT_SP).apply {
                maxLines = 2
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            textView("＋", 22f).apply {
                gravity = Gravity.CENTER
                minWidth = dp(42)
                minHeight = dp(40)
                isClickable = true
                setOnClickListener { startNewConversation() }
            }
        )
        header.addView(
            textView("复制", SECONDARY_TEXT_SP).apply {
                val enabled = activeMessages().isNotEmpty()
                gravity = Gravity.CENTER
                minWidth = dp(52)
                minHeight = dp(40)
                alpha = if (enabled) 1f else 0.35f
                isClickable = enabled
                contentDescription = "复制当前对话全部内容"
                setOnClickListener { copyActiveConversation() }
            }
        )
        header.addView(
            textView("历史", SECONDARY_TEXT_SP).apply {
                val enabled = conversationMode != ConversationMode.LOCAL && !chatBusy
                gravity = Gravity.CENTER
                minWidth = dp(56)
                minHeight = dp(40)
                alpha = if (enabled) 1f else 0.35f
                isClickable = enabled
                setOnClickListener { showConversationHistory() }
            }
        )
        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        )

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ConversationMode.values().forEach { mode ->
            val selected = mode == conversationMode
            modeRow.addView(
                textView(mode.label, SECONDARY_TEXT_SP).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(38)
                    background = roundedBackground(
                        if (selected) Color.argb(92, 255, 255, 255) else Color.argb(28, 255, 255, 255),
                        11
                    )
                    alpha = if (chatBusy && !selected) 0.45f else 1f
                    isClickable = !chatBusy
                    isFocusable = true
                    contentDescription = "${mode.label}模式"
                    setOnClickListener { setConversationMode(mode) }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
            )
        }
        root.addView(
            modeRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5) }
        )

        val messageColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }
        val visibleMessages = activeMessages()
        if (visibleMessages.isEmpty()) {
            messageColumn.addView(
                textView("输入消息开始。", SECONDARY_TEXT_SP).apply {
                    setTextColor(Color.argb(205, 255, 255, 255))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
            )
        } else {
            visibleMessages.forEach { message ->
                val isUser = message.role == "user"
                val label = when {
                    isUser -> "你"
                    message.failed -> "系统"
                    message.streaming -> "ChatGPT · 实时"
                    else -> "ChatGPT"
                }
                val bubbleColor = if (isUser) {
                    Color.argb(52, 255, 255, 255)
                } else {
                    Color.argb(30, 255, 255, 255)
                }
                val item = textView("$label\n${message.content}", BASE_TEXT_SP).apply {
                    setTextIsSelectable(true)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = roundedBackground(bubbleColor, 12)
                }
                messageColumn.addView(
                    item,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(7) }
                )
            }
        }
        val messageScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(messageColumn)
            post { fullScroll(View.FOCUS_DOWN) }
        }
        root.addView(
            messageScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        if (chatStatus.isNotBlank()) {
            root.addView(
                textView(chatStatus, SECONDARY_TEXT_SP).apply {
                    setTextColor(Color.argb(205, 255, 255, 255))
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }
            )
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val field = EditText(this).apply {
            hint = if (chatBusy) "正在处理…" else "输入消息"
            setHintTextColor(Color.argb(150, 255, 255, 255))
            setTextColor(TEXT_COLOR)
            textSize = BASE_TEXT_SP
            maxLines = 3
            minLines = 1
            isEnabled = !chatBusy
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(Color.argb(34, 255, 255, 255), 12)
        }
        val candidateColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(3), dp(2), 0)
        }
        fun updateLocalCandidates(raw: String) {
            candidateColumn.removeAllViews()
            if (conversationMode != ConversationMode.LOCAL || chatBusy) return

            val suggestions = LocalControlSuggestions.matching(
                raw,
                AppResolver.searchCandidates(this@FloatingBubbleService, raw)
            )
            suggestions.forEach { suggestion ->
                candidateColumn.addView(
                    textView(suggestion.label, SECONDARY_TEXT_SP).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minHeight = dp(36)
                        setPadding(dp(10), dp(5), dp(10), dp(5))
                        background = roundedBackground(Color.argb(28, 255, 255, 255), 10)
                        isClickable = true
                        isFocusable = true
                        contentDescription = "执行本地命令 ${suggestion.label}"
                        setOnClickListener {
                            if (!chatBusy) {
                                candidateColumn.removeAllViews()
                                if (suggestion.executeImmediately) {
                                    field.setText("")
                                    sendLocalControlMessage(suggestion.label, suggestion.command)
                                } else {
                                    field.setText(suggestion.command)
                                    field.setSelection(field.text.length)
                                    field.requestFocus()
                                }
                            }
                        }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(3) }
                )
            }
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateLocalCandidates(s?.toString().orEmpty())
            }
        })
        val submit = {
            val value = field.text.toString().trim()
            if (value.isBlank()) {
                field.error = "内容不能为空"
            } else if (!chatBusy) {
                field.setText("")
                sendChatMessage(value)
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else {
                false
            }
        }
        inputRow.addView(
            field,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        inputRow.addView(
            textView("➤", 20f).apply {
                gravity = Gravity.CENTER
                minWidth = dp(42)
                minHeight = dp(44)
                alpha = if (chatBusy) 0.45f else 1f
                isClickable = !chatBusy
                setOnClickListener { submit() }
            }
        )
        inputRow.addView(
            textView("⚙", 21f).apply {
                gravity = Gravity.CENTER
                minWidth = dp(44)
                minHeight = dp(44)
                isClickable = true
                contentDescription = "设置"
                setOnClickListener { openSettingsPanel() }
            }
        )
        root.addView(
            inputRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        root.addView(
            candidateColumn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }

    private fun showConnectionMenu() {
        pushPanel {
            buildMenuPanel(
                title = "连接 / 状态",
                subtitle = "应用内能力与 ACS ADB server 直连",
                actions = listOf(
                    OverlayAction("本机能力状态") { showLocalCapabilityStatus() },
                    OverlayAction("ADB status（ACS 直连）") { showCodexAdbRequest("status") },
                    OverlayAction("Bridge ping（ACS 直连）") { showCodexAdbRequest("ping") },
                    OverlayAction("Bridge tools（ACS 直连）") { showCodexAdbRequest("tools") },
                    OverlayAction("查看 Trace") {
                        pushInfo("Trace", engine.recentTrace().ifBlank { "暂无 Trace。" })
                    },
                    OverlayAction("写入 Trace（ACS 直连）") {
                        pushInputPanel("写入 Trace", "要记录的消息") { message ->
                            showCodexAdbRequest("trace $message")
                        }
                    }
                )
            )
        }
    }

    private fun showDeviceMenu() {
        pushPanel {
            buildMenuPanel(
                title = "设备 / 快捷控制",
                subtitle = "常用动作直接调用本地工具；媒体按键经 ACS ADB 白名单执行",
                actions = listOf(
                    OverlayAction("设备 / 内存 / 存储 / 前台应用") {
                        runCapabilityTool(ToolCall("device.info"))
                    },
                    OverlayAction("电池状态") {
                        runCapabilityTool(ToolCall("device.battery"))
                    },
                    OverlayAction("音量 +") {
                        runCapabilityTool(ToolCall("device.volume", mapOf("action" to "up")))
                    },
                    OverlayAction("音量 -") {
                        runCapabilityTool(ToolCall("device.volume", mapOf("action" to "down")))
                    },
                    OverlayAction("媒体静音") {
                        runCapabilityTool(ToolCall("device.volume", mapOf("action" to "mute")))
                    },
                    OverlayAction("取消静音") {
                        runCapabilityTool(ToolCall("device.volume", mapOf("action" to "unmute")))
                    },
                    OverlayAction("唤醒屏幕") {
                        runCapabilityTool(ToolCall("device.keyevent", mapOf("key" to "KEYCODE_WAKEUP")))
                    },
                    OverlayAction("播放 / 暂停") {
                        runCapabilityTool(ToolCall("device.keyevent", mapOf("key" to "KEYCODE_MEDIA_PLAY_PAUSE")))
                    },
                    OverlayAction("上一首") {
                        runCapabilityTool(ToolCall("device.keyevent", mapOf("key" to "KEYCODE_MEDIA_PREVIOUS")))
                    },
                    OverlayAction("下一首") {
                        runCapabilityTool(ToolCall("device.keyevent", mapOf("key" to "KEYCODE_MEDIA_NEXT")))
                    }
                )
            )
        }
    }

    private fun showOverlayAppearanceMenu() {
        pushPanel {
            buildAppearancePanel()
        }
    }

    private fun showAppMenu() {
        pushPanel {
            buildMenuPanel(
                title = "应用控制",
                subtitle = "可输入包名或应用名称；同名应用会列出全部包名供选择",
                actions = listOf(
                    OverlayAction("启动应用") {
                        pushPackageInputPanel(
                            "启动应用",
                            "包名或应用名称，例如 com.android.settings / 设置"
                        ) { packageName ->
                            runCapabilityTool(ToolCall("app.launch", mapOf("package" to packageName)))
                        }
                    },
                    OverlayAction("列出 Activities（ACS 直连）") {
                        pushPackageInputPanel(
                            "列出 Activities",
                            "包名或应用名称，例如 com.android.settings / 设置"
                        ) { packageName ->
                            runCapabilityTool(ToolCall("app.activities", mapOf("package" to packageName)))
                        }
                    },
                    OverlayAction("启动指定 Activity（ACS 直连）") {
                        pushInputPanel(
                            "启动 Activity",
                            "组件，例如 com.android.settings/.Settings"
                        ) { component ->
                            runCapabilityTool(ToolCall("app.start_activity", mapOf("component" to component)))
                        }
                    },
                    OverlayAction("强制停止应用（ACS 直连）") {
                        pushPackageInputPanel(
                            "Force-stop",
                            "包名或应用名称，例如 com.android.chrome / Chrome"
                        ) { packageName ->
                            runCapabilityTool(ToolCall("app.force_stop", mapOf("package" to packageName)))
                        }
                    }
                )
            )
        }
    }

    private fun showUiMenu() {
        pushPanel {
            buildMenuPanel(
                title = "界面控制",
                subtitle = "通过 ADB 无障碍控制执行",
                actions = listOf(
                    OverlayAction("读取 UI 树") {
                        runCapabilityTool(ToolCall("ui.dump"))
                    },
                    OverlayAction("点击可见文字") {
                        pushInputPanel("点击文字", "界面上可见的文字") { text ->
                            runCapabilityTool(ToolCall("ui.click", mapOf("text" to text)))
                        }
                    },
                    OverlayAction("按无障碍描述点击") {
                        pushInputPanel("点击描述", "contentDescription 文本") { description ->
                            runCapabilityTool(ToolCall("ui.click", mapOf("description" to description)))
                        }
                    },
                    OverlayAction("按资源 ID 点击") {
                        pushInputPanel("点击资源 ID", "例如 com.example:id/button") { viewId ->
                            runCapabilityTool(ToolCall("ui.click", mapOf("id" to viewId)))
                        }
                    },
                    OverlayAction("长按可见文字") {
                        pushInputPanel("长按文字", "界面上可见的文字") { text ->
                            runCapabilityTool(ToolCall("ui.long_click", mapOf("text" to text)))
                        }
                    },
                    OverlayAction("向下滚动") {
                        runCapabilityTool(ToolCall("ui.scroll", mapOf("direction" to "down")))
                    },
                    OverlayAction("向上滚动") {
                        runCapabilityTool(ToolCall("ui.scroll", mapOf("direction" to "up")))
                    },
                    OverlayAction("输入文字") {
                        pushInputPanel("输入文字", "要写入当前输入框的内容") { text ->
                            runCapabilityTool(ToolCall("ui.input_text", mapOf("text" to text)))
                        }
                    },
                    OverlayAction("返回") {
                        runCapabilityTool(ToolCall("ui.back"))
                    },
                    OverlayAction("Home") {
                        runCapabilityTool(ToolCall("ui.home"))
                    },
                    OverlayAction("最近任务") {
                        runCapabilityTool(ToolCall("ui.recents"))
                    },
                    OverlayAction("展开通知栏") {
                        runCapabilityTool(ToolCall("ui.notifications"))
                    },
                    OverlayAction("展开快捷设置") {
                        runCapabilityTool(ToolCall("ui.quick_settings"))
                    },
                    OverlayAction("打开电源菜单") {
                        runCapabilityTool(ToolCall("ui.power_dialog"))
                    },
                    OverlayAction("锁屏") {
                        runCapabilityTool(ToolCall("ui.lock_screen"))
                    },
                    OverlayAction("系统截图") {
                        runCapabilityTool(ToolCall("ui.take_screenshot"))
                    },
                    OverlayAction("收起通知栏 / 快捷设置") {
                        runCapabilityTool(ToolCall("ui.dismiss_shade"))
                    },
                    OverlayAction("打开所有应用") {
                        runCapabilityTool(ToolCall("ui.all_apps"))
                    },
                    OverlayAction("切换分屏") {
                        runCapabilityTool(ToolCall("ui.split_screen"))
                    }
                )
            )
        }
    }

    private fun showShellMenu() {
        pushPanel {
            buildMenuPanel(
                title = "原始 Shell / 风险边界",
                subtitle = "APK 保持普通应用 UID；系统级 shell 通过 ACS ADB server 以 shell UID 2000 执行",
                actions = listOf(
                    OverlayAction("原始 Shell（RED）") {
                        pushInputPanel("原始 Shell 命令", "输入完整命令；提交后需再次确认") { command ->
                            showRawShellRequest(command)
                        }
                    },
                    OverlayAction("为什么现在可以直连 ACS ADB？") {
                        pushInfo(
                            "Android 权限边界",
                            "应用进程仍是普通应用 UID；这里不是用 Runtime.exec() 提权，而是作为客户端连接 ACS/AndroidIDE 已运行的本机 ADB server（127.0.0.1:5037）。系统级命令仍由 adbd 以 shell UID 2000 执行。"
                        )
                    }
                )
            )
        }
    }

    private fun showPermissionCenter() {
        val text = buildString {
            appendLine("普通 Android API    ✓")
            appendLine("Accessibility       ${if (AgentAccessibilityService.instance != null) "✓" else "×"}")
            appendLine("Codex ADB Bridge    ✓（ADB shell 专用）")
            appendLine("ACS ADB Shell       本机 127.0.0.1:5037")
            appendLine("悬浮窗              ${if (Settings.canDrawOverlays(this@FloatingBubbleService)) "✓" else "×"}")
            appendLine("MediaProjection      ×（后续）")
            appendLine("Device Owner         ×（可选）")
            append("Root                 ×（可选）")
        }
        pushPanel {
            buildInfoPanel(
                title = "权限中心",
                message = text,
                actions = listOf(
                    OverlayAction("无障碍设置") {
                        startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    OverlayAction("悬浮窗设置") {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            )
        }
    }

    private fun showLocalCapabilityStatus() {
        val accessibilityReady = AgentAccessibilityService.instance != null
        val overlayReady = Settings.canDrawOverlays(this)
        val status = buildString {
            appendLine("应用内结构化工具：${engine.toolSummary().lineSequence().count { it.isNotBlank() }} 个")
            appendLine("普通 Android API：✓")
            appendLine("Accessibility：${if (accessibilityReady) "✓" else "×"}")
            appendLine("CodexBridgeReceiver：✓（DUMP 权限保护）")
            appendLine("悬浮窗：${if (overlayReady) "✓" else "×"}")
            appendLine()
            appendLine("ACS ADB 直连：status / ping / tools / activities / start-activity / force-stop / trace / raw shell")
            append("系统级动作由 ACS ADB server 转交给 ADB shell UID 2000。")
        }
        pushInfo("能力状态", status)
    }

    private fun showCodexAdbRequest(command: String) {
        runAcsAdbRequest(command, rawShell = false)
    }

    private fun showRawShellRequest(command: String) {
        pushPanel {
            buildInfoPanel(
                title = "⚠ RED：原始 Shell",
                message = "该命令将通过 ACS ADB server 以 adb shell UID 2000 执行。请核对完整命令后再确认。\n\n原始命令：$command",
                actions = listOf(
                    OverlayAction("确认并通过 ACS ADB 执行") {
                        runAcsAdbRequest(command, rawShell = true)
                    }
                )
            )
        }
    }

    private fun runAcsAdbRequest(command: String, rawShell: Boolean) {
        val tag = "acs-adb:${++toolSequence}"
        pushPanel(tag = tag) {
            buildInfoPanel(
                title = "执行中",
                message = if (rawShell) {
                    "正在通过 ACS ADB 执行原始 Shell：$command"
                } else {
                    "正在通过 ACS ADB 执行：$command"
                }
            )
        }
        worker.execute {
            val result = if (rawShell) {
                acsAdb.executeRawShell(command)
            } else {
                acsAdb.execute(command)
            }
            main.post {
                if (panelStack.peekLast()?.tag != tag) return@post
                replaceTopPanel(tag = tag) {
                    buildInfoPanel(
                        title = if (result.success) "ACS ADB 结果" else "ACS ADB 失败",
                        message = result.message
                    )
                }
            }
        }
    }

    private fun runCapabilityTool(call: ToolCall) {
        val tag = "tool:${++toolSequence}"
        pushPanel(tag = tag) {
            buildInfoPanel(
                title = "执行中",
                message = "正在执行 ${call.describe()}"
            )
        }
        worker.execute {
            val result = engine.execute(call, false)
            main.post {
                if (panelStack.peekLast()?.tag != tag) return@post
                replaceTopPanel(tag = tag) {
                    buildInfoPanel(
                        title = if (result.success) "执行结果" else "执行失败",
                        message = result.message
                    )
                }
            }
        }
    }

    private fun pushInputPanel(
        title: String,
        hint: String,
        onSubmit: (String) -> Unit
    ) {
        pushPanel(focusable = true) {
            buildInputPanel(title, hint, onSubmit)
        }
    }

    private fun pushInfo(
        title: String,
        message: String,
        actions: List<OverlayAction> = emptyList()
    ) {
        pushPanel {
            buildInfoPanel(title, message, actions)
        }
    }

    private fun pushPanel(
        focusable: Boolean = false,
        tag: String? = null,
        builder: () -> View
    ) {
        panelStack.addLast(PanelState(focusable, tag, builder))
        renderCurrentPanel()
    }

    private fun replaceTopPanel(
        focusable: Boolean = false,
        tag: String? = null,
        builder: () -> View
    ) {
        if (panelStack.isNotEmpty()) panelStack.removeLast()
        panelStack.addLast(PanelState(focusable, tag, builder))
        renderCurrentPanel()
    }

    private fun popPanel() {
        if (panelStack.size <= 1) {
            closePanel()
            return
        }
        panelStack.removeLast()
        renderCurrentPanel()
    }

    private fun renderCurrentPanel() {
        val state = panelStack.peekLast() ?: run {
            closePanel()
            return
        }
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        panelParams = null
        hideKeyboard()

        val view = state.builder()
        val metrics = resources.displayMetrics
        val panelWidth = min(dp(PANEL_WIDTH_DP), metrics.widthPixels - dp(24))
        val panelHeight = if (state.tag == CHAT_PANEL_TAG) {
            metrics.heightPixels / 2
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
        val bubbleX = bubbleParams?.x ?: 0
        val bubbleY = bubbleParams?.y ?: dp(180)
        val onRight = bubbleX > metrics.widthPixels / 2
        val desiredX = if (onRight) {
            bubbleX - panelWidth - dp(10)
        } else {
            bubbleX + (bubble?.width ?: dp(barWidthDp)) + dp(10)
        }
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            if (state.focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        val params = WindowManager.LayoutParams(
            panelWidth,
            panelHeight,
            overlayWindowType(),
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = desiredX.coerceIn(dp(6), max(dp(6), metrics.widthPixels - panelWidth - dp(6)))
            val maxPanelY = if (panelHeight > 0) {
                max(dp(12), metrics.heightPixels - panelHeight - dp(12))
            } else {
                max(dp(12), metrics.heightPixels - dp(140))
            }
            y = bubbleY.coerceIn(dp(12), maxPanelY)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        windowManager.addView(view, params)
        panel = view
        panelParams = params

        if (state.focusable) {
            view.post {
                val field = findFirstEditText(view)
                field?.requestFocus()
                field?.let {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    private fun repositionPanelNextToBubble() {
        val currentPanel = panel ?: return
        val currentPanelParams = panelParams ?: return
        val currentBubbleParams = bubbleParams ?: return
        val metrics = resources.displayMetrics
        val panelWidth = currentPanelParams.width.coerceAtLeast(1)
        val onRight = currentBubbleParams.x > metrics.widthPixels / 2
        val desiredX = if (onRight) {
            currentBubbleParams.x - panelWidth - dp(10)
        } else {
            currentBubbleParams.x + (bubble?.width ?: dp(barWidthDp)) + dp(10)
        }
        currentPanelParams.x = desiredX.coerceIn(dp(6), max(dp(6), metrics.widthPixels - panelWidth - dp(6)))
        val panelHeight = currentPanel.height
        val maxPanelY = if (panelHeight > 0) {
            max(dp(12), metrics.heightPixels - panelHeight - dp(12))
        } else {
            max(dp(12), metrics.heightPixels - dp(140))
        }
        currentPanelParams.y = currentBubbleParams.y.coerceIn(dp(12), maxPanelY)
        windowManager.updateViewLayout(currentPanel, currentPanelParams)
    }

    private fun collapsePanel() {
        hideKeyboard()
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        panelParams = null
    }

    private fun closePanel() {
        collapsePanel()
        panelStack.clear()
    }

    private fun buildMenuPanel(
        title: String,
        subtitle: String,
        actions: List<OverlayAction>
    ): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = panelAlphaPercent / 100f
            background = roundedBackground(SURFACE_COLOR, PANEL_RADIUS_DP)
        }
        root.addView(panelContent(title, subtitle))

        val actionContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(14))
        }
        actions.forEach { addAction(actionContent, it) }
        addNavigationAction(actionContent)

        val visibleButtonCount = min(
            MENU_VISIBLE_BUTTONS,
            actionContent.childCount
        )
        root.addView(
            ScrollView(this).apply {
                isFillViewport = false
                addView(actionContent)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(MENU_ACTION_SLOT_DP * visibleButtonCount)
            )
        )
        return root
    }
    private fun buildInfoPanel(
        title: String,
        message: String,
        actions: List<OverlayAction> = emptyList()
    ): View {
        val content = panelContent(title, null)
        content.addView(textView(message, BASE_TEXT_SP).apply {
            setPadding(dp(2), dp(6), dp(2), dp(10))
            setTextIsSelectable(true)
        })
        actions.forEach { addAction(content, it) }
        addNavigationAction(content)
        return wrapPanel(content)
    }

    private fun buildInputPanel(
        title: String,
        hint: String,
        onSubmit: (String) -> Unit
    ): View {
        val content = panelContent(title, null)
        val field = EditText(this).apply {
            this.hint = hint
            setHintTextColor(Color.argb(150, 255, 255, 255))
            setTextColor(TEXT_COLOR)
            textSize = BASE_TEXT_SP
            setSingleLine(false)
            maxLines = 5
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(Color.argb(34, 255, 255, 255), 12)
        }
        content.addView(
            field,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )
        addAction(
            content,
            OverlayAction("继续") {
                val value = field.text.toString().trim()
                if (value.isBlank()) {
                    field.error = "内容不能为空"
                } else {
                    popPanel()
                    onSubmit(value)
                }
            }
        )
        addNavigationAction(content)
        return wrapPanel(content)
    }

    private fun pushPackageInputPanel(
        title: String,
        hint: String,
        onPackageSelected: (String) -> Unit
    ) {
        pushInputPanel(title, hint) { target ->
            val candidates = AppResolver.resolveCandidates(this, target)
            when {
                candidates.isEmpty() -> {
                    pushInfo("未找到应用", "找不到应用或包名：$target")
                }
                candidates.size == 1 -> {
                    onPackageSelected(candidates.single().packageName)
                }
                else -> {
                    pushPanel {
                        buildMenuPanel(
                            title = "选择包名",
                            subtitle = "“$target”匹配到 ${candidates.size} 个应用",
                            actions = candidates.map { candidate ->
                                OverlayAction("${candidate.label}\n${candidate.packageName}") {
                                    popPanel()
                                    onPackageSelected(candidate.packageName)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun buildAppearancePanel(): View {
        val content = panelContent(
            "悬浮条外观",
            "拖动细条可上下移动并自动吸附左右边缘；下列参数实时生效并自动保存。"
        )
        addSlider(
            content,
            "长度",
            MIN_BAR_LENGTH_DP,
            MAX_BAR_LENGTH_DP,
            barLengthDp,
            "dp"
        ) { value ->
            barLengthDp = value
            saveBarPreferences()
            applyBarAppearance()
        }
        addSlider(
            content,
            "宽度",
            MIN_BAR_WIDTH_DP,
            MAX_BAR_WIDTH_DP,
            barWidthDp,
            "dp"
        ) { value ->
            barWidthDp = value
            saveBarPreferences()
            applyBarAppearance()
        }
        addSlider(
            content,
            "透明度",
            MIN_BAR_ALPHA_PERCENT,
            MAX_BAR_ALPHA_PERCENT,
            barAlphaPercent,
            "%"
        ) { value ->
            barAlphaPercent = value
            saveBarPreferences()
            applyBarAppearance()
        }
        addSlider(
            content,
            "聊天 / 设置透明度",
            MIN_PANEL_ALPHA_PERCENT,
            MAX_PANEL_ALPHA_PERCENT,
            panelAlphaPercent,
            "%"
        ) { value ->
            panelAlphaPercent = value
            saveBarPreferences()
            applyPanelAppearance()
        }
        addNavigationAction(content)
        return wrapPanel(content)
    }

    private fun addSlider(
        container: LinearLayout,
        title: String,
        minValue: Int,
        maxValue: Int,
        currentValue: Int,
        suffix: String,
        onChanged: (Int) -> Unit
    ) {
        val label = textView("$title：$currentValue$suffix", BASE_TEXT_SP).apply {
            setPadding(dp(2), dp(6), dp(2), 0)
        }
        val slider = SeekBar(this).apply {
            max = maxValue - minValue
            progress = currentValue - minValue
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val value = minValue + progress
                    label.text = "$title：$value$suffix"
                    onChanged(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        container.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            slider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        )
    }

    private fun loadBarPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        barLengthDp = prefs.getInt(PREF_BAR_LENGTH, DEFAULT_BAR_LENGTH_DP)
            .coerceIn(MIN_BAR_LENGTH_DP, MAX_BAR_LENGTH_DP)
        barWidthDp = prefs.getInt(PREF_BAR_WIDTH, DEFAULT_BAR_WIDTH_DP)
            .coerceIn(MIN_BAR_WIDTH_DP, MAX_BAR_WIDTH_DP)
        barAlphaPercent = prefs.getInt(PREF_BAR_ALPHA, DEFAULT_BAR_ALPHA_PERCENT)
            .coerceIn(MIN_BAR_ALPHA_PERCENT, MAX_BAR_ALPHA_PERCENT)
        panelAlphaPercent = prefs.getInt(PREF_PANEL_ALPHA, DEFAULT_PANEL_ALPHA_PERCENT)
            .coerceIn(MIN_PANEL_ALPHA_PERCENT, MAX_PANEL_ALPHA_PERCENT)
        val savedMode = prefs.getString(PREF_CHAT_MODE, ConversationMode.CHAT.name)
        conversationMode = ConversationMode.values().firstOrNull { mode ->
            mode.name == savedMode
        } ?: ConversationMode.CHAT
    }

    private fun saveBarPreferences() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(PREF_BAR_LENGTH, barLengthDp)
            .putInt(PREF_BAR_WIDTH, barWidthDp)
            .putInt(PREF_BAR_ALPHA, barAlphaPercent)
            .putInt(PREF_PANEL_ALPHA, panelAlphaPercent)
            .apply()
    }

    private fun applyBarAppearance() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        val metrics = resources.displayMetrics
        val onRight = params.x > metrics.widthPixels / 2

        params.width = dp(barWidthDp)
        params.height = dp(barLengthDp)
        params.x = if (onRight) metrics.widthPixels - params.width else 0
        params.y = params.y.coerceIn(0, max(0, metrics.heightPixels - params.height))
        view.alpha = barAlphaPercent / 100f
        view.background = barBackground()
        windowManager.updateViewLayout(view, params)

        val currentPanel = panel
        val currentPanelParams = panelParams
        if (currentPanel != null && currentPanelParams != null) {
            val desiredX = if (onRight) {
                params.x - currentPanelParams.width - dp(10)
            } else {
                params.x + params.width + dp(10)
            }
            currentPanelParams.x = desiredX.coerceIn(
                dp(6),
                max(dp(6), metrics.widthPixels - currentPanelParams.width - dp(6))
            )
            windowManager.updateViewLayout(currentPanel, currentPanelParams)
        }
    }

    private fun applyPanelAppearance() {
        panel?.alpha = panelAlphaPercent / 100f
    }

    private fun barBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(SURFACE_COLOR)
            cornerRadius = dp(max(2, barWidthDp / 2)).toFloat()
        }

    private fun panelContent(title: String, subtitle: String?): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(textView(title, BASE_TEXT_SP).apply {
                setPadding(dp(2), 0, dp(2), dp(6))
            })
            if (!subtitle.isNullOrBlank()) {
                addView(textView(subtitle, SECONDARY_TEXT_SP).apply {
                    setTextColor(Color.argb(205, 255, 255, 255))
                    setPadding(dp(2), 0, dp(2), dp(10))
                })
            }
        }

    private fun wrapPanel(content: LinearLayout): View =
        ScrollView(this).apply {
            alpha = panelAlphaPercent / 100f
            background = roundedBackground(SURFACE_COLOR, PANEL_RADIUS_DP)
            isFillViewport = false
            addView(content)
        }

    private fun addAction(container: LinearLayout, action: OverlayAction) {
        val item = textView(action.label, BASE_TEXT_SP).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBackground(Color.argb(34, 255, 255, 255), 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { action.onClick() }
        }
        container.addView(
            item,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
        )
    }

    private fun addNavigationAction(container: LinearLayout) {
        addAction(
            container,
            if (panelStack.size <= 1) {
                OverlayAction("关闭") { closePanel() }
            } else {
                OverlayAction("← 返回") { popPanel() }
            }
        )
        val navigation = container.getChildAt(container.childCount - 1)
        container.removeViewAt(container.childCount - 1)
        container.addView(navigation, 0)
    }

    private fun textView(value: String, sizeSp: Float): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(TEXT_COLOR)
        }

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }


    private fun hideKeyboard() {
        panel?.windowToken?.let { token ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
        }
    }

    private fun findFirstEditText(view: View): EditText? {
        if (view is EditText) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                findFirstEditText(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {

        private const val CHANNEL_ID = "adb_agent_overlay"
        private const val NOTIFICATION_ID = 4310
        private const val PREF_BAR_WIDTH = "bar_width_dp"
        private const val PREF_BAR_ALPHA = "bar_alpha_percent"
        private const val PREF_PANEL_ALPHA = "panel_alpha_percent"
        private const val PREFS_NAME = "floating_bar"
        private const val PREF_BAR_LENGTH = "bar_length_dp"
        private const val PREF_CHAT_MODE = "chat_mode"
        private const val DEFAULT_BAR_LENGTH_DP = 150
        private const val MIN_BAR_LENGTH_DP = 60
        private const val MAX_BAR_LENGTH_DP = 280
        private const val DEFAULT_BAR_WIDTH_DP = 12
        private const val MIN_BAR_WIDTH_DP = 6
        private const val MAX_BAR_WIDTH_DP = 36
        private const val DEFAULT_BAR_ALPHA_PERCENT = 55
        private const val MIN_BAR_ALPHA_PERCENT = 10
        private const val MAX_BAR_ALPHA_PERCENT = 100
        private const val DEFAULT_PANEL_ALPHA_PERCENT = 82
        private const val MIN_PANEL_ALPHA_PERCENT = 10
        private const val MAX_PANEL_ALPHA_PERCENT = 100
        private const val HARNESS_MESSAGE_POLL_MS = 650L
        private const val CHAT_PANEL_TAG = "chat"
        private const val HISTORY_PANEL_TAG = "chat-history"
        private const val PANEL_WIDTH_DP = 350
        private const val PANEL_RADIUS_DP = 18
        private const val MENU_VISIBLE_BUTTONS = 3
        private const val MENU_ACTION_SLOT_DP = 51
        private const val LONG_PRESS_MS = 700L
        private const val BASE_TEXT_SP = 16f
        private const val SECONDARY_TEXT_SP = 16f
        private val SURFACE_COLOR = Color.rgb(35, 35, 40)
        private val TEXT_COLOR = Color.WHITE
    }
}
