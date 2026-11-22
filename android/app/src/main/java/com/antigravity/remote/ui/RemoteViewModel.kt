package com.antigravity.remote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.remote.data.BridgeClient
import com.antigravity.remote.data.BridgeStatus
import com.antigravity.remote.data.ConnectionPreferences
import com.antigravity.remote.data.ConnectionProfile
import com.antigravity.remote.data.ConnectionStatus
import com.antigravity.remote.data.FileContent
import com.antigravity.remote.data.FileEntry
import com.antigravity.remote.data.LogEntry
import com.antigravity.remote.data.WorkspaceItem
import kotlinx.coroutines.Dispatchers
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
    val logs: List<LogEntry> = emptyList(),
    val workspaces: List<WorkspaceItem> = emptyList(),
    val currentFilePath: String = "",
    val fileEntries: List<FileEntry> = emptyList(),
    val filesLoading: Boolean = false,
    val openedFile: FileContent? = null,
    val toastMessage: String? = null
)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = ConnectionPreferences(application)
    private val _uiState = MutableStateFlow(RemoteUiState(savedProfile = prefs.getSavedProfile()))
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    private val client = BridgeClient(
        scope = viewModelScope,
        onConnectionStateChanged = { status, error ->
            _uiState.update {
                it.copy(
                    connectionStatus = status,
                    connectionError = error
                )
            }
            if (status == ConnectionStatus.CONNECTED) {
                loadWorkspaces()
                loadFiles("")
            }
        },
        onStatusUpdated = { newStatus ->
            _uiState.update { state ->
                val merged = state.bridgeStatus.copy(
                    hostname = newStatus.hostname.ifEmpty { state.bridgeStatus.hostname },
                    activeWorkspace = newStatus.activeWorkspace.ifEmpty { state.bridgeStatus.activeWorkspace },
                    workspaceName = newStatus.workspaceName.ifEmpty { state.bridgeStatus.workspaceName },
                    running = newStatus.running,
                    activeCommand = newStatus.activeCommand,
                    antigravityRunning = newStatus.antigravityRunning
                )
                state.copy(bridgeStatus = merged)
            }
        },
        onLogReceived = { entry ->
            _uiState.update { state ->
                val updated = (state.logs + entry).takeLast(600)
                state.copy(logs = updated)
            }
        },
        onLogsCleared = {
            _uiState.update { it.copy(logs = emptyList()) }
        }
    )

    init {
        // Auto-reconnect to saved profile on launch if available
        prefs.getSavedProfile()?.let { saved ->
            connect(saved)
        }
    }

    fun connect(profile: ConnectionProfile) {
        prefs.saveProfile(profile)
        _uiState.update {
            it.copy(
                savedProfile = profile,
                activeProfile = profile,
                connectionError = null
            )
        }
        client.connect(profile)
    }

    fun connectFromQrPayload(rawQr: String): Boolean {
        return try {
            val json = JSONObject(rawQr)
            val ip = json.getString("ip")
            val port = json.optInt("port", 8765)
            val token = json.getString("token")
            connect(ConnectionProfile(host = ip, port = port, token = token))
            true
        } catch (_: Exception) {
            _uiState.update { it.copy(connectionError = "Invalid QR code format") }
            false
        }
    }

    fun disconnect() {
        client.disconnect()
        _uiState.update {
            it.copy(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                activeProfile = null
            )
        }
    }

    fun clearSavedProfile() {
        prefs.clearProfile()
        _uiState.update { it.copy(savedProfile = null) }
    }

    fun sendPrompt(prompt: String) {
        if (prompt.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            client.sendPrompt(prompt).onFailure { err ->
                showToast(err.message ?: "Failed to send prompt")
            }
        }
    }

    fun runTerminalCommand(command: String) {
        if (command.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            client.runTerminalCommand(command).onFailure { err ->
                showToast(err.message ?: "Command failed")
            }
        }
    }

    fun sendAction(action: String) {
        viewModelScope.launch(Dispatchers.IO) {
            client.sendAction(action).onFailure { err ->
                showToast(err.message ?: "Action failed")
            }
        }
    }

    fun loadWorkspaces() {
        viewModelScope.launch(Dispatchers.IO) {
            client.fetchWorkspaces().onSuccess { list ->
                _uiState.update { it.copy(workspaces = list) }
            }
        }
    }

    fun switchWorkspace(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            client.switchWorkspace(path)
                .onSuccess {
                    loadWorkspaces()
                    loadFiles("")
                    showToast("Workspace switched")
                }
                .onFailure { err ->
                    showToast(err.message ?: "Failed to switch workspace")
                }
        }
    }

    fun loadFiles(relativePath: String) {
        _uiState.update { it.copy(filesLoading = true, currentFilePath = relativePath) }
        viewModelScope.launch(Dispatchers.IO) {
            client.fetchFiles(relativePath)
                .onSuccess { entries ->
                    _uiState.update { it.copy(fileEntries = entries, filesLoading = false) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(filesLoading = false) }
                    showToast(err.message ?: "Failed to list files")
                }
        }
    }

    fun navigateUpDirectory() {
        val current = _uiState.value.currentFilePath
        if (current.isEmpty()) return
        val parent = current.substringBeforeLast("/", "")
        loadFiles(parent)
    }

    fun openFile(relativePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            client.readFile(relativePath)
                .onSuccess { content ->
                    _uiState.update { it.copy(openedFile = content) }
                }
                .onFailure { err ->
                    showToast(err.message ?: "Failed to read file")
                }
        }
    }

    fun closeOpenedFile() {
        _uiState.update { it.copy(openedFile = null) }
    }

    fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    fun consumeToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        client.disconnect()
    }
}
