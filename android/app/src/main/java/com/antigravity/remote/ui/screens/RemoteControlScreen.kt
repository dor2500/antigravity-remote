package com.antigravity.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.remote.data.ConnectionStatus
import com.antigravity.remote.data.FileContent
import com.antigravity.remote.data.FileEntry
import com.antigravity.remote.data.LogEntry
import com.antigravity.remote.ui.RemoteUiState
import com.antigravity.remote.ui.theme.AccentBlue
import com.antigravity.remote.ui.theme.AccentPurple
import com.antigravity.remote.ui.theme.BgPrimary
import com.antigravity.remote.ui.theme.BgSecondary
import com.antigravity.remote.ui.theme.BgTertiary
import com.antigravity.remote.ui.theme.StatusAmber
import com.antigravity.remote.ui.theme.StatusGreen
import com.antigravity.remote.ui.theme.StatusRed
import com.antigravity.remote.ui.theme.TextPrimary
import com.antigravity.remote.ui.theme.TextSecondary

@Composable
fun RemoteControlScreen(
    uiState: RemoteUiState,
    onSendPrompt: (String) -> Unit,
    onRunTerminal: (String) -> Unit,
    onSendAction: (String) -> Unit,
    onSwitchWorkspace: (String) -> Unit,
    onRefreshWorkspaces: () -> Unit,
    onOpenDirectory: (String) -> Unit,
    onNavigateUpDir: () -> Unit,
    onOpenFile: (String) -> Unit,
    onCloseFile: () -> Unit,
    onDisconnect: () -> Unit,
    onConsumeToast: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showWorkspaceDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(uiState.toastMessage) {
        val msg = uiState.toastMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            onConsumeToast()
        }
    }

    if (showWorkspaceDialog) {
        AlertDialog(
            onDismissRequest = { showWorkspaceDialog = false },
            containerColor = BgSecondary,
            title = { Text("Switch Workspace") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(uiState.workspaces) { ws ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSwitchWorkspace(ws.path)
                                    showWorkspaceDialog = false
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ws.name,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (ws.active) AccentBlue else TextPrimary
                                )
                                Text(
                                    text = ws.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (ws.active) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = AccentBlue)
                            }
                        }
                        HorizontalDivider(color = BgTertiary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWorkspaceDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    uiState.openedFile?.let { file ->
        FileViewerDialog(
            file = file,
            onCopy = { clipboard.setText(AnnotatedString(file.content)) },
            onDismiss = onCloseFile
        )
    }

    Scaffold(
        containerColor = BgPrimary,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            StatusTopBar(
                uiState = uiState,
                onSwitchWorkspaceClick = {
                    onRefreshWorkspaces()
                    showWorkspaceDialog = true
                },
                onDisconnect = onDisconnect
            )
        },
        bottomBar = {
            NavigationBar(containerColor = BgSecondary) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = "Control") },
                    label = { Text("Control") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Terminal") },
                    label = { Text("Terminal") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Folder, contentDescription = "Files") },
                    label = { Text("Files") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            when (selectedTab) {
                0 -> ControlTab(
                    logs = uiState.logs,
                    onSendPrompt = onSendPrompt,
                    onSendAction = onSendAction,
                    onCopyAll = {
                        val all = uiState.logs.joinToString("\n") { it.message }
                        clipboard.setText(AnnotatedString(all))
                    }
                )
                1 -> TerminalTab(
                    workspaceName = uiState.bridgeStatus.workspaceName,
                    logs = uiState.logs,
                    onRunCommand = onRunTerminal,
                    onStop = { onSendAction("stop") }
                )
                2 -> FilesTab(
                    currentPath = uiState.currentFilePath,
                    entries = uiState.fileEntries,
                    loading = uiState.filesLoading,
                    onRefresh = { onOpenDirectory(uiState.currentFilePath) },
                    onNavigateUp = onNavigateUpDir,
                    onOpenDir = onOpenDirectory,
                    onOpenFile = onOpenFile
                )
            }
        }
    }
}

@Composable
private fun StatusTopBar(
    uiState: RemoteUiState,
    onSwitchWorkspaceClick: () -> Unit,
    onDisconnect: () -> Unit
) {
    val statusColor = when (uiState.connectionStatus) {
        ConnectionStatus.CONNECTED -> StatusGreen
        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> StatusAmber
        ConnectionStatus.DISCONNECTED -> StatusRed
    }
    val statusLabel = when (uiState.connectionStatus) {
        ConnectionStatus.CONNECTED -> "Connected"
        ConnectionStatus.CONNECTING -> "Connecting"
        ConnectionStatus.RECONNECTING -> "Reconnecting"
        ConnectionStatus.DISCONNECTED -> "Disconnected"
    }

    Surface(color = BgSecondary, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (uiState.bridgeStatus.hostname.isNotEmpty()) {
                        Text(
                            text = "@ ${uiState.bridgeStatus.hostname}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(onClick = onSwitchWorkspaceClick)
                        .padding(top = 2.dp)
                ) {
                    Text(
                        text = uiState.bridgeStatus.workspaceName.ifEmpty { "Select workspace" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Switch workspace",
                        tint = AccentBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            IconButton(onClick = onDisconnect) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Disconnect",
                    tint = StatusRed
                )
            }
        }
    }
}

@Composable
private fun ControlTab(
    logs: List<LogEntry>,
    onSendPrompt: (String) -> Unit,
    onSendAction: (String) -> Unit,
    onCopyAll: () -> Unit
) {
    var promptText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Output header with Copy button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LIVE OUTPUT STREAM",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            IconButton(onClick = onCopyAll, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy output",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Auto-scrolling monospace log viewer
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(BgPrimary)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (logs.isEmpty()) {
                item {
                    Text(
                        text = "Waiting for output... Send a prompt or run a command below.",
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            } else {
                items(logs, key = { it.id }) { entry ->
                    LogLineItem(entry)
                }
            }
        }

        // Quick Action Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSecondary)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { onSendAction("approve") },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = StatusGreen.copy(alpha = 0.18f),
                    contentColor = StatusGreen
                )
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Approve", fontSize = 12.sp, maxLines = 1)
            }
            FilledTonalButton(
                onClick = { onSendAction("reject") },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = StatusRed.copy(alpha = 0.18f),
                    contentColor = StatusRed
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reject", fontSize = 12.sp, maxLines = 1)
            }
            FilledTonalButton(
                onClick = { onSendAction("stop") },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = StatusAmber.copy(alpha = 0.18f),
                    contentColor = StatusAmber
                )
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Stop", fontSize = 12.sp, maxLines = 1)
            }
            FilledTonalButton(
                onClick = { onSendAction("clear") },
                modifier = Modifier.weight(0.9f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = BgTertiary,
                    contentColor = TextSecondary
                )
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear", fontSize = 12.sp, maxLines = 1)
            }
        }

        // Multi-line Prompt Input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSecondary)
                .padding(start = 12.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                placeholder = { Text("Send prompt to Antigravity (or !cmd)...") },
                maxLines = 4,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = {
                    if (promptText.isNotBlank()) {
                        onSendPrompt(promptText)
                        promptText = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(AccentBlue)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send prompt",
                    tint = BgPrimary
                )
            }
        }
    }
}

@Composable
private fun TerminalTab(
    workspaceName: String,
    logs: List<LogEntry>,
    onRunCommand: (String) -> Unit,
    onStop: () -> Unit
) {
    var cmdInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val quickCommands = listOf("git status", "ls", "pwd", "npm test")

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(BgPrimary)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(logs, key = { it.id }) { entry ->
                LogLineItem(entry)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(BgSecondary)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickCommands.forEach { qc ->
                AssistChip(
                    onClick = { onRunCommand(qc) },
                    label = { Text(qc, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSecondary)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = cmdInput,
                onValueChange = { cmdInput = it },
                placeholder = { Text("${workspaceName.ifEmpty { "ps" }}> command") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = "Stop", tint = StatusRed)
            }
            IconButton(
                onClick = {
                    if (cmdInput.isNotBlank()) {
                        onRunCommand(cmdInput)
                        cmdInput = ""
                    }
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(AccentBlue)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run", tint = BgPrimary)
            }
        }
    }
}

@Composable
private fun FilesTab(
    currentPath: String,
    entries: List<FileEntry>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onNavigateUp: () -> Unit,
    onOpenDir: (String) -> Unit,
    onOpenFile: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSecondary)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (currentPath.isNotEmpty()) {
                    IconButton(onClick = onNavigateUp, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = if (currentPath.isEmpty()) "/" else "/$currentPath",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.isDirectory) onOpenDir(entry.path)
                                else onOpenFile(entry.path)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            tint = if (entry.isDirectory) AccentBlue else TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = entry.name,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!entry.isDirectory) {
                            Text(
                                text = "${entry.size} B",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                    HorizontalDivider(color = BgSecondary)
                }
            }
        }
    }
}

@Composable
private fun LogLineItem(entry: LogEntry) {
    val color = when (entry.level) {
        "error" -> StatusRed
        "user" -> AccentBlue
        "agent" -> AccentPurple
        "action" -> StatusGreen
        "system" -> StatusAmber
        else -> TextPrimary
    }
    Text(
        text = entry.message,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
}

@Composable
private fun FileViewerDialog(
    file: FileContent,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(14.dp),
            color = BgSecondary
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = file.path,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row {
                        IconButton(onClick = onCopy) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy file")
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }
                HorizontalDivider(color = BgTertiary)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(BgPrimary)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = file.content,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}
