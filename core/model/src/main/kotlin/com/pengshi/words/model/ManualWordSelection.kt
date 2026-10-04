package com.pengshi.words.model

/** A fresh, deck-aware view of the words that can still be added to today's main task. */
data class ManualWordOption(
    val word: Word,
    val deckIds: Set<Long>,
)

data class ManualWordCatalog(
    val decks: List<Deck>,
    val words: List<ManualWordOption>,
    val remainingQuota: Int,
) {
    val selectionLimit: Int get() = minOf(remainingQuota, words.size)
}

fun StudyDataSnapshot.manualWordCatalog(
    candidateWordIds: Set<Long>,
    alreadyPlannedWordIds: Set<Long>,
    remainingQuota: Int,
): ManualWordCatalog {
    val deckIdsByWord = deckWords.groupBy { it.wordId }
        .mapValues { (_, links) -> links.mapTo(linkedSetOf()) { it.deckId } }
    val options = words.asSequence()
        .filter { it.id in candidateWordIds && it.id !in alreadyPlannedWordIds }
        .map { ManualWordOption(it, deckIdsByWord[it.id].orEmpty()) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.word.spelling })
        .toList()
    return ManualWordCatalog(decks, options, remainingQuota.coerceAtLeast(0))
}
