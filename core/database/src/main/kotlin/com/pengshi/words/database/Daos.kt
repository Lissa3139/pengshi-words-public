package com.pengshi.words.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.pengshi.words.model.CardStatus
import com.pengshi.words.model.StudyMode
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

data class WordPoolDashboardCounts(
    val wordCount: Int,
    val dueCount: Int,
    val newCount: Int,
)

data class DeckDashboardCountRow(
    val id: Long,
    val name: String,
    val sourceType: String,
    val wordCount: Int,
    val totalWordCount: Int,
    val dueCount: Int,
    val newCount: Int,
)

data class ExcludedWordRow(
    val id: Long,
    val spelling: String,
    val phonetic: String?,
    val partOfSpeech: String,
    val definitionCn: String,
    val deckId: Long?,
    val deckName: String?,
)

data class WordLibraryRow(
    val id: Long,
    val spelling: String,
    val phonetic: String?,
    val partOfSpeech: String,
    val definitionCn: String,
    val definitionSource: String,
    val mnemonic: String = "",
    val deckId: Long?,
    val deckName: String?,
)

data class StatsReviewLogRow(
    val id: Long,
    val wordId: Long,
    val mode: StudyMode,
    val reviewedAt: Instant,
    val feedback: com.pengshi.words.model.Feedback,
    val responseTimeMs: Long,
    val nextStateSnapshot: String,
)

data class DailyPlanCandidateRow(
    val wordId: Long,
    val spelling: String,
    val tags: String,
    val frequencyRank: Int?,
    val cardWordId: Long?,
    val state: CardStatus?,
    val dueAt: Instant?,
    val retrievability: Double?,
    val difficulty: Double?,
    val lapses: Int?,
    val lastReviewedAt: Instant?,
)

data class WordTagSeedStatus(
    val wordCount: Int,
    val missingFrequencyRankCount: Int,
)

@Dao
interface WordDao {
    @Insert suspend fun insert(word: WordEntity): Long
    @Upsert suspend fun upsert(word: WordEntity): Long
    @Update suspend fun update(word: WordEntity): Int
    @Update suspend fun updateAll(words: List<WordEntity>): Int
    @Insert suspend fun insertAll(words: List<WordEntity>)
    @Query("SELECT * FROM words WHERE normalizedSpelling = :normalized LIMIT 1")
    suspend fun getByNormalizedSpelling(normalized: String): WordEntity?
    @Query("SELECT * FROM words WHERE id = :wordId LIMIT 1")
    suspend fun getById(wordId: Long): WordEntity?
    @Query("SELECT * FROM words WHERE id IN (:wordIds) ORDER BY id")
    suspend fun getByIds(wordIds: List<Long>): List<WordEntity>
    @Query(
        """
        SELECT COUNT(*) AS wordCount,
            COALESCE(SUM(CASE WHEN frequencyRank IS NULL THEN 1 ELSE 0 END), 0) AS missingFrequencyRankCount
        FROM words WHERE instr(' ' || tags || ' ', ' ' || :tag || ' ') > 0
        """,
    )
    suspend fun getTagSeedStatus(tag: String): WordTagSeedStatus
    @Query("SELECT * FROM words WHERE instr(' ' || tags || ' ', ' ' || :tag || ' ') > 0 ORDER BY id")
    suspend fun getByTag(tag: String): List<WordEntity>
    @Query(
        """
        SELECT w.id AS id, w.spelling AS spelling, w.phonetic AS phonetic,
            w.partOfSpeech AS partOfSpeech, w.definitionCn AS definitionCn,
            (SELECT dw.deckId FROM deck_words dw WHERE dw.wordId = w.id ORDER BY dw.position, dw.deckId LIMIT 1) AS deckId,
            (SELECT d.name FROM decks d WHERE d.id = (
                SELECT dw.deckId FROM deck_words dw WHERE dw.wordId = w.id ORDER BY dw.position, dw.deckId LIMIT 1
            )) AS deckName
        FROM words w WHERE w.id IN (:wordIds) ORDER BY w.id
        """,
    )
    suspend fun getExcludedWordRows(wordIds: List<Long>): List<ExcludedWordRow>
    @Query(
        """
        SELECT w.id AS id, w.spelling AS spelling, w.phonetic AS phonetic,
            w.partOfSpeech AS partOfSpeech, w.definitionCn AS definitionCn,
            w.definitionSource AS definitionSource, w.mnemonic AS mnemonic, dw.deckId AS deckId, d.name AS deckName
        FROM deck_words AS dw
        JOIN words AS w ON w.id = dw.wordId
        JOIN decks AS d ON d.id = dw.deckId
        ORDER BY dw.deckId, dw.position, w.id
        """,
    )
    suspend fun getLibraryRows(): List<WordLibraryRow>
    @Query(
        """
        SELECT w.id AS id, w.spelling AS spelling, w.phonetic AS phonetic,
            w.partOfSpeech AS partOfSpeech, w.definitionCn AS definitionCn,
            w.definitionSource AS definitionSource, w.mnemonic AS mnemonic, NULL AS deckId, NULL AS deckName
        FROM words AS w ORDER BY w.spelling, w.id
        """,
    )
    suspend fun getAllLibraryRows(): List<WordLibraryRow>
    @Query(
        """
        SELECT w.id AS id, w.spelling AS spelling, w.phonetic AS phonetic,
            w.partOfSpeech AS partOfSpeech, w.definitionCn AS definitionCn,
            w.definitionSource AS definitionSource, w.mnemonic AS mnemonic, d.id AS deckId, d.name AS deckName
        FROM deck_words dw
        JOIN words w ON w.id = dw.wordId
        JOIN decks d ON d.id = dw.deckId
        WHERE dw.deckId = :deckId
        ORDER BY dw.position, w.id
        """,
    )
    suspend fun getLibraryRowsForDeck(deckId: Long): List<WordLibraryRow>
    @Query(
        """
        SELECT COUNT(w.id) AS wordCount,
            COALESCE(SUM(CASE WHEN c.wordId IS NOT NULL AND c.state != 'NEW'
                AND c.dueAt IS NOT NULL AND c.dueAt <= :nowEpochMillis THEN 1 ELSE 0 END), 0) AS dueCount,
            COALESCE(SUM(CASE WHEN c.wordId IS NULL OR c.state = 'NEW' THEN 1 ELSE 0 END), 0) AS newCount
        FROM words w
        LEFT JOIN card_states c ON c.wordId = w.id AND c.studyMode = :mode
        WHERE EXISTS (
            SELECT 1 FROM deck_words activeLink JOIN decks activeDeck ON activeDeck.id = activeLink.deckId
            WHERE activeLink.wordId = w.id AND activeDeck.sourceFileName != '__deleted_user_deck__'
        ) AND (:includeAllDecks OR EXISTS (
            SELECT 1 FROM deck_words dw WHERE dw.wordId = w.id AND dw.deckId IN (:includedDeckIds)
        ))
        AND (:restrictToWordIds = 0 OR w.id IN (:wordIds))
        """,
    )
    suspend fun getDashboardPoolCounts(
        includeAllDecks: Boolean,
        includedDeckIds: List<Long>,
        restrictToWordIds: Int,
        wordIds: List<Long>,
        nowEpochMillis: Long,
        mode: StudyMode,
    ): WordPoolDashboardCounts
    @Query("SELECT id FROM words ORDER BY id")
    suspend fun getAllIds(): List<Long>
    @Query("SELECT DISTINCT w.id FROM words w JOIN deck_words dw ON dw.wordId = w.id JOIN decks d ON d.id = dw.deckId WHERE d.sourceFileName != '__deleted_user_deck__' ORDER BY w.id")
    suspend fun getAllActiveIds(): List<Long>
    @Query(
        """
        SELECT w.id AS wordId, w.spelling AS spelling, w.tags AS tags,
            w.frequencyRank AS frequencyRank, c.wordId AS cardWordId,
            c.state AS state, c.dueAt AS dueAt, c.retrievability AS retrievability,
            c.difficulty AS difficulty, c.lapses AS lapses, c.lastReviewedAt AS lastReviewedAt
        FROM words w
        LEFT JOIN card_states c ON c.wordId = w.id AND c.studyMode = :mode
        WHERE (c.wordId IS NULL OR c.state = 'NEW' OR (c.dueAt IS NOT NULL AND c.dueAt < :dueBeforeEpochMillis))
            AND EXISTS (SELECT 1 FROM deck_words activeLink WHERE activeLink.wordId = w.id)
        ORDER BY w.id
        """,
    )
    suspend fun getDailyPlanCandidateRows(mode: StudyMode, dueBeforeEpochMillis: Long): List<DailyPlanCandidateRow>
    @Query(
        """
        SELECT w.id AS wordId, w.spelling AS spelling, w.tags AS tags,
            w.frequencyRank AS frequencyRank, c.wordId AS cardWordId,
            c.state AS state, c.dueAt AS dueAt, c.retrievability AS retrievability,
            c.difficulty AS difficulty, c.lapses AS lapses, c.lastReviewedAt AS lastReviewedAt
        FROM words w
        LEFT JOIN card_states c ON c.wordId = w.id AND c.studyMode = :mode
        WHERE (c.wordId IS NULL OR c.state = 'NEW')
            AND EXISTS (SELECT 1 FROM deck_words activeLink WHERE activeLink.wordId = w.id)
        ORDER BY w.id
        """,
    )
    suspend fun getNewCandidateRows(mode: StudyMode): List<DailyPlanCandidateRow>
    @Query(
        """
        SELECT w.id AS wordId, w.spelling AS spelling, w.tags AS tags,
            w.frequencyRank AS frequencyRank, c.wordId AS cardWordId,
            c.state AS state, c.dueAt AS dueAt, c.retrievability AS retrievability,
            c.difficulty AS difficulty, c.lapses AS lapses, c.lastReviewedAt AS lastReviewedAt
        FROM words w
        JOIN card_states c ON c.wordId = w.id AND c.studyMode = :mode
        WHERE c.state != 'NEW' AND c.dueAt IS NOT NULL AND c.dueAt < :dueBeforeEpochMillis
            AND EXISTS (SELECT 1 FROM deck_words activeLink WHERE activeLink.wordId = w.id)
        ORDER BY w.id
        """,
    )
    suspend fun getDueCandidateRows(mode: StudyMode, dueBeforeEpochMillis: Long): List<DailyPlanCandidateRow>
    @Query("SELECT * FROM words ORDER BY id")
    suspend fun getAll(): List<WordEntity>
    @Query("SELECT COUNT(*) FROM words")
    suspend fun count(): Int
    @Query("DELETE FROM words")
    suspend fun deleteAll()
}

@Dao
interface WordSenseDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sense: WordSenseEntity): Long
    @Update suspend fun update(sense: WordSenseEntity)
    @Insert
    suspend fun insertAll(senses: List<WordSenseEntity>)
    @Query("SELECT * FROM word_senses WHERE wordId = :wordId ORDER BY sortOrder, id")
    suspend fun getForWord(wordId: Long): List<WordSenseEntity>
    @Query("SELECT * FROM word_senses ORDER BY wordId, sortOrder, id")
    suspend fun getAll(): List<WordSenseEntity>
    @Query("SELECT * FROM word_senses WHERE wordId = :wordId AND normalizedKey = :normalizedKey LIMIT 1")
    suspend fun getByKey(wordId: Long, normalizedKey: String): WordSenseEntity?
    @Query("DELETE FROM word_senses WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
    @Query("DELETE FROM word_senses")
    suspend fun deleteAll()
}

@Dao
interface ExampleSentenceDao {
    @Insert suspend fun insert(sentence: ExampleSentenceEntity): Long
    @Insert suspend fun insertAll(sentences: List<ExampleSentenceEntity>)
    @Update suspend fun update(sentence: ExampleSentenceEntity): Int
    @Query("SELECT * FROM example_sentences WHERE id = :sentenceId LIMIT 1")
    suspend fun getById(sentenceId: Long): ExampleSentenceEntity?
    @Query("SELECT * FROM example_sentences WHERE wordId = :wordId ORDER BY sortOrder, id")
    suspend fun getForWord(wordId: Long): List<ExampleSentenceEntity>
    @Query(
        """
        SELECT examples.* FROM example_sentences AS examples
        JOIN deck_words AS links ON links.wordId = examples.wordId
        WHERE links.deckId = :deckId
        ORDER BY links.position, examples.wordId, examples.sortOrder, examples.id
        """,
    )
    suspend fun getForDeck(deckId: Long): List<ExampleSentenceEntity>
    @Query("DELETE FROM example_sentences WHERE id = :sentenceId")
    suspend fun deleteById(sentenceId: Long)
    @Query("DELETE FROM example_sentences WHERE wordId = :wordId")
    suspend fun deleteForWord(wordId: Long)
    @Query("UPDATE example_sentences SET sortOrder = sortOrder + :offset WHERE wordId = :wordId")
    suspend fun shiftSortOrderForWord(wordId: Long, offset: Int)
    @Query("SELECT * FROM example_sentences ORDER BY id")
    suspend fun getAll(): List<ExampleSentenceEntity>
    @Query("DELETE FROM example_sentences")
    suspend fun deleteAll()
}

@Dao
interface DeckDao {
    @Insert suspend fun insert(deck: DeckEntity): Long
    @Insert suspend fun insertAll(decks: List<DeckEntity>)
    @Update suspend fun update(deck: DeckEntity): Int
    @Insert suspend fun insertDeckWord(deckWord: DeckWordEntity)
    @Insert suspend fun insertDeckWords(deckWords: List<DeckWordEntity>)
    @Query("DELETE FROM deck_words WHERE deckId = :deckId AND wordId = :wordId")
    suspend fun deleteDeckWord(deckId: Long, wordId: Long)
    @Query("DELETE FROM deck_words WHERE deckId = :deckId")
    suspend fun deleteDeckWords(deckId: Long)
    @Query("SELECT deckId FROM deck_words WHERE wordId = :wordId ORDER BY deckId")
    suspend fun getDeckIdsForWord(wordId: Long): List<Long>
    @Query("SELECT * FROM decks ORDER BY id")
    suspend fun getAll(): List<DeckEntity>
    @Query(
        """
        SELECT d.id AS id, d.name AS name, d.sourceType AS sourceType,
            COUNT(DISTINCT w.id) AS wordCount,
            (SELECT COUNT(DISTINCT corpus.id) FROM words AS corpus) AS totalWordCount,
            COUNT(DISTINCT CASE WHEN c.wordId IS NOT NULL AND c.state != 'NEW'
                AND c.dueAt IS NOT NULL AND c.dueAt <= :nowEpochMillis THEN w.id END) AS dueCount,
            COUNT(DISTINCT CASE WHEN w.id IS NOT NULL AND (c.wordId IS NULL OR c.state = 'NEW')
                THEN w.id END) AS newCount
        FROM decks d
        LEFT JOIN deck_words dw ON dw.deckId = d.id
        LEFT JOIN words w ON w.id = dw.wordId
        LEFT JOIN card_states c ON c.wordId = w.id AND c.studyMode = :mode
        GROUP BY d.id ORDER BY d.id
        """,
    )
    suspend fun getDashboardCounts(nowEpochMillis: Long, mode: StudyMode): List<DeckDashboardCountRow>
    @Query("SELECT * FROM decks WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): DeckEntity?
    @Query("SELECT * FROM decks WHERE sourceFileName = :sourceFileName LIMIT 1")
    suspend fun getBySourceFileName(sourceFileName: String): DeckEntity?
    @Query("SELECT * FROM decks WHERE id = :deckId LIMIT 1")
    suspend fun getById(deckId: Long): DeckEntity?
    @Query("DELETE FROM decks WHERE id = :deckId")
    suspend fun deleteById(deckId: Long)
    @Query("SELECT * FROM deck_words ORDER BY deckId, wordId")
    suspend fun getAllDeckWords(): List<DeckWordEntity>
    @Query("SELECT * FROM deck_words WHERE deckId IN (:deckIds) ORDER BY deckId, wordId")
    suspend fun getDeckWordsForDecks(deckIds: List<Long>): List<DeckWordEntity>
    @Query("DELETE FROM deck_words")
    suspend fun deleteAllDeckWords()
    @Query("DELETE FROM decks")
    suspend fun deleteAll()
}

@Dao
interface CardStateDao {
    @Insert suspend fun insert(state: CardStateEntity): Long
    @Insert suspend fun insertAll(states: List<CardStateEntity>)
    @Query(
        """
        UPDATE card_states SET
            state = :state,
            difficulty = :difficulty,
            stability = :stability,
            retrievability = :retrievability,
            dueAt = :dueAt,
            lastReviewedAt = :lastReviewedAt,
            scheduledDays = :scheduledDays,
            repetitions = :repetitions,
            lapses = :lapses,
            learningStep = :learningStep,
            updatedAt = :updatedAt
        WHERE wordId = :wordId AND studyMode = :studyMode
        """,
    )
    suspend fun updateByKey(
        wordId: Long,
        studyMode: StudyMode,
        state: CardStatus,
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        dueAt: Instant?,
        lastReviewedAt: Instant?,
        scheduledDays: Int,
        repetitions: Int,
        lapses: Int,
        learningStep: Int,
        updatedAt: Instant,
    ): Int

    @Transaction
    suspend fun upsert(state: CardStateEntity): Long {
        val updated = updateByKey(
            wordId = state.wordId,
            studyMode = state.studyMode,
            state = state.state,
            difficulty = state.difficulty,
            stability = state.stability,
            retrievability = state.retrievability,
            dueAt = state.dueAt,
            lastReviewedAt = state.lastReviewedAt,
            scheduledDays = state.scheduledDays,
            repetitions = state.repetitions,
            lapses = state.lapses,
            learningStep = state.learningStep,
            updatedAt = state.updatedAt,
        )
        return if (updated == 0) insert(state.copy(id = 0))
        else requireNotNull(get(state.wordId, state.studyMode)).id
    }
    @Query("SELECT * FROM card_states WHERE wordId = :wordId AND studyMode = :mode LIMIT 1")
    suspend fun get(wordId: Long, mode: StudyMode): CardStateEntity?
    @Query("SELECT * FROM card_states WHERE wordId = :wordId ORDER BY studyMode")
    suspend fun getForWord(wordId: Long): List<CardStateEntity>
    @Query("SELECT * FROM card_states ORDER BY id")
    suspend fun getAll(): List<CardStateEntity>
    @Query("DELETE FROM card_states")
    suspend fun deleteAll()
}

@Dao
interface DailyPlanDao {
    @Insert suspend fun insertPlan(plan: DailyPlanEntity): Long
    @Insert suspend fun insertItem(item: DailyPlanItemEntity): Long
    @Insert suspend fun insertEvent(event: IntradayReviewEventEntity): Long
    @Insert suspend fun insertPlans(plans: List<DailyPlanEntity>)
    @Insert suspend fun insertItems(items: List<DailyPlanItemEntity>): List<Long>
    @Insert suspend fun insertEvents(events: List<IntradayReviewEventEntity>): List<Long>
    @Update suspend fun updatePlan(plan: DailyPlanEntity)
    @Update suspend fun updateItem(item: DailyPlanItemEntity)
    @Query("DELETE FROM daily_plan_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)
    @Query("DELETE FROM daily_plan_items WHERE id IN (:itemIds)")
    suspend fun deleteItems(itemIds: List<Long>)
    @Update suspend fun updateEvent(event: IntradayReviewEventEntity)
    @Query("DELETE FROM intraday_review_events WHERE id = :eventId")
    suspend fun deleteEvent(eventId: Long)
    @Query("DELETE FROM intraday_review_events WHERE dailyPlanItemId = :itemId")
    suspend fun deleteEventsForItem(itemId: Long)
    @Query("DELETE FROM intraday_review_events WHERE dailyPlanItemId IN (:itemIds)")
    suspend fun deleteEventsForItems(itemIds: List<Long>)
    @Query("DELETE FROM intraday_review_events WHERE dailyPlanItemId = :itemId AND id != :keepEventId")
    suspend fun deleteEventsForItemExcept(itemId: Long, keepEventId: Long)
    @Query("SELECT * FROM daily_plans WHERE localDate = :date LIMIT 1")
    suspend fun getPlanByDate(date: LocalDate): DailyPlanEntity?
    @Query("SELECT * FROM daily_plans WHERE id = :planId LIMIT 1")
    suspend fun getPlan(planId: Long): DailyPlanEntity?
    @Query("SELECT * FROM daily_plan_items WHERE dailyPlanId = :planId ORDER BY selectionRank")
    suspend fun getItemsForPlan(planId: Long): List<DailyPlanItemEntity>
    @Query("SELECT * FROM daily_plan_items WHERE id = :itemId LIMIT 1")
    suspend fun getItem(itemId: Long): DailyPlanItemEntity?
    @Query("SELECT * FROM intraday_review_events WHERE dailyPlanItemId = :itemId ORDER BY stepIndex")
    suspend fun getEventsForItem(itemId: Long): List<IntradayReviewEventEntity>
    @Query("SELECT * FROM intraday_review_events WHERE dailyPlanItemId IN (:itemIds) ORDER BY dailyPlanItemId, stepIndex")
    suspend fun getEventsForItems(itemIds: List<Long>): List<IntradayReviewEventEntity>
    @Query("SELECT * FROM intraday_review_events WHERE id = :eventId LIMIT 1")
    suspend fun getEvent(eventId: Long): IntradayReviewEventEntity?
    @Query("SELECT * FROM daily_plans ORDER BY id")
    suspend fun getAllPlans(): List<DailyPlanEntity>
    @Query("SELECT localDate FROM daily_plans WHERE status = 'COMPLETED' ORDER BY localDate")
    suspend fun getCompletedPlanDates(): List<LocalDate>
    @Query("SELECT * FROM daily_plan_items ORDER BY id")
    suspend fun getAllItems(): List<DailyPlanItemEntity>
    @Query(
        """
        SELECT items.* FROM daily_plan_items AS items
        JOIN daily_plans AS plans ON plans.id = items.dailyPlanId
        WHERE plans.localDate >= :startDate AND plans.localDate <= :endDate
        ORDER BY plans.localDate, items.selectionRank, items.id
        """,
    )
    suspend fun getItemsForDateRange(startDate: LocalDate, endDate: LocalDate): List<DailyPlanItemEntity>
    @Query("SELECT * FROM intraday_review_events ORDER BY id")
    suspend fun getAllEvents(): List<IntradayReviewEventEntity>
    @Query("DELETE FROM intraday_review_events")
    suspend fun deleteAllEvents()
    @Query("DELETE FROM daily_plan_items")
    suspend fun deleteAllItems()
    @Query("DELETE FROM daily_plans")
    suspend fun deleteAllPlans()
}

@Dao
interface ReviewLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(log: ReviewLogEntity): Long
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertAll(logs: List<ReviewLogEntity>)
    @Query("SELECT * FROM review_logs WHERE wordId = :wordId ORDER BY reviewedAt, id")
    suspend fun getForWord(wordId: Long): List<ReviewLogEntity>
    @Query("SELECT * FROM review_logs WHERE id = :logId LIMIT 1")
    suspend fun get(logId: Long): ReviewLogEntity?
    @Query("SELECT DISTINCT wordId FROM review_logs ORDER BY wordId")
    suspend fun getReviewedWordIds(): List<Long>
    @Query("DELETE FROM review_logs WHERE id = :logId")
    suspend fun delete(logId: Long)
    @Query("SELECT * FROM review_logs WHERE reviewedAt >= :startInclusive AND reviewedAt < :endExclusive ORDER BY reviewedAt, id")
    fun observeBetween(startInclusive: Instant, endExclusive: Instant): Flow<List<ReviewLogEntity>>
    @Query("SELECT * FROM review_logs ORDER BY id")
    suspend fun getAll(): List<ReviewLogEntity>
    @Query(
        """
        SELECT id, wordId, mode, reviewedAt, feedback, responseTimeMs, nextStateSnapshot
        FROM review_logs ORDER BY reviewedAt, id
        """,
    )
    suspend fun getStatsRows(): List<StatsReviewLogRow>
    @Query("DELETE FROM review_logs")
    suspend fun deleteAll()
}
