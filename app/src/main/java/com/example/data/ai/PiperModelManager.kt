/**
 * Manages downloading, extracting, importing, and deleting local neural voice models
 * (Kokoro ONNX & Piper VITS). Supports shared voice folders, free storage checks,
 * Tar Slip security guards, and offline file import via Uri.
 */
package com.example.data.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class PiperModelManager(private val context: Context) {

    val voicesDir: File
        get() = File(context.filesDir, "piper_voices").apply { mkdirs() }

    fun getVoiceModelDir(voice: PiperVoice): File {
        return File(voicesDir, voice.folderName)
    }

    fun isVoiceDownloaded(voice: PiperVoice): Boolean {
        val voiceFolder = getVoiceModelDir(voice)
        val modelFile = File(voiceFolder, voice.modelFilename)
        val tokensFile = File(voiceFolder, voice.tokensFilename)
        val espeakDir = File(voiceFolder, "espeak-ng-data")
        val baseChecks = modelFile.exists() && modelFile.length() > 1024 * 1024 && // At least 1MB
               tokensFile.exists() && tokensFile.length() > 0 &&
               espeakDir.exists() && espeakDir.isDirectory && (espeakDir.list()?.isNotEmpty() ?: false)
               
        return if (voice.isKokoro) {
            val voicesFile = File(voiceFolder, voice.voicesFilename)
            baseChecks && voicesFile.exists() && voicesFile.length() > 0
        } else {
            baseChecks
        }
    }

    suspend fun downloadAndExtractVoice(
        voice: PiperVoice,
        onProgress: (Int) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        // Free space check before starting download
        val requiredBytes = voice.sizeMb.toLong() * 1024L * 1024L * 2L
        val freeBytes = context.filesDir.usableSpace
        if (freeBytes < requiredBytes) {
            val reqMb = voice.sizeMb * 2
            val freeMb = freeBytes / (1024 * 1024)
            return@withContext Result.failure(
                Exception("Not enough disk space. $reqMb MB required ($voice.sizeMb MB download + extraction), but only $freeMb MB is available.")
            )
        }

        val voiceFolder = getVoiceModelDir(voice)
        val tempTarBz2File = File(context.cacheDir, "${voice.folderName}.tar.bz2")
        
        try {
            // Step 1: Download .tar.bz2 to cache with progress (0% - 50%)
            var urlString = voice.tarBz2Url
            var connection: HttpURLConnection
            var responseCode: Int
            var redirectCount = 0
            val maxRedirects = 5

            while (true) {
                val url = URL(urlString)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true
                connection.connect()

                responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || 
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM || 
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                    responseCode == 307 || responseCode == 308) {
                    
                    redirectCount++
                    if (redirectCount > maxRedirects) {
                        return@withContext Result.failure(Exception("Too many redirects"))
                    }
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (newUrl != null) {
                        urlString = newUrl
                        continue
                    }
                }
                break
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("NeuralTtsDownload", "HTTP error for voice ${voice.id}: code=$responseCode, url=$urlString")
                return@withContext Result.failure(
                    Exception("Failed to download voice: Server returned HTTP $responseCode")
                )
            }

            val totalSize = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(tempTarBz2File).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var lastPercent = 0
                    
                    while (true) {
                        val len = input.read(buffer)
                        if (len == -1) break
                        output.write(buffer, 0, len)
                        bytesRead += len
                        
                        if (totalSize > 0) {
                            val percent = ((bytesRead * 50) / totalSize).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }

            // Step 2: Extract .tar.bz2 directly into context.filesDir/piper_voices (50% - 100%)
            onProgress(55)
            voiceFolder.mkdirs()

            val canonicalVoicesDir = voicesDir.canonicalPath

            FileInputStream(tempTarBz2File).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    BZip2CompressorInputStream(bis).use { bzIn ->
                        TarArchiveInputStream(bzIn).use { tarIn ->
                            var entry: TarArchiveEntry? = tarIn.nextEntry
                            val buffer = ByteArray(8192)
                            
                            while (entry != null) {
                                val targetFile = File(voicesDir, entry.name)
                                // Tar Slip Security Guard
                                if (!targetFile.canonicalPath.startsWith(canonicalVoicesDir + File.separator)) {
                                    throw SecurityException("Tar entry '${entry.name}' attempts directory traversal.")
                                }

                                if (entry.isDirectory) {
                                    targetFile.mkdirs()
                                } else {
                                    targetFile.parentFile?.mkdirs()
                                    FileOutputStream(targetFile).use { fos ->
                                        while (true) {
                                            val len = tarIn.read(buffer)
                                            if (len == -1) break
                                            fos.write(buffer, 0, len)
                                        }
                                    }
                                }
                                entry = tarIn.nextEntry
                            }
                        }
                    }
                }
            }

            // Validate extracted files
            if (!isVoiceDownloaded(voice)) {
                throw Exception("Extracted files are incomplete or corrupted.")
            }

            onProgress(100)
            Result.success(Unit)
        } catch (e: Exception) {
            voiceFolder.deleteRecursively()
            Log.e("NeuralTtsDownload", "Download/extraction failed for voice ${voice.id}", e)
            Result.failure(e)
        } finally {
            if (tempTarBz2File.exists()) {
                tempTarBz2File.delete()
            }
        }
    }

    /**
     * Installs a voice pack from a .tar.bz2 the user already has on their device. Same extraction
     * path as the download, including the Tar Slip guard.
     */
    suspend fun importVoiceArchive(
        uri: Uri,
        voice: PiperVoice,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val requiredBytes = voice.sizeMb.toLong() * 1024L * 1024L * 2L
        val freeBytes = context.filesDir.usableSpace
        if (freeBytes < requiredBytes) {
            val reqMb = voice.sizeMb * 2
            val freeMb = freeBytes / (1024 * 1024)
            return@withContext Result.failure(
                Exception("Not enough disk space. $reqMb MB required, but only $freeMb MB is available.")
            )
        }

        val voiceFolder = getVoiceModelDir(voice)
        val canonicalVoicesDir = voicesDir.canonicalPath

        try {
            onProgress(10)
            voiceFolder.mkdirs()

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(Exception("Unable to open selected archive file."))

            inputStream.use { rawIn ->
                BufferedInputStream(rawIn).use { bis ->
                    BZip2CompressorInputStream(bis).use { bzIn ->
                        TarArchiveInputStream(bzIn).use { tarIn ->
                            var entry: TarArchiveEntry? = tarIn.nextEntry
                            val buffer = ByteArray(8192)
                            var extractedCount = 0

                            while (entry != null) {
                                val targetFile = File(voicesDir, entry.name)
                                // Tar Slip Security Guard
                                if (!targetFile.canonicalPath.startsWith(canonicalVoicesDir + File.separator)) {
                                    throw SecurityException("Tar entry '${entry.name}' attempts directory traversal.")
                                }

                                if (entry.isDirectory) {
                                    targetFile.mkdirs()
                                } else {
                                    targetFile.parentFile?.mkdirs()
                                    FileOutputStream(targetFile).use { fos ->
                                        while (true) {
                                            val len = tarIn.read(buffer)
                                            if (len == -1) break
                                            fos.write(buffer, 0, len)
                                        }
                                    }
                                }
                                extractedCount++
                                val progress = (10 + (extractedCount % 85)).coerceAtMost(95)
                                onProgress(progress)
                                entry = tarIn.nextEntry
                            }
                        }
                    }
                }
            }

            // Verify expected model file exists and is non-empty inside folder
            val modelFile = File(voiceFolder, voice.modelFilename)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                voiceFolder.deleteRecursively()
                return@withContext Result.failure(
                    Exception("Archive extracted, but '${voice.modelFilename}' was missing or empty.")
                )
            }

            if (!isVoiceDownloaded(voice)) {
                voiceFolder.deleteRecursively()
                return@withContext Result.failure(
                    Exception("Extracted archive did not contain all required files for ${voice.name}.")
                )
            }

            onProgress(100)
            Result.success(Unit)
        } catch (e: Exception) {
            voiceFolder.deleteRecursively()
            Log.e("NeuralTtsImport", "Import failed for voice ${voice.id}", e)
            Result.failure(e)
        }
    }

    fun deleteVoice(voice: PiperVoice): Boolean {
        val sharers = PiperVoiceCatalog.ALL_VOICES.count { it.folderName == voice.folderName }
        if (sharers > 1) return false
        return getVoiceModelDir(voice).deleteRecursively()
    }

    /**
     * Deletes every file inside the folder that holds [voice] (e.g. `kokoro-en-v0_19`).
     * Returns true if the folder existed and was deleted successfully.
     */
    fun deleteSharedVoiceGroup(voice: PiperVoice): Boolean {
        return try {
            val modelDir = File(voicesDir, voice.folderName)
            if (modelDir.exists()) modelDir.deleteRecursively() else false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun voicesSharingFolder(voice: PiperVoice): List<PiperVoice> =
        PiperVoiceCatalog.ALL_VOICES.filter { it.folderName == voice.folderName && it.id != voice.id }
}
