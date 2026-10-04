package com.pengshi.words.model

/**
 * The decks used by automatic new-word selection. An empty deck set means all
 * available decks, which keeps existing installations backwards compatible.
 */
data class WordPoolSelection(
    val includedDeckIds: Set<Long> = emptySet(),
    val excludedWordIds: Set<Long> = emptySet(),
    val deckWeights: Map<Long, Int> = emptyMap(),
) {
    fun includesDeck(deckId: Long): Boolean = includedDeckIds.isEmpty() || deckId in includedDeckIds

    fun excludesWord(wordId: Long): Boolean = wordId in excludedWordIds
}

/** Editable, non-persisted selection state used by both Android and desktop dashboards. */
data class WordPoolDraft(
    val allDeckIds: Set<Long>,
    val selectedDeckIds: Set<Long>,
    val excludedWordIds: Set<Long>,
    val weightInputs: Map<Long, String>,
    private val savedSelection: WordPoolSelection,
) {
    val parsedWeights: Map<Long, Int>?
        get() {
            if (selectedDeckIds.isEmpty()) return null
            val parsed = selectedDeckIds.associateWith { deckId ->
                weightInputs[deckId]?.trim()?.toIntOrNull() ?: return null
            }
            return parsed.takeIf { values -> values.values.all { it in 1..100 } }
        }

    val totalPercent: Int get() = parsedWeights?.values?.sum() ?: 0
    val canSave: Boolean get() = parsedWeights != null && totalPercent == 100
    val validationMessage: String?
        get() = when {
            selectedDeckIds.isEmpty() -> "请至少选择一个词库"
            parsedWeights == null -> "每个比例都必须是 1 到 100 的整数"
            totalPercent != 100 -> "当前合计 $totalPercent%，请调整为 100%"
            else -> null
        }

    val hasUnsavedChanges: Boolean
        get() = toSelectionOrNull()?.let { candidate ->
            candidate.includedDeckIds != savedSelection.includedDeckIds ||
                candidate.excludedWordIds != savedSelection.excludedWordIds ||
                candidate.resolve(allDeckIds).deckWeights != savedSelection.resolve(allDeckIds).deckWeights
        } ?: true

    fun withWeightInput(deckId: Long, value: String): WordPoolDraft {
        if (deckId !in selectedDeckIds) return this
        return copy(weightInputs = weightInputs + (deckId to value.filter(Char::isDigit).take(3)))
    }

    fun toggleDeck(deckId: Long): WordPoolDraft {
        if (deckId !in allDeckIds) return this
        val nextIds = selectedDeckIds.toMutableSet().apply {
            if (!add(deckId) && size > 1) remove(deckId)
        }.toSet()
        return copy(
            selectedDeckIds = nextIds,
            weightInputs = balancedWeights(nextIds).mapValues { it.value.toString() },
        )
    }

    fun selectAllDecks(): WordPoolDraft = copy(
        selectedDeckIds = allDeckIds,
        weightInputs = balancedWeights(allDeckIds).mapValues { it.value.toString() },
    )

    fun toSelectionOrNull(): WordPoolSelection? {
        val weights = parsedWeights?.takeIf { totalPercent == 100 } ?: return null
        return WordPoolSelection(
            includedDeckIds = if (selectedDeckIds == allDeckIds) emptySet() else selectedDeckIds,
            excludedWordIds = excludedWordIds,
            deckWeights = weights,
        )
    }

    companion object {
        fun from(selection: WordPoolSelection, allDeckIds: Set<Long>): WordPoolDraft {
            val resolved = selection.resolve(allDeckIds)
            return WordPoolDraft(
                allDeckIds = allDeckIds,
                selectedDeckIds = resolved.includedDeckIds,
                excludedWordIds = resolved.excludedWordIds,
                weightInputs = resolved.deckWeights.mapValues { it.value.toString() },
                savedSelection = selection.copy(deckWeights = resolved.deckWeights),
            )
        }

        private fun balancedWeights(deckIds: Set<Long>): Map<Long, Int> {
            if (deckIds.isEmpty()) return emptyMap()
            val sorted = deckIds.sorted()
            val base = 100 / sorted.size
            var remainder = 100 - base * sorted.size
            return sorted.associateWith {
                base + if (remainder-- > 0) 1 else 0
            }
        }
    }
}

data class ResolvedWordPoolSelection(
    val deckWeights: Map<Long, Int>,
    val excludedWordIds: Set<Long>,
) {
    val includedDeckIds: Set<Long> get() = deckWeights.keys
}

data class AutomaticWordPool(
    val deckWeights: Map<Long, Int>,
    val deckIdsByWordId: Map<Long, Set<Long>>,
    val excludedWordIds: Set<Long>,
    /** Distinguishes an intentionally empty selected pool from legacy unrestricted callers. */
    val restrictToEligibleWordIds: Boolean = false,
) {
    val eligibleWordIds: Set<Long>
        get() = deckIdsByWordId.asSequence()
            .filter { (wordId, deckIds) -> wordId !in excludedWordIds && deckIds.any(deckWeights::containsKey) }
            .map { it.key }
            .toSet()

    fun resolveEligibleWordIds(fallback: Set<Long>): Set<Long> = when {
        restrictToEligibleWordIds -> eligibleWordIds
        eligibleWordIds.isNotEmpty() -> eligibleWordIds
        else -> fallback
    }
}

/**
 * Resolves persisted selection settings against the decks that currently exist.
 * Rounding is deterministic: integer floors are assigned first, then the
 * remaining percentage points go to the lowest deck ids.
 */
fun WordPoolSelection.resolve(allDeckIds: Set<Long>): ResolvedWordPoolSelection {
    val selectedDeckIds = (if (includedDeckIds.isEmpty()) allDeckIds else includedDeckIds intersect allDeckIds).toSortedSet()
    if (selectedDeckIds.isEmpty()) return ResolvedWordPoolSelection(emptyMap(), excludedWordIds)

    val positiveWeights = deckWeights
        .filterKeys { it in selectedDeckIds }
        .filterValues { it > 0 }
    val source = if (positiveWeights.isEmpty()) {
        selectedDeckIds.associateWith { 1 }
    } else {
        positiveWeights
    }
    val total = source.values.sumOf { it.toLong() }
    val floors = source.mapValues { (_, weight) -> (weight.toLong() * 100L / total).toInt() }.toMutableMap()
    var remainder = 100 - floors.values.sum()
    source.keys.sorted().forEach { deckId ->
        if (remainder > 0) {
            floors[deckId] = floors.getValue(deckId) + 1
            remainder--
        }
    }
    return ResolvedWordPoolSelection(floors.filterValues { it > 0 }, excludedWordIds)
}

fun List<DailyPlanCandidate>.filterByWordPool(
    includedWordIds: Set<Long>,
    excludedWordIds: Set<Long>,
): List<DailyPlanCandidate> {
    if (includedWordIds.isEmpty() && excludedWordIds.isEmpty()) return this
    return filter { candidate ->
        (includedWordIds.isEmpty() || candidate.wordId in includedWordIds) &&
            candidate.wordId !in excludedWordIds
    }
}
