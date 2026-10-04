package com.pengshi.words.desktop.speech

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.pengshi.words.speech.PengshiVoicePresets
import com.pengshi.words.speech.SpeechEngine
import com.pengshi.words.speech.SpeechSettings
import com.pengshi.words.speech.SpeechTextPolicy
import com.pengshi.words.speech.SpeechVoiceCatalog
import com.pengshi.words.speech.SpeechVoiceOption
import com.pengshi.words.speech.SpeechVoicePreset
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

class EmbeddedWindowsSpeechEngine internal constructor(
    private val resources: DesktopSpeechResources = ClasspathDesktopSpeechResources(defaultModelRoot()),
    private val settings: WindowsSpeechSettings? = null,
    private val externalModelsRoot: Path = defaultModelRoot().resolve("external"),
    private val runtimeFactory: DesktopTtsRuntimeFactory = SherpaDesktopTtsRuntimeFactory,
    private val audioOutput: DesktopPcmOutput = JavaSoundDesktopPcmOutput(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pengshi-offline-speech").apply { isDaemon = true }
    },
) : SpeechEngine {
    private val lock = Any()
    private val runtimeLock = Any()
    private val builtinVoice = PengshiVoicePresets.builtin.let { preset ->
        SpeechVoiceOption(preset.packageName, preset.label, "ljspeech", preset.localeTag, false)
    }
    private var externalModels: Map<String, DesktopExternalModel> = emptyMap()
    var voiceCatalog by mutableStateOf(SpeechVoiceCatalog(listOf(builtinVoice), listOf(builtinVoice), builtinVoice.key))
        private set
    val modelInstallDirectory: Path get() = externalModelsRoot
    @Volatile private var generation = 0L
    @Volatile private var released = false
    private var loadedVoiceKey: String? = null
    private var runtime: DesktopTtsRuntime? = null

    init { refreshVoices() }

    override fun speak(text: String, utteranceId: String, rate: Float) {
        val catalog = voiceCatalog
        val voice = catalog.enabledVoices.firstOrNull { it.key == catalog.selectedVoiceKey }
            ?: catalog.enabledVoices.firstOrNull() ?: builtinVoice
        speakWithVoice(text, voice, utteranceId, rate)
    }

    override fun speakSequence(texts: List<String>, rate: Float) {
        SpeechTextPolicy.sequenceText(texts)?.let { speak(it, "sequence", rate) }
    }

    override fun speakWithVoice(text: String, option: SpeechVoiceOption, utteranceId: String, rate: Float) {
        val clean = text.trim()
        if (clean.isBlank() || voiceCatalog.voices.none { it.key == option.key }) return
        val requestGeneration = synchronized(lock) {
            if (released) return
            generation += 1
            audioOutput.stop()
            generation
        }
        executor.execute {
            if (!isCurrent(requestGeneration)) return@execute
            runCatching {
                val selectedRuntime = runtimeFor(option)
                val audio = selectedRuntime.generate(clean, SpeechSettings.clampRate(rate))
                if (audio.samples.isNotEmpty() && audio.sampleRate > 0 && isCurrent(requestGeneration)) {
                    audioOutput.play(audio) { isCurrent(requestGeneration) }
                }
            }.onFailure { error ->
                voiceCatalog = voiceCatalog.copy(message = "音色 ${option.engineLabel} 朗读失败：${error.message ?: "模型不可用"}")
                System.err.println("离线英语语音生成/播放失败 (${option.engineLabel}): ${error.message}")
            }
        }
    }

    private fun runtimeFor(option: SpeechVoiceOption): DesktopTtsRuntime = synchronized(runtimeLock) {
        if (loadedVoiceKey == option.key) runtime?.let { return it }
        runtime?.close()
        runtime = null
        loadedVoiceKey = null
        val model = if (option.key == builtinVoice.key) {
            val preset = PengshiVoicePresets.builtin
            val spec = VoiceModel.forPreset(preset)
            DesktopExternalModel(option, resources.modelDirectory(preset), spec.modelFile)
        } else externalModels[option.key] ?: error("语音模型已移除，请刷新音色")
        val espeakDirectory = resources.espeakDataDirectory()
        runtimeFactory.create(model.modelFile, model.directory, espeakDirectory).also {
            runtime = it
            loadedVoiceKey = option.key
        }
    }

    override fun stop() {
        synchronized(lock) { generation += 1 }
        audioOutput.stop()
    }

    override fun selectVoice(option: SpeechVoiceOption?) {
        option?.takeIf { candidate -> voiceCatalog.enabledVoices.any { it.key == candidate.key } }?.let {
            voiceCatalog = voiceCatalog.copy(selectedVoiceKey = it.key, message = null)
            settings?.saveSelectedVoice(it.key)
        }
    }

    fun restoreVoiceKey(voiceKey: String?) {
        val selected = voiceCatalog.enabledVoices.firstOrNull { it.key == voiceKey } ?: voiceCatalog.enabledVoices.first()
        voiceCatalog = voiceCatalog.copy(selectedVoiceKey = selected.key)
    }

    val selectedVoiceKey: String get() = voiceCatalog.selectedVoiceKey ?: builtinVoice.key
    override fun availableVoices(): List<SpeechVoiceOption> = voiceCatalog.enabledVoices
    override fun isEnglishVoiceAvailable(): Boolean = !released && voiceCatalog.enabledVoices.isNotEmpty()

    fun refreshVoices() {
        if (released) return
        val found = runCatching { scanExternalModels() }.getOrElse { error ->
            voiceCatalog = voiceCatalog.copy(message = "扫描语音模型失败：${error.message ?: "无法读取模型目录"}")
            return
        }
        externalModels = found.associateBy { it.option.key }
        val all = listOf(builtinVoice) + found.map { it.option }
        val savedKeys = settings?.enabledVoiceKeys.orEmpty()
        val priorKeys = voiceCatalog.enabledVoices.mapTo(linkedSetOf()) { it.key }
        val requested = if (savedKeys.isEmpty()) priorKeys else savedKeys
        val enabled = all.filter { it.key in requested }.take(SpeechVoiceCatalog.MAX_ENABLED_VOICES)
            .ifEmpty { listOf(builtinVoice) }
        val selected = settings?.selectedVoiceKey?.takeIf { key -> enabled.any { it.key == key } }
            ?: voiceCatalog.selectedVoiceKey?.takeIf { key -> enabled.any { it.key == key } }
            ?: enabled.first().key
        voiceCatalog = SpeechVoiceCatalog(all, enabled, selected)
        settings?.saveEnabledVoices(enabled.mapTo(linkedSetOf()) { it.key })
    }

    fun setVoiceEnabled(option: SpeechVoiceOption, enabled: Boolean) {
        val catalog = voiceCatalog
        if (catalog.voices.none { it.key == option.key }) return
        val keys = catalog.enabledVoices.mapTo(linkedSetOf()) { it.key }
        if (enabled && option.key !in keys && keys.size >= SpeechVoiceCatalog.MAX_ENABLED_VOICES) {
            voiceCatalog = catalog.copy(message = "最多启用三个音色，请先关闭一个。")
            return
        }
        if (!enabled && option.key in keys && keys.size == 1) {
            voiceCatalog = catalog.copy(message = "请至少保留一个启用音色。")
            return
        }
        if (enabled) keys.add(option.key) else keys.remove(option.key)
        val active = catalog.voices.filter { it.key in keys }
        val selected = catalog.selectedVoiceKey?.takeIf { it in keys } ?: active.first().key
        stop()
        voiceCatalog = catalog.copy(enabledVoices = active, selectedVoiceKey = selected, message = null)
        settings?.saveEnabledVoices(keys)
        settings?.saveSelectedVoice(selected)
    }

    private fun scanExternalModels(): List<DesktopExternalModel> {
        Files.createDirectories(externalModelsRoot)
        return Files.list(externalModelsRoot).use { stream ->
            stream.toList().asSequence().filter(Files::isDirectory).mapNotNull { folder ->
                val name = folder.fileName.toString()
                if (!name.startsWith("vits-piper-en_", ignoreCase = true) ||
                    !Files.isRegularFile(folder.resolve("tokens.txt"))) return@mapNotNull null
                val model = Files.list(folder).use { files ->
                    files.toList().firstOrNull { Files.isRegularFile(it) && it.fileName.toString().endsWith(".onnx") }
                } ?: return@mapNotNull null
                val label = when {
                    name.contains("lessac", true) -> "Lessac · 美式女声"
                    name.contains("ryan", true) -> "Ryan · 美式男声"
                    name.contains("jenny", true) -> "Jenny (Dioco) · 英式女声"
                    else -> name.removePrefix("vits-piper-")
                }
                val option = SpeechVoiceOption("desktop-piper:$name", label, name,
                    if (name.contains("en_GB", true)) "en-GB" else "en-US", false)
                DesktopExternalModel(option, folder, model.fileName.toString())
            }.sortedBy { it.option.engineLabel }.toList()
        }
    }

    override fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            generation += 1
            audioOutput.stop()
        }
        executor.shutdownNow()
        synchronized(runtimeLock) {
            runtime?.close()
            runtime = null
            loadedVoiceKey = null
        }
    }

    private fun isCurrent(requestGeneration: Long): Boolean = !released && generation == requestGeneration

    private companion object {
        fun defaultModelRoot(): Path {
            val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Path::of)
            return (localAppData ?: Path.of(System.getProperty("user.home"))).resolve("PengshiWordsOpenSource/speech-models")
        }
    }
}

private data class DesktopExternalModel(
    val option: SpeechVoiceOption,
    val directory: Path,
    val modelFile: String,
)

internal data class DesktopPcm(val samples: FloatArray, val sampleRate: Int)

internal interface DesktopTtsRuntime : Closeable {
    fun generate(text: String, rate: Float): DesktopPcm
}

internal fun interface DesktopTtsRuntimeFactory {
    fun create(modelFile: String, modelDirectory: Path, espeakDataDirectory: Path): DesktopTtsRuntime
}

internal interface DesktopSpeechResources {
    fun modelDirectory(preset: SpeechVoicePreset): Path
    fun espeakDataDirectory(): Path
}

internal interface DesktopPcmOutput {
    fun play(audio: DesktopPcm, shouldContinue: () -> Boolean)
    fun stop()
}

internal class ClasspathDesktopSpeechResources(private val root: Path) : DesktopSpeechResources {
    override fun modelDirectory(preset: SpeechVoicePreset): Path {
        val model = VoiceModel.forPreset(preset)
        val target = root.resolve("sherpa-onnx-1.13.8").resolve(model.directory)
        val modelFile = target.resolve(model.modelFile)
        val tokensFile = target.resolve("tokens.txt")
        if (Files.isRegularFile(modelFile) && Files.isRegularFile(tokensFile)) return target
        Files.createDirectories(target)
        copyResource("/${model.directory}/${model.modelFile}", modelFile)
        copyResource("/${model.directory}/${model.modelFile}.json", target.resolve("${model.modelFile}.json"))
        copyResource("/${model.directory}/tokens.txt", tokensFile)
        return target
    }

    override fun espeakDataDirectory(): Path {
        val target = root.resolve("sherpa-onnx-1.13.8/espeak-ng-data")
        if (Files.isRegularFile(target.resolve("phontab")) && Files.isRegularFile(target.resolve("en_dict"))) return target
        Files.createDirectories(target.parent)
        val zipResource = requireNotNull(javaClass.getResourceAsStream("/espeak-ng-data.zip")) {
            "Bundled eSpeak data was not found in the application resources"
        }
        val temporary = Files.createTempDirectory(target.parent, "espeak-ng-data-")
        try {
            ZipInputStream(zipResource).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val output = temporary.resolve(entry.name).normalize()
                    require(output.startsWith(temporary)) { "Invalid bundled eSpeak archive entry" }
                    if (entry.isDirectory) Files.createDirectories(output) else {
                        Files.createDirectories(output.parent)
                        Files.newOutputStream(output).use(zip::copyTo)
                    }
                    zip.closeEntry()
                }
            }
            val extracted = temporary.resolve("espeak-ng-data")
            require(Files.isRegularFile(extracted.resolve("phontab"))) { "Bundled eSpeak data is incomplete" }
            if (Files.exists(target)) target.toFile().deleteRecursively()
            Files.move(extracted, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.toFile().deleteRecursively()
        }
        return target
    }

    private fun copyResource(name: String, target: Path) {
        val stream = requireNotNull(javaClass.getResourceAsStream(name)) { "Missing bundled voice resource $name" }
        Files.createDirectories(target.parent)
        stream.use { input -> Files.newOutputStream(target).use(input::copyTo) }
    }
}

private data class VoiceModel(val directory: String, val modelFile: String) {
    companion object {
        fun forPreset(preset: SpeechVoicePreset): VoiceModel = when (preset.packageName) {
            PengshiVoicePresets.builtin.packageName -> VoiceModel("vits-piper-en_US-ljspeech-medium-int8", "en_US-ljspeech-medium.onnx")
            else -> error("No bundled model for ${preset.packageName}")
        }
    }
}

private object SherpaDesktopTtsRuntimeFactory : DesktopTtsRuntimeFactory {
    override fun create(modelFile: String, modelDirectory: Path, espeakDataDirectory: Path): DesktopTtsRuntime {
        SherpaWindowsNativeLibraries.load(DesktopSpeechModelPaths.userRoot())
        val vits = OfflineTtsVitsModelConfig.builder()
            .setModel(modelDirectory.resolve(modelFile).toString())
            .setTokens(modelDirectory.resolve("tokens.txt").toString())
            .setDataDir(espeakDataDirectory.toString())
            .build()
        val modelConfig = OfflineTtsModelConfig.builder()
            .setVits(vits)
            .setNumThreads(2)
            .setProvider("cpu")
            .build()
        val tts = OfflineTts(OfflineTtsConfig.builder().setModel(modelConfig).build())
        return object : DesktopTtsRuntime {
            override fun generate(text: String, rate: Float): DesktopPcm {
                val generated = tts.generate(text, 0, rate)
                return DesktopPcm(generated.samples, generated.sampleRate)
            }

            override fun close() = tts.release()
        }
    }
}

/** Keep native libraries writable even when the models live beside a protected installation. */
internal object DesktopSpeechModelPaths {
    fun userRoot(): Path {
        val local = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Path::of)
        return (local ?: Path.of(System.getProperty("user.home"))).resolve("PengshiWordsOpenSource/speech-models")
    }

    fun defaultExternal(): Path {
        val legacy = userRoot().resolve("external")
        val hasEarlierModels = runCatching { Files.list(legacy).use { entries ->
            entries.anyMatch { Files.isDirectory(it) && it.fileName.toString().startsWith("vits-piper-en_", true) }
        } }.getOrDefault(false)
        if (hasEarlierModels) return legacy
        val codeSource = EmbeddedWindowsSpeechEngine::class.java.protectionDomain.codeSource?.location
        val applicationJar = runCatching { codeSource?.toURI()?.let(Path::of) }.getOrNull()
        val installRoot = applicationJar?.parent?.takeIf { it.fileName?.toString() == "app" }?.parent
        val candidate = installRoot?.resolve("speech-models/external") ?: return legacy
        return if (runCatching {
            Files.createDirectories(candidate)
            Files.createTempFile(candidate, ".write-check-", ".tmp").also(Files::deleteIfExists)
            true
        }.getOrDefault(false)) candidate else legacy
    }
}

private object SherpaWindowsNativeLibraries {
    private val lock = Any()
    @Volatile private var loaded = false

    fun load(root: Path) = synchronized(lock) {
        if (loaded) return@synchronized
        val nativeDirectory = root.resolve("native")
        Files.createDirectories(nativeDirectory)
        val runtime = extractNative("sherpa-onnx/native/win-x64/onnxruntime.dll", nativeDirectory.resolve("onnxruntime.dll"))
        val jni = extractNative("sherpa-onnx/native/win-x64/sherpa-onnx-jni.dll", nativeDirectory.resolve("sherpa-onnx-jni.dll"))
        System.load(runtime.toAbsolutePath().toString())
        System.load(jni.toAbsolutePath().toString())
        loaded = true
    }

    private fun extractNative(resourcePath: String, target: Path): Path {
        if (Files.isRegularFile(target) && Files.size(target) > 0L) return target
        val input = requireNotNull(javaClass.classLoader.getResourceAsStream(resourcePath)) { "Missing Sherpa native library $resourcePath" }
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        input.use { source -> Files.newOutputStream(temporary).use(source::copyTo) }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }
}

private class JavaSoundDesktopPcmOutput : DesktopPcmOutput {
    @Volatile private var currentLine: SourceDataLine? = null

    override fun play(audio: DesktopPcm, shouldContinue: () -> Boolean) {
        val format = AudioFormat(audio.sampleRate.toFloat(), 16, 1, true, false)
        val line = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
        line.open(format, 8192)
        currentLine = line
        try {
            line.start()
            val samplesPerChunk = 2048
            var offset = 0
            val bytes = ByteArray(samplesPerChunk * 2)
            while (offset < audio.samples.size && shouldContinue()) {
                val sampleCount = minOf(samplesPerChunk, audio.samples.size - offset)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                repeat(sampleCount) { index ->
                    val sample = audio.samples[offset + index].coerceIn(-1f, 1f)
                    buffer.putShort((sample * Short.MAX_VALUE).toInt().toShort())
                }
                val written = line.write(bytes, 0, sampleCount * 2)
                if (written <= 0) break
                offset += written / 2
            }
            if (shouldContinue()) line.drain()
        } finally {
            if (currentLine === line) currentLine = null
            runCatching { line.stop() }
            line.close()
        }
    }

    override fun stop() {
        val line = currentLine ?: return
        currentLine = null
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }
}
