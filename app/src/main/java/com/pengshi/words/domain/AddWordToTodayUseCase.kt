package com.pengshi.words.domain

import com.pengshi.words.model.DailyPlan
import com.pengshi.words.model.DailyPlanEntry
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.DailyPlanRepository
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.StudyMode
import java.time.Instant
import java.time.LocalDate

/** Adds one explicitly chosen dictionary word without changing the existing order. */
class AddWordToTodayUseCase(
    private val repository: DailyPlanRepository,
    private val renderer: StartDailyStudyUseCase,
) {
    suspend fun addWordToToday(
        localDate: LocalDate,
        now: Instant,
        mode: StudyMode,
        wordId: Long,
    ): StudySessionState {
        val word = requireNotNull(repository.getWord(wordId)) { "词条不存在" }
        var currentPlan = repository.getPlan(localDate)
        if (currentPlan == null) {
            renderer.startDailyStudy(localDate, now, mode)
            currentPlan = requireNotNull(repository.getPlan(localDate)) { "今日计划创建失败" }
        }

        val items = repository.getItems(currentPlan.id)
        if (items.any { it.wordId == word.id }) return renderer.render(currentPlan, mode)

        val rank = items.maxOfOrNull { it.selectionRank }?.plus(1) ?: 0
        val entry = DailyPlanEntry(
            // Extra words are deliberately outside the adjustable main quota.
            item = DailyPlanItem(0, currentPlan.id, word.id, PlanSource.EXTRA, rank),
            initialEvent = IntradayReviewEvent(0, 0, word.id, mode, 0, currentPlan.createdAt.plusSeconds(rank.toLong())),
        )
        val updatedPlan = currentPlan.copy(
            updatedAt = now,
        )
        return renderer.render(repository.appendToPlan(updatedPlan, listOf(entry)), mode)
    }
}
