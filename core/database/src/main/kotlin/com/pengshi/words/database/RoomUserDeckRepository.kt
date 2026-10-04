package com.pengshi.words.database

import androidx.room.withTransaction
import com.pengshi.words.model.CardKey
import com.pengshi.words.model.CardState
import com.pengshi.words.model.ImportPreview
import com.pengshi.words.model.ImportRow
import com.pengshi.words.model.PersonalWordInput
import com.pengshi.words.model.PersonalWordResult
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.UserDeckInput
import com.pengshi.words.model.UserDeckBulkResult
import com.pengshi.words.model.normalizeDictionaryText
import com.pengshi.words.model.wordSenseKey
import java.time.Instant
import java.util.Locale

class RoomUserDeckRepository(
    private val database: PengshiDatabase,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun labelUnattributedLiteratureContent() = database.withTransaction {
        val db = database.openHelper.writableDatabase
        val literaryWords = "SELECT dw.wordId FROM deck_words dw JOIN decks d ON d.id = dw.deckId WHERE d.name = '文献阅读'"
        db.execSQL("UPDATE words SET definitionSource = 'ChatGPT 生成' WHERE id IN ($literaryWords) AND (trim(definitionSource) = '' OR definitionSource = '来源待核实')")
        db.execSQL("UPDATE word_senses SET definitionSource = 'ChatGPT 生成' WHERE wordId IN ($literaryWords) AND (trim(definitionSource) = '' OR definitionSource = '来源待核实')")
        db.execSQL("UPDATE example_sentences SET source = 'ChatGPT 生成' WHERE wordId IN ($literaryWords) AND (trim(source) = '' OR source = '来源待核实')")
    }
    suspend fun deleteDeck(deckId: Long): Boolean = database.withTransaction {
        val deck = database.deckDao().getById(deckId) ?: return@withTransaction false
        require(deck.sourceType != DeckSourceTypeEntity.BUILTIN) { "内置词库不能删除" }
        if (deck.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE) return@withTransaction false
        database.deckDao().deleteDeckWords(deckId)
        database.deckDao().update(deck.copy(
            sourceFileName = com.pengshi.words.model.DELETED_USER_DECK_SOURCE,
            wordCount = 0,
            updatedAt = now(),
        ))
        true
    }
    suspend fun createDeck(input: UserDeckInput): Long {
        val name = input.name.trim()
        require(name.isNotBlank()) { "词库名称不能为空" }
        return database.withTransaction {
            val timestamp = now()
            database.deckDao().insert(
                DeckEntity(
                    name = name,
                    sourceType = DeckSourceTypeEntity.USER,
                    sourceFileName = "manual",
                    wordCount = 0,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
        }
    }

    suspend fun addWord(deckId: Long, input: PersonalWordInput): PersonalWordResult {
        val spelling = input.spelling.trim()
        require(spelling.isNotBlank()) { "单词不能为空" }
        val normalized = spelling.lowercase(Locale.ROOT)
        return database.withTransaction {
            val timestamp = now()
            val deck = database.deckDao().getAll().firstOrNull { it.id == deckId }
                ?: error("词库不存在")
            require(deck.sourceType != DeckSourceTypeEntity.BUILTIN) { "内置词库不能直接添加单词，请新建用户词库" }
            val wordDao = database.wordDao()
            val existing = wordDao.getByNormalizedSpelling(normalized)
            var addedDefinition = false
            val wordId = if (existing == null) {
                wordDao.insert(
                    WordEntity(
                        spelling = spelling,
                        normalizedSpelling = normalized,
                        phonetic = input.phonetic.trim().ifBlank { null },
                        partOfSpeech = input.partOfSpeech.trim(),
                        definitionCn = input.definitionCn.trim().normalizeDictionaryText(),
                        tags = USER_TAG,
                        definitionSource = input.definitionSource.trim().ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED },
                        mnemonic = input.mnemonic.trim(),
                        createdAt = timestamp,
                        updatedAt = timestamp,
                    ),
                )
            } else {
                val incomingDefinition = input.definitionCn.trim().normalizeDictionaryText()
                wordDao.update(
                    existing.copy(
                        phonetic = existing.phonetic ?: input.phonetic.trim().ifBlank { null },
                        partOfSpeech = existing.partOfSpeech.ifBlank { input.partOfSpeech.trim() },
                        definitionCn = existing.definitionCn.ifBlank { incomingDefinition },
                        definitionSource = if (existing.definitionCn.isBlank()) input.definitionSource.trim()
                            .ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED } else existing.definitionSource,
                        mnemonic = existing.mnemonic.ifBlank { input.mnemonic.trim() },
                        tags = (existing.tags.split(Regex("\\s+")) + USER_TAG)
                            .filter(String::isNotBlank)
                            .distinct()
                            .joinToString(" "),
                        updatedAt = timestamp,
                    ),
                )
                val current = wordDao.getById(existing.id) ?: existing
                addedDefinition = addSenseIfNeeded(current, input.partOfSpeech, incomingDefinition, input.definitionSource, timestamp)
                existing.id
            }

            val links = database.deckDao().getAllDeckWords().filter { it.deckId == deckId }
            val alreadyLinked = links.any { it.wordId == wordId }
            if (!alreadyLinked) {
                database.deckDao().insertDeckWord(DeckWordEntity(deckId, wordId, links.size, timestamp))
            }
            database.deckDao().update(
                deck.copy(
                    wordCount = links.size + if (alreadyLinked) 0 else 1,
                    updatedAt = timestamp,
                ),
            )
            StudyMode.entries.forEach { mode ->
                if (database.cardStateDao().get(wordId, mode) == null) {
                    database.cardStateDao().upsert(CardState.new(CardKey(wordId, mode), timestamp).toEntity())
                }
            }
            PersonalWordResult(wordId, deckId, createdNewWord = existing == null, addedDefinition = addedDefinition)
        }
    }

    suspend fun updateWord(deckId: Long, wordId: Long, input: PersonalWordInput): PersonalWordResult =
        database.withTransaction {
            val deck = requireEditableDeck(deckId)
            val links = database.deckDao().getAllDeckWords()
            require(links.any { it.deckId == deckId && it.wordId == wordId }) { "单词不在当前词库中" }
            require(!hasBuiltinAssociation(wordId, deckId)) { "六级词条不能修改官方内容，请在个人词库中补充其他单词" }
            val existing = database.wordDao().getAll().firstOrNull { it.id == wordId }
                ?: error("单词不存在")
            val timestamp = now()
            database.wordDao().update(
                existing.copy(
                    phonetic = input.phonetic.trim().ifBlank { null },
                    partOfSpeech = input.partOfSpeech.trim(),
                    definitionCn = input.definitionCn.trim().normalizeDictionaryText(),
                    definitionSource = input.definitionSource.trim().ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED },
                    mnemonic = input.mnemonic.trim(),
                    tags = mergeTags(existing.tags, USER_TAG),
                    updatedAt = timestamp,
                ),
            )
            addExamples(wordId, input.examples)
            PersonalWordResult(wordId, deck.id, createdNewWord = false)
        }

    suspend fun deleteWord(deckId: Long, wordId: Long): Boolean = database.withTransaction {
        val deck = requireEditableDeck(deckId)
        val links = database.deckDao().getAllDeckWords()
        if (links.none { it.deckId == deckId && it.wordId == wordId }) return@withTransaction false
        database.deckDao().deleteDeckWord(deckId, wordId)
        val remaining = links.count { it.deckId == deckId && it.wordId != wordId }
        database.deckDao().update(deck.copy(wordCount = remaining, updatedAt = now()))
        true
    }

    suspend fun bulkUpsert(deckId: Long, preview: ImportPreview): UserDeckBulkResult =
        database.withTransaction {
            val deck = requireEditableDeck(deckId)
            val timestamp = now()
            var importedRows = 0
            var updatedRows = 0
            var linkedRows = 0
            var protectedBuiltinRows = 0
            var addedDefinitions = 0
            preview.rows.forEach { row ->
                val existing = database.wordDao().getByNormalizedSpelling(row.spelling.trim().lowercase(Locale.ROOT))
                val linkedBefore = database.deckDao().getAllDeckWords().any { it.deckId == deckId && it.wordId == existing?.id }
                if (existing != null && hasBuiltinAssociation(existing.id, null)) protectedBuiltinRows++
                val (wordId, addedDefinition) = upsertBulkWord(row, timestamp)
                if (addedDefinition) addedDefinitions++
                if (linkedBefore) {
                    updatedRows++
                } else {
                    val links = database.deckDao().getAllDeckWords().filter { it.deckId == deckId }
                    database.deckDao().insertDeckWord(DeckWordEntity(deckId, wordId, links.size, timestamp))
                    importedRows++
                    if (existing != null) linkedRows++
                }
                ensureCards(wordId, timestamp)
            }
            val finalCount = database.deckDao().getAllDeckWords().count { it.deckId == deckId }
            database.deckDao().update(deck.copy(wordCount = finalCount, updatedAt = timestamp))
            UserDeckBulkResult(
                importedRows = importedRows,
                updatedRows = updatedRows,
                linkedRows = linkedRows,
                skippedRows = preview.errors.size,
                errors = preview.errors,
                protectedBuiltinRows = protectedBuiltinRows,
                addedDefinitions = addedDefinitions,
            )
        }

    private suspend fun upsertBulkWord(row: ImportRow, timestamp: Instant): Pair<Long, Boolean> {
        val normalized = row.spelling.trim().lowercase(Locale.ROOT)
        val existing = database.wordDao().getByNormalizedSpelling(normalized)
        if (existing == null) {
            val wordId = database.wordDao().insert(
                WordEntity(
                    spelling = row.spelling.trim(),
                    normalizedSpelling = normalized,
                    phonetic = row.phonetic?.trim()?.ifBlank { null },
                    partOfSpeech = row.partOfSpeech?.trim().orEmpty(),
                    definitionCn = row.definitionCn.trim().normalizeDictionaryText(),
                    tags = mergeTags(row.tags.orEmpty(), USER_TAG),
                    definitionSource = row.definitionSource?.takeIf(String::isNotBlank)
                        ?: com.pengshi.words.model.definitionSourceFromTags(row.tags.orEmpty()),
                    mnemonic = row.mnemonic?.trim().orEmpty(),
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    frequencyRank = row.frequencyRank,
                ),
            )
            addExamples(wordId, row.examplePairs())
            return wordId to false
        }
        val addedSense: Boolean
        if (hasBuiltinAssociation(existing.id, null)) {
            val mnemonic = row.mnemonic?.trim().orEmpty()
            if (existing.mnemonic.isBlank() && mnemonic.isNotBlank()) {
                database.wordDao().update(existing.copy(mnemonic = mnemonic, updatedAt = timestamp))
            }
            val current = database.wordDao().getById(existing.id) ?: existing
            addedSense = addSenseIfNeeded(current, row.partOfSpeech.orEmpty(), row.definitionCn, row.definitionSource
                ?: com.pengshi.words.model.definitionSourceFromTags(row.tags.orEmpty()), timestamp)
        } else {
            val incomingDefinition = row.definitionCn.trim().normalizeDictionaryText()
            val mainWasBlank = existing.definitionCn.isBlank()
            database.wordDao().update(
                existing.copy(
                    phonetic = row.phonetic?.trim()?.ifBlank { existing.phonetic } ?: existing.phonetic,
                    partOfSpeech = row.partOfSpeech?.trim()?.ifBlank { existing.partOfSpeech } ?: existing.partOfSpeech,
                    definitionCn = if (mainWasBlank) incomingDefinition else existing.definitionCn,
                    definitionSource = if (mainWasBlank && row.definitionCn.isNotBlank())
                        row.definitionSource?.takeIf(String::isNotBlank)
                            ?: com.pengshi.words.model.definitionSourceFromTags(row.tags.orEmpty()) else existing.definitionSource,
                    mnemonic = row.mnemonic?.trim()?.ifBlank { existing.mnemonic } ?: existing.mnemonic,
                    tags = mergeTags(existing.tags, row.tags.orEmpty(), USER_TAG),
                    frequencyRank = row.frequencyRank ?: existing.frequencyRank,
                    updatedAt = timestamp,
                ),
            )
            val current = database.wordDao().getById(existing.id) ?: existing
            addedSense = if (mainWasBlank) false else addSenseIfNeeded(
                current, row.partOfSpeech.orEmpty(), incomingDefinition,
                row.definitionSource ?: com.pengshi.words.model.definitionSourceFromTags(row.tags.orEmpty()), timestamp,
            )
            addExamples(existing.id, row.examplePairs())
        }
        return existing.id to addedSense
    }

    private suspend fun addSenseIfNeeded(
        word: WordEntity,
        partOfSpeech: String,
        definitionCn: String,
        source: String,
        timestamp: Instant,
    ): Boolean {
        val definition = definitionCn.trim().normalizeDictionaryText()
        if (definition.isBlank()) return false
        val key = wordSenseKey(partOfSpeech, definition)
        val cleanSource = source.trim().ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED }
        if (key == wordSenseKey(word.partOfSpeech, word.definitionCn)) {
            if (com.pengshi.words.model.sourceLabelNeedsUpgrade(word.definitionSource, cleanSource)) {
                database.wordDao().getById(word.id)?.let { current ->
                    database.wordDao().update(current.copy(definitionSource = cleanSource, updatedAt = timestamp))
                }
            }
            return false
        }
        val dao = database.wordSenseDao()
        val existing = dao.getByKey(word.id, key)
        if (existing != null) {
            if (com.pengshi.words.model.sourceLabelNeedsUpgrade(existing.definitionSource, cleanSource)) {
                dao.update(existing.copy(definitionSource = cleanSource))
            }
            return false
        }
        val order = dao.getForWord(word.id).maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        return dao.insert(
            WordSenseEntity(
                wordId = word.id,
                partOfSpeech = partOfSpeech.trim(),
                definitionCn = definition,
                definitionSource = cleanSource,
                normalizedKey = key,
                sortOrder = order,
                createdAt = timestamp,
            ),
        ) > 0
    }

    private suspend fun requireEditableDeck(deckId: Long): DeckEntity {
        val deck = database.deckDao().getAll().firstOrNull { it.id == deckId } ?: error("词库不存在")
        require(deck.sourceType != DeckSourceTypeEntity.BUILTIN) { "内置词库不能修改" }
        return deck
    }

    private suspend fun hasBuiltinAssociation(wordId: Long, exceptDeckId: Long?): Boolean {
        val decksById = database.deckDao().getAll().associateBy { it.id }
        return database.deckDao().getDeckIdsForWord(wordId).any { deckId ->
            deckId != exceptDeckId && decksById[deckId]?.sourceType == DeckSourceTypeEntity.BUILTIN
        }
    }

    private suspend fun ensureCards(wordId: Long, timestamp: Instant) {
        StudyMode.entries.forEach { mode ->
            if (database.cardStateDao().get(wordId, mode) == null) {
                database.cardStateDao().upsert(CardState.new(CardKey(wordId, mode), timestamp).toEntity())
            }
        }
    }

    private suspend fun addExamples(wordId: Long, examples: List<com.pengshi.words.model.PersonalExampleInput>) {
        val existing = database.exampleSentenceDao().getForWord(wordId)
        val existingBySentence = existing.associateBy {
            it.sentenceEn.trim().lowercase(Locale.ROOT) to it.sentenceCn?.trim()?.ifBlank { null }
        }
        val known = existing.mapTo(mutableSetOf()) { it.sentenceEn.trim().lowercase(Locale.ROOT) }
        var nextSortOrder = existing.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        examples.forEach { example ->
            val sentenceEn = example.sentenceEn.trim()
            val key = sentenceEn.lowercase(Locale.ROOT)
            val translation = example.sentenceCn?.trim()?.ifBlank { null }
            if (sentenceEn.isNotBlank() && known.add(key)) {
                database.exampleSentenceDao().insert(
                    ExampleSentenceEntity(
                        wordId = wordId,
                        sentenceEn = sentenceEn,
                        sentenceCn = example.sentenceCn?.trim()?.ifBlank { null },
                        sortOrder = nextSortOrder++,
                        source = example.source.trim().ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED },
                    ),
                )
            } else if (sentenceEn.isNotBlank()) {
                val current = existingBySentence[key to translation]
                if (current != null && com.pengshi.words.model.isUnverifiedSource(current.source) &&
                    example.source.isNotBlank() && example.source != com.pengshi.words.model.SOURCE_UNVERIFIED
                ) database.exampleSentenceDao().update(current.copy(source = example.source.trim()))
            }
        }
    }

    private fun mergeTags(vararg values: String): String = values
        .flatMap { it.split(Regex("\\s+")) }
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" ")

    private companion object {
        const val USER_TAG = "user-content"
    }
}

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

private fun ImportRow.examplePairs(): List<com.pengshi.words.model.PersonalExampleInput> {
    val english = exampleEn.orEmpty().split("||").map(String::trim).filter(String::isNotBlank)
    val chinese = exampleCn.orEmpty().split("||").map(String::trim)
    return english.mapIndexed { index, sentence ->
        com.pengshi.words.model.PersonalExampleInput(
            sentence, chinese.getOrNull(index)?.ifBlank { null },
            exampleSource?.takeIf(String::isNotBlank) ?: com.pengshi.words.model.SOURCE_UNVERIFIED,
        )
    }
}
