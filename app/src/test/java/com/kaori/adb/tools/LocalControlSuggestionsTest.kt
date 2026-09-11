package com.kaori.adb.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalControlSuggestionsTest {
    @Test
    fun matchesInstalledAppsByAnyInputCharacterAndUsesPackageForExecution() {
        val suggestions = LocalControlSuggestions.matching(
            "微",
            listOf(
                AppCandidate("微信", "com.tencent.mm"),
                AppCandidate("企业微信", "com.tencent.wework")
            )
        )

        assertEquals(listOf("打开微信", "打开企业微信"), suggestions.map { it.label })
        assertEquals("打开com.tencent.mm", suggestions.first().command)
    }

    @Test
    fun matchesBuiltInCommands() {
        val suggestions = LocalControlSuggestions.matching("通知", emptyList())

        assertTrue(suggestions.any { it.label == "打开通知栏" })
        assertTrue(suggestions.any { it.label == "收起通知栏" })
    }
}
