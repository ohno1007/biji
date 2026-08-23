package com.biji.notes.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Vosk on-device ASR. Models live in app-private storage and are
 *  downloaded on demand from alphacephei.com.
 *
 *  libvosk.so 也一样是现下的（见 [VoskNativeLib]）—— 它解压后 8.86 MB，
 *  打进 APK 就是 61.7% 的包体，而不下模型这个功能本来也用不了。
 *
 *  这里原本有个 `init { LibVosk.setLogLevel(...) }`，看着人畜无害，实际上
 *  构造这个类就等于 dlopen 8.86 MB —— 而 VoiceRecognizer 是在首次组合里
 *  构造的，等于每次冷启动都白做一遍。现在所有 native 触碰点都推迟到
 *  [installModel] 和 [start]。 */
class OfflineVoiceRecognizer(private val ctx: Context) {

    /** A selectable Vosk model. */
    data class ModelSpec(
        val id: String,
        val label: String,
        val sizeLabel: String,
        val url: String
    )

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

    val modelDir: File get() = File(ctx.filesDir, "vosk-model").also { it.mkdirs() }

    /**
     * 模型和 native 库都齐，且 native 没在本进程里加载失败过。
     *
     * VoiceRecognizer 拿它决定走离线还是系统识别，UI 每次进设置页也读它，
     * 所以它必须**便宜且无副作用**：只看文件是否存在，绝不触发 dlopen。
     * 加载失败过一次（[VoskNativeLib.broken]）就永久报 false —— 整个门面
     * 自动退回系统语音识别，不用改 VoiceRecognizer 一行。
     */
    val installed: Boolean
        get() = modelPresent && VoskNativeLib.present(ctx) && !VoskNativeLib.broken

    /**
     * 声学模型的文件在。**和 [installed] 分开是必须的**：老用户升上来时模型
     * 是全的、只缺这次才改成现下的 .so，[installed] 会是 false —— 而设置页的
     * 「卸载」按钮以前挂在 installed 上，于是那个最大 1.3 GB 的模型在 UI 上
     * 变成了删不掉的东西。腾空间这件事只该看模型在不在。
     */
    val modelPresent: Boolean
        get() = File(modelDir, "conf").exists()

    /** 模型齐了但 native 库还没下（或被清掉了）—— 设置页据此提示「补齐」。 */
    val nativeMissing: Boolean
        get() = !VoskNativeLib.present(ctx)

    /**
     * 只补 [VoskNativeLib]，**不碰模型**。
     *
     * 这条路径以前藏在 [installModel] 里：进门看到「模型全 && 库缺」就补完库
     * 直接 return true。问题是 installModel 是带 url 参数的 —— 用户在设置里
     * 选了「中文大模型」再按下载，走到那个分支就会补完 2.87 MB 的库、报告
     * 「已安装」，而实际装着的还是原来那个小模型，**一个字都不提**。
     * 而且设置页的默认选中项恰好就是大模型，所以这是升级用户的第一条路径，
     * 不是边角情况。现在拆成两个按钮，各自只做字面上那件事。
     */
    suspend fun repairNativeLib(): Boolean = withContext(Dispatchers.IO) {
        try {
            _install.value = InstallProgress.Downloading(0L, 0L)
            val libErr = VoskNativeLib.ensure(ctx) { done, total ->
                _install.value = InstallProgress.Downloading(done, total)
            }
            if (libErr != null) {
                _install.value = InstallProgress.Failed("离线语音库下载失败：$libErr")
                return@withContext false
            }
            VoskNativeLib.load(ctx)?.let {
                _install.value = InstallProgress.Failed(
                    "离线引擎无法加载，继续使用系统语音识别：$it"
                )
                return@withContext false
            }
            val bytes = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            _install.value = InstallProgress.Done(bytes)
            true
        } catch (e: Exception) {
            _install.value = InstallProgress.Failed(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** Available Vosk Chinese models from alphacephei.com/vosk/models.
     *  cn-0.22 is the large one (much higher accuracy, slower download). */
    val availableModels: List<ModelSpec> = listOf(
        ModelSpec(
            id = "cn-small",
            label = "中文 · 小模型",
            sizeLabel = "42 MB",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
        ),
        ModelSpec(
            id = "cn-large",
            label = "中文 · 大模型（更准）",
            sizeLabel = "1.3 GB",
            url = "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip"
        ),
        ModelSpec(
            id = "en-small",
            label = "English · small",
            sizeLabel = "40 MB",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        )
    )

    val defaultModel: ModelSpec get() = availableModels[1] // 中文大模型, 更准

    suspend fun installModel(url: String = defaultModel.url): Boolean =
        withContext(Dispatchers.IO) {
            try {
                _install.value = InstallProgress.Downloading(0L, 0L)

                // .so 先于模型下载，两个理由：
                // 一是它只有 2.87 MB，失败得快，别让用户等完 1.3 GB 才发现装不上；
                // 二是这一步在 modelDir.deleteRecursively() **之前**，所以下载或
                // 加载失败时，用户原来那份能用的模型是完好的。
                val libErr = VoskNativeLib.ensure(ctx) { done, total ->
                    _install.value = InstallProgress.Downloading(done, total)
                }
                if (libErr != null) {
                    _install.value = InstallProgress.Failed("离线语音库下载失败：$libErr")
                    return@withContext false
                }
                // 装的时候就把 dlopen 试掉。能不能从 filesDir 加载 .so 取决于
                // targetSdk 28 的 SELinux 豁免，这事在设备上才见分晓；现在试，
                // 失败就把 installed 钉成 false，用户永远走不进离线分支，
                // 而不是等到按下麦克风才炸。
                val loadErr = VoskNativeLib.load(ctx)
                if (loadErr != null) {
                    _install.value = InstallProgress.Failed(
                        "离线引擎无法加载，继续使用系统语音识别：$loadErr"
                    )
                    return@withContext false
                }

                // 这里曾经有一条「模型全 && 库缺 → 只补库就 return true」的近路。
                // 它把「补 2.87 MB 的库」和「装用户刚选的那个模型」混成了同一个
                // 按钮：升级用户在设置里选中大模型按下载，会被告知已安装，装着
                // 的却还是旧的小模型。现在那件事有自己的入口 [repairNativeLib]，
                // 这个函数只做一件事 —— 把 [url] 指的模型装上。
                _install.value = InstallProgress.Downloading(0L, 0L)
                modelDir.deleteRecursively()
                modelDir.mkdirs()
                val zipFile = File(ctx.filesDir, "vosk-model.zip")
                // zipFile.delete() 原来只写在成功路径的末尾。大模型是 1.3 GB，
                // 下到 90% 断网 / 磁盘满 / 用户退出，异常一抛，这一坨就永远留在
                // filesDir 里 —— 没有任何人再碰它，用户也看不到它。用 try/finally
                // 兜住：删的是临时归档，成功失败都该删。
                try {
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
                            val buf = ByteArray(64 * 1024)
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
                    // Vosk zips wrap everything under `vosk-model-X/...`;
                    // strip that prefix so model files land at modelDir root.
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
                            if (stripped.isBlank()) { zin.closeEntry(); continue }
                            val target = File(modelDir, stripped)
                            if (e.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { os ->
                                    val buf = ByteArray(64 * 1024)
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
                    val totalBytes = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    _install.value = InstallProgress.Done(totalBytes)
                    true
                } finally {
                    runCatching { zipFile.delete() }
                }
            } catch (e: Exception) {
                _install.value = InstallProgress.Failed(e.message ?: e.javaClass.simpleName)
                false
            }
        }

    suspend fun uninstallModel() = withContext(Dispatchers.IO) {
        runCatching { stop() }
        runCatching { loadedModel?.close() }
        loadedModel = null
        modelDir.deleteRecursively()
        modelDir.mkdirs()
        // 用户按「卸载」是为了腾空间，8.86 MB 的 .so 留着没意义。已经 dlopen
        // 的映射不会因为删文件失效，本进程照常，重启后才真的没了 —— 无所谓，
        // 那时模型也没了。
        VoskNativeLib.remove(ctx)
        _install.value = InstallProgress.Idle
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (!installed) {
            _state.value = VoiceState.Error("尚未下载语音模型")
            return@withContext
        }
        // 正常情况下这在 installModel 里已经成功过，直接返回缓存结果。
        // 走到失败分支说明装完之后环境变了（换机恢复、系统升级、文件被清理），
        // 这一次只能报错；但 broken 一置位 installed 就是 false，
        // 下一次按麦克风 VoiceRecognizer 会自己走系统语音识别。
        VoskNativeLib.load(ctx)?.let {
            _state.value = VoiceState.Error("离线引擎加载失败（$it），已切回系统语音识别，请再按一次")
            return@withContext
        }
        runCatching {
            val model = loadedModel ?: Model(modelDir.absolutePath).also { loadedModel = it }
            val recognizer = Recognizer(model, 16000f)
            val service = SpeechService(recognizer, 16000f)
            service.startListening(listener)
            speechService = service
            _state.value = VoiceState.Listening("")
        }.onFailure {
            _state.value = VoiceState.Error(it.message ?: it.javaClass.simpleName)
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

    fun reset() { _state.value = VoiceState.Idle }

    fun destroy() {
        runCatching { speechService?.shutdown() }
        speechService = null
        runCatching { loadedModel?.close() }
        loadedModel = null
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = field(hypothesis, "partial")
            if (text.isNotBlank()) _state.value = VoiceState.Listening(text)
        }
        override fun onResult(hypothesis: String?) {
            val text = field(hypothesis, "text")
            if (text.isNotBlank()) _state.value = VoiceState.Result(text)
        }
        override fun onFinalResult(hypothesis: String?) {
            val text = field(hypothesis, "text")
            if (text.isNotBlank()) _state.value = VoiceState.Result(text)
        }
        override fun onError(exception: Exception?) {
            _state.value = VoiceState.Error(exception?.message ?: "识别失败")
        }
        override fun onTimeout() {
            _state.value = VoiceState.Error("没听到声音")
        }
    }

    private fun field(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return runCatching { JSONObject(json).optString(key, "") }
            .getOrDefault("")
            .replace(" ", "")
    }
}
