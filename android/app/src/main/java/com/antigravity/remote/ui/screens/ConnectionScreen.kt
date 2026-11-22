package com.antigravity.remote.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.antigravity.remote.R
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.antigravity.remote.data.ConnectionProfile
import com.antigravity.remote.data.ConnectionStatus
import com.antigravity.remote.ui.RemoteUiState
import com.antigravity.remote.ui.components.QrScannerDialog
import com.antigravity.remote.ui.theme.AccentBlue
import com.antigravity.remote.ui.theme.BgPrimary
import com.antigravity.remote.ui.theme.BgSecondary
import com.antigravity.remote.ui.theme.StatusRed

@Composable
fun ConnectionScreen(
    uiState: RemoteUiState,
    onConnect: (ConnectionProfile) -> Unit,
    onQrScanned: (String) -> Boolean,
    onClearSaved: () -> Unit
) {
    val context = LocalContext.current
    var host by remember(uiState.savedProfile) { mutableStateOf(uiState.savedProfile?.host ?: "") }
    var port by remember(uiState.savedProfile) {
        mutableStateOf(uiState.savedProfile?.port?.toString() ?: "8765")
    }
    var token by remember(uiState.savedProfile) { mutableStateOf(uiState.savedProfile?.token ?: "") }
    var showScanner by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showScanner = true
        }
    }

    val isConnecting = uiState.connectionStatus == ConnectionStatus.CONNECTING ||
        uiState.connectionStatus == ConnectionStatus.RECONNECTING

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
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppLogo(size = 48.dp)
                Column {
                    Text(
                        text = "Antigravity Remote",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Control Antigravity on your PC from this phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            uiState.savedProfile?.let { saved ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = BgSecondary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Saved Bridge",
                                style = MaterialTheme.typography.labelMedium,
                                color = AccentBlue
                            )
                            Text(
                                text = saved.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onClearSaved) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Forget saved bridge",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { onConnect(saved) },
                                enabled = !isConnecting
                            ) {
                                Text("Reconnect")
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val hasCam = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasCam) {
                        showScanner = true
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Scan Bridge QR Code",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = "  OR ENTER MANUALLY  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("PC address: LAN IP, Tailscale IP or https URL") },
                placeholder = { Text("192.168.1.42 or https://...trycloudflare.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                label = { Text("Port") },
                placeholder = { Text("8765") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Auth Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            uiState.connectionError?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = StatusRed.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = StatusRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    val parsedPort = port.toIntOrNull() ?: 8765
                    if (host.isNotBlank() && token.isNotBlank()) {
                        onConnect(
                            ConnectionProfile(
                                host = host.trim(),
                                port = parsedPort,
                                token = token.trim(),
                                // Keep the other routes from the QR code (Tailscale, public URL) for the same PC.
                                extraRoutes = uiState.savedProfile
                                    ?.takeIf { it.token == token.trim() }
                                    ?.extraRoutes
                                    ?.filter { it != host.trim() }
                                    .orEmpty()
                            )
                        )
                    }
                },
                enabled = !isConnecting && host.isNotBlank() && token.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Connecting...")
                } else {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect")
                }
            }

            PairingHint()
        }
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
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BgSecondary)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("How to pair", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        listOf(
            "1. On the PC, double-click start-bridge-remote.bat (works from anywhere) or start-bridge.bat (home network)",
            "2. Tap Scan Bridge QR Code and point at the code it prints",
            "3. The app remembers every route to the PC and switches between Wi-Fi, Tailscale and the tunnel by itself"
        ).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
