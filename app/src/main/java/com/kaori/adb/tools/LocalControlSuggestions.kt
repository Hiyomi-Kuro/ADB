package com.kaori.adb.tools

data class LocalControlSuggestion(
    val label: String,
    val command: String
)

object LocalControlSuggestions {
    private val commonSuggestions = listOf(
        LocalControlSuggestion("查看电量", "查看电量"),
        LocalControlSuggestion("查看设备信息", "查看设备信息"),
        LocalControlSuggestion("读取界面结构", "读取界面结构"),
        LocalControlSuggestion("返回", "返回"),
        LocalControlSuggestion("回到桌面", "回到桌面"),
        LocalControlSuggestion("查看最近任务", "查看最近任务"),
        LocalControlSuggestion("打开通知栏", "打开通知栏"),
        LocalControlSuggestion("打开快捷设置", "打开快捷设置"),
        LocalControlSuggestion("收起通知栏", "收起通知栏"),
        LocalControlSuggestion("截屏", "截屏"),
        LocalControlSuggestion("锁屏", "锁屏"),
        LocalControlSuggestion("调高音量", "调高音量"),
        LocalControlSuggestion("调低音量", "调低音量"),
        LocalControlSuggestion("静音", "静音"),
        LocalControlSuggestion("取消静音", "取消静音")
    )

    fun matching(query: String, apps: List<AppCandidate>): List<LocalControlSuggestion> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val staticMatches = commonSuggestions.filter { suggestion ->
            suggestion.label.contains(keyword, ignoreCase = true) ||
                suggestion.command.contains(keyword, ignoreCase = true)
        }
        val appMatches = apps.map { app ->
            LocalControlSuggestion(
                label = "打开${app.label}",
                command = "打开${app.packageName}"
            )
        }.filter { suggestion ->
            suggestion.label.contains(keyword, ignoreCase = true) ||
                suggestion.command.contains(keyword, ignoreCase = true)
        }

        return (staticMatches + appMatches).distinctBy { it.command }.take(MAX_SUGGESTIONS)
    }

    private const val MAX_SUGGESTIONS = 8
}
