package com.kaori.adb.tools

import android.content.Context
import com.kaori.adb.agent.AgentEngine
import com.kaori.adb.agent.AgentTool
import com.kaori.adb.agent.RiskLevel
import com.kaori.adb.agent.ToolCall
import com.kaori.adb.agent.ToolResult
import org.json.JSONArray
import org.json.JSONObject

class WorkflowRunTool : AgentTool {
    override val name = "workflow.run"
    override val risk = RiskLevel.YELLOW

    override fun execute(context: Context, arguments: Map<String, String>): ToolResult {
        val rawSteps = arguments["steps"]
            ?: return ToolResult(false, "缺少 steps JSON 数组")
        val steps = runCatching { JSONArray(rawSteps) }
            .getOrElse { return ToolResult(false, "steps 不是有效 JSON 数组") }
        if (steps.length() == 0) return ToolResult(false, "steps 不能为空")
        if (steps.length() > 30) return ToolResult(false, "单次 workflow 最多 30 步")

        val engine = AgentEngine(context)
        val outputs = JSONArray()
        var completed = 0
        var allSucceeded = true
        for (index in 0 until steps.length()) {
            val step = steps.optJSONObject(index)
                ?: return ToolResult(false, "第 ${index + 1} 步不是 JSON 对象")
            val action = step.optString("action").trim()
            if (action.isBlank()) return ToolResult(false, "第 ${index + 1} 步缺少 action")
            if (action == name) return ToolResult(false, "workflow.run 不允许递归调用自身")
            val stepArguments = jsonToStringMap(step.optJSONObject("arguments"))
            val call = ToolCall(action, stepArguments)
            val result = engine.execute(call, confirmed = false)
            completed += 1

            val output = JSONObject()
                .put("step", index + 1)
                .put("action", action)
                .put("success", result.success)
                .put("message", result.message)
            result.data["after_package"]?.let { output.put("package", it) }
            result.data["after_screenshot_location"]?.let { output.put("screenshot_location", it) }
            outputs.put(output)
            if (result.confirmationRequired) {
                return ToolResult(
                    false,
                    "第 ${index + 1} 步需要单独确认：$action",
                    mapOf("results" to outputs.toString(), "completed" to completed.toString()),
                    confirmationRequired = true
                )
            }
            if (!result.success) {
                allSucceeded = false
                if (!step.optBoolean("continue_on_failure", false)) break
            }
            val pauseMs = step.optLong("pause_ms", 0L).coerceIn(0L, 5_000L)
            if (pauseMs > 0L) Thread.sleep(pauseMs)
        }

        val finalObservation = UiObserveTool().execute(context, mapOf("screenshot" to "true", "base64" to "true"))
        val data = linkedMapOf<String, String>()
        data["results"] = outputs.toString()
        data["completed"] = completed.toString()
        data["total"] = steps.length().toString()
        finalObservation.data.forEach { (key, value) ->
            data["final_$key"] = value
        }
        return ToolResult(
            allSucceeded,
            if (allSucceeded) "工作流已完成 $completed 步" else "工作流在第 $completed 步停止",
            data
        )
    }

    private fun jsonToStringMap(value: JSONObject?): MutableMap<String, String> {
        val result = linkedMapOf<String, String>()
        if (value == null) return result
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = value.opt(key)?.toString().orEmpty()
        }
        return result
    }
}
