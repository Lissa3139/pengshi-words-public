package com.pengshi.words.sync

import com.pengshi.words.model.PlanSource

object PlanLockCompatibility {
    /** A downloaded older lock must not remove new words already studied locally. */
    fun canKeepLocalSuperset(
        remoteKeys: Set<String>,
        localKeys: Set<String>,
        unmatchedLocalSources: List<PlanSource>,
    ): Boolean = localKeys.containsAll(remoteKeys) &&
        unmatchedLocalSources.isNotEmpty() && unmatchedLocalSources.all {
            it == PlanSource.NEW || it == PlanSource.MANUAL_NEW
        }
}
