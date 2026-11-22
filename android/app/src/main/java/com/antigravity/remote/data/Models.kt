package com.antigravity.remote.data

import java.net.URLEncoder

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

enum class Channel(val wire: String) {
    AGENT("agent"),
    TERMINAL("terminal");

    companion object {
        fun from(value: String?): Channel? = entries.firstOrNull { it.wire == value }
    }
}

data class ConnectionProfile(
    val host: String,
    val port: Int,
    val token: String
) {
    private val hostPart: String get() = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    val baseUrl: String get() = "http://$hostPart:$port"
    val wsUrl: String get() = "ws://$hostPart:$port/ws?token=${URLEncoder.encode(token, "UTF-8")}"
    val label: String get() = "$host:$port"
}

data class LogEntry(
    val id: Long,
    val channel: Channel,
    val level: String,
    val message: String,
    val timestamp: String,
    val streamId: String?
)

data class PermissionRequest(
    val title: String,
    val kind: String,
    val detail: String
)

enum class AgentState(val wire: String, val label: String) {
    OFFLINE("offline", "Offline"),
    STARTING("starting", "Starting"),
    IDLE("idle", "Idle"),
    WORKING("working", "Working"),
    AWAITING_PERMISSION("awaiting_permission", "Needs approval"),
    ERROR("error", "Error");

    companion object {
        fun from(value: String?): AgentState = entries.firstOrNull { it.wire == value } ?: OFFLINE
    }
}

data class AgentInfo(
    val available: Boolean = false,
    val state: AgentState = AgentState.OFFLINE,
    val title: String? = null,
    val queued: Int = 0,
    val lastError: String? = null
)

data class BridgeStatus(
    val version: String = "",
    val hostname: String = "",
    val activeWorkspace: String = "",
    val workspaceName: String = "",
    val agent: AgentInfo = AgentInfo(),
    val terminalRunning: Boolean = false,
    val terminalCommand: String? = null,
    val terminalCwd: String = "",
    val ideInstalled: Boolean = false,
    val ideRunning: Boolean = false
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
