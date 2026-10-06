package com.pengshi.words.domain

import com.pengshi.words.model.DailyPlanEntry
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.DailyPlanRepository
import com.pengshi.words.model.IntradayEventStatus
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.StudyMode
import com.pengshi.words.scheduler.DailyPlanInput
import com.pengshi.words.scheduler.PlanCandidate
import com.pengshi.words.scheduler.StudyScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Applies a changed preference to today's plan while preserving every started or fixed item. */
class AdjustDailyQuotaUseCase(
    private val repository: DailyPlanRepository,
    private val renderer: StartDailyStudyUseCase,
    private val scheduler: StudyScheduler,
) {
    suspend fun adjust(
        localDate: LocalDate,
        targetQuota: Int,
        now: Instant,
        mode: StudyMode,
    ): Boolean {
        require(DailyQuotaPolicy.isValid(targetQuota)) { "每日额度必须在 1–100 之间" }
        val plan = repository.getPlan(localDate) ?: return false
        val items = repository.getItems(plan.id)
        val mainItemCount = items.count { it.source != com.pengshi.words.model.PlanSource.EXTRA }
        val eventsByItemId = repository.getEventsForItems(items.map { it.id }).groupBy { it.dailyPlanItemId }
        val currentItemId = renderer.render(plan, mode).currentItem?.id
        val removeItemIds = DailyQuotaPolicy.removableAutomaticItemIds(
            items = items,
            eventsByItemId = eventsByItemId,
            targetQuota = targetQuota,
            currentItemId = currentItemId,
        )
        val retainedMainCount = mainItemCount - removeItemIds.size
        val hasReviewPlan = items.any { it.source == PlanSource.DUE_REVIEW }
        val addedReviewEntries = if (hasReviewPlan) {
            addDueReviewsToFillQuota(
                plan = plan,
                items = items,
                eventsByItemId = eventsByItemId,
                removedItemIds = removeItemIds,
                availableSlots = (targetQuota - retainedMainCount).coerceAtLeast(0),
                localDate = localDate,
                now = now,
                mode = mode,
            )
        } else {
            emptyList()
        }
        if (plan.quota == targetQuota && removeItemIds.isEmpty() && addedReviewEntries.isEmpty()) return false
        val updated = plan.copy(
            quota = targetQuota,
            plannedUniqueWordCount = retainedMainCount + addedReviewEntries.size,
            status = if (addedReviewEntries.isEmpty()) plan.status else com.pengshi.words.model.DailyPlanStatus.IN_PROGRESS,
            updatedAt = now,
        )
        repository.replaceUnseenAutomaticItems(
            plan = updated,
            removeItemIds = removeItemIds,
            entries = addedReviewEntries,
            now = now,
            reason = "daily-quota-adjustment",
        )
        return true
    }

    private suspend fun addDueReviewsToFillQuota(
        plan: com.pengshi.words.model.DailyPlan,
        items: List<DailyPlanItem>,
        eventsByItemId: Map<Long, List<IntradayReviewEvent>>,
        removedItemIds: Set<Long>,
        availableSlots: Int,
        localDate: LocalDate,
        now: Instant,
        mode: StudyMode,
    ): List<DailyPlanEntry> {
        if (availableSlots == 0) return emptyList()
        val dueBefore = localDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val existingWordIds = items.mapTo(hashSetOf()) { it.wordId }
        val candidates = repository.getDueCandidatesBefore(mode, now, dueBefore)
            .asSequence()
            .filter { !it.isNew && it.dueAt?.isBefore(dueBefore) == true }
            .filter { it.wordId !in existingWordIds }
            .map { candidate ->
                PlanCandidate(
                    wordId = candidate.wordId,
                    isNew = false,
                    dueAt = candidate.dueAt,
                    retrievability = candidate.retrievability,
                    overdueSeconds = candidate.overdueSeconds,
                    familyKey = candidate.familyKey,
                    initialKey = candidate.initialKey,
                    difficulty = candidate.difficulty,
                    lapses = candidate.lapses,
                    lastReviewedAt = candidate.lastReviewedAt,
                    intervalDays = candidate.intervalDays,
                    frequencyRank = candidate.frequencyRank,
                )
            }
            .toList()
        if (candidates.isEmpty()) return emptyList()

        val selected = scheduler.buildDailyPlan(
            DailyPlanInput(
                localDate = localDate,
                now = now,
                quota = availableSlots,
                mode = mode,
                candidates = candidates,
                zoneId = ZoneId.systemDefault(),
            ),
        )
        val selectedWordIds = selected.wordIds
            .filter { selected.sourceByWordId[it] == PlanSource.DUE_REVIEW }
            .take(availableSlots)
        if (selectedWordIds.isEmpty()) return emptyList()

        val firstRank = items.maxOfOrNull(DailyPlanItem::selectionRank)?.plus(1) ?: 0
        val latestPendingReviewEvent = items.asSequence()
            .filter { it.source == PlanSource.DUE_REVIEW && it.id !in removedItemIds }
            .flatMap { eventsByItemId[it.id].orEmpty().asSequence() }
            .filter { it.status == IntradayEventStatus.PENDING }
            .maxOfOrNull(IntradayReviewEvent::scheduledAt)
        val firstScheduledAt = latestPendingReviewEvent
            ?.plusSeconds(1)
            ?.let { maxOf(now, it) }
            ?: now

        return selectedWordIds.mapIndexed { index, wordId ->
            DailyPlanEntry(
                item = DailyPlanItem(
                    dailyPlanId = plan.id,
                    wordId = wordId,
                    source = PlanSource.DUE_REVIEW,
                    selectionRank = firstRank + index,
                ),
                initialEvent = IntradayReviewEvent(
                    dailyPlanItemId = 0,
                    wordId = wordId,
                    mode = mode,
                    stepIndex = 0,
                    scheduledAt = firstScheduledAt.plusSeconds(index.toLong()),
                ),
            )
        }
    }
}
