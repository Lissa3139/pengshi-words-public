package com.pengshi.words.feature.decks

import com.pengshi.words.database.ExampleSentenceEntity
import com.pengshi.words.database.WordLibraryRow
import com.pengshi.words.model.Deck
import com.pengshi.words.model.DeckWord
import com.pengshi.words.model.ExampleSentence
import com.pengshi.words.model.Word
import com.pengshi.words.model.WordSense
import com.pengshi.words.model.distinctSupplementaryMeanings
import com.pengshi.words.model.normalizeDictionaryText

internal fun buildWordListItems(
    words: List<Word>,
    decks: List<Deck>,
    deckWords: List<DeckWord>,
    examples: List<ExampleSentence>,
    senses: List<WordSense> = emptyList(),
): List<WordListItemUi> {
    val wordsById = words.associateBy { it.id }
    val deckNames = decks.associate { it.id to it.name }
    val examplesByWordId = examples
        .sortedWith(compareBy<ExampleSentence>({ it.wordId }, { it.sortOrder }, { it.id }))
        .groupBy { it.wordId }
    val sensesByWordId = senses
        .sortedWith(compareBy<WordSense>({ it.wordId }, { it.sortOrder }, { it.id }))
        .groupBy { it.wordId }
        .mapValues { (wordId, wordSenses) ->
            distinctSupplementaryMeanings(wordsById[wordId]?.definitionCn.orEmpty(), wordSenses)
        }

    fun Word.toUi(deckId: Long? = null): WordListItemUi = WordListItemUi(
        id = id,
        spelling = spelling,
        phonetic = phonetic,
        partOfSpeech = partOfSpeech.ifBlank { inferredPartOfSpeech(definitionCn) },
        definitionCn = definitionCn.normalizeDictionaryText(),
        definitionSource = definitionSource,
        mnemonic = mnemonic,
        deckId = deckId,
        deckName = deckId?.let(deckNames::get),
        examples = examplesByWordId[id].orEmpty().map { WordExampleUi(it.sentenceEn, it.sentenceCn, it.id, it.source) },
        senses = sensesByWordId[id].orEmpty(),
    )

    val linked = deckWords
        .sortedWith(compareBy({ it.deckId }, { it.position }))
        .mapNotNull { link -> wordsById[link.wordId]?.toUi(link.deckId) }
    if (linked.isNotEmpty()) return linked
    return words.sortedBy { it.spelling }.map { it.toUi() }
}

internal fun buildWordListItems(
    rows: List<WordLibraryRow>,
    examples: List<ExampleSentenceEntity> = emptyList(),
    senses: List<WordSense> = emptyList(),
): List<WordListItemUi> {
    val examplesByWordId = examples
        .sortedWith(compareBy<ExampleSentenceEntity>({ it.wordId }, { it.sortOrder }, { it.id }))
        .groupBy { it.wordId }
    val definitionsByWordId = rows.associate { it.id to it.definitionCn }
    val sensesByWordId = senses.groupBy { it.wordId }
        .mapValues { (wordId, wordSenses) ->
            distinctSupplementaryMeanings(definitionsByWordId[wordId].orEmpty(), wordSenses)
        }
    return rows.map { row ->
        WordListItemUi(
            id = row.id,
            spelling = row.spelling,
            phonetic = row.phonetic,
            partOfSpeech = row.partOfSpeech.ifBlank { inferredPartOfSpeech(row.definitionCn) },
            definitionCn = row.definitionCn.normalizeDictionaryText(),
            definitionSource = row.definitionSource,
            mnemonic = row.mnemonic,
            deckId = row.deckId,
            deckName = row.deckName,
            examples = examplesByWordId[row.id].orEmpty().map { WordExampleUi(it.sentenceEn, it.sentenceCn, it.id, it.source) },
            senses = sensesByWordId[row.id].orEmpty(),
        )
    }
}

private fun inferredPartOfSpeech(definitionCn: String): String = definitionCn.normalizeDictionaryText()
    .lineSequence()
    .mapNotNull { line -> Regex("^\\s*((?:[A-Za-z]+\\.\\s*)+)").find(line)?.groupValues?.getOrNull(1) }
    .map { it.trim().replace(Regex("\\s+"), " ") }
    .distinct()
    .joinToString(" / ")
