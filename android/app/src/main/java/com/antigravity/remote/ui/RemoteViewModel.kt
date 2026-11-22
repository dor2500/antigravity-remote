package com.antigravity.remote.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.remote.data.BridgeClient
import com.antigravity.remote.data.BridgeStatus
import com.antigravity.remote.data.Channel
import com.antigravity.remote.data.ConnectionPreferences
import com.antigravity.remote.data.ConnectionProfile
import com.antigravity.remote.data.ConnectionStatus
import com.antigravity.remote.data.FileContent
import com.antigravity.remote.data.FileEntry
import com.antigravity.remote.data.IdeConversation
import com.antigravity.remote.data.LogEntry
import com.antigravity.remote.data.PermissionRequest
import com.antigravity.remote.data.WorkspaceItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class RemoteUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectionError: String? = null,
    val savedProfile: ConnectionProfile? = null,
    val activeProfile: ConnectionProfile? = null,
    val bridgeStatus: BridgeStatus = BridgeStatus(),
    val agentLogs: List<LogEntry> = emptyList(),
    val terminalLogs: List<LogEntry> = emptyList(),
    val permission: PermissionRequest? = null,
    val workspaces: List<WorkspaceItem> = emptyList(),
    val currentFilePath: String = "",
    val fileEntries: List<FileEntry> = emptyList(),
    val filesLoading: Boolean = false,
    val openedFile: FileContent? = null,
    val toastMessage: String? = null,
    val activeRoute: String? = null,
    val ideConversations: List<IdeConversation> = emptyList(),
    val ideConversationsLoading: Boolean = false
) {
    val isOnline: Boolean
        get() = connectionStatus == ConnectionStatus.CONNECTED || connectionStatus == ConnectionStatus.RECONNECTING
}

private const val MAX_LOG_ENTRIES = 800

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = ConnectionPreferences(application)
    private val _uiState = MutableStateFlow(RemoteUiState(savedProfile = prefs.getSavedProfile()))
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    private val client = BridgeClient(viewModelScope, object : BridgeClient.Listener {
        override fun onConnectionState(status: ConnectionStatus, error: String?) {
            val wasConnected = _uiState.value.connectionStatus == ConnectionStatus.CONNECTED
            _uiState.update { it.copy(connectionStatus = status, connectionError = error) }
            if (status == ConnectionStatus.CONNECTED && !wasConnected) {
                loadWorkspaces()
                loadFiles(_uiState.value.currentFilePath)
            }
        }

        override fun onStatus(status: BridgeStatus) {
            val previous = _uiState.value.bridgeStatus.activeWorkspace
            _uiState.update { it.copy(bridgeStatus = status) }
            if (previous.isNotEmpty() && previous != status.activeWorkspace) {
                loadWorkspaces()
                loadFiles("")
            }
        }

        override fun onLog(entry: LogEntry) {
            _uiState.update { state ->
                when (entry.channel) {
                    Channel.AGENT -> state.copy(agentLogs = append(state.agentLogs, entry))
                    Channel.TERMINAL -> state.copy(terminalLogs = append(state.terminalLogs, entry))
                }
            }
        }

        override fun onClear(channel: Channel) {
            _uiState.update {
                when (channel) {
                    Channel.AGENT -> it.copy(agentLogs = emptyList())
                    Channel.TERMINAL -> it.copy(terminalLogs = emptyList())
                }
            }
        }

        override fun onRoute(baseUrl: String?) {
            _uiState.update { it.copy(activeRoute = baseUrl) }
        }

        override fun onPermission(request: PermissionRequest?) {
            if (_uiState.value.permission != request) _uiState.update { it.copy(permission = request) }
        }
    })

    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            client.reconnectNow()
        }
    }

    init {
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        prefs.getSavedProfile()?.let { connect(it) }
    }

    fun connect(profile: ConnectionProfile) {
        prefs.saveProfile(profile)
        _uiState.update { it.copy(savedProfile = profile, activeProfile = profile, connectionError = null) }
        client.connect(profile)
    }

    /**
     * Accepts the bridge QR payload: {"ip", "port", "token"} plus optional "hosts" (every
     * address of the PC, such as its Tailscale IP) and "url" (a public https address).
     */
    fun connectFromQrPayload(raw: String): Boolean {
        val profile = runCatching {
            val json = JSONObject(raw.trim())
            val ip = json.getString("ip").trim()
            val hosts = json.optJSONArray("hosts")
            val extra = buildList {
                if (hosts != null) for (i in 0 until hosts.length()) add(hosts.optString(i).trim())
                json.optString("url").trim().takeIf { it.startsWith("http") }?.let { add(it) }
            }.filter { it.isNotEmpty() && it != ip }.distinct()
            ConnectionProfile(
                host = ip,
                port = json.optInt("port", 8765),
                token = json.getString("token").trim(),
                extraRoutes = extra
            )
        }.getOrNull()
        if (profile == null || profile.host.isEmpty() || profile.token.isEmpty()) {
            _uiState.update { it.copy(connectionError = "That QR code is not an Antigravity Remote pairing code") }
            return false
        }
        connect(profile)
        return true
    }

    fun disconnect() {
        client.disconnect()
        _uiState.update { it.copy(activeProfile = null, permission = null) }
    }

    fun clearSavedProfile() {
        prefs.clearProfile()
        _uiState.update { it.copy(savedProfile = null) }
    }

    fun onAppForeground() {
        client.reconnectNow()
    }

    fun sendPrompt(prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            client.sendPrompt(prompt).onFailure { toast(it.message ?: "Could not send the prompt") }
        }
    }

    fun runTerminalCommand(command: String) {
        if (command.isBlank()) return
        viewModelScope.launch {
            client.runTerminal(command).onFailure { toast(it.message ?: "Command failed") }
        }
    }

    fun sendTerminalInput(text: String) {
        viewModelScope.launch {
            client.sendTerminalInput(text).onFailure { toast(it.message ?: "Could not send input") }
        }
    }

    fun sendAction(action: String, target: Channel = Channel.AGENT) {
        viewModelScope.launch {
            client.sendAction(action, target).onFailure { toast(it.message ?: "Action failed") }
        }
    }

    fun openInIde(path: String? = null) {
        viewModelScope.launch {
            client.openInIde(path)
                .onSuccess { toast("Opened in Antigravity IDE") }
                .onFailure { toast(it.message ?: "Could not open the IDE") }
        }
    }

    fun loadIdeConversations() {
        _uiState.update { it.copy(ideConversationsLoading = true) }
        viewModelScope.launch {
            client.fetchIdeConversations()
                .onSuccess { list -> _uiState.update { it.copy(ideConversations = list, ideConversationsLoading = false) } }
                .onFailure { err ->
                    _uiState.update { it.copy(ideConversationsLoading = false) }
                    toast(err.message ?: "Could not read IDE conversations")
                }
        }
    }

    fun openIdeConversation(id: String) {
        viewModelScope.launch {
            client.openIdeConversation(id).onFailure { toast(it.message ?: "Could not open the conversation") }
        }
    }

    fun newIdeConversation(prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch {
            client.newIdeConversation(prompt.trim()).onFailure { toast(it.message ?: "Could not start the conversation") }
        }
    }

    fun closeIdeConversation() {
        viewModelScope.launch {
            client.closeIdeConversation().onFailure { toast(it.message ?: "Could not leave the conversation") }
        }
    }

    fun loadWorkspaces() {
        viewModelScope.launch {
            client.fetchWorkspaces().onSuccess { list -> _uiState.update { it.copy(workspaces = list) } }
        }
    }

    fun switchWorkspace(path: String) {
        viewModelScope.launch {
            client.switchWorkspace(path)
                .onSuccess {
                    loadWorkspaces()
                    loadFiles("")
                }
                .onFailure { toast(it.message ?: "Could not switch workspace") }
        }
    }

    fun loadFiles(relativePath: String) {
        _uiState.update { it.copy(filesLoading = true, currentFilePath = relativePath) }
        viewModelScope.launch {
            client.fetchFiles(relativePath)
                .onSuccess { entries -> _uiState.update { it.copy(fileEntries = entries, filesLoading = false) } }
                .onFailure { err ->
                    _uiState.update { it.copy(filesLoading = false) }
                    toast(err.message ?: "Could not list files")
                }
        }
    }

    fun navigateUpDirectory() {
        val current = _uiState.value.currentFilePath
        if (current.isNotEmpty()) loadFiles(current.substringBeforeLast('/', ""))
    }

    fun openFile(relativePath: String) {
        viewModelScope.launch {
            client.readFile(relativePath)
                .onSuccess { content -> _uiState.update { it.copy(openedFile = content) } }
                .onFailure { toast(it.message ?: "Could not read the file") }
        }
    }

    fun closeOpenedFile() {
        _uiState.update { it.copy(openedFile = null) }
    }

    fun consumeToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun toast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        client.disconnect()
        super.onCleared()
    }
}

/** Appends a log entry, merging fragments that belong to the same streamed message. */
private fun append(list: List<LogEntry>, entry: LogEntry): List<LogEntry> {
    val last = list.lastOrNull()
    if (entry.streamId != null && last != null && last.streamId == entry.streamId) {
        return list.dropLast(1) + last.copy(message = last.message + entry.message)
    }
    val next = list + entry
    return if (next.size > MAX_LOG_ENTRIES) next.takeLast(MAX_LOG_ENTRIES) else next
}
