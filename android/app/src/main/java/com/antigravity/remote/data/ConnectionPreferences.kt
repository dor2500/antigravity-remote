package com.antigravity.remote.data

import android.content.Context

class ConnectionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("antigravity_remote_prefs", Context.MODE_PRIVATE)

    fun saveProfile(profile: ConnectionProfile) {
        prefs.edit()
            .putString(KEY_HOST, profile.host)
            .putInt(KEY_PORT, profile.port)
            .putString(KEY_TOKEN, profile.token)
            .putString(KEY_ROUTES, profile.extraRoutes.joinToString("\n"))
            .apply()
    }

    fun getSavedProfile(): ConnectionProfile? {
        val host = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        val port = prefs.getInt(KEY_PORT, 8765)
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val routes = prefs.getString(KEY_ROUTES, null).orEmpty().split('\n').filter { it.isNotBlank() }
        return ConnectionProfile(host = host, port = port, token = token, extraRoutes = routes)
    }

    fun clearProfile() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_ROUTES = "routes"
    }
}
