package com.kaori.adb.agent

import android.content.Context
import com.kaori.adb.data.TraceStore
import com.kaori.adb.model.ModelProvider
import com.kaori.adb.model.RuleBasedModelProvider
import com.kaori.adb.security.RiskPolicy
import com.kaori.adb.tools.ToolRegistry

class AgentEngine(
    context: Context,
    private val modelProvider: ModelProvider = RuleBasedModelProvider()
) {
    private val appContext = context.applicationContext
    private val registry = ToolRegistry.default()
    private val traceStore = TraceStore(appContext)
    private val skillText by lazy { SkillRepository(appContext).loadCoreSkills() }

    fun plan(userMessage: String): AgentPlan {
        traceStore.append("user", userMessage)
        val plan = modelProvider.plan(userMessage, registry.names, skillText)
        traceStore.append("agent.plan", "${plan.summary}: ${plan.calls.joinToString { it.describe() }}")
        return plan
    }

    fun riskFor(call: ToolCall): RiskLevel? = registry.get(call.action)?.risk

    fun execute(call: ToolCall, confirmed: Boolean = false): ToolResult {
        val autoVerify = shouldAutoVerify(call.action)
        val before = if (autoVerify) {
            executeRaw(
                ToolCall("ui.observe", mapOf("screenshot" to "false", "base64" to "false")),
                confirmed = true
            )
        } else {
            null
        }

        val actionResult = executeRaw(call, confirmed)
        if (!actionResult.success || !autoVerify) {
            traceStore.recordTool(call, actionResult)
            return actionResult
        }

        val verificationArgs = verificationArguments(call.arguments)
        val waitResult = if (verificationArgs.isNotEmpty()) {
            executeRaw(ToolCall("ui.wait_for", verificationArgs), confirmed = true)
        } else {
            null
        }
        val after = executeRaw(
            ToolCall("ui.observe", mapOf("screenshot" to "true", "base64" to "true")),
            confirmed = true
        )
        val combinedData = LinkedHashMap(actionResult.data)
        val beforeUi = before?.data?.get("ui").orEmpty()
        val afterUi = after.data["ui"].orEmpty()
        combinedData["before_package"] = before?.data?.get("package").orEmpty()
        combinedData["state_changed"] = (beforeUi != afterUi).toString()
        after.data.forEach { (key, value) ->
            combinedData["after_$key"] = value
        }
        if (waitResult != null) {
            combinedData["verification_message"] = waitResult.message
            combinedData["verified"] = waitResult.success.toString()
        } else {
            combinedData["verified"] = after.success.toString()
        }
        val verificationFailed = waitResult != null && !waitResult.success
        val finalResult = ToolResult(
            success = !verificationFailed,
            message = when {
                verificationFailed -> "${actionResult.message}；验证失败：${waitResult?.message}"
                waitResult != null -> "${actionResult.message}；验证成功"
                after.success -> "${actionResult.message}；已重新观察界面"
                else -> "${actionResult.message}；重新观察界面失败"
            },
            data = combinedData,
            confirmationRequired = actionResult.confirmationRequired
        )
        traceStore.recordTool(call, finalResult)
        return finalResult
    }

    private fun executeRaw(call: ToolCall, confirmed: Boolean): ToolResult {
        val tool = registry.get(call.action)
            ?: return ToolResult(false, "未知工具：${call.action}")
        if (RiskPolicy.requiresConfirmation(tool.risk) && !confirmed) {
            return ToolResult(
                success = false,
                message = "${RiskPolicy.label(tool.risk)}风险动作需要确认：${call.describe()}",
                confirmationRequired = true
            )
        }
        return runCatching { tool.execute(appContext, call.arguments) }
            .getOrElse { ToolResult(false, "执行异常：${it.message ?: it.javaClass.simpleName}") }
    }

    private fun shouldAutoVerify(action: String): Boolean {
        if (action == "ui.observe" || action == "ui.dump" || action == "ui.wait_for") return false
        if (action.startsWith("ui.")) return true
        return action == "app.launch" || action == "app.start_activity" || action == "app.intent"
    }

    private fun verificationArguments(arguments: Map<String, String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val mappings = listOf(
            "expect_text" to "text",
            "expect_absent_text" to "absent_text",
            "expect_description" to "description",
            "expect_id" to "id",
            "expect_package" to "package",
            "expect_clickable_text" to "clickable_text"
        )
        mappings.forEach { (source, target) ->
            arguments[source]?.takeIf { it.isNotBlank() }?.let { result[target] = it }
        }
        arguments["verify_timeout_ms"]?.let { result["timeout_ms"] = it }
        arguments["verify_interval_ms"]?.let { result["interval_ms"] = it }
        return result
    }

    fun toolSummary(): String = registry.all().joinToString("\n") {
        "${it.name} · ${RiskPolicy.label(it.risk)}"
    }

    fun recordExternal(actor: String, message: String) {
        traceStore.append(actor, message)
    }

    fun recentTrace(): String = traceStore.readRecent()
}
