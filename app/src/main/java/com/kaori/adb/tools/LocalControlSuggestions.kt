package com.kaori.adb.tools

data class LocalControlSuggestion(
    val label: String,
    val command: String,
    val keywords: List<String> = emptyList(),
    val executeImmediately: Boolean = true
)

object LocalControlSuggestions {
    private val commonSuggestions = listOf(
        action("ADB 状态", "ADB状态", "adb", "连接", "调试"),
        action("查看电量", keywords = arrayOf("电池", "充电")),
        action("查看设备信息", keywords = arrayOf("设备", "内存", "存储", "前台")),
        action("查看设备状态", keywords = arrayOf("状态", "网络", "IP", "亮度", "旋转", "音频")),
        action("观察当前界面", keywords = arrayOf("观察", "界面", "截图", "UI")),
        action("读取界面结构", keywords = arrayOf("界面", "结构", "UI树", "控件")),
        action("读取剪贴板", keywords = arrayOf("剪贴板", "复制", "粘贴")),
        action("返回", keywords = arrayOf("返回键", "back")),
        action("回到桌面", keywords = arrayOf("主页", "home", "桌面")),
        action("查看最近任务", keywords = arrayOf("最近", "多任务", "recents")),
        action("打开通知栏", keywords = arrayOf("通知", "通知中心")),
        action("打开快捷设置", keywords = arrayOf("快捷", "控制中心")),
        action("收起通知栏", keywords = arrayOf("收起", "关闭通知栏")),
        action("打开电源菜单", keywords = arrayOf("电源", "关机菜单")),
        action("打开所有应用", keywords = arrayOf("所有应用", "应用抽屉")),
        action("截屏", keywords = arrayOf("截图", "屏幕截图")),
        action("锁屏", keywords = arrayOf("锁定", "屏幕")),
        action("亮屏", keywords = arrayOf("唤醒", "点亮")),
        action("调高音量", keywords = arrayOf("音量加", "声音大")),
        action("调低音量", keywords = arrayOf("音量减", "声音小")),
        action("静音", keywords = arrayOf("媒体静音")),
        action("取消静音", keywords = arrayOf("解除静音", "恢复声音")),
        action("下一首", keywords = arrayOf("下一曲", "媒体")),
        action("上一首", keywords = arrayOf("上一曲", "媒体")),
        action("播放/暂停", keywords = arrayOf("播放暂停", "音乐", "媒体")),
        action("菜单键", keywords = arrayOf("menu", "菜单")),
        action("方向键上", keywords = arrayOf("dpad", "上键")),
        action("方向键下", keywords = arrayOf("dpad", "下键")),
        action("方向键左", keywords = arrayOf("dpad", "左键")),
        action("方向键右", keywords = arrayOf("dpad", "右键")),
        action("方向键确认", keywords = arrayOf("dpad", "中键", "确定")),
        action("分屏", keywords = arrayOf("多窗口", "split")),
        action("打开设置", keywords = arrayOf("系统设置", "setting")),
        action("打开 Wi-Fi 设置", "打开WiFi设置", "wifi", "无线", "网络"),
        action("打开蓝牙设置", keywords = arrayOf("蓝牙", "bluetooth")),
        action("打开相机", keywords = arrayOf("拍照", "camera")),
        action("打开浏览器", keywords = arrayOf("网页", "浏览", "browser")),
        template("打开应用", "打开 ", "启动", "应用"),
        template("强制停止应用", "强制停止 ", "停止", "关闭", "结束应用"),
        template("查看应用 Activities", "查看Activity ", "activity", "活动列表", "组件"),
        template("启动 Activity", "启动Activity ", "activity", "组件"),
        template("点击界面文字", "点击 ", "点击", "控件", "按钮"),
        template("长按界面文字", "长按 ", "长按", "控件"),
        template("输入文字", "输入 ", "输入", "填写", "文本"),
        action("向上滚动", keywords = arrayOf("上滑", "滚动")),
        action("向下滚动", keywords = arrayOf("下滑", "滚动")),
        action("向左滚动", keywords = arrayOf("左滑", "滚动")),
        action("向右滚动", keywords = arrayOf("右滑", "滚动")),
        template("点击坐标", "点击坐标 ", "坐标", "点按"),
        template("滑动手势", "滑动 ", "swipe", "手势"),
        template("坐标长按", "坐标长按 ", "长按", "坐标"),
        template("双指缩放", "双指缩放 ", "缩放", "放大", "缩小", "pinch"),
        template("等待界面文字", "等待文字 ", "等待", "出现", "界面"),
        template("按键事件", "按键 ", "按键", "keycode", "device.keyevent"),
        template("发送系统 Intent", "发送Intent ", "intent", "系统动作", "app.intent"),
        template("运行工作流", "运行工作流 ", "工作流", "批量", "步骤", "workflow.run"),
        template("原始 ADB Shell（需确认）", "shell ", "shell", "命令", "adb", "acs.adb.shell"),
        template("写入剪贴板", "写入剪贴板 ", "剪贴板", "复制")
    )

    fun matching(query: String, apps: List<AppCandidate>): List<LocalControlSuggestion> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val staticMatches = commonSuggestions.filter { suggestion -> suggestion.matches(keyword) }
        val appMatches = apps.flatMap { app ->
            listOf(
                LocalControlSuggestion(
                    label = "打开${app.label}",
                    command = "打开${app.packageName}",
                    keywords = listOf(app.label, app.packageName, "启动", "应用")
                ),
                LocalControlSuggestion(
                    label = "强制停止${app.label}",
                    command = "强制停止 ${app.packageName}",
                    keywords = listOf(app.label, app.packageName, "停止", "关闭", "结束")
                ),
                LocalControlSuggestion(
                    label = "查看${app.label} Activities",
                    command = "查看 ${app.packageName} Activities",
                    keywords = listOf(app.label, app.packageName, "activity", "组件", "活动")
                )
            )
        }.filter { suggestion -> suggestion.matches(keyword) }

        return (staticMatches + appMatches).distinctBy { it.command }.take(MAX_SUGGESTIONS)
    }

    private fun action(
        label: String,
        command: String = label,
        vararg keywords: String
    ) = LocalControlSuggestion(label, command, keywords.toList())

    private fun template(
        label: String,
        command: String,
        vararg keywords: String
    ) = LocalControlSuggestion(label, command, keywords.toList(), executeImmediately = false)

    private fun LocalControlSuggestion.matches(query: String): Boolean {
        val needle = normalize(query)
        if (needle.isBlank()) return false
        val haystack = normalize(buildString {
            append(label)
            append(' ')
            append(command)
            keywords.forEach {
                append(' ')
                append(it)
            }
        })
        return haystack.contains(needle)
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace("wi-fi", "wifi")
        .replace(Regex("[\\s_./-]+"), "")

    private const val MAX_SUGGESTIONS = 12
}
