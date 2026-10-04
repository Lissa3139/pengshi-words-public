package com.pengshi.words.domain

import com.pengshi.words.model.DailyPlan
import com.pengshi.words.model.DailyPlanEntry
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.DailyPlanRepository
import com.pengshi.words.model.DailyPlanCandidate
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.AutomaticWordPool
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.isNewWord
import com.pengshi.words.model.StudyMode
import com.pengshi.words.scheduler.DailyPlanInput
import com.pengshi.words.scheduler.PlanCandidate
import com.pengshi.words.scheduler.StudyScheduler
import com.pengshi.words.scheduler.SameDayMemoryProgress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StartDailyStudyUseCase(
    private val repository: DailyPlanRepository,
    private val scheduler: StudyScheduler,
    private val quotaProvider: suspend () -> Int = { DailyQuotaPolicy.DEFAULT_QUOTA },
    private val eligibleWordIdsProvider: suspend () -> Set<Long> = { emptySet() },
    private val automaticWordPoolProvider: suspend () -> AutomaticWordPool = {
        AutomaticWordPool(emptyMap(), emptyMap(), emptySet())
    },
    private val loadRelatedWordsImmediately: Boolean = true,
    private val emptyEligibleWordSetMeansNoWords: Boolean = false,
) {
    private val reconciler = ReconcileDailyPlanUseCase(
        repository,
        scheduler,
        eligibleWordIdsProvider,
        automaticWordPoolProvider,
        emptyEligibleWordSetMeansNoWords,
    )

    suspend fun startDailyStudy(localDate: LocalDate, now: Instant, mode: StudyMode, requestedQuota: Int? = null): StudySessionState {
        val existing = repository.getPlan(localDate)
        if (existing != null) return render(reconciler.reconcile(localDate, mode, now).plan, mode)

        val quota = DailyQuotaPolicy.sanitize(requestedQuota ?: quotaProvider())
        val dueBefore = localDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        // Lock the review set first. New words are chosen explicitly after review.
        val dueCandidates = repository.getDueCandidatesBefore(mode, now, dueBefore).filter { candidate ->
            !candidate.isNew && candidate.dueAt?.isBefore(dueBefore) == true
        }
        val planningCandidates = dueCandidates
        val selected = scheduler.buildDailyPlan(
            DailyPlanInput(
                localDate = localDate,
                now = now,
                quota = quota,
                mode = mode,
                candidates = planningCandidates.map {
                    PlanCandidate(
                        wordId = it.wordId,
                        isNew = it.isNew,
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
                deckWeights = emptyMap(),
                deckIdsByWordId = emptyMap(),
                zoneId = ZoneId.systemDefault(),
            ),
        )
        val plan = DailyPlan(
            localDate = localDate,
            quota = quota,
            plannedUniqueWordCount = selected.wordIds.size,
            createdAt = now,
            updatedAt = now,
        )
        val entries = selected.wordIds.mapIndexed { index, wordId ->
            val source = selected.sourceByWordId[wordId] ?: PlanSource.NEW
            DailyPlanEntry(
                item = DailyPlanItem(0, 0, wordId, source, index),
                // Logical queue slots let short-term confirmations re-enter between new cards.
                initialEvent = IntradayReviewEvent(0, 0, wordId, mode, 0, now.plusSeconds(index.toLong())),
            )
        }
        return render(repository.createPlan(plan, entries), mode)
    }

    internal suspend fun render(plan: DailyPlan, mode: StudyMode): StudySessionState {
        val items = repository.getItems(plan.id)
        val eventsByItemId = repository.getEventsForItems(items.map { it.id }).groupBy { it.dailyPlanItemId }
        val eventPairs = items.flatMap { item -> eventsByItemId[item.id].orEmpty().map { item to it } }
        val pending = eventPairs.filter { it.second.status == com.pengshi.words.model.IntradayEventStatus.PENDING }
            .sortedWith(
                compareBy<Pair<DailyPlanItem, IntradayReviewEvent>> {
                    when (it.first.source) {
                        PlanSource.DUE_REVIEW -> 0
                        PlanSource.NEW, PlanSource.MANUAL_NEW -> 1
                        PlanSource.EXTRA -> 2
                    }
                }
                    .thenBy { it.second.scheduledAt }
                    .thenBy { it.first.selectionRank }
                    .thenBy { it.second.stepIndex },
            )
        val current = pending.firstOrNull()
        val event = current?.second
        val item = current?.first
        val reviewItems = items.filter { it.source == PlanSource.DUE_REVIEW }
        val newItems = items.filter { it.source.isNewWord }
        val extraItems = items.filter { it.source == PlanSource.EXTRA }
        return StudySessionState(
            plan = plan,
            mode = event?.mode ?: mode,
            currentItem = item,
            currentEvent = event,
            currentCard = event?.let { repository.getCard(it.wordId, it.mode) },
            currentWord = event?.let { repository.getWord(it.wordId) },
            currentWordSenses = event?.let { repository.getWordSenses(it.wordId) }.orEmpty(),
            currentExamples = event?.let { repository.getExamples(it.wordId) }.orEmpty(),
            currentRelatedWords = if (loadRelatedWordsImmediately) {
                event?.let { repository.getRelatedWords(it.wordId) }.orEmpty()
            } else {
                emptyList()
            },
            pendingEventCount = pending.size,
            reviewPlannedCount = reviewItems.size,
            reviewCompletedCount = reviewItems.count { it.status == com.pengshi.words.model.DailyItemStatus.COMPLETED },
            newPlannedCount = newItems.size,
            newCompletedCount = newItems.count { it.status == com.pengshi.words.model.DailyItemStatus.COMPLETED },
            extraPlannedCount = extraItems.size,
            extraCompletedCount = extraItems.count { it.status == com.pengshi.words.model.DailyItemStatus.COMPLETED },
            currentMemoryProgress = current?.first?.let { currentItem ->
                SameDayMemoryProgress.fromCompletedEvents(
                    eventPairs.asSequence()
                        .filter { it.first.id == currentItem.id }
                        .map { it.second }
                        .asIterable(),
                )
            } ?: 0,
        )
    }

    /** Locks the remaining new-word set after the review phase has completed. */
    suspend fun fillRemainingNewWords(plan: DailyPlan, mode: StudyMode, now: Instant): StudySessionState {
        val items = repository.getItems(plan.id)
        val reviewStillPending = items.any { item ->
            item.source == PlanSource.DUE_REVIEW && item.status != com.pengshi.words.model.DailyItemStatus.COMPLETED
        }
        val plannedMainCount = items.count { it.source != PlanSource.EXTRA }
        val remaining = (plan.quota - plannedMainCount).coerceAtLeast(0)
        if (reviewStillPending || remaining == 0) return render(plan, mode)

        val existingWordIds = items.map { it.wordId }.toSet()
        val automaticPool = automaticWordPoolProvider()
        val candidates = repository.getAutomaticNewWordCandidates(mode, now, automaticPool, eligibleWordIdsProvider)
            .filter { it.wordId !in existingWordIds }
        val selected = scheduler.buildDailyPlan(
            DailyPlanInput(
                localDate = plan.localDate,
                now = now,
                quota = remaining,
                mode = mode,
                candidates = candidates.map { candidate ->
                    PlanCandidate(
                        wordId = candidate.wordId,
                        isNew = true,
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
                },
                deckWeights = automaticPool.deckWeights,
                deckIdsByWordId = automaticPool.deckIdsByWordId,
            ),
        )
        val firstRank = items.maxOfOrNull { it.selectionRank }?.plus(1) ?: 0
        val entries = selected.wordIds.mapIndexed { index, wordId ->
            DailyPlanEntry(
                item = DailyPlanItem(0, plan.id, wordId, PlanSource.NEW, firstRank + index),
                initialEvent = IntradayReviewEvent(
                    0, 0, wordId, mode, 0,
                    plan.createdAt.plusSeconds((firstRank + index).toLong()),
                ),
            )
        }
        if (entries.isEmpty()) return render(plan, mode)
        val updatedPlan = plan.copy(
            quota = plan.quota,
            plannedUniqueWordCount = plannedMainCount + entries.size,
            status = com.pengshi.words.model.DailyPlanStatus.IN_PROGRESS,
            updatedAt = now,
        )
        return render(repository.appendToPlan(updatedPlan, entries), mode)
    }
}

internal suspend fun DailyPlanRepository.getAutomaticNewWordCandidates(
    mode: StudyMode,
    now: Instant,
    automaticPool: AutomaticWordPool,
    eligibleWordIdsProvider: suspend () -> Set<Long>,
): List<DailyPlanCandidate> {
    val poolWordIds = automaticPool.eligibleWordIds
    val fallbackWordIds = if (automaticPool.restrictToEligibleWordIds || poolWordIds.isNotEmpty()) {
        emptySet()
    } else {
        eligibleWordIdsProvider()
    }
    val eligibleWordIds = automaticPool.resolveEligibleWordIds(fallbackWordIds)
    if (automaticPool.restrictToEligibleWordIds && eligibleWordIds.isEmpty()) return emptyList()
    return getNewCandidates(mode, now, eligibleWordIds).filter { candidate ->
        candidate.isNew &&
            (eligibleWordIds.isEmpty() || candidate.wordId in eligibleWordIds) &&
            candidate.wordId !in automaticPool.excludedWordIds
    }
}
