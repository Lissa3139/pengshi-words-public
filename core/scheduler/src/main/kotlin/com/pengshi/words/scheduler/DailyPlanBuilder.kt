package com.pengshi.words.scheduler

import com.pengshi.words.model.DailyQuota

import com.pengshi.words.model.PlanSource

class DailyPlanBuilder {
    fun build(input: DailyPlanInput): PlannedDay {
        val limit = input.quota.coerceIn(0, MAX_UNIQUE_WORDS)
        if (limit == 0) return PlannedDay(emptyList(), emptyMap())

        val nextDayStart = input.localDate.plusDays(1).atStartOfDay(input.zoneId).toInstant()
        val due = input.candidates
            .asSequence()
            .filter { !it.isNew && it.dueAt != null && it.dueAt.isBefore(nextDayStart) }
            .sortedWith(SelectionPriority.dueComparator(input.localDate, input.zoneId))

        val newCandidates = input.candidates.filter { it.isNew }
        val selected = LinkedHashMap<Long, PlanSource>(limit)

        due.forEach { candidate ->
            if (selected.size < limit) selected.putIfAbsent(candidate.wordId, PlanSource.DUE_REVIEW)
        }
        val remainingNewSlots = (limit - selected.size).coerceAtLeast(0)
        val orderedNew = if (remainingNewSlots == 0) {
            emptyList()
        } else if (input.deckWeights.isEmpty() || input.deckIdsByWordId.isEmpty()) {
            // Unweighted/fallback selection only needs a bounded stable window.
            val selectionWindow = (remainingNewSlots * input.deckWeights.size.coerceAtLeast(1) * 4)
                .coerceAtLeast(remainingNewSlots)
            diversifiedNewCards(newCandidates, input.localDate, selectionWindow)
        } else {
            // Allocate over the full eligible pool before applying presentation
            // diversification. Truncating globally first can accidentally remove
            // every candidate from a selected deck and make its configured share
            // appear to have no effect.
            val rankedCandidates = newCandidates
                .distinctBy { it.wordId }
                .sortedBy { stableScore(it.wordId, input.localDate.toEpochDay()) }
            val allocation = WeightedDeckAllocator().allocate(
                slotCount = remainingNewSlots,
                deckWeights = input.deckWeights,
                rankedCandidates = rankedCandidates.map { candidate ->
                    WeightedCandidate(candidate.wordId, input.deckIdsByWordId[candidate.wordId].orEmpty())
                },
            )
            val allocatedIds = allocation.wordIds.toHashSet()
            val allocated = rankedCandidates.filter { it.wordId in allocatedIds }
            // Metadata can be incomplete for legacy/imported words. The input
            // candidates are already restricted to the saved eligible pool, so
            // use only those unmatched words to fill any slots left by allocation.
            val backfill = rankedCandidates.asSequence()
                .filter { it.wordId !in allocatedIds }
                .take((remainingNewSlots - allocated.size).coerceAtLeast(0))
                .toList()
            diversifiedNewCards(
                candidates = allocated + backfill,
                date = input.localDate,
                selectionWindow = remainingNewSlots,
            )
        }
        orderedNew.forEach { candidate ->
            if (selected.size < limit) selected.putIfAbsent(candidate.wordId, PlanSource.NEW)
        }

        return PlannedDay(
            wordIds = selected.keys.toList(),
            sourceByWordId = selected.toMap(),
        )
    }

    /** New cards use stable scoring plus recent-family/initial avoidance. */
    private fun diversifiedNewCards(
        candidates: List<PlanCandidate>,
        date: java.time.LocalDate,
        selectionWindow: Int,
    ): List<PlanCandidate> {
        val remaining = candidates
            .distinctBy { it.wordId }
            .sortedBy { stableScore(it.wordId, date.toEpochDay()) }
            .take(selectionWindow)
        val result = mutableListOf<PlanCandidate>()
        val buckets = remaining.groupBy { candidate ->
            candidate.familyKey ?: candidate.initialKey?.let { "initial:$it" } ?: "word:${candidate.wordId}"
        }.mapValues { (_, values) -> values.toMutableList() }.toMutableMap()
        while (buckets.isNotEmpty()) {
            val recentFamilies = result.takeLast(2).mapNotNull { it.familyKey }.toSet()
            val recentInitials = result.takeLast(2).mapNotNull { it.initialKey }.toSet()
            val orderedKeys = buckets.keys.sortedWith(
                compareBy<String> { buckets.getValue(it).first().frequencyRank ?: Int.MAX_VALUE }
                    .thenBy { stableScore(it.hashCode().toLong(), date.toEpochDay()) },
            )
            val preferredKey = orderedKeys.firstOrNull { key ->
                val candidate = buckets.getValue(key).first()
                candidate.familyKey !in recentFamilies && candidate.initialKey !in recentInitials
            } ?: orderedKeys.firstOrNull { key ->
                buckets.getValue(key).first().familyKey !in recentFamilies
            } ?: orderedKeys.first()
            val bucket = buckets.getValue(preferredKey)
            result += bucket.removeAt(0)
            if (bucket.isEmpty()) buckets.remove(preferredKey)
        }
        return result
    }

    private fun stableScore(wordId: Long, day: Long): Long {
        var value = wordId xor (day * -7046029254386353131L)
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    private companion object {
        const val MAX_UNIQUE_WORDS = DailyQuota.MAX
    }
}
