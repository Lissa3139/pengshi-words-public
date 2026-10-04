package com.pengshi.words.speech

data class SpeechSettings(
    val autoPlayWord: Boolean = false,
    val autoPlaySentence: Boolean = false,
    val speechRate: Float = 1.0f,
) {
    init { require(speechRate in MIN_RATE..MAX_RATE) { "speechRate must be between $MIN_RATE and $MAX_RATE" } }

    companion object {
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f

        fun clampRate(rate: Float): Float =
            if (rate.isFinite()) rate.coerceIn(MIN_RATE, MAX_RATE) else DEFAULT_RATE

        private const val DEFAULT_RATE = 1.0f
    }
}
