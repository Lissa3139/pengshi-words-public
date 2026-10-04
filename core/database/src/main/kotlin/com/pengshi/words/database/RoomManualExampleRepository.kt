package com.pengshi.words.database

import androidx.room.withTransaction
import java.time.Instant

class RoomManualExampleRepository(
    private val database: PengshiDatabase,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun addExample(wordId: Long, sentenceEn: String, sentenceCn: String?, source: String = com.pengshi.words.model.SOURCE_UNVERIFIED): Boolean {
        val english = sentenceEn.trim()
        require(english.isNotBlank()) { "英文例句不能为空" }
        return database.withTransaction {
            val examples = database.exampleSentenceDao().getForWord(wordId)
            if (examples.any { it.sentenceEn.trim().equals(english, ignoreCase = true) }) {
                false
            } else {
                database.exampleSentenceDao().insert(
                    ExampleSentenceEntity(
                        wordId = wordId,
                        sentenceEn = english,
                        sentenceCn = sentenceCn?.trim()?.ifBlank { null },
                        sortOrder = examples.maxOfOrNull { it.sortOrder }?.plus(1) ?: 0,
                        source = source.trim().ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED },
                    ),
                )
                true
            }
        }
    }

    suspend fun updateExample(exampleId: Long, sentenceEn: String, sentenceCn: String?, source: String = com.pengshi.words.model.SOURCE_UNVERIFIED): Boolean {
        val english = sentenceEn.trim()
        require(english.isNotBlank()) { "英文例句不能为空" }
        return database.withTransaction {
            val existing = database.exampleSentenceDao().getById(exampleId) ?: return@withTransaction false
            val duplicate = database.exampleSentenceDao().getForWord(existing.wordId)
                .any { it.id != exampleId && it.sentenceEn.trim().equals(english, ignoreCase = true) }
            if (duplicate) return@withTransaction false
            database.exampleSentenceDao().update(
                existing.copy(
                    sentenceEn = english,
                    sentenceCn = sentenceCn?.trim()?.ifBlank { null },
                    source = source.trim().ifBlank { com.pengshi.words.model.SOURCE_UNVERIFIED },
                ),
            ) > 0
        }
    }

    suspend fun deleteExample(exampleId: Long): Boolean = database.withTransaction {
        if (database.exampleSentenceDao().getById(exampleId) == null) return@withTransaction false
        database.exampleSentenceDao().deleteById(exampleId)
        true
    }
}
