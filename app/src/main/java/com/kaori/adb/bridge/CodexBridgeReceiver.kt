package com.kaori.adb.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.kaori.adb.agent.AgentEngine
import com.kaori.adb.agent.ToolCall
import org.json.JSONObject

/**
 * Narrow shell-only bridge used by the Codex phone-control skill.
 * The manifest requires android.permission.DUMP, which adb shell holds but
 * ordinary third-party applications do not.
 */
class CodexBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CODEX_BRIDGE) return

        val appContext = context.applicationContext
        val op = intent.getStringExtra(EXTRA_OP).orEmpty()
        if (op == OP_TOOL) {
            handleToolAsync(appContext, intent)
            return
        }

        val engine = AgentEngine(appContext)
        val payload = when (op) {
            OP_PING -> response(
                success = true,
                message = "ADB bridge ready",
                data = mapOf("tools" to engine.toolSummary())
            )

            OP_TOOLS -> response(
                success = true,
                message = "registered tools",
                data = mapOf("tools" to engine.toolSummary())
            )

            OP_TRACE -> {
                val message = decodeBase64(intent.getStringExtra(EXTRA_MESSAGE64).orEmpty())
                if (message.isBlank()) {
                    response(false, "missing trace message")
                } else {
                    engine.recordExternal("codex.adb", message.take(MAX_TRACE_CHARS))
                    response(true, "trace recorded")
                }
            }

            else -> response(false, "unknown op")
        }

        publishResult(payload)
    }

    private fun handleToolAsync(context: Context, intent: Intent) {
        val pending = goAsync()
        val toolName = intent.getStringExtra(EXTRA_TOOL).orEmpty().trim()
        val arguments = decodeArguments(intent)
        val confirmed = intent.getBooleanExtra(EXTRA_CONFIRMED, false)

        Thread({
            val payload = runCatching {
                if (toolName.isEmpty()) {
                    response(false, "missing tool")
                } else {
                    val result = AgentEngine(context).execute(ToolCall(toolName, arguments), confirmed)
                    response(
                        success = result.success,
                        message = result.message,
                        data = result.data + mapOf(
                            "confirmation_required" to result.confirmationRequired.toString(),
                            "tool" to toolName
                        )
                    )
                }
            }.getOrElse { error ->
                response(false, "bridge tool failed: ${error.message ?: error.javaClass.simpleName}")
            }

            try {
                pending.setResultCode(if (payload.optBoolean("success")) 0 else 1)
                pending.setResultData(encodeResult(payload))
            } finally {
                pending.finish()
            }
        }, "CodexBridgeTool").start()
    }

    private fun publishResult(payload: JSONObject) {
        setResultCode(if (payload.optBoolean("success")) 0 else 1)
        setResultData(encodeResult(payload))
    }

    private fun encodeResult(payload: JSONObject): String =
        Base64.encodeToString(
            payload.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

    private fun decodeArguments(intent: Intent): Map<String, String> {
        val extras = intent.extras ?: return emptyMap()
        return extras.keySet()
            .asSequence()
            .filter { it.startsWith(ARG64_PREFIX) }
            .mapNotNull { key ->
                val encoded = extras.getString(key) ?: return@mapNotNull null
                val value = decodeBase64(encoded)
                key.removePrefix(ARG64_PREFIX) to value
            }
            .toMap()
    }

    private fun decodeBase64(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun response(
        success: Boolean,
        message: String,
        data: Map<String, String> = emptyMap()
    ): JSONObject = JSONObject().apply {
        put("success", success)
        put("message", message)
        put("data", JSONObject(data))
    }

    companion object {
        const val ACTION_CODEX_BRIDGE = "com.kaori.adb.CODEX_BRIDGE"
        private const val EXTRA_OP = "op"
        private const val EXTRA_TOOL = "tool"
        private const val EXTRA_CONFIRMED = "confirmed"
        private const val EXTRA_MESSAGE64 = "message64"
        private const val ARG64_PREFIX = "arg64."
        private const val OP_PING = "ping"
        private const val OP_TOOLS = "tools"
        private const val OP_TOOL = "tool"
        private const val OP_TRACE = "trace"
        private const val MAX_TRACE_CHARS = 12000
    }
}
