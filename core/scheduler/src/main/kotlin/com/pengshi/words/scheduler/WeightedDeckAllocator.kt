package com.pengshi.words.scheduler

/** A ranked new-word candidate and every selected deck that contains it. */
data class WeightedCandidate(
    val wordId: Long,
    val deckIds: Set<Long>,
)

data class DeckAllocation(
    val wordIds: List<Long>,
    val assignedDeckByWordId: Map<Long, Long>,
)

/**
 * Deterministically assigns ranked candidates to weighted decks. A candidate
 * that belongs to more than one deck is counted once and is assigned to the
 * deck with the largest relative quota deficit. Short decks naturally refill
 * the remaining slots because no deck is treated as a hard cap.
 */
class WeightedDeckAllocator {
    fun allocate(
        slotCount: Int,
        deckWeights: Map<Long, Int>,
        rankedCandidates: List<WeightedCandidate>,
    ): DeckAllocation {
        val limit = slotCount.coerceAtLeast(0)
        if (limit == 0 || rankedCandidates.isEmpty()) return DeckAllocation(emptyList(), emptyMap())

        val weights = deckWeights.filterValues { it > 0 }.toSortedMap()
        if (weights.isEmpty()) {
            val ids = rankedCandidates.map { it.wordId }.distinct().take(limit)
            return DeckAllocation(ids, emptyMap())
        }

        val quotas = largestRemainder(limit, weights)
        val allocatedByDeck = weights.keys.associateWith { 0 }.toMutableMap()
        val candidates = mergeCandidates(rankedCandidates)
        // Preserve scarce, deck-specific candidates for their only possible
        // bucket before assigning flexible words shared across several decks.
        val constrainedFirst = candidates.withIndex()
            .sortedWith(
                compareBy<IndexedValue<WeightedCandidate>> {
                    it.value.deckIds.count(weights.keys::contains)
                }.thenBy { it.index },
            )
            .map { it.value }
        val candidatesByDeck = weights.keys.associateWith { deckId ->
            constrainedFirst.filter { deckId in it.deckIds }
        }
        val assigned = LinkedHashMap<Long, Long>(limit)

        while (assigned.size < limit) {
            val availableDeck = weights.keys.asSequence()
                .filter { deckId -> candidatesByDeck.getValue(deckId).any { it.wordId !in assigned } }
                .minWithOrNull(
                    compareBy<Long> { relativeLoad(allocatedByDeck.getValue(it), quotas.getValue(it)) }
                        .thenBy { it },
                ) ?: break
            val candidate = candidatesByDeck.getValue(availableDeck).firstOrNull { it.wordId !in assigned } ?: break
            assigned[candidate.wordId] = availableDeck
            allocatedByDeck[availableDeck] = allocatedByDeck.getValue(availableDeck) + 1
        }

        val orderedAssigned = candidates.asSequence()
            .filter { it.wordId in assigned }
            .associate { it.wordId to assigned.getValue(it.wordId) }
        return DeckAllocation(orderedAssigned.keys.toList(), orderedAssigned)
    }

    private fun mergeCandidates(rankedCandidates: List<WeightedCandidate>): List<WeightedCandidate> {
        val merged = LinkedHashMap<Long, LinkedHashSet<Long>>()
        rankedCandidates.forEach { candidate ->
            merged.getOrPut(candidate.wordId) { linkedSetOf() }.addAll(candidate.deckIds)
        }
        return merged.map { (wordId, deckIds) -> WeightedCandidate(wordId, deckIds) }
    }

    private fun relativeLoad(allocated: Int, quota: Int): Double =
        if (quota == 0) Double.POSITIVE_INFINITY else allocated.toDouble() / quota

    private fun largestRemainder(slotCount: Int, weights: Map<Long, Int>): Map<Long, Int> {
        val total = weights.values.sum().toLong()
        val scaledWeights = weights.mapValues { (_, weight) -> slotCount.toLong() * weight }
        val quotas = scaledWeights.mapValues { (_, scaledWeight) -> (scaledWeight / total).toInt() }.toMutableMap()
        var remainder = slotCount - quotas.values.sum()
        scaledWeights.keys
            .sortedWith(
                compareByDescending<Long> { scaledWeights.getValue(it) % total }
                    .thenBy { it },
            )
            .take(remainder)
            .forEach { deckId ->
                quotas[deckId] = quotas.getValue(deckId) + 1
            }
        return quotas
    }
}
