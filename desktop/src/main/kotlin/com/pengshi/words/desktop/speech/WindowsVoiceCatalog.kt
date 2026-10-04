package com.pengshi.words.desktop.speech

import com.pengshi.words.speech.SpeechVoiceOption
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class WindowsVoiceCatalog(
    private val platformName: String = System.getProperty("os.name").orEmpty(),
) : VoiceCatalog {
    private val sapi: SapiProcessBoundary = WindowsSapiProcessBoundary(platformName)
    override fun availableVoices(): List<SpeechVoiceOption> {
        if (!platformName.startsWith("Windows", ignoreCase = true)) return emptyList()
        return runCatching { sapi.listEnglishVoices() }.getOrDefault(emptyList())
    }
}

internal interface VoiceCatalog {
    fun availableVoices(): List<SpeechVoiceOption>
}

internal interface SapiProcessBoundary : AutoCloseable {
    fun listEnglishVoices(): List<SpeechVoiceOption>
    fun startSpeech(voiceName: String?, rate: Float, text: String): SapiSpeechPlayback?
}

internal interface SapiSpeechPlayback {
    fun awaitCompletion()
    fun cancel()
}

internal class WindowsSapiProcessBoundary(
    private val platformName: String = System.getProperty("os.name").orEmpty(),
) : SapiProcessBoundary {
    override fun listEnglishVoices(): List<SpeechVoiceOption> {
        if (!isWindows()) return emptyList()
        val process = startPowerShell(listVoicesScript()) ?: return emptyList()
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return emptyList()
        }
        if (process.exitValue() != 0) return emptyList()
        return process.inputStream.bufferedReader().use { reader ->
            reader.readLines().mapNotNull(::toVoiceOption)
        }
    }

    override fun startSpeech(voiceName: String?, rate: Float, text: String): SapiSpeechPlayback? {
        if (!isWindows() || text.isBlank()) return null
        val scriptPath = runCatching {
            Files.createTempFile("pengshi-sapi-speech-", ".ps1").also { path ->
                Files.writeString(path, speechScript())
            }
        }.getOrNull() ?: return null
        val process = runCatching {
            ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-STA",
                "-File",
                scriptPath.toString(),
                voiceName.orEmpty(),
                SpeechRate.toSapiRate(rate).toString(),
                text,
            ).redirectErrorStream(true).start()
        }.getOrElse {
            runCatching { Files.deleteIfExists(scriptPath) }
            return null
        }
        return SapiSpeechProcess(process) { runCatching { Files.deleteIfExists(scriptPath) } }
    }

    override fun close() = Unit

    private fun startPowerShell(script: String): Process? = runCatching {
        ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            script,
        ).redirectErrorStream(true).start()
    }.getOrNull()

    private fun isWindows(): Boolean = platformName.startsWith("Windows", ignoreCase = true)

    private fun toVoiceOption(line: String): SpeechVoiceOption? {
        val fields = line.split('\t', limit = 2)
        val name = fields.getOrNull(0)?.trim().orEmpty()
        val locale = fields.getOrNull(1)?.trim().orEmpty()
        if (name.isBlank() || !locale.startsWith("en", ignoreCase = true)) return null
        return SpeechVoiceOption(
            enginePackageName = WINDOWS_SAPI_ENGINE,
            engineLabel = WINDOWS_SAPI_LABEL,
            voiceName = name,
            localeTag = locale,
            networkRequired = false,
        )
    }

    private fun listVoicesScript(): String = """
        Add-Type -AssemblyName System.Speech
        ${'$'}synth = [System.Speech.Synthesis.SpeechSynthesizer]::new()
        try {
            ${'$'}synth.GetInstalledVoices() |
                Where-Object { ${'$'}_.Enabled -and ${'$'}_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq 'en' } |
                ForEach-Object { "{0}`t{1}" -f ${'$'}_.VoiceInfo.Name, ${'$'}_.VoiceInfo.Culture.Name }
        } finally {
            ${'$'}synth.Dispose()
        }
    """.trimIndent()

    private fun speechScript(): String = """
        param([string]${'$'}voice, [int]${'$'}rate, [string]${'$'}text)
        Add-Type -AssemblyName System.Speech
        ${'$'}synth = [System.Speech.Synthesis.SpeechSynthesizer]::new()
        try {
            ${'$'}synth.SetOutputToDefaultAudioDevice()
            ${ '$' }englishVoices = ${ '$' }synth.GetInstalledVoices() |
                Where-Object { ${ '$' }_.Enabled -and ${ '$' }_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq 'en' }
            ${ '$' }voiceToUse = ${ '$' }englishVoices |
                Where-Object { ${ '$' }_.VoiceInfo.Name -eq ${ '$' }voice } |
                Select-Object -First 1
            if (-not ${ '$' }voiceToUse) { ${ '$' }voiceToUse = ${ '$' }englishVoices | Select-Object -First 1 }
            if (${ '$' }voiceToUse) { ${ '$' }synth.SelectVoice(${ '$' }voiceToUse.VoiceInfo.Name) }
            ${ '$' }synth.Rate = ${ '$' }rate
            ${ '$' }synth.Speak(${ '$' }text)
        } finally {
            ${ '$' }synth.Dispose()
        }
    """.trimIndent()

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 3L
        const val WINDOWS_SAPI_ENGINE = "windows-sapi"
        const val WINDOWS_SAPI_LABEL = "Windows SAPI"
    }
}

internal class SapiSpeechProcess(
    private val process: Process,
    private val deleteHelper: () -> Unit,
) : SapiSpeechPlayback {
    private val cleanupLock = Any()
    private var cleanedUp = false

    override fun awaitCompletion() {
        try {
            runCatching { process.waitFor() }
        } finally {
            cleanUp()
        }
    }

    override fun cancel() {
        try {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.waitFor() }
        } finally {
            cleanUp()
        }
    }

    private fun cleanUp() = synchronized(cleanupLock) {
        if (cleanedUp) return
        cleanedUp = true
        deleteHelper()
    }
}

internal object SpeechRate {
    fun toSapiRate(rate: Float): Int {
        val clamped = com.pengshi.words.speech.SpeechSettings.clampRate(rate)
        return ((clamped - 1.0f) * 10.0f).toInt().coerceIn(-10, 10)
    }
}
