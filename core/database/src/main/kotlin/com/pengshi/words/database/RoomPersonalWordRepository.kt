package com.pengshi.words.database

import androidx.room.withTransaction
import com.pengshi.words.model.CardKey
import com.pengshi.words.model.CardState
import com.pengshi.words.model.PersonalWordInput
import com.pengshi.words.model.PersonalWordResult
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.normalizeDictionaryText
import com.pengshi.words.model.sameMeaningContent
import com.pengshi.words.model.wordSenseKey
import com.pengshi.words.model.sourceLabelNeedsUpgrade
import java.time.Instant
import java.util.Locale

class RoomPersonalWordRepository(
    private val database: PengshiDatabase,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun addWord(input: PersonalWordInput): PersonalWordResult {
        val spelling = input.spelling.trim()
        require(spelling.isNotBlank()) { "单词不能为空" }
        val normalized = spelling.lowercase(Locale.ROOT)
        return database.withTransaction {
            val timestamp = now()
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
                        tags = PERSONAL_TAG,
                        definitionSource = input.definitionSource.trim().ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED },
                        mnemonic = input.mnemonic.trim(),
                        createdAt = timestamp,
                        updatedAt = timestamp,
                    ),
                )
            } else {
                val incomingDefinition = input.definitionCn.trim().normalizeDictionaryText()
                val mainWasBlank = existing.definitionCn.isBlank()
                wordDao.update(
                    existing.copy(
                        phonetic = existing.phonetic ?: input.phonetic.trim().ifBlank { null },
                        partOfSpeech = existing.partOfSpeech.ifBlank { input.partOfSpeech.trim() },
                        definitionCn = if (mainWasBlank) incomingDefinition else existing.definitionCn,
                        definitionSource = if (mainWasBlank) input.definitionSource.trim()
                            .ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED } else existing.definitionSource,
                        mnemonic = existing.mnemonic.ifBlank { input.mnemonic.trim() },
                        tags = (existing.tags.split(Regex("\\s+")) + PERSONAL_TAG)
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

            val deckDao = database.deckDao()
            val currentDeck = deckDao.getByName(PERSONAL_DECK_NAME)
            val deckId = currentDeck?.id ?: deckDao.insert(
                DeckEntity(
                    name = PERSONAL_DECK_NAME,
                    sourceType = DeckSourceTypeEntity.IMPORTED,
                    sourceFileName = PERSONAL_SOURCE_FILE,
                    wordCount = 0,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                ),
            )
            val deckLinks = deckDao.getAllDeckWords().filter { it.deckId == deckId }
            val alreadyLinked = deckLinks.any { it.wordId == wordId }
            if (!alreadyLinked) {
                deckDao.insertDeckWord(DeckWordEntity(deckId, wordId, deckLinks.size, timestamp))
            }
            val updatedDeck = (currentDeck ?: requireNotNull(deckDao.getAll().first { it.id == deckId })).copy(
                wordCount = deckLinks.size + if (alreadyLinked) 0 else 1,
                updatedAt = timestamp,
            )
            deckDao.update(updatedDeck)
            StudyMode.entries.forEach { mode ->
                if (database.cardStateDao().get(wordId, mode) == null) {
                    database.cardStateDao().upsert(CardState.new(CardKey(wordId, mode), timestamp).toEntity())
                }
            }
            PersonalWordResult(wordId, deckId, createdNewWord = existing == null, addedDefinition = addedDefinition)
        }
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
        if (key == wordSenseKey(word.partOfSpeech, word.definitionCn) ||
            sameMeaningContent(word.definitionCn, definition)
        ) {
            if (sourceLabelNeedsUpgrade(word.definitionSource, cleanSource)) {
                database.wordDao().getById(word.id)?.let { current ->
                    database.wordDao().update(current.copy(definitionSource = cleanSource, updatedAt = timestamp))
                }
            }
            return false
        }
        val dao = database.wordSenseDao()
        val existing = dao.getByKey(word.id, key)
        if (existing != null) {
            if (sourceLabelNeedsUpgrade(existing.definitionSource, cleanSource)) {
                dao.update(existing.copy(definitionSource = cleanSource))
            }
            return false
        }
        val sortOrder = dao.getForWord(word.id).maxOfOrNull { it.sortOrder }?.plus(1) ?: 0
        return dao.insert(
            WordSenseEntity(
                wordId = word.id,
                partOfSpeech = partOfSpeech.trim(),
                definitionCn = definition,
                definitionSource = cleanSource,
                normalizedKey = key,
                sortOrder = sortOrder,
                createdAt = timestamp,
            ),
        ) > 0
    }

    private companion object {
        const val PERSONAL_DECK_NAME = "个人词库"
        const val PERSONAL_SOURCE_FILE = "manual"
        const val PERSONAL_TAG = "personal"
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
