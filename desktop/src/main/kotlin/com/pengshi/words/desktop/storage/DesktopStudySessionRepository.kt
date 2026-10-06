package com.pengshi.words.desktop.storage

import com.pengshi.words.model.CardState
import com.pengshi.words.model.CardStatus
import com.pengshi.words.model.CardKey
import com.pengshi.words.model.DailyPlan
import com.pengshi.words.model.DailyQuota
import com.pengshi.words.model.DailyPlanCandidate
import com.pengshi.words.model.DailyPlanEntry
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.DailyItemStatus
import com.pengshi.words.model.DailyPlanRepository
import com.pengshi.words.model.ExampleSentence
import com.pengshi.words.model.FeedbackContext
import com.pengshi.words.model.FeedbackSubmission
import com.pengshi.words.model.FeedbackTransaction
import com.pengshi.words.model.FeedbackUndoToken
import com.pengshi.words.model.IntradayEventStatus
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.RelatedWord
import com.pengshi.words.model.RelatedWordKind
import com.pengshi.words.model.ReviewLog
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.Word
import com.pengshi.words.sync.LocalChangeFactory
import com.pengshi.words.sync.CardSnapshotV2
import com.pengshi.words.sync.FeedbackAppliedV2
import com.pengshi.words.sync.FeedbackRevisedV2
import com.pengshi.words.sync.SyncKeyFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class DesktopStudySessionRepository(
    private val database: DesktopSqliteDatabase,
    private val syncStore: DesktopSyncStore? = null,
) : DailyPlanRepository, AutoCloseable {
    // Related-word rendering is part of every study-session refresh. Keep the
    // word table in memory while its row count/max id is unchanged so a 30-word
    // session does not repeatedly scan and re-tokenize the entire vocabulary.
    private var relatedWordsCache: List<Word>? = null
    private var relatedWordsCacheSignature: Pair<Int, Long?>? = null

    override suspend fun getPlan(localDate: LocalDate): DailyPlan? = database.read { connection ->
        connection.queryOne(
            "SELECT * FROM daily_plans WHERE local_date = ? LIMIT 1",
            { it.setString(1, localDate.toString()) },
            { it.toDailyPlan() },
        )
    }

    override suspend fun getPlanById(planId: Long): DailyPlan? = database.read { connection ->
        connection.plan(planId)
    }

    override suspend fun getItem(itemId: Long): DailyPlanItem? = database.read { connection ->
        connection.item(itemId)
    }

    override suspend fun getItems(planId: Long): List<DailyPlanItem> = database.read { connection ->
        connection.queryList(
            "SELECT * FROM daily_plan_items WHERE daily_plan_id = ? ORDER BY selection_rank",
            { it.setLong(1, planId) },
            { it.toDailyPlanItem() },
        )
    }

    override suspend fun getEvents(itemId: Long): List<IntradayReviewEvent> = database.read { connection ->
        connection.events(itemId)
    }

    override suspend fun getWord(wordId: Long): Word? = database.read { connection ->
        connection.queryOne("SELECT * FROM words WHERE id = ? LIMIT 1", { it.setLong(1, wordId) }, { it.toWord() })
    }

    override suspend fun getWordSenses(wordId: Long): List<com.pengshi.words.model.WordSense> = database.read { connection ->
        connection.queryList(
            "SELECT * FROM word_senses WHERE word_id = ? ORDER BY sort_order, id",
            { it.setLong(1, wordId) },
            { it.toWordSense() },
        )
    }

    suspend fun getWordByNormalizedSpelling(normalizedSpelling: String): Word? = database.read { connection ->
        connection.queryOne(
            "SELECT * FROM words WHERE normalized_spelling = ? LIMIT 1",
            { it.setString(1, normalizedSpelling) },
            { it.toWord() },
        )
    }

    override suspend fun getExamples(wordId: Long): List<ExampleSentence> = database.read { connection ->
        connection.queryList(
            "SELECT * FROM examples WHERE word_id = ? ORDER BY sort_order, id",
            { it.setLong(1, wordId) },
            { it.toExample() },
        )
    }

    override suspend fun getRelatedWords(wordId: Long): List<RelatedWord> = database.read { connection ->
        val signature = connection.queryOne(
            "SELECT COUNT(*) AS count, MAX(id) AS max_id FROM words",
            map = { result -> result.getInt("count") to result.getLong("max_id").takeUnless { result.wasNull() } },
        ) ?: (0 to null)
        val words = if (relatedWordsCacheSignature == signature && relatedWordsCache != null) {
            relatedWordsCache.orEmpty()
        } else {
            connection.queryList("SELECT * FROM words ORDER BY id", map = { it.toWord() }).also {
                relatedWordsCache = it
                relatedWordsCacheSignature = signature
            }
        }
        val current = words.firstOrNull { it.id == wordId } ?: return@read emptyList()
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
        val derivedIds = familyIds + derivedMatches.map { it.word.id }
        val similarMatches = words.asSequence()
            .filter { it.id !in derivedIds }
            .filter { it.spelling.isSpellingNeighborOf(current.spelling) }
            .sortedWith(compareBy<Word>({ spellingDistance(current.spelling, it.spelling) }, { it.frequencyRank ?: Int.MAX_VALUE }, { it.spelling.lowercase() }))
            .map { RelatedWord(it, RelatedWordKind.SIMILAR, "拼写相似") }
            .take(8)
            .toList()
        familyMatches + derivedMatches + similarMatches
    }

    override suspend fun getCard(wordId: Long, mode: StudyMode): CardState? = database.read { connection ->
        connection.card(wordId, mode)
    }

    suspend fun ensureCard(wordId: Long, mode: StudyMode, now: Instant) {
        database.transaction { connection ->
            if (connection.card(wordId, mode) == null) {
                connection.upsertCard(CardState.new(CardKey(wordId, mode), now))
            }
        }
    }

    override suspend fun getCandidates(mode: StudyMode, now: Instant): List<DailyPlanCandidate> =
        getCandidatesBefore(mode, now, now.plusNanos(1))

    override suspend fun getCandidatesBefore(mode: StudyMode, now: Instant, dueBefore: Instant): List<DailyPlanCandidate> = database.read { connection ->
        val words = connection.queryList("SELECT * FROM words ORDER BY id", map = { it.toWord() })
        val cardsByWordId = connection.queryList(
            "SELECT * FROM card_states WHERE study_mode = ?",
            { it.setString(1, mode.name) },
            { it.toCardState() },
        ).associateBy { it.wordId }
        words.mapNotNull { word ->
            val card = cardsByWordId[word.id]
            val isNew = card == null || card.status == CardStatus.NEW
            val dueAt = card?.dueAt
            if (!isNew && (dueAt == null || !dueAt.isBefore(dueBefore))) return@mapNotNull null
            DailyPlanCandidate(
                wordId = word.id,
                isNew = isNew,
                dueAt = dueAt,
                retrievability = card?.retrievability,
                overdueSeconds = dueAt?.let { (now.epochSecond - it.epochSecond).coerceAtLeast(0) } ?: 0,
                familyKey = word.tags.splitTags().firstOrNull { it.startsWith("family:") },
                initialKey = word.spelling.firstOrNull()?.lowercase(),
                difficulty = card?.difficulty,
                lapses = card?.lapses ?: 0,
                lastReviewedAt = card?.lastReviewedAt,
                intervalDays = card?.lastReviewedAt?.let { Duration.between(it, now).toDays().coerceAtLeast(0) },
                frequencyRank = word.frequencyRank,
            )
        }
    }

    override suspend fun createPlan(plan: DailyPlan, entries: List<DailyPlanEntry>): DailyPlan =
        database.transaction { connection ->
            DailyQuota.requireValid(plan.quota)
            require(entries.count { it.item.source != PlanSource.EXTRA } <= plan.quota) {
                "Daily plan cannot contain more than its locked quota"
            }
            val planId = connection.insertPlan(plan)
            entries.forEach { entry -> connection.insertEntry(planId, entry) }
            syncStore?.let { store ->
                val mode = entries.firstOrNull()?.initialEvent?.mode ?: StudyMode.EN_TO_CN
                val wordKeys = entries.associate { entry ->
                    val word = requireNotNull(
                        connection.queryOne(
                            "SELECT * FROM words WHERE id = ? LIMIT 1",
                            { it.setLong(1, entry.item.wordId) },
                        ) { it.toWord() },
                    )
                    entry.item.wordId to SyncKeyFactory.wordKey(word.normalizedSpelling)
                }
                store.appendChangeInTransaction(
                    connection,
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

    /** Replaces only an untouched automatic plan while applying its canonical remote lock. */
    suspend fun replaceUnstartedPlanFromSync(plan: DailyPlan, entries: List<DailyPlanEntry>): DailyPlan =
        database.transaction { connection ->
            require(plan.id > 0) { "An existing plan id is required" }
            DailyQuota.requireValid(plan.quota)
            require(entries.count { it.item.source != PlanSource.EXTRA } <= plan.quota) {
                "Daily plan cannot contain more than its locked quota"
            }
            val persistedPlan = requireNotNull(connection.plan(plan.id)) { "Daily plan no longer exists" }
            require(persistedPlan.localDate == plan.localDate) { "Daily plan date changed during sync" }
            val oldItems = connection.queryList(
                "SELECT * FROM daily_plan_items WHERE daily_plan_id = ? ORDER BY selection_rank",
                { it.setLong(1, plan.id) },
            ) { it.toDailyPlanItem() }
            require(oldItems.all { item ->
                item.source in setOf(PlanSource.NEW, PlanSource.DUE_REVIEW) &&
                    item.status == DailyItemStatus.PENDING &&
                    connection.events(item.id).let { events ->
                        events.isNotEmpty() && events.all {
                            it.status == IntradayEventStatus.PENDING && it.feedback == null
                        }
                    }
            }) { "Only an untouched automatic plan can be replaced from sync" }
            oldItems.forEach { item ->
                connection.executeUpdate(
                    "DELETE FROM intraday_review_events WHERE daily_plan_item_id = ?",
                ) { it.setLong(1, item.id) }
            }
            connection.executeUpdate("DELETE FROM daily_plan_items WHERE daily_plan_id = ?") {
                it.setLong(1, plan.id)
            }
            connection.updatePlan(plan.copy(id = persistedPlan.id))
            entries.forEach { entry -> connection.insertEntry(persistedPlan.id, entry) }
            plan.copy(id = persistedPlan.id)
        }

    override suspend fun appendToPlan(plan: DailyPlan, entries: List<DailyPlanEntry>): DailyPlan =
        database.transaction { connection ->
            require(plan.id > 0) { "An existing plan id is required" }
            val persistedPlan = requireNotNull(connection.plan(plan.id)) { "Unknown daily plan: ${plan.id}" }
            DailyQuota.requireValid(persistedPlan.quota)
            require(plan.quota == persistedPlan.quota) { "Daily plan quota cannot be changed" }
            val existingMainCount = connection.queryOne(
                "SELECT COUNT(*) AS count FROM daily_plan_items WHERE daily_plan_id = ? AND source_type != ?",
                { statement -> statement.setLong(1, plan.id); statement.setString(2, PlanSource.EXTRA.name) },
                { it.getInt("count") },
            ) ?: 0
            val incomingMainCount = entries.count { it.item.source != PlanSource.EXTRA }
            if (incomingMainCount > 0) require(existingMainCount + incomingMainCount <= persistedPlan.quota) {
                "Daily plan cannot contain more than its locked quota"
            }
            val retainedEntries = connection.queryList(
                "SELECT * FROM daily_plan_items WHERE daily_plan_id = ? ORDER BY selection_rank",
                { it.setLong(1, plan.id) },
            ) { result -> result.toDailyPlanItem() }.mapNotNull { item ->
                val initialEvent = connection.events(item.id).minByOrNull { it.stepIndex }
                initialEvent?.let { DailyPlanEntry(item, it) }
            }
            entries.forEach { entry -> connection.insertEntry(plan.id, entry) }
            connection.updatePlan(plan)
            syncStore?.let { store ->
                val mode = (retainedEntries + entries).firstOrNull()?.initialEvent?.mode ?: return@let
                val oldPlanVersion = store.currentPlanVersionInTransaction(connection, SyncKeyFactory.planKey(plan.localDate))
                val allEntries = retainedEntries + entries
                val wordKeys = allEntries.associate { entry ->
                    val word = requireNotNull(
                        connection.queryOne(
                            "SELECT * FROM words WHERE id = ? LIMIT 1",
                            { it.setLong(1, entry.item.wordId) },
                        ) { it.toWord() },
                    )
                    entry.item.wordId to SyncKeyFactory.wordKey(word.normalizedSpelling)
                }
                store.appendChangeInTransaction(
                    connection,
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

    override suspend fun replaceUnseenAutomaticItems(
        plan: DailyPlan,
        removeItemIds: Set<Long>,
        entries: List<DailyPlanEntry>,
        now: Instant,
        reason: String,
    ): DailyPlan = database.transaction { connection ->
        require(plan.id > 0) { "An existing plan id is required" }
        val persistedPlanQuota = requireNotNull(connection.plan(plan.id)) { "Daily plan no longer exists" }.quota
        DailyQuota.requireValid(plan.quota)
        val persistedItems = connection.queryList(
            "SELECT * FROM daily_plan_items WHERE daily_plan_id = ? ORDER BY selection_rank",
            { it.setLong(1, plan.id) },
        ) { result -> result.toDailyPlanItem() }
        val removedItems = persistedItems.filter { it.id in removeItemIds }
        require(removedItems.size == removeItemIds.size) { "Every replacement item must belong to the plan" }
        removedItems.forEach { item ->
            require(item.source == PlanSource.NEW || item.source == PlanSource.DUE_REVIEW) {
                "Only automatic review or new items can be replaced: ${item.id}"
            }
            val events = connection.events(item.id)
            require(item.status == com.pengshi.words.model.DailyItemStatus.PENDING && events.isNotEmpty() &&
                events.all { event -> event.status == IntradayEventStatus.PENDING && event.feedback == null }) {
                "Only unseen automatic items can be replaced: ${item.id}"
            }
        }
        val retainedEntries = persistedItems.filterNot { it.id in removeItemIds }.mapNotNull { item ->
            connection.events(item.id).minByOrNull { it.stepIndex }?.let { event -> DailyPlanEntry(item, event) }
        }
        val removedWordKeys = removedItems.map { item ->
            val word = requireNotNull(
                connection.queryOne(
                    "SELECT * FROM words WHERE id = ? LIMIT 1",
                    { it.setLong(1, item.wordId) },
                ) { it.toWord() },
            )
            SyncKeyFactory.itemKey(
                SyncKeyFactory.planKey(plan.localDate),
                SyncKeyFactory.wordKey(word.normalizedSpelling),
                item.source,
            )
        }
        removedItems.forEach { item ->
            connection.executeUpdate("DELETE FROM intraday_review_events WHERE daily_plan_item_id = ?") {
                it.setLong(1, item.id)
            }
            connection.executeUpdate("DELETE FROM daily_plan_items WHERE id = ?") { it.setLong(1, item.id) }
        }
        entries.forEach { entry -> connection.insertEntry(plan.id, entry) }
        val actualMainCount = persistedItems.count { it.id !in removeItemIds && it.source != PlanSource.EXTRA } +
            entries.count { it.item.source != PlanSource.EXTRA }
        if (entries.isNotEmpty()) {
            require(actualMainCount <= maxOf(persistedPlanQuota, plan.quota)) {
                "Daily plan cannot contain more items than its existing or updated quota"
            }
        }
        val updatedPlan = plan.copy(plannedUniqueWordCount = actualMainCount, updatedAt = now)
        connection.updatePlan(updatedPlan)
        syncStore?.let { store ->
            val mode = (retainedEntries + entries).firstOrNull()?.initialEvent?.mode ?: return@let
            val oldPlanVersion = store.currentPlanVersionInTransaction(connection, SyncKeyFactory.planKey(plan.localDate))
            val allEntries = retainedEntries + entries
            val wordKeys = allEntries.associate { entry ->
                val word = requireNotNull(
                    connection.queryOne(
                        "SELECT * FROM words WHERE id = ? LIMIT 1",
                        { it.setLong(1, entry.item.wordId) },
                    ) { it.toWord() },
                )
                entry.item.wordId to SyncKeyFactory.wordKey(word.normalizedSpelling)
            }
            store.appendChangeInTransaction(
                connection,
                LocalChangeFactory.planReconciled(
                    plan = updatedPlan,
                    retainedEntries = retainedEntries,
                    addedEntries = entries,
                    mode = mode,
                    wordKeyForWordId = { wordKeys.getValue(it) },
                    removedItemKeys = removedWordKeys,
                    oldPlanVersion = oldPlanVersion,
                    newPlanVersion = oldPlanVersion + 1,
                    reason = reason,
                ),
                now,
            )
        }
        updatedPlan
    }

    override suspend fun submitFeedback(
        eventId: Long,
        transform: (FeedbackContext) -> FeedbackTransaction,
    ): FeedbackTransaction = submitFeedback(eventId, transform, FeedbackSubmission())

    override suspend fun submitFeedback(
        eventId: Long,
        transform: (FeedbackContext) -> FeedbackTransaction,
        submission: FeedbackSubmission,
    ): FeedbackTransaction = database.transaction { connection ->
        val event = requireNotNull(connection.event(eventId)) { "Unknown intraday event: $eventId" }
        require(event.status == IntradayEventStatus.PENDING) { "Event is not pending: $eventId" }
        val item = requireNotNull(connection.item(event.dailyPlanItemId)) { "Missing daily plan item for event: $eventId" }
        val plan = requireNotNull(connection.plan(item.dailyPlanId)) { "Missing daily plan for event: $eventId" }
        val card = requireNotNull(connection.card(event.wordId, event.mode)) { "Missing card state for event: $eventId" }
        require(event.wordId == item.wordId) { "Event word does not match its item: $eventId" }
        val context = FeedbackContext(plan, item, event, card, connection.events(item.id))
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

        connection.upsertCard(transaction.updatedCard)
        val logId = connection.insertReviewLog(transaction.reviewLog)
        connection.updateEvent(transaction.completedEvent)
        transaction.skippedEvents.forEach { connection.updateEvent(it) }
        val followUps = transaction.followUpEvents.map { followUp ->
            followUp.copy(id = connection.insertEvent(followUp))
        }
        connection.updateItem(transaction.updatedItem)
        connection.updatePlan(transaction.updatedPlan)
        val syncEventId = syncStore?.takeIf { submission.recordSyncEvent }?.let { store ->
            val word = requireNotNull(
                connection.queryOne("SELECT * FROM words WHERE id = ? LIMIT 1", { it.setLong(1, event.wordId) }) { it.toWord() },
            ) { "Missing word for sync event: ${event.wordId}" }
            val planKey = SyncKeyFactory.planKey(plan.localDate)
            val planVersion = store.currentPlanVersionInTransaction(connection, planKey)
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
            store.appendChangeInTransaction(connection, change, transaction.reviewLog.reviewedAt).eventId
        }
        transaction.copy(
            updatedCard = requireNotNull(connection.card(event.wordId, event.mode)),
            reviewLog = transaction.reviewLog.copy(id = logId),
            followUpEvents = followUps,
            syncEventId = syncEventId,
        )
    }

    override suspend fun undoFeedback(token: FeedbackUndoToken): Unit = database.transaction { connection ->
        val event = requireNotNull(connection.event(token.event.id)) { "Unknown feedback event: ${token.event.id}" }
        require(event.status == IntradayEventStatus.COMPLETED) { "Feedback event is not completed: ${event.id}" }
        requireNotNull(connection.reviewLog(token.reviewLogId)) { "Missing review log: ${token.reviewLogId}" }
        token.autoAddedItemIds.forEach { itemId ->
            val autoItem = requireNotNull(connection.item(itemId)) { "Missing auto-planned item: $itemId" }
            require(autoItem.source == PlanSource.NEW) { "Only auto-planned new words can be removed: $itemId" }
            connection.events(itemId).forEach { autoEvent ->
                require(autoEvent.status == IntradayEventStatus.PENDING) { "Auto-planned item is already being studied: $itemId" }
                connection.executeUpdate("DELETE FROM intraday_review_events WHERE id = ?") { it.setLong(1, autoEvent.id) }
            }
            connection.executeUpdate("DELETE FROM daily_plan_items WHERE id = ?") { it.setLong(1, itemId) }
        }
        token.followUpEventIds.forEach { followUpId ->
            val followUp = requireNotNull(connection.event(followUpId)) { "Missing follow-up event: $followUpId" }
            require(followUp.status == IntradayEventStatus.PENDING) { "Follow-up event is no longer pending: $followUpId" }
            connection.executeUpdate("DELETE FROM intraday_review_events WHERE id = ?") { it.setLong(1, followUpId) }
        }
        token.skippedEventSnapshots.forEach { snapshot ->
            val skipped = requireNotNull(connection.event(snapshot.id)) { "Missing skipped event: ${snapshot.id}" }
            require(skipped.status == IntradayEventStatus.SKIPPED) { "Sibling event is no longer skipped: ${snapshot.id}" }
            connection.updateEvent(snapshot)
        }
        connection.upsertCard(token.previousCard)
        connection.updateEvent(token.event)
        connection.updateItem(token.previousItem)
        connection.updatePlan(token.previousPlan)
        connection.executeUpdate("DELETE FROM review_logs WHERE id = ?") { it.setLong(1, token.reviewLogId) }
        Unit
    }

    override fun close() = database.close()
}


private fun Connection.plan(id: Long): DailyPlan? =
    queryOne("SELECT * FROM daily_plans WHERE id = ? LIMIT 1", { it.setLong(1, id) }, { it.toDailyPlan() })

private fun Connection.item(id: Long): DailyPlanItem? =
    queryOne("SELECT * FROM daily_plan_items WHERE id = ? LIMIT 1", { it.setLong(1, id) }, { it.toDailyPlanItem() })

private fun Connection.event(id: Long): IntradayReviewEvent? =
    queryOne("SELECT * FROM intraday_review_events WHERE id = ? LIMIT 1", { it.setLong(1, id) }, { it.toIntradayEvent() })

private fun Connection.events(itemId: Long): List<IntradayReviewEvent> = queryList(
    "SELECT * FROM intraday_review_events WHERE daily_plan_item_id = ? ORDER BY step_index",
    { it.setLong(1, itemId) },
    { it.toIntradayEvent() },
)

private fun Connection.card(wordId: Long, mode: StudyMode): CardState? = queryOne(
    "SELECT * FROM card_states WHERE word_id = ? AND study_mode = ? LIMIT 1",
    { statement -> statement.setLong(1, wordId); statement.setString(2, mode.name) },
    { it.toCardState() },
)

private fun Connection.reviewLog(id: Long): ReviewLog? =
    queryOne("SELECT * FROM review_logs WHERE id = ? LIMIT 1", { it.setLong(1, id) }, { it.toReviewLog() })

private fun Connection.insertPlan(plan: DailyPlan): Long = generatedId(
    """INSERT INTO daily_plans
        (local_date, quota, planned_unique_word_count, completed_unique_word_count, status, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
) { statement -> statement.bindPlan(plan, includeId = false) }

private fun Connection.insertEntry(planId: Long, entry: DailyPlanEntry) {
    require(entry.item.wordId == entry.initialEvent.wordId)
    val itemId = generatedId(
        """INSERT INTO daily_plan_items
            (daily_plan_id, word_id, source_type, selection_rank, status) VALUES (?, ?, ?, ?, ?)""".trimIndent(),
    ) { statement -> statement.bindItem(entry.item.copy(dailyPlanId = planId), includeId = false) }
    insertEvent(entry.initialEvent.copy(dailyPlanItemId = itemId))
}

private fun Connection.insertEvent(event: IntradayReviewEvent): Long = generatedId(
    """INSERT INTO intraday_review_events
        (daily_plan_item_id, word_id, study_mode, step_index, scheduled_at, completed_at, status, feedback)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
) { statement -> statement.bindEvent(event, includeId = false) }

private fun Connection.insertReviewLog(log: ReviewLog): Long = generatedId(
    """INSERT INTO review_logs
        (word_id, study_mode, reviewed_at, feedback, response_time_ms, previous_state_snapshot,
         next_state_snapshot, previous_due_at, next_due_at, event_type)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
) { statement -> statement.bindReviewLog(log, includeId = false) }

private fun Connection.upsertCard(card: CardState) {
    val updated = executeUpdate(
        """UPDATE card_states SET status = ?, difficulty = ?, stability = ?, retrievability = ?, due_at = ?,
            last_reviewed_at = ?, scheduled_days = ?, repetitions = ?, lapses = ?, learning_step = ?, updated_at = ?
            WHERE word_id = ? AND study_mode = ?""".trimIndent(),
    ) { statement ->
        statement.setString(1, card.status.name)
        statement.setDouble(2, card.difficulty)
        statement.setDouble(3, card.stability)
        statement.setDouble(4, card.retrievability)
        statement.setInstant(5, card.dueAt)
        statement.setInstant(6, card.lastReviewedAt)
        statement.setInt(7, card.scheduledDays)
        statement.setInt(8, card.repetitions)
        statement.setInt(9, card.lapses)
        statement.setInt(10, card.learningStep)
        statement.setInstant(11, card.updatedAt)
        statement.setLong(12, card.wordId)
        statement.setString(13, card.mode.name)
    }
    if (updated == 0) {
        executeUpdate(
            """INSERT INTO card_states
                (word_id, study_mode, status, difficulty, stability, retrievability, due_at, last_reviewed_at,
                 scheduled_days, repetitions, lapses, learning_step, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
        ) { statement -> statement.bindCard(card, includeId = false) }
    }
}

private fun Connection.updatePlan(plan: DailyPlan) {
    executeUpdate(
        """UPDATE daily_plans SET local_date = ?, quota = ?, planned_unique_word_count = ?,
            completed_unique_word_count = ?, status = ?, created_at = ?, updated_at = ? WHERE id = ?""".trimIndent(),
    ) { statement -> statement.bindPlan(plan, includeId = true) }
}

private fun Connection.updateItem(item: DailyPlanItem) {
    executeUpdate(
        """UPDATE daily_plan_items SET daily_plan_id = ?, word_id = ?, source_type = ?, selection_rank = ?,
            status = ? WHERE id = ?""".trimIndent(),
    ) { statement -> statement.bindItem(item, includeId = true) }
}

private fun Connection.updateEvent(event: IntradayReviewEvent) {
    executeUpdate(
        """UPDATE intraday_review_events SET daily_plan_item_id = ?, word_id = ?, study_mode = ?, step_index = ?,
            scheduled_at = ?, completed_at = ?, status = ?, feedback = ? WHERE id = ?""".trimIndent(),
    ) { statement -> statement.bindEvent(event, includeId = true) }
}

internal fun PreparedStatement.bindPlan(plan: DailyPlan, includeId: Boolean) {
    var index = 1
    setString(index++, plan.localDate.toString())
    setInt(index++, plan.quota)
    setInt(index++, plan.plannedUniqueWordCount)
    setInt(index++, plan.completedUniqueWordCount)
    setString(index++, plan.status.name)
    setInstant(index++, plan.createdAt)
    setInstant(index++, plan.updatedAt)
    if (includeId) setLong(index, plan.id)
}

internal fun PreparedStatement.bindItem(item: DailyPlanItem, includeId: Boolean) {
    var index = 1
    setLong(index++, item.dailyPlanId)
    setLong(index++, item.wordId)
    setString(index++, item.source.name)
    setInt(index++, item.selectionRank)
    setString(index++, item.status.name)
    if (includeId) setLong(index, item.id)
}

internal fun PreparedStatement.bindEvent(event: IntradayReviewEvent, includeId: Boolean) {
    var index = 1
    setLong(index++, event.dailyPlanItemId)
    setLong(index++, event.wordId)
    setString(index++, event.mode.name)
    setInt(index++, event.stepIndex)
    setInstant(index++, event.scheduledAt)
    setInstant(index++, event.completedAt)
    setString(index++, event.status.name)
    setNullableString(index++, event.feedback?.name)
    if (includeId) setLong(index, event.id)
}

internal fun PreparedStatement.bindCard(card: CardState, includeId: Boolean) {
    var index = 1
    setLong(index++, card.wordId)
    setString(index++, card.mode.name)
    setString(index++, card.status.name)
    setDouble(index++, card.difficulty)
    setDouble(index++, card.stability)
    setDouble(index++, card.retrievability)
    setInstant(index++, card.dueAt)
    setInstant(index++, card.lastReviewedAt)
    setInt(index++, card.scheduledDays)
    setInt(index++, card.repetitions)
    setInt(index++, card.lapses)
    setInt(index++, card.learningStep)
    setInstant(index++, card.createdAt)
    setInstant(index++, card.updatedAt)
    if (includeId) setLong(index, card.id)
}

internal fun PreparedStatement.bindReviewLog(log: ReviewLog, includeId: Boolean) {
    var index = 1
    setLong(index++, log.wordId)
    setString(index++, log.mode.name)
    setInstant(index++, log.reviewedAt)
    setString(index++, log.feedback.name)
    setLong(index++, log.responseTimeMs)
    setString(index++, log.previousStateSnapshot)
    setString(index++, log.nextStateSnapshot)
    setInstant(index++, log.previousDueAt)
    setInstant(index++, log.nextDueAt)
    setString(index++, log.eventType.name)
    if (includeId) setLong(index, log.id)
}

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
        if (normalized.endsWith(suffix) && normalized.length - suffix.length >= 4) roots += normalized.removeSuffix(suffix)
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
