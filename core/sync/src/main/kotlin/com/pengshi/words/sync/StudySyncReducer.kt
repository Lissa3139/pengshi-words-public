package com.pengshi.words.sync

data class StudySyncState(
    val plans: Map<String, PlanLockedV2> = emptyMap(),
    val cardSnapshots: Map<String, CardSnapshotV2> = emptyMap(),
    val feedbackPayloads: Map<String, FeedbackPayloadV2> = emptyMap(),
    val appliedEventIds: Set<String> = emptySet(),
)

data class ReductionResult(
    val state: StudySyncState,
    val applied: List<String> = emptyList(),
    val deferred: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    val rejected: List<String> = emptyList(),
    val changedPlans: Set<String> = emptySet(),
    val changedCards: Set<String> = emptySet(),
)

private data class DecodedEvent(
    val record: SyncEventRecord,
    val payload: SyncPayloadV2Model,
)

object StudySyncReducer {
    /** Orders mixed V1/V2 bundles so both clients apply V2 dependencies first. */
    fun orderEvents(events: List<SyncEventRecord>): List<SyncEventRecord> = events.sortedWith(
        compareBy<SyncEventRecord>({ eventPhase(it) }, { it.occurredAtUtc }, { it.eventId }),
    )

    fun reduce(
        events: List<SyncEventRecord>,
        currentState: StudySyncState = StudySyncState(),
    ): ReductionResult {
        val decoded = mutableListOf<DecodedEvent>()
        val rejected = mutableListOf<String>()
        val seenInput = mutableSetOf<String>()
        for (event in events) {
            if (!seenInput.add(event.eventId) || event.eventId in currentState.appliedEventIds) continue
            try {
                decoded += DecodedEvent(event, SyncPayloadV2.decode(event.payload))
            } catch (_: UnsupportedSyncPayloadVersion) {
                rejected += event.eventId
            } catch (_: InvalidSyncPayload) {
                rejected += event.eventId
            }
        }

        val ordered = decoded.sortedWith(
            compareBy<DecodedEvent>({ phase(it.payload) }, { it.record.occurredAtUtc }, { it.record.deviceId }, { it.record.sequence }),
        )
        val plans = currentState.plans.toMutableMap()
        val cards = currentState.cardSnapshots.toMutableMap()
        val feedbacks = currentState.feedbackPayloads.toMutableMap()
        val appliedIds = currentState.appliedEventIds.toMutableSet()
        val applied = mutableListOf<String>()
        val deferred = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val changedPlans = mutableSetOf<String>()
        val changedCards = mutableSetOf<String>()

        for (decodedEvent in ordered) {
            val event = decodedEvent.record
            when (val payload = decodedEvent.payload) {
                is PlanLockedV2 -> {
                    val existing = plans[payload.planKey]
                    when {
                        existing == null -> {
                            plans[payload.planKey] = payload
                            appliedIds += event.eventId
                            applied += event.eventId
                            changedPlans += payload.planKey
                        }
                        existing == payload -> appliedIds += event.eventId
                        else -> conflicts += event.eventId
                    }
                }
                is PlanReconciledV2 -> {
                    val existing = plans[payload.planKey]
                    when {
                        existing == null || existing.planVersion != payload.oldPlanVersion -> deferred += event.eventId
                        payload.newPlanVersion <= payload.oldPlanVersion -> conflicts += event.eventId
                        else -> {
                            val retained = existing.items.filter {
                                it.itemKey in payload.retainedItemKeys && it.itemKey !in payload.removedItemKeys
                            }
                            val added = payload.addedItems.sortedBy(PlanItemV2::position)
                            plans[payload.planKey] = existing.copy(
                                planVersion = payload.newPlanVersion,
                                quota = payload.quota ?: existing.quota,
                                items = (retained + added).sortedBy(PlanItemV2::position),
                            )
                            appliedIds += event.eventId
                            applied += event.eventId
                            changedPlans += payload.planKey
                        }
                    }
                }
                is FeedbackAppliedV2 -> applyFeedback(
                    event = event,
                    payload = payload,
                    plans = plans,
                    cards = cards,
                    feedbacks = feedbacks,
                    appliedIds = appliedIds,
                    applied = applied,
                    deferred = deferred,
                    conflicts = conflicts,
                    changedPlans = changedPlans,
                    changedCards = changedCards,
                    isRevision = false,
                )
                is FeedbackRevisedV2 -> {
                    val replaced = feedbacks[payload.replacesEventId]
                    if (replaced == null) {
                        deferred += event.eventId
                    } else {
                        feedbacks.remove(payload.replacesEventId)
                        appliedIds.remove(payload.replacesEventId)
                        applied.remove(payload.replacesEventId)
                        applyFeedback(
                            event = event,
                            payload = payload,
                            plans = plans,
                            cards = cards,
                            feedbacks = feedbacks,
                            appliedIds = appliedIds,
                            applied = applied,
                            deferred = deferred,
                            conflicts = conflicts,
                            changedPlans = changedPlans,
                            changedCards = changedCards,
                            isRevision = true,
                        )
                    }
                }
            }
        }

        return ReductionResult(
            state = StudySyncState(plans, cards, feedbacks, appliedIds),
            applied = applied,
            deferred = deferred,
            conflicts = conflicts,
            rejected = rejected,
            changedPlans = changedPlans,
            changedCards = changedCards,
        )
    }

    private fun applyFeedback(
        event: SyncEventRecord,
        payload: FeedbackPayloadV2,
        plans: MutableMap<String, PlanLockedV2>,
        cards: MutableMap<String, CardSnapshotV2>,
        feedbacks: MutableMap<String, FeedbackPayloadV2>,
        appliedIds: MutableSet<String>,
        applied: MutableList<String>,
        deferred: MutableList<String>,
        conflicts: MutableList<String>,
        changedPlans: MutableSet<String>,
        changedCards: MutableSet<String>,
        isRevision: Boolean,
    ) {
        val plan = plans[payload.planKey]
        when {
            plan == null || plan.planVersion < payload.planVersion -> {
                deferred += event.eventId
                return
            }
            plan.items.none { it.itemKey == payload.itemKey && it.wordKey == payload.wordKey } -> {
                deferred += event.eventId
                return
            }
        }

        val cardKey = "${payload.studyMode.name}|${payload.wordKey}"
        val baseKey = "$cardKey|${payload.baseCardVersion}"
        val existingEntry = feedbacks.entries.firstOrNull { (_, existing) ->
            "${existing.studyMode.name}|${existing.wordKey}|${existing.baseCardVersion}" == baseKey
        }
        if (existingEntry != null && !isRevision) {
            val existing = existingEntry.value
            val candidateWins = compareFeedback(payload, event.eventId, existing, existingEntry.key) < 0
            if (!candidateWins) {
                conflicts += event.eventId
                return
            }
            feedbacks.remove(existingEntry.key)
            appliedIds.remove(existingEntry.key)
            applied.remove(existingEntry.key)
            conflicts += existingEntry.key
        }

        feedbacks[event.eventId] = payload
        appliedIds += event.eventId
        applied += event.eventId
        cards[cardKey] = payload.card
        changedCards += payload.wordKey
        changedPlans += payload.planKey
    }

    private fun compareFeedback(
        left: FeedbackPayloadV2,
        leftEventId: String,
        right: FeedbackPayloadV2,
        rightEventId: String,
    ): Int {
        val timeComparison = left.reviewedAtUtc.compareTo(right.reviewedAtUtc)
        return if (timeComparison != 0) timeComparison else leftEventId.compareTo(rightEventId)
    }

    private fun phase(payload: SyncPayloadV2Model): Int = when (payload) {
        is PlanLockedV2 -> 0
        is PlanReconciledV2 -> 1
        is FeedbackAppliedV2 -> 2
        is FeedbackRevisedV2 -> 3
    }

    private fun eventPhase(event: SyncEventRecord): Int = runCatching {
        phase(SyncPayloadV2.decode(event.payload))
    }.getOrDefault(4)

}
