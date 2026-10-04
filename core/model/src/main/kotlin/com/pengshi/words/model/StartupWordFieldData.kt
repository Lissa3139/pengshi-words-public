package com.pengshi.words.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Persisted word identities used to render the startup word field. */
data class StartupWordFieldData(
    val wordIds: List<Long> = emptyList(),
    val completedFeedbackWordIds: Set<Long> = emptySet(),
) {
    fun layout(widthPx: Float, heightPx: Float): List<StartupWordPoint> {
        if (widthPx <= 0f || heightPx <= 0f) return emptyList()
        val centerX = widthPx / 2f
        val centerY = heightPx / 2f
        val cloudRadius = min(widthPx, heightPx) * .48f

        return wordIds.map { wordId ->
            val angle = unit(wordId, ANGLE_SALT) * (2.0 * PI)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            val horizontalLimit = when {
                dx > 0f -> (widthPx - 8f - centerX) / dx
                dx < 0f -> (centerX - 8f) / -dx
                else -> Float.POSITIVE_INFINITY
            }
            val verticalLimit = when {
                dy > 0f -> (heightPx - 8f - centerY) / dy
                dy < 0f -> (centerY - 8f) / -dy
                else -> Float.POSITIVE_INFINITY
            }
            val edgeRadius = min(horizontalLimit, verticalLimit).coerceAtLeast(0f)
            val localLimit = min(cloudRadius, edgeRadius)
            val radius = if (unit(wordId, OUTER_SALT) < OUTER_FIELD_FRACTION && edgeRadius > localLimit) {
                localLimit + (edgeRadius - localLimit) * unit(wordId, OUTER_RADIUS_SALT).pow(1.35).toFloat()
            } else {
                localLimit * unit(wordId, CLOUD_RADIUS_SALT).pow(1.7).toFloat()
            }

            StartupWordPoint(
                wordId = wordId,
                xPx = centerX + dx * radius,
                yPx = centerY + dy * radius,
                hasCompletedFeedback = wordId in completedFeedbackWordIds,
                motionRadiusPx = min(widthPx, heightPx) * (.008f + .024f * unit(wordId, MOTION_RADIUS_SALT).toFloat()),
                motionPhase = (unit(wordId, MOTION_PHASE_SALT) * 2.0 * PI).toFloat(),
                motionPeriodSeconds = MOTION_PERIODS[(unit(wordId, MOTION_PERIOD_SALT) * MOTION_PERIODS.size).toInt().coerceAtMost(MOTION_PERIODS.lastIndex)],
                motionDirection = if (unit(wordId, MOTION_DIRECTION_SALT) < .5) 1f else -1f,
            )
        }
    }

    companion object {
        val Empty = StartupWordFieldData()

        fun fromSnapshot(snapshot: StudyDataSnapshot): StartupWordFieldData {
            val activeDeckIds = snapshot.decks.filterNot { it.isDeletedUserDeck() }.mapTo(hashSetOf()) { it.id }
            val linkedWordIds = snapshot.deckWords.filter { it.deckId in activeDeckIds }.mapTo(hashSetOf()) { it.wordId }
            val wordIds = snapshot.words.map { it.id }.filter { it in linkedWordIds }
            val wordIdSet = wordIds.toHashSet()
            val feedbackWordIds = snapshot.reviewLogs.asSequence()
                .map { it.wordId }
                .filter { it in wordIdSet }
                .toSet()
            return StartupWordFieldData(wordIds, feedbackWordIds)
        }

        private const val OUTER_FIELD_FRACTION = .09
        private const val ANGLE_SALT = 0x5f3759dfL
        private const val OUTER_SALT = 0x2d358dccL
        private const val OUTER_RADIUS_SALT = 0x6c8e9cf5L
        private const val CLOUD_RADIUS_SALT = 0x1b873593L
        private const val MOTION_RADIUS_SALT = 0x53ad8f41L
        private const val MOTION_PHASE_SALT = 0x29b9cb81L
        private const val MOTION_PERIOD_SALT = 0x3fa4a769L
        private const val MOTION_DIRECTION_SALT = 0x6c29bc15L
        private val MOTION_PERIODS = floatArrayOf(8f, 10f, 12f, 15f, 20f)

        private fun unit(wordId: Long, salt: Long): Double {
            var value = wordId xor salt
            value = (value xor (value ushr 30)) * -4658895280553007687L
            value = (value xor (value ushr 27)) * -7723592293110705685L
            value = value xor (value ushr 31)
            return (value ushr 11).toDouble() / 9_007_199_254_740_992.0
        }
    }
}

data class StartupWordPoint(
    val wordId: Long,
    val xPx: Float,
    val yPx: Float,
    val hasCompletedFeedback: Boolean,
    val motionRadiusPx: Float,
    val motionPhase: Float,
    val motionPeriodSeconds: Float,
    val motionDirection: Float,
) {
    /** Every word keeps its own small orbit; its learning state changes only the light. */
    fun positionAt(seconds: Float): Pair<Float, Float> {
        val angle = motionPhase + motionDirection * (2.0 * PI * seconds / motionPeriodSeconds).toFloat()
        return (xPx + cos(angle).toFloat() * motionRadiusPx) to
            (yPx + sin(angle).toFloat() * motionRadiusPx * .72f)
    }
}
