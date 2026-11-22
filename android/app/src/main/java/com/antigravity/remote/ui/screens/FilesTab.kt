package com.antigravity.remote.ui.screens

import androidx.compose.ui.res.stringResource
import com.antigravity.remote.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.remote.data.FileEntry
import com.antigravity.remote.ui.RemoteUiState
import com.antigravity.remote.ui.theme.AccentBlue
import com.antigravity.remote.ui.theme.AccentPurple
import com.antigravity.remote.ui.theme.BgSecondary
import com.antigravity.remote.ui.theme.BgTertiary
import com.antigravity.remote.ui.theme.TextPrimary
import com.antigravity.remote.ui.theme.TextSecondary

private val CODE_EXTENSIONS = setOf(
    "kt", "kts", "java", "ts", "tsx", "js", "jsx", "mjs", "py", "rs", "go", "c", "cpp", "h", "cs", "swift",
    "json", "yml", "yaml", "toml", "xml", "html", "css", "scss", "sh", "ps1", "bat", "gradle", "sql"
)
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp")

@Composable
fun FilesTab(
    uiState: RemoteUiState,
    onRefresh: () -> Unit,
    onNavigateUp: () -> Unit,
    onOpenDir: (String) -> Unit,
    onOpenFile: (String) -> Unit
) {
    val path = uiState.currentFilePath
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(BgSecondary).padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateUp, enabled = path.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.up_one_folder))
            }
            Breadcrumbs(
                root = uiState.bridgeStatus.workspaceName.ifEmpty { "workspace" },
                path = path,
                onOpen = onOpenDir,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
            }
        }
        if (uiState.filesLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentBlue, trackColor = BgTertiary)
        }
        if (uiState.fileEntries.isEmpty() && !uiState.filesLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.folder_empty), color = TextSecondary)
            }
        } else if (uiState.fileEntries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentBlue)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.fileEntries, key = { it.path }) { entry ->
                    FileRow(entry) { if (entry.isDirectory) onOpenDir(entry.path) else onOpenFile(entry.path) }
                    HorizontalDivider(color = BgSecondary, modifier = Modifier.padding(start = 52.dp))
                }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(root: String, path: String, onOpen: (String) -> Unit, modifier: Modifier) {
    val parts = if (path.isEmpty()) emptyList() else path.split('/')
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Crumb(root, active = parts.isEmpty()) { onOpen("") }
        parts.forEachIndexed { index, part ->
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
            val target = parts.take(index + 1).joinToString("/")
            Crumb(part, active = index == parts.lastIndex) { onOpen(target) }
        }
    }
}

@Composable
private fun Crumb(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (active) TextPrimary else TextSecondary,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp)
    )
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit) {
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    val (icon: ImageVector, tint) = when {
        entry.isDirectory -> Icons.Default.Folder to AccentBlue
        ext in CODE_EXTENSIONS -> Icons.Default.Code to AccentPurple
        ext in IMAGE_EXTENSIONS -> Icons.Default.Image to TextSecondary
        else -> Icons.Default.Description to TextSecondary
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            text = entry.name,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (entry.isDirectory) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        } else {
            Text(formatSize(entry.size), color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
