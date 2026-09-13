package com.kaori.adb.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertTrue(suggestions.any { it.label == "打开微信" && it.command == "打开com.tencent.mm" })
        assertTrue(suggestions.any { it.label == "强制停止微信" && it.command == "强制停止 com.tencent.mm" })
        assertTrue(suggestions.any { it.label == "查看微信 Activities" && it.command == "查看 com.tencent.mm Activities" })
    }

    @Test
    fun matchesBuiltInCommandsAndAliases() {
        val notificationSuggestions = LocalControlSuggestions.matching("通知", emptyList())
        assertTrue(notificationSuggestions.any { it.label == "打开通知栏" })
        assertTrue(notificationSuggestions.any { it.label == "收起通知栏" })

        val wifiSuggestions = LocalControlSuggestions.matching("无", emptyList())
        assertTrue(wifiSuggestions.any { it.label == "打开 Wi-Fi 设置" })

        val bluetoothSuggestions = LocalControlSuggestions.matching("蓝", emptyList())
        assertTrue(bluetoothSuggestions.any { it.label == "打开蓝牙设置" })
    }

    @Test
    fun parameterizedCommandsFillTemplateInsteadOfExecutingImmediately() {
        val suggestions = LocalControlSuggestions.matching("长按", emptyList())
        val textLongClick = suggestions.first { it.label == "长按界面文字" }
        val coordinateLongPress = suggestions.first { it.label == "坐标长按" }

        assertFalse(textLongClick.executeImmediately)
        assertEquals("长按 ", textLongClick.command)
        assertFalse(coordinateLongPress.executeImmediately)
        assertEquals("坐标长按 ", coordinateLongPress.command)
    }
}
