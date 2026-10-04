package com.pengshi.words.model

data class PersonalWordInput(
    val spelling: String,
    val definitionCn: String = "",
    val partOfSpeech: String = "",
    val phonetic: String = "",
    val examples: List<PersonalExampleInput> = emptyList(),
    val definitionSource: String = SOURCE_UNVERIFIED,
    val mnemonic: String = "",
)

data class PersonalExampleInput(
    val sentenceEn: String,
    val sentenceCn: String? = null,
    val source: String = SOURCE_UNVERIFIED,
)

data class PersonalWordResult(
    val wordId: Long,
    val deckId: Long,
    val createdNewWord: Boolean,
    val addedDefinition: Boolean = false,
)

data class UserDeckBulkResult(
    val importedRows: Int,
    val updatedRows: Int,
    val linkedRows: Int,
    val skippedRows: Int,
    val errors: List<ImportError> = emptyList(),
    val protectedBuiltinRows: Int = 0,
    val addedDefinitions: Int = 0,
)
