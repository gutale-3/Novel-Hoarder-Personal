package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases. The CI workflow publishes each build as a release tagged
 * `build-<versionCode>` with the APK attached; the app compares that number with its own
 * versionCode, downloads the APK and hands it to the system installer.
 */
object AppUpdater {

    const val REPO = "gutale-3/Novel-Hoarder-Personal"
    private const val PREFS = "app_updater"
    private const val KEY_LAST_CHECK = "last_check"

    data class Release(
        val versionCode: Long,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long
    )

    private val _available = MutableStateFlow<Release?>(null)
    /** Set by a background check when a newer build exists; shown as a card on Home. */
    val available: StateFlow<Release?> = _available.asStateFlow()

    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** "test" for the side-by-side build, "main" otherwise; APK asset names carry the edition. */
    fun edition(context: Context): String = if (context.packageName.endsWith(".test")) "test" else "main"

    /** Picks the newest release that has an APK for this edition. Pure, for testing. */
    fun pickRelease(releasesJson: String, edition: String): Release? {
        val arr = JSONArray(releasesJson)
        var best: Release? = null
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val code = r.optString("tag_name").removePrefix("build-").toLongOrNull() ?: continue
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.optString("name")
                if (name.endsWith(".apk") && name.contains("-$edition-")) {
                    if (best == null || code > best.versionCode) {
                        best = Release(
                            versionCode = code,
                            title = r.optString("name").ifBlank { "Build $code" },
                            notes = r.optString("body"),
                            apkUrl = a.optString("browser_download_url"),
                            sizeBytes = a.optLong("size")
                        )
                    }
                }
            }
        }
        return best
    }

    /** Returns the newer release, or null when this install is up to date. */
    suspend fun check(context: Context): Release? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/$REPO/releases?per_page=15").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode !in 200..299) throw java.io.IOException("GitHub returned HTTP ${conn.responseCode}")
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            pickRelease(json, edition(context))
                ?.takeIf { it.versionCode > currentVersionCode(context) }
                .also { _available.value = it }
        } finally {
            conn.disconnect()
        }
    }

    /** At most once every 12 hours; failures are ignored because this is a background nicety. */
    suspend fun checkIfDue(context: Context) {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < 12 * 60 * 60 * 1000L) return
        runCatching { check(context) }
    }

    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "update-${release.versionCode}.apk")
            val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                if (conn.responseCode !in 200..299) throw java.io.IOException("Download failed: HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            out
        }

    /** True when Android will let this app open the installer (the user grants it once). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
