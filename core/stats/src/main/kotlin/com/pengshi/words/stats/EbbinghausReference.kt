package com.pengshi.words.stats

import kotlin.math.log10
import kotlin.math.pow

/**
 * Ebbinghaus' original savings formula from Memory (1885), chapter VII:
 * b = 100k / ((log10(t))^c + k), k = 1.84, c = 1.25.
 * Time is measured in minutes and clamped to one minute so the end of learning is 100%.
 */
object EbbinghausReference {
    fun retention(elapsedMinutes: Double): Double {
        val minutes = elapsedMinutes.coerceAtLeast(1.0)
        val k = 1.84
        val c = 1.25
        return (k / (log10(minutes).pow(c) + k)).coerceIn(0.0, 1.0)
    }
}
