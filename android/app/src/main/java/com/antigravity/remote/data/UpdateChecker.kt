package com.antigravity.remote.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.antigravity.remote.BuildConfig
import com.antigravity.remote.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Checks GitHub Releases for a newer app version and installs it. */
class UpdateChecker(private val context: Context) {

    data class Release(val version: String, val notes: String, val apkUrl: String, val checksumUrl: String?)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class NeedsPermission(val release: Release) : State
        data class Failed(val message: UiText, val release: Release? = null) : State
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun check(): State = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_RELEASE)
                .header("Accept", "application/vnd.github+json")
                .build()
            val json = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("GitHub HTTP ${response.code}")
                JSONObject(response.body?.string().orEmpty())
            }
            val version = json.optString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            var checksum: String? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val asset = assets!!.getJSONObject(i)
                when (asset.optString("name")) {
                    APK_NAME -> apk = asset.optString("browser_download_url")
                    "$APK_NAME.sha256" -> checksum = asset.optString("browser_download_url")
                }
            }
            if (apk == null || !isNewer(version, currentVersion())) {
                State.UpToDate
            } else {
                State.Available(Release(version, json.optString("body").trim(), apk, checksum))
            }
        }.getOrElse { State.Failed(UiText.Res(R.string.update_failed, listOf(it.message ?: it.javaClass.simpleName))) }
    }

    suspend fun downloadAndInstall(release: Release, onState: (State) -> Unit) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            onState(State.NeedsPermission(release))
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        onState(State.Downloading(release, 0))
        val file = withContext(Dispatchers.IO) {
            runCatching { download(release) { percent -> onState(State.Downloading(release, percent)) } }
        }.getOrElse {
            onState(State.Failed(UiText.Res(R.string.update_failed, listOf(it.message ?: "")), release))
            return
        }
        if (file == null) {
            onState(State.Failed(UiText.Res(R.string.update_verify_failed), release))
            return
        }
        onState(State.Available(release))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Downloads the APK; returns null when it does not match the published SHA-256. */
    private fun download(release: Release, progress: (Int) -> Unit): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "AntigravityRemote-${release.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val body = response.body ?: throw java.io.IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        val percent = if (total != null) ((done * 100) / total).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            progress(percent)
                        }
                    }
                }
            }
        }
        val expected = release.checksumUrl?.let { url ->
            http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }
                ?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.lowercase()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && expected != actual) {
            target.delete()
            return null
        }
        return target
    }

    companion object {
        private const val LATEST_RELEASE = "https://api.github.com/repos/dor2500/antigravity-remote/releases/latest"
        private const val APK_NAME = "AntigravityRemote-latest.apk"
        const val PROJECT_URL = "https://github.com/dor2500/antigravity-remote"

        fun currentVersion(): String = BuildConfig.VERSION_NAME.substringBefore('-')

        /** True when [candidate] (for example "1.3.0") is a higher version than [current]. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
            val b = current.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
