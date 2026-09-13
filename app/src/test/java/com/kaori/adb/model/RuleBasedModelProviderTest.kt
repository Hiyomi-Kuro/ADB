package com.kaori.adb.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedModelProviderTest {
    private val provider = RuleBasedModelProvider()
    private val tools = setOf(
        "device.state",
        "device.keyevent",
        "app.intent",
        "clipboard.write",
        "ui.wait_for",
        "ui.long_press",
        "ui.pinch"
    )

    @Test
    fun plansSearchableSystemActions() {
        val state = provider.plan("查看设备状态", tools, "")
        assertTrue(state.calls.any { it.action == "device.state" })

        val menu = provider.plan("菜单键", tools, "")
        val menuCall = menu.calls.single { it.action == "device.keyevent" }
        assertEquals("KEYCODE_MENU", menuCall.arguments["key"])

        val wifi = provider.plan("打开WiFi设置", tools, "")
        val wifiCall = wifi.calls.single { it.action == "app.intent" }
        assertEquals("android.settings.WIFI_SETTINGS", wifiCall.arguments["action"])

        val bluetooth = provider.plan("打开蓝牙设置", tools, "")
        val bluetoothCall = bluetooth.calls.single { it.action == "app.intent" }
        assertEquals("android.settings.BLUETOOTH_SETTINGS", bluetoothCall.arguments["action"])
    }

    @Test
    fun plansParameterizedLocalTools() {
        val clipboard = provider.plan("写入剪贴板 hello", tools, "")
        assertEquals("hello", clipboard.calls.single { it.action == "clipboard.write" }.arguments["text"])

        val wait = provider.plan("等待文字 完成", tools, "")
        assertEquals("完成", wait.calls.single { it.action == "ui.wait_for" }.arguments["text"])

        val longPress = provider.plan("坐标长按 100,200 900", tools, "")
        val longPressCall = longPress.calls.single { it.action == "ui.long_press" }
        assertEquals("100", longPressCall.arguments["x"])
        assertEquals("200", longPressCall.arguments["y"])
        assertEquals("900", longPressCall.arguments["duration_ms"])
        assertFalse(longPress.calls.any { it.action == "ui.long_click" })

        val pinch = provider.plan("双指缩放 500,1000 200->500", tools, "")
        val pinchCall = pinch.calls.single { it.action == "ui.pinch" }
        assertEquals("500", pinchCall.arguments["center_x"])
        assertEquals("1000", pinchCall.arguments["center_y"])
        assertEquals("200", pinchCall.arguments["start_spacing"])
        assertEquals("500", pinchCall.arguments["end_spacing"])
    }
}
