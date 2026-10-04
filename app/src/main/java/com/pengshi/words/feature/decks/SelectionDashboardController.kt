package com.pengshi.words.feature.decks

import com.pengshi.words.model.WordPoolSelection
import com.pengshi.words.model.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SelectionDashboardController(
    private val scope: CoroutineScope,
    private val store: AndroidWordPoolStore,
    private val allDeckIds: Set<Long>,
    initialState: SelectionDashboardUiState,
) {
    private val _state = MutableStateFlow(initialState.withResolvedWeights(allDeckIds))
    val state: StateFlow<SelectionDashboardUiState> = _state.asStateFlow()

    fun setDeckSelected(deckId: Long, selected: Boolean) {
        val before = _state.value
        val currentIds = selectedDeckIds(before.wordPoolSelection)
        val nextIds = currentIds.toMutableSet().apply {
            if (selected) add(deckId) else remove(deckId)
        }
        val next = selectionState(before, before.wordPoolSelection.copy(includedDeckIds = encodeIncluded(nextIds)))
        persistOptimistically(before, next)
    }

    fun setDeckWeight(deckId: Long, percent: Int) {
        val before = _state.value
        val selectedIds = selectedDeckIds(before.wordPoolSelection)
        if (deckId !in selectedIds) return
        val target = percent.coerceIn(1, 100)
        val nextWeights = if (selectedIds.size == 1) {
            mapOf(deckId to 100)
        } else {
            val others = selectedIds - deckId
            val current = before.deckWeights.filterKeys { it in others }
            val baseTotal = current.values.sum().takeIf { it > 0 } ?: others.size
            val remaining = 100 - target
            val floors = others.associateWith { other -> (remaining.toLong() * (current[other] ?: 1) / baseTotal).toInt() }.toMutableMap()
            var remainder = remaining - floors.values.sum()
            others.sorted().forEach { other ->
                if (remainder > 0) {
                    floors[other] = floors.getValue(other) + 1
                    remainder--
                }
            }
            floors + (deckId to target)
        }
        val next = selectionState(before, before.wordPoolSelection.copy(deckWeights = nextWeights))
        persistOptimistically(before, next)
    }

    fun restoreAllDecks() {
        val before = _state.value
        val next = selectionState(before, WordPoolSelection())
        persistOptimistically(before, next)
    }

    private fun persistOptimistically(before: SelectionDashboardUiState, next: SelectionDashboardUiState) {
        _state.value = next.copy(isSaving = true, saveError = null)
        scope.launch {
            runCatching { store.save(next.wordPoolSelection) }
                .onSuccess { _state.value = _state.value.copy(isSaving = false, saveError = null) }
                .onFailure {
                    _state.value = before.copy(isSaving = false, saveError = it.message ?: "保存失败")
                }
        }
    }

    private fun selectionState(base: SelectionDashboardUiState, selection: WordPoolSelection): SelectionDashboardUiState =
        base.copy(
            wordPoolSelection = selection,
            deckWeights = selection.resolve(allDeckIds).deckWeights,
        )

    private fun selectedDeckIds(selection: WordPoolSelection): Set<Long> =
        if (selection.includedDeckIds.isEmpty()) allDeckIds else selection.includedDeckIds intersect allDeckIds

    private fun encodeIncluded(selected: Set<Long>): Set<Long> =
        if (selected == allDeckIds) emptySet() else selected
}

private fun SelectionDashboardUiState.withResolvedWeights(allDeckIds: Set<Long>): SelectionDashboardUiState =
    copy(deckWeights = wordPoolSelection.resolve(allDeckIds).deckWeights)
