package com.kaori.adb.security

import com.kaori.adb.agent.RiskLevel

object RiskPolicy {
    fun requiresConfirmation(level: RiskLevel): Boolean = level == RiskLevel.RED

    fun label(level: RiskLevel): String = when (level) {
        RiskLevel.GREEN -> "绿色"
        RiskLevel.YELLOW -> "黄色"
        RiskLevel.RED -> "红色"
    }
}
