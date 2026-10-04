package com.pengshi.words.model

import java.time.Instant

enum class CardStatus {
    NEW,
    LEARNING,
    REVIEW,
    RELEARNING,
}

data class CardState(
    val id: Long = 0,
    val wordId: Long,
    val mode: StudyMode,
    val status: CardStatus,
    val difficulty: Double,
    val stability: Double,
    val retrievability: Double,
    val dueAt: Instant?,
    val lastReviewedAt: Instant?,
    val scheduledDays: Int,
    val repetitions: Int,
    val lapses: Int,
    val learningStep: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val key: CardKey
        get() = CardKey(wordId, mode)

    companion object {
        fun new(key: CardKey, createdAt: Instant): CardState = CardState(
            wordId = key.wordId,
            mode = key.mode,
            status = CardStatus.NEW,
            difficulty = 0.0,
            stability = 0.0,
            retrievability = 0.0,
            dueAt = null,
            lastReviewedAt = null,
            scheduledDays = 0,
            repetitions = 0,
            lapses = 0,
            learningStep = 0,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }
}

interface CardStateRepository {
    suspend fun get(key: CardKey): CardState?

    suspend fun upsert(state: CardState)
}
