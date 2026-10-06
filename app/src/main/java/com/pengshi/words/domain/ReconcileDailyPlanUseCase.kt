package com.pengshi.words.domain

import com.pengshi.words.model.DailyItemStatus
import com.pengshi.words.model.AutomaticWordPool
import com.pengshi.words.model.DailyPlan
import com.pengshi.words.model.DailyPlanEntry
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.DailyPlanRepository
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.filterByWordPool
import com.pengshi.words.scheduler.DailyPlanInput
import com.pengshi.words.scheduler.PlanCandidate
import com.pengshi.words.scheduler.StudyScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reconciles the locked main task after synchronization has applied the latest
 * feedback. Only unseen automatic NEW items may be replaced; completed,
 * in-progress and EXTRA items are immutable for this day's task.
 */
class ReconcileDailyPlanUseCase(
    private val repository: DailyPlanRepository,
    private val scheduler: StudyScheduler,
    private val eligibleWordIdsProvider: suspend () -> Set<Long> = { emptySet() },
    private val automaticWordPoolProvider: suspend () -> AutomaticWordPool = {
        AutomaticWordPool(emptyMap(), emptyMap(), emptySet())
    },
    private val emptyEligibleWordSetMeansNoWords: Boolean = false,
) {
    data class ReconcileResult(
        val plan: DailyPlan,
        val replacedItemIds: Set<Long>,
        val addedReviewWordIds: List<Long>,
        val remainingDueReviewCount: Int,
    )

    suspend fun reconcile(localDate: LocalDate, mode: StudyMode, now: Instant): ReconcileResult {
        val plan = requireNotNull(repository.getPlan(localDate)) {
            "Cannot reconcile a day without a locked plan: $localDate"
        }
        val items = repository.getItems(plan.id)
        val mainItems = items.filter { it.source != PlanSource.EXTRA }
        val existingWordIds = items.map { it.wordId }.toSet()
        val pendingNewItems = mainItems.filter { item ->
            item.source == PlanSource.NEW && item.status == DailyItemStatus.PENDING
        }
        val eventsByItemId = repository.getEventsForItems(pendingNewItems.map { it.id }).groupBy { it.dailyPlanItemId }
        val unseenNewItems = pendingNewItems.filter { item ->
                eventsByItemId[item.id].orEmpty().all { event -> event.status == com.pengshi.words.model.IntradayEventStatus.PENDING }
        }
        val fixedMainCount = mainItems.size - unseenNewItems.size
        val replacementSlots = (plan.quota - fixedMainCount).coerceAtLeast(0)
        if (replacementSlots == 0 || unseenNewItems.isEmpty()) {
            return ReconcileResult(plan, emptySet(), emptyList(), 0)
        }

        val dueBefore = localDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val eligibleWordIds = eligibleWordIdsProvider()
        if (emptyEligibleWordSetMeansNoWords && eligibleWordIds.isEmpty()) {
            return ReconcileResult(plan, emptySet(), emptyList(), 0)
        }
        val dueCandidates = repository.getDueCandidatesBefore(mode, now, dueBefore)
            .filterByWordPool(eligibleWordIds, emptySet())
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
        if (dueCandidates.isEmpty()) {
            return ReconcileResult(plan, emptySet(), emptyList(), 0)
        }

        val selected = scheduler.buildDailyPlan(
            DailyPlanInput(
                localDate = localDate,
                now = now,
                quota = minOf(replacementSlots, unseenNewItems.size),
                mode = mode,
                candidates = dueCandidates,
            ),
        )
        val selectedReviewWordIds = selected.wordIds
            .filter { selected.sourceByWordId[it] == PlanSource.DUE_REVIEW }
            .take(unseenNewItems.size)
        if (selectedReviewWordIds.isEmpty()) {
            return ReconcileResult(plan, emptySet(), emptyList(), dueCandidates.size)
        }

        val removedItems = unseenNewItems
            .sortedWith(compareByDescending<DailyPlanItem> { it.selectionRank }.thenByDescending { it.id })
            .take(selectedReviewWordIds.size)
        val firstRank = items.maxOfOrNull { it.selectionRank }?.plus(1) ?: 0
        val entries = selectedReviewWordIds.mapIndexed { index, wordId ->
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
                    scheduledAt = now.plusSeconds((firstRank + index).toLong()),
                ),
            )
        }
        val updatedPlan = repository.replaceUnseenAutomaticItems(
            plan = plan.copy(
                plannedUniqueWordCount = fixedMainCount - removedItems.size + entries.size,
                status = com.pengshi.words.model.DailyPlanStatus.IN_PROGRESS,
                updatedAt = now,
            ),
            removeItemIds = removedItems.map { it.id }.toSet(),
            entries = entries,
            now = now,
        )
        return ReconcileResult(
            plan = updatedPlan,
            replacedItemIds = removedItems.map { it.id }.toSet(),
            addedReviewWordIds = selectedReviewWordIds,
            remainingDueReviewCount = (dueCandidates.size - selectedReviewWordIds.size).coerceAtLeast(0),
        )
    }
}
