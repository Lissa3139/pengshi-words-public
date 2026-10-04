package com.pengshi.words.database

import androidx.room.withTransaction
import com.pengshi.words.model.CardKey
import com.pengshi.words.model.CardState
import com.pengshi.words.model.CardStateRepository
import com.pengshi.words.model.DailyStudyRepository
import com.pengshi.words.model.DailyPlan
import com.pengshi.words.model.DailyQuota
import com.pengshi.words.model.DailyPlanCandidate
import com.pengshi.words.model.DailyPlanEntry
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.DailyPlanRepository
import com.pengshi.words.model.DailyPlanStatus
import com.pengshi.words.model.Deck
import com.pengshi.words.model.DeckSourceType
import com.pengshi.words.model.DeckWord
import com.pengshi.words.model.ExampleSentence
import com.pengshi.words.model.FeedbackContext
import com.pengshi.words.model.FeedbackSubmission
import com.pengshi.words.model.FeedbackTransaction
import com.pengshi.words.model.FeedbackUndoToken
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.IntradayEventStatus
import com.pengshi.words.model.ReviewLog
import com.pengshi.words.model.RelatedWord
import com.pengshi.words.model.RelatedWordKind
import com.pengshi.words.model.StudyDataSnapshot
import com.pengshi.words.model.StudyDataSnapshotGateway
import com.pengshi.words.model.StudyLogRepository
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.Word
import com.pengshi.words.model.WordSense
import com.pengshi.words.model.wordSenseKey
import com.pengshi.words.model.sourceLabelNeedsUpgrade
import com.pengshi.words.model.normalizeDictionaryText
import com.pengshi.words.model.SOURCE_UNVERIFIED
import com.pengshi.words.sync.LocalChangeFactory
import com.pengshi.words.sync.CardSnapshotV2
import com.pengshi.words.sync.FeedbackAppliedV2
import com.pengshi.words.sync.FeedbackRevisedV2
import com.pengshi.words.sync.SyncKeyFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val SQLITE_BIND_PARAMETER_CHUNK_SIZE = 900

class RoomCardStateRepository(private val dao: CardStateDao) : CardStateRepository {
    override suspend fun get(key: CardKey): CardState? = dao.get(key.wordId, key.mode)?.toModel()
    override suspend fun upsert(state: CardState) { dao.upsert(state.toEntity()) }
}

class RoomStudyLogRepository(
    private val dao: ReviewLogDao,
    private val zoneId: ZoneId,
) : StudyLogRepository {
    override suspend fun append(log: ReviewLog) { dao.insert(log.toEntity()) }
    override fun observeForDate(date: LocalDate): Flow<List<ReviewLog>> {
        val start = date.atStartOfDay(zoneId).toInstant()
        val end = date.plusDays(1).atStartOfDay(zoneId).toInstant()
        return dao.observeBetween(start, end).map { rows -> rows.map(ReviewLogEntity::toModel) }
    }
}

data class InitialPlanEntry(
    val item: DailyPlanItem,
    val initialEvent: IntradayReviewEvent,
)

class RoomDailyPlanRepository(
    private val database: PengshiDatabase,
    private val syncStore: SyncRoomStore? = null,
) {
    suspend fun createPlanWithInitialEvents(plan: DailyPlan, entries: List<InitialPlanEntry>): DailyPlan =
        createPlan(plan, entries.map { DailyPlanEntry(it.item, it.initialEvent) })

    suspend fun createPlan(plan: DailyPlan, entries: List<DailyPlanEntry>): DailyPlan =
        database.withTransaction {
            val dao = database.dailyPlanDao()
            DailyQuota.requireValid(plan.quota)
            val mainEntryCount = entries.count { it.item.source != com.pengshi.words.model.PlanSource.EXTRA }
            require(mainEntryCount <= plan.quota) { "Daily plan cannot contain more than its locked quota" }
            val planId = dao.insertPlan(plan.toEntity())
            entries.forEach { entry ->
                require(entry.item.wordId == entry.initialEvent.wordId)
            }
            val itemIds = dao.insertItems(entries.map { it.item.toEntity(planId) })
            dao.insertEvents(entries.mapIndexed { index, entry -> entry.initialEvent.toEntity(itemIds[index]) })
            syncStore?.let { store ->
                val mode = entries.firstOrNull()?.initialEvent?.mode ?: StudyMode.EN_TO_CN
                val wordKeys = getWordEntitiesByIds(entries.map { it.item.wordId }.distinct())
                    .associate { word -> word.id to SyncKeyFactory.wordKey(word.normalizedSpelling) }
                store.appendChangeInCurrentTransaction(
                    LocalChangeFactory.planLocked(
                        plan = plan,
                        entries = entries,
                        mode = mode,
                        deviceId = store.deviceId,
                        wordKeyForWordId = { wordKeys.getValue(it) },
                    ),
                    plan.createdAt,
                )
            }
            plan.copy(id = planId)
        }

    suspend fun appendToPlan(plan: DailyPlan, entries: List<DailyPlanEntry>): DailyPlan =
        database.withTransaction {
            require(plan.id > 0) { "An existing plan id is required" }
            val dao = database.dailyPlanDao()
            val existingItems = dao.getItemsForPlan(plan.id)
            val existingMainCount = existingItems.count { it.sourceType != com.pengshi.words.model.PlanSource.EXTRA }
            val incomingMainCount = entries.count { it.item.source != com.pengshi.words.model.PlanSource.EXTRA }
            DailyQuota.requireValid(plan.quota)
            if (incomingMainCount > 0) require(existingMainCount + incomingMainCount <= plan.quota) {
                "Daily plan cannot contain more than its locked quota"
            }
            val existingEventsByItemId = getEventEntitiesForItems(dao, existingItems.map { it.id }).groupBy { it.dailyPlanItemId }
            val retainedEntries = existingItems.mapNotNull { item ->
                val initialEvent = existingEventsByItemId[item.id].orEmpty().minByOrNull { it.stepIndex } ?: return@mapNotNull null
                DailyPlanEntry(item.toModel(), initialEvent.toModel())
            }
            entries.forEach { entry -> require(entry.item.wordId == entry.initialEvent.wordId) }
            val itemIds = dao.insertItems(entries.map { it.item.toEntity(plan.id) })
            dao.insertEvents(entries.mapIndexed { index, entry -> entry.initialEvent.toEntity(itemIds[index]) })
            dao.updatePlan(plan.toEntity())
            syncStore?.let { store ->
                val mode = (retainedEntries + entries).firstOrNull()?.initialEvent?.mode ?: return@let
                val oldPlanVersion = store.currentPlanVersionInCurrentTransaction(SyncKeyFactory.planKey(plan.localDate))
                val allEntries = retainedEntries + entries
                val wordKeys = getWordEntitiesByIds(allEntries.map { it.item.wordId }.distinct())
                    .associate { word -> word.id to SyncKeyFactory.wordKey(word.normalizedSpelling) }
                store.appendChangeInCurrentTransaction(
                    LocalChangeFactory.planReconciled(
                        plan = plan,
                        retainedEntries = retainedEntries,
                        addedEntries = entries,
                        mode = mode,
                        wordKeyForWordId = { wordKeys.getValue(it) },
                        oldPlanVersion = oldPlanVersion,
                        newPlanVersion = oldPlanVersion + 1,
                    ),
                    plan.updatedAt,
                )
            }
            plan
        }

    suspend fun replaceUnseenNewItems(
        plan: DailyPlan,
        removeItemIds: Set<Long>,
        entries: List<DailyPlanEntry>,
        now: Instant,
    ): DailyPlan = database.withTransaction {
        require(plan.id > 0) { "An existing plan id is required" }
        val dao = database.dailyPlanDao()
        val persistedPlanQuota = requireNotNull(dao.getPlan(plan.id)) { "Daily plan no longer exists" }.quota
        DailyQuota.requireValid(plan.quota)
        val persistedItems = dao.getItemsForPlan(plan.id)
        val persistedEventsByItemId = getEventEntitiesForItems(dao, persistedItems.map { it.id }).groupBy { it.dailyPlanItemId }
        val removedItems = persistedItems.filter { it.id in removeItemIds }
        require(removedItems.size == removeItemIds.size) { "Every replacement item must belong to the plan" }
        removedItems.forEach { item ->
            require(item.sourceType == com.pengshi.words.model.PlanSource.NEW) {
                "Only automatic NEW items can be replaced: ${item.id}"
            }
            val events = persistedEventsByItemId[item.id].orEmpty()
            require(item.status == com.pengshi.words.model.DailyItemStatus.PENDING && events.isNotEmpty() &&
                events.all { it.status == com.pengshi.words.model.IntradayEventStatus.PENDING && it.feedback == null }) {
                "Only unseen NEW items can be replaced: ${item.id}"
            }
        }
        val retainedEntries = persistedItems.filterNot { it.id in removeItemIds }.mapNotNull { item ->
            val initialEvent = persistedEventsByItemId[item.id].orEmpty().minByOrNull { it.stepIndex } ?: return@mapNotNull null
            DailyPlanEntry(item.toModel(), initialEvent.toModel())
        }
        val removedWordKeys = removedItems.map { item ->
            val word = requireNotNull(database.wordDao().getById(item.wordId))
            SyncKeyFactory.itemKey(
                SyncKeyFactory.planKey(plan.localDate),
                SyncKeyFactory.wordKey(word.normalizedSpelling),
                item.sourceType,
            )
        }
        if (removeItemIds.isNotEmpty()) {
            val ids = removeItemIds.toList()
            dao.deleteEventsForItems(ids)
            dao.deleteItems(ids)
        }
        entries.forEach { entry -> require(entry.item.wordId == entry.initialEvent.wordId) }
        val itemIds = dao.insertItems(entries.map { it.item.toEntity(plan.id) })
        dao.insertEvents(entries.mapIndexed { index, entry -> entry.initialEvent.toEntity(itemIds[index]) })
        val actualMainCount = persistedItems.count {
            it.id !in removeItemIds && it.sourceType != com.pengshi.words.model.PlanSource.EXTRA
        } + entries.count { it.item.source != com.pengshi.words.model.PlanSource.EXTRA }
        require(actualMainCount <= maxOf(persistedPlanQuota, plan.quota)) {
            "Daily plan cannot contain more items than its existing or updated quota"
        }
        val updatedPlan = plan.copy(
            plannedUniqueWordCount = actualMainCount,
            updatedAt = now,
        )
        dao.updatePlan(updatedPlan.toEntity())
        syncStore?.let { store ->
            val mode = (retainedEntries + entries).firstOrNull()?.initialEvent?.mode ?: return@let
            val oldPlanVersion = store.currentPlanVersionInCurrentTransaction(SyncKeyFactory.planKey(plan.localDate))
            val allEntries = retainedEntries + entries
            val wordKeys = getWordEntitiesByIds((removedItems.map { it.wordId } + allEntries.map { it.item.wordId }).distinct())
                .associate { word -> word.id to SyncKeyFactory.wordKey(word.normalizedSpelling) }
            store.appendChangeInCurrentTransaction(
                LocalChangeFactory.planReconciled(
                    plan = updatedPlan,
                    retainedEntries = retainedEntries,
                    addedEntries = entries,
                    mode = mode,
                    wordKeyForWordId = { wordKeys.getValue(it) },
                    removedItemKeys = removedWordKeys,
                    oldPlanVersion = oldPlanVersion,
                    newPlanVersion = oldPlanVersion + 1,
                    reason = "due-review-priority",
                ),
                now,
            )
        }
        updatedPlan
    }

    private suspend fun getEventEntitiesForItems(
        dao: DailyPlanDao,
        itemIds: List<Long>,
    ): List<IntradayReviewEventEntity> = itemIds.distinct()
        .chunked(SQLITE_BIND_PARAMETER_CHUNK_SIZE)
        .flatMap { chunk -> dao.getEventsForItems(chunk) }

    private suspend fun getWordEntitiesByIds(wordIds: List<Long>): List<WordEntity> = wordIds.distinct()
        .chunked(SQLITE_BIND_PARAMETER_CHUNK_SIZE)
        .flatMap { chunk -> database.wordDao().getByIds(chunk) }

    suspend fun resetTodayReview(localDate: LocalDate, now: Instant): Int =
        database.withTransaction {
            val dao = database.dailyPlanDao()
            val plan = dao.getPlanByDate(localDate) ?: return@withTransaction 0
            val reviewItems = dao.getItemsForPlan(plan.id)
                .filter { it.sourceType == com.pengshi.words.model.PlanSource.DUE_REVIEW }
            if (reviewItems.isEmpty()) return@withTransaction 0

            reviewItems.forEach { item ->
                val events = dao.getEventsForItem(item.id)
                val initialEvent = events.minWithOrNull(compareBy({ it.stepIndex }, { it.id }))
                if (initialEvent != null) {
                    dao.deleteEventsForItemExcept(item.id, initialEvent.id)
                    dao.updateEvent(
                        initialEvent.copy(
                            completedAt = null,
                            status = com.pengshi.words.model.IntradayEventStatus.PENDING,
                            feedback = null,
                        ),
                    )
                }
                dao.updateItem(item.copy(status = com.pengshi.words.model.DailyItemStatus.PENDING))
            }

            val completedReviewCount = reviewItems.count {
                it.status == com.pengshi.words.model.DailyItemStatus.COMPLETED
            }
            dao.updatePlan(
                plan.copy(
                    completedUniqueWordCount = (plan.completedUniqueWordCount - completedReviewCount).coerceAtLeast(0),
                    status = DailyPlanStatusEntity.IN_PROGRESS,
                    updatedAt = now,
                ),
            )
            reviewItems.size
        }

    suspend fun getPlan(localDate: LocalDate): DailyPlan? = database.dailyPlanDao().getPlanByDate(localDate)?.toModel()

    suspend fun getPlanById(planId: Long): DailyPlan? = database.dailyPlanDao().getPlan(planId)?.toModel()

    suspend fun getItem(itemId: Long): DailyPlanItem? = database.dailyPlanDao().getItem(itemId)?.toModel()

    suspend fun getItems(planId: Long): List<DailyPlanItem> = database.dailyPlanDao().getItemsForPlan(planId).map(DailyPlanItemEntity::toModel)

    suspend fun getEvents(itemId: Long): List<IntradayReviewEvent> = database.dailyPlanDao().getEventsForItem(itemId).map(IntradayReviewEventEntity::toModel)

    suspend fun getWord(wordId: Long): Word? = database.wordDao().getById(wordId)?.toModel()

    suspend fun getWordSenses(wordId: Long): List<WordSense> = database.wordSenseDao().getForWord(wordId).map(WordSenseEntity::toModel)

    suspend fun getExamples(wordId: Long): List<ExampleSentence> = database.exampleSentenceDao().getForWord(wordId)
        .map(ExampleSentenceEntity::toModel)

    suspend fun getRelatedWords(wordId: Long): List<RelatedWord> {
        val words = database.wordDao().getAll().map(WordEntity::toModel)
        val current = words.firstOrNull { it.id == wordId } ?: return emptyList()
        val currentFamilyTags = current.tags.splitTags().filter { it.startsWith("family:") }.toSet()
        val familyMatches = words.asSequence()
            .filter { it.id != current.id }
            .filter { word -> currentFamilyTags.any { tag -> tag in word.tags.splitTags() } }
            .sortedWith(compareBy<Word>({ it.frequencyRank ?: Int.MAX_VALUE }, { it.spelling.lowercase() }))
            .map { RelatedWord(it, RelatedWordKind.DERIVED, "同一词族") }
            .take(8)
            .toList()
        val familyIds = familyMatches.map { it.word.id }.toSet() + current.id
        val currentRoots = current.spelling.morphologyRoots()
        val derivedMatches = words.asSequence()
            .filter { it.id !in familyIds }
            .filter { word -> currentRoots.intersect(word.spelling.morphologyRoots()).any { it.length >= 4 } }
            .sortedWith(compareBy<Word>({ it.frequencyRank ?: Int.MAX_VALUE }, { it.spelling.lowercase() }))
            .map { RelatedWord(it, RelatedWordKind.DERIVED, "同根词/词形派生") }
            .take(8)
            .toList()
        val derivedIds = (familyIds + derivedMatches.map { it.word.id }).toSet()
        val similarMatches = words.asSequence()
            .filter { it.id !in derivedIds }
            .filter { word -> word.spelling.isSpellingNeighborOf(current.spelling) }
            .sortedWith(compareBy<Word>({ spellingDistance(current.spelling, it.spelling) }, { it.frequencyRank ?: Int.MAX_VALUE }, { it.spelling.lowercase() }))
            .map { RelatedWord(it, RelatedWordKind.SIMILAR, "拼写相似") }
            .take(8)
            .toList()
        return familyMatches + derivedMatches + similarMatches
    }

    suspend fun getCard(wordId: Long, mode: com.pengshi.words.model.StudyMode): CardState? = database.cardStateDao().get(wordId, mode)?.toModel()

    suspend fun getCandidates(mode: com.pengshi.words.model.StudyMode, now: java.time.Instant): List<DailyPlanCandidate> =
        database.wordDao()
            .getDailyPlanCandidateRows(mode, exclusiveEpochMillis(now.plusNanos(1)))
            .map { it.toDailyPlanCandidate(now) }

    suspend fun getCandidatesBefore(
        mode: com.pengshi.words.model.StudyMode,
        now: java.time.Instant,
        dueBefore: java.time.Instant,
    ): List<DailyPlanCandidate> = database.wordDao()
        .getDailyPlanCandidateRows(mode, dueBefore.toEpochMilli())
        .map { it.toDailyPlanCandidate(now) }

    suspend fun getDueCandidatesBefore(
        mode: com.pengshi.words.model.StudyMode,
        now: java.time.Instant,
        dueBefore: java.time.Instant,
    ): List<DailyPlanCandidate> = database.wordDao()
        .getDueCandidateRows(mode, dueBefore.toEpochMilli())
        .map { it.toDailyPlanCandidate(now) }

    suspend fun getNewCandidates(mode: StudyMode, now: Instant): List<DailyPlanCandidate> =
        database.wordDao().getNewCandidateRows(mode).map { it.toDailyPlanCandidate(now) }

    suspend fun getEventsForItems(itemIds: List<Long>): List<IntradayReviewEvent> =
        itemIds.distinct().chunked(SQLITE_BIND_PARAMETER_CHUNK_SIZE)
            .flatMap { chunk -> database.dailyPlanDao().getEventsForItems(chunk) }
            .map(IntradayReviewEventEntity::toModel)

    suspend fun getCompletedPlanDates(): Set<LocalDate> = database.dailyPlanDao().getCompletedPlanDates().toSet()

    private fun DailyPlanCandidateRow.toDailyPlanCandidate(now: Instant): DailyPlanCandidate {
        val isNew = cardWordId == null || state == com.pengshi.words.model.CardStatus.NEW
        return DailyPlanCandidate(
            wordId = wordId,
            isNew = isNew,
            dueAt = dueAt,
            retrievability = retrievability,
            overdueSeconds = dueAt?.let { (now.epochSecond - it.epochSecond).coerceAtLeast(0) } ?: 0,
            familyKey = tags.splitTags().firstOrNull { it.startsWith("family:") },
            initialKey = spelling.firstOrNull()?.lowercase(),
            difficulty = difficulty,
            lapses = lapses ?: 0,
            lastReviewedAt = lastReviewedAt,
            intervalDays = lastReviewedAt?.let { reviewedAt ->
                java.time.Duration.between(reviewedAt, now).toDays().coerceAtLeast(0L)
            },
            frequencyRank = frequencyRank,
        )
    }

    private fun exclusiveEpochMillis(instant: Instant): Long {
        val epochMillis = instant.toEpochMilli()
        return if (instant.nano % 1_000_000 == 0) epochMillis else epochMillis + 1
    }
}

class RoomStudySessionRepository(
    database: PengshiDatabase,
    syncStore: SyncRoomStore? = null,
) : DailyPlanRepository {
    private val planRepository = RoomDailyPlanRepository(database, syncStore)
    private val studyRepository = RoomDailyStudyRepository(database, syncStore)

    override suspend fun getPlan(localDate: LocalDate): DailyPlan? = planRepository.getPlan(localDate)
    override suspend fun getPlanById(planId: Long): DailyPlan? = planRepository.getPlanById(planId)
    override suspend fun getItem(itemId: Long): DailyPlanItem? = planRepository.getItem(itemId)
    override suspend fun getItems(planId: Long): List<DailyPlanItem> = planRepository.getItems(planId)
    override suspend fun getEvents(itemId: Long): List<IntradayReviewEvent> = planRepository.getEvents(itemId)
    override suspend fun getEventsForItems(itemIds: List<Long>): List<IntradayReviewEvent> = planRepository.getEventsForItems(itemIds)
    override suspend fun getWord(wordId: Long): Word? = planRepository.getWord(wordId)
    override suspend fun getWordSenses(wordId: Long): List<WordSense> = planRepository.getWordSenses(wordId)
    override suspend fun getExamples(wordId: Long): List<ExampleSentence> = planRepository.getExamples(wordId)
    override suspend fun getRelatedWords(wordId: Long): List<RelatedWord> = planRepository.getRelatedWords(wordId)
    override suspend fun getCard(wordId: Long, mode: com.pengshi.words.model.StudyMode): CardState? = planRepository.getCard(wordId, mode)
    override suspend fun getCandidates(mode: com.pengshi.words.model.StudyMode, now: java.time.Instant): List<DailyPlanCandidate> = planRepository.getCandidates(mode, now)
    override suspend fun getCandidatesBefore(
        mode: com.pengshi.words.model.StudyMode,
        now: java.time.Instant,
        dueBefore: java.time.Instant,
    ): List<DailyPlanCandidate> = planRepository.getCandidatesBefore(mode, now, dueBefore)
    override suspend fun getDueCandidatesBefore(
        mode: StudyMode,
        now: Instant,
        dueBefore: Instant,
    ): List<DailyPlanCandidate> = planRepository.getDueCandidatesBefore(mode, now, dueBefore)
    override suspend fun getNewCandidates(mode: StudyMode, now: Instant): List<DailyPlanCandidate> =
        planRepository.getNewCandidates(mode, now)
    override suspend fun createPlan(plan: DailyPlan, entries: List<DailyPlanEntry>): DailyPlan = planRepository.createPlan(plan, entries)
    override suspend fun appendToPlan(plan: DailyPlan, entries: List<DailyPlanEntry>): DailyPlan = planRepository.appendToPlan(plan, entries)
    override suspend fun replaceUnseenNewItems(
        plan: DailyPlan,
        removeItemIds: Set<Long>,
        entries: List<DailyPlanEntry>,
        now: Instant,
    ): DailyPlan = planRepository.replaceUnseenNewItems(plan, removeItemIds, entries, now)
    override suspend fun resetTodayReview(localDate: LocalDate, now: Instant): Int = planRepository.resetTodayReview(localDate, now)
    override suspend fun submitFeedback(eventId: Long, transform: (FeedbackContext) -> FeedbackTransaction): FeedbackTransaction = studyRepository.submitFeedback(eventId, transform)
    override suspend fun submitFeedback(
        eventId: Long,
        transform: (FeedbackContext) -> FeedbackTransaction,
        submission: FeedbackSubmission,
    ): FeedbackTransaction = studyRepository.submitFeedback(eventId, transform, submission)
}

class RoomDailyStudyRepository(
    private val database: PengshiDatabase,
    private val syncStore: SyncRoomStore? = null,
) : DailyStudyRepository {
    override suspend fun submitFeedback(
        eventId: Long,
        transform: (FeedbackContext) -> FeedbackTransaction,
    ): FeedbackTransaction = submitFeedback(eventId, transform, FeedbackSubmission())

    override suspend fun submitFeedback(
        eventId: Long,
        transform: (FeedbackContext) -> FeedbackTransaction,
        submission: FeedbackSubmission,
    ): FeedbackTransaction = database.withTransaction {
        val planDao = database.dailyPlanDao()
        val event = requireNotNull(planDao.getEvent(eventId)) { "Unknown intraday event: $eventId" }
        require(event.status == IntradayEventStatus.PENDING) { "Event is not pending: $eventId" }
        val item = requireNotNull(planDao.getItem(event.dailyPlanItemId)) { "Missing daily plan item for event: $eventId" }
        val plan = requireNotNull(planDao.getPlan(item.dailyPlanId)) { "Missing daily plan for event: $eventId" }
        val card = requireNotNull(database.cardStateDao().get(event.wordId, event.mode)) { "Missing card state for event: $eventId" }
        require(event.wordId == item.wordId) { "Event word does not match its item: $eventId" }
        val context = FeedbackContext(
            plan.toModel(), item.toModel(), event.toModel(), card.toModel(),
            planDao.getEventsForItem(item.id).map(IntradayReviewEventEntity::toModel),
        )
        val transaction = transform(context)

        require(transaction.updatedCard.key == context.card.key)
        require(transaction.reviewLog.id == 0L) { "Feedback must append a new log" }
        require(transaction.reviewLog.wordId == event.wordId && transaction.reviewLog.mode == event.mode)
        require(transaction.completedEvent == context.event.copy(
            status = IntradayEventStatus.COMPLETED,
            completedAt = transaction.reviewLog.reviewedAt,
            feedback = transaction.reviewLog.feedback,
        )) { "Feedback must complete the loaded event without changing its identity" }
        require(transaction.followUpEvents.all {
            it.id == 0L && it.dailyPlanItemId == item.id && it.wordId == event.wordId &&
                it.mode == event.mode && it.status == IntradayEventStatus.PENDING &&
                it.completedAt == null && it.feedback == null
        }) { "Follow-ups must be new pending events for this card and item" }
        val pendingSiblings = context.itemEvents.filter {
            it.id != context.event.id && it.status == IntradayEventStatus.PENDING
        }
        require(transaction.skippedEvents.size == pendingSiblings.size &&
            transaction.skippedEvents.map { it.id }.toSet().size == pendingSiblings.size &&
            transaction.skippedEvents.all { skipped ->
            pendingSiblings.any { original -> skipped == original.copy(status = IntradayEventStatus.SKIPPED) }
        }) { "Only pending sibling events may be skipped, and every pending sibling must be included" }
        require(transaction.updatedItem == context.item.copy(status = transaction.updatedItem.status))
        require(transaction.updatedPlan == context.plan.copy(
            completedUniqueWordCount = transaction.updatedPlan.completedUniqueWordCount,
            status = transaction.updatedPlan.status,
            updatedAt = transaction.updatedPlan.updatedAt,
        ))

        database.cardStateDao().upsert(transaction.updatedCard.toEntity())
        val logId = database.reviewLogDao().insert(transaction.reviewLog.toEntity())
        planDao.updateEvent(transaction.completedEvent.toEntity())
        transaction.skippedEvents.forEach { planDao.updateEvent(it.toEntity()) }
        val followUps = transaction.followUpEvents.map { followUp ->
            followUp.copy(id = planDao.insertEvent(followUp.toEntity()))
        }
        planDao.updateItem(transaction.updatedItem.toEntity())
        planDao.updatePlan(transaction.updatedPlan.toEntity())
        val syncEventId = syncStore?.takeIf { submission.recordSyncEvent }?.let { store ->
            val word = requireNotNull(database.wordDao().getById(event.wordId)) { "Missing word for sync event: ${event.wordId}" }
            val planKey = SyncKeyFactory.planKey(plan.localDate)
            val planVersion = store.currentPlanVersionInCurrentTransaction(planKey)
            val wordKey = SyncKeyFactory.wordKey(word.normalizedSpelling)
            val cardSnapshot = CardSnapshotV2(
                status = transaction.updatedCard.status,
                difficulty = transaction.updatedCard.difficulty,
                stability = transaction.updatedCard.stability,
                retrievability = transaction.updatedCard.retrievability,
                dueAtUtc = transaction.updatedCard.dueAt,
                scheduledDays = transaction.updatedCard.scheduledDays,
                lapses = transaction.updatedCard.lapses,
                learningStep = transaction.updatedCard.learningStep,
                lastFeedbackEventId = null,
            )
            val change = submission.revisionOfEventId?.let { originalEventId ->
                LocalChangeFactory.feedbackRevised(
                    FeedbackRevisedV2(
                        replacesEventId = originalEventId,
                        planKey = planKey,
                        planVersion = planVersion,
                        itemKey = SyncKeyFactory.itemKey(planKey, wordKey, context.item.source),
                        wordKey = wordKey,
                        studyMode = transaction.reviewLog.mode,
                        feedback = transaction.reviewLog.feedback,
                        reviewedAtUtc = transaction.reviewLog.reviewedAt,
                        responseTimeMs = transaction.reviewLog.responseTimeMs,
                        shortTermStep = transaction.completedEvent.stepIndex,
                        baseCardVersion = context.card.repetitions,
                        previousReviewEventId = originalEventId,
                        fsrsAlgorithmVersion = "fsrs-v6",
                        card = cardSnapshot,
                        itemStatus = transaction.updatedItem.status,
                        shortTermEventStatus = transaction.completedEvent.status,
                    ),
                )
            } ?: LocalChangeFactory.feedbackApplied(
                FeedbackAppliedV2(
                    planKey = planKey,
                    planVersion = planVersion,
                    itemKey = SyncKeyFactory.itemKey(planKey, wordKey, context.item.source),
                    wordKey = wordKey,
                    studyMode = transaction.reviewLog.mode,
                    feedback = transaction.reviewLog.feedback,
                    reviewedAtUtc = transaction.reviewLog.reviewedAt,
                    responseTimeMs = transaction.reviewLog.responseTimeMs,
                    shortTermStep = transaction.completedEvent.stepIndex,
                    baseCardVersion = context.card.repetitions,
                    previousReviewEventId = null,
                    fsrsAlgorithmVersion = "fsrs-v6",
                    card = cardSnapshot,
                    itemStatus = transaction.updatedItem.status,
                    shortTermEventStatus = transaction.completedEvent.status,
                ),
            )
            store.appendChangeInCurrentTransaction(change, transaction.reviewLog.reviewedAt).eventId
        }
        transaction.copy(
            updatedCard = requireNotNull(database.cardStateDao().get(event.wordId, event.mode)).toModel(),
            reviewLog = transaction.reviewLog.copy(id = logId),
            followUpEvents = followUps,
            syncEventId = syncEventId,
        )
    }

    override suspend fun undoFeedback(token: FeedbackUndoToken) = database.withTransaction {
        val planDao = database.dailyPlanDao()
        val event = requireNotNull(planDao.getEvent(token.event.id)) { "Unknown feedback event: ${token.event.id}" }
        require(event.status == IntradayEventStatus.COMPLETED) { "Feedback event is not completed: ${event.id}" }
        requireNotNull(database.reviewLogDao().get(token.reviewLogId)) { "Missing review log: ${token.reviewLogId}" }
        token.autoAddedItemIds.forEach { itemId ->
            val autoItem = requireNotNull(planDao.getItem(itemId)) { "Missing auto-planned item: $itemId" }
            require(autoItem.sourceType == com.pengshi.words.model.PlanSource.NEW) { "Only auto-planned new words can be removed: $itemId" }
            planDao.getEventsForItem(itemId).forEach { autoEvent ->
                require(autoEvent.status == IntradayEventStatus.PENDING) { "Auto-planned item is already being studied: $itemId" }
                planDao.deleteEvent(autoEvent.id)
            }
            planDao.deleteItem(itemId)
        }
        token.followUpEventIds.forEach { followUpId ->
            val followUp = requireNotNull(planDao.getEvent(followUpId)) { "Missing follow-up event: $followUpId" }
            require(followUp.status == IntradayEventStatus.PENDING) { "Follow-up event is no longer pending: $followUpId" }
            planDao.deleteEvent(followUpId)
        }
        token.skippedEventSnapshots.forEach { snapshot ->
            val skipped = requireNotNull(planDao.getEvent(snapshot.id)) { "Missing skipped event: ${snapshot.id}" }.toModel()
            require(skipped.status == IntradayEventStatus.SKIPPED) { "Sibling event is no longer skipped: ${snapshot.id}" }
            planDao.updateEvent(snapshot.toEntity())
        }
        database.cardStateDao().upsert(token.previousCard.toEntity())
        planDao.updateEvent(token.event.toEntity())
        planDao.updateItem(token.previousItem.toEntity())
        planDao.updatePlan(token.previousPlan.toEntity())
        database.reviewLogDao().delete(token.reviewLogId)
    }
}

class RoomStudyDataSnapshotGateway(private val database: PengshiDatabase) : StudyDataSnapshotGateway {
    override suspend fun snapshot(): StudyDataSnapshot = database.withTransaction {
        StudyDataSnapshot(
            words = database.wordDao().getAll().map(WordEntity::toModel),
            exampleSentences = database.exampleSentenceDao().getAll().map(ExampleSentenceEntity::toModel),
            decks = database.deckDao().getAll().map(DeckEntity::toModel),
            deckWords = database.deckDao().getAllDeckWords().map(DeckWordEntity::toModel),
            cardStates = database.cardStateDao().getAll().map(CardStateEntity::toModel),
            dailyPlans = database.dailyPlanDao().getAllPlans().map(DailyPlanEntity::toModel),
            dailyPlanItems = database.dailyPlanDao().getAllItems().map(DailyPlanItemEntity::toModel),
            intradayReviewEvents = database.dailyPlanDao().getAllEvents().map(IntradayReviewEventEntity::toModel),
            reviewLogs = database.reviewLogDao().getAll().map(ReviewLogEntity::toModel),
            wordSenses = database.wordSenseDao().getAll().map(WordSenseEntity::toModel),
        )
    }

    override suspend fun restore(snapshot: StudyDataSnapshot) {
        database.withTransaction {
            database.wordDao().insertAll(snapshot.words.map(Word::toEntity))
            database.wordSenseDao().insertAll(snapshot.wordSenses.map(WordSense::toEntity))
            database.exampleSentenceDao().insertAll(snapshot.exampleSentences.map(ExampleSentence::toEntity))
            database.deckDao().insertAll(snapshot.decks.map(Deck::toEntity))
            database.deckDao().insertDeckWords(snapshot.deckWords.map(DeckWord::toEntity))
            database.cardStateDao().insertAll(snapshot.cardStates.map(CardState::toEntity))
            database.dailyPlanDao().insertPlans(snapshot.dailyPlans.map(DailyPlan::toEntity))
            database.dailyPlanDao().insertItems(snapshot.dailyPlanItems.map(DailyPlanItem::toEntity))
            database.dailyPlanDao().insertEvents(snapshot.intradayReviewEvents.map(IntradayReviewEvent::toEntity))
            database.reviewLogDao().insertAll(snapshot.reviewLogs.map(ReviewLog::toEntity))
        }
    }

    override suspend fun replace(snapshot: StudyDataSnapshot) {
        database.withTransaction {
            database.dailyPlanDao().deleteAllEvents()
            database.dailyPlanDao().deleteAllItems()
            database.dailyPlanDao().deleteAllPlans()
            database.reviewLogDao().deleteAll()
            database.cardStateDao().deleteAll()
            database.exampleSentenceDao().deleteAll()
            database.wordSenseDao().deleteAll()
            database.deckDao().deleteAllDeckWords()
            database.deckDao().deleteAll()
            database.wordDao().deleteAll()

            database.wordDao().insertAll(snapshot.words.map(Word::toEntity))
            database.wordSenseDao().insertAll(snapshot.wordSenses.map(WordSense::toEntity))
            database.exampleSentenceDao().insertAll(snapshot.exampleSentences.map(ExampleSentence::toEntity))
            database.deckDao().insertAll(snapshot.decks.map(Deck::toEntity))
            database.deckDao().insertDeckWords(snapshot.deckWords.map(DeckWord::toEntity))
            database.cardStateDao().insertAll(snapshot.cardStates.map(CardState::toEntity))
            database.dailyPlanDao().insertPlans(snapshot.dailyPlans.map(DailyPlan::toEntity))
            database.dailyPlanDao().insertItems(snapshot.dailyPlanItems.map(DailyPlanItem::toEntity))
            database.dailyPlanDao().insertEvents(snapshot.intradayReviewEvents.map(IntradayReviewEvent::toEntity))
            database.reviewLogDao().insertAll(snapshot.reviewLogs.map(ReviewLog::toEntity))
        }
    }

    override suspend fun mergeContent(remote: StudyDataSnapshot) {
        database.withTransaction {
            val localWordsBySpelling = database.wordDao().getAll()
                .associateBy { it.normalizedSpelling }
                .toMutableMap()
            val remoteWordIds = mutableMapOf<Long, Long>()
            remote.words.forEach { word ->
                val local = localWordsBySpelling[word.normalizedSpelling]
                val localId = if (local != null) {
                    val localHasMeaning = local.definitionCn.isNotBlank()
                    val remoteHasMeaning = word.definitionCn.isNotBlank()
                    val sameMeaning = localHasMeaning && remoteHasMeaning &&
                        wordSenseKey(local.partOfSpeech, local.definitionCn) == wordSenseKey(word.partOfSpeech, word.definitionCn)
                    val conflictingMeaning = localHasMeaning && remoteHasMeaning && !sameMeaning
                    if (conflictingMeaning) {
                        mergeMeaning(local, word.partOfSpeech, word.definitionCn, word.definitionSource, word.updatedAt)
                    }
                    val mainSource = when {
                        !localHasMeaning && remoteHasMeaning -> word.definitionSource
                        localHasMeaning && !remoteHasMeaning -> local.definitionSource
                        sameMeaning && sourceLabelNeedsUpgrade(local.definitionSource, word.definitionSource) -> word.definitionSource
                        else -> local.definitionSource
                    }
                    val merged = when {
                        word.updatedAt.isAfter(local.updatedAt) -> word.copy(
                            id = local.id,
                            partOfSpeech = if (localHasMeaning && !sameMeaning) local.partOfSpeech else word.partOfSpeech,
                            definitionCn = if (localHasMeaning && !sameMeaning) local.definitionCn else word.definitionCn,
                            definitionSource = mainSource,
                        ).toEntity()
                        !localHasMeaning && remoteHasMeaning -> local.copy(
                            partOfSpeech = word.partOfSpeech,
                            definitionCn = word.definitionCn,
                            definitionSource = word.definitionSource,
                        )
                        mainSource != local.definitionSource -> local.copy(definitionSource = mainSource)
                        else -> local
                    }
                    if (merged != local) database.wordDao().upsert(merged)
                    local.id
                } else {
                    database.wordDao().upsert(word.copy(id = 0).toEntity())
                    requireNotNull(database.wordDao().getByNormalizedSpelling(word.normalizedSpelling)).id
                }
                localWordsBySpelling[word.normalizedSpelling] =
                    database.wordDao().getByNormalizedSpelling(word.normalizedSpelling) ?: word.copy(id = localId).toEntity()
                remoteWordIds[word.id] = localId
            }

            val localDecks = database.deckDao().getAll().toMutableList()
            val remoteDeckIds = mutableMapOf<Long, Long>()
            remote.decks.filter { it.sourceType != DeckSourceType.BUILTIN }.forEach { deck ->
                val local = localDecks.firstOrNull {
                    it.name == deck.name && it.sourceType == DeckSourceTypeEntity.valueOf(deck.sourceType.name) &&
                        !(it.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE &&
                            deck.sourceFileName != com.pengshi.words.model.DELETED_USER_DECK_SOURCE &&
                            deck.createdAt.isAfter(it.updatedAt)) &&
                        !(deck.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE &&
                            it.sourceFileName != com.pengshi.words.model.DELETED_USER_DECK_SOURCE &&
                            it.createdAt.isAfter(deck.updatedAt))
                }
                val localId = local?.id ?: database.deckDao().insert(deck.copy(id = 0).toEntity()).also {
                    localDecks += deck.copy(id = it).toEntity()
                }
                val remoteDeleted = deck.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE
                val localDeleted = local?.sourceFileName == com.pengshi.words.model.DELETED_USER_DECK_SOURCE
                val remoteWins = local == null || deck.updatedAt.isAfter(local.updatedAt)
                if (local != null && remoteWins) {
                    database.deckDao().update(deck.copy(id = local.id).toEntity())
                }
                val deleted = if (remoteWins) remoteDeleted else localDeleted
                if (deleted) {
                    database.deckDao().deleteDeckWords(localId)
                } else {
                    remoteDeckIds[deck.id] = localId
                }
            }

            val existingLinks = database.deckDao().getAllDeckWords()
                .mapTo(mutableSetOf()) { it.deckId to it.wordId }
            remote.deckWords.forEach { link ->
                val deckId = remoteDeckIds[link.deckId] ?: return@forEach
                val wordId = remoteWordIds[link.wordId] ?: return@forEach
                if (existingLinks.add(deckId to wordId)) {
                    database.deckDao().insertDeckWord(DeckWordEntity(deckId, wordId, link.position, link.addedAt))
                }
            }
            val mergedLinks = database.deckDao().getAllDeckWords()
            remoteDeckIds.values.toSet().forEach { deckId ->
                val currentDeck = database.deckDao().getById(deckId) ?: return@forEach
                val actualCount = mergedLinks.count { it.deckId == deckId }
                if (actualCount != currentDeck.wordCount) {
                    database.deckDao().update(currentDeck.copy(wordCount = actualCount))
                }
            }

            val existingExamples = database.exampleSentenceDao().getAll()
                .associateBy { it.wordId to (it.sentenceEn to (it.sentenceCn ?: "")) }
                .toMutableMap()
            remote.exampleSentences.forEach { example ->
                val wordId = remoteWordIds[example.wordId] ?: return@forEach
                val key = wordId to (example.sentenceEn to (example.sentenceCn ?: ""))
                val existing = existingExamples[key]
                if (existing != null) {
                    if (com.pengshi.words.model.sourceLabelNeedsUpgrade(existing.source, example.source)) {
                        database.exampleSentenceDao().update(existing.copy(source = example.source))
                    }
                } else {
                    val entity = ExampleSentenceEntity(
                        wordId = wordId,
                        sentenceEn = example.sentenceEn,
                        sentenceCn = example.sentenceCn,
                        sortOrder = example.sortOrder,
                        source = example.source,
                    )
                    val id = database.exampleSentenceDao().insert(entity)
                    existingExamples[key] = entity.copy(id = id)
                }
            }
            remote.wordSenses.forEach { sense ->
                val wordId = remoteWordIds[sense.wordId] ?: return@forEach
                val currentWord = database.wordDao().getById(wordId) ?: return@forEach
                mergeMeaning(currentWord, sense.partOfSpeech, sense.definitionCn, sense.definitionSource, sense.createdAt)
            }
        }
    }

    private suspend fun mergeMeaning(
        word: WordEntity,
        partOfSpeech: String,
        definitionCn: String,
        source: String,
        createdAt: Instant,
    ) {
        val definition = definitionCn.trim().normalizeDictionaryText()
        if (definition.isBlank()) return
        val normalizedKey = wordSenseKey(partOfSpeech, definition)
        val cleanSource = source.trim().ifBlank { SOURCE_UNVERIFIED }
        if (normalizedKey == wordSenseKey(word.partOfSpeech, word.definitionCn)) {
            if (sourceLabelNeedsUpgrade(word.definitionSource, cleanSource)) {
                database.wordDao().update(word.copy(definitionSource = cleanSource))
            }
            return
        }

        val dao = database.wordSenseDao()
        val existing = dao.getByKey(word.id, normalizedKey)
        if (existing != null) {
            if (sourceLabelNeedsUpgrade(existing.definitionSource, cleanSource)) {
                dao.update(existing.copy(definitionSource = cleanSource))
            }
            return
        }
        val nextOrder = dao.getForWord(word.id).maxOfOrNull { it.sortOrder + 1 } ?: 0
        dao.insert(
            WordSenseEntity(
                wordId = word.id,
                partOfSpeech = partOfSpeech.trim(),
                definitionCn = definition,
                definitionSource = cleanSource,
                normalizedKey = normalizedKey,
                sortOrder = nextOrder,
                createdAt = createdAt,
            ),
        )
    }
}

private fun CardStateEntity.toModel() = CardState(id, wordId, studyMode, state, difficulty, stability, retrievability, dueAt, lastReviewedAt, scheduledDays, repetitions, lapses, learningStep, createdAt, updatedAt)
private fun CardState.toEntity() = CardStateEntity(id, wordId, mode, status, difficulty, stability, retrievability, dueAt, lastReviewedAt, scheduledDays, repetitions, lapses, learningStep, createdAt, updatedAt)
private fun WordEntity.toModel() = Word(id, spelling, normalizedSpelling, phonetic, partOfSpeech, definitionCn, tags, createdAt, updatedAt, frequencyRank, definitionSource, mnemonic)
private fun Word.toEntity() = WordEntity(id, spelling, normalizedSpelling, phonetic, partOfSpeech, definitionCn, tags, createdAt, updatedAt, frequencyRank, definitionSource, mnemonic)
private fun WordSenseEntity.toModel() = WordSense(id, wordId, partOfSpeech, definitionCn, definitionSource, normalizedKey, sortOrder, createdAt)
private fun WordSense.toEntity() = WordSenseEntity(id, wordId, partOfSpeech, definitionCn, definitionSource, normalizedKey, sortOrder, createdAt)
private fun ExampleSentenceEntity.toModel() = ExampleSentence(id, wordId, sentenceEn, sentenceCn, sortOrder, source)
private fun ExampleSentence.toEntity() = ExampleSentenceEntity(id, wordId, sentenceEn, sentenceCn, sortOrder, source)
private fun DeckEntity.toModel() = Deck(id, name, DeckSourceType.valueOf(sourceType.name), sourceFileName, wordCount, createdAt, updatedAt)
private fun Deck.toEntity() = DeckEntity(id, name, DeckSourceTypeEntity.valueOf(sourceType.name), sourceFileName, wordCount, createdAt, updatedAt)
private fun DeckWordEntity.toModel() = DeckWord(deckId, wordId, position, addedAt)
private fun DeckWord.toEntity() = DeckWordEntity(deckId, wordId, position, addedAt)
private fun ReviewLogEntity.toModel() = ReviewLog(id, wordId, mode, reviewedAt, feedback, responseTimeMs, previousStateSnapshot, nextStateSnapshot, previousDueAt, nextDueAt, eventType)
private fun ReviewLog.toEntity() = ReviewLogEntity(id, wordId, mode, reviewedAt, feedback, responseTimeMs, previousStateSnapshot, nextStateSnapshot, previousDueAt, nextDueAt, eventType)

private fun String.splitTags(): Set<String> = split(Regex("\\s+")).filter(String::isNotBlank).toSet()

private fun String.morphologyRoots(): Set<String> {
    val normalized = lowercase().filter(Char::isLetter)
    if (normalized.length < 4) return setOf(normalized)
    val roots = linkedSetOf(normalized)
    val suffixes = listOf(
        "ization", "ational", "fulness", "ousness", "iveness", "ability", "ibility",
        "itive", "ative", "ition", "ation", "ement", "ance", "ence", "ment", "ness",
        "ity", "ive", "ous", "able", "ible", "ent", "ant", "ing", "ied", "ed", "er", "or", "es", "s",
    )
    suffixes.forEach { suffix ->
        if (normalized.endsWith(suffix) && normalized.length - suffix.length >= 4) {
            roots += normalized.removeSuffix(suffix)
        }
    }
    if (normalized.endsWith("e") && normalized.length >= 5) roots += normalized.dropLast(1)
    return roots
}

private fun String.isSpellingNeighborOf(other: String): Boolean {
    val left = lowercase().filter(Char::isLetter)
    val right = other.lowercase().filter(Char::isLetter)
    if (left.length < 4 || right.length < 4) return false
    val prefix = left.zip(right).takeWhile { (a, b) -> a == b }.count()
    return prefix >= 5 || (kotlin.math.abs(left.length - right.length) <= 1 && spellingDistance(left, right) <= 2)
}

private fun spellingDistance(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { row, leftChar ->
        val current = IntArray(right.length + 1)
        current[0] = row + 1
        right.forEachIndexed { column, rightChar ->
            current[column + 1] = minOf(
                current[column] + 1,
                previous[column + 1] + 1,
                previous[column] + if (leftChar == rightChar) 0 else 1,
            )
        }
        previous = current
    }
    return previous[right.length]
}
private fun DailyPlan.toEntity() = DailyPlanEntity(id, localDate, quota, plannedUniqueWordCount, completedUniqueWordCount, DailyPlanStatusEntity.valueOf(status.name), createdAt, updatedAt)
private fun DailyPlanEntity.toModel() = DailyPlan(id, localDate, quota, plannedUniqueWordCount, completedUniqueWordCount, DailyPlanStatus.valueOf(status.name), createdAt, updatedAt)
private fun DailyPlanItem.toEntity(planId: Long) = DailyPlanItemEntity(id, planId, wordId, source, selectionRank, status)
private fun DailyPlanItem.toEntity() = DailyPlanItemEntity(id, dailyPlanId, wordId, source, selectionRank, status)
private fun DailyPlanItemEntity.toModel() = DailyPlanItem(id, dailyPlanId, wordId, sourceType, selectionRank, status)
private fun IntradayReviewEvent.toEntity(itemId: Long) = IntradayReviewEventEntity(id, itemId, wordId, mode, stepIndex, scheduledAt, completedAt, status, feedback)
private fun IntradayReviewEvent.toEntity() = IntradayReviewEventEntity(id, dailyPlanItemId, wordId, mode, stepIndex, scheduledAt, completedAt, status, feedback)
private fun IntradayReviewEventEntity.toModel() = IntradayReviewEvent(id, dailyPlanItemId, wordId, mode, stepIndex, scheduledAt, completedAt, status, feedback)
