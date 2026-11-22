package com.antigravity.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.remote.data.LogEntry
import com.antigravity.remote.ui.theme.AccentBlue
import com.antigravity.remote.ui.theme.AccentPurple
import com.antigravity.remote.ui.theme.BgSecondary
import com.antigravity.remote.ui.theme.BgTertiary
import com.antigravity.remote.ui.theme.StatusAmber
import com.antigravity.remote.ui.theme.StatusGreen
import com.antigravity.remote.ui.theme.StatusRed
import com.antigravity.remote.ui.theme.TextPrimary
import com.antigravity.remote.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Auto-scrolling log list. It follows new output while the user is at the bottom and stops
 * following as soon as they scroll up; the arrow button jumps back and resumes following.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogList(
    entries: List<LogEntry>,
    emptyText: String,
    onLongPress: (LogEntry) -> Unit,
    modifier: Modifier = Modifier,
    line: @Composable (LogEntry) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    var userScrolled by remember { mutableStateOf(false) }

    // Only a finger drag (and the fling after it) may turn following off or back on;
    // programmatic scrolls while history streams in must not.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) userScrolled = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && userScrolled) {
                userScrolled = false
                follow = !listState.canScrollForward
            }
        }
    }

    val tailLength = entries.lastOrNull()?.message?.length ?: 0
    LaunchedEffect(entries.size, tailLength) {
        if (follow && entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
            listState.scrollBy(1_000_000f)
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (entries.isEmpty()) {
                item {
                    Text(emptyText, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            items(entries, key = { it.id }) { entry ->
                Box(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { onLongPress(entry) })) {
                    line(entry)
                }
            }
        }
        if (!follow && entries.isNotEmpty()) {
            SmallFloatingActionButton(
                onClick = {
                    follow = true
                    scope.launch {
                        listState.scrollToItem(entries.lastIndex)
                        listState.scrollBy(1_000_000f)
                    }
                },
                containerColor = BgTertiary,
                contentColor = TextPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Jump to latest")
            }
        }
    }
}

private val Mono = FontFamily.Monospace

/** Agent stream line: prompts, agent prose, thoughts, tool calls and their output. */
@Composable
fun AgentLogLine(entry: LogEntry) {
    when (entry.level) {
        "user" -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = entry.message,
                color = TextPrimary,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                modifier = Modifier
                    .padding(start = 40.dp, top = 6.dp)
                    .background(AccentBlue.copy(alpha = 0.16f), RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        "agent" -> {
            val rendered = remember(entry.message) { renderMarkdown(entry.message, AccentBlue, BgSecondary) }
            Text(rendered, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
        }
        "thought" -> ThoughtLine(entry.message)
        "tool" -> Text(
            text = entry.message,
            color = if (entry.message.startsWith("ok ")) TextSecondary else AccentPurple,
            fontFamily = Mono,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        "output" -> OutputBlock(entry.message)
        else -> StatusLine(entry)
    }
}

/** Terminal stream line: monospace, one entry per output line. */
@Composable
fun TerminalLogLine(entry: LogEntry) {
    val color = when (entry.level) {
        "user" -> AccentBlue
        "error" -> StatusRed
        "system" -> TextSecondary
        "action" -> StatusAmber
        else -> TextPrimary
    }
    Text(
        text = entry.message,
        color = color,
        fontFamily = Mono,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = if (entry.level == "user") FontWeight.SemiBold else FontWeight.Normal
    )
}

@Composable
private fun ThoughtLine(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text(
        text = text.trim(),
        color = TextSecondary,
        fontStyle = FontStyle.Italic,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        maxLines = if (expanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable { expanded = !expanded }
    )
}

private const val COLLAPSED_OUTPUT_LINES = 8

/** Command output from an agent tool call, collapsed to a few lines until tapped. */
@Composable
private fun OutputBlock(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val collapsible = lineCount > COLLAPSED_OUTPUT_LINES
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSecondary, RoundedCornerShape(8.dp))
            .clickable(enabled = collapsible) { expanded = !expanded }
            .padding(10.dp)
    ) {
        Text(
            text = text,
            color = TextPrimary.copy(alpha = 0.85f),
            fontFamily = Mono,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            maxLines = if (expanded || !collapsible) Int.MAX_VALUE else COLLAPSED_OUTPUT_LINES,
            overflow = TextOverflow.Ellipsis
        )
        if (collapsible) {
            Text(
                text = if (expanded) "Show less" else "Show all $lineCount lines",
                color = AccentBlue,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun StatusLine(entry: LogEntry) {
    val color = when (entry.level) {
        "error" -> StatusRed
        "action" -> StatusGreen
        else -> StatusAmber
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entry.timestamp.isNotEmpty()) {
            Text(entry.timestamp, color = TextSecondary.copy(alpha = 0.6f), fontFamily = Mono, fontSize = 10.sp, lineHeight = 16.sp)
        }
        Text(entry.message, color = color, fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
fun StatusDot(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(8.dp).background(color, RoundedCornerShape(50)))
}
