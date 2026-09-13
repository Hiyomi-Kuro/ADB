package com.kaori.adb.tools

import android.content.Context
import android.os.Build
import com.kaori.adb.acs.AcsAdbTransport
import com.kaori.adb.accessibility.AgentAccessibilityService
import com.kaori.adb.agent.AgentTool
import com.kaori.adb.agent.RiskLevel
import com.kaori.adb.agent.ToolResult

private fun serviceOrError(): Pair<AgentAccessibilityService?, ToolResult?> {
    val service = AgentAccessibilityService.instance
    return if (service == null) {
        null to ToolResult(false, "无障碍服务未连接，请先在系统设置中启用 ADB 无障碍控制。")
    } else {
        service to null
    }
}

class UiDumpTool : AgentTool {
    override val name = "ui.dump"
    override val risk = RiskLevel.GREEN

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val (service, error) = serviceOrError()
        if (error != null) return error
        val dump = service!!.dumpUi()
        return ToolResult(true, dump, mapOf("ui" to dump))
    }
}

class UiClickTool : AgentTool {
    override val name = "ui.click"
    override val risk = RiskLevel.YELLOW

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val text = arguments["text"]?.takeIf { it.isNotBlank() }
        val description = arguments["description"]?.takeIf { it.isNotBlank() }
        val viewId = arguments["id"]?.takeIf { it.isNotBlank() }
        if (text == null && description == null && viewId == null) {
            return ToolResult(false, "缺少 text / description / id 参数")
        }
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = when {
            text != null -> service!!.clickText(text)
            description != null -> service!!.clickDescription(description)
            else -> service!!.clickViewId(viewId!!)
        }
        val target = text ?: description ?: viewId.orEmpty()
        return ToolResult(ok, if (ok) "已点击：$target" else "未找到可点击控件：$target")
    }
}

class UiInputTextTool : AgentTool {
    override val name = "ui.input_text"
    override val risk = RiskLevel.YELLOW

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val text = arguments["text"] ?: return ToolResult(false, "缺少 text 参数")
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.inputText(text)
        return ToolResult(ok, if (ok) "已输入文本" else "没有找到可编辑输入框")
    }
}

class UiBackTool : AgentTool {
    override val name = "ui.back"
    override val risk = RiskLevel.GREEN
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.pressBack()
        return ToolResult(ok, if (ok) "已执行返回" else "返回动作失败")
    }
}

class UiHomeTool : AgentTool {
    override val name = "ui.home"
    override val risk = RiskLevel.GREEN
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.pressHome()
        return ToolResult(ok, if (ok) "已回到桌面" else "Home 动作失败")
    }
}

class UiLongClickTool : AgentTool {
    override val name = "ui.long_click"
    override val risk = RiskLevel.YELLOW

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val text = arguments["text"] ?: return ToolResult(false, "缺少 text 参数")
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.longClickText(text)
        return ToolResult(ok, if (ok) "已长按：$text" else "未找到可长按控件：$text")
    }
}

class UiScrollTool : AgentTool {
    override val name = "ui.scroll"
    override val risk = RiskLevel.YELLOW

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val direction = arguments["direction"] ?: return ToolResult(false, "缺少 direction 参数")
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.scroll(direction)
        return ToolResult(ok, if (ok) "已滚动：$direction" else "当前界面无法按 $direction 滚动")
    }
}

class UiTapTool : AgentTool {
    override val name = "ui.tap"
    override val risk = RiskLevel.YELLOW

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val x = arguments["x"]?.toFloatOrNull() ?: return ToolResult(false, "x 参数无效")
        val y = arguments["y"]?.toFloatOrNull() ?: return ToolResult(false, "y 参数无效")
        if (x < 0f || y < 0f) return ToolResult(false, "坐标不能为负数")
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.tap(x, y)
        return ToolResult(ok, if (ok) "已点击坐标：($x, $y)" else "坐标点击失败（Android 7+ 且无障碍需允许手势）")
    }
}

class UiSwipeTool : AgentTool {
    override val name = "ui.swipe"
    override val risk = RiskLevel.YELLOW

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val startX = arguments["start_x"]?.toFloatOrNull() ?: return ToolResult(false, "start_x 参数无效")
        val startY = arguments["start_y"]?.toFloatOrNull() ?: return ToolResult(false, "start_y 参数无效")
        val endX = arguments["end_x"]?.toFloatOrNull() ?: return ToolResult(false, "end_x 参数无效")
        val endY = arguments["end_y"]?.toFloatOrNull() ?: return ToolResult(false, "end_y 参数无效")
        val durationMs = arguments["duration_ms"]?.toLongOrNull() ?: 350L
        if (listOf(startX, startY, endX, endY).any { it < 0f }) {
            return ToolResult(false, "坐标不能为负数")
        }
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.swipe(startX, startY, endX, endY, durationMs)
        return ToolResult(ok, if (ok) "已滑动" else "滑动失败（Android 7+ 且无障碍需允许手势）")
    }
}

class UiRecentsTool : AgentTool {
    override val name = "ui.recents"
    override val risk = RiskLevel.GREEN
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.pressRecents()
        return ToolResult(ok, if (ok) "已打开最近任务" else "最近任务动作失败")
    }
}

class UiNotificationsTool : AgentTool {
    override val name = "ui.notifications"
    override val risk = RiskLevel.GREEN
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.openNotifications()
        return ToolResult(ok, if (ok) "已展开通知栏" else "通知栏动作失败")
    }
}

class UiQuickSettingsTool : AgentTool {
    override val name = "ui.quick_settings"
    override val risk = RiskLevel.GREEN
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.openQuickSettings()
        return ToolResult(ok, if (ok) "已展开快捷设置" else "快捷设置动作失败")
    }
}

class UiPowerDialogTool : AgentTool {
    override val name = "ui.power_dialog"
    override val risk = RiskLevel.GREEN
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.openPowerDialog()
        return ToolResult(ok, if (ok) "已打开电源菜单" else "电源菜单动作失败")
    }
}

class UiLockScreenTool : AgentTool {
    override val name = "ui.lock_screen"
    override val risk = RiskLevel.YELLOW
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.lockScreen()
        return ToolResult(ok, if (ok) "已锁屏" else "锁屏失败（需要 Android 9+）")
    }
}

class UiDismissShadeTool : AgentTool {
    override val name = "ui.dismiss_shade"
    override val risk = RiskLevel.GREEN
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.dismissNotificationShade()
        return ToolResult(ok, if (ok) "已收起通知栏/快捷设置" else "收起通知栏失败（需要 Android 12+）")
    }
}

class UiAllAppsTool : AgentTool {
    override val name = "ui.all_apps"
    override val risk = RiskLevel.GREEN
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.openAllApps()
        return ToolResult(ok, if (ok) "已打开所有应用" else "所有应用动作失败（需要 Android 12+ 且桌面支持）")
    }
}

class UiDpadTool : AgentTool {
    override val name = "ui.dpad"
    override val risk = RiskLevel.YELLOW
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val direction = arguments["direction"]?.trim()?.lowercase()
            ?: return ToolResult(false, "缺少 direction 参数")
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.dpad(direction)
        return ToolResult(ok, if (ok) "已执行方向键：$direction" else "方向键动作失败（需要 Android 13+）")
    }
}

class UiMenuTool : AgentTool {
    override val name = "ui.menu"
    override val risk = RiskLevel.GREEN
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        if (Build.VERSION.SDK_INT < 36) {
            val result = AcsAdbTransport(context).execute("keyevent KEYCODE_MENU")
            return ToolResult(result.success, if (result.success) "已执行菜单键" else result.message)
        }
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.openMenu()
        return ToolResult(ok, if (ok) "已执行菜单键" else "菜单键动作失败")
    }
}

class UiMediaPlayPauseTool : AgentTool {
    override val name = "ui.media_play_pause"
    override val risk = RiskLevel.YELLOW
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        if (Build.VERSION.SDK_INT < 36) {
            val result = AcsAdbTransport(context).execute("keyevent KEYCODE_MEDIA_PLAY_PAUSE")
            return ToolResult(result.success, if (result.success) "已切换媒体播放/暂停" else result.message)
        }
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.mediaPlayPause()
        return ToolResult(ok, if (ok) "已切换媒体播放/暂停" else "媒体播放/暂停动作失败")
    }
}

class UiSplitScreenTool : AgentTool {
    override val name = "ui.split_screen"
    override val risk = RiskLevel.YELLOW
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.toggleSplitScreen()
        return ToolResult(ok, if (ok) "已切换分屏" else "分屏动作失败（系统/桌面可能不支持）")
    }
}

class UiLongPressTool : AgentTool {
    override val name = "ui.long_press"
    override val risk = RiskLevel.YELLOW
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val x = arguments["x"]?.toFloatOrNull() ?: return ToolResult(false, "x 参数无效")
        val y = arguments["y"]?.toFloatOrNull() ?: return ToolResult(false, "y 参数无效")
        val durationMs = arguments["duration_ms"]?.toLongOrNull() ?: 700L
        if (x < 0f || y < 0f) return ToolResult(false, "坐标不能为负数")
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.longPress(x, y, durationMs)
        return ToolResult(ok, if (ok) "已长按坐标：($x, $y)" else "坐标长按失败")
    }
}

class UiPinchTool : AgentTool {
    override val name = "ui.pinch"
    override val risk = RiskLevel.YELLOW
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val centerX = arguments["center_x"]?.toFloatOrNull() ?: return ToolResult(false, "center_x 参数无效")
        val centerY = arguments["center_y"]?.toFloatOrNull() ?: return ToolResult(false, "center_y 参数无效")
        val startSpacing = arguments["start_spacing"]?.toFloatOrNull() ?: return ToolResult(false, "start_spacing 参数无效")
        val endSpacing = arguments["end_spacing"]?.toFloatOrNull() ?: return ToolResult(false, "end_spacing 参数无效")
        val durationMs = arguments["duration_ms"]?.toLongOrNull() ?: 350L
        if (listOf(centerX, centerY, startSpacing, endSpacing).any { it < 0f }) {
            return ToolResult(false, "坐标和双指间距不能为负数")
        }
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.pinch(centerX, centerY, startSpacing, endSpacing, durationMs)
        val mode = if (endSpacing > startSpacing) "放大" else if (endSpacing < startSpacing) "缩小" else "双指零距离变化"
        return ToolResult(ok, if (ok) "已执行双指手势：$mode" else "双指手势失败")
    }
}

class UiTakeScreenshotTool : AgentTool {
    override val name = "ui.take_screenshot"
    override val risk = RiskLevel.YELLOW
    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val (service, error) = serviceOrError()
        if (error != null) return error
        val ok = service!!.takeScreenshot()
        return ToolResult(ok, if (ok) "已触发系统截图" else "系统截图动作失败（需要 Android 9+）")
    }
}
