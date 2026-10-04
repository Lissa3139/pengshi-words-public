package com.pengshi.words.importer

import java.io.InputStream
import com.pengshi.words.model.BatchImportSources
import com.pengshi.words.model.ImportPreview
import com.pengshi.words.model.SOURCE_UNVERIFIED

class DefaultWordImportParser : WordImportParser {
    override fun parse(input: InputStream, format: ImportFormat): ImportPreview = when (format) {
        ImportFormat.CSV, ImportFormat.TXT -> DelimitedTextParser().parse(input, format)
        ImportFormat.XLSX -> XlsxParser().parse(input, format)
    }

    override fun parseForUserDeck(input: InputStream, format: ImportFormat): ImportPreview = when (format) {
        ImportFormat.CSV, ImportFormat.TXT -> DelimitedTextParser().parseForUserDeck(input, format)
        ImportFormat.XLSX -> XlsxParser().parseForUserDeck(input, format)
    }
}

fun ImportPreview.withBatchSources(sources: BatchImportSources): ImportPreview {
    val definitionSource = sources.definitionSource.trim().ifBlank { SOURCE_UNVERIFIED }
    val exampleSource = sources.exampleSource.trim().ifBlank { SOURCE_UNVERIFIED }
    return copy(
        rows = rows.map { row ->
            row.copy(
                definitionSource = if (row.definitionCn.isNotBlank()) definitionSource else row.definitionSource,
                exampleSource = if (row.exampleEn?.isNotBlank() == true) exampleSource else row.exampleSource,
            )
        },
    )
}
