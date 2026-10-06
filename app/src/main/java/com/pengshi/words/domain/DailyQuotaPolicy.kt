package com.pengshi.words.domain

import com.pengshi.words.model.DailyItemStatus
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.DailyQuota
import com.pengshi.words.model.IntradayEventStatus
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.PlanSource

internal object DailyQuotaPolicy {
    const val MIN_QUOTA = DailyQuota.MIN
    const val DEFAULT_QUOTA = DailyQuota.DEFAULT
    const val MAX_QUOTA = DailyQuota.MAX

    fun isValid(quota: Int): Boolean = quota in MIN_QUOTA..MAX_QUOTA

    fun sanitize(quota: Int): Int = quota.coerceIn(MIN_QUOTA, MAX_QUOTA)

    fun removableAutomaticItemIds(
        items: List<DailyPlanItem>,
        eventsByItemId: Map<Long, List<IntradayReviewEvent>>,
        targetQuota: Int,
        currentItemId: Long?,
    ): Set<Long> {
        require(isValid(targetQuota)) { "每日额度必须在 1–100 之间" }
        val mainCount = items.count { it.source != PlanSource.EXTRA }
        val excess = (mainCount - targetQuota).coerceAtLeast(0)
        if (excess == 0) return emptySet()

        val removable = items.asSequence()
            .filter { item ->
                item.source in setOf(PlanSource.NEW, PlanSource.DUE_REVIEW) &&
                    item.status == DailyItemStatus.PENDING &&
                    item.id != currentItemId
            }
            .filter { item ->
                val events = eventsByItemId[item.id].orEmpty()
                events.isNotEmpty() && events.all { it.status == IntradayEventStatus.PENDING && it.feedback == null }
            }
            .toList()

        // Keep due reviews ahead of new words; when a smaller quota cannot fit
        // all reviews, remove the least-prioritized unseen reviews last.
        val newWordIds = removable.asSequence()
            .filter { it.source == PlanSource.NEW }
            .sortedByDescending(DailyPlanItem::selectionRank)
            .map(DailyPlanItem::id)
            .toList()
        val reviewIds = removable.asSequence()
            .filter { it.source == PlanSource.DUE_REVIEW }
            .sortedByDescending(DailyPlanItem::selectionRank)
            .map(DailyPlanItem::id)
            .toList()
        return (newWordIds + reviewIds).take(excess).toCollection(linkedSetOf())
    }
}
