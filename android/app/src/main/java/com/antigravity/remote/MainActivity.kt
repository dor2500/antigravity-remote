package com.antigravity.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.antigravity.remote.data.ConnectionStatus
import com.antigravity.remote.ui.RemoteViewModel
import com.antigravity.remote.ui.screens.ConnectionScreen
import com.antigravity.remote.ui.screens.RemoteControlScreen
import com.antigravity.remote.ui.theme.AntigravityRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AntigravityRemoteTheme {
                val vm: RemoteViewModel = viewModel()
                val uiState by vm.uiState.collectAsState()

                if (uiState.connectionStatus == ConnectionStatus.CONNECTED ||
                    uiState.connectionStatus == ConnectionStatus.RECONNECTING
                ) {
                    RemoteControlScreen(
                        uiState = uiState,
                        onSendPrompt = vm::sendPrompt,
                        onRunTerminal = vm::runTerminalCommand,
                        onSendAction = vm::sendAction,
                        onSwitchWorkspace = vm::switchWorkspace,
                        onRefreshWorkspaces = vm::loadWorkspaces,
                        onOpenDirectory = vm::loadFiles,
                        onNavigateUpDir = vm::navigateUpDirectory,
                        onOpenFile = vm::openFile,
                        onCloseFile = vm::closeOpenedFile,
                        onDisconnect = vm::disconnect,
                        onConsumeToast = vm::consumeToast
                    )
                } else {
                    ConnectionScreen(
                        uiState = uiState,
                        onConnect = vm::connect,
                        onQrScanned = vm::connectFromQrPayload,
                        onClearSaved = vm::clearSavedProfile
                    )
                }
            }
        }
    }
}
