package com.pengshi.words.feature.decks

import android.content.SharedPreferences
import com.pengshi.words.model.WordPoolSelection

interface AndroidWordPoolStore {
    fun load(): WordPoolSelection
    suspend fun save(selection: WordPoolSelection)
}

class SharedPreferencesWordPoolStore(
    private val preferences: SharedPreferences,
    private val deckKey: String = "automatic_word_pool_decks",
    private val excludedKey: String = "automatic_word_pool_excluded_words",
    private val weightsKey: String = "automatic_word_pool_weights",
) : AndroidWordPoolStore {
    override fun load(): WordPoolSelection = WordPoolSelection(
        includedDeckIds = preferences.getStringSet(deckKey, emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet(),
        excludedWordIds = preferences.getStringSet(excludedKey, emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet(),
        deckWeights = parseWeights(preferences.getStringSet(weightsKey, emptySet()).orEmpty()),
    )

    override suspend fun save(selection: WordPoolSelection) {
        preferences.edit()
            .putStringSet(deckKey, selection.includedDeckIds.map(Long::toString).toSet())
            .putStringSet(excludedKey, selection.excludedWordIds.map(Long::toString).toSet())
            .putStringSet(weightsKey, selection.deckWeights.entries.map { "${it.key}:${it.value}" }.toSet())
            .apply()
    }

    private fun parseWeights(values: Set<String>): Map<Long, Int> = values.mapNotNull { value ->
        val parts = value.split(':', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val deckId = parts[0].toLongOrNull() ?: return@mapNotNull null
        val weight = parts[1].toIntOrNull() ?: return@mapNotNull null
        deckId to weight
    }.toMap()
}
