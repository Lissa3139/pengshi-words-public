package com.pengshi.words.sync

/** Finds the current plan version from locally applied sync changes. */
object PlanSyncVersion {
    fun fromPayloads(payloads: List<String>): Int = payloads.asSequence()
        .mapNotNull { runCatching { SyncPayloadV2.decode(it) }.getOrNull() }
        .mapNotNull { payload ->
            when (payload) {
                is PlanLockedV2 -> payload.planVersion
                is PlanReconciledV2 -> payload.newPlanVersion
                else -> null
            }
        }
        .maxOrNull() ?: 1
}
