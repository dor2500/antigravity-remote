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
    val isHttps: Boolean
        get() = host.trim().startsWith("https://", ignoreCase = true) ||
            (!host.trim().startsWith("http://", ignoreCase = true) && port == 443)

    private val normalizedBase: String
        get() {
            val trimmed = host.trim().trimEnd('/')
            if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
                return trimmed
            }
            val hostPart = if (trimmed.contains(':') && !trimmed.startsWith("[")) "[$trimmed]" else trimmed
            return if (port == 443) "https://$hostPart" else "http://$hostPart:$port"
        }

    val baseUrl: String get() = normalizedBase

    val wsUrl: String
        get() {
            val wsBase = when {
                normalizedBase.startsWith("https://", ignoreCase = true) -> "wss://" + normalizedBase.substring(8)
                normalizedBase.startsWith("http://", ignoreCase = true) -> "ws://" + normalizedBase.substring(7)
                else -> "ws://$normalizedBase"
            }
            return "$wsBase/ws?token=${URLEncoder.encode(token, "UTF-8")}"
        }

    val label: String
        get() {
            val trimmed = host.trim().trimEnd('/')
            return when {
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
                trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
                port == 443 -> trimmed
                else -> "$trimmed:$port"
            }
        }
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
