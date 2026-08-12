/**
 * SherpaOnnx offline TTS engine wrapper using C++ JNI bindings.
 *
 * Implements model caching by folder, multi-core execution, chunking for low latency,
 * priming of chapter openings, and race-free generation control.
 */
package com.example.data.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class SherpaOnnxTtsEngine(private val context: Context) {

    val modelManager = PiperModelManager(context)
    private val engineLock = Any()
    private var offlineTts: OfflineTts? = null

    // Keyed on the model folder, not the voice id: every natural voice shares one model file and
    // differs only by speaker id, so switching voices must not reload 82 MB from disk.
    private var loadedModelFolder: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var isPlaying = false

    /**
     * Increments on every speak() call. A job may only clear isPlaying if it is still the current
     * generation — otherwise a finishing job wipes the flag that the job replacing it just set,
     * and the next chapter never starts.
     */
    private val playbackGeneration = AtomicInteger(0)

    var selectedVoiceId: String = PiperVoiceCatalog.AMY_LOW.id
    var selectedSpeakerId: Int = 0

    private data class SpeechChunk(val paragraphIndex: Int, val text: String)
    private data class Rendered(val samples: FloatArray, val sampleRate: Int)

    private var primedText: String? = null
    private var primedSamples: FloatArray? = null
    private var primedSampleRate: Int = 0

    // Ensure the current voice engine is loaded and initialized
    private fun initEngine(voice: PiperVoice): OfflineTts? {
        synchronized(engineLock) {
            if (offlineTts != null && loadedModelFolder == voice.folderName) {
                return offlineTts
            }

            // Release previous engine
            releaseEngine()

            if (!modelManager.isVoiceDownloaded(voice)) {
                return null
            }

            try {
                val voiceDir = modelManager.getVoiceModelDir(voice)
                val modelPath = File(voiceDir, voice.modelFilename).absolutePath
                val tokensPath = File(voiceDir, voice.tokensFilename).absolutePath
                val dataDir = File(voiceDir, "espeak-ng-data").absolutePath
                val numCores = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

                val modelConfig = if (voice.isKokoro) {
                    val voicesPath = File(voiceDir, voice.voicesFilename).absolutePath
                    val kokoroConfig = OfflineTtsKokoroModelConfig(
                        model = modelPath,
                        voices = voicesPath,
                        tokens = tokensPath,
                        dataDir = dataDir,
                        lengthScale = 1.0f
                    )
                    OfflineTtsModelConfig(
                        kokoro = kokoroConfig,
                        numThreads = numCores,
                        provider = "cpu"
                    )
                } else {
                    val vitsConfig = OfflineTtsVitsModelConfig(
                        model = modelPath,
                        tokens = tokensPath,
                        dataDir = dataDir,
                        lengthScale = 1.0f // Speed is controlled at generation time
                    )
                    OfflineTtsModelConfig(
                        vits = vitsConfig,
                        numThreads = numCores,
                        provider = "cpu"
                    )
                }

                val config = OfflineTtsConfig(
                    model = modelConfig,
                    maxNumSentences = 1
                )

                offlineTts = OfflineTts(config = config)
                loadedModelFolder = voice.folderName
                return offlineTts
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }
    }

    private fun releaseEngine() {
        synchronized(engineLock) {
            try {
                offlineTts?.release()
            } catch (e: Exception) {
                // ignore
            }
            offlineTts = null
            loadedModelFolder = null
        }
    }

    fun isModelDownloaded(): Boolean {
        val voice = PiperVoiceCatalog.getVoiceById(selectedVoiceId)
        return modelManager.isVoiceDownloaded(voice)
    }

    suspend fun downloadModel(
        onProgress: (Int) -> Unit,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val voice = PiperVoiceCatalog.getVoiceById(selectedVoiceId)
        val result = modelManager.downloadAndExtractVoice(voice, onProgress)
        if (result.isSuccess) {
            onSuccess()
        } else {
            Log.e("NeuralTtsDownload", "downloadModel failed: ${result.exceptionOrNull()?.message}", result.exceptionOrNull())
            onFailure(result.exceptionOrNull()?.message ?: "Failed to download model")
        }
    }

    suspend fun importModel(
        uri: Uri,
        onProgress: (Int) -> Unit,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val voice = PiperVoiceCatalog.getVoiceById(selectedVoiceId)
        val result = modelManager.importVoiceArchive(uri, voice, onProgress)
        if (result.isSuccess) {
            onSuccess()
        } else {
            Log.e("NeuralTtsImport", "importModel failed: ${result.exceptionOrNull()?.message}", result.exceptionOrNull())
            onFailure(result.exceptionOrNull()?.message ?: "Failed to import model archive")
        }
    }

    fun deleteModel(): Boolean {
        val voice = PiperVoiceCatalog.getVoiceById(selectedVoiceId)
        releaseEngine()
        return modelManager.deleteVoice(voice)
    }

    /** Releases the loaded model and deletes the shared voice pack from storage. */
    fun deleteVoicePack(voice: PiperVoice): Boolean {
        releaseEngine()
        return modelManager.deleteSharedVoiceGroup(voice)
    }

    fun initOnnx() {
        // Automatically initialized on speak, or pre-warmed here
        val voice = PiperVoiceCatalog.getVoiceById(selectedVoiceId)
        initEngine(voice)
    }

    /**
     * Splits paragraphs into pieces for synthesis.
     *
     * The first piece is deliberately small: it alone determines how long the user waits after
     * pressing play. Later pieces are larger, which keeps the audio smooth and reduces the number
     * of separate generation calls.
     */
    private fun buildChunks(
        paragraphs: List<String>,
        firstChunkChars: Int = 90,
        maxChars: Int = 260
    ): List<SpeechChunk> {
        val chunks = ArrayList<SpeechChunk>()
        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                // The very first chunk of the whole passage is kept short.
                val limit = if (chunks.isEmpty()) firstChunkChars else maxChars

                if (remaining.length <= limit) {
                    chunks.add(SpeechChunk(paragraphIndex, remaining))
                    break
                }

                var cut = -1
                for (mark in listOf(". ", "! ", "? ", "。", "！", "？", "”", "\" ")) {
                    val at = remaining.lastIndexOf(mark, limit)
                    if (at > cut) cut = at + mark.length
                }
                if (cut <= 0) {
                    cut = remaining.lastIndexOf(' ', limit)
                    if (cut <= 0) cut = limit
                }

                chunks.add(SpeechChunk(paragraphIndex, remaining.substring(0, cut).trim()))
                remaining = remaining.substring(cut).trim()
            }
        }
        return chunks.filter { it.text.isNotEmpty() }
    }

    /**
     * Renders the opening chunk of a chapter ahead of time so that pressing play is immediate.
     * Does nothing if that exact text is already prepared.
     */
    fun primeOpening(text: String, speed: Float) {
        val paragraphs = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return
        val first = buildChunks(paragraphs).firstOrNull() ?: return

        synchronized(engineLock) {
            if (primedText == first.text && primedSamples != null) return
        }

        scope.launch {
            val voice = PiperVoiceCatalog.getVoiceById(selectedVoiceId)
            val engine = initEngine(voice) ?: return@launch
            val sid = if (voice.isKokoro) voice.kokoroSpeakerId else selectedSpeakerId
            val lengthScale = if (speed > 0) 1.0f / speed else 1.0f
            val generated = try {
                synchronized(engineLock) {
                    engine.generate(text = first.text, sid = sid, speed = lengthScale)
                }
            } catch (e: Exception) {
                Log.e("SherpaTts", "Priming failed", e)
                null
            } ?: return@launch

            synchronized(engineLock) {
                primedText = first.text
                primedSamples = generated.samples
                primedSampleRate = generated.sampleRate
            }
        }
    }

    /** Returns and clears the prepared audio when it matches the text about to be spoken. */
    private fun takePrimed(text: String): Rendered? {
        synchronized(engineLock) {
            val samples = primedSamples
            if (primedText == text && samples != null) {
                val result = Rendered(samples, primedSampleRate)
                primedText = null
                primedSamples = null
                return result
            }
            return null
        }
    }

    fun speak(
        text: String,
        speed: Float,
        pitch: Float,
        onStart: (Int) -> Unit,
        onDone: () -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        val myGeneration = playbackGeneration.incrementAndGet()
        val oldJob = activeJob
        isPlaying = false
        oldJob?.cancel()

        isPlaying = true
        activeJob = scope.launch {
            try {
                oldJob?.join()
            } catch (e: Exception) {
                // ignore
            }

            val voice = PiperVoiceCatalog.getVoiceById(selectedVoiceId)
            val tts = initEngine(voice)
            if (tts == null) {
                if (playbackGeneration.get() == myGeneration) {
                    isPlaying = false
                }
                withContext(Dispatchers.Main) {
                    onError?.invoke("Model not downloaded or failed to load")
                }
                return@launch
            }

            val rawParagraphs = text.split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val chunks = buildChunks(rawParagraphs)

            try {
                var lastParagraphIndex = -1

                for (chunk in chunks) {
                    if (!isPlaying || !isActive || playbackGeneration.get() != myGeneration) break

                    if (chunk.paragraphIndex != lastParagraphIndex) {
                        lastParagraphIndex = chunk.paragraphIndex
                        withContext(Dispatchers.Main) {
                            onStart(chunk.paragraphIndex)
                        }
                    }

                    // Check for primed opening chunk
                    val primed = takePrimed(chunk.text)
                    val samples: FloatArray
                    val sampleRate: Int

                    if (primed != null) {
                        samples = primed.samples
                        sampleRate = primed.sampleRate
                    } else {
                        val lengthScale = if (speed > 0) 1.0f / speed else 1.0f
                        val audio = synchronized(engineLock) {
                            tts.generate(
                                text = chunk.text,
                                sid = if (voice.isKokoro) voice.kokoroSpeakerId else selectedSpeakerId,
                                speed = lengthScale
                            )
                        }
                        samples = audio.samples
                        sampleRate = audio.sampleRate
                    }

                    if (!isPlaying || !isActive || playbackGeneration.get() != myGeneration) break

                    playSamples(samples, sampleRate, myGeneration)
                }

                if (isPlaying && isActive && playbackGeneration.get() == myGeneration) {
                    withContext(Dispatchers.Main) {
                        onDone()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (isActive && playbackGeneration.get() == myGeneration) {
                    withContext(Dispatchers.Main) {
                        onError?.invoke(e.message ?: "Speech generation failed")
                    }
                }
            } finally {
                if (playbackGeneration.get() == myGeneration) {
                    isPlaying = false
                }
            }
        }
    }

    private suspend fun playSamples(
        samples: FloatArray,
        sampleRate: Int,
        myGeneration: Int
    ) = withContext(Dispatchers.IO) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        val bufferSize = maxOf(minBufferSize, samples.size * 4)

        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )

        synchronized(this@SherpaOnnxTtsEngine) {
            audioTrack = track
        }

        try {
            track.play()

            val chunkSize = 8192
            var offset = 0
            while (offset < samples.size && isPlaying && isActive && playbackGeneration.get() == myGeneration) {
                val len = minOf(chunkSize, samples.size - offset)
                track.write(samples, offset, len, AudioTrack.WRITE_BLOCKING)
                offset += len
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                // ignore
            }
            synchronized(this@SherpaOnnxTtsEngine) {
                if (audioTrack == track) {
                    audioTrack = null
                }
            }
        }
    }

    fun stop() {
        isPlaying = false
        playbackGeneration.incrementAndGet()
        activeJob?.cancel()
        activeJob = null

        synchronized(this) {
            try {
                audioTrack?.stop()
                audioTrack?.flush()
                audioTrack?.release()
                audioTrack = null
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun shutdown() {
        stop()
        releaseEngine()
    }
}
