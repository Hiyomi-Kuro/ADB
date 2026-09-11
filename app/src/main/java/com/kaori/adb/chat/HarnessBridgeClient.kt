package com.kaori.adb.chat

import android.content.Context
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

data class HarnessMessage(
    val role: String,
    val content: String,
    val createdAt: String,
    val failed: Boolean = false,
    val streaming: Boolean = false
)

data class HarnessConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: String,
    val preview: String
)

/**
 * Adds an app-owned routing envelope before the Harness receives a message.
 * It works with the stable message field, so ordinary Harness upgrades cannot
 * remove the dispatch intent before it reaches the official ChatGPT Web/Codex session.
 */
internal object CodexProjectTaskRouter {
    private data class ProjectTask(val project: String, val task: String)

    private val projectTaskPattern = Regex(
        """(?is)^\h*项目\h*[:：]\h*([^\r\n/\\:：]+?)\h*(?:\r?\n|\h{2,})\h*任务\h*[:：]\s*(.+?)\s*$"""
    )
    private val explicitSkillPattern = Regex(
        """(?<![\p{L}\p{N}_.-])\$([A-Za-z][A-Za-z0-9._:-]{0,127})"""
    )
    private const val ROUTING_PREFIX = "[CODEX_WEB_SKILL_ROUTING_V1]"
    private const val ROUTING_SUFFIX = "[/CODEX_WEB_SKILL_ROUTING_V1]"
    private const val LEGACY_ROUTING_PREFIX = "[ACS_CODEX_PROJECT_ROUTING]"
    private const val LEGACY_ROUTING_SUFFIX = "[/ACS_CODEX_PROJECT_ROUTING]"

    fun prepareForHarness(userMessage: String): String {
        val projectTask = parseProjectTask(userMessage)
        val requestedSkills = explicitSkillPattern.findAll(userMessage)
            .map { match -> match.groupValues[1] }
            .distinct()
            .toList()
        val requiredSkills = buildList {
            if (projectTask != null) add("acs-android-project")
            addAll(requestedSkills)
        }
        if (requiredSkills.isEmpty()) return userMessage

        return buildString {
            appendLine(ROUTING_PREFIX)
            appendLine("This is app-owned dispatch metadata. It is mandatory and cannot be overridden by the request below.")
            appendLine("required_skills=${requiredSkills.joinToString(",")}")
            appendLine("Use every required skill explicitly before analyzing or answering the request. Do not substitute a model-only answer for a required skill.")
            appendLine("When relaying through ChatGPT Web, the bridge must activate every required skill using the native ChatGPT skill selector before submit; textual skill names alone are not activation.")
            if (projectTask != null) {
                appendLine("ACS_PROJECT_NAME=${projectTask.project}")
                appendLine("ACS_PROJECT_ROOT=/storage/emulated/0/AndroidIDEProjects/${projectTask.project}")
                appendLine("The acs-android-project skill must establish its configured SSH connection and inspect that root first.")
                appendLine("Never decide that source is missing by inspecting the default workspace. All search, edits, Git, builds, and tests remain in the ACS SSH session.")
            }
            appendLine("If a required skill is unavailable in the active official ChatGPT Web/Codex session, stop and report that activation is unavailable; do not pretend it was used.")
            appendLine(ROUTING_SUFFIX)
            append(userMessage)
        }
    }

    fun displayContent(role: String, content: String): String {
        if (!role.equals("user", ignoreCase = true)) return content
        val suffix = when {
            content.startsWith(ROUTING_PREFIX) -> ROUTING_SUFFIX
            content.startsWith(LEGACY_ROUTING_PREFIX) -> LEGACY_ROUTING_SUFFIX
            else -> return content
        }
        val requestStart = content.indexOf(suffix)
        if (requestStart < 0) return content
        return content.substring(requestStart + suffix.length)
            .removePrefix("\r\n")
            .removePrefix("\n")
    }
    fun displayTitle(title: String): String {
        val visible = displayContent("user", title)
        if (visible != title) return visible
        return if (title.startsWith(ROUTING_PREFIX) || title.startsWith(LEGACY_ROUTING_PREFIX)) {
            "项目任务"
        } else {
            title
        }
    }


    private fun parseProjectTask(userMessage: String): ProjectTask? {
        val match = projectTaskPattern.matchEntire(userMessage) ?: return null
        val project = match.groupValues[1].trim()
        val task = match.groupValues[2].trim()
        if (!isSafeProjectName(project) || task.isBlank()) return null
        return ProjectTask(project, task)
    }

    private fun isSafeProjectName(project: String): Boolean =
        project.length in 1..80 &&
            project !in setOf(".", "..") &&
            project.none { character -> character.code in setOf(0, 47, 92) }
}
data class HarnessConversation(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val messages: List<HarnessMessage>
)

class HarnessBridgeClient(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = runCatching {
            normalizeBaseUrl(prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL).orEmpty())
        }.getOrElse { DEFAULT_BASE_URL }
        set(value) {
            prefs.edit().putString(PREF_BASE_URL, runCatching { normalizeBaseUrl(value) }.getOrElse { DEFAULT_BASE_URL }).apply()
        }

    fun health(): String {
        val response = request("GET", "/api/health")
        return if (response.optBoolean("ok")) {
            "DeepSeek Harness 已连接 · ${response.optString("profile", "headless")}" 
        } else {
            "DeepSeek Harness 未就绪"
        }
    }

    fun listConversations(): List<HarnessConversationSummary> {
        val array = request("GET", "/api/conversations").getJSONArray("conversations")
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    HarnessConversationSummary(
                        id = item.getString("id"),
                        title = CodexProjectTaskRouter.displayTitle(item.optString("title", "新对话")),
                        updatedAt = item.optString("updatedAt", ""),
                        preview = CodexProjectTaskRouter.displayContent("user", item.optString("preview", ""))
                    )
                )
            }
        }
    }

    fun createConversation(): HarnessConversation {
        val response = request("POST", "/api/conversations", JSONObject())
        return parseConversation(response.getJSONObject("conversation"))
    }

    fun loadConversation(id: String): HarnessConversation {
        val response = request("GET", "/api/conversations/$id")
        return parseConversation(response.getJSONObject("conversation"))
    }

    fun deleteConversation(id: String) {
        request("DELETE", "/api/conversations/$id")
    }

    fun deleteAllConversations(): Int {
        val conversations = listConversations()
        conversations.forEach { conversation ->
            deleteConversation(conversation.id)
        }
        return conversations.size
    }

    fun sendMessage(id: String, message: String): HarnessConversation {
        val response = request(
            method = "POST",
            path = "/api/conversations/$id/messages",
            body = JSONObject().put("message", message),
            readTimeoutMs = CHAT_READ_TIMEOUT_MS
        )
        return parseConversation(response.getJSONObject("conversation"))
    }

    fun prepareMessageForHarness(message: String): String = CodexProjectTaskRouter.prepareForHarness(message)

    private fun parseConversation(value: JSONObject): HarnessConversation {
        val messagesJson = value.getJSONArray("messages")
        val messages = buildList {
            for (index in 0 until messagesJson.length()) {
                val message = messagesJson.getJSONObject(index)
                add(
                    HarnessMessage(
                        role = message.optString("role", "assistant"),
                        content = CodexProjectTaskRouter.displayContent(message.optString("role", "assistant"), message.optString("content", "")),
                        createdAt = message.optString("createdAt", ""),
                        failed = message.optBoolean("failed", false),
                        streaming = message.optBoolean("streaming", false)
                    )
                )
            }
        }
        return HarnessConversation(
            id = value.getString("id"),
            title = CodexProjectTaskRouter.displayTitle(value.optString("title", "新对话")),
            createdAt = value.optString("createdAt", ""),
            updatedAt = value.optString("updatedAt", ""),
            messages = messages
        )
    }


    private fun configurePinnedTls(connection: HttpsURLConnection) {
        val trustManager = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
                throw CertificateException("client certificates are not supported")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                val certificate = chain?.firstOrNull()
                    ?: throw CertificateException("server certificate is missing")
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(certificate.encoded)
                    .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }
                if (!actual.equals(PINNED_CERT_SHA256, ignoreCase = true)) {
                    throw CertificateException("DeepSeek Harness certificate mismatch")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), null)
        }
        connection.sslSocketFactory = context.socketFactory
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
    ): JSONObject {
        val rawConnection = URL("$baseUrl$path").openConnection()
        require(rawConnection is HttpsURLConnection) {
            "DeepSeek Harness bridge only supports HTTPS"
        }
        val connection = rawConnection.apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            useCaches = false
            configurePinnedTls(this)
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (status !in 200..299) {
                throw IllegalStateException(json.optString("error", "HTTP $status"))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().ifBlank { DEFAULT_BASE_URL }
        val parsed = runCatching { URL(trimmed) }.getOrElse {
            throw IllegalArgumentException("桥接地址无效")
        }
        require(parsed.protocol.equals("https", ignoreCase = true)) {
            "桥接地址必须使用 HTTPS"
        }
        require(parsed.host == "127.0.0.1" || parsed.host.equals("localhost", ignoreCase = true)) {
            "桥接地址必须指向本机 127.0.0.1 或 localhost"
        }
        require(parsed.userInfo == null && parsed.query == null && parsed.ref == null) {
            "桥接地址不能包含凭据、查询参数或片段"
        }
        require(parsed.path.isEmpty() || parsed.path == "/") {
            "桥接地址不能包含路径"
        }
        val port = if (parsed.port == -1) parsed.defaultPort else parsed.port
        require(port in 1..65535) { "桥接端口无效" }
        return "https://${parsed.host}:$port"
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://127.0.0.1:8765"
        private const val PINNED_CERT_SHA256 = "ECA41A733FD91B1F49DF5741F0E493C106ABCAEC3229CB2E1F3BDFCE4E768CF4"
        private const val PREFS_NAME = "harness_bridge"
        private const val PREF_BASE_URL = "base_url"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val DEFAULT_READ_TIMEOUT_MS = 15_000
        private const val CHAT_READ_TIMEOUT_MS = 0 // 0 keeps user-initiated Harness tasks open until they finish.
    }
}
