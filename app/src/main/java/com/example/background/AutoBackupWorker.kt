package com.example.background

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.NovelHoarderApp
import com.example.util.BackupRestoreManager
import java.util.concurrent.TimeUnit

/**
 * Nightly backup into a folder the user picked with the system file picker. Picking a Google
 * Drive folder there gives off-phone backups without any Google Cloud / sign-in setup, because
 * the Drive app does the uploading. Keeps the newest [KEEP] backups and deletes older ones.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = prefs(applicationContext)
        val tree = prefs.getString(KEY_TREE, null)?.let(Uri::parse) ?: return Result.success()
        if (!prefs.getBoolean(KEY_ENABLED, false) && tags.none { it == TAG_NOW }) return Result.success()

        var created: Uri? = null
        return try {
            val resolver = applicationContext.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date())
            val name = "$PREFIX$stamp.zip"
            val target = DocumentsContract.createDocument(resolver, parent, "application/zip", name)
                ?: throw java.io.IOException("Could not create a file in the backup folder")
            created = target

            val repository = NovelHoarderApp.getContainer(applicationContext as Application).repository
            val books = BackupRestoreManager.createFullBackupZip(applicationContext, repository, target).getOrThrow()
            prune(tree)
            prefs.edit()
                .putLong(KEY_LAST_OK, System.currentTimeMillis())
                .putString(KEY_LAST_MSG, "$name · $books novels")
                .remove(KEY_LAST_ERROR)
                .apply()
            Result.success()
        } catch (e: Exception) {
            // A half-written zip would count as one of the 7 kept backups and push out good ones.
            created?.let { runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, it) } }
            prefs.edit().putString(KEY_LAST_ERROR, e.message ?: e::class.simpleName).apply()
            // The folder may have been removed or access revoked; retrying won't help that, but a
            // transient Drive/provider error might clear up.
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private fun prune(tree: Uri) {
        val resolver = applicationContext.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val ours = mutableListOf<Pair<String, String>>() // documentId to name
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(PREFIX) && name.endsWith(".zip")) ours += c.getString(0) to name
            }
        }
        // Names embed a sortable timestamp, so name order is age order.
        ours.sortedByDescending { it.second }.drop(KEEP).forEach { (docId, _) ->
            runCatching {
                DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId))
            }
        }
    }

    companion object {
        const val KEEP = 7
        private const val PREFIX = "NovelHoarder-backup-"
        private const val WORK_NAME = "auto_backup_daily"
        private const val WORK_NOW = "auto_backup_now"
        private const val TAG_NOW = "auto_backup_manual"
        const val KEY_TREE = "tree_uri"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_OK = "last_ok"
        const val KEY_LAST_MSG = "last_msg"
        const val KEY_LAST_ERROR = "last_error"

        fun prefs(context: Context) = context.getSharedPreferences("auto_backup", Context.MODE_PRIVATE)

        /** Stores the chosen folder with a permission that survives reboots. */
        fun setFolder(context: Context, tree: Uri) {
            context.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs(context).edit().putString(KEY_TREE, tree.toString()).apply()
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
            schedule(context)
        }

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val p = prefs(context)
            if (!p.getBoolean(KEY_ENABLED, false) || p.getString(KEY_TREE, null) == null) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun backupNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoBackupWorker>().addTag(TAG_NOW).build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.REPLACE, request)
        }

        fun folderLabel(context: Context): String? {
            val tree = prefs(context).getString(KEY_TREE, null)?.let(Uri::parse) ?: return null
            return runCatching {
                val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: tree.lastPathSegment
        }
    }
}
