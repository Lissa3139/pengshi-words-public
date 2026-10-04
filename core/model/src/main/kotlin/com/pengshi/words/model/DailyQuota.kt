package com.pengshi.words.model

/** Shared bounds for the user's main daily study plan. EXTRA items are outside this quota. */
object DailyQuota {
    const val MIN = 1
    const val DEFAULT = 30
    const val MAX = 100

    fun normalize(value: Int): Int = value.coerceIn(MIN, MAX)

    fun requireValid(value: Int) {
        require(value in MIN..MAX) { "Daily quota must be between $MIN and $MAX" }
    }
}
