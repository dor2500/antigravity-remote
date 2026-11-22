package com.antigravity.remote.data

import com.antigravity.remote.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Talks to the Windows bridge: REST for commands, one WebSocket for the live event stream.
 * Tries every saved route to the PC, asks the address relay when none answers, and reconnects
 * with backoff until [disconnect] is called.
 */
class BridgeClient(
    private val scope: CoroutineScope,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionState(status: ConnectionStatus, error: UiText?)
        fun onStatus(status: BridgeStatus)
        fun onLog(entry: LogEntry)
        fun onClear(channel: Channel)
        fun onPermission(request: PermissionRequest?)
        fun onRoute(baseUrl: String?)
        /** The relay reported new addresses for the PC; the caller should save them. */
        fun onProfileUpdated(profile: ConnectionProfile)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    /** Short timeouts for probing which route to the PC currently works. */
    private val probe = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()
    private val ids = AtomicLong(1L)

    @Volatile private var profile: ConnectionProfile? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var activeBase: String? = null
    @Volatile private var everConnected = false
    /** Changes on every connect/disconnect, so callbacks from an old attempt can tell they are stale. */
    @Volatile private var generation = 0
    private var reconnectJob: Job? = null
    private var attempt = 0

    val activeProfile: ConnectionProfile? get() = profile

    /** Language the bridge should use for its own messages. */
    private val language: String get() = Locale.getDefault().language.let { if (it == "iw") "he" else it }

    fun connect(target: ConnectionProfile) {
        val gen = ++generation
        everConnected = false
        attempt = 0
        profile = target
        reconnectJob?.cancel()
        socket?.cancel()
        socket = null
        listener.onConnectionState(ConnectionStatus.CONNECTING, null)
        scope.launch(Dispatchers.IO) {
            val found = findRoute(gen)
            if (gen != generation) return@launch
            if (found.isSuccess) {
                openSocket(gen)
            } else {
                generation++
                profile = null
                listener.onConnectionState(ConnectionStatus.DISCONNECTED, describe(found.exceptionOrNull()!!))
            }
        }
    }

    /**
     * Tries every route to the PC (the last working one first). When none answers, asks the
     * address relay for the PC's current addresses (a new tunnel URL, for example) and tries those.
     * A wrong token stops the search, since no route will accept it.
     */
    private fun findRoute(gen: Int): Result<String> {
        val target = profile ?: return Result.failure(IOException("stale"))
        val first = probeRoutes(target)
        if (first.isSuccess || first.exceptionOrNull() is BridgeHttpException) return first
        val relayed = AddressRelay.fetch(probe, target.token) ?: return first
        if (gen != generation) return first
        val updated = target.withRelayedRoutes(relayed)
        if (updated.routes == target.routes) return first
        profile = updated
        listener.onProfileUpdated(updated)
        return probeRoutes(updated).let { if (it.isSuccess) it else first }
    }

    private fun probeRoutes(target: ConnectionProfile): Result<String> {
        val ordered = (listOfNotNull(activeBase?.takeIf { it in target.routes }) + target.routes).distinct()
        var lastError: Throwable = IOException("No route")
        for (base in ordered) {
            val result = runCatching { execute(probe, request(target, base, "/api/status").get().build()) { parseStatus(it) } }
            result.onSuccess { status ->
                activeBase = base
                listener.onRoute(base)
                listener.onStatus(status)
                return Result.success(base)
            }
            lastError = result.exceptionOrNull() ?: lastError
            if (lastError is BridgeHttpException) break
        }
        return Result.failure(lastError)
    }

    /** Skip the backoff wait, e.g. when the network comes back or the app returns to the foreground. */
    fun reconnectNow() {
        if (profile == null || !everConnected || socket != null) return
        val gen = generation
        reconnectJob?.cancel()
        attempt = 0
        reconnectJob = scope.launch(Dispatchers.IO) {
            if (findRoute(gen).isSuccess && gen == generation && socket == null) openSocket(gen)
        }
    }

    fun disconnect() {
        generation++
        reconnectJob?.cancel()
        socket?.close(1000, "Disconnected by user")
        socket = null
        profile = null
        activeBase = null
        listener.onRoute(null)
        listener.onConnectionState(ConnectionStatus.DISCONNECTED, null)
    }

    private fun openSocket(gen: Int) {
        val target = profile ?: return
        val base = activeBase ?: target.routes.first()
        val request = Request.Builder().url(target.wsUrl(base) + "&lang=" + language).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                attempt = 0
                everConnected = true
                listener.onConnectionState(ConnectionStatus.CONNECTED, null)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation) handleEvent(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if ((code == 4001 || code == 4029) && gen == generation) {
                    generation++
                    profile = null
                    val message = if (code == 4001) R.string.error_token else R.string.error_locked_out
                    listener.onConnectionState(ConnectionStatus.DISCONNECTED, UiText.Res(message))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lost(webSocket, gen, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lost(webSocket, gen, describe(t))
            }
        })
    }

    private fun lost(webSocket: WebSocket, gen: Int, reason: UiText?) {
        if (socket === webSocket) socket = null
        if (gen != generation || profile == null) return
        listener.onConnectionState(ConnectionStatus.RECONNECTING, reason)
        val wait = (1000L shl attempt.coerceAtMost(3)).coerceAtMost(8000L)
        attempt += 1
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(wait)
            if (gen != generation || socket != null) return@launch
            // The network may have changed (Wi-Fi to mobile data): look for a route that works now.
            if (findRoute(gen).isSuccess) {
                if (gen == generation && socket == null) openSocket(gen)
            } else {
                lost(webSocket, gen, UiText.Res(R.string.error_no_route))
            }
        }
    }

    private fun handleEvent(raw: String) {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val channel = Channel.from(event.optString("channel"))
        when (event.optString("type")) {
            "log" -> {
                if (channel == null) return
                listener.onLog(
                    LogEntry(
                        id = ids.getAndIncrement(),
                        channel = channel,
                        level = event.optString("level", "system"),
                        message = event.optString("message", ""),
                        timestamp = clock(event.optString("timestamp")),
                        streamId = event.optString("streamId").takeIf { it.isNotEmpty() }
                    )
                )
            }
            "status" -> event.optJSONObject("data")?.let { listener.onStatus(parseStatus(it)) }
            "clear" -> channel?.let { listener.onClear(it) }
            "permission" -> event.optJSONObject("data")?.let { listener.onPermission(parsePermission(it)) }
            "permission_resolved" -> listener.onPermission(null)
        }
    }

    suspend fun fetchStatus(target: ConnectionProfile? = profile): Result<BridgeStatus> = call(target) { p ->
        get(p, "/api/status") { parseStatus(it) }
    }

    suspend fun fetchIdeConversations(): Result<List<IdeConversation>> = call { p ->
        get(p, "/api/ide/conversations") { body ->
            body.optJSONArray("conversations").objects().map {
                IdeConversation(it.getString("id"), it.optString("title"), it.optString("status"), it.optString("updatedAt"))
            }
        }
    }

    suspend fun openIdeConversation(id: String): Result<String> = call { p ->
        post(p, "/api/ide/conversations/open", JSONObject().put("id", id)) { it.optString("title") }
    }

    suspend fun newIdeConversation(prompt: String): Result<String> = call { p ->
        post(p, "/api/ide/conversations/new", JSONObject().put("prompt", prompt)) { it.optString("title") }
    }

    suspend fun closeIdeConversation(): Result<Unit> = call { p ->
        post(p, "/api/ide/conversations/close", JSONObject()) { }
    }

    suspend fun sendPrompt(prompt: String): Result<String> = call { p ->
        post(p, "/api/prompt", JSONObject().put("prompt", prompt)) { it.optString("mode") }
    }

    suspend fun runTerminal(command: String): Result<Unit> = call { p ->
        post(p, "/api/terminal", JSONObject().put("command", command)) { }
    }

    suspend fun sendTerminalInput(text: String): Result<Unit> = call { p ->
        post(p, "/api/terminal/input", JSONObject().put("text", text)) { }
    }

    suspend fun sendAction(action: String, target: Channel = Channel.AGENT): Result<String> = call { p ->
        post(p, "/api/action", JSONObject().put("action", action).put("target", target.wire)) { it.optString("detail") }
    }

    suspend fun openInIde(path: String? = null): Result<Unit> = call { p ->
        post(p, "/api/ide/open", JSONObject().apply { if (path != null) put("path", path) }) { }
    }

    suspend fun fetchWorkspaces(): Result<List<WorkspaceItem>> = call { p ->
        get(p, "/api/workspaces") { body ->
            body.optJSONArray("workspaces").objects().map {
                WorkspaceItem(it.getString("path"), it.getString("name"), it.optBoolean("active"))
            }
        }
    }

    suspend fun switchWorkspace(path: String): Result<Unit> = call { p ->
        post(p, "/api/workspaces/switch", JSONObject().put("path", path)) { }
    }

    suspend fun fetchFiles(relativePath: String): Result<List<FileEntry>> = call { p ->
        get(p, "/api/files?path=${URLEncoder.encode(relativePath, "UTF-8")}") { body ->
            body.optJSONArray("entries").objects().map {
                FileEntry(it.getString("name"), it.getString("path"), it.getBoolean("isDirectory"), it.optLong("size"))
            }
        }
    }

    suspend fun readFile(relativePath: String): Result<FileContent> = call { p ->
        get(p, "/api/files/read?path=${URLEncoder.encode(relativePath, "UTF-8")}") {
            FileContent(it.getString("path"), it.getString("content"), it.optBoolean("truncated"), it.optLong("size"))
        }
    }

    private suspend fun <T> call(target: ConnectionProfile? = profile, block: (ConnectionProfile) -> T): Result<T> {
        val p = target ?: return Result.failure(NotConnectedException())
        return withContext(Dispatchers.IO) { runCatching { block(p) } }
    }

    private fun <T> get(p: ConnectionProfile, path: String, parse: (JSONObject) -> T): T =
        execute(http, request(p, activeBase ?: p.routes.first(), path).get().build(), parse)

    private fun <T> post(p: ConnectionProfile, path: String, body: JSONObject, parse: (JSONObject) -> T): T =
        execute(http, request(p, activeBase ?: p.routes.first(), path).post(body.toString().toRequestBody(json)).build(), parse)

    private fun request(p: ConnectionProfile, base: String, path: String): Request.Builder =
        Request.Builder().url(base + path)
            .header("Authorization", "Bearer ${p.token}")
            .header("X-App-Language", language)

    private fun <T> execute(client: OkHttpClient, request: Request, parse: (JSONObject) -> T): T {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val body = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) {
                throw BridgeHttpException(response.code, body.optString("error").ifEmpty { "HTTP ${response.code}" })
            }
            return parse(body)
        }
    }

    private fun parseStatus(data: JSONObject): BridgeStatus {
        val agent = data.optJSONObject("agent") ?: JSONObject()
        val terminal = data.optJSONObject("terminal") ?: JSONObject()
        val ide = data.optJSONObject("ide") ?: JSONObject()
        agent.optJSONObject("permission").let { listener.onPermission(it?.let(::parsePermission)) }
        return BridgeStatus(
            version = data.optString("version"),
            hostname = data.optString("hostname"),
            activeWorkspace = data.optString("activeWorkspace"),
            workspaceName = data.optString("workspaceName"),
            agent = AgentInfo(
                mode = agent.optString("mode", "cli"),
                conversationId = agent.optNullableString("conversationId"),
                available = agent.optBoolean("available"),
                state = AgentState.from(agent.optString("state")),
                title = agent.optNullableString("title"),
                queued = agent.optInt("queued"),
                lastError = agent.optNullableString("lastError")
            ),
            terminalRunning = terminal.optBoolean("running"),
            terminalCommand = terminal.optNullableString("command"),
            terminalCwd = terminal.optString("cwd"),
            ideInstalled = ide.optBoolean("installed"),
            ideRunning = ide.optBoolean("running"),
            ideConversations = ide.optBoolean("conversations")
        )
    }

    private fun parsePermission(data: JSONObject) = PermissionRequest(
        title = data.optString("title", "Agent action"),
        kind = data.optString("kind", "other"),
        detail = data.optString("detail")
    )

    /** A message for the user explaining why talking to the PC failed. */
    fun describe(err: Throwable): UiText = when {
        err is BridgeHttpException && err.code == 401 -> UiText.Res(R.string.error_token)
        err is BridgeHttpException && err.code == 429 -> UiText.Res(R.string.error_locked_out)
        err is BridgeHttpException -> UiText.Raw(err.message ?: "HTTP ${err.code}")
        err is NotConnectedException -> UiText.Res(R.string.error_not_connected)
        err is java.net.ConnectException || err is java.net.NoRouteToHostException -> UiText.Res(R.string.error_unreachable)
        err is java.net.SocketTimeoutException -> UiText.Res(R.string.error_timeout)
        err is java.net.UnknownHostException -> UiText.Res(R.string.error_unknown_host)
        err.message.isNullOrBlank() || err.message == "No route" -> UiText.Res(R.string.error_unreachable)
        else -> UiText.Raw(err.message!!)
    }

    private fun clock(iso: String): String {
        val t = iso.indexOf('T')
        return if (t >= 0 && iso.length >= t + 9) iso.substring(t + 1, t + 9) else ""
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private class NotConnectedException : IOException("Not connected")
