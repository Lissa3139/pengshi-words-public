package com.pengshi.words.sync

import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.DailyQuota
import java.time.LocalDate

data class LockedPlan(
    val planKey: String,
    val itemKeys: List<String>,
    val sourceRevision: String,
) {
    init {
        require(itemKeys.size <= DailyQuota.MAX) { "A locked main plan cannot exceed ${DailyQuota.MAX} items" }
        require(itemKeys.distinct().size == itemKeys.size) { "A locked plan cannot contain duplicate items" }
    }
}

class PlanLockCoordinator(
    private val remote: SyncPlanStore,
) {
    suspend fun getOrLock(
        date: LocalDate,
        items: List<Pair<String, PlanSource>>,
        sourceRevision: String,
    ): LockedPlan {
        val planKey = SyncKeyFactory.planKey(date)
        remote.readLockedPlan(planKey)?.let { return it }
        val itemKeys = items
            .filter { it.second != PlanSource.EXTRA }
            .map { (wordKey, source) -> SyncKeyFactory.itemKey(planKey, wordKey, source) }
        return remote.createPlanIfAbsent(LockedPlan(planKey, itemKeys, sourceRevision))
    }
}
