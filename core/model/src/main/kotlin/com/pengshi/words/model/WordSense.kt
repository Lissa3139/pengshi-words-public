package com.pengshi.words.model

import java.time.Instant
import java.util.Locale

/** A separately sourced Chinese meaning attached to an existing spelling. */
data class WordSense(
    val id: Long = 0,
    val wordId: Long,
    val partOfSpeech: String,
    val definitionCn: String,
    val definitionSource: String = SOURCE_UNVERIFIED,
    val normalizedKey: String = wordSenseKey(partOfSpeech, definitionCn),
    val sortOrder: Int = 0,
    val createdAt: Instant,
)

/** Stable identity for one meaning; this intentionally does not guess semantic similarity. */
fun wordSenseKey(partOfSpeech: String, definitionCn: String): String {
    val normalizedPos = partOfSpeech.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    val normalizedDefinition = definitionCn.normalizeDictionaryText().trim().lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
    return "${normalizedPos.length}:$normalizedPos${normalizedDefinition.length}:$normalizedDefinition"
}

fun WordSense.sameMeaning(partOfSpeech: String, definitionCn: String): Boolean =
    normalizedKey == wordSenseKey(partOfSpeech, definitionCn)
