package com.kaori.adb.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CapturedScreen(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int
)

data class ScreenCaptureResult(
    val screen: CapturedScreen?,
    val error: String?
)

class AgentAccessibilityService : AccessibilityService() {
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    fun dumpUi(maxNodes: Int = 220): String {
        val root = rootInActiveWindow ?: return "无障碍已连接，但当前没有可读取的活动窗口。"
        val lines = mutableListOf<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var count = 0
        while (queue.isNotEmpty() && count < maxNodes) {
            val (node, depth) = queue.removeFirst()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString()?.replace("\n", " ")?.take(100).orEmpty()
            val desc = node.contentDescription?.toString()?.replace("\n", " ")?.take(100).orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable) {
                lines += "${"  ".repeat(depth.coerceAtMost(8))}${node.className ?: "node"} text=\"$text\" desc=\"$desc\" id=\"$id\" clickable=${node.isClickable} editable=${node.isEditable} scrollable=${node.isScrollable} selected=${node.isSelected} bounds=$rect"
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
            count++
        }
        return lines.joinToString("\n").ifBlank { "当前 UI 树没有可描述节点。" }
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = root.findAccessibilityNodeInfosByText(text)
        for (candidate in candidates) {
            if (performOnSelfOrParent(candidate, AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    fun clickDescription(description: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findMatchingNode(root) { candidate ->
            candidate.contentDescription?.toString()?.contains(description, ignoreCase = true) == true
        } ?: return false
        return performOnSelfOrParent(node, AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun clickViewId(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.findAccessibilityNodeInfosByViewId(viewId)
            .any { performOnSelfOrParent(it, AccessibilityNodeInfo.ACTION_CLICK) }
    }

    fun longClickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.findAccessibilityNodeInfosByText(text)
            .any { performOnSelfOrParent(it, AccessibilityNodeInfo.ACTION_LONG_CLICK) }
    }

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
        target ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findMatchingNode(root) { it.isScrollable } ?: return false
        val action = when (direction.lowercase()) {
            "up", "left", "backward", "previous" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down", "right", "forward", "next" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> return false
        }
        return target.performAction(action)
    }

    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun openPowerDialog(): Boolean = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)

    fun lockScreen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    fun dismissNotificationShade(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    }

    fun openAllApps(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return performGlobalAction(GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS)
    }

    fun dpad(direction: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val action = when (direction.lowercase()) {
            "up" -> GLOBAL_ACTION_DPAD_UP
            "down" -> GLOBAL_ACTION_DPAD_DOWN
            "left" -> GLOBAL_ACTION_DPAD_LEFT
            "right" -> GLOBAL_ACTION_DPAD_RIGHT
            "center", "enter", "ok" -> GLOBAL_ACTION_DPAD_CENTER
            else -> return false
        }
        return performGlobalAction(action)
    }

    fun openMenu(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return performGlobalAction(GLOBAL_ACTION_MENU)
    }

    fun mediaPlayPause(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return performGlobalAction(GLOBAL_ACTION_MEDIA_PLAY_PAUSE)
    }

    fun toggleSplitScreen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
    }

    fun takeScreenshot(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    fun longPress(x: Float, y: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(400, 5000)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun pinch(centerX: Float, centerY: Float, startSpacing: Float, endSpacing: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val startHalf = startSpacing / 2f
        val endHalf = endSpacing / 2f
        val first = Path().apply {
            moveTo(centerX - startHalf, centerY)
            lineTo(centerX - endHalf, centerY)
        }
        val second = Path().apply {
            moveTo(centerX + startHalf, centerY)
            lineTo(centerX + endHalf, centerY)
        }
        val duration = durationMs.coerceIn(100, 5000)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(first, 0, duration))
            .addStroke(GestureDescription.StrokeDescription(second, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun tap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    durationMs.coerceIn(100, 5000)
                )
            )
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun performOnSelfOrParent(node: AccessibilityNodeInfo, action: Int): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.performAction(action)) return true
            current = current.parent
        }
        return false
    }

    private fun findMatchingNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) return found
        }
        return null
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }
}
