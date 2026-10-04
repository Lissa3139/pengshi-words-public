package com.pengshi.words.database

import androidx.room.withTransaction
import com.pengshi.words.sync.SyncApplyResult
import com.pengshi.words.sync.SyncApplyState
import com.pengshi.words.sync.SyncCodec
import com.pengshi.words.sync.SyncCursor
import com.pengshi.words.sync.SyncDownload
import com.pengshi.words.sync.SyncEventKind
import com.pengshi.words.sync.SyncEventRecord
import com.pengshi.words.sync.PlanSyncVersion
import com.pengshi.words.sync.SyncEventSequence
import com.pengshi.words.sync.SyncLocalStore
import com.pengshi.words.sync.SyncRemoteEventApplier
import com.pengshi.words.sync.StudySyncReducer
import com.pengshi.words.sync.cursorKey
import com.pengshi.words.sync.LocalChangeDraft
import com.pengshi.words.sync.CardSnapshotV2
import com.pengshi.words.sync.FeedbackAppliedV2
import com.pengshi.words.sync.FeedbackPayloadV2
import com.pengshi.words.sync.LocalChangeFactory
import com.pengshi.words.sync.SyncPayloadV2
import com.pengshi.words.model.DailyItemStatus
import com.pengshi.words.model.IntradayEventStatus
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.ReviewEventType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class SyncRoomStore(
    private val database: PengshiDatabase,
    val deviceId: String,
    var remoteEventApplier: SyncRemoteEventApplier? = null,
) : SyncLocalStore {
    init {
        require(deviceId.isNotBlank()) { "Device id must not be blank" }
    }

    override suspend fun readCursor(): SyncCursor = database.withTransaction {
        val events = database.syncDao().getAllEvents()
        SyncCursor(
            revision = database.syncDao().getMetadata(REVISION_KEY),
            knownEventIds = events.mapTo(linkedSetOf()) { it.eventId },
            appliedEventIds = database.syncDao().getEventIdsByApplyState(SyncApplyState.APPLIED).toSet(),
            perDeviceHighWatermarks = events.groupBy { it.deviceId }
                .mapValues { (_, values) -> values.maxOfOrNull { it.sequence } ?: 0L },
            knownFiles = database.syncDao().getMetadata(FILES_KEY)
                ?.split('\n')
                ?.filter(String::isNotBlank)
                ?.toSet()
                ?: emptySet(),
            eventIdsBySequence = events.associate { SyncEventSequence(it.deviceId, it.sequence) to it.eventId },
            knownEventsById = events.associate { it.eventId to it.toModel() },
        )
    }

    override suspend fun pendingEvents(): List<SyncEventRecord> = database.syncDao().getPendingEvents().map { it.toModel() }

    override suspend fun checkpointEvents(): List<SyncEventRecord> =
        database.syncDao().getAllEvents().map { it.toModel() }

    override suspend fun restoreCheckpointEvents(events: List<SyncEventRecord>, revision: String) {
        database.withTransaction {
            val dao = database.syncDao()
            dao.deleteAllEvents()
            events.sortedWith(compareBy(SyncEventRecord::deviceId, SyncEventRecord::sequence)).forEach { event ->
                check(
                    dao.insertEvent(
                        event.toEntity(
                            uploadedRevision = revision,
                            applyState = SyncApplyState.APPLIED,
                            sourceFile = CHECKPOINT_SOURCE,
                        ),
                    ) != -1L,
                ) { "Failed to restore checkpoint event ${event.eventId}" }
            }
            events.groupBy(SyncEventRecord::deviceId).forEach { (eventDeviceId, deviceEvents) ->
                val restoredSequence = deviceEvents.maxOf(SyncEventRecord::sequence)
                val current = dao.getDevice(eventDeviceId)
                if (current == null) {
                    dao.insertDevice(SyncDeviceEntity(eventDeviceId, restoredSequence))
                } else if (restoredSequence > current.nextSequence) {
                    dao.updateDevice(current.copy(nextSequence = restoredSequence))
                }
            }
            dao.putMetadata(SyncMetadataEntity(REVISION_KEY, revision))
        }
    }

    suspend fun appendEvent(
        kind: SyncEventKind,
        planKey: String?,
        wordKey: String?,
        payload: String,
        occurredAtUtc: Instant = Instant.now(),
        replacesEventId: String? = null,
    ): SyncEventRecord = database.withTransaction {
        appendEventInTransaction(kind, planKey, wordKey, payload, occurredAtUtc, replacesEventId)
    }

    /** Appends to the current Room transaction; the caller owns the surrounding transaction. */
    suspend fun appendChangeInCurrentTransaction(draft: LocalChangeDraft, occurredAtUtc: Instant): SyncEventRecord =
        appendEventInTransaction(
            kind = draft.kind,
            planKey = draft.planKey,
            wordKey = draft.wordKey,
            payload = draft.payload,
            occurredAtUtc = occurredAtUtc,
            replacesEventId = draft.replacesEventId,
        )

    suspend fun currentPlanVersionInCurrentTransaction(planKey: String): Int =
        PlanSyncVersion.fromPayloads(database.syncDao().getAppliedPlanPayloads(planKey))

    override suspend fun applyRemote(bundle: SyncDownload): SyncApplyResult {
        val insertedEventIds = linkedSetOf<String>()
        val changedPlanKeys = linkedSetOf<String>()
        val events = bundle.manifest.chunks
            .filter { it.path in bundle.files }
            .flatMap { ref ->
                bundle.files[ref.path]?.let(SyncCodec::decodeEvents)
                    ?.map { event -> ref.path to event }
                    ?: error("Missing sync chunk: ${ref.path}")
            }
        database.withTransaction {
            events.forEach { (sourceFile, event) ->
                val inserted = database.syncDao().insertEvent(
                    event.toEntity(
                        uploadedRevision = bundle.manifest.revision,
                        applyState = SyncApplyState.RECEIVED,
                        sourceFile = sourceFile,
                    ),
                )
                if (inserted != -1L) {
                    insertedEventIds += event.eventId
                    event.planKey?.let(changedPlanKeys::add)
                }
            }
            val allFiles = buildSet {
                bundle.manifest.bootstrap?.let { add(it.cursorKey()) }
                bundle.manifest.chunks.forEach { add(it.cursorKey()) }
                bundle.manifest.plans.forEach { add(it.cursorKey()) }
                bundle.manifest.content.forEach { add(it.cursorKey()) }
            }
            database.syncDao().putMetadata(SyncMetadataEntity(REVISION_KEY, bundle.manifest.revision))
            database.syncDao().putMetadata(SyncMetadataEntity(FILES_KEY, allFiles.sorted().joinToString("\n")))
        }
        for (event in StudySyncReducer.orderEvents(pendingApplicationEvents())) {
            applyRemoteEvent(event)
        }
        return SyncApplyResult(insertedEventIds, changedPlanKeys)
    }

    override suspend fun pendingApplicationEvents(): List<SyncEventRecord> = database.withTransaction {
        database.syncDao()
            .getPendingApplicationEvents(listOf(SyncApplyState.RECEIVED, SyncApplyState.DEFERRED))
            .map { it.toModel() }
    }

    override suspend fun conflictApplicationEvents(): List<SyncEventRecord> = database.withTransaction {
        database.syncDao()
            .getPendingApplicationEvents(listOf(SyncApplyState.CONFLICT))
            .map { it.toModel() }
    }

    override suspend fun retryDeferredApplications() {
        for (event in StudySyncReducer.orderEvents(pendingApplicationEvents())) {
            applyRemoteEvent(event)
        }
    }

    suspend fun retryRemoteApplications() = retryDeferredApplications()

    /**
     * Older clients replayed their own uploaded feedback after downloading its chunk.
     * The second local review was committed without an outbox entry. Recover only the
     * exact two-log, two-step case while that second review is still the card's latest.
     */
    suspend fun recoverReplayedLocalFeedback(): Int = database.withTransaction {
        val syncDao = database.syncDao()
        val events = syncDao.getAllEvents()
        val knownFeedbackSteps = events.mapNotNull { row ->
            (runCatching { SyncPayloadV2.decode(row.payload) }.getOrNull() as? FeedbackPayloadV2)
                ?.let { payload -> Triple(payload.itemKey, payload.shortTermStep, payload.reviewedAtUtc) }
        }.toMutableSet()
        var recovered = 0
        for (row in events) {
            if (row.deviceId != deviceId || row.sourceFile != null || row.applyAttempts <= 0 ||
                row.uploadedRevision == null || row.kind != SyncEventKind.FEEDBACK_APPLIED_V2
            ) continue
            val original = runCatching { SyncPayloadV2.decode(row.payload) }.getOrNull() as? FeedbackAppliedV2
                ?: continue
            if (original.itemStatus != DailyItemStatus.IN_PROGRESS) continue
            val storedReviewedAt = Instant.ofEpochMilli(original.reviewedAtUtc.toEpochMilli())
            val date = runCatching { LocalDate.parse(original.planKey.removePrefix("plan:")) }.getOrNull()
                ?: continue
            val plan = database.dailyPlanDao().getPlanByDate(date) ?: continue
            val word = database.wordDao().getByNormalizedSpelling(original.wordKey.removePrefix("word:"))
                ?: continue
            val item = database.dailyPlanDao().getItemsForPlan(plan.id).singleOrNull {
                it.wordId == word.id && it.sourceType != PlanSource.EXTRA
            } ?: continue
            val itemEvents = database.dailyPlanDao().getEventsForItem(item.id)
            if (itemEvents.none {
                it.stepIndex == original.shortTermStep && it.status == IntradayEventStatus.COMPLETED &&
                        it.completedAt == storedReviewedAt && it.feedback == original.feedback
                }
            ) continue
            val replayed = itemEvents.filter {
                it.stepIndex > original.shortTermStep && it.status == IntradayEventStatus.COMPLETED &&
                    it.completedAt == storedReviewedAt && it.feedback == original.feedback
            }.singleOrNull() ?: continue
            if (itemEvents.any { it.status == IntradayEventStatus.COMPLETED && it.stepIndex > replayed.stepIndex }) continue
            val key = Triple(original.itemKey, replayed.stepIndex, original.reviewedAtUtc)
            if (key in knownFeedbackSteps) continue
            val logs = database.reviewLogDao().getForWord(word.id)
                .filter {
                    it.mode == original.studyMode && it.reviewedAt == storedReviewedAt &&
                        it.feedback == original.feedback
                }.sortedBy { it.id }
            if (logs.size != 2 || logs.last().eventType != ReviewEventType.INTRADAY) continue
            val card = database.cardStateDao().get(word.id, original.studyMode) ?: continue
            if (card.lastReviewedAt != storedReviewedAt ||
                card.repetitions != original.baseCardVersion + 2
            ) continue
            val repaired = FeedbackAppliedV2(
                planKey = original.planKey,
                planVersion = original.planVersion,
                itemKey = original.itemKey,
                wordKey = original.wordKey,
                studyMode = original.studyMode,
                feedback = original.feedback,
                reviewedAtUtc = original.reviewedAtUtc,
                responseTimeMs = logs.last().responseTimeMs,
                shortTermStep = replayed.stepIndex,
                baseCardVersion = original.baseCardVersion + 1,
                previousReviewEventId = row.eventId,
                fsrsAlgorithmVersion = original.fsrsAlgorithmVersion,
                card = CardSnapshotV2(
                    status = card.state,
                    difficulty = card.difficulty,
                    stability = card.stability,
                    retrievability = card.retrievability,
                    dueAtUtc = card.dueAt,
                    scheduledDays = card.scheduledDays,
                    lapses = card.lapses,
                    learningStep = card.learningStep,
                    lastFeedbackEventId = null,
                ),
                itemStatus = item.status,
                shortTermEventStatus = IntradayEventStatus.COMPLETED,
            )
            appendChangeInCurrentTransaction(
                LocalChangeFactory.feedbackApplied(repaired),
                original.reviewedAtUtc.plusMillis(1),
            )
            knownFeedbackSteps += key
            recovered += 1
        }
        recovered
    }

    override suspend fun markUploaded(eventIds: Set<String>, revision: String) {
        if (eventIds.isEmpty()) return
        database.withTransaction {
            database.syncDao().markUploaded(eventIds.toList(), revision)
            database.syncDao().putMetadata(SyncMetadataEntity(REVISION_KEY, revision))
        }
    }

    private suspend fun appendEventInTransaction(
        kind: SyncEventKind,
        planKey: String?,
        wordKey: String?,
        payload: String,
        occurredAtUtc: Instant = Instant.now(),
        replacesEventId: String? = null,
    ): SyncEventRecord {
        val dao = database.syncDao()
        val current = dao.getDevice(deviceId) ?: SyncDeviceEntity(deviceId, 0).also { dao.insertDevice(it) }
        val sequence = current.nextSequence + 1
        dao.updateDevice(current.copy(nextSequence = sequence))
        val event = SyncEventRecord(
            eventId = UUID.randomUUID().toString(),
            deviceId = deviceId,
            sequence = sequence,
            kind = kind,
            occurredAtUtc = occurredAtUtc,
            planKey = planKey,
            wordKey = wordKey,
            payload = payload,
            replacesEventId = replacesEventId,
        )
        check(dao.insertEvent(event.toEntity(null, SyncApplyState.APPLIED, null)) != -1L) {
            "Failed to append sync event"
        }
        return event
    }

    private suspend fun applyRemoteEvent(event: SyncEventRecord) {
        val applier = remoteEventApplier ?: return
        val (state, error) = try {
            if (applier.apply(event)) {
                SyncApplyState.APPLIED to null
            } else {
                SyncApplyState.DEFERRED to "Remote event dependencies are not ready"
            }
        } catch (failure: Throwable) {
            SyncApplyState.DEFERRED to (failure.message ?: failure::class.simpleName ?: "Remote event application failed")
        }
        database.withTransaction {
            database.syncDao().updateApplyState(
                eventId = event.eventId,
                state = state,
                error = error,
                appliedAt = if (state == SyncApplyState.APPLIED) Instant.now() else null,
            )
        }
    }

    private fun SyncEventEntity.toModel() = SyncEventRecord(
        eventId = eventId,
        deviceId = deviceId,
        sequence = sequence,
        kind = kind,
        occurredAtUtc = occurredAtUtc,
        planKey = planKey,
        wordKey = wordKey,
        payload = payload,
        replacesEventId = replacesEventId,
    )

    private fun SyncEventRecord.toEntity(
        uploadedRevision: String?,
        applyState: SyncApplyState = SyncApplyState.RECEIVED,
        sourceFile: String? = null,
    ) = SyncEventEntity(
        eventId = eventId,
        deviceId = deviceId,
        sequence = sequence,
        kind = kind,
        occurredAtUtc = occurredAtUtc,
        planKey = planKey,
        wordKey = wordKey,
        payload = payload,
        replacesEventId = replacesEventId,
        uploadedRevision = uploadedRevision,
        applyState = applyState,
        sourceFile = sourceFile,
    )

    private companion object {
        const val REVISION_KEY = "sync.cursor.revision"
        const val FILES_KEY = "sync.cursor.files"
        const val CHECKPOINT_SOURCE = "checkpoint"
    }
}
