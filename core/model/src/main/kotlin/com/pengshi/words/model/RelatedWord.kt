package com.pengshi.words.model

enum class RelatedWordKind {
    DERIVED,
    SIMILAR,
}

data class RelatedWord(
    val word: Word,
    val kind: RelatedWordKind,
    val reason: String,
)
