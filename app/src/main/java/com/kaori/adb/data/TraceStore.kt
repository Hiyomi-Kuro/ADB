package com.kaori.adb.data

import android.content.Context
import com.kaori.adb.agent.ToolCall
import com.kaori.adb.agent.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TraceStore(private val context: Context) {
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun append(actor: String, message: String) {
        val safe = message.replace("\n", "\\n").replace("\t", " ")
        val line = "${formatter.format(Date())}\t$actor\t$safe\n"
        context.openFileOutput(FILE_NAME, Context.MODE_APPEND).use { stream ->
            stream.write(line.toByteArray(Charsets.UTF_8))
        }
    }

    fun recordTool(call: ToolCall, result: ToolResult) {
        append("tool", "${call.describe()} -> success=${result.success}; ${result.message}")
    }

    fun readRecent(maxChars: Int = 12000): String {
        return runCatching {
            context.openFileInput(FILE_NAME).bufferedReader().use { it.readText() }.takeLast(maxChars)
        }.getOrDefault("")
    }

    companion object {
        private const val FILE_NAME = "agent_trace.log"
    }
}
