package com.antigravity.remote.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.antigravity.remote.R
import com.antigravity.remote.data.ConnectionProfile
import com.antigravity.remote.data.ConnectionStatus
import com.antigravity.remote.data.UpdateChecker
import com.antigravity.remote.ui.RemoteUiState
import com.antigravity.remote.ui.asString
import com.antigravity.remote.ui.components.QrScannerDialog
import com.antigravity.remote.ui.theme.AccentBlue
import com.antigravity.remote.ui.theme.BgPrimary
import com.antigravity.remote.ui.theme.BgSecondary
import com.antigravity.remote.ui.theme.StatusRed
import com.antigravity.remote.ui.theme.TextSecondary

/** First screen: scan the PC's QR code (or reconnect to the saved PC). Manual entry is folded away. */
@Composable
fun ConnectionScreen(
    uiState: RemoteUiState,
    onConnect: (ConnectionProfile) -> Unit,
    onQrScanned: (String) -> Boolean,
    onClearSaved: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var showScanner by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    val connecting = uiState.connectionStatus == ConnectionStatus.CONNECTING

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showScanner = true
    }
    val startScan = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScanner = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    if (showScanner) {
        QrScannerDialog(
            onQrScanned = { payload ->
                showScanner = false
                onQrScanned(payload)
            },
            onDismiss = { showScanner = false }
        )
    }

    Scaffold(containerColor = BgPrimary) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLogo(size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.connect_subtitle), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings), tint = TextSecondary)
                }
            }

            (uiState.update as? UpdateChecker.State.Available)?.let { available ->
                UpdateBanner(available.release.version, onOpenSettings)
            }

            uiState.savedProfile?.let { saved ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(BgSecondary)
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.saved_bridge), style = MaterialTheme.typography.labelMedium, color = AccentBlue)
                            Text(saved.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = onClearSaved) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.forget_saved_bridge), tint = TextSecondary)
                        }
                    }
                    Button(
                        onClick = { onConnect(saved) },
                        enabled = !connecting,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = BgPrimary)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.connecting_ellipsis))
                        } else {
                            Text(stringResource(R.string.reconnect), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            val scanFirst = uiState.savedProfile == null
            if (scanFirst) {
                Button(
                    onClick = startScan,
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.scan_qr), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            } else {
                OutlinedButton(onClick = startScan, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.scan_qr))
                }
            }

            uiState.connectionError?.let { error ->
                Text(
                    text = error.asString(),
                    color = StatusRed,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(StatusRed.copy(alpha = 0.12f))
                        .padding(14.dp)
                )
            }

            TextButton(onClick = { showManual = !showManual }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.enter_manually))
                Icon(if (showManual) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
            }
            AnimatedVisibility(visible = showManual) {
                ManualEntry(uiState, connecting, onConnect)
            }

            PairingHint()
        }
    }
}

@Composable
private fun ManualEntry(uiState: RemoteUiState, connecting: Boolean, onConnect: (ConnectionProfile) -> Unit) {
    var host by remember(uiState.savedProfile) { mutableStateOf(uiState.savedProfile?.host ?: "") }
    var port by remember(uiState.savedProfile) { mutableStateOf(uiState.savedProfile?.port?.toString() ?: "8765") }
    var token by remember(uiState.savedProfile) { mutableStateOf(uiState.savedProfile?.token ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.field_host)) },
                placeholder = { Text(stringResource(R.string.field_host_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.field_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(96.dp)
            )
        }
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(R.string.field_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = {
                onConnect(
                    ConnectionProfile(
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 8765,
                        token = token.trim(),
                        // Keep the other routes from the QR code (Tailscale, tunnel) for the same PC.
                        extraRoutes = uiState.savedProfile
                            ?.takeIf { it.token == token.trim() }
                            ?.extraRoutes
                            ?.filter { it != host.trim() }
                            .orEmpty()
                    )
                )
            },
            enabled = !connecting && host.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.connect))
        }
    }
}

@Composable
private fun UpdateBanner(version: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AccentBlue.copy(alpha = 0.14f))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.update_available, version), color = AccentBlue, modifier = Modifier.weight(1f))
        TextButton(onClick = onOpen) { Text(stringResource(R.string.update_open)) }
    }
}

@Composable
fun AppLogo(size: Dp) {
    Box(modifier = Modifier.size(size).clip(RoundedCornerShape(size / 3.6f))) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun PairingHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgSecondary)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.how_to_pair), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        listOf(R.string.pair_step_1, R.string.pair_step_2, R.string.pair_step_3).forEach {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}
