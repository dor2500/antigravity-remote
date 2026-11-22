package com.antigravity.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.remote.data.AgentState
import com.antigravity.remote.data.PermissionRequest
import com.antigravity.remote.ui.RemoteUiState
import com.antigravity.remote.ui.components.AgentLogLine
import com.antigravity.remote.ui.components.LogList
import com.antigravity.remote.ui.theme.AccentBlue
import com.antigravity.remote.ui.theme.BgPrimary
import com.antigravity.remote.ui.theme.BgSecondary
import com.antigravity.remote.ui.theme.BgTertiary
import com.antigravity.remote.ui.theme.BorderSubtle
import com.antigravity.remote.ui.theme.StatusAmber
import com.antigravity.remote.ui.theme.StatusGreen
import com.antigravity.remote.ui.theme.StatusRed
import com.antigravity.remote.ui.theme.TextPrimary
import com.antigravity.remote.ui.theme.TextSecondary

@Composable
fun AgentTab(
    uiState: RemoteUiState,
    onSendPrompt: (String) -> Unit,
    onAction: (String) -> Unit,
    onCopy: (String, String) -> Unit
) {
    var prompt by rememberSaveable { mutableStateOf("") }
    val agent = uiState.bridgeStatus.agent
    val busy = agent.state == AgentState.WORKING || agent.state == AgentState.AWAITING_PERMISSION || agent.state == AgentState.STARTING

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agent.title ?: "Antigravity agent",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = when {
                    !agent.available -> "Antigravity CLI (agy) not found on the PC"
                    agent.queued > 0 -> "${agent.queued} prompt${if (agent.queued == 1) "" else "s"} queued"
                    else -> "Prefix with ! to run a shell command"
                }
                Text(sub, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            IconButton(onClick = { onCopy(uiState.agentLogs.copyText(), "Output") }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy output", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
            SessionMenu(onAction)
        }

        LogList(
            entries = uiState.agentLogs,
            emptyText = "Send a prompt to the Antigravity agent running on your PC. Output, tool calls and approval requests stream here live.",
            onLongPress = { onCopy(it.message, "Message") },
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { AgentLogLine(it) }

        uiState.permission?.let { PermissionCard(it, onApprove = { onAction("approve") }, onReject = { onAction("reject") }) }

        QuickActions(
            hasPermission = uiState.permission != null,
            busy = busy,
            onAction = onAction
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSecondary)
                .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text(if (busy) "Queue a follow-up..." else "Ask the agent...") },
                minLines = 1,
                maxLines = 6,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = BorderSubtle,
                    focusedContainerColor = BgPrimary,
                    unfocusedContainerColor = BgPrimary
                )
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (prompt.isNotBlank()) {
                        onSendPrompt(prompt.trim())
                        prompt = ""
                    }
                },
                enabled = prompt.isNotBlank(),
                modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = AccentBlue,
                    contentColor = BgPrimary,
                    disabledContainerColor = BgTertiary,
                    disabledContentColor = TextSecondary
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send prompt")
            }
        }
    }
}

@Composable
private fun SessionMenu(onAction: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Session options", tint = TextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = BgTertiary) {
            DropdownMenuItem(text = { Text("New session") }, onClick = { open = false; onAction("restart") })
            DropdownMenuItem(text = { Text("Retry last prompt") }, onClick = { open = false; onAction("retry") })
        }
    }
}

@Composable
private fun PermissionCard(request: PermissionRequest, onApprove: () -> Unit, onReject: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(StatusAmber.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .border(1.dp, StatusAmber.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = StatusAmber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Agent wants to ${verb(request.kind)}", color = StatusAmber, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Text(
            text = request.detail.ifEmpty { request.title },
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = TextPrimary,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .heightIn(min = 20.dp)
                .background(BgPrimary, RoundedCornerShape(8.dp))
                .padding(10.dp)
        )
        Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                Text("Reject", color = StatusRed)
            }
            Button(
                onClick = onApprove,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StatusGreen, contentColor = BgPrimary)
            ) {
                Text("Approve", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun verb(kind: String): String = when (kind) {
    "execute" -> "run a command"
    "edit" -> "edit a file"
    "delete" -> "delete a file"
    "fetch" -> "fetch a URL"
    "read" -> "read a file"
    else -> "perform an action"
}

@Composable
private fun QuickActions(hasPermission: Boolean, busy: Boolean, onAction: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSecondary)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActionButton("Approve", Icons.Default.Check, StatusGreen, hasPermission, Modifier.weight(1f)) { onAction("approve") }
        ActionButton("Reject", Icons.Default.Close, StatusRed, hasPermission, Modifier.weight(1f)) { onAction("reject") }
        ActionButton("Stop", Icons.Default.Stop, StatusAmber, busy, Modifier.weight(1f)) { onAction("stop") }
        ActionButton("Clear", Icons.Default.DeleteSweep, TextSecondary, true, Modifier.weight(1f)) { onAction("clear") }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    color: Color,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = color.copy(alpha = 0.16f),
            contentColor = color,
            disabledContainerColor = BgTertiary.copy(alpha = 0.6f),
            disabledContentColor = TextSecondary.copy(alpha = 0.45f)
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, maxLines = 1)
    }
}
