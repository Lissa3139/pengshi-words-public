package com.pengshi.words.sync

/**
 * A database-independent, redacted view of the state needed to detect a
 * cross-device learning fork before attempting recovery.
 */
data class SyncStateSnapshot(
    val completedPlanCounts: Map<String, Int> = emptyMap(),
    val feedbackLogCounts: Map<String, Int> = emptyMap(),
    val dueCardCounts: Map<String, Int> = emptyMap(),
    val planCompositions: Map<String, PlanComposition> = emptyMap(),
)

data class PlanComposition(
    val review: Int,
    val new: Int,
    val extra: Int,
)

data class PlanCompositionDifference(
    val review: Int,
    val new: Int,
    val extra: Int,
)

data class SyncStateDifference(
    val completedPlanCountDifferences: Map<String, Int>,
    val feedbackLogCountDifferences: Map<String, Int>,
    val dueCardCountDifferences: Map<String, Int>,
    val planCompositionDifferences: Map<String, PlanCompositionDifference>,
) {
    val hasDifferences: Boolean
        get() = completedPlanCountDifferences.isNotEmpty() ||
            feedbackLogCountDifferences.isNotEmpty() ||
            dueCardCountDifferences.isNotEmpty() ||
            planCompositionDifferences.isNotEmpty()
}

object SyncStateInspection {
    /**
     * Returns authoritative minus local values. A missing key is treated as
     * zero, making the result useful for redacted snapshots and partial dumps.
     */
    fun compare(local: SyncStateSnapshot, authoritative: SyncStateSnapshot): SyncStateDifference =
        SyncStateDifference(
            completedPlanCountDifferences = difference(local.completedPlanCounts, authoritative.completedPlanCounts),
            feedbackLogCountDifferences = difference(local.feedbackLogCounts, authoritative.feedbackLogCounts),
            dueCardCountDifferences = difference(local.dueCardCounts, authoritative.dueCardCounts),
            planCompositionDifferences = compositionDifference(local.planCompositions, authoritative.planCompositions),
        )

    private fun difference(local: Map<String, Int>, authoritative: Map<String, Int>): Map<String, Int> =
        (local.keys + authoritative.keys)
            .associateWith { key -> (authoritative[key] ?: 0) - (local[key] ?: 0) }
            .filterValues { it != 0 }

    private fun compositionDifference(
        local: Map<String, PlanComposition>,
        authoritative: Map<String, PlanComposition>,
    ): Map<String, PlanCompositionDifference> =
        (local.keys + authoritative.keys)
            .associateWith { key ->
                val localValue = local[key] ?: PlanComposition(review = 0, new = 0, extra = 0)
                val authoritativeValue = authoritative[key] ?: PlanComposition(review = 0, new = 0, extra = 0)
                PlanCompositionDifference(
                    review = authoritativeValue.review - localValue.review,
                    new = authoritativeValue.new - localValue.new,
                    extra = authoritativeValue.extra - localValue.extra,
                )
            }
            .filterValues { it.review != 0 || it.new != 0 || it.extra != 0 }
}
