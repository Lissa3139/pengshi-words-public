package com.pengshi.words.sync

/**
 * Resolves two devices that independently locked different plans for the same day.
 * The newest lock wins when neither device has submitted feedback for that plan.
 * Event timestamps are part of the immutable event, so all clients choose the same winner.
 */
object PlanLockArbitration {
    private val feedbackKinds = setOf(
        SyncEventKind.FEEDBACK_RECORDED,
        SyncEventKind.FEEDBACK_REVISED,
        SyncEventKind.FEEDBACK_APPLIED_V2,
        SyncEventKind.FEEDBACK_REVISED_V2,
    )

    fun canonicalLock(events: List<SyncEventRecord>, planKey: String): SyncEventRecord? =
        events.asSequence()
            .filter { it.kind == SyncEventKind.PLAN_LOCKED_V2 }
            .mapNotNull { event ->
                val payload = runCatching { SyncPayloadV2.decode(event.payload) }.getOrNull() as? PlanLockedV2
                    ?: return@mapNotNull null
                event.takeIf { payload.planKey == planKey }
            }
            .maxWithOrNull(
                compareBy<SyncEventRecord>(SyncEventRecord::occurredAtUtc)
                    .thenBy { if (it.deviceId.startsWith("desktop-")) 1 else 0 }
                    .thenBy(SyncEventRecord::deviceId)
                    .thenBy(SyncEventRecord::sequence)
                    .thenBy(SyncEventRecord::eventId),
            )

    fun samePlan(left: PlanLockedV2, right: PlanLockedV2): Boolean =
        left.planKey == right.planKey &&
            left.studyMode == right.studyMode &&
            left.quota == right.quota &&
            left.items.sortedWith(compareBy(PlanItemV2::position, PlanItemV2::itemKey)) ==
                right.items.sortedWith(compareBy(PlanItemV2::position, PlanItemV2::itemKey))

    fun hasFeedbackFrom(events: List<SyncEventRecord>, planKey: String, deviceId: String): Boolean =
        events.any { event ->
            event.deviceId == deviceId && event.planKey == planKey && event.kind in feedbackKinds
        }

    fun hasFeedbackFromAnotherDevice(events: List<SyncEventRecord>, planKey: String, deviceId: String): Boolean =
        events.any { event ->
            event.deviceId != deviceId && event.planKey == planKey && event.kind in feedbackKinds
        }
}
