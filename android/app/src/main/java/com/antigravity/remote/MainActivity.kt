package com.antigravity.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antigravity.remote.ui.RemoteViewModel
import com.antigravity.remote.ui.screens.ConnectionScreen
import com.antigravity.remote.ui.screens.RemoteActions
import com.antigravity.remote.ui.screens.RemoteControlScreen
import com.antigravity.remote.ui.theme.AntigravityRemoteTheme

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            AntigravityRemoteTheme {
                val uiState by vm.uiState.collectAsStateWithLifecycle()
                val actions = remember {
                    RemoteActions(
                        sendPrompt = vm::sendPrompt,
                        runTerminal = vm::runTerminalCommand,
                        sendTerminalInput = vm::sendTerminalInput,
                        sendAction = vm::sendAction,
                        openInIde = vm::openInIde,
                        switchWorkspace = vm::switchWorkspace,
                        refreshWorkspaces = vm::loadWorkspaces,
                        openDirectory = vm::loadFiles,
                        navigateUp = vm::navigateUpDirectory,
                        openFile = vm::openFile,
                        closeFile = vm::closeOpenedFile,
                        disconnect = vm::disconnect,
                        consumeToast = vm::consumeToast
                    )
                }
                if (uiState.isOnline) {
                    RemoteControlScreen(uiState = uiState, actions = actions)
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

    override fun onStart() {
        super.onStart()
        vm.onAppForeground()
    }
}
