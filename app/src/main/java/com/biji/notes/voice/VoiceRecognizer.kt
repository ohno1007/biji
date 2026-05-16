package com.biji.notes.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val partial: String) : VoiceState
    data class Result(val text: String) : VoiceState
    data class Error(val message: String) : VoiceState
}

/**
 * Voice façade. Prefers the on-device Vosk recogniser when the
 * Mandarin model is installed (works without Google Speech Services,
 * works offline) and falls back to Android's [SpeechRecognizer] —
 * which itself prefers `createOnDeviceSpeechRecognizer` on API 31+ —
 * when Vosk isn't ready.
 *
 * The same [state] flow surface fits both back-ends so the chat UI
 * doesn't need to care which engine actually heard the user.
 */
class VoiceRecognizer(private val ctx: Context) {

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    val offline: OfflineVoiceRecognizer = OfflineVoiceRecognizer(ctx)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fwdJob: Job? = null
    private var systemRecognizer: SpeechRecognizer? = null

    /** True if *something* — offline or system — can recognise. */
    fun available(): Boolean {
        if (offline.installed) return true
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) return true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }
                .getOrDefault(false)
                .let { if (it) return true }
        }
        return false
    }

    /** Which engine [start] will use right now. */
    fun engineLabel(): String =
        if (offline.installed) "biji 离线 (Vosk)"
        else "系统语音识别"

    fun start(languageTag: String = Locale.getDefault().toLanguageTag()) {
        if (offline.installed) {
            startOffline()
        } else {
            startSystem(languageTag)
        }
    }

    private fun startOffline() {
        // Mirror Vosk's internal state into our public flow so callers
        // can use a single VoiceState surface regardless of backend.
        fwdJob?.cancel()
        fwdJob = scope.launch {
            offline.state.collect { _state.value = it }
        }
        scope.launch { offline.start() }
    }

    private fun startSystem(languageTag: String) {
        if (systemRecognizer == null) {
            systemRecognizer = createBestRecognizer().apply {
                setRecognitionListener(systemListener)
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        _state.value = VoiceState.Listening("")
        runCatching { systemRecognizer?.startListening(intent) }
            .onFailure { _state.value = VoiceState.Error(it.message ?: "无法启动语音识别") }
    }

    fun stop() {
        if (offline.installed) offline.stop()
        runCatching { systemRecognizer?.stopListening() }
    }

    fun cancel() {
        if (offline.installed) offline.cancel()
        runCatching { systemRecognizer?.cancel() }
        _state.value = VoiceState.Idle
    }

    fun reset() { _state.value = VoiceState.Idle }

    fun destroy() {
        fwdJob?.cancel()
        runCatching { offline.destroy() }
        runCatching { systemRecognizer?.destroy() }
        systemRecognizer = null
    }

    /** Prefer the on-device recogniser when the OEM ships one — it's
     *  free, doesn't need network, and side-steps the "you need to
     *  enable Google Assistant" trap that catches users on AOSP /
     *  de-Googled builds. Falls back to the regular service. */
    private fun createBestRecognizer(): SpeechRecognizer {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val onDevice = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            }.getOrNull()
            if (onDevice != null) return onDevice
        }
        return SpeechRecognizer.createSpeechRecognizer(ctx)
    }

    private val systemListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { _state.value = VoiceState.Listening("") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (_state.value is VoiceState.Listening) {
                _state.value = VoiceState.Listening(text)
            }
        }
        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            _state.value = if (text.isBlank()) VoiceState.Error("没识别到内容") else VoiceState.Result(text)
        }
        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "系统语音识别服务没拿到麦克风权限。biji 这边权限是开的——问题在于设备的默认语音助手。建议在「设置 → 语音」里下载离线 Vosk 模型，或直接打字。"
                SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再试一次"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙，稍后重试"
                SpeechRecognizer.ERROR_SERVER -> "服务端错误"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
                else -> "识别失败 ($error)"
            }
            _state.value = VoiceState.Error(msg)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
