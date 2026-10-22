package com.example.util

import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.BuildConfig
import java.io.File

/**
 * Crash reports: the crash handler saves one, and the next launch offers to send it with a
 * single tap through the share sheet (email, chat, notes — whatever the user picks).
 */
object CrashReports {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash_reports"
    private const val KEY_UNSENT = "unsent"

    fun build(thread: String, error: String, trace: String): String = buildString {
        appendLine("=== INKVAULT CRASH REPORT ===")
        appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
        appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine("Thread: $thread")
        appendLine("Error: $error")
        appendLine()
        appendLine("=== STACK TRACE ===")
        appendLine(trace)
    }

    /** Called from the crash handler: must not throw. */
    fun save(context: Context, report: String) {
        runCatching {
            File(context.filesDir, FILE).writeText(report)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_UNSENT, true).commit()
        }
    }

    fun latest(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    /** A saved report the user has neither sent nor dismissed yet. */
    fun hasUnsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_UNSENT, false) && latest(context) != null

    fun markHandled(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_UNSENT, false).apply()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
        markHandled(context)
    }

    /** Opens the share sheet with the report as text. */
    fun send(context: Context, report: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Inkvault crash report (build ${BuildConfig.VERSION_CODE})")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        val chooser = Intent.createChooser(send, "Send crash report").apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        markHandled(context)
    }
}
