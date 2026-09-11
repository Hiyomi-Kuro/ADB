package com.kaori.adb.agent

import android.content.Context

enum class RiskLevel {
    GREEN,
    YELLOW,
    RED
}

data class ToolCall(
    val action: String,
    val arguments: Map<String, String> = emptyMap()
) {
    fun describe(): String {
        if (arguments.isEmpty()) return action
        return "$action(${arguments.entries.joinToString { "${it.key}=\"${it.value}\"" }})"
    }
}

data class ToolResult(
    val success: Boolean,
    val message: String,
    val data: Map<String, String> = emptyMap(),
    val confirmationRequired: Boolean = false
)

data class AgentPlan(
    val summary: String,
    val calls: List<ToolCall>
)

interface AgentTool {
    val name: String
    val risk: RiskLevel
    fun execute(context: Context, arguments: Map<String, String>): ToolResult
}
