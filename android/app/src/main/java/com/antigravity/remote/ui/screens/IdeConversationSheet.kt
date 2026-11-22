package com.antigravity.remote.ui.screens

import androidx.compose.ui.res.stringResource
import com.antigravity.remote.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antigravity.remote.data.IdeConversation
import com.antigravity.remote.ui.RemoteUiState
import com.antigravity.remote.ui.components.StatusDot
import com.antigravity.remote.ui.theme.AccentBlue
import com.antigravity.remote.ui.theme.BgSecondary
import com.antigravity.remote.ui.theme.StatusGreen
import com.antigravity.remote.ui.theme.TextPrimary
import com.antigravity.remote.ui.theme.TextSecondary
import java.time.Duration
import java.time.Instant

/** Lists Antigravity IDE conversations so the phone can follow one or start a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeConversationSheet(
    uiState: RemoteUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newPrompt by remember { mutableStateOf("") }
    val activeId = uiState.bridgeStatus.agent.conversationId
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = BgSecondary) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ide_sheet_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.ide_sheet_subtitle),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh)) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newPrompt,
                    onValueChange = { newPrompt = it },
                    placeholder = { Text(stringResource(R.string.ide_new_hint)) },
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                )
                IconButton(onClick = { if (newPrompt.isNotBlank()) onNew(newPrompt) }, enabled = newPrompt.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.ide_new_start), tint = if (newPrompt.isNotBlank()) AccentBlue else TextSecondary)
                }
            }
            if (uiState.ideConversationsLoading && uiState.ideConversations.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentBlue)
                }
            } else if (uiState.ideConversations.isEmpty()) {
                Text(
                    stringResource(R.string.ide_none),
                    color = TextSecondary,
                    modifier = Modifier.padding(20.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(uiState.ideConversations, key = { it.id }) { conversation ->
                        ConversationRow(conversation, active = conversation.id == activeId) { onOpen(conversation.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: IdeConversation, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = if (active) AccentBlue else TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                conversation.title,
                color = if (active) AccentBlue else TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val running = conversation.status == "running" || conversation.status == "busy"
                if (running) StatusDot(StatusGreen, Modifier.size(6.dp))
                Text(
                    text = listOfNotNull(
                        if (running) stringResource(R.string.ide_running) else null,
                        relativeTime(conversation.updatedAt)
                    ).joinToString("  -  "),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun relativeTime(iso: String): String? {
    val then = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
    val minutes = Duration.between(then, Instant.now()).toMinutes().toInt()
    return when {
        minutes < 1 -> stringResource(R.string.time_just_now)
        minutes < 60 -> stringResource(R.string.time_minutes, minutes)
        minutes < 60 * 24 -> stringResource(R.string.time_hours, minutes / 60)
        else -> stringResource(R.string.time_days, minutes / (60 * 24))
    }
}
