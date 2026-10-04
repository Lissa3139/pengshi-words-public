package com.pengshi.words.desktop.speech

import com.pengshi.words.speech.SpeechEngine
import com.pengshi.words.speech.SpeechSettings
import com.pengshi.words.speech.SpeechVoiceOption
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WindowsSpeechEngine internal constructor(
    private val voiceCatalog: VoiceCatalog = WindowsVoiceCatalog(),
    private val sapi: SapiProcessBoundary = WindowsSapiProcessBoundary(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pengshi-windows-speech").apply { isDaemon = true }
    },
) : SpeechEngine {
    private val lock = Any()
    private val voices: List<SpeechVoiceOption> by lazy(voiceCatalog::availableVoices)
    private var selectedVoiceName: String? = null
    private var playbackGeneration = 0L
    private var currentSpeech: SapiSpeechPlayback? = null
    private var released = false

    override fun speak(text: String, utteranceId: String, rate: Float) {
        play(listOf(text), rate)
    }

    override fun speakSequence(texts: List<String>, rate: Float) {
        play(texts, rate)
    }

    override fun stop() {
        synchronized(lock) { cancelCurrentPlayback() }
    }

    override fun isEnglishVoiceAvailable(): Boolean = voices.isNotEmpty()

    override fun availableVoices(): List<SpeechVoiceOption> = voices

    override fun selectVoice(option: SpeechVoiceOption?) {
        synchronized(lock) {
            cancelCurrentPlayback()
            selectedVoiceName = option?.takeIf { candidate -> voices.any { it.key == candidate.key } }?.voiceName
        }
    }

    fun restoreVoiceKey(voiceKey: String?) {
        synchronized(lock) {
            selectedVoiceName = voiceKey
                ?.takeIf { it.startsWith("windows-sapi:") }
                ?.substringAfter(':')
                ?.takeIf(String::isNotBlank)
                ?.takeIf { candidate -> voices.any { it.voiceName == candidate } }
        }
    }

    val selectedVoiceKey: String?
        get() = synchronized(lock) {
            selectedVoiceName?.let { voiceName -> "$WINDOWS_SAPI_ENGINE:$voiceName" }
        }

    override fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            cancelCurrentPlayback()
        }
        executor.shutdownNow()
        sapi.close()
    }

    private fun play(texts: List<String>, rate: Float) {
        val sequence = texts.map(String::trim).filter(String::isNotBlank)
        if (sequence.isEmpty()) return
        val clampedRate = SpeechSettings.clampRate(rate)
        val voiceName: String?
        val generation: Long
        synchronized(lock) {
            if (released) return
            cancelCurrentPlayback()
            generation = ++playbackGeneration
            // The catalog only exposes English voices. Use the first one when
            // the user has not explicitly selected a voice, so Windows' global
            // default (often a Chinese voice) cannot silently take over.
            voiceName = selectedVoiceName
                ?.takeIf { candidate -> voices.any { it.voiceName == candidate } }
                ?: voices.firstOrNull()?.voiceName
        }
        executor.execute {
            for (text in sequence) {
                val speech = synchronized(lock) {
                    if (released || generation != playbackGeneration) {
                        return@execute
                    }
                    val startedSpeech = sapi.startSpeech(voiceName, clampedRate, text) ?: return@execute
                    currentSpeech = startedSpeech
                    startedSpeech
                }
                speech.awaitCompletion()
                synchronized(lock) {
                    if (currentSpeech === speech) currentSpeech = null
                    if (released || generation != playbackGeneration) return@execute
                }
            }
        }
    }

    private fun cancelCurrentPlayback() {
        playbackGeneration++
        currentSpeech?.cancel()
        currentSpeech = null
    }

    private companion object {
        const val WINDOWS_SAPI_ENGINE = "windows-sapi"
    }
}

class WindowsSpeechSettings(private val path: Path) {
    val selectedVoiceKey: String?
        get() = properties().getProperty(VOICE_KEY)?.takeIf(String::isNotBlank)

    val enabledVoiceKeys: Set<String>
        get() = properties().getProperty(ENABLED_VOICES_KEY).orEmpty()
            .split(',').map(String::trim).filter(String::isNotBlank).toSet()

    val speechRate: Float
        get() = SpeechSettings.clampRate(properties().getProperty(RATE_KEY)?.toFloatOrNull() ?: DEFAULT_RATE)

    fun save(voice: SpeechVoiceOption?, rate: Float) = save(voice?.key, rate)

    fun save(voiceKey: String?, rate: Float) {
        val properties = properties()
        if (voiceKey.isNullOrBlank()) properties.remove(VOICE_KEY) else properties.setProperty(VOICE_KEY, voiceKey)
        properties.setProperty(RATE_KEY, SpeechSettings.clampRate(rate).toString())
        write(properties)
    }

    fun saveSelectedVoice(voiceKey: String?) {
        val properties = properties()
        if (voiceKey.isNullOrBlank()) properties.remove(VOICE_KEY) else properties.setProperty(VOICE_KEY, voiceKey)
        write(properties)
    }

    fun saveEnabledVoices(keys: Set<String>) {
        val properties = properties()
        properties.setProperty(ENABLED_VOICES_KEY, keys.joinToString(","))
        write(properties)
    }

    private fun write(properties: Properties) {
        path.parent?.let(Files::createDirectories)
        Files.newOutputStream(path).use { properties.store(it, "Pengshi Words desktop speech settings") }
    }

    private fun properties(): Properties = Properties().apply {
        if (Files.exists(path)) runCatching { Files.newInputStream(path).use(::load) }
    }

    private companion object {
        const val VOICE_KEY = "speech.voice.key"
        const val ENABLED_VOICES_KEY = "speech.voices.enabled"
        const val RATE_KEY = "speech.rate"
        const val DEFAULT_RATE = 1.0f
    }
}
