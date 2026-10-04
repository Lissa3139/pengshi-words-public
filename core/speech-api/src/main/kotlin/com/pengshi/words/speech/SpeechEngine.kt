package com.pengshi.words.speech

data class SpeechVoiceOption(
    val enginePackageName: String,
    val engineLabel: String,
    val voiceName: String,
    val localeTag: String,
    val networkRequired: Boolean,
) {
    val key: String get() = "$enginePackageName:$voiceName"
}

/** A preset included in the app's own assets. */
data class SpeechVoicePreset(val packageName: String, val label: String, val localeTag: String)

object PengshiVoicePresets {
    val builtin = SpeechVoicePreset("com.pengshi.tts.ljspeech", "LJSpeech · 美式女声（内置）", "en-US")
    val all = listOf(builtin)
}

data class SpeechVoiceCatalog(
    val voices: List<SpeechVoiceOption> = emptyList(),
    val enabledVoices: List<SpeechVoiceOption> = emptyList(),
    val selectedVoiceKey: String? = null,
    val refreshing: Boolean = false,
    val message: String? = null,
    val downloadingModel: String? = null,
    val downloadPercent: Int? = null,
    val removingModel: String? = null,
) {
    companion object { const val MAX_ENABLED_VOICES = 3 }
}

object SpeechModelDownloads {
    const val guideUrl = "https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html"
    val models = listOf(
        "Lessac · 美式女声" to "vits-piper-en_US-lessac-medium",
        "Ryan · 美式男声" to "vits-piper-en_US-ryan-high",
        "Jenny (Dioco) · 英式女声" to "vits-piper-en_GB-jenny_dioco-medium",
    )
    val archiveSha256 = mapOf(
        "vits-piper-en_US-lessac-medium" to "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e",
        "vits-piper-en_US-ryan-high" to "6a71edf4d308b9cb2eaeadc8d1f3c6bf96120ecb7fe52c29a2b6e139c59760ed",
        "vits-piper-en_GB-jenny_dioco-medium" to "a0888024569bafbefc05a4b48ddf8419d8dbbf3205f4af37cf7c6f1a87cc20c5",
    )
    fun desktopArchiveUrl(model: String) =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$model.tar.bz2"
}

object SpeechTextPolicy {
    fun firstSentence(texts: List<String>): List<String> =
        texts.map(String::trim).firstOrNull(String::isNotBlank)?.let(::listOf).orEmpty()

    fun sequenceText(texts: List<String>): String? = texts
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" ") { text ->
            if (text.lastOrNull() in listOf('.', '!', '?')) text else "$text."
        }
        .takeIf(String::isNotBlank)
}

interface SpeechEngine {
    fun speak(text: String, utteranceId: String, rate: Float)

    fun speakSequence(texts: List<String>, rate: Float) {
        texts.filter(String::isNotBlank).forEachIndexed { index, text ->
            speak(text, "sequence-$index", rate)
        }
    }

    fun speakWithVoice(text: String, option: SpeechVoiceOption, utteranceId: String, rate: Float) =
        speak(text, utteranceId, rate)

    fun stop()
    fun isEnglishVoiceAvailable(): Boolean
    fun availableVoices(): List<SpeechVoiceOption> = emptyList()
    fun selectVoice(option: SpeechVoiceOption?) = Unit
    fun release()
}
