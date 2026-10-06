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

/** Compares the translated meaning while ignoring POS labels and dictionary formatting. */
fun sameMeaningContent(first: String, second: String): Boolean {
    val firstLines = meaningLineKeys(first).distinct().sorted()
    val secondLines = meaningLineKeys(second).distinct().sorted()
    return firstLines.isNotEmpty() && firstLines == secondLines
}

/** Removes duplicate and re-aggregated senses before they are shown beside the primary definition. */
fun distinctSupplementaryMeanings(primaryDefinitionCn: String, senses: List<WordSense>): List<WordSense> {
    val candidates = mutableListOf<Pair<WordSense, List<String>>>()
    val positionsByMeaning = mutableMapOf<String, Int>()
    senses.forEach { sense ->
        val lines = meaningLineKeys(sense.definitionCn)
        if (lines.isEmpty()) return@forEach
        val identity = lines.distinct().sorted().joinToString("\n")
        val existingIndex = positionsByMeaning[identity]
        if (existingIndex == null) {
            positionsByMeaning[identity] = candidates.size
            candidates += sense to lines
        } else if (candidates[existingIndex].first.partOfSpeech.isBlank() && sense.partOfSpeech.isNotBlank()) {
            // Keep the POS label when the same imported definition exists with and without one.
            candidates[existingIndex] = sense to lines
        }
    }

    val primaryLines = meaningLineKeys(primaryDefinitionCn).toSet()
    val atomicLines = candidates.asSequence()
        .filter { (_, lines) -> lines.distinct().size == 1 }
        .flatMap { (_, lines) -> lines.asSequence() }
        .toSet() + primaryLines

    return candidates.filterNot { (_, lines) ->
        val distinctLines = lines.distinct()
        distinctLines.all(primaryLines::contains) ||
            (distinctLines.size > 1 && distinctLines.all(atomicLines::contains))
    }.map { it.first }
}

private fun meaningLineKeys(value: String): List<String> = value.normalizeDictionaryText()
    .lineSequence()
    .flatMap { it.split(Regex("[;；]")).asSequence() }
    .map { line ->
        dictionaryPartOfSpeechPrefix.replace(line.trim(), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s,，。:：、.!?！？()（）\\[\\]【】]+"), " ")
            .trim()
    }
    .filter(String::isNotEmpty)
    .toList()

private val dictionaryPartOfSpeechPrefix = Regex(
    "^(?:(?:adj|adv|prep|pron|num|art|conj|aux|int|abbr|phr|det|vt|vi|v|n|a)\\.\\s*)+",
    RegexOption.IGNORE_CASE,
)
