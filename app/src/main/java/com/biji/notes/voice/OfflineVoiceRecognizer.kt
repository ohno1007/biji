package com.biji.notes.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Fully on-device speech recognition powered by Vosk (a Kaldi-based
 * streaming ASR with small per-language models, typically 40–80 MB
 * for Mandarin). Once the model is unpacked into app-private
 * storage, recognition runs locally — no Google Speech Services, no
 * network, no third-party app required.
 *
 * The model isn't bundled in the APK (would double the install
 * size). Instead [installModel] downloads + extracts a zip from a
 * configurable URL the first time the user asks for offline ASR.
 *
 * Streams partial transcripts as the user speaks (so the voice sheet
 * can echo what's being heard live), and emits a final result when
 * the recognizer detects end-of-utterance.
 */
class OfflineVoiceRecognizer(private val ctx: Context) {

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
    }

    sealed interface InstallProgress {
        data object Idle : InstallProgress
        data class Downloading(val bytes: Long, val total: Long) : InstallProgress
        data class Extracting(val current: Int, val total: Int) : InstallProgress
        data class Done(val sizeBytes: Long) : InstallProgress
        data class Failed(val message: String) : InstallProgress
    }

    private val _install = MutableStateFlow<InstallProgress>(InstallProgress.Idle)
    val install: StateFlow<InstallProgress> = _install.asStateFlow()

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var speechService: SpeechService? = null
    private var loadedModel: Model? = null

    /** Where the unpacked model lives. The presence of `conf/` is our
     *  installed-ness check — every Vosk model ships a conf dir. */
    val modelDir: File get() = File(ctx.filesDir, "vosk-model").also { it.mkdirs() }
    val installed: Boolean
        get() = File(modelDir, "conf").exists()

    /** Default model: vosk-small-cn — Chinese, ~42 MB unzipped, the
     *  sweet spot of size vs. accuracy. The exact URL is configurable
     *  via [installModel]'s [url] argument. */
    val defaultModelUrl: String =
        "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"

    /**
     * Download + extract the Vosk model zip. Returns true on success.
     * Cancelling the coroutine aborts mid-stream — the partial files
     * stay on disk and the next install overwrites them.
     */
    suspend fun installModel(url: String = defaultModelUrl): Boolean =
        withContext(Dispatchers.IO) {
            try {
                _install.value = InstallProgress.Downloading(0L, 0L)
                modelDir.deleteRecursively()
                modelDir.mkdirs()
                val zipFile = File(ctx.filesDir, "vosk-model.zip")
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    _install.value = InstallProgress.Failed("HTTP ${conn.responseCode}")
                    return@withContext false
                }
                val total = conn.contentLengthLong.coerceAtLeast(0L)
                zipFile.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(32 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            read += n
                            _install.value = InstallProgress.Downloading(read, total)
                        }
                    }
                }

                // Vosk zips ship as `vosk-model-X/...`. We strip that
                // top folder so [modelDir]/conf etc. land at the root.
                val entries = mutableListOf<java.util.zip.ZipEntry>()
                ZipInputStream(zipFile.inputStream()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        entries += e
                        zin.closeEntry()
                    }
                }
                ZipInputStream(zipFile.inputStream()).use { zin ->
                    var idx = 0
                    while (true) {
                        val e = zin.nextEntry ?: break
                        idx++
                        _install.value = InstallProgress.Extracting(idx, entries.size)
                        val stripped = e.name.substringAfter('/', e.name)
                        if (stripped.isBlank()) {
                            zin.closeEntry()
                            continue
                        }
                        val target = File(modelDir, stripped)
                        if (e.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { os ->
                                val buf = ByteArray(16 * 1024)
                                while (true) {
                                    val n = zin.read(buf)
                                    if (n <= 0) break
                                    os.write(buf, 0, n)
                                }
                            }
                        }
                        zin.closeEntry()
                    }
                }
                zipFile.delete()
                val totalBytes = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                _install.value = InstallProgress.Done(totalBytes)
                true
            } catch (e: Exception) {
                _install.value = InstallProgress.Failed(e.message ?: e.javaClass.simpleName)
                false
            }
        }

    /** Remove the installed model and free disk. */
    suspend fun uninstallModel() = withContext(Dispatchers.IO) {
        runCatching { stop() }
        runCatching { loadedModel?.close() }
        loadedModel = null
        modelDir.deleteRecursively()
        modelDir.mkdirs()
        _install.value = InstallProgress.Idle
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (!installed) {
            _state.value = VoiceState.Error("尚未下载语音模型")
            return@withContext
        }
        runCatching {
            // Load model lazily on first start, keep it around for
            // subsequent sessions — loading a 40 MB Kaldi model costs
            // a second or two so we don't want to redo it per utterance.
            val model = loadedModel ?: Model(modelDir.absolutePath).also { loadedModel = it }
            val recognizer = Recognizer(model, 16000f)
            val service = SpeechService(recognizer, 16000f)
            service.startListening(listener)
            speechService = service
            _state.value = VoiceState.Listening("")
        }.onFailure {
            _state.value = VoiceState.Error("启动识别失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun stop() {
        runCatching { speechService?.stop() }
        speechService = null
    }

    fun cancel() {
        runCatching { speechService?.cancel() }
        speechService = null
        _state.value = VoiceState.Idle
    }

    fun reset() {
        _state.value = VoiceState.Idle
    }

    fun destroy() {
        runCatching { speechService?.shutdown() }
        speechService = null
        runCatching { loadedModel?.close() }
        loadedModel = null
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = extractField(hypothesis, "partial")
            if (text.isNotBlank()) _state.value = VoiceState.Listening(text)
        }

        override fun onResult(hypothesis: String?) {
            val text = extractField(hypothesis, "text")
            if (text.isNotBlank()) _state.value = VoiceState.Result(text)
        }

        override fun onFinalResult(hypothesis: String?) {
            val text = extractField(hypothesis, "text")
            if (text.isNotBlank()) _state.value = VoiceState.Result(text)
        }

        override fun onError(exception: Exception?) {
            _state.value = VoiceState.Error(
                exception?.message ?: "识别失败"
            )
        }

        override fun onTimeout() {
            _state.value = VoiceState.Error("超时，没听到声音")
        }
    }

    private fun extractField(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return runCatching { JSONObject(json).optString(key, "") }
            .getOrDefault("")
            // Vosk's Chinese model returns words separated by spaces;
            // strip them so the result lines up with what the user
            // expects to see in chat.
            .replace(" ", "")
    }
}
