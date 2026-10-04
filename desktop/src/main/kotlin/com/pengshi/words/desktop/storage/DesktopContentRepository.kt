package com.pengshi.words.desktop.storage

import com.pengshi.words.model.ExampleSentence
import com.pengshi.words.model.Word
import com.pengshi.words.model.SOURCE_UNVERIFIED
import com.pengshi.words.model.exampleSourceFromPack
import com.pengshi.words.model.ImportPreview
import com.pengshi.words.model.inlineExampleSourceFromTags
import com.pengshi.words.model.definitionSourceFromTags
import com.pengshi.words.model.sourceLabelNeedsUpgrade
import java.util.Locale

data class BundledExample(
    val spelling: String,
    val sentenceEn: String,
    val sentenceCn: String?,
    val sourceTag: String = "source:tatoeba",
    val sourceSentenceId: String? = null,
    val license: String? = null,
)

class DesktopContentRepository(private val database: DesktopSqliteDatabase) {
    fun findWordsMissingExamples(): List<Word> = database.read { connection ->
        connection.queryList(
            """
            SELECT w.* FROM words w
            WHERE NOT EXISTS (
                SELECT 1 FROM examples e
                WHERE e.word_id = w.id
                  AND e.sentence_en NOT LIKE 'The word % appears in many academic texts.'
                  AND e.sentence_en NOT LIKE 'Try to use % in a sentence.'
            )
            ORDER BY w.id
            """.trimIndent(),
            map = { it.toWord() },
        )
    }

    fun findWord(spelling: String): Word? = database.read { connection ->
        connection.queryOne(
            "SELECT * FROM words WHERE normalized_spelling = ? LIMIT 1",
            { it.setString(1, spelling.trim().lowercase(Locale.ROOT)) },
            { it.toWord() },
        )
    }

    fun getExamples(wordId: Long): List<ExampleSentence> = database.read { connection ->
        connection.queryList(
            "SELECT * FROM examples WHERE word_id = ? ORDER BY sort_order, id",
            { it.setLong(1, wordId) },
            { it.toExample() },
        )
    }

    fun mergeBundledExamples(examples: List<BundledExample>): Int = database.transaction { connection ->
        var insertedCount = 0
        val wordsBySpelling = connection.queryList(
            "SELECT * FROM words",
            map = { it.toWord() },
        ).associateBy { it.normalizedSpelling }
        val examplesByWordId = connection.queryList(
            "SELECT * FROM examples ORDER BY sort_order, id",
            map = { it.toExample() },
        ).groupBy { it.wordId }
        examples.asSequence()
            .map { example ->
                example.copy(
                    spelling = example.spelling.trim().lowercase(Locale.ROOT),
                    sentenceEn = example.sentenceEn.trim(),
                    sentenceCn = example.sentenceCn?.trim()?.ifBlank { null },
                )
            }
            .filter { it.spelling.isNotBlank() && it.sentenceEn.isNotBlank() }
            .groupBy { it.spelling }
            .forEach { (normalizedSpelling, bundledForWord) ->
                val word = wordsBySpelling[normalizedSpelling] ?: return@forEach
                val currentExamples = examplesByWordId[word.id].orEmpty()
                bundledForWord.forEach { bundled ->
                    val source = exampleSourceFromPack(bundled.sourceTag, bundled.sourceSentenceId, bundled.license)
                    currentExamples.firstOrNull {
                        it.sentenceEn == bundled.sentenceEn && it.sentenceCn == bundled.sentenceCn &&
                            sourceLabelNeedsUpgrade(it.source, source)
                    }
                        ?.let { existing ->
                            connection.executeUpdate("UPDATE examples SET source = ? WHERE id = ?") {
                                it.setString(1, source)
                                it.setLong(2, existing.id)
                            }
                        }
                }
                val placeholderIds = currentExamples
                    .filter { it.sentenceEn.isGeneratedPlaceholder() }
                    .map { it.id }
                val existingRealSentences = currentExamples
                    .filterNot { it.id in placeholderIds }
                    .mapTo(mutableSetOf()) { it.sentenceEn }
                val uniqueBundled = bundledForWord.distinctBy { it.sentenceEn }
                val existingTags = word.tags.split(Regex("\\s+"))
                    .filter(String::isNotBlank)
                    .toSet()
                val sourceTags = bundledForWord.mapTo(mutableSetOf()) { it.sourceTag.trim() }
                    .filter(String::isNotBlank)
                val alreadyMerged = sourceTags.isNotEmpty() &&
                    sourceTags.all(existingTags::contains) &&
                    uniqueBundled.all { it.sentenceEn in existingRealSentences }
                if (alreadyMerged && placeholderIds.isEmpty()) return@forEach
                val newBundled = uniqueBundled.filter { it.sentenceEn !in existingRealSentences }

                placeholderIds.forEach { placeholderId ->
                    connection.executeUpdate("DELETE FROM examples WHERE id = ?") {
                        it.setLong(1, placeholderId)
                    }
                }
                if (newBundled.isNotEmpty()) {
                    connection.executeUpdate(
                        "UPDATE examples SET sort_order = sort_order + ? WHERE word_id = ?",
                    ) {
                        it.setInt(1, newBundled.size)
                        it.setLong(2, word.id)
                    }
                    newBundled.forEachIndexed { index, example ->
                        connection.executeUpdate(
                            "INSERT INTO examples (word_id, sentence_en, sentence_cn, sort_order, source) VALUES (?, ?, ?, ?, ?)",
                        ) {
                            it.setLong(1, word.id)
                            it.setString(2, example.sentenceEn)
                            it.setNullableString(3, example.sentenceCn)
                            it.setInt(4, index)
                            it.setString(5, exampleSourceFromPack(example.sourceTag, example.sourceSentenceId, example.license))
                        }
                    }
                    insertedCount += newBundled.size
                }
                val tags = existingTags + sourceTags
                if (tags != existingTags) {
                    connection.executeUpdate(
                        "UPDATE words SET tags = ?, updated_at = ? WHERE id = ?",
                    ) {
                        it.setString(1, tags.joinToString(" "))
                        it.setInstant(2, java.time.Instant.now())
                        it.setLong(3, word.id)
                    }
                }
            }
        insertedCount
    }

    fun reconcileCet6SourceMetadata(preview: ImportPreview) = database.transaction { connection ->
        preview.rows.forEach rowLoop@ { row ->
            if (!row.tags.orEmpty().split(Regex("\\s+")).contains("cet6-core")) return@rowLoop
            val word = connection.queryOne(
                "SELECT * FROM words WHERE normalized_spelling = ? LIMIT 1",
                { it.setString(1, row.spelling.trim().lowercase(Locale.ROOT)) },
                { it.toWord() },
            ) ?: return@rowLoop
            val expectedDefinition = row.definitionSource?.takeIf(String::isNotBlank)
                ?: definitionSourceFromTags(row.tags.orEmpty())
            if (sourceLabelNeedsUpgrade(word.definitionSource, expectedDefinition)) {
                connection.executeUpdate("UPDATE words SET definition_source = ?, updated_at = ? WHERE id = ?") {
                    it.setString(1, expectedDefinition)
                    it.setInstant(2, java.time.Instant.now())
                    it.setLong(3, word.id)
                }
            }
            val english = row.exampleEn.orEmpty().split("||").map(String::trim).filter(String::isNotBlank)
            val chinese = row.exampleCn.orEmpty().split("||").map(String::trim)
            english.forEachIndexed sentenceLoop@ { index, sentence ->
                val translation = chinese.getOrNull(index)?.ifBlank { null }
                val existing = connection.queryOne(
                    "SELECT id, source FROM examples WHERE word_id = ? AND sentence_en = ? AND COALESCE(sentence_cn, '') = COALESCE(?, '') LIMIT 1",
                    { it.setLong(1, word.id); it.setString(2, sentence); it.setNullableString(3, translation) },
                ) { it.getLong("id") to it.getString("source") } ?: return@sentenceLoop
                val expected = inlineExampleSourceFromTags(row.tags.orEmpty(), sentence)
                if (sourceLabelNeedsUpgrade(existing.second, expected)) {
                    connection.executeUpdate("UPDATE examples SET source = ? WHERE id = ?") {
                        it.setString(1, expected)
                        it.setLong(2, existing.first)
                    }
                }
            }
        }
    }

}

private fun String.isGeneratedPlaceholder(): Boolean =
    matches(Regex("^The word \\\".*\\\" appears in many academic texts\\.$")) ||
        matches(Regex("^Try to use \\\".*\\\" in a sentence\\.$"))
