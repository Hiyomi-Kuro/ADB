package com.kaori.adb.tools

import android.content.Context
import com.kaori.adb.acs.AcsAdbTransport
import com.kaori.adb.agent.AgentTool
import com.kaori.adb.agent.RiskLevel
import com.kaori.adb.agent.ToolResult

class AcsAdbStatusTool : AgentTool {
    override val name = "acs.adb.status"
    override val risk = RiskLevel.GREEN

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val result = AcsAdbTransport(context).execute("status")
        return ToolResult(result.success, result.message)
    }
}

class AcsAdbActivitiesTool : AgentTool {
    override val name = "app.activities"
    override val risk = RiskLevel.GREEN

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val target = arguments["package"] ?: arguments["app"]
            ?: return ToolResult(false, "缺少 app/package 参数")
        val packageName = resolveSinglePackage(context, target) ?: return packageResolutionError(context, target)
        val result = AcsAdbTransport(context).execute("activities $packageName")
        return ToolResult(result.success, result.message, mapOf("package" to packageName))
    }
}

class AcsAdbStartActivityTool : AgentTool {
    override val name = "app.start_activity"
    override val risk = RiskLevel.YELLOW

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val component = arguments["component"]?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolResult(false, "缺少 component 参数")
        val result = AcsAdbTransport(context).execute("start-activity $component")
        return ToolResult(result.success, result.message, mapOf("component" to component))
    }
}

class AcsAdbForceStopTool : AgentTool {
    override val name = "app.force_stop"
    override val risk = RiskLevel.YELLOW

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val target = arguments["package"] ?: arguments["app"]
            ?: return ToolResult(false, "缺少 app/package 参数")
        val packageName = resolveSinglePackage(context, target) ?: return packageResolutionError(context, target)
        val result = AcsAdbTransport(context).execute("force-stop $packageName")
        return ToolResult(result.success, result.message, mapOf("package" to packageName))
    }
}

class AcsAdbShellTool : AgentTool {
    override val name = "acs.adb.shell"
    override val risk = RiskLevel.RED

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val command = arguments["command"]?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ToolResult(false, "缺少 command 参数")
        val result = AcsAdbTransport(context).executeRawShell(command)
        return ToolResult(result.success, result.message)
    }
}

class AcsAdbKeyEventTool : AgentTool {
    override val name = "device.keyevent"
    override val risk = RiskLevel.YELLOW

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val key = arguments["key"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult(false, "缺少 key 参数")
        val result = AcsAdbTransport(context).execute("keyevent $key")
        return ToolResult(result.success, result.message, mapOf("key" to key))
    }
}

private fun resolveSinglePackage(context: Context, target: String): String? {
    val candidates = AppResolver.resolveCandidates(context, target)
    return candidates.singleOrNull()?.packageName
}

private fun packageResolutionError(context: Context, target: String): ToolResult {
    val candidates = AppResolver.resolveCandidates(context, target)
    if (candidates.isEmpty()) return ToolResult(false, "找不到应用：$target")
    val packages = candidates.joinToString("\n") { "${it.label}：${it.packageName}" }
    return ToolResult(
        false,
        "找到多个同名应用，请改用具体包名：\n$packages",
        mapOf("packages" to candidates.joinToString(",") { it.packageName })
    )
}
