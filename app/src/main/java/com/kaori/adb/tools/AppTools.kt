package com.kaori.adb.tools

import android.content.Context
import android.content.Intent
import com.kaori.adb.agent.AgentTool
import com.kaori.adb.agent.RiskLevel
import com.kaori.adb.agent.ToolResult

class AppLaunchTool : AgentTool {
    override val name = "app.launch"
    override val risk = RiskLevel.GREEN

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val target = arguments["package"] ?: arguments["app"]
            ?: return ToolResult(false, "缺少 app/package 参数")
        val candidates = AppResolver.resolveCandidates(context, target)
        if (candidates.isEmpty()) return ToolResult(false, "找不到应用：$target")
        if (candidates.size > 1) {
            val packages = candidates.joinToString("\n") { candidate ->
                "${candidate.label}：${candidate.packageName}"
            }
            return ToolResult(
                false,
                "找到多个同名应用，请改用具体包名：\n$packages",
                mapOf("packages" to candidates.joinToString(",") { it.packageName })
            )
        }

        val packageName = candidates.single().packageName
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResult(false, "应用没有可启动 Activity：$packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolResult(true, "已启动 $packageName", mapOf("package" to packageName))
        }.getOrElse { ToolResult(false, "启动失败：${it.message}") }
    }
}
