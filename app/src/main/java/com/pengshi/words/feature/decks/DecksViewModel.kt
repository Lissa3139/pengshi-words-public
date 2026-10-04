package com.pengshi.words.feature.decks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class DecksViewModel(initialDecks: List<DeckSummary> = listOf(DeckSummary("六级核心词汇", 5, 0))) {
    var decks by mutableStateOf(initialDecks)
        private set
}
