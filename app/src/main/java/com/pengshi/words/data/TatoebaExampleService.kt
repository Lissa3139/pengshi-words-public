package com.pengshi.words.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TatoebaExample(
    val sentenceEn: String,
    val sentenceCn: String?,
    val source: String,
)

/** Loads real bilingual example pairs on demand and leaves the local seed usable offline. */
class TatoebaExampleService {
    suspend fun fetch(spelling: String): List<TatoebaExample> = withContext(Dispatchers.IO) {
        runCatching {
            val query = URLEncoder.encode("=${spelling.trim()}", Charsets.UTF_8.name())
            val connection = (URL(
                "https://tatoeba.org/eng/api_v0/search?from=eng&query=$query&to=cmn" +
                    "&trans_filter=limit&trans_link=direct&trans_to=cmn&page=1",
            ).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    emptyList()
                } else {
                    val payload = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    parse(payload)
                }
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(emptyList())
    }

    private fun parse(payload: String): List<TatoebaExample> {
        val results = JSONObject(payload).optJSONArray("results") ?: return emptyList()
        val unique = linkedSetOf<String>()
        return buildList {
            for (index in 0 until results.length()) {
                val result = results.optJSONObject(index) ?: continue
                val english = result.optString("text").trim()
                if (english.isBlank() || !unique.add(english)) continue
                val translations = result.optJSONArray("translations")
                val firstGroup = translations?.optJSONArray(0)
                val chinese = firstGroup?.optJSONObject(0)?.optString("text")?.trim()?.takeIf { it.isNotBlank() }
                val sentenceId = result.optString("id").trim()
                add(TatoebaExample(english, chinese,
                    if (sentenceId.isNotEmpty()) "Tatoeba · 句子 $sentenceId" else "Tatoeba（句子编号待核实）"))
                if (size == 3) break
            }
        }
    }
}
