package com.antigravity.remote.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.remote.data.AgentState
import com.antigravity.remote.data.Channel
import com.antigravity.remote.data.ConnectionProfile
import com.antigravity.remote.data.ConnectionStatus
import com.antigravity.remote.data.FileContent
import com.antigravity.remote.data.LogEntry
import com.antigravity.remote.data.WorkspaceItem
import com.antigravity.remote.ui.RemoteUiState
import com.antigravity.remote.ui.components.StatusDot
import com.antigravity.remote.ui.theme.AccentBlue
import com.antigravity.remote.ui.theme.BgPrimary
import com.antigravity.remote.ui.theme.BgSecondary
import com.antigravity.remote.ui.theme.BgTertiary
import com.antigravity.remote.ui.theme.StatusAmber
import com.antigravity.remote.ui.theme.StatusGreen
import com.antigravity.remote.ui.theme.StatusRed
import com.antigravity.remote.ui.theme.TextPrimary
import com.antigravity.remote.ui.theme.TextSecondary

/** Everything the remote screen can ask the view model to do. */
data class RemoteActions(
    val sendPrompt: (String) -> Unit,
    val runTerminal: (String) -> Unit,
    val sendTerminalInput: (String) -> Unit,
    val sendAction: (String, Channel) -> Unit,
    val openInIde: (String?) -> Unit,
    val switchWorkspace: (String) -> Unit,
    val refreshWorkspaces: () -> Unit,
    val openDirectory: (String) -> Unit,
    val navigateUp: () -> Unit,
    val openFile: (String) -> Unit,
    val closeFile: () -> Unit,
    val disconnect: () -> Unit,
    val consumeToast: () -> Unit,
    val loadIdeConversations: () -> Unit,
    val openIdeConversation: (String) -> Unit,
    val newIdeConversation: (String) -> Unit,
    val closeIdeConversation: () -> Unit
)

private enum class Tab(val label: String) { AGENT("Agent"), TERMINAL("Terminal"), FILES("Files") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen(uiState: RemoteUiState, actions: RemoteActions) {
    var tab by rememberSaveable { mutableStateOf(Tab.AGENT) }
    var showWorkspaces by remember { mutableStateOf(false) }
    var showIdeConversations by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var pendingToast by remember { mutableStateOf<String?>(null) }

    val copy: (String, String) -> Unit = { text, label ->
        clipboard.setText(AnnotatedString(text))
        snackbar.currentSnackbarData?.dismiss()
        pendingToast = "$label copied"
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let {
            snackbar.showSnackbar(it)
            actions.consumeToast()
        }
    }
    LaunchedEffect(pendingToast) {
        val message = pendingToast ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        pendingToast = null
    }

    BackHandler(enabled = tab == Tab.FILES && uiState.currentFilePath.isNotEmpty()) { actions.navigateUp() }
    BackHandler(enabled = tab != Tab.AGENT && !(tab == Tab.FILES && uiState.currentFilePath.isNotEmpty())) {
        tab = Tab.AGENT
    }

    if (showIdeConversations) {
        IdeConversationSheet(
            uiState = uiState,
            onDismiss = { showIdeConversations = false },
            onRefresh = actions.loadIdeConversations,
            onOpen = {
                actions.openIdeConversation(it)
                showIdeConversations = false
            },
            onNew = {
                actions.newIdeConversation(it)
                showIdeConversations = false
            }
        )
    }

    if (showWorkspaces) {
        WorkspaceSheet(
            uiState = uiState,
            onDismiss = { showWorkspaces = false },
            onSelect = {
                actions.switchWorkspace(it)
                showWorkspaces = false
            },
            onOpenInIde = { actions.openInIde(it) }
        )
    }

    uiState.openedFile?.let { file ->
        FileViewerDialog(file = file, onCopy = { copy(file.content, "File") }, onDismiss = actions.closeFile)
    }

    Scaffold(
        containerColor = BgPrimary,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            StatusBar(
                uiState = uiState,
                onWorkspaceClick = {
                    actions.refreshWorkspaces()
                    showWorkspaces = true
                },
                onDisconnect = actions.disconnect
            )
        },
        bottomBar = {
            NavigationBar(containerColor = BgSecondary, tonalElevation = 0.dp) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    Tab.AGENT -> Icons.Default.SmartToy
                                    Tab.TERMINAL -> Icons.Default.Terminal
                                    Tab.FILES -> Icons.Default.Folder
                                },
                                contentDescription = null
                            )
                        },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = AccentBlue.copy(alpha = 0.18f))
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            when (tab) {
                Tab.AGENT -> AgentTab(
                    uiState = uiState,
                    onSendPrompt = actions.sendPrompt,
                    onAction = { actions.sendAction(it, Channel.AGENT) },
                    onCopy = copy,
                    onShowIdeConversations = {
                        actions.loadIdeConversations()
                        showIdeConversations = true
                    },
                    onLeaveIdeConversation = actions.closeIdeConversation
                )
                Tab.TERMINAL -> TerminalTab(
                    uiState = uiState,
                    onRun = actions.runTerminal,
                    onInput = actions.sendTerminalInput,
                    onStop = { actions.sendAction("stop", Channel.TERMINAL) },
                    onClear = { actions.sendAction("clear", Channel.TERMINAL) },
                    onCopy = copy
                )
                Tab.FILES -> FilesTab(
                    uiState = uiState,
                    onRefresh = { actions.openDirectory(uiState.currentFilePath) },
                    onNavigateUp = actions.navigateUp,
                    onOpenDir = actions.openDirectory,
                    onOpenFile = actions.openFile
                )
            }
        }
    }
}

@Composable
private fun StatusBar(uiState: RemoteUiState, onWorkspaceClick: () -> Unit, onDisconnect: () -> Unit) {
    val (color, label) = when (uiState.connectionStatus) {
        ConnectionStatus.CONNECTED -> StatusGreen to "Connected"
        ConnectionStatus.CONNECTING -> StatusAmber to "Connecting"
        ConnectionStatus.RECONNECTING -> StatusAmber to "Reconnecting"
        ConnectionStatus.DISCONNECTED -> StatusRed to "Disconnected"
    }
    val status = uiState.bridgeStatus
    Surface(color = BgSecondary) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(color)
                    Text(label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    uiState.activeRoute?.let { route ->
                        Text(
                            text = "via ${ConnectionProfile.routeKind(route)}",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    val host = status.hostname.ifEmpty { uiState.activeProfile?.host.orEmpty() }
                    if (host.isNotEmpty()) {
                        Text(
                            text = host,
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp).clickable(onClick = onWorkspaceClick)
                ) {
                    Text(
                        text = status.workspaceName.ifEmpty { "Choose workspace" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(Icons.Default.UnfoldMore, contentDescription = "Switch workspace", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
            AgentBadge(status.agent.state, status.agent.available)
            IconButton(onClick = onDisconnect) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = "Disconnect", tint = TextSecondary)
            }
        }
    }
}

@Composable
private fun AgentBadge(state: AgentState, available: Boolean) {
    val ready = available && state == AgentState.OFFLINE
    val color = when {
        !available -> TextSecondary
        ready -> StatusGreen
        state == AgentState.WORKING || state == AgentState.STARTING -> AccentBlue
        state == AgentState.AWAITING_PERMISSION -> StatusAmber
        state == AgentState.ERROR -> StatusRed
        state == AgentState.IDLE -> StatusGreen
        else -> TextSecondary
    }
    val text = when {
        !available -> "No agent"
        ready -> "Ready"
        else -> state.label
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        StatusDot(color, Modifier.size(6.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceSheet(
    uiState: RemoteUiState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenInIde: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customPath by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = BgSecondary) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Workspaces", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (uiState.bridgeStatus.ideInstalled) {
                    TextButton(onClick = { onOpenInIde(uiState.bridgeStatus.activeWorkspace) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open in IDE")
                    }
                }
            }
            Text(
                text = "The agent and terminal run in the selected folder.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(uiState.workspaces, key = { it.path }) { ws -> WorkspaceRow(ws, onSelect) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customPath,
                    onValueChange = { customPath = it },
                    placeholder = { Text("C:\\path\\to\\project") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
                TextButton(onClick = { if (customPath.isNotBlank()) onSelect(customPath.trim()) }, enabled = customPath.isNotBlank()) {
                    Text("Open")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceRow(ws: WorkspaceItem, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(ws.path) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ws.active) Icons.Default.FolderOpen else Icons.Default.Folder,
            contentDescription = null,
            tint = if (ws.active) AccentBlue else TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(ws.name, fontWeight = FontWeight.SemiBold, color = if (ws.active) AccentBlue else TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ws.path, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ws.active) Icon(Icons.Default.Check, contentDescription = "Active", tint = AccentBlue)
    }
}

@Composable
private fun FileViewerDialog(file: FileContent, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val lines = remember(file) { file.content.split('\n') }
    val gutter = lines.size.toString().length
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().padding(10.dp), shape = RoundedCornerShape(16.dp), color = BgSecondary) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "${file.path}  -  ${lines.size} lines${if (file.truncated) "  -  first 256 KB" else ""}",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onCopy) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy file") }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }
                HorizontalDivider(color = BgTertiary)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(BgPrimary)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        text = numbered(lines, gutter),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = TextPrimary,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            }
        }
    }
}

private fun numbered(lines: List<String>, gutter: Int): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val numberStyle = androidx.compose.ui.text.SpanStyle(color = TextSecondary.copy(alpha = 0.55f))
    lines.forEachIndexed { index, line ->
        builder.pushStyle(numberStyle)
        builder.append((index + 1).toString().padStart(gutter + 2))
        builder.append("  ")
        builder.pop()
        builder.append(line.trimEnd('\r'))
        if (index < lines.lastIndex) builder.append('\n')
    }
    return builder.toAnnotatedString()
}

internal fun List<LogEntry>.copyText(): String = joinToString("\n") { it.message }
