package com.antigravity.remote.ui.screens

import androidx.compose.ui.res.stringResource
import com.antigravity.remote.R
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.remote.ui.RemoteUiState
import com.antigravity.remote.ui.components.LogList
import com.antigravity.remote.ui.components.StatusDot
import com.antigravity.remote.ui.components.TerminalLogLine
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

private val QUICK_COMMANDS = listOf("git status", "git log --oneline -10", "git diff --stat", "ls", "npm test", "npm run build")

@Composable
fun TerminalTab(
    uiState: RemoteUiState,
    onRun: (String) -> Unit,
    onInput: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onCopy: (String, Int) -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    val status = uiState.bridgeStatus
    val running = status.terminalRunning

    val submit = {
        val text = input
        if (running) {
            onInput(text)
            input = ""
        } else if (text.isNotBlank()) {
            onRun(text.trim())
            input = ""
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(if (running) StatusAmber else StatusGreen)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (running) stringResource(R.string.terminal_running, status.terminalCommand.orEmpty()) else stringResource(R.string.terminal_ready),
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = shortPath(status.terminalCwd.ifEmpty { status.activeWorkspace }),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { onCopy(uiState.terminalLogs.copyText(), R.string.label_terminal_output) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy_terminal), tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.clear_terminal), tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
        }

        LogList(
            entries = uiState.terminalLogs,
            emptyText = stringResource(R.string.terminal_empty),
            onLongPress = { onCopy(it.message, R.string.label_line) },
            modifier = Modifier.weight(1f).fillMaxWidth().background(BgPrimary)
        ) { TerminalLogLine(it) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSecondary)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QUICK_COMMANDS.forEach { command ->
                AssistChip(
                    onClick = { onRun(command) },
                    enabled = !running,
                    label = { Text(command, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                    shape = RoundedCornerShape(10.dp),
                    colors = AssistChipDefaults.assistChipColors(containerColor = BgTertiary, labelColor = TextPrimary),
                    border = null
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSecondary)
                .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = {
                    Text(
                        text = if (running) stringResource(R.string.terminal_input_hint) else "PS> command",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = BorderSubtle,
                    focusedContainerColor = BgPrimary,
                    unfocusedContainerColor = BgPrimary
                )
            )
            Spacer(Modifier.width(6.dp))
            if (running) {
                IconButton(
                    onClick = onStop,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = StatusRed.copy(alpha = 0.18f), contentColor = StatusRed)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_command))
                }
            }
            IconButton(
                onClick = submit,
                enabled = running || input.isNotBlank(),
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = AccentBlue,
                    contentColor = BgPrimary,
                    disabledContainerColor = BgTertiary,
                    disabledContentColor = TextSecondary
                )
            ) {
                Icon(
                    imageVector = if (running) Icons.AutoMirrored.Filled.KeyboardReturn else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (running) stringResource(R.string.send_input) else stringResource(R.string.run_command)
                )
            }
        }
    }
}

/** Keeps the last folders of a long Windows path, e.g. "...\projects\app\src". */
private fun shortPath(path: String, keep: Int = 3): String {
    val parts = path.split('\\', '/').filter { it.isNotEmpty() }
    return if (parts.size <= keep + 1) path else "...\\" + parts.takeLast(keep).joinToString("\\")
}
