package com.pengshi.words.importer

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

class DelimitedTextParser : WordImportParser {
    override fun parse(input: InputStream, format: ImportFormat): ImportPreview {
        require(format == ImportFormat.CSV || format == ImportFormat.TXT) { "DelimitedTextParser only supports TXT and CSV" }
        val text = input.readBytes().toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")
        val delimiter = if (format == ImportFormat.TXT) detectDelimiter(text) else ','
        return mapTable(parseRecords(text, delimiter), format)
    }

    override fun parseForUserDeck(input: InputStream, format: ImportFormat): ImportPreview {
        require(format == ImportFormat.CSV || format == ImportFormat.TXT) { "DelimitedTextParser only supports TXT and CSV" }
        val text = input.readBytes().toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")
        val delimiter = if (format == ImportFormat.TXT) detectDelimiter(text) else ','
        return mapTable(parseRecords(text, delimiter), format, allowOptionalFields = true, mergeDuplicates = true,
            personalDeckDefaults = true, requireSevenColumns = format == ImportFormat.TXT)
    }

    private fun detectDelimiter(text: String): Char =
        text.lineSequence().firstOrNull()?.let { line ->
            if ('\t' in line) '\t' else ','
        } ?: '\t'

    companion object {
        internal fun mapTable(
            records: List<List<String>>,
            format: ImportFormat,
            allowOptionalFields: Boolean = false,
            mergeDuplicates: Boolean = false,
            personalDeckDefaults: Boolean = false,
            requireSevenColumns: Boolean = false,
        ): ImportPreview {
            if (records.isEmpty()) return ImportPreview(emptyList(), emptyList(), format)
            val first = records.first().map(::headerKey)
            val hasHeader = first.any { it in HEADER_KEYS }
            val indexes = if (hasHeader) headerIndexes(records.first()) else defaultIndexes(personalDeckDefaults)
            val data = if (hasHeader) records.drop(1) else records
            val rows = mutableListOf<ImportRow>()
            val errors = mutableListOf<ImportError>()
            val seen = mutableSetOf<String>()
            val validator = ImportValidator()
            data.forEachIndexed { offset, cells ->
                val rowNumber = offset + if (hasHeader) 2 else 1
                if (cells.all(String::isBlank)) return@forEachIndexed
                if (requireSevenColumns && cells.size != 7) {
                    errors += ImportError(rowNumber, "columns", "本行有 ${cells.size} 列，需要用制表符分隔成 7 列；空列也要保留分隔符")
                    return@forEachIndexed
                }
                val row = ImportRow(
                    spelling = cell(cells, indexes["spelling"] ?: -1),
                    phonetic = cell(cells, indexes["phonetic"] ?: -1).ifBlank { null },
                    partOfSpeech = cell(cells, indexes["partOfSpeech"] ?: -1).ifBlank { null },
                    definitionCn = cell(cells, indexes["definitionCn"] ?: -1),
                    exampleEn = cell(cells, indexes["exampleEn"] ?: -1).ifBlank { null },
                    exampleCn = cell(cells, indexes["exampleCn"] ?: -1).ifBlank { null },
                    tags = cell(cells, indexes["tags"] ?: -1).ifBlank { null },
                    frequencyRank = cell(cells, indexes["frequencyRank"] ?: -1).toIntOrNull(),
                    definitionSource = cell(cells, indexes["definitionSource"] ?: -1).ifBlank { null },
                    exampleSource = cell(cells, indexes["exampleSource"] ?: -1).ifBlank { null },
                    mnemonic = cell(cells, indexes["mnemonic"] ?: -1).ifBlank { null },
                )
                validator.validate(
                    row,
                    rowNumber,
                    seen,
                    requireDefinition = !allowOptionalFields,
                    rejectDuplicates = !mergeDuplicates,
                )?.let { errors += it } ?: rows.add(row.copy(spelling = row.spelling.trim()))
            }
            return ImportPreview(rows, errors, format)
        }

        fun parseRecords(text: String, delimiter: Char): List<List<String>> {
            val records = mutableListOf<List<String>>()
            val row = mutableListOf<String>()
            val cell = StringBuilder()
            var quoted = false
            var index = 0
            fun finishCell() { row += cell.toString().trim(); cell.clear() }
            fun finishRow() { finishCell(); if (row.any { it.isNotBlank() }) records += row.toList(); row.clear() }
            while (index < text.length) {
                val ch = text[index]
                when {
                    ch == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> { cell.append('"'); index++ }
                    ch == '"' -> quoted = !quoted
                    ch == delimiter && !quoted -> finishCell()
                    (ch == '\n' || ch == '\r') && !quoted -> { finishRow(); if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++ }
                    else -> cell.append(ch)
                }
                index++
            }
            if (cell.isNotEmpty() || row.isNotEmpty()) finishRow()
            return records
        }

        private val HEADER_KEYS = setOf("spelling", "word", "单词", "definitioncn", "中文释义", "definition", "frequencyrank", "频率排名", "mnemonic", "助记", "memoryaid")

        private fun headerIndexes(headers: List<String>): Map<String, Int> {
            val map = headers.mapIndexed { index, header -> headerKey(header) to index }.toMap()
            fun find(vararg names: String): Int = names.firstNotNullOfOrNull { map[it] } ?: -1
            return mapOf(
                "spelling" to find("spelling", "word", "单词"),
                "phonetic" to find("phonetic", "ipa", "音标"),
                "partOfSpeech" to find("partofspeech", "part_of_speech", "pos", "词性"),
                "definitionCn" to find("definitioncn", "definition", "中文释义", "释义"),
                "exampleEn" to find("exampleen", "example", "英文例句", "例句"),
                "exampleCn" to find("examplecn", "例句翻译", "翻译"),
                "tags" to find("tags", "tag", "标签"),
                "frequencyRank" to find("frequencyrank", "frequency", "freq", "频率排名", "频率"),
                "definitionSource" to find("definitionsource", "翻译来源", "释义来源"),
                "exampleSource" to find("examplesource", "例句来源"),
                "mnemonic" to find("mnemonic", "memoryaid", "助记"),
            )
        }

        private fun defaultIndexes(personalDeckDefaults: Boolean = false) = mapOf(
            "spelling" to 0, "phonetic" to 1, "partOfSpeech" to 2, "definitionCn" to 3,
            "exampleEn" to 4, "exampleCn" to 5,
            if (personalDeckDefaults) "mnemonic" to 6 else "tags" to 6,
        )

        private fun cell(cells: List<String>, index: Int): String = if (index in cells.indices) cells[index].trim() else ""

        private fun headerKey(value: String): String = value.trim().lowercase(Locale.ROOT).replace(" ", "").replace("-", "").replace("_", "")
    }
}
