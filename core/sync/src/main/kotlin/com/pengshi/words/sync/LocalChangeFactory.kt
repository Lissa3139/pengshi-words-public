package com.pengshi.words.sync

import com.pengshi.words.model.Feedback
import com.pengshi.words.model.DailyPlan
import com.pengshi.words.model.DailyPlanEntry
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.StudyMode
import java.time.Instant

data class LocalChangeDraft(
    val kind: SyncEventKind,
    val planKey: String,
    val wordKey: String?,
    val payload: String,
    val replacesEventId: String? = null,
)

object LocalChangeFactory {
    fun planLocked(
        plan: DailyPlan,
        entries: List<DailyPlanEntry>,
        mode: StudyMode,
        deviceId: String,
        wordKeyForWordId: (Long) -> String,
        selectionAlgorithmVersion: String = "frequency-fsrs-v2",
        poolDigest: String = "unknown",
    ): LocalChangeDraft {
        val planKey = SyncKeyFactory.planKey(plan.localDate)
        val items = entries.mapIndexed { index, entry ->
            val wordKey = wordKeyForWordId(entry.item.wordId)
            PlanItemV2(
                itemKey = SyncKeyFactory.itemKey(planKey, wordKey, entry.item.source),
                wordKey = wordKey,
                source = entry.item.source,
                position = entry.item.selectionRank.takeIf { it >= 0 } ?: index,
            )
        }
        val payload = PlanLockedV2(
            planKey = planKey,
            localDate = plan.localDate,
            studyMode = mode,
            quota = plan.quota,
            planVersion = 1,
            createdAtUtc = plan.createdAt,
            createdByDeviceId = deviceId,
            items = items,
            selectionAlgorithmVersion = selectionAlgorithmVersion,
            poolDigest = poolDigest,
        )
        return LocalChangeDraft(SyncEventKind.PLAN_LOCKED_V2, planKey, null, SyncPayloadV2.encode(payload))
    }

    fun planReconciled(
        plan: DailyPlan,
        retainedEntries: List<DailyPlanEntry>,
        addedEntries: List<DailyPlanEntry>,
        mode: StudyMode,
        wordKeyForWordId: (Long) -> String,
        oldPlanVersion: Int = 1,
        newPlanVersion: Int = 2,
        removedItemKeys: List<String> = emptyList(),
        reason: String = "deferred-new-word-fill",
    ): LocalChangeDraft {
        val planKey = SyncKeyFactory.planKey(plan.localDate)
        fun item(entry: DailyPlanEntry, index: Int): PlanItemV2 {
            val wordKey = wordKeyForWordId(entry.item.wordId)
            return PlanItemV2(
                itemKey = SyncKeyFactory.itemKey(planKey, wordKey, entry.item.source),
                wordKey = wordKey,
                source = entry.item.source,
                position = entry.item.selectionRank.takeIf { it >= 0 } ?: index,
            )
        }
        val payload = PlanReconciledV2(
            planKey = planKey,
            localDate = plan.localDate,
            studyMode = mode,
            oldPlanVersion = oldPlanVersion,
            newPlanVersion = newPlanVersion,
            retainedItemKeys = retainedEntries.mapIndexed { index, entry -> item(entry, index).itemKey },
            removedItemKeys = removedItemKeys,
            addedItems = addedEntries.mapIndexed { index, entry -> item(entry, index) },
            reason = reason,
            quota = plan.quota,
        )
        return LocalChangeDraft(SyncEventKind.PLAN_RECONCILED_V2, planKey, null, SyncPayloadV2.encode(payload))
    }

    fun feedbackApplied(payload: FeedbackAppliedV2): LocalChangeDraft = LocalChangeDraft(
        kind = SyncEventKind.FEEDBACK_APPLIED_V2,
        planKey = payload.planKey,
        wordKey = payload.wordKey,
        payload = SyncPayloadV2.encode(payload),
    )

    fun feedbackRevised(payload: FeedbackRevisedV2): LocalChangeDraft = LocalChangeDraft(
        kind = SyncEventKind.FEEDBACK_REVISED_V2,
        planKey = payload.planKey,
        wordKey = payload.wordKey,
        payload = SyncPayloadV2.encode(payload),
        replacesEventId = payload.replacesEventId,
    )

    fun feedbackRecorded(
        planKey: String,
        wordKey: String,
        mode: StudyMode,
        feedback: Feedback,
        reviewedAt: Instant,
        responseTimeMs: Long,
    ): LocalChangeDraft = feedbackDraft(
        kind = SyncEventKind.FEEDBACK_RECORDED,
        planKey = planKey,
        wordKey = wordKey,
        mode = mode,
        feedback = feedback,
        reviewedAt = reviewedAt,
        responseTimeMs = responseTimeMs,
    )

    fun feedbackRevised(
        planKey: String,
        wordKey: String,
        mode: StudyMode,
        feedback: Feedback,
        reviewedAt: Instant,
        responseTimeMs: Long,
        replacesEventId: String,
    ): LocalChangeDraft = feedbackDraft(
        kind = SyncEventKind.FEEDBACK_REVISED,
        planKey = planKey,
        wordKey = wordKey,
        mode = mode,
        feedback = feedback,
        reviewedAt = reviewedAt,
        responseTimeMs = responseTimeMs,
        replacesEventId = replacesEventId,
    )

    private fun feedbackDraft(
        kind: SyncEventKind,
        planKey: String,
        wordKey: String,
        mode: StudyMode,
        feedback: Feedback,
        reviewedAt: Instant,
        responseTimeMs: Long,
        replacesEventId: String? = null,
    ) = LocalChangeDraft(
        kind = kind,
        planKey = planKey,
        wordKey = wordKey,
        payload = "v1|mode=${mode.name}|feedback=${feedback.name}|reviewedAt=${reviewedAt}|responseTimeMs=$responseTimeMs",
        replacesEventId = replacesEventId,
    )
}
