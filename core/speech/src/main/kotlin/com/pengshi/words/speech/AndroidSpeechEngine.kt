package com.pengshi.words.speech

import android.content.Context
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Offline speech bundled into the main application; no standalone TTS package is required. */
class AndroidSpeechEngine(
    context: Context,
    private val onVoicesChanged: (List<SpeechVoiceOption>) -> Unit = {},
) : SpeechEngine {
    private val applicationContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val downloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtimes = linkedMapOf<String, EmbeddedVoiceRuntime>()
    private val downloadedModels = DownloadedSpeechModels(applicationContext)
    private var externalEngineVoices = emptyList<SpeechVoiceOption>()

    private val optionsByPackage = PengshiVoicePresets.all.associate { preset ->
        preset.packageName to SpeechVoiceOption(
            enginePackageName = preset.packageName,
            engineLabel = preset.label,
            voiceName = preset.packageName.substringAfterLast('.'),
            localeTag = preset.localeTag,
            networkRequired = false,
        )
    }
    private val modelPreferences = applicationContext.getSharedPreferences("speech-models", Context.MODE_PRIVATE)
    private val builtinVoice = optionsByPackage.getValue(PengshiVoicePresets.builtin.packageName)
    private val mutableCatalog = MutableStateFlow(SpeechVoiceCatalog(
        voices = listOf(builtinVoice), enabledVoices = listOf(builtinVoice), selectedVoiceKey = builtinVoice.key,
    ))
    val voiceCatalog: StateFlow<SpeechVoiceCatalog> = mutableCatalog.asStateFlow()
    private val installedEngines = InstalledSpeechEngines(applicationContext, ::acceptInstalledVoices) { error ->
        mutableCatalog.update { it.copy(message = error) }
    }
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var released = false
    private var playbackGeneration = 0L

    init {
        onVoicesChanged(availableVoices())
        refreshVoices()
    }

    private fun runtimeFor(option: SpeechVoiceOption): EmbeddedVoiceRuntime? {
        synchronized(runtimes) { runtimes[option.key] }?.let { return it }
        if (released) return null
        val bundled = option.key == builtinVoice.key
        val modelName = option.voiceName
        val model = if (bundled) EmbeddedVoiceModel.all.first() else null
        val directory = if (bundled) null else downloadedModels.modelDirectory(modelName)
        if (!bundled && !downloadedModels.isInstalled(modelName)) return null
        val config = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
            model = if (bundled) "${model!!.assetDirectory}/${model.modelFileName}"
                else requireNotNull(downloadedModels.modelFile(modelName)).absolutePath,
            tokens = if (bundled) "${model!!.assetDirectory}/tokens.txt" else File(directory, "tokens.txt").absolutePath,
            dataDir = extractSharedEspeakData().absolutePath,
        ), numThreads = 2))
        val tts = if (bundled) OfflineTts(applicationContext.assets, config) else OfflineTts(config = config)
        return EmbeddedVoiceRuntime(tts).also { runtime ->
            synchronized(runtimes) { runtimes[option.key] = runtime }
        }
    }

    private fun reportError(message: String, error: Throwable) {
        android.util.Log.e(TAG, message, error)
        mutableCatalog.update { it.copy(message = message) }
    }

    private fun extractSharedEspeakData(): File {
        val target = File(applicationContext.noBackupFilesDir, "embedded_tts/espeak-ng-data")
        val marker = File(target, ".complete")
        if (!marker.isFile) {
            target.mkdirs()
            copyAssetTree("vits-piper-en_US-ljspeech-medium-int8/espeak-ng-data", target)
            marker.writeText("1")
        }
        return target
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = applicationContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            applicationContext.assets.open(assetPath).use { input -> target.outputStream().use(input::copyTo) }
            return
        }
        target.mkdirs()
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(target, child)) }
    }

    override fun speak(text: String, utteranceId: String, rate: Float) {
        val state = mutableCatalog.value
        val voice = state.enabledVoices.firstOrNull { it.key == state.selectedVoiceKey }
            ?: state.enabledVoices.firstOrNull() ?: builtinVoice
        speakWithVoice(text, voice, utteranceId, rate)
    }

    override fun speakWithVoice(text: String, option: SpeechVoiceOption, utteranceId: String, rate: Float) {
        val clean = text.trim()
        if (clean.isBlank() || released) return
        stop()
        if (option.key != builtinVoice.key && !option.enginePackageName.startsWith("com.pengshi.tts.downloaded.")) {
            installedEngines.speak(clean, option, utteranceId, rate)
            return
        }
        val requestGeneration = synchronized(this) {
            if (released) return
            playbackGeneration += 1
            stopCurrentTrackLocked()
            playbackGeneration
        }
        executor.execute {
            if (!isCurrentGeneration(requestGeneration)) return@execute
            runCatching { runtimeFor(option) }
                .onFailure { reportError("无法加载离线音色，请检查模型文件。", it) }
                .getOrNull()
                ?.let { if (isCurrentGeneration(requestGeneration)) play(it, clean, rate, requestGeneration) }
        }
    }

    override fun speakSequence(texts: List<String>, rate: Float) {
        SpeechTextPolicy.sequenceText(texts)?.let { speak(it, "sequence", rate) }
    }

    private fun play(runtime: EmbeddedVoiceRuntime, text: String, rate: Float, requestGeneration: Long) {
        val audio = runCatching { runtime.tts.generate(text, 0, SpeechSettings.clampRate(rate)) }
            .onFailure { reportError("内置语音生成失败，请稍后重试。", it) }
            .getOrNull() ?: return
        val samples = audio.samples
        val sampleRate = audio.sampleRate
        if (samples.isEmpty() || sampleRate <= 0 || !isCurrentGeneration(requestGeneration)) return
        android.util.Log.i(TAG, "内置音色合成成功，${samples.size} 个采样点")
        val pcm = ShortArray(samples.size) { index -> (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuffer, pcm.size * 2))
            .build()
        synchronized(this) {
            if (released || playbackGeneration != requestGeneration) { track.release(); return }
            currentTrack?.stopSafely()
            currentTrack?.let { runCatching { it.release() } }
            currentTrack = track
        }
        runCatching {
            track.play()
            var offset = 0
            while (offset < pcm.size && isCurrentTrack(track)) {
                val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                if (written < 0) error("AudioTrack.write failed: $written")
                if (written == 0) {
                    Thread.sleep(2L)
                } else {
                    offset += written
                }
            }
            awaitAudioTrackDrain(
                expectedFrames = offset,
                isActive = { isCurrentGeneration(requestGeneration) && isCurrentTrack(track) && track.playState == AudioTrack.PLAYSTATE_PLAYING },
                playbackHeadPosition = { runCatching { track.playbackHeadPosition }.getOrDefault(offset) },
                pause = Thread::sleep,
            )
        }.onFailure { android.util.Log.e(TAG, "内置语音播放失败", it) }
            .also {
                track.stopSafely()
                runCatching { track.release() }
                synchronized(this) { if (currentTrack === track) currentTrack = null }
            }
    }

    private fun isCurrentTrack(track: AudioTrack): Boolean =
        !released && synchronized(this) { currentTrack === track }

    override fun stop() {
        installedEngines.stop()
        synchronized(this) {
            playbackGeneration += 1
            stopCurrentTrackLocked()
        }
    }

    private fun stopCurrentTrackLocked() {
        currentTrack?.stopSafely()
        currentTrack?.let { runCatching { it.release() } }
        currentTrack = null
    }

    private fun isCurrentGeneration(generation: Long): Boolean = synchronized(this) {
        !released && playbackGeneration == generation
    }

    override fun isEnglishVoiceAvailable(): Boolean = !released && availableVoices().isNotEmpty()
    override fun availableVoices(): List<SpeechVoiceOption> = mutableCatalog.value.enabledVoices

    fun refreshVoices() {
        if (released) return
        mutableCatalog.update { it.copy(refreshing = true, message = null) }
        installedEngines.refresh()
    }

    private fun acceptInstalledVoices(voices: List<SpeechVoiceOption>) {
        if (released) return
        externalEngineVoices = voices
        updateVoiceCatalog()
    }

    private fun updateVoiceCatalog() {
        val all = (listOf(builtinVoice) + downloadedModels.installedVoices() + externalEngineVoices).distinctBy { it.key }
        val stored = modelPreferences.getStringSet("enabled", setOf(builtinVoice.key)).orEmpty()
        val enabled = all.filter { it.key in stored }.take(SpeechVoiceCatalog.MAX_ENABLED_VOICES)
            .ifEmpty { listOf(builtinVoice) }
        val selected = modelPreferences.getString("default", builtinVoice.key)
            ?.takeIf { key -> enabled.any { it.key == key } } ?: enabled.first().key
        modelPreferences.edit().putStringSet("enabled", enabled.map { it.key }.toSet())
            .putString("default", selected).apply()
        val current = mutableCatalog.value
        mutableCatalog.value = SpeechVoiceCatalog(all, enabled, selected, false,
            current.message, current.downloadingModel, current.downloadPercent, current.removingModel)
        onVoicesChanged(enabled)
    }

    fun downloadModel(name: String) {
        if (released || name !in SpeechModelDownloads.archiveSha256) return
        if (mutableCatalog.value.removingModel != null) return
        if (mutableCatalog.value.downloadingModel != null) {
            mutableCatalog.update { it.copy(message = "请等待当前模型下载完成。") }
            return
        }
        if (downloadedModels.isInstalled(name)) {
            mutableCatalog.update { it.copy(message = "这个音色已下载，可在模型管理中启用。") }
            return
        }
        mutableCatalog.update { it.copy(downloadingModel = name, downloadPercent = null, message = null) }
        downloadExecutor.execute {
            runCatching {
                downloadedModels.install(name) { percent ->
                    if (!released) mutableCatalog.update { it.copy(downloadPercent = percent) }
                }
            }.onSuccess {
                mainHandler.post {
                    if (released) return@post
                    updateVoiceCatalog()
                    downloadedModels.installedVoices().firstOrNull { it.voiceName == name }?.let { voice ->
                        if (mutableCatalog.value.enabledVoices.size < SpeechVoiceCatalog.MAX_ENABLED_VOICES) {
                            setVoiceEnabled(voice, true)
                        }
                    }
                    mutableCatalog.update { it.copy(downloadingModel = null, downloadPercent = null,
                        message = "模型已保存在本应用中，可直接试听和使用。") }
                }
            }.onFailure { error ->
                mainHandler.post {
                    if (!released) mutableCatalog.update { it.copy(downloadingModel = null, downloadPercent = null,
                        message = "模型下载失败：${error.message ?: "请检查网络后重试"}") }
                }
            }
        }
    }

    fun importModelFolder(tree: Uri) {
        if (released) return
        if (mutableCatalog.value.downloadingModel != null || mutableCatalog.value.removingModel != null) {
            mutableCatalog.update { it.copy(message = "请等待当前模型操作完成。") }
            return
        }
        mutableCatalog.update { it.copy(downloadingModel = "import", downloadPercent = null, message = "正在导入模型…") }
        downloadExecutor.execute {
            runCatching { downloadedModels.importFolder(tree) }
                .onSuccess { name -> mainHandler.post {
                    if (released) return@post
                    updateVoiceCatalog()
                    downloadedModels.installedVoices().firstOrNull { it.voiceName == name }?.let { voice ->
                        if (mutableCatalog.value.enabledVoices.size < SpeechVoiceCatalog.MAX_ENABLED_VOICES) setVoiceEnabled(voice, true)
                    }
                    mutableCatalog.update { it.copy(downloadingModel = null, message = "模型已导入本应用，可直接试听和使用。") }
                } }
                .onFailure { error -> mainHandler.post {
                    if (!released) mutableCatalog.update { it.copy(downloadingModel = null,
                        message = "导入失败：${error.message ?: "请检查模型文件"}") }
                } }
        }
    }

    fun uninstallModel(name: String) {
        if (released || !downloadedModels.isInstalled(name)) return
        if (mutableCatalog.value.downloadingModel != null || mutableCatalog.value.removingModel != null) {
            mutableCatalog.update { it.copy(message = "请等待当前模型操作完成。") }
            return
        }
        if (!downloadedModels.isInstalled(name)) return
        mutableCatalog.update { it.copy(removingModel = name, message = null) }
        stop()
        executor.execute {
            val key = downloadedModels.installedVoices().firstOrNull { it.voiceName == name }?.key
            runCatching {
                key?.let { voiceKey ->
                    synchronized(runtimes) {
                        runtimes.remove(voiceKey)?.tts?.release()
                    }
                }
                downloadedModels.uninstall(name)
            }.onSuccess {
                mainHandler.post {
                    if (released) return@post
                    updateVoiceCatalog()
                    mutableCatalog.update { it.copy(removingModel = null, message = "语音模型已从本应用删除。") }
                }
            }.onFailure { error ->
                mainHandler.post {
                    if (!released) mutableCatalog.update { it.copy(removingModel = null,
                        message = "删除失败：${error.message ?: "请重试"}") }
                }
            }
        }
    }

    fun setVoiceEnabled(option: SpeechVoiceOption, enabled: Boolean) {
        val state = mutableCatalog.value
        if (state.voices.none { it.key == option.key }) return
        val keys = state.enabledVoices.mapTo(linkedSetOf()) { it.key }
        if (enabled && option.key !in keys && keys.size >= SpeechVoiceCatalog.MAX_ENABLED_VOICES) {
            mutableCatalog.update { it.copy(message = "最多启用三个音色，请先关闭一个音色。") }
            return
        }
        if (!enabled && option.key in keys && keys.size == 1) {
            mutableCatalog.update { it.copy(message = "请至少保留一个启用音色。") }
            return
        }
        if (enabled) keys.add(option.key) else keys.remove(option.key)
        val active = state.voices.filter { it.key in keys }
        val selected = state.selectedVoiceKey?.takeIf { it in keys } ?: active.first().key
        stop()
        modelPreferences.edit().putStringSet("enabled", keys).putString("default", selected).apply()
        mutableCatalog.value = state.copy(enabledVoices = active, selectedVoiceKey = selected, message = null)
        onVoicesChanged(active)
    }

    override fun selectVoice(option: SpeechVoiceOption?) {
        val state = mutableCatalog.value
        val voice = state.enabledVoices.firstOrNull { it.key == option?.key } ?: return
        modelPreferences.edit().putString("default", voice.key).apply()
        mutableCatalog.update { it.copy(selectedVoiceKey = voice.key) }
    }

    override fun release() {
        if (released) return
        released = true
        stop()
        installedEngines.release()
        downloadExecutor.shutdownNow()
        // JNI generation cannot be interrupted safely. Release on the same
        // worker after any generation already in progress has returned.
        executor.execute {
            synchronized(runtimes) {
                runtimes.values.forEach { runtime -> runCatching { runtime.tts.release() } }
                runtimes.clear()
            }
        }
        executor.shutdown()
    }

    private data class EmbeddedVoiceRuntime(val tts: OfflineTts)
    private companion object {
        const val TAG = "PengshiEmbeddedSpeech"
        fun AudioTrack.stopSafely() { runCatching { if (playState == AudioTrack.PLAYSTATE_PLAYING) stop() } }
    }

    private data class EmbeddedVoiceModel(
        val preset: SpeechVoicePreset,
        val assetDirectory: String,
        val modelFileName: String,
    ) {
        companion object {
            val all = listOf(
                EmbeddedVoiceModel(PengshiVoicePresets.builtin, "vits-piper-en_US-ljspeech-medium-int8", "en_US-ljspeech-medium.onnx"),
            )
        }
    }
}
