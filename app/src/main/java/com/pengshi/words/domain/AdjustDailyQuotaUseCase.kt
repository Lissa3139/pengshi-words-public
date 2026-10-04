package com.pengshi.words.domain

import com.pengshi.words.model.DailyPlanRepository
import com.pengshi.words.model.StudyMode
import java.time.Instant
import java.time.LocalDate

/** Applies a changed preference to today's plan while preserving every started or fixed item. */
class AdjustDailyQuotaUseCase(
    private val repository: DailyPlanRepository,
    private val renderer: StartDailyStudyUseCase,
) {
    suspend fun adjust(
        localDate: LocalDate,
        targetQuota: Int,
        now: Instant,
        mode: StudyMode,
    ): Boolean {
        require(DailyQuotaPolicy.isValid(targetQuota)) { "每日额度必须在 1–100 之间" }
        val plan = repository.getPlan(localDate) ?: return false
        if (plan.quota == targetQuota) return false

        val items = repository.getItems(plan.id)
        val eventsByItemId = repository.getEventsForItems(items.map { it.id }).groupBy { it.dailyPlanItemId }
        val currentItemId = renderer.render(plan, mode).currentItem?.id
        val removeItemIds = DailyQuotaPolicy.removableNewItemIds(
            items = items,
            eventsByItemId = eventsByItemId,
            targetQuota = targetQuota,
            currentItemId = currentItemId,
        )
        val updated = plan.copy(
            quota = targetQuota,
            plannedUniqueWordCount = items.count { it.source != com.pengshi.words.model.PlanSource.EXTRA } - removeItemIds.size,
            updatedAt = now,
        )
        repository.replaceUnseenNewItems(updated, removeItemIds, emptyList(), now)
        return true
    }
}
