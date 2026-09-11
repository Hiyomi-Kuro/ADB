package com.kaori.adb.tools

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import com.kaori.adb.accessibility.AgentAccessibilityService
import com.kaori.adb.agent.AgentTool
import com.kaori.adb.agent.RiskLevel
import com.kaori.adb.agent.ToolResult

class DeviceInfoTool : AgentTool {
    override val name = "device.info"
    override val risk = RiskLevel.GREEN

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val stat = StatFs(context.filesDir.absolutePath)
        val foreground = AgentAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() ?: "未知（需无障碍）"
        val data = linkedMapOf(
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "memory_available" to formatBytes(memoryInfo.availMem),
            "memory_total" to formatBytes(memoryInfo.totalMem),
            "storage_available" to formatBytes(stat.availableBytes),
            "storage_total" to formatBytes(stat.totalBytes),
            "foreground_package" to foreground
        )
        val message = buildString {
            appendLine("设备：${data["device"]} / Android ${data["android"]} (API ${data["sdk"]})")
            appendLine("内存：${data["memory_available"]} 可用 / ${data["memory_total"]} 总计")
            appendLine("应用私有存储所在分区：${data["storage_available"]} 可用 / ${data["storage_total"]} 总计")
            append("前台应用：${data["foreground_package"]}")
        }
        return ToolResult(true, message, data)
    }

    private fun formatBytes(bytes: Long): String {
        val gib = bytes / 1024.0 / 1024.0 / 1024.0
        return String.format("%.1f GB", gib)
    }
}

class BatteryTool : AgentTool {
    override val name = "device.battery"
    override val risk = RiskLevel.GREEN

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val manager = context.getSystemService(BatteryManager::class.java)
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = manager.isCharging
        return ToolResult(
            true,
            "电量：$percent%，${if (charging) "正在充电" else "未充电"}",
            mapOf("percent" to percent.toString(), "charging" to charging.toString())
        )
    }
}

class DeviceVolumeTool : AgentTool {
    override val name = "device.volume"
    override val risk = RiskLevel.YELLOW

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val action = (arguments["action"] ?: arguments["direction"])
            ?.trim()
            ?.lowercase()
            ?: return ToolResult(false, "缺少 action 参数（up/down/mute/unmute）")
        val adjustment = when (action) {
            "up", "raise", "increase" -> AudioManager.ADJUST_RAISE
            "down", "lower", "decrease" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_MUTE
            "unmute" -> AudioManager.ADJUST_UNMUTE
            else -> return ToolResult(false, "不支持的音量动作：$action")
        }
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return runCatching {
            manager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                adjustment,
                AudioManager.FLAG_SHOW_UI
            )
            val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            ToolResult(
                true,
                "媒体音量：$current / $max",
                mapOf("current" to current.toString(), "max" to max.toString(), "action" to action)
            )
        }.getOrElse { error ->
            ToolResult(false, "音量控制失败：${error.message ?: error.javaClass.simpleName}")
        }
    }
}
