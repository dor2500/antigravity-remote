package com.antigravity.remote.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class BridgeClient(
    private val scope: CoroutineScope,
    private val onConnectionStateChanged: (ConnectionStatus, String?) -> Unit,
    private val onStatusUpdated: (BridgeStatus) -> Unit,
    private val onLogReceived: (LogEntry) -> Unit,
    private val onLogsCleared: () -> Unit
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var currentProfile: ConnectionProfile? = null
    private var webSocket: WebSocket? = null
    private var manualDisconnect = false
    private var reconnectJob: Job? = null
    private val logIdCounter = AtomicLong(1L)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun connect(profile: ConnectionProfile) {
        manualDisconnect = false
        reconnectJob?.cancel()
        currentProfile = profile
        onConnectionStateChanged(ConnectionStatus.CONNECTING, null)

        scope.launch(Dispatchers.IO) {
            val statusResult = fetchStatus(profile)
            if (statusResult.isSuccess) {
                statusResult.getOrNull()?.let { onStatusUpdated(it) }
                openWebSocket(profile)
            } else {
                val msg = statusResult.exceptionOrNull()?.message ?: "Unable to reach bridge server"
                onConnectionStateChanged(ConnectionStatus.DISCONNECTED, msg)
            }
        }
    }

    fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        currentProfile = null
        onConnectionStateChanged(ConnectionStatus.DISCONNECTED, null)
    }

    private fun openWebSocket(profile: ConnectionProfile) {
        webSocket?.cancel()
        val request = Request.Builder().url(profile.wsUrl).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onConnectionStateChanged(ConnectionStatus.CONNECTED, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWsMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (code == 4001) {
                    manualDisconnect = true
                    onConnectionStateChanged(ConnectionStatus.DISCONNECTED, "Invalid auth token")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!manualDisconnect) {
                    scheduleReconnect()
                } else {
                    onConnectionStateChanged(ConnectionStatus.DISCONNECTED, null)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!manualDisconnect) {
                    scheduleReconnect(t.message)
                } else {
                    onConnectionStateChanged(ConnectionStatus.DISCONNECTED, t.message)
                }
            }
        })
    }

    private fun scheduleReconnect(reason: String? = null) {
        val profile = currentProfile ?: return
        if (manualDisconnect) return
        onConnectionStateChanged(ConnectionStatus.RECONNECTING, reason)
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(2500)
            if (!manualDisconnect && currentProfile == profile) {
                openWebSocket(profile)
            }
        }
    }

    private fun handleWsMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            val type = json.optString("type")
            val timestamp = json.optString("timestamp", "")
            when (type) {
                "log", "terminal_exit", "workspace_changed" -> {
                    val message = json.optString("message", "")
                    val level = json.optString("level", "system")
                    if (message.isNotEmpty()) {
                        onLogReceived(
                            LogEntry(
                                id = logIdCounter.getAndIncrement(),
                                level = level,
                                message = message,
                                timestamp = formatTimestamp(timestamp)
                            )
                        )
                    }
                    if (type == "workspace_changed") {
                        val data = json.optJSONObject("data")
                        if (data != null) {
                            onStatusUpdated(
                                BridgeStatus(
                                    activeWorkspace = data.optString("activeWorkspace", ""),
                                    workspaceName = data.optString("workspaceName", "")
                                )
                            )
                        }
                    }
                }
                "status" -> {
                    val data = json.optJSONObject("data") ?: return
                    onStatusUpdated(
                        BridgeStatus(
                            hostname = data.optString("hostname", ""),
                            activeWorkspace = data.optString("activeWorkspace", ""),
                            workspaceName = data.optString("workspaceName", ""),
                            running = data.optBoolean("running", false),
                            activeCommand = data.optString("activeCommand", "").takeIf { it.isNotEmpty() },
                            antigravityRunning = data.optBoolean("antigravityRunning", false)
                        )
                    )
                }
                "clear" -> onLogsCleared()
            }
        } catch (_: Exception) {
            // Ignore malformed JSON frames
        }
    }

    private fun formatTimestamp(iso: String): String {
        val tIndex = iso.indexOf('T')
        return if (tIndex != -1 && iso.length >= tIndex + 9) {
            iso.substring(tIndex + 1, tIndex + 9)
        } else {
            ""
        }
    }

    private fun authorizedRequest(profile: ConnectionProfile, path: String): Request.Builder {
        return Request.Builder()
            .url("${profile.baseUrl}$path")
            .header("Authorization", "Bearer ${profile.token}")
    }

    suspend fun fetchStatus(profile: ConnectionProfile? = currentProfile): Result<BridgeStatus> {
        val p = profile ?: return Result.failure(IllegalStateException("Not connected"))
        return runCatching {
            val req = authorizedRequest(p, "/api/status").get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = if (resp.code == 401) "Unauthorized (check token)" else "HTTP ${resp.code}"
                    error(msg)
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                BridgeStatus(
                    hostname = json.optString("hostname", ""),
                    activeWorkspace = json.optString("activeWorkspace", ""),
                    workspaceName = json.optString("workspaceName", ""),
                    running = json.optBoolean("running", false),
                    activeCommand = json.optString("activeCommand", "").takeIf { it.isNotEmpty() },
                    antigravityRunning = json.optBoolean("antigravityRunning", false)
                )
            }
        }
    }

    suspend fun sendPrompt(prompt: String): Result<Unit> {
        val p = currentProfile ?: return Result.failure(IllegalStateException("Not connected"))
        return runCatching {
            val body = JSONObject().put("prompt", prompt).toString().toRequestBody(jsonMediaType)
            val req = authorizedRequest(p, "/api/prompt").post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Failed (${resp.code})")
            }
        }
    }

    suspend fun runTerminalCommand(command: String): Result<Unit> {
        val p = currentProfile ?: return Result.failure(IllegalStateException("Not connected"))
        return runCatching {
            val body = JSONObject().put("command", command).toString().toRequestBody(jsonMediaType)
            val req = authorizedRequest(p, "/api/terminal").post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errText = resp.body?.string().orEmpty()
                    error("HTTP ${resp.code}: $errText")
                }
            }
        }
    }

    suspend fun sendAction(action: String): Result<Unit> {
        val p = currentProfile ?: return Result.failure(IllegalStateException("Not connected"))
        return runCatching {
            val body = JSONObject().put("action", action).toString().toRequestBody(jsonMediaType)
            val req = authorizedRequest(p, "/api/action").post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Action failed (${resp.code})")
            }
        }
    }

    suspend fun fetchWorkspaces(): Result<List<WorkspaceItem>> {
        val p = currentProfile ?: return Result.failure(IllegalStateException("Not connected"))
        return runCatching {
            val req = authorizedRequest(p, "/api/workspaces").get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val json = JSONObject(resp.body?.string().orEmpty())
                val arr = json.optJSONArray("workspaces") ?: return@use emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        add(
                            WorkspaceItem(
                                path = obj.getString("path"),
                                name = obj.getString("name"),
                                active = obj.optBoolean("active", false)
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun switchWorkspace(path: String): Result<Unit> {
        val p = currentProfile ?: return Result.failure(IllegalStateException("Not connected"))
        return runCatching {
            val body = JSONObject().put("path", path).toString().toRequestBody(jsonMediaType)
            val req = authorizedRequest(p, "/api/workspaces/switch").post(body).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
            }
        }
    }

    suspend fun fetchFiles(relativePath: String = ""): Result<List<FileEntry>> {
        val p = currentProfile ?: return Result.failure(IllegalStateException("Not connected"))
        return runCatching {
            val encoded = URLEncoder.encode(relativePath, "UTF-8")
            val req = authorizedRequest(p, "/api/files?path=$encoded").get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val json = JSONObject(resp.body?.string().orEmpty())
                val arr = json.optJSONArray("entries") ?: return@use emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        add(
                            FileEntry(
                                name = obj.getString("name"),
                                path = obj.getString("path"),
                                isDirectory = obj.getBoolean("isDirectory"),
                                size = obj.optLong("size", 0L)
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun readFile(relativePath: String): Result<FileContent> {
        val p = currentProfile ?: return Result.failure(IllegalStateException("Not connected"))
        return runCatching {
            val encoded = URLEncoder.encode(relativePath, "UTF-8")
            val req = authorizedRequest(p, "/api/files/read?path=$encoded").get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val json = JSONObject(resp.body?.string().orEmpty())
                FileContent(
                    path = json.getString("path"),
                    content = json.getString("content"),
                    truncated = json.optBoolean("truncated", false),
                    size = json.optLong("size", 0L)
                )
            }
        }
    }
}
