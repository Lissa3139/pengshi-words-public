package com.pengshi.words.database

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.pengshi.words.model.CardStatus
import com.pengshi.words.model.DailyItemStatus
import com.pengshi.words.model.Feedback
import com.pengshi.words.model.IntradayEventStatus
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.ReviewEventType
import com.pengshi.words.model.StudyMode
import com.pengshi.words.sync.SyncEventKind
import com.pengshi.words.sync.SyncApplyState
import java.time.Instant
import java.time.LocalDate

enum class DeckSourceTypeEntity { BUILTIN, IMPORTED, USER }
enum class DailyPlanStatusEntity { IN_PROGRESS, COMPLETED }

@Entity(tableName = "words", indices = [Index(value = ["normalizedSpelling"], unique = true)])
data class WordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val spelling: String,
    val normalizedSpelling: String,
    val phonetic: String?,
    val partOfSpeech: String,
    val definitionCn: String,
    val tags: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val frequencyRank: Int? = null,
    @ColumnInfo(defaultValue = "'来源待核实'")
    val definitionSource: String = com.pengshi.words.model.definitionSourceFromTags(tags),
    @ColumnInfo(defaultValue = "''")
    val mnemonic: String = "",
)

@Entity(
    tableName = "word_senses",
    foreignKeys = [ForeignKey(entity = WordEntity::class, parentColumns = ["id"], childColumns = ["wordId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("wordId"), Index(value = ["wordId", "normalizedKey"], unique = true)],
)
data class WordSenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    val partOfSpeech: String,
    val definitionCn: String,
    val definitionSource: String,
    val normalizedKey: String,
    val sortOrder: Int,
    val createdAt: Instant,
)

@Entity(
    tableName = "example_sentences",
    foreignKeys = [ForeignKey(entity = WordEntity::class, parentColumns = ["id"], childColumns = ["wordId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("wordId")],
)
data class ExampleSentenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    val sentenceEn: String,
    val sentenceCn: String?,
    val sortOrder: Int,
    @ColumnInfo(defaultValue = "'来源待核实'")
    val source: String = com.pengshi.words.model.SOURCE_UNVERIFIED,
)

@Entity(tableName = "decks")
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceType: DeckSourceTypeEntity,
    val sourceFileName: String,
    val wordCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(
    tableName = "deck_words",
    primaryKeys = ["deckId", "wordId"],
    foreignKeys = [
        ForeignKey(entity = DeckEntity::class, parentColumns = ["id"], childColumns = ["deckId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WordEntity::class, parentColumns = ["id"], childColumns = ["wordId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("wordId")],
)
data class DeckWordEntity(val deckId: Long, val wordId: Long, val position: Int, val addedAt: Instant)

@Entity(
    tableName = "card_states",
    foreignKeys = [ForeignKey(entity = WordEntity::class, parentColumns = ["id"], childColumns = ["wordId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["wordId", "studyMode"], unique = true)],
)
data class CardStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    val studyMode: StudyMode,
    val state: CardStatus,
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
)

@Entity(tableName = "daily_plans", indices = [Index(value = ["localDate"], unique = true)])
data class DailyPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localDate: LocalDate,
    val quota: Int,
    val plannedUniqueWordCount: Int,
    val completedUniqueWordCount: Int,
    val status: DailyPlanStatusEntity,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(
    tableName = "daily_plan_items",
    foreignKeys = [
        ForeignKey(entity = DailyPlanEntity::class, parentColumns = ["id"], childColumns = ["dailyPlanId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WordEntity::class, parentColumns = ["id"], childColumns = ["wordId"]),
    ],
    indices = [Index(value = ["dailyPlanId", "wordId"], unique = true), Index("wordId")],
)
data class DailyPlanItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dailyPlanId: Long,
    val wordId: Long,
    val sourceType: PlanSource,
    val selectionRank: Int,
    val status: DailyItemStatus,
)

@Entity(
    tableName = "intraday_review_events",
    foreignKeys = [
        ForeignKey(entity = DailyPlanItemEntity::class, parentColumns = ["id"], childColumns = ["dailyPlanItemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WordEntity::class, parentColumns = ["id"], childColumns = ["wordId"]),
    ],
    indices = [Index(value = ["dailyPlanItemId", "stepIndex"], unique = true), Index("wordId")],
)
data class IntradayReviewEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dailyPlanItemId: Long,
    val wordId: Long,
    val mode: StudyMode,
    val stepIndex: Int,
    val scheduledAt: Instant,
    val completedAt: Instant?,
    val status: IntradayEventStatus,
    val feedback: Feedback?,
)

@Entity(
    tableName = "review_logs",
    foreignKeys = [ForeignKey(entity = WordEntity::class, parentColumns = ["id"], childColumns = ["wordId"])],
    indices = [Index("wordId"), Index("reviewedAt")],
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    val mode: StudyMode,
    val reviewedAt: Instant,
    val feedback: Feedback,
    val responseTimeMs: Long,
    val previousStateSnapshot: String,
    val nextStateSnapshot: String,
    val previousDueAt: Instant?,
    val nextDueAt: Instant?,
    val eventType: ReviewEventType,
)

class DatabaseConverters {
    @TypeConverter fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
    @TypeConverter fun localDateToString(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
    @TypeConverter fun studyModeToString(value: StudyMode?): String? = value?.name
    @TypeConverter fun stringToStudyMode(value: String?): StudyMode? = value?.let(StudyMode::valueOf)
    @TypeConverter fun cardStatusToString(value: CardStatus?): String? = value?.name
    @TypeConverter fun stringToCardStatus(value: String?): CardStatus? = value?.let(CardStatus::valueOf)
    @TypeConverter fun feedbackToString(value: Feedback?): String? = value?.name
    @TypeConverter fun stringToFeedback(value: String?): Feedback? = value?.let(Feedback::valueOf)
    @TypeConverter fun dailyItemStatusToString(value: DailyItemStatus?): String? = value?.name
    @TypeConverter fun stringToDailyItemStatus(value: String?): DailyItemStatus? = value?.let(DailyItemStatus::valueOf)
    @TypeConverter fun planSourceToString(value: PlanSource?): String? = value?.name
    @TypeConverter fun stringToPlanSource(value: String?): PlanSource? = value?.let(PlanSource::valueOf)
    @TypeConverter fun eventStatusToString(value: IntradayEventStatus?): String? = value?.name
    @TypeConverter fun stringToEventStatus(value: String?): IntradayEventStatus? = value?.let(IntradayEventStatus::valueOf)
    @TypeConverter fun reviewEventTypeToString(value: ReviewEventType?): String? = value?.name
    @TypeConverter fun stringToReviewEventType(value: String?): ReviewEventType? = value?.let(ReviewEventType::valueOf)
    @TypeConverter fun deckSourceToString(value: DeckSourceTypeEntity?): String? = value?.name
    @TypeConverter fun stringToDeckSource(value: String?): DeckSourceTypeEntity? = value?.let(DeckSourceTypeEntity::valueOf)
    @TypeConverter fun planStatusToString(value: DailyPlanStatusEntity?): String? = value?.name
    @TypeConverter fun stringToPlanStatus(value: String?): DailyPlanStatusEntity? = value?.let(DailyPlanStatusEntity::valueOf)
    @TypeConverter fun syncEventKindToString(value: SyncEventKind?): String? = value?.name
    @TypeConverter fun stringToSyncEventKind(value: String?): SyncEventKind? = value?.let(SyncEventKind::valueOf)
    @TypeConverter fun syncApplyStateToString(value: SyncApplyState?): String? = value?.name
    @TypeConverter fun stringToSyncApplyState(value: String?): SyncApplyState? = value?.let(SyncApplyState::valueOf)
}
