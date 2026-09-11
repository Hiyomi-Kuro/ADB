package com.kaori.adb.acs

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Connects directly to AndroidIDE/ACS's local ADB server (127.0.0.1:5037).
 * Commands still execute on the device as adb shell UID 2000; this app never
 * tries to obtain shell privileges with Runtime.exec().
 */
class AcsAdbTransport(context: Context) {
    private val appContext = context.applicationContext
    private val client = AdbServerClient()

    fun execute(command: String): AcsAdbExecutionResult {
        val request = command.trim()
        return runCatching {
            when {
                request == "status" -> status()
                request == "ping" -> bridgeCall("ping")
                request == "tools" -> bridgeCall("tools")
                request.startsWith("trace ") -> trace(request.removePrefix("trace ").trim())
                request.startsWith("activities ") -> activities(request.removePrefix("activities ").trim())
                request.startsWith("start-activity ") -> startActivity(request.removePrefix("start-activity ").trim())
                request.startsWith("force-stop ") -> forceStop(request.removePrefix("force-stop ").trim())
                request.startsWith("keyevent ") -> keyEvent(request.removePrefix("keyevent ").trim())
                else -> AcsAdbExecutionResult(false, "不支持的 ACS ADB 请求：$request")
            }
        }.getOrElse { error ->
            AcsAdbExecutionResult(false, readableError(error))
        }
    }

    fun executeRawShell(command: String): AcsAdbExecutionResult {
        val raw = command.trim()
        if (raw.isEmpty()) return AcsAdbExecutionResult(false, "原始 Shell 命令不能为空")
        return runCatching {
            val result = client.shell(raw)
            AcsAdbExecutionResult(
                true,
                buildString {
                    appendLine("ACS ADB：${result.serial}")
                    append(result.output.ifBlank { "命令执行完成（无输出）" })
                }
            )
        }.getOrElse { error ->
            AcsAdbExecutionResult(false, readableError(error))
        }
    }

    private fun status(): AcsAdbExecutionResult {
        val devices = client.devices()
        val ready = devices.filter { it.state == "device" }
        val message = buildString {
            appendLine("ACS ADB server：127.0.0.1:5037 ✓")
            if (devices.isEmpty()) {
                append("未发现 ADB transport")
            } else {
                devices.forEach { device ->
                    appendLine("${device.serial}\t${device.state}${device.details.takeIf { it.isNotBlank() }?.let { "\t$it" } ?: ""}")
                }
                if (ready.size == 1) append("selected_serial=${ready.single().serial}")
            }
        }
        return AcsAdbExecutionResult(ready.size == 1, message.trimEnd())
    }

    private fun bridgeCall(op: String, message64: String? = null): AcsAdbExecutionResult {
        val command = buildString {
            append("am broadcast --receiver-foreground ")
            append("-a com.kaori.adb.CODEX_BRIDGE ")
            append("-n com.kaori.adb/.bridge.CodexBridgeReceiver ")
            append("--es op ").append(op)
            if (!message64.isNullOrBlank()) append(" --es message64 ").append(message64)
        }
        val shell = client.shell(command)
        return decodeBridgeResult(shell.serial, shell.output)
    }

    private fun trace(message: String): AcsAdbExecutionResult {
        if (message.isBlank()) return AcsAdbExecutionResult(false, "Trace 消息不能为空")
        val encoded = Base64.encodeToString(message.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return bridgeCall("trace", encoded)
    }

    private fun activities(packageName: String): AcsAdbExecutionResult {
        if (!PACKAGE_PATTERN.matches(packageName)) {
            return AcsAdbExecutionResult(false, "包名格式无效：$packageName")
        }
        val device = client.singleReadyDevice()
        val packageInfo = packageInfo(packageName)
            ?: return AcsAdbExecutionResult(false, "未找到包：$packageName")
        val activities = packageInfo.activities.orEmpty().sortedBy { it.name }
        val message = buildString {
            appendLine("ACS ADB：${device.serial}")
            appendLine("kind\tcomponent\texported\tenabled\tpermission\ttarget")
            if (activities.isEmpty()) {
                append("未声明 Activity")
            } else {
                activities.forEachIndexed { index, info ->
                    val target = info.targetActivity
                    val kind = if (target.isNullOrBlank()) "activity" else "activity-alias"
                    append(kind).append('\t')
                    append(packageName).append('/').append(info.name).append('\t')
                    append(info.exported).append('\t')
                    append(info.enabled).append('\t')
                    append(info.permission ?: "-").append('\t')
                    append(target ?: "-")
                    if (index != activities.lastIndex) appendLine()
                }
            }
        }
        return AcsAdbExecutionResult(true, message)
    }

    private fun packageInfo(packageName: String): PackageInfo? {
        val pm = appContext.packageManager
        val activityFlags = PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(activityFlags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, activityFlags)
            }
        }.getOrNull()
    }

    private fun startActivity(component: String): AcsAdbExecutionResult {
        if (!COMPONENT_PATTERN.matches(component)) {
            return AcsAdbExecutionResult(false, "组件格式无效：$component")
        }
        val result = client.shell("am start --user current -W -n $component")
        val denied = listOf(
            "SecurityException",
            "Permission Denial",
            "not exported",
            "Error type",
            "Error:"
        ).any { marker -> result.output.contains(marker, ignoreCase = true) }
        return AcsAdbExecutionResult(
            !denied,
            buildString {
                appendLine("ACS ADB：${result.serial}")
                append(result.output.ifBlank { "Activity 启动命令已完成" })
            }
        )
    }

    private fun forceStop(packageName: String): AcsAdbExecutionResult {
        if (!PACKAGE_PATTERN.matches(packageName)) {
            return AcsAdbExecutionResult(false, "包名格式无效：$packageName")
        }
        val result = client.shell("am force-stop $packageName")
        return AcsAdbExecutionResult(
            true,
            buildString {
                appendLine("ACS ADB：${result.serial}")
                append(result.output.ifBlank { "force-stop completed: $packageName" })
            }
        )
    }

    private fun keyEvent(keyCode: String): AcsAdbExecutionResult {
        val normalized = keyCode.trim().uppercase(Locale.ROOT)
        if (normalized !in ALLOWED_KEYEVENTS) {
            return AcsAdbExecutionResult(false, "不支持的快捷按键：$keyCode")
        }
        val result = client.shell("input keyevent $normalized")
        return AcsAdbExecutionResult(
            true,
            buildString {
                appendLine("ACS ADB：${result.serial}")
                append(result.output.ifBlank { "keyevent completed: $normalized" })
            }
        )
    }

    private fun decodeBridgeResult(serial: String, output: String): AcsAdbExecutionResult {
        val encoded = BRIDGE_DATA_REGEX.find(output)?.groupValues?.getOrNull(1)
            ?: return AcsAdbExecutionResult(false, "ACS ADB：$serial\nBridge 未返回 data：\n$output")
        val jsonText = runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrElse {
            return AcsAdbExecutionResult(false, "ACS ADB：$serial\nBridge data 解码失败")
        }
        val json = runCatching { JSONObject(jsonText) }.getOrElse {
            return AcsAdbExecutionResult(false, "ACS ADB：$serial\n$jsonText")
        }
        val success = json.optBoolean("success", false)
        val message = buildString {
            appendLine("ACS ADB：$serial")
            append(json.optString("message", if (success) "完成" else "失败"))
            val data = json.optJSONObject("data")
            if (data != null && data.length() > 0) {
                appendLine()
                append(data.toString(2))
            }
        }
        return AcsAdbExecutionResult(success, message)
    }

    private fun readableError(error: Throwable): String {
        val detail = error.message ?: error.javaClass.simpleName
        return if (error is java.net.ConnectException) {
            "无法连接 ACS ADB server（127.0.0.1:5037）。请先打开 ACS/AndroidIDE 并让其 ADB server 保持运行。\n$detail"
        } else {
            "ACS ADB 执行失败：$detail"
        }
    }

    companion object {
        private val PACKAGE_PATTERN = Regex("""^[A-Za-z0-9_.]+$""")
        private val COMPONENT_PATTERN = Regex("""^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$""")
        private val BRIDGE_DATA_REGEX = Regex("""data=\"([A-Za-z0-9+/=]+)\"""")
        private val ALLOWED_KEYEVENTS = setOf(
            "KEYCODE_WAKEUP",
            "KEYCODE_MEDIA_PLAY_PAUSE",
            "KEYCODE_MEDIA_NEXT",
            "KEYCODE_MEDIA_PREVIOUS"
        )
    }
}

data class AcsAdbExecutionResult(
    val success: Boolean,
    val message: String
)

private data class AdbDevice(
    val serial: String,
    val state: String,
    val details: String
)

private data class AdbShellResult(
    val serial: String,
    val output: String
)

private class AdbServerClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5037
) {
    fun devices(): List<AdbDevice> = connect().use { socket ->
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        sendService(output, "host:devices-l")
        readOkay(input)
        val payload = readLengthPrefixed(input)
        payload.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val firstTab = line.indexOf('\t')
                val serial = if (firstTab >= 0) line.substring(0, firstTab) else line.substringBefore(' ')
                val remainder = if (firstTab >= 0) line.substring(firstTab + 1) else line.substringAfter(' ', "")
                val state = remainder.substringBefore(' ').trim()
                val details = remainder.substringAfter(' ', "").trim()
                AdbDevice(serial, state, details)
            }
            .toList()
    }

    fun singleReadyDevice(): AdbDevice {
        val all = devices()
        val ready = all.filter { it.state == "device" }
        if (ready.size == 1) return ready.single()
        if (all.any { it.state == "authorizing" || it.state == "unauthorized" }) {
            throw IOException("ADB 正在等待手机确认调试 RSA 授权")
        }
        if (ready.isEmpty()) throw IOException("没有可用的 ADB device transport")
        throw IOException("检测到多个 ADB device transport，应用无法安全自动选择")
    }

    fun shell(command: String): AdbShellResult {
        val device = singleReadyDevice()
        return connect().use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            sendService(output, "host:transport:${device.serial}")
            readOkay(input)
            sendService(output, "shell:$command")
            readOkay(input)
            AdbShellResult(device.serial, readUntilEof(input))
        }
    }

    private fun connect(): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = READ_TIMEOUT_MS
        socket.tcpNoDelay = true
        return socket
    }

    private fun sendService(output: BufferedOutputStream, service: String) {
        val payload = service.toByteArray(StandardCharsets.UTF_8)
        if (payload.size > 0xffff) throw IOException("ADB service 请求过长")
        val header = String.format(Locale.US, "%04X", payload.size)
            .toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        output.write(payload)
        output.flush()
    }

    private fun readOkay(input: BufferedInputStream) {
        val status = String(readExact(input, 4), StandardCharsets.US_ASCII)
        when (status) {
            "OKAY" -> return
            "FAIL" -> throw IOException("ADB server 拒绝请求：${readLengthPrefixed(input)}")
            else -> throw IOException("ADB server 返回未知状态：$status")
        }
    }

    private fun readLengthPrefixed(input: BufferedInputStream): String {
        val rawLength = String(readExact(input, 4), StandardCharsets.US_ASCII)
        val length = rawLength.toIntOrNull(16)
            ?: throw IOException("ADB payload 长度无效：$rawLength")
        if (length > MAX_OUTPUT_BYTES) throw IOException("ADB payload 超过大小限制")
        return String(readExact(input, length), StandardCharsets.UTF_8)
    }

    private fun readUntilEof(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size + count > MAX_OUTPUT_BYTES) {
                throw IOException("ADB shell 输出超过 ${MAX_OUTPUT_BYTES / 1024} KiB 限制")
            }
            for (index in 0 until count) bytes.add(buffer[index])
        }
        val array = ByteArray(bytes.size)
        for (index in bytes.indices) array[index] = bytes[index]
        return String(array, StandardCharsets.UTF_8).trimEnd()
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(data, offset, length - offset)
            if (count < 0) throw EOFException("ADB server 提前关闭连接")
            offset += count
        }
        return data
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 1500
        private const val READ_TIMEOUT_MS = 30000
        private const val MAX_OUTPUT_BYTES = 512 * 1024
    }
}
