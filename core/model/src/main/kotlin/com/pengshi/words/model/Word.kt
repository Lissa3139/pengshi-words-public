package com.pengshi.words.model

import java.time.Instant

data class Word(
    val id: Long = 0,
    val spelling: String,
    val normalizedSpelling: String,
    val phonetic: String?,
    val partOfSpeech: String,
    val definitionCn: String,
    val tags: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Lower values are more frequent; null means the source did not provide a rank. */
    val frequencyRank: Int? = null,
    val definitionSource: String = definitionSourceFromTags(tags),
    val mnemonic: String = "",
)

data class ExampleSentence(
    val id: Long = 0,
    val wordId: Long,
    val sentenceEn: String,
    val sentenceCn: String?,
    val sortOrder: Int,
    val source: String = SOURCE_UNVERIFIED,
)

enum class DeckSourceType {
    BUILTIN,
    IMPORTED,
    USER,
}

const val DELETED_USER_DECK_SOURCE = "__deleted_user_deck__"

fun Deck.isDeletedUserDeck(): Boolean = sourceType != DeckSourceType.BUILTIN &&
    sourceFileName == DELETED_USER_DECK_SOURCE

data class Deck(
    val id: Long = 0,
    val name: String,
    val sourceType: DeckSourceType,
    val sourceFileName: String,
    val wordCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DeckWord(
    val deckId: Long,
    val wordId: Long,
    val position: Int,
    val addedAt: Instant,
)
