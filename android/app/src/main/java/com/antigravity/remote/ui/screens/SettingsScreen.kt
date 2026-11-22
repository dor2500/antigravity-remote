package com.antigravity.remote.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.antigravity.remote.R
import com.antigravity.remote.data.ConnectionProfile
import com.antigravity.remote.data.UpdateChecker
import com.antigravity.remote.ui.RemoteUiState
import com.antigravity.remote.ui.asString
import com.antigravity.remote.ui.theme.AccentBlue
import com.antigravity.remote.ui.theme.BgPrimary
import com.antigravity.remote.ui.theme.BgSecondary
import com.antigravity.remote.ui.theme.BgTertiary
import com.antigravity.remote.ui.theme.StatusGreen
import com.antigravity.remote.ui.theme.StatusRed
import com.antigravity.remote.ui.theme.TextPrimary
import com.antigravity.remote.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    uiState: RemoteUiState,
    onBack: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val openUrl = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Scaffold(containerColor = BgPrimary) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            Section(stringResource(R.string.settings_updates)) {
                UpdateSection(uiState, onCheckUpdate, onInstallUpdate)
            }

            Section(stringResource(R.string.settings_language)) {
                LanguageSection()
            }

            Section(stringResource(R.string.settings_connection)) {
                Text(stringResource(R.string.connection_routes), color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                val profile = uiState.savedProfile
                if (profile == null) {
                    Text(stringResource(R.string.connection_no_routes), color = TextSecondary)
                } else {
                    profile.routes.forEach { route ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            val active = route == uiState.activeRoute
                            Text(
                                text = stringResource(ConnectionProfile.routeKind(route)),
                                color = if (active) StatusGreen else AccentBlue,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .background((if (active) StatusGreen else AccentBlue).copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(route.removePrefix("http://").removePrefix("https://"), fontFamily = FontFamily.Monospace, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BgTertiary).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(stringResource(R.string.tailscale_title), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.tailscale_body), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { openUrl("https://tailscale.com/download") }, modifier = Modifier.padding(top = 2.dp)) {
                        Text(stringResource(R.string.tailscale_open))
                    }
                }
            }

            Section(stringResource(R.string.settings_about)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { openUrl(UpdateChecker.PROJECT_URL) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.open_github), modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BgSecondary)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = AccentBlue, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun UpdateSection(uiState: RemoteUiState, onCheck: () -> Unit, onInstall: () -> Unit) {
    Text(stringResource(R.string.app_version, UpdateChecker.currentVersion()))
    uiState.bridgeStatus.version.takeIf { it.isNotEmpty() && uiState.isOnline }?.let {
        Text(stringResource(R.string.bridge_version, it), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.bridge_updates_itself), color = TextSecondary, style = MaterialTheme.typography.bodySmall)

    when (val state = uiState.update) {
        UpdateChecker.State.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentBlue)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.checking_updates), color = TextSecondary)
        }
        UpdateChecker.State.UpToDate -> {
            Text(stringResource(R.string.up_to_date), color = StatusGreen)
            OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.check_updates)) }
        }
        is UpdateChecker.State.Available -> ReleaseCard(state.release, onInstall, null)
        is UpdateChecker.State.NeedsPermission -> ReleaseCard(state.release, onInstall, stringResource(R.string.update_allow_installs))
        is UpdateChecker.State.Downloading -> {
            Text(stringResource(R.string.update_downloading, state.percent))
            LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = AccentBlue, trackColor = BgTertiary)
        }
        is UpdateChecker.State.Failed -> {
            Text(state.message.asString(), color = StatusRed, style = MaterialTheme.typography.bodySmall)
            if (state.release != null) ReleaseCard(state.release, onInstall, null)
            else OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.check_updates)) }
        }
        UpdateChecker.State.Idle -> OutlinedButton(onClick = onCheck) { Text(stringResource(R.string.check_updates)) }
    }
}

@Composable
private fun ReleaseCard(release: UpdateChecker.Release, onInstall: () -> Unit, note: String?) {
    var showNotes by remember { mutableStateOf(false) }
    Text(stringResource(R.string.update_available, release.version), color = AccentBlue, fontWeight = FontWeight.SemiBold)
    note?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
    Button(
        onClick = onInstall,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
    ) { Text(stringResource(R.string.update_download), fontWeight = FontWeight.SemiBold) }
    if (release.notes.isNotBlank()) {
        TextButton(onClick = { showNotes = !showNotes }) { Text(stringResource(R.string.whats_new)) }
        if (showNotes) Text(release.notes, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LanguageSection() {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val options = listOf(
        "he" to R.string.language_hebrew,
        "en" to R.string.language_english,
        "" to R.string.language_system
    )
    options.forEach { (tag, label) ->
        val selected = when (tag) {
            "he" -> current.startsWith("he") || current.startsWith("iw")
            "" -> current.isEmpty()
            else -> current.startsWith(tag)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) },
                colors = RadioButtonDefaults.colors(selectedColor = AccentBlue)
            )
            Text(stringResource(label))
        }
    }
}
