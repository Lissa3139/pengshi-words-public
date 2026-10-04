package com.pengshi.words.database

import androidx.room.withTransaction
import com.pengshi.words.importer.ImportRepository
import com.pengshi.words.importer.ImportResult
import com.pengshi.words.importer.ImportPreview
import com.pengshi.words.importer.ImportRow
import com.pengshi.words.model.CardKey
import com.pengshi.words.model.CardState
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.normalizeDictionaryText
import com.pengshi.words.model.definitionSourceFromTags
import com.pengshi.words.model.SOURCE_UNVERIFIED
import com.pengshi.words.model.inlineExampleSourceFromTags
import com.pengshi.words.model.sourceLabelNeedsUpgrade
import java.time.Instant
import java.util.Locale

class RoomImportRepository(
    private val database: PengshiDatabase,
    private val now: () -> Instant = Instant::now,
) : ImportRepository {
    /**
     * Fast path used for bundled read-only packs during startup. Unlike the
     * interactive importer, it resolves existing words/cards in bulk and
     * inserts deck links in one batch, avoiding tens of thousands of per-row
     * Room round trips on a fresh install.
     */
    suspend fun importBuiltinDeck(
        preview: ImportPreview,
        deckName: String,
        sourceFileName: String,
    ): ImportResult {
        if (preview.errors.isNotEmpty()) {
            return ImportResult(deckId = 0, importedRows = 0, skippedRows = preview.rows.size, errors = preview.errors)
        }
        if (preview.rows.isEmpty()) {
            return ImportResult(deckId = 0, importedRows = 0, skippedRows = 0, errors = emptyList())
        }
        require(preview.rows.all { row ->
            row.definitionSource?.isNotBlank() == true ||
                definitionSourceFromTags(row.tags.orEmpty()) != SOURCE_UNVERIFIED
        }) { "内置词库存在未注明释义来源的词条，请填写 definition_source 或来源标签" }
        return database.withTransaction {
            val timestamp = now()
            val existingByNormalized = database.wordDao().getAll()
                .associateBy { it.normalizedSpelling }
            val rows = preview.rows.map { row ->
                row to row.spelling.trim().lowercase(Locale.ROOT)
            }
            val updates = rows.mapNotNull { (row, normalized) ->
                val existing = existingByNormalized[normalized] ?: return@mapNotNull null
                val bundledDefinitionSource = row.definitionSource?.takeIf(String::isNotBlank)
                    ?: definitionSourceFromTags(row.tags.orEmpty())
                existing.copy(
                    spelling = row.spelling.trim(),
                    phonetic = row.phonetic,
                    partOfSpeech = row.partOfSpeech.orEmpty(),
                    definitionCn = row.definitionCn.trim().normalizeDictionaryText(),
                    tags = row.tags.orEmpty(),
                    definitionSource = if (sourceLabelNeedsUpgrade(existing.definitionSource, bundledDefinitionSource)) {
                        bundledDefinitionSource
                    } else existing.definitionSource,
                    mnemonic = row.mnemonic?.trim()?.ifBlank { existing.mnemonic } ?: existing.mnemonic,
                    updatedAt = timestamp,
                    frequencyRank = row.frequencyRank ?: existing.frequencyRank,
                )
            }
            if (updates.isNotEmpty()) database.wordDao().updateAll(updates)
            val inserts = rows.asSequence()
                .filter { (_, normalized) -> normalized !in existingByNormalized }
                .map { (row, normalized) ->
                    WordEntity(
                        spelling = row.spelling.trim(),
                        normalizedSpelling = normalized,
                        phonetic = row.phonetic,
                        partOfSpeech = row.partOfSpeech.orEmpty(),
                        definitionCn = row.definitionCn.trim().normalizeDictionaryText(),
                        tags = row.tags.orEmpty(),
                        definitionSource = row.definitionSource?.takeIf(String::isNotBlank)
                            ?: definitionSourceFromTags(row.tags.orEmpty()),
                        mnemonic = row.mnemonic?.trim().orEmpty(),
                        createdAt = timestamp,
                        updatedAt = timestamp,
                        frequencyRank = row.frequencyRank,
                    )
                }
                .toList()
            if (inserts.isNotEmpty()) database.wordDao().insertAll(inserts)

            val wordsByNormalized = database.wordDao().getAll().associateBy { it.normalizedSpelling }
            val deckId = database.deckDao().insert(
                DeckEntity(
                    name = deckName,
                    sourceType = DeckSourceTypeEntity.BUILTIN,
                    sourceFileName = sourceFileName,
                    wordCount = rows.size,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
            val wordIds = rows.map { (_, normalized) -> requireNotNull(wordsByNormalized[normalized]).id }
            val existingCardKeys = database.cardStateDao().getAll()
                .asSequence()
                .map { it.wordId to it.studyMode }
                .toSet()
            val missingCards = wordIds.distinct().flatMap { wordId ->
                StudyMode.entries
                    .filter { mode -> (wordId to mode) !in existingCardKeys }
                    .map { mode -> CardState.new(CardKey(wordId, mode), timestamp).toEntity() }
            }
            if (missingCards.isNotEmpty()) database.cardStateDao().insertAll(missingCards)
            database.deckDao().insertDeckWords(
                wordIds.mapIndexed { position, wordId ->
                    DeckWordEntity(deckId, wordId, position, timestamp)
                },
            )
            ImportResult(deckId, rows.size, 0, emptyList())
        }
    }

    override suspend fun importDeck(preview: ImportPreview): ImportResult {
        if (preview.errors.isNotEmpty()) {
            return ImportResult(deckId = 0, importedRows = 0, skippedRows = preview.rows.size, errors = preview.errors)
        }
        if (preview.rows.isEmpty()) {
            return ImportResult(deckId = 0, importedRows = 0, skippedRows = 0, errors = emptyList())
        }
        return database.withTransaction {
            val timestamp = now()
            val isCet6 = preview.rows.any { it.tags.orEmpty().split(Regex("\\s+")).contains("cet6-core") }
            val deckId = database.deckDao().insert(
                DeckEntity(
                    name = if (isCet6) "六级核心词汇" else "导入词库 ${preview.detectedFormat.name}",
                    sourceType = if (isCet6) DeckSourceTypeEntity.BUILTIN else DeckSourceTypeEntity.IMPORTED,
                    sourceFileName = "imported.${preview.detectedFormat.name.lowercase(Locale.ROOT)}",
                    wordCount = preview.rows.size,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
            val existingExamples = database.exampleSentenceDao().getAll()
                .groupBy { it.wordId }
                .mapValues { (_, sentences) -> sentences.map { it.sentenceEn }.toMutableSet() }
                .toMutableMap()
            preview.rows.forEachIndexed { position, row ->
                val wordId = upsertWord(row, timestamp)
                val englishExamples = row.exampleEn.splitExamples()
                val chineseExamples = row.exampleCn.splitExamples()
                val knownExamples = existingExamples.getOrPut(wordId) { mutableSetOf() }
                englishExamples.forEachIndexed { index, example ->
                    if (knownExamples.add(example)) {
                        database.exampleSentenceDao().insert(
                            ExampleSentenceEntity(
                                wordId = wordId,
                                sentenceEn = example,
                                sentenceCn = chineseExamples.getOrNull(index),
                                sortOrder = index,
                                source = row.exampleSource?.takeIf(String::isNotBlank)
                                    ?: inlineExampleSourceFromTags(row.tags.orEmpty(), example),
                            ),
                        )
                    }
                }
                StudyMode.entries.forEach { mode ->
                    if (database.cardStateDao().get(wordId, mode) == null) {
                        database.cardStateDao().upsert(CardState.new(CardKey(wordId, mode), timestamp).toEntity())
                    }
                }
                database.deckDao().insertDeckWord(DeckWordEntity(deckId, wordId, position, timestamp))
            }
            ImportResult(deckId, preview.rows.size, 0, emptyList())
        }
    }

    /** Correct source labels for exact bundled CET6 rows without replacing edited content or learning data. */
    suspend fun reconcileCet6SourceMetadata(preview: ImportPreview) = database.withTransaction {
        val exampleDao = database.exampleSentenceDao()
        val wordDao = database.wordDao()
        preview.rows.forEach rowLoop@ { row ->
            if (!row.tags.orEmpty().split(Regex("\\s+")).contains("cet6-core")) return@rowLoop
            val current = wordDao.getByNormalizedSpelling(row.spelling.trim().lowercase(Locale.ROOT)) ?: return@rowLoop
            val expectedDefinition = row.definitionSource?.takeIf(String::isNotBlank)
                ?: definitionSourceFromTags(row.tags.orEmpty())
            if (sourceLabelNeedsUpgrade(current.definitionSource, expectedDefinition)) {
                wordDao.update(current.copy(definitionSource = expectedDefinition, updatedAt = now()))
            }
            val bySentence = exampleDao.getForWord(current.id).associateBy { it.sentenceEn to it.sentenceCn }
            val english = row.exampleEn.splitExamples()
            val chinese = row.exampleCn.splitExamples()
            english.forEachIndexed sentenceLoop@ { index, sentence ->
                val translation = chinese.getOrNull(index)?.ifBlank { null }
                val saved = bySentence[sentence to translation] ?: return@sentenceLoop
                val expected = inlineExampleSourceFromTags(row.tags.orEmpty(), sentence)
                if (sourceLabelNeedsUpgrade(saved.source, expected)) {
                    exampleDao.update(saved.copy(source = expected))
                }
            }
        }
    }

    /** Fill missing bundled metadata in place, preserving word IDs, cards, and personal edits. */
    suspend fun reconcileBuiltinWordMetadata(preview: ImportPreview) = database.withTransaction {
        val rowsBySpelling = preview.rows.associateBy { it.spelling.trim().lowercase(Locale.ROOT) }
        val timestamp = now()
        val updates = database.wordDao().getAll().mapNotNull { current ->
            val row = rowsBySpelling[current.normalizedSpelling] ?: return@mapNotNull null
            val partOfSpeech = current.partOfSpeech.ifBlank { row.partOfSpeech.orEmpty() }
            val expectedSource = row.definitionSource?.takeIf(String::isNotBlank)
                ?: definitionSourceFromTags(row.tags.orEmpty())
            val definitionSource = if (sourceLabelNeedsUpgrade(current.definitionSource, expectedSource)) {
                expectedSource
            } else current.definitionSource
            if (partOfSpeech == current.partOfSpeech && definitionSource == current.definitionSource) null
            else current.copy(partOfSpeech = partOfSpeech, definitionSource = definitionSource, updatedAt = timestamp)
        }
        if (updates.isNotEmpty()) database.wordDao().updateAll(updates)
    }

    private suspend fun upsertWord(row: ImportRow, timestamp: Instant): Long {
        val normalized = row.spelling.trim().lowercase(Locale.ROOT)
        val existing = database.wordDao().getByNormalizedSpelling(normalized)
        if (existing != null) {
            database.wordDao().update(
                existing.copy(
                    spelling = row.spelling.trim(),
                    phonetic = row.phonetic,
                    partOfSpeech = row.partOfSpeech.orEmpty(),
                    definitionCn = row.definitionCn.trim().normalizeDictionaryText(),
                    tags = row.tags.orEmpty(),
                    definitionSource = row.definitionSource?.takeIf(String::isNotBlank)
                        ?: definitionSourceFromTags(row.tags.orEmpty()),
                    mnemonic = row.mnemonic?.trim()?.ifBlank { existing.mnemonic } ?: existing.mnemonic,
                    updatedAt = timestamp,
                    frequencyRank = row.frequencyRank ?: existing.frequencyRank,
                ),
            )
            return existing.id
        }
        return database.wordDao().insert(
            WordEntity(
                spelling = row.spelling.trim(),
                normalizedSpelling = normalized,
                phonetic = row.phonetic,
                partOfSpeech = row.partOfSpeech.orEmpty(),
                definitionCn = row.definitionCn.trim().normalizeDictionaryText(),
                tags = row.tags.orEmpty(),
                definitionSource = row.definitionSource?.takeIf(String::isNotBlank)
                    ?: definitionSourceFromTags(row.tags.orEmpty()),
                mnemonic = row.mnemonic?.trim().orEmpty(),
                createdAt = timestamp,
                updatedAt = timestamp,
                frequencyRank = row.frequencyRank,
            ),
        )
    }
}

private fun String?.splitExamples(): List<String> = this.orEmpty()
    .split("||")
    .map(String::trim)
    .filter(String::isNotBlank)

private fun CardState.toEntity() = CardStateEntity(
    id = id,
    wordId = wordId,
    studyMode = mode,
    state = status,
    difficulty = difficulty,
    stability = stability,
    retrievability = retrievability,
    dueAt = dueAt,
    lastReviewedAt = lastReviewedAt,
    scheduledDays = scheduledDays,
    repetitions = repetitions,
    lapses = lapses,
    learningStep = learningStep,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
