package com.pengshi.words.database

import androidx.room.withTransaction
import com.pengshi.words.importer.DelimitedTextParser
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import com.pengshi.words.model.SOURCE_UNVERIFIED
import com.pengshi.words.model.exampleSourceFromPack
import com.pengshi.words.model.sourceLabelNeedsUpgrade

data class ExamplePackImportResult(
    val insertedExampleCount: Int,
    val removedPlaceholderCount: Int,
    val skippedRowCount: Int,
)

class RoomExamplePackImporter(private val database: PengshiDatabase) {
    suspend fun importCet6Examples(
        input: InputStream,
        now: Instant = Instant.now(),
    ): ExamplePackImportResult = importExamples(input, now, cet6Only = true)

    suspend fun importBundledExamples(
        input: InputStream,
        now: Instant = Instant.now(),
    ): ExamplePackImportResult = importExamples(input, now, cet6Only = false)

    private suspend fun importExamples(
        input: InputStream,
        now: Instant,
        cet6Only: Boolean,
    ): ExamplePackImportResult {
        val rows = parseRows(input)
        return database.withTransaction {
            val wordsBySpelling = database.wordDao().getAll()
                .asSequence()
                .filter { !cet6Only || it.tags.splitTags().contains("cet6-core") }
                .associateBy { it.normalizedSpelling }
            val existingByWord = database.exampleSentenceDao().getAll().groupBy { it.wordId }
            val rowsByWord = rows.groupBy { it.spelling }
            var insertedCount = 0
            var removedPlaceholderCount = 0
            var matchedRowCount = 0
            val wordUpdates = mutableListOf<WordEntity>()

            rowsByWord.forEach { (spelling, bundled) ->
                val word = wordsBySpelling[spelling] ?: return@forEach
                matchedRowCount += bundled.size
                val current = existingByWord[word.id].orEmpty()
                bundled.forEach { row ->
                    val source = exampleSourceFromPack(row.sourceTag, row.sourceSentenceId, row.license)
                    current.firstOrNull {
                        it.sentenceEn == row.sentenceEn && it.sentenceCn == row.sentenceCn &&
                            sourceLabelNeedsUpgrade(it.source, source)
                    }
                        ?.let { database.exampleSentenceDao().update(it.copy(source = source)) }
                }
                val placeholders = current.filter { it.sentenceEn.isGenericPlaceholder() }
                val existingSentences = current
                    .filterNot { it.id in placeholders.mapTo(mutableSetOf()) { placeholder -> placeholder.id } }
                    .mapTo(mutableSetOf()) { it.sentenceEn }
                val newRows = bundled
                    .distinctBy { it.sentenceEn }
                    .filterNot { it.sentenceEn in existingSentences }

                placeholders.forEach { placeholder ->
                    database.exampleSentenceDao().deleteById(placeholder.id)
                    removedPlaceholderCount++
                }
                if (newRows.isNotEmpty()) {
                    database.exampleSentenceDao().shiftSortOrderForWord(word.id, newRows.size)
                    database.exampleSentenceDao().insertAll(
                        newRows.mapIndexed { index, row ->
                            ExampleSentenceEntity(
                                wordId = word.id,
                                sentenceEn = row.sentenceEn,
                                sentenceCn = row.sentenceCn,
                                sortOrder = index,
                                source = exampleSourceFromPack(row.sourceTag, row.sourceSentenceId, row.license),
                            )
                        },
                    )
                    insertedCount += newRows.size
                }
                val tags = word.tags.splitTags().toMutableSet()
                val addedSourceTags = bundled.mapNotNull { it.sourceTag }.filter(String::isNotBlank).toSet()
                if (tags.addAll(addedSourceTags)) {
                    wordUpdates += word.copy(tags = tags.joinToString(" "), updatedAt = now)
                }
            }
            if (wordUpdates.isNotEmpty()) database.wordDao().updateAll(wordUpdates)
            ExamplePackImportResult(
                insertedExampleCount = insertedCount,
                removedPlaceholderCount = removedPlaceholderCount,
                skippedRowCount = rows.size - matchedRowCount,
            )
        }
    }

    private fun parseRows(input: InputStream): List<OfflineExampleRow> {
        val text = input.readBytes().toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")
        val records = DelimitedTextParser.parseRecords(text, ',')
        if (records.isEmpty()) return emptyList()
        val headers = records.first().mapIndexed { index, value ->
            value.trim().lowercase(Locale.ROOT) to index
        }.toMap()
        val spellingIndex = headers["spelling"] ?: error("例句包缺少 spelling 列")
        val englishIndex = headers["example_en"] ?: error("例句包缺少 example_en 列")
        val chineseIndex = headers["example_cn"] ?: error("例句包缺少 example_cn 列")
        return records.drop(1).mapIndexedNotNull { index, cells ->
            val spelling = cells.getOrNull(spellingIndex).orEmpty().trim().lowercase(Locale.ROOT)
            val sentenceEn = cells.getOrNull(englishIndex).orEmpty().trim()
            if (spelling.isBlank() || sentenceEn.isBlank()) return@mapIndexedNotNull null
            val sourceSentenceId = headers["source_sentence_id"]?.let { cells.getOrNull(it)?.trim()?.ifBlank { null } }
            val sourceTag = headers["source_tag"]?.let { cells.getOrNull(it)?.trim()?.ifBlank { null } }
                ?: if (sourceSentenceId != null) "source:tatoeba" else null
            require(sourceTag != null) { "例句包第 ${index + 2} 行缺少 source_tag 或 source_sentence_id" }
            OfflineExampleRow(
                spelling = spelling,
                sentenceEn = sentenceEn,
                sentenceCn = cells.getOrNull(chineseIndex)?.trim()?.ifBlank { null },
                sourceTag = sourceTag,
                sourceSentenceId = sourceSentenceId,
                license = headers["license"]?.let { cells.getOrNull(it)?.trim()?.ifBlank { null } },
            )
        }
    }
}

private data class OfflineExampleRow(
    val spelling: String,
    val sentenceEn: String,
    val sentenceCn: String?,
    val sourceTag: String,
    val sourceSentenceId: String?,
    val license: String?,
)

private fun String.isGenericPlaceholder(): Boolean =
    matches(Regex("^The word \\\"?.*\\\"? appears in many academic texts\\.$")) ||
        matches(Regex("^Try to use \\\"?.*\\\"? in a sentence\\.$"))

private fun String.splitTags(): List<String> = split(Regex("\\s+")).filter(String::isNotBlank)
