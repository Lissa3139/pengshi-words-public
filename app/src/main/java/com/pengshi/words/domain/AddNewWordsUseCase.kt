package com.pengshi.words.domain

import com.pengshi.words.model.DailyPlanEntry
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.DailyPlanRepository
import com.pengshi.words.model.DailyPlanStatus
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.AutomaticWordPool
import com.pengshi.words.scheduler.DailyPlanInput
import com.pengshi.words.scheduler.PlanCandidate
import com.pengshi.words.scheduler.StudyScheduler
import java.time.Instant
import java.time.LocalDate

class AddNewWordsUseCase(
    private val repository: DailyPlanRepository,
    private val scheduler: StudyScheduler,
    private val renderer: StartDailyStudyUseCase,
    private val eligibleWordIdsProvider: suspend () -> Set<Long> = { emptySet() },
    private val automaticWordPoolProvider: suspend () -> AutomaticWordPool = {
        AutomaticWordPool(emptyMap(), emptyMap(), emptySet())
    },
) {
    /** Adds exact user-selected NEW cards in the order selected, within today's remaining quota. */
    suspend fun addSelectedNewWords(
        localDate: LocalDate,
        now: Instant,
        mode: StudyMode,
        wordIds: List<Long>,
    ): StudySessionState {
        val plan = repository.getPlan(localDate) ?: renderer.startDailyStudy(localDate, now, mode)
            .plan
        val items = repository.getItems(plan.id)
        if (items.any { it.source == PlanSource.DUE_REVIEW && it.status != com.pengshi.words.model.DailyItemStatus.COMPLETED }) {
            return renderer.render(plan, mode)
        }
        val remaining = (plan.quota - items.count { it.source != PlanSource.EXTRA }).coerceAtLeast(0)
        if (remaining == 0 || wordIds.isEmpty()) return renderer.render(plan, mode)
        val existingIds = items.mapTo(hashSetOf()) { it.wordId }
        val eligibleIds = repository.getNewCandidates(mode, now).mapTo(hashSetOf()) { it.wordId }
        val selectedIds = wordIds.distinct().filter { it in eligibleIds && it !in existingIds }.take(remaining)
        if (selectedIds.isEmpty()) return renderer.render(plan, mode)
        val firstRank = items.maxOfOrNull { it.selectionRank }?.plus(1) ?: 0
        val entries = selectedIds.mapIndexed { index, wordId ->
            DailyPlanEntry(
                item = DailyPlanItem(0, plan.id, wordId, PlanSource.MANUAL_NEW, firstRank + index),
                initialEvent = IntradayReviewEvent(0, 0, wordId, mode, 0, now.plusSeconds(index.toLong())),
            )
        }
        val updatedPlan = plan.copy(
            plannedUniqueWordCount = items.count { it.source != PlanSource.EXTRA } + entries.size,
            status = DailyPlanStatus.IN_PROGRESS,
            updatedAt = now,
        )
        return renderer.render(repository.appendToPlan(updatedPlan, entries), mode)
    }

    suspend fun addNewWords(localDate: LocalDate, now: Instant, mode: StudyMode, count: Int): StudySessionState {
        val plan = repository.getPlan(localDate) ?: return renderer.startDailyStudy(localDate, now, mode)
        val items = repository.getItems(plan.id)
        if (items.any { it.source == PlanSource.DUE_REVIEW && it.status != com.pengshi.words.model.DailyItemStatus.COMPLETED }) {
            return renderer.render(plan, mode)
        }
        val plannedMainCount = items.count { it.source != PlanSource.EXTRA }
        val remaining = (plan.quota - plannedMainCount).coerceAtLeast(0)
        if (remaining == 0) return renderer.render(plan, mode)
        val existingWordIds = items.map { it.wordId }.toSet()
        val automaticPool = automaticWordPoolProvider()
        val candidates = repository.getAutomaticNewWordCandidates(mode, now, automaticPool, eligibleWordIdsProvider)
            .filter { it.wordId !in existingWordIds }
        val selected = scheduler.buildDailyPlan(
            DailyPlanInput(
                localDate = localDate,
                now = now,
                quota = count.coerceIn(1, remaining),
                mode = mode,
                candidates = candidates.map {
                    PlanCandidate(
                        wordId = it.wordId,
                        isNew = true,
                        dueAt = it.dueAt,
                        retrievability = it.retrievability,
                        overdueSeconds = it.overdueSeconds,
                        familyKey = it.familyKey,
                        initialKey = it.initialKey,
                        difficulty = it.difficulty,
                        lapses = it.lapses,
                        lastReviewedAt = it.lastReviewedAt,
                        intervalDays = it.intervalDays,
                        frequencyRank = it.frequencyRank,
                    )
                },
                deckWeights = automaticPool.deckWeights,
                deckIdsByWordId = automaticPool.deckIdsByWordId,
            ),
        )
        val firstRank = items.maxOfOrNull { it.selectionRank }?.plus(1) ?: 0
        val entries = selected.wordIds.mapIndexed { index, wordId ->
            DailyPlanEntry(
                item = DailyPlanItem(0, plan.id, wordId, PlanSource.NEW, firstRank + index),
                initialEvent = IntradayReviewEvent(0, 0, wordId, mode, 0, plan.createdAt.plusSeconds((firstRank + index).toLong())),
            )
        }
        if (entries.isEmpty()) return renderer.render(plan, mode)
        val updatedPlan = plan.copy(
            quota = plan.quota,
            plannedUniqueWordCount = plannedMainCount + entries.size,
            status = DailyPlanStatus.IN_PROGRESS,
            updatedAt = now,
        )
        return renderer.render(repository.appendToPlan(updatedPlan, entries), mode)
    }
}
