package com.pengshi.words.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/** Uses Android's installed TTS services; model APKs stay outside the app. */
internal class InstalledSpeechEngines(
    private val context: Context,
    private val onCatalog: (List<SpeechVoiceOption>) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var clients = emptyMap<String, TextToSpeech>()
    private var starting = emptyMap<String, TextToSpeech>()
    private var generation = 0
    private var closed = false

    fun refresh() = handler.post {
        if (closed) return@post
        val scan = ++generation
        starting.values.forEach(TextToSpeech::shutdown)
        starting = emptyMap()
        val services = context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0,
        ).distinctBy { it.serviceInfo.packageName }.sortedBy { it.serviceInfo.packageName }
        if (services.isEmpty()) {
            clients.values.forEach(TextToSpeech::shutdown)
            clients = emptyMap()
            onCatalog(emptyList())
            return@post
        }
        val connected = linkedMapOf<String, TextToSpeech>()
        val pending = linkedMapOf<String, TextToSpeech>()
        val options = mutableListOf<SpeechVoiceOption>()
        val completed = mutableSetOf<String>()
        var finished = false
        fun finish() {
            if (finished || closed || generation != scan) return
            finished = true
            pending.filterKeys { it !in connected }.values.forEach(TextToSpeech::shutdown)
            clients.values.forEach(TextToSpeech::shutdown)
            clients = connected.toMap()
            starting = emptyMap()
            onCatalog(options.sortedWith(compareBy({ it.engineLabel }, { it.voiceName })))
        }
        services.forEach { service ->
            val packageName = service.serviceInfo.packageName
            val appLabel = service.loadLabel(context.packageManager).toString()
            var client: TextToSpeech? = null
            runCatching {
                client = TextToSpeech(context, { status ->
                    handler.post connected@{
                        val tts = client ?: return@connected
                        if (closed || generation != scan || finished) {
                            if (packageName !in connected) tts.shutdown()
                            return@connected
                        }
                        if (!completed.add(packageName)) return@connected
                        if (status == TextToSpeech.SUCCESS) {
                            val voices = runCatching {
                                tts.voices.orEmpty().filter {
                                    it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                                }.sortedBy { it.name }
                            }.getOrDefault(emptyList())
                            if (voices.isNotEmpty()) {
                                connected[packageName] = tts
                                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                    override fun onStart(utteranceId: String?) = Unit
                                    override fun onDone(utteranceId: String?) = Unit
                                    @Deprecated("Required by Android TTS")
                                    override fun onError(utteranceId: String?) {
                                        reportFailure()
                                    }
                                    override fun onError(utteranceId: String?, errorCode: Int) = reportFailure()
                                    private fun reportFailure() {
                                        handler.post {
                                            if (!closed) this@InstalledSpeechEngines.onError("语音引擎无法完成朗读，请检查模型数据或换一个音色。")
                                        }
                                    }
                                })
                                val label = when {
                                    packageName.contains("lessac", true) -> "Lessac · 美式女声"
                                    packageName.contains("ryan", true) -> "Ryan · 美式男声"
                                    packageName.contains("jenny", true) -> "Jenny (Dioco) · 英式女声"
                                    else -> appLabel
                                }
                                options += voices.map { voice ->
                                    SpeechVoiceOption(packageName,
                                        if (voices.size == 1) label else "$label · ${voice.name}",
                                        voice.name, voice.locale.toLanguageTag(), false)
                                }
                            }
                        }
                        if (completed.size == services.size) finish()
                    }
                }, packageName)
                pending[packageName] = requireNotNull(client)
            }.onFailure {
                client?.shutdown()
                completed.add(packageName)
            }
        }
        starting = pending.toMap()
        if (completed.size == services.size) finish()
        handler.postDelayed({ finish() }, 15_000)
    }

    fun speak(text: String, voice: SpeechVoiceOption, utteranceId: String, rate: Float) = handler.post {
        if (closed) return@post
        val client = clients[voice.enginePackageName]
        if (client == null) {
            onError("该音色不可用，请确认模型已安装并刷新音色。")
            return@post
        }
        runCatching {
            val selected = client.voices.orEmpty().firstOrNull { it.name == voice.voiceName }
                ?: error("模型不再提供该音色")
            check(!selected.isNetworkConnectionRequired) { "该音色需要网络" }
            check(client.setVoice(selected) == TextToSpeech.SUCCESS) { "无法切换音色" }
            check(client.setSpeechRate(SpeechSettings.clampRate(rate)) == TextToSpeech.SUCCESS)
            check(client.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId) == TextToSpeech.SUCCESS)
        }.onFailure { onError("朗读失败：${it.message ?: "语音引擎不可用"}") }
    }

    fun stop() = handler.post { clients.values.forEach { runCatching { it.stop() } } }

    fun release() = handler.post {
        closed = true
        generation++
        handler.removeCallbacksAndMessages(null)
        (clients.values + starting.values).distinct().forEach(TextToSpeech::shutdown)
        clients = emptyMap()
        starting = emptyMap()
    }
}
