package com.antigravity.remote.data

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads the PC's current addresses from the encrypted relay the bridge publishes to ntfy.sh.
 * The topic and the AES-256-GCM key are both derived from the pairing token, matching
 * bridge/src/relay.ts, so a new tunnel address reaches the phone without scanning again.
 */
object AddressRelay {
    private const val SERVER = "https://ntfy.sh"

    data class Routes(val hosts: List<String>, val port: Int, val url: String?)

    fun fetch(http: OkHttpClient, token: String): Routes? {
        val request = Request.Builder().url("$SERVER/${topic(token)}/json?poll=1&since=24h").get().build()
        val body = runCatching { http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null } }.getOrNull()
            ?: return null
        val latest = body.lineSequence()
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
            .filter { it.optString("event") == "message" }
            .lastOrNull()
            ?.optString("message")
            ?: return null
        val plain = runCatching { open(token, latest) }.getOrNull() ?: return null
        val json = runCatching { JSONObject(plain) }.getOrNull() ?: return null
        val hostsJson = json.optJSONArray("hosts")
        val hosts = (0 until (hostsJson?.length() ?: 0)).mapNotNull { hostsJson?.optString(it)?.takeIf { h -> h.isNotBlank() } }
        val url = json.optString("url").takeIf { it.startsWith("http") }
        return Routes(hosts, json.optInt("port", 8765), url)
    }

    private fun topic(token: String): String = "agr-" + sha256("antigravity-remote/topic/$token").toHex().take(32)

    private fun open(token: String, sealed: String): String {
        val raw = Base64.decode(sealed, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sha256("antigravity-remote/key/$token"), "AES"),
            GCMParameterSpec(128, raw, 0, 12)
        )
        return String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
