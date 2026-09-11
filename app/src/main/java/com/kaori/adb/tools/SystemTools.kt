package com.kaori.adb.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import com.kaori.adb.agent.AgentTool
import com.kaori.adb.agent.RiskLevel
import com.kaori.adb.agent.ToolResult
import org.json.JSONObject
import java.net.NetworkInterface

class ClipboardReadTool : AgentTool {
    override val name = "clipboard.read"
    override val risk = RiskLevel.GREEN

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return runCatching {
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            ToolResult(true, if (text.isBlank()) "剪贴板为空" else text, mapOf("text" to text))
        }.getOrElse { error ->
            ToolResult(false, "读取剪贴板失败：${error.message ?: error.javaClass.simpleName}")
        }
    }
}

class ClipboardWriteTool : AgentTool {
    override val name = "clipboard.write"
    override val risk = RiskLevel.YELLOW

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val text = arguments["text"] ?: return ToolResult(false, "缺少 text 参数")
        val label = arguments["label"].orEmpty().ifBlank { "ADB Agent" }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            ToolResult(true, "已写入剪贴板", mapOf("length" to text.length.toString()))
        }.getOrElse { error ->
            ToolResult(false, "写入剪贴板失败：${error.message ?: error.javaClass.simpleName}")
        }
    }
}

class AppIntentTool : AgentTool {
    override val name = "app.intent"
    override val risk = RiskLevel.YELLOW

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val dataText = arguments["data"] ?: arguments["uri"]
        val action = arguments["action"]?.takeIf { it.isNotBlank() }
            ?: if (!dataText.isNullOrBlank()) Intent.ACTION_VIEW else return ToolResult(false, "缺少 action 或 uri/data 参数")
        val intent = Intent(action)
        val type = arguments["type"]?.takeIf { it.isNotBlank() }
        val data = dataText?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        if (data != null && type != null) {
            intent.setDataAndType(data, type)
        }
        else if (data != null) {
            intent.data = data
        } else if (type != null) {
            intent.type = type
        }
        val componentText = arguments["component"]
        if (!componentText.isNullOrBlank()) {
            val component = ComponentName.unflattenFromString(componentText)
                ?: return ToolResult(false, "component 格式无效")
            intent.component = component
        }
        val packageName = arguments["package"]
        if (!packageName.isNullOrBlank()) intent.setPackage(packageName)
        arguments["categories"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach {
            intent.addCategory(it)
        }
        val extraFlags = arguments["flags"]?.let { value ->
            runCatching {
                if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toLong(16).toInt()
                else value.toLong().toInt()
            }.getOrNull()
        } ?: 0
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or extraFlags)
        val extrasText = arguments["extras_json"]
        if (!extrasText.isNullOrBlank()) {
            val extras = runCatching { JSONObject(extrasText) }
                .getOrElse { return ToolResult(false, "extras_json 不是有效 JSON 对象") }
            val keys = extras.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = extras.opt(key)
                when (value) {
                    is Boolean -> intent.putExtra(key, value)
                    is Int -> intent.putExtra(key, value)
                    is Long -> intent.putExtra(key, value)
                    is Double -> intent.putExtra(key, value)
                    is String -> intent.putExtra(key, value)
                    else -> if (value != null && value !== JSONObject.NULL) intent.putExtra(key, value.toString())
                }
            }
        }
        return runCatching {
            context.startActivity(intent)
            ToolResult(
                true,
                "Intent 已启动：$action",
                mapOf("action" to action, "data" to dataText.orEmpty(), "component" to componentText.orEmpty())
            )
        }.getOrElse { error ->
            ToolResult(false, "Intent 启动失败：${error.message ?: error.javaClass.simpleName}")
        }
    }
}

class DeviceStateTool : AgentTool {
    override val name = "device.state"
    override val risk = RiskLevel.GREEN

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val window = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val brightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        val autoRotate = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION)
        }.getOrDefault(-1)
        val currentVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val routes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .joinToString(",") { it.productName?.toString().orEmpty().ifBlank { "type-${it.type}" } }
        } else {
            "unknown"
        }
        val data = linkedMapOf<String, String>()
        data["network"] = networkSummary(context)
        data["ip"] = localAddresses()
        data["brightness"] = brightness.toString()
        data["auto_rotate"] = autoRotate.toString()
        data["rotation"] = window.defaultDisplay.rotation.toString()
        data["screen_interactive"] = power.isInteractive.toString()
        data["music_volume"] = "$currentVolume/$maxVolume"
        data["ringer_mode"] = audio.ringerMode.toString()
        data["audio_outputs"] = routes
        return ToolResult(
            true,
            "网络：${data["network"]}；IP：${data["ip"]}；亮度：$brightness；屏幕亮起：${power.isInteractive}；媒体音量：$currentVolume/$maxVolume",
            data
        )
    }

    private fun networkSummary(context: Context): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return manager.activeNetworkInfo?.typeName ?: "disconnected"
        }
        val active = manager.activeNetwork ?: return "disconnected"
        val capabilities = manager.getNetworkCapabilities(active) ?: return "unknown"
        val transports = mutableListOf<String>()
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "wifi"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "cellular"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "ethernet"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports += "vpn"
        return transports.ifEmpty { listOf("other") }.joinToString("+")
    }

    private fun localAddresses(): String = runCatching {
        val addresses = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            val values = network.inetAddresses
            while (values.hasMoreElements()) {
                val address = values.nextElement()
                if (!address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    address.hostAddress?.let(addresses::add)
                }
            }
        }
        addresses.distinct().joinToString(",").ifBlank { "unknown" }
    }.getOrDefault("unknown")
}
