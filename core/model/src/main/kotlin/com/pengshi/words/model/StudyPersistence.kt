package com.pengshi.words.model

data class FeedbackContext(
    val plan: DailyPlan,
    val item: DailyPlanItem,
    val event: IntradayReviewEvent,
    val card: CardState,
    /** All existing events for this item, including other modes, ordered by step index. */
    val itemEvents: List<IntradayReviewEvent>,
)

data class FeedbackTransaction(
    val updatedCard: CardState,
    val reviewLog: ReviewLog,
    val completedEvent: IntradayReviewEvent,
    val followUpEvents: List<IntradayReviewEvent>,
    val updatedItem: DailyPlanItem,
    val updatedPlan: DailyPlan,
    val syncEventId: String? = null,
    /** Previously pending confirmations made obsolete by this response. */
    val skippedEvents: List<IntradayReviewEvent> = emptyList(),
)

data class FeedbackSubmission(
    val revisionOfEventId: String? = null,
    val recordSyncEvent: Boolean = true,
)

/** In-memory token used to revise the most recent feedback before moving on. */
data class FeedbackUndoToken(
    val event: IntradayReviewEvent,
    val previousCard: CardState,
    val previousItem: DailyPlanItem,
    val previousPlan: DailyPlan,
    val reviewLogId: Long,
    val followUpEventIds: List<Long>,
    val autoAddedItemIds: List<Long> = emptyList(),
    val syncEventId: String? = null,
    /** Original pending sibling events to restore if this feedback is revised. */
    val skippedEventSnapshots: List<IntradayReviewEvent> = emptyList(),
)

interface DailyStudyRepository {
    /**
     * Loads the pending event, mode-specific card, item, plan and item events, then invokes
     * [transform] inside the same transaction as every resulting write. The callback must
     * be a pure calculation; scheduling policy belongs to the caller. Existing completed or
     * skipped events and persisted identities cannot be changed; explicitly listed pending
     * siblings may transition to skipped. Any failure rolls back all writes. Returns the
     * persisted result, including generated card/log/follow-up ids.
     */
    suspend fun submitFeedback(
        eventId: Long,
        transform: (FeedbackContext) -> FeedbackTransaction,
    ): FeedbackTransaction

    /** Same local transaction with optional cross-device event metadata. */
    suspend fun submitFeedback(
        eventId: Long,
        transform: (FeedbackContext) -> FeedbackTransaction,
        submission: FeedbackSubmission,
    ): FeedbackTransaction = submitFeedback(eventId, transform)

    /** Reopens one just-completed event and restores the card/plan state before its feedback. */
    suspend fun undoFeedback(token: FeedbackUndoToken) {
        error("Feedback revision is not supported by this repository")
    }
}

data class DailyPlanEntry(
    val item: DailyPlanItem,
    val initialEvent: IntradayReviewEvent,
)

data class DailyPlanCandidate(
    val wordId: Long,
    val isNew: Boolean,
    val dueAt: java.time.Instant?,
    val retrievability: Double?,
    val overdueSeconds: Long,
    val familyKey: String? = null,
    val initialKey: String? = null,
    val difficulty: Double? = null,
    val lapses: Int = 0,
    val lastReviewedAt: java.time.Instant? = null,
    val intervalDays: Long? = null,
    val frequencyRank: Int? = null,
)

interface DailyPlanRepository : DailyStudyRepository {
    suspend fun getPlan(localDate: java.time.LocalDate): DailyPlan?
    suspend fun getPlanById(planId: Long): DailyPlan?
    suspend fun getItem(itemId: Long): DailyPlanItem?
    suspend fun getItems(planId: Long): List<DailyPlanItem>
    suspend fun getEvents(itemId: Long): List<IntradayReviewEvent>
    suspend fun getWord(wordId: Long): Word?
    suspend fun getWordSenses(wordId: Long): List<WordSense> = emptyList()
    suspend fun getExamples(wordId: Long): List<ExampleSentence>
    suspend fun getRelatedWords(wordId: Long): List<RelatedWord>
    suspend fun getCard(wordId: Long, mode: StudyMode): CardState?
    suspend fun getCandidates(mode: StudyMode, now: java.time.Instant): List<DailyPlanCandidate>
    suspend fun getDueCandidatesBefore(
        mode: StudyMode,
        now: java.time.Instant,
        dueBefore: java.time.Instant,
    ): List<DailyPlanCandidate> = getCandidatesBefore(mode, now, dueBefore)

    suspend fun getNewCandidates(mode: StudyMode, now: java.time.Instant): List<DailyPlanCandidate> =
        getCandidates(mode, now).filter { it.isNew }

    suspend fun getEventsForItems(itemIds: List<Long>): List<IntradayReviewEvent> =
        itemIds.flatMap { getEvents(it) }

    /**
     * Loads NEW cards and review cards due before the exclusive natural-day boundary.
     * The default keeps simpler repository implementations source-compatible.
     */
    suspend fun getCandidatesBefore(
        mode: StudyMode,
        now: java.time.Instant,
        dueBefore: java.time.Instant,
    ): List<DailyPlanCandidate> = getCandidates(mode, now)

    suspend fun getCandidates(
        mode: StudyMode,
        now: java.time.Instant,
        eligibleWordIds: Set<Long>,
    ): List<DailyPlanCandidate> = getCandidates(mode, now).filterByWordPool(eligibleWordIds, emptySet())

    suspend fun getNewCandidates(
        mode: StudyMode,
        now: java.time.Instant,
        eligibleWordIds: Set<Long>,
    ): List<DailyPlanCandidate> = getNewCandidates(mode, now).filterByWordPool(eligibleWordIds, emptySet())

    suspend fun getCandidatesBefore(
        mode: StudyMode,
        now: java.time.Instant,
        dueBefore: java.time.Instant,
        eligibleWordIds: Set<Long>,
    ): List<DailyPlanCandidate> = getCandidatesBefore(mode, now, dueBefore).filterByWordPool(eligibleWordIds, emptySet())
    suspend fun createPlan(plan: DailyPlan, entries: List<DailyPlanEntry>): DailyPlan
    suspend fun appendToPlan(plan: DailyPlan, entries: List<DailyPlanEntry>): DailyPlan =
        error("Appending words is not supported by this repository")
    /** Replaces only unseen NEW items; completed or in-progress items must be excluded by the caller. */
    suspend fun replaceUnseenNewItems(
        plan: DailyPlan,
        removeItemIds: Set<Long>,
        entries: List<DailyPlanEntry>,
        now: java.time.Instant,
    ): DailyPlan = error("Replacing unseen new items is not supported by this repository")
    /** Reopens only today's due-review items for another review. */
    suspend fun resetTodayReview(localDate: java.time.LocalDate, now: java.time.Instant): Int =
        error("Resetting today's review is not supported by this repository")
}

data class StudyDataSnapshot(
    val words: List<Word>,
    val exampleSentences: List<ExampleSentence>,
    val decks: List<Deck>,
    val deckWords: List<DeckWord>,
    val cardStates: List<CardState>,
    val dailyPlans: List<DailyPlan>,
    val dailyPlanItems: List<DailyPlanItem>,
    val intradayReviewEvents: List<IntradayReviewEvent>,
    val reviewLogs: List<ReviewLog>,
    val wordSenses: List<WordSense> = emptyList(),
)

interface StudyDataSnapshotGateway {
    /** Reads all nine persisted tables from one consistent database transaction. */
    suspend fun snapshot(): StudyDataSnapshot

    /**
     * Inserts a validated snapshot in one transaction, preserving ids and log history.
     * Existing rows are never deleted or overwritten: primary/unique-key or foreign-key
     * conflicts abort the entire restore. The caller owns validation, conflict preview
     * and resolution, serialization and settings stored outside the database.
     */
    suspend fun restore(snapshot: StudyDataSnapshot)

    /** Replaces local study tables atomically; callers must prove local data is disposable first. */
    suspend fun replace(snapshot: StudyDataSnapshot) {
        restore(snapshot)
    }

    /** Merges dictionary and personal-deck content without replacing learning state. */
    suspend fun mergeContent(snapshot: StudyDataSnapshot) {
        error("Content-only snapshot merge is not supported")
    }
}
