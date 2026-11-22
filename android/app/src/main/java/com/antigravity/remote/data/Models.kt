package com.antigravity.remote.data

import androidx.annotation.StringRes
import com.antigravity.remote.R
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

/**
 * A paired PC. [host] is the main address (an IP, a host name, or a full http(s) URL);
 * [extraRoutes] are other ways to reach the same bridge, such as its Tailscale IP or a public
 * tunnel URL. The app tries the routes in order and uses the first one that answers.
 */
data class ConnectionProfile(
    val host: String,
    val port: Int,
    val token: String,
    val extraRoutes: List<String> = emptyList()
) {
    val routes: List<String> get() = (listOf(host) + extraRoutes).map { baseUrlFor(it, port) }.distinct()
    val label: String get() = if (host.startsWith("http")) host.removePrefix("https://").removePrefix("http://") else "$host:$port"

    /** The same PC with the addresses the relay reported (LAN, Tailscale, tunnel), deduplicated. */
    fun withRelayedRoutes(relayed: AddressRelay.Routes): ConnectionProfile {
        val fresh = relayed.hosts + listOfNotNull(relayed.url)
        val first = fresh.firstOrNull() ?: return this
        return copy(host = first, port = relayed.port, extraRoutes = fresh.drop(1).filter { it != first }.distinct())
    }

    fun wsUrl(baseUrl: String): String =
        baseUrl.replaceFirst(Regex("^http"), "ws") + "/ws?token=" + URLEncoder.encode(token, "UTF-8")

    companion object {
        fun baseUrlFor(route: String, port: Int): String {
            val value = route.trim().trimEnd('/')
            if (value.startsWith("http://") || value.startsWith("https://")) return value
            val host = if (value.count { it == ':' } > 1 && !value.startsWith("[")) "[$value]" else value
            return "http://$host:$port"
        }

        /** Where a route goes: the home network, Tailscale or the internet (a tunnel). */
        @StringRes
        fun routeKind(baseUrl: String): Int {
            val host = baseUrl.substringAfter("://").substringBefore('/').substringBeforeLast(':').trim('[', ']')
            return when {
                baseUrl.startsWith("https://") -> R.string.route_internet
                Regex("^100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\.").containsMatchIn(host) -> R.string.route_tailscale
                host.endsWith(".ts.net") -> R.string.route_tailscale
                else -> R.string.route_lan
            }
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

enum class AgentState(val wire: String, @StringRes val label: Int) {
    OFFLINE("offline", R.string.agent_offline),
    STARTING("starting", R.string.agent_starting),
    IDLE("idle", R.string.agent_idle),
    WORKING("working", R.string.agent_working),
    AWAITING_PERMISSION("awaiting_permission", R.string.agent_needs_approval),
    ERROR("error", R.string.agent_error);

    companion object {
        fun from(value: String?): AgentState = entries.firstOrNull { it.wire == value } ?: OFFLINE
    }
}

data class AgentInfo(
    val mode: String = "cli",
    val conversationId: String? = null,
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
    val ideRunning: Boolean = false,
    val ideConversations: Boolean = false
)

data class IdeConversation(
    val id: String,
    val title: String,
    val status: String,
    val updatedAt: String
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
