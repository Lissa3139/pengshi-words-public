package com.pengshi.words.domain

import com.pengshi.words.model.AutomaticWordPool
import com.pengshi.words.model.DailyPlanEntry
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.DailyPlanRepository
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.StudyMode
import com.pengshi.words.scheduler.DailyPlanInput
import com.pengshi.words.scheduler.PlanCandidate
import com.pengshi.words.scheduler.StudyScheduler
import java.time.Instant
import java.time.LocalDate

data class AppendExtraWordsResult(
    val requested: Int,
    val appended: Int,
    val availableBeforeAppend: Int,
    val session: StudySessionState,
)

/** Appends EXTRA items without consuming the adjustable main daily quota. */
class AppendExtraWordsUseCase(
    private val repository: DailyPlanRepository,
    private val scheduler: StudyScheduler,
    private val renderer: StartDailyStudyUseCase,
    private val eligibleWordIdsProvider: suspend () -> Set<Long> = { emptySet() },
    private val automaticWordPoolProvider: suspend () -> AutomaticWordPool = {
        AutomaticWordPool(emptyMap(), emptyMap(), emptySet())
    },
    private val deckWordIdsProvider: suspend (Long) -> Set<Long> = { emptySet() },
) {
    suspend fun append(
        localDate: LocalDate,
        now: Instant,
        mode: StudyMode,
        requestedCount: Int,
        sourceDeckId: Long? = null,
    ): AppendExtraWordsResult {
        require(requestedCount > 0) { "额外单词数量必须是正整数" }
        val plan = requireNotNull(repository.getPlan(localDate)) { "今日计划尚未创建" }
        val items = repository.getItems(plan.id)
        val mainItems = items.filter { it.source != PlanSource.EXTRA }
        require(plan.status == com.pengshi.words.model.DailyPlanStatus.COMPLETED && mainItems.all { it.status == com.pengshi.words.model.DailyItemStatus.COMPLETED }) {
            "请先完成今日主任务"
        }
        val existingWordIds = items.mapTo(mutableSetOf()) { it.wordId }
        val automaticPool = if (sourceDeckId == null) automaticWordPoolProvider() else null
        val candidatesForSelection = if (sourceDeckId == null) {
            repository.getAutomaticNewWordCandidates(mode, now, requireNotNull(automaticPool), eligibleWordIdsProvider)
        } else {
            val deckWordIds = deckWordIdsProvider(sourceDeckId)
            if (deckWordIds.isEmpty()) emptyList() else repository.getNewCandidates(mode, now, deckWordIds)
        }
        val availableCandidates = candidatesForSelection
            .filter { it.isNew && it.wordId !in existingWordIds }
        val availableBeforeAppend = availableCandidates.size
        val selected = scheduler.buildDailyPlan(
            DailyPlanInput(
                localDate = localDate,
                now = now,
                quota = requestedCount.coerceAtMost(availableBeforeAppend),
                mode = mode,
                candidates = availableCandidates.map { candidate ->
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
                deckWeights = sourceDeckId?.let { mapOf(it to 100) } ?: requireNotNull(automaticPool).deckWeights,
                deckIdsByWordId = if (sourceDeckId == null) requireNotNull(automaticPool).deckIdsByWordId else {
                    availableCandidates.associate { it.wordId to setOf(sourceDeckId) }
                },
            ),
        )
        val firstRank = items.maxOfOrNull { it.selectionRank }?.plus(1) ?: 0
        val entries = selected.wordIds.mapIndexed { index, wordId ->
            val rank = firstRank + index
            DailyPlanEntry(
                item = DailyPlanItem(0, plan.id, wordId, PlanSource.EXTRA, rank),
                initialEvent = IntradayReviewEvent(0, 0, wordId, mode, 0, plan.createdAt.plusSeconds(rank.toLong())),
            )
        }
        val updatedPlan = plan.copy(updatedAt = now)
        val persisted = if (entries.isEmpty()) plan else repository.appendToPlan(updatedPlan, entries)
        return AppendExtraWordsResult(
            requested = requestedCount,
            appended = entries.size,
            availableBeforeAppend = availableBeforeAppend,
            session = renderer.render(persisted, mode),
        )
    }
}
