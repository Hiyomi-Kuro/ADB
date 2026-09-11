package com.kaori.adb.tools

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.kaori.adb.accessibility.AgentAccessibilityService
import com.kaori.adb.agent.AgentTool
import com.kaori.adb.agent.RiskLevel
import com.kaori.adb.agent.ToolResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private data class ScreenBytes(
    val bytes: ByteArray,
    val width: Int,
    val height: Int
)

class UiObserveTool : AgentTool {
    override val name = "ui.observe"
    override val risk = RiskLevel.GREEN

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val service = AgentAccessibilityService.instance
            ?: return ToolResult(false, "无障碍服务未连接，请先启用 ADB 无障碍控制。")
        val maxNodes = arguments["max_nodes"]?.toIntOrNull()?.coerceIn(20, 500) ?: 220
        val includeScreenshot = arguments["screenshot"]?.toBooleanStrictOrNull() ?: true
        val includeBase64 = arguments["base64"]?.toBooleanStrictOrNull() ?: true
        val ui = service.dumpUi(maxNodes)
        val packageName = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        val data = linkedMapOf("ui" to ui, "package" to packageName)

        var screenSummary = ""
        if (includeScreenshot) {
            val maxSide = arguments["max_side"]?.toIntOrNull()?.coerceIn(320, 1440) ?: 720
            val quality = arguments["quality"]?.toIntOrNull()?.coerceIn(35, 90) ?: 60
            val capture = captureScreenshot(service, maxSide, quality)
            if (capture != null) {
                data["screenshot_mime"] = "image/jpeg"
                data["screenshot_width"] = capture.width.toString()
                data["screenshot_height"] = capture.height.toString()
                data["screenshot_bytes"] = capture.bytes.size.toString()
                if (includeBase64) {
                    data["screenshot_base64"] = Base64.encodeToString(capture.bytes, Base64.NO_WRAP)
                }
                saveScreenshot(context, capture.bytes)?.let { location ->
                    data["screenshot_location"] = location
                }
                screenSummary = "；截图 ${capture.width}×${capture.height}"
            } else {
                data["screenshot_error"] = "截图不可用"
            }
        }

        return ToolResult(true, "已观察当前界面：${packageName.ifBlank { "未知包名" }}$screenSummary", data)
    }

    private fun captureScreenshot(
        service: AgentAccessibilityService,
        maxSide: Int,
        quality: Int
    ): ScreenBytes? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        var result: ScreenBytes? = null
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val buffer = screenshot.hardwareBuffer
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            val bitmapCopy = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            buffer.close()
                            if (bitmapCopy != null) {
                                val largest = maxOf(bitmapCopy.width, bitmapCopy.height)
                                val bitmap = if (largest > maxSide) {
                                    val scale = maxSide.toFloat() / largest.toFloat()
                                    val targetWidth = (bitmapCopy.width * scale).toInt().coerceAtLeast(1)
                                    val targetHeight = (bitmapCopy.height * scale).toInt().coerceAtLeast(1)
                                    Bitmap.createScaledBitmap(bitmapCopy, targetWidth, targetHeight, true)
                                        .also { bitmapCopy.recycle() }
                                } else {
                                    bitmapCopy
                                }
                                val bytes = ByteArrayOutputStream().use { output ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                                    output.toByteArray()
                                }
                                result = ScreenBytes(bytes, bitmap.width, bitmap.height)
                                bitmap.recycle()
                            }
                        } finally {
                            latch.countDown()
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                }
            )
            latch.await(5, TimeUnit.SECONDS)
            return result
        } finally {
            executor.shutdownNow()
        }
    }

    private fun saveScreenshot(context: Context, bytes: ByteArray): String? {
        val fileName = "observe-${System.currentTimeMillis()}.jpg"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/ADBAgent/observations")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                try {
                    val stream = context.contentResolver.openOutputStream(uri, "w")
                        ?: return@runCatching null
                    stream.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    uri.toString()
                } catch (error: Throwable) {
                    context.contentResolver.delete(uri, null, null)
                    throw error
                }
            } else {
                val file = File(context.cacheDir, fileName)
                file.writeBytes(bytes)
                file.absolutePath
            }
        }.getOrNull()
    }
}

class UiWaitForTool : AgentTool {
    override val name = "ui.wait_for"
    override val risk = RiskLevel.GREEN

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val service = AgentAccessibilityService.instance
            ?: return ToolResult(false, "无障碍服务未连接，请先启用 ADB 无障碍控制。")
        val text = arguments["text"] ?: arguments["expect_text"]
        val absentText = arguments["absent_text"] ?: arguments["expect_absent_text"]
        val description = arguments["description"] ?: arguments["expect_description"]
        val viewId = arguments["id"] ?: arguments["expect_id"]
        val packageName = arguments["package"] ?: arguments["expect_package"]
        val clickableText = arguments["clickable_text"] ?: arguments["expect_clickable_text"]
        if (listOf(text, absentText, description, viewId, packageName, clickableText).all { it.isNullOrBlank() }) {
            return ToolResult(false, "至少需要一个等待条件")
        }

        val timeoutMs = arguments["timeout_ms"]?.toLongOrNull()?.coerceIn(250L, 60_000L) ?: 10_000L
        val intervalMs = arguments["interval_ms"]?.toLongOrNull()?.coerceIn(100L, 2_000L) ?: 250L
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var lastUi = ""
        var lastPackage = ""

        while (true) {
            val root = service.rootInActiveWindow
            lastPackage = root?.packageName?.toString().orEmpty()
            lastUi = service.dumpUi(260)
            val textOk = text.isNullOrBlank() || lastUi.contains(text, ignoreCase = true)
            val absentOk = absentText.isNullOrBlank() || !lastUi.contains(absentText, ignoreCase = true)
            val packageOk = packageName.isNullOrBlank() || lastPackage == packageName
            val descriptionOk = description.isNullOrBlank() || hasNode(root) { node ->
                node.contentDescription?.toString()?.contains(description, ignoreCase = true) == true
            }
            val idOk = viewId.isNullOrBlank() || hasNode(root) { node ->
                node.viewIdResourceName == viewId
            }
            val clickableOk = clickableText.isNullOrBlank() || hasNode(root) { node ->
                node.text?.toString()?.contains(clickableText, ignoreCase = true) == true && isClickable(node)
            }
            val matched = textOk && absentOk && descriptionOk && idOk && packageOk && clickableOk
            if (matched) {
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                return ToolResult(
                    true,
                    "等待条件已满足",
                    mapOf("ui" to lastUi, "package" to lastPackage, "elapsed_ms" to elapsedMs.toString())
                )
            }
            if (System.nanoTime() >= deadline) {
                return ToolResult(
                    false,
                    "等待条件超时（${timeoutMs}ms）",
                    mapOf("ui" to lastUi, "package" to lastPackage)
                )
            }
            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return ToolResult(false, "等待被中断", mapOf("ui" to lastUi, "package" to lastPackage))
            }
        }
    }

    private fun hasNode(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        root ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return true
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return false
    }

    private fun isClickable(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return true
            current = current.parent
        }
        return false
    }
}
