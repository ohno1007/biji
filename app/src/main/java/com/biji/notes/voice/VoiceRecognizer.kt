package com.biji.notes.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val partial: String) : VoiceState
    data class Result(val text: String) : VoiceState
    data class Error(val message: String) : VoiceState
}

/**
 * Wrapper around Android's built-in [SpeechRecognizer]. Uses the device's
 * on-device speech model when available (Android 12+ has free offline
 * recognition on most OEMs); falls back to Google's cloud service otherwise.
 *
 * We expose a single [state] flow plus [start] / [cancel] commands; the UI
 * consumes Listening (with partial transcript) and presents a confirm sheet
 * once a final Result arrives.
 */
class VoiceRecognizer(private val ctx: Context) {

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    fun available(): Boolean {
        // On API 31+ on-device recognition can be present even when the
        // regular service isn't, so check both.
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) return true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }
                .getOrDefault(false)
                .let { if (it) return true }
        }
        return false
    }

    fun start(languageTag: String = Locale.getDefault().toLanguageTag()) {
        if (recognizer == null) {
            recognizer = createBestRecognizer().apply {
                setRecognitionListener(listener)
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
        runCatching { recognizer?.startListening(intent) }
            .onFailure { _state.value = VoiceState.Error(it.message ?: "无法启动语音识别") }
    }

    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        runCatching { recognizer?.cancel() }
        _state.value = VoiceState.Idle
    }

    fun reset() { _state.value = VoiceState.Idle }

    fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
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

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {
            _state.value = VoiceState.Listening("")
        }
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
                    // 这条不是 biji 自己缺权限——biji 在 manifest 里已经声
                    // 明 RECORD_AUDIO，用户也授予了。它实际上是系统语
                    // 音识别服务本身没拿到麦克风权限，常见于：① 设备
                    // 没安装 Google 语音服务；② 系统语音助手没设；
                    // ③ AOSP / 去 Google 化 ROM。提示用户走真正能解决
                    // 的路径，而不是再去开自己已经开过的权限。
                    "系统语音识别服务没拿到麦克风权限。biji 这边权限是开的——问题在于设备的默认语音助手（系统设置 → 应用 → 默认应用 → 数字助理 / 语音助手）。可以换个识别服务或直接打字。"
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
