package com.kaori.adb.model

import com.kaori.adb.agent.AgentPlan

interface ModelProvider {
    fun plan(
        userMessage: String,
        availableTools: Set<String>,
        skillText: String
    ): AgentPlan
}
