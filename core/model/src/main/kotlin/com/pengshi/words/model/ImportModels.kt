package com.pengshi.words.model

enum class ImportFormat {
    TXT,
    CSV,
    XLSX,
}

data class ImportRow(
    val spelling: String,
    val phonetic: String?,
    val partOfSpeech: String?,
    val definitionCn: String,
    val exampleEn: String?,
    val exampleCn: String?,
    val tags: String?,
    val frequencyRank: Int? = null,
    val definitionSource: String? = null,
    val exampleSource: String? = null,
    val mnemonic: String? = null,
)

data class BatchImportSources(
    val definitionSource: String,
    val exampleSource: String,
)

data class ImportError(
    val rowNumber: Int,
    val field: String,
    val message: String,
)

data class ImportPreview(
    val rows: List<ImportRow>,
    val errors: List<ImportError>,
    val detectedFormat: ImportFormat,
)

data class ImportResult(
    val deckId: Long,
    val importedRows: Int,
    val skippedRows: Int,
    val errors: List<ImportError>,
)
