package com.antigravity.remote.data

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

data class ConnectionProfile(
    val host: String,
    val port: Int,
    val token: String
) {
    val baseUrl: String get() = "http://$host:$port"
    val wsUrl: String get() = "ws://$host:$port/ws?token=$token"
}

data class LogEntry(
    val id: Long,
    val level: String,
    val message: String,
    val timestamp: String
)

data class WorkspaceItem(
    val path: String,
    val name: String,
    val active: Boolean
)

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long
)

data class FileContent(
    val path: String,
    val content: String,
    val truncated: Boolean,
    val size: Long
)

data class BridgeStatus(
    val hostname: String = "",
    val activeWorkspace: String = "",
    val workspaceName: String = "",
    val running: Boolean = false,
    val activeCommand: String? = null,
    val antigravityRunning: Boolean = false
)
