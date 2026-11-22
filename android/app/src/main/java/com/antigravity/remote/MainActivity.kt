package com.antigravity.remote

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antigravity.remote.ui.RemoteViewModel
import com.antigravity.remote.ui.screens.ConnectionScreen
import com.antigravity.remote.ui.screens.RemoteActions
import com.antigravity.remote.ui.screens.RemoteControlScreen
import com.antigravity.remote.ui.screens.SettingsScreen
import com.antigravity.remote.ui.theme.AntigravityRemoteTheme

class MainActivity : AppCompatActivity() {
    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        // Needs the activity registered with AppCompat, so it runs after super.onCreate().
        applyDefaultLanguage()
        if (savedInstanceState == null) vm.checkForUpdate()
        setContent {
            AntigravityRemoteTheme {
                val uiState by vm.uiState.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }
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
                        consumeToast = vm::consumeToast,
                        loadIdeConversations = vm::loadIdeConversations,
                        openIdeConversation = vm::openIdeConversation,
                        newIdeConversation = vm::newIdeConversation,
                        closeIdeConversation = vm::closeIdeConversation,
                        openSettings = { showSettings = true }
                    )
                }
                when {
                    showSettings -> SettingsScreen(
                        uiState = uiState,
                        onBack = { showSettings = false },
                        onCheckUpdate = vm::checkForUpdate,
                        onInstallUpdate = vm::installUpdate
                    )
                    uiState.isOnline -> RemoteControlScreen(uiState = uiState, actions = actions)
                    else -> ConnectionScreen(
                        uiState = uiState,
                        onConnect = vm::connect,
                        onQrScanned = vm::connectFromQrPayload,
                        onClearSaved = vm::clearSavedProfile,
                        onOpenSettings = { showSettings = true }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppForeground()
    }

    /** The app opens in Hebrew the first time; the language can be changed in Settings. */
    private fun applyDefaultLanguage() {
        val prefs = getSharedPreferences("antigravity_remote_app", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LANGUAGE_SET, false)) return
        prefs.edit().putBoolean(KEY_LANGUAGE_SET, true).apply()
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("he"))
        }
    }

    private companion object {
        const val KEY_LANGUAGE_SET = "language_set"
    }
}
