package com.kaori.adb.model

import com.kaori.adb.agent.AgentPlan
import com.kaori.adb.agent.ToolCall

class RuleBasedModelProvider : ModelProvider {
    override fun plan(
        userMessage: String,
        availableTools: Set<String>,
        skillText: String
    ): AgentPlan {
        val text = userMessage.trim()
        val lower = text.lowercase()
        val calls = mutableListOf<ToolCall>()

        fun add(action: String, args: Map<String, String> = emptyMap()) {
            if (action in availableTools) calls += ToolCall(action, args)
        }

        if (text.contains("电量") || text.contains("电池")) add("device.battery")
        if (
            text.contains("设备状态") || text.contains("网络状态") || text.contains("当前网络") ||
            text.contains("IP地址", ignoreCase = true) || text.contains("屏幕亮度") || text.contains("自动旋转")
        ) {
            add("device.state")
        }
        when {
            text.contains("取消静音") || text.contains("解除静音") -> add("device.volume", mapOf("action" to "unmute"))
            text == "静音" || text.contains("媒体静音") -> add("device.volume", mapOf("action" to "mute"))
            text.contains("音量加") || text.contains("调高音量") || text.contains("音量大一点") -> add("device.volume", mapOf("action" to "up"))
            text.contains("音量减") || text.contains("调低音量") || text.contains("音量小一点") -> add("device.volume", mapOf("action" to "down"))
        }
        if (text.contains("唤醒屏幕") || text == "亮屏" || text == "点亮屏幕") {
            add("device.keyevent", mapOf("key" to "KEYCODE_WAKEUP"))
        }
        if (text.contains("下一首") || text.contains("下一曲")) {
            add("device.keyevent", mapOf("key" to "KEYCODE_MEDIA_NEXT"))
        }
        if (text.contains("上一首") || text.contains("上一曲")) {
            add("device.keyevent", mapOf("key" to "KEYCODE_MEDIA_PREVIOUS"))
        }
        if (text.contains("播放暂停") || text.contains("播放/暂停") || lower == "media play pause") {
            add("device.keyevent", mapOf("key" to "KEYCODE_MEDIA_PLAY_PAUSE"))
        }
        if (text.contains("内存") || text.contains("存储") || text.contains("设备信息") || text.contains("前台应用")) {
            add("device.info")
        }
        if (text.contains("观察当前界面") || text == "观察界面" || text == "界面观察") {
            add("ui.observe", mapOf("base64" to "false"))
        }
        if (text.contains("读取界面") || text.contains("界面结构") || text.contains("UI树", ignoreCase = true)) {
            add("ui.dump")
        }
        if (text == "读取剪贴板" || text == "查看剪贴板" || text == "剪贴板内容") {
            add("clipboard.read")
        }
        if (text == "返回" || text.endsWith("返回键")) add("ui.back")
        if (text == "回到桌面" || text == "主页" || lower == "home") add("ui.home")
        if (text.contains("最近任务") || text.contains("多任务") || lower == "recents") add("ui.recents")
        if ((text.contains("通知栏") || text.contains("通知中心")) && !text.startsWith("收起") && !text.startsWith("关闭")) add("ui.notifications")
        if (text.contains("快捷设置") || text.contains("控制中心")) add("ui.quick_settings")
        if (text.contains("电源菜单")) add("ui.power_dialog")
        if (text == "锁屏" || text.endsWith("锁定屏幕")) add("ui.lock_screen")
        if (text.contains("收起通知栏") || text.contains("关闭通知栏")) add("ui.dismiss_shade")
        if (text.contains("所有应用") || text.contains("应用抽屉")) add("ui.all_apps")
        if (text.contains("菜单键") || lower == "menu") add("device.keyevent", mapOf("key" to "KEYCODE_MENU"))
        if (text.contains("分屏")) add("ui.split_screen")
        if (text.contains("截屏") || text.contains("截图")) add("ui.take_screenshot")
        if (text.contains("方向键上") || lower == "dpad up") add("ui.dpad", mapOf("direction" to "up"))
        if (text.contains("方向键下") || lower == "dpad down") add("ui.dpad", mapOf("direction" to "down"))
        if (text.contains("方向键左") || lower == "dpad left") add("ui.dpad", mapOf("direction" to "left"))
        if (text.contains("方向键右") || lower == "dpad right") add("ui.dpad", mapOf("direction" to "right"))
        if (text.contains("方向键确认") || text.contains("方向键中键") || lower == "dpad center") add("ui.dpad", mapOf("direction" to "center"))
        if (lower == "adb status" || text.equals("ADB状态", ignoreCase = true)) add("acs.adb.status")

        parseGenericKeyEvent(text)?.let { add("device.keyevent", mapOf("key" to it)) }
        parseWorkflow(text)?.let { add("workflow.run", mapOf("steps" to it)) }
        val systemIntent = parseSystemIntent(text) ?: parseGenericIntent(text)
        systemIntent?.let { add("app.intent", it) }
        parseClipboardWrite(text)?.let { add("clipboard.write", mapOf("text" to it)) }
        parseWaitForText(text)?.let { add("ui.wait_for", mapOf("text" to it)) }
        parseLongClick(text)?.let { add("ui.long_click", mapOf("text" to it)) }
        parseScroll(text)?.let { add("ui.scroll", mapOf("direction" to it)) }
        parseTap(text)?.let { (x, y) -> add("ui.tap", mapOf("x" to x, "y" to y)) }
        parseSwipe(text)?.let { args -> add("ui.swipe", args) }
        parseLongPress(text)?.let { args -> add("ui.long_press", args) }
        parsePinch(text)?.let { args -> add("ui.pinch", args) }

        parseClick(text)?.let { add("ui.click", mapOf("text" to it)) }
        parseInput(text)?.let { add("ui.input_text", mapOf("text" to it)) }

        parseActivities(text)?.let { target ->
            val packageName = COMMON_PACKAGES[target] ?: target
            add("app.activities", mapOf("app" to packageName))
        }
        parseStartActivity(text)?.let { component ->
            add("app.start_activity", mapOf("component" to component))
        }
        parseForceStop(text)?.let { target ->
            val packageName = COMMON_PACKAGES[target] ?: target
            add("app.force_stop", mapOf("app" to packageName))
        }
        parseRawShell(text)?.let { command ->
            add("acs.adb.shell", mapOf("command" to command))
        }

        if (systemIntent == null) {
            parseLaunch(text)?.let { target ->
                val packageName = COMMON_PACKAGES[target] ?: target
                add("app.launch", mapOf("app" to packageName))
            }
        }

        return if (calls.isEmpty()) {
            AgentPlan(
                summary = "当前规划器可编排普通 API、Accessibility 和结构化 ACS ADB 工具；原始 Shell 仍需逐次确认。",
                calls = emptyList()
            )
        } else {
            AgentPlan(summary = "计划执行 ${calls.size} 个结构化动作", calls = calls.distinct())
        }
    }

    private fun parseGenericKeyEvent(text: String): String? {
        val match = Regex("""(?:按键|keyevent)\s+(KEYCODE_[A-Z0-9_]+)""", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        return match.groupValues[1].uppercase()
    }

    private fun parseGenericIntent(text: String): Map<String, String>? {
        val prefixes = listOf("发送Intent ", "发送 Intent ", "intent ")
        val prefix = prefixes.firstOrNull { text.startsWith(it, ignoreCase = true) } ?: return null
        val payload = text.substring(prefix.length).trim()
        if (payload.isBlank()) return null
        val parts = payload.split(Regex("\\s+"), limit = 2)
        return linkedMapOf("action" to parts[0]).apply {
            parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { put("uri", it) }
        }
    }

    private fun parseWorkflow(text: String): String? {
        val prefixes = listOf("运行工作流 ", "工作流 ")
        val prefix = prefixes.firstOrNull { text.startsWith(it, ignoreCase = true) } ?: return null
        return text.substring(prefix.length).trim().takeIf { it.startsWith("[") && it.endsWith("]") }
    }


    private fun parseSystemIntent(text: String): Map<String, String>? {
        val compact = text.replace(" ", "")
        return when {
            compact.contains("打开WiFi设置", ignoreCase = true) ||
                compact.equals("打开WIFI", ignoreCase = true) ||
                compact.contains("无线网络设置") -> mapOf("action" to "android.settings.WIFI_SETTINGS")
            compact.contains("打开蓝牙设置", ignoreCase = true) || compact.equals("打开蓝牙", ignoreCase = true) ->
                mapOf("action" to "android.settings.BLUETOOTH_SETTINGS")
            compact == "打开设置" || compact == "打开系统设置" ->
                mapOf("action" to "android.settings.SETTINGS")
            compact == "打开相机" || compact == "启动相机" || compact == "拍照" ->
                mapOf("action" to "android.media.action.STILL_IMAGE_CAMERA")
            compact == "打开浏览器" || compact == "启动浏览器" ->
                mapOf("action" to "android.intent.action.VIEW", "uri" to "https://www.google.com")
            else -> null
        }
    }

    private fun parseClipboardWrite(text: String): String? {
        val match = Regex("(?:写入剪贴板|复制到剪贴板)\\s*[“\"']?([^”\"'，。；]+)").find(text) ?: return null
        return match.groupValues[1].trim()
    }

    private fun parseWaitForText(text: String): String? {
        val match = Regex("(?:等待文字|等待界面文字|等待出现)\\s*[“\"']?([^”\"'，。；]+)").find(text) ?: return null
        return match.groupValues[1].trim()
    }

    private fun parseLongClick(text: String): String? {
        if (text.contains("坐标长按") || text.contains("长按坐标")) return null
        val match = Regex("(?:长按一下|长按)\\s*[“\"']?([^”\"'，。；]+)").find(text) ?: return null
        return match.groupValues[1].trim()
    }

    private fun parseScroll(text: String): String? = when {
        text.contains("向下滚动") || text.contains("往下滚") || text.contains("下滑") -> "down"
        text.contains("向上滚动") || text.contains("往上滚") || text.contains("上滑") -> "up"
        text.contains("向左滚动") || text.contains("左滑") -> "left"
        text.contains("向右滚动") || text.contains("右滑") -> "right"
        else -> null
    }

    private fun parseTap(text: String): Pair<String, String>? {
        val match = Regex("""(?:点击坐标|坐标点击|点按坐标)\s*\(?\s*(\d+(?:\.\d+)?)\s*[,，]\s*(\d+(?:\.\d+)?)\s*\)?""")
            .find(text) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun parseSwipe(text: String): Map<String, String>? {
        val match = Regex(
            """(?:滑动|swipe)\s*\(?\s*(\d+(?:\.\d+)?)\s*[,，]\s*(\d+(?:\.\d+)?)\s*[-→>]\s*(\d+(?:\.\d+)?)\s*[,，]\s*(\d+(?:\.\d+)?)\s*\)?""",
            RegexOption.IGNORE_CASE
        ).find(text) ?: return null
        return mapOf(
            "start_x" to match.groupValues[1],
            "start_y" to match.groupValues[2],
            "end_x" to match.groupValues[3],
            "end_y" to match.groupValues[4]
        )
    }

    private fun parseLongPress(text: String): Map<String, String>? {
        val match = Regex(
            """(?:坐标长按|长按坐标)\s*\(?\s*(\d+(?:\.\d+)?)\s*[,，]\s*(\d+(?:\.\d+)?)\s*\)?(?:\s+(\d+))?"""
        ).find(text) ?: return null
        val result = linkedMapOf(
            "x" to match.groupValues[1],
            "y" to match.groupValues[2]
        )
        match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { result["duration_ms"] = it }
        return result
    }

    private fun parsePinch(text: String): Map<String, String>? {
        val match = Regex(
            """(?:双指缩放|pinch)\s*\(?\s*(\d+(?:\.\d+)?)\s*[,，]\s*(\d+(?:\.\d+)?)\s+(\d+(?:\.\d+)?)\s*[-→>]\s*(\d+(?:\.\d+)?)\s*\)?""",
            RegexOption.IGNORE_CASE
        ).find(text) ?: return null
        return mapOf(
            "center_x" to match.groupValues[1],
            "center_y" to match.groupValues[2],
            "start_spacing" to match.groupValues[3],
            "end_spacing" to match.groupValues[4]
        )
    }

    private fun parseActivities(text: String): String? {
        if (!text.contains("Activit", ignoreCase = true) && !text.contains("活动列表")) return null
        Regex("""[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+""").find(text)?.value?.let { return it }
        val match = Regex("(?:列出|查看|获取)\\s*([^，。；]+?)(?:的)?(?:Activities|Activity列表|活动列表)", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        return cleanTarget(match.groupValues[1])
    }

    private fun parseStartActivity(text: String): String? {
        if (!text.contains("Activity", ignoreCase = true) && !text.contains("组件") && !text.startsWith("start-activity", ignoreCase = true)) {
            return null
        }
        return Regex("""[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+""").find(text)?.value
    }

    private fun parseForceStop(text: String): String? {
        val match = Regex("(?:强制停止|结束应用|force[- ]?stop)\\s*([^，。；]+)", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        return cleanTarget(match.groupValues[1])
    }

    private fun parseRawShell(text: String): String? {
        val trimmed = text.trim()
        val prefixes = listOf("shell ", "执行 shell ", "执行shell ", "原始 shell ", "原始shell ")
        val prefix = prefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) } ?: return null
        return trimmed.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
    }

    private fun parseLaunch(text: String): String? {
        if (listOf("通知栏", "通知中心", "快捷设置", "控制中心", "最近任务", "多任务", "电源菜单", "所有应用", "应用抽屉", "菜单键", "播放暂停", "分屏")
                .any { text.contains(it) }
        ) return null
        if (text.contains("Activity", ignoreCase = true) || text.contains("强制停止") ||
            text.lowercase().startsWith("force-stop")
        ) return null
        val match = Regex("(?:打开|启动)\\s*([^，。；]+)").find(text) ?: return null
        return cleanTarget(match.groupValues[1])
    }

    private fun parseClick(text: String): String? {
        if (text.contains("点击坐标") || text.contains("坐标点击") || text.contains("点按坐标")) return null
        val match = Regex("(?:点击|点一下|点开)\\s*[“\"']?([^”\"'，。；]+)").find(text) ?: return null
        return match.groupValues[1].trim()
    }

    private fun parseInput(text: String): String? {
        val match = Regex("(?:输入|填写)\\s*[“\"']?([^”\"'，。；]+)").find(text) ?: return null
        return match.groupValues[1].trim()
    }

    private fun cleanTarget(raw: String): String {
        return raw.trim().removeSuffix("应用").removeSuffix("App").removeSuffix("APP").trim()
    }

    companion object {
        private val COMMON_PACKAGES = mapOf(
            "微信" to "com.tencent.mm",
            "Chrome" to "com.android.chrome",
            "chrome" to "com.android.chrome",
            "设置" to "com.android.settings",
            "系统设置" to "com.android.settings"
        )
    }
}
