package com.pengshi.words.scheduler

import com.pengshi.words.model.CardState
import com.pengshi.words.model.CardStatus
import com.pengshi.words.model.Feedback
import java.time.Duration
import java.time.Instant
import java.util.Collections
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

interface FsrsEngine {
    val parameters: FsrsParameters

    fun schedule(card: CardState, feedback: Feedback, reviewedAt: Instant): CardState

    fun retrievability(card: CardState, at: Instant): Double
}

class FsrsParameters(
    val algorithmVersion: String,
    val desiredRetention: Double,
    weights: List<Double>,
    val maximumIntervalDays: Int,
) {
    val weights: List<Double> = Collections.unmodifiableList(weights.toList())

    init {
        require(desiredRetention in 0.0..1.0) { "desiredRetention must be between 0 and 1" }
        require(this.weights.size == 21) { "FSRS-6 requires exactly 21 parameters" }
        require(maximumIntervalDays > 0) { "maximumIntervalDays must be positive" }
    }

    companion object {
        val V6_DEFAULT = FsrsParameters(
            algorithmVersion = "FSRS-6.0-local-1",
            desiredRetention = 0.90,
            weights = listOf(
                0.212,
                1.2931,
                2.3065,
                8.2956,
                6.4133,
                0.8334,
                3.0194,
                0.001,
                1.8722,
                0.1666,
                0.796,
                1.4835,
                0.0614,
                0.2629,
                1.6483,
                0.6014,
                1.8729,
                0.5425,
                0.0912,
                0.0658,
                0.1542,
            ),
            maximumIntervalDays = 36_500,
        )
    }
}

class LocalFsrsEngine(
    override val parameters: FsrsParameters = FsrsParameters.V6_DEFAULT,
) : FsrsEngine {
    override fun schedule(card: CardState, feedback: Feedback, reviewedAt: Instant): CardState {
        val grade = feedback.grade
        val isUninitialized = card.status == CardStatus.NEW || card.stability <= 0.0 || card.difficulty <= 0.0
        val nextDifficulty: Double
        val nextStability: Double

        if (isUninitialized) {
            nextDifficulty = initialDifficulty(grade)
            nextStability = weights[grade - 1]
        } else {
            val elapsedDays = elapsedDays(card, reviewedAt)
            val currentRetrievability = forgettingCurve(elapsedDays, card.stability)
            nextDifficulty = nextDifficulty(card.difficulty, grade)
            nextStability = when {
                elapsedDays < 1.0 -> nextShortTermStability(card.stability, grade)
                feedback == Feedback.AGAIN -> nextForgetStability(
                    card.difficulty,
                    card.stability,
                    currentRetrievability,
                )
                else -> nextRecallStability(
                    card.difficulty,
                    card.stability,
                    currentRetrievability,
                    feedback,
                )
            }
        }

        val intervalDays = nextInterval(nextStability)
        return card.copy(
            status = nextStatus(card.status, feedback),
            difficulty = nextDifficulty.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY),
            stability = nextStability.coerceAtLeast(MIN_STABILITY),
            retrievability = 1.0,
            dueAt = reviewedAt.plus(Duration.ofDays(intervalDays.toLong())),
            lastReviewedAt = reviewedAt,
            scheduledDays = intervalDays,
            repetitions = card.repetitions + 1,
            lapses = card.lapses + if (feedback == Feedback.AGAIN) 1 else 0,
            updatedAt = reviewedAt,
        )
    }

    override fun retrievability(card: CardState, at: Instant): Double {
        if (card.lastReviewedAt == null || card.stability <= 0.0) return 0.0
        return forgettingCurve(elapsedDays(card, at), card.stability).coerceIn(0.0, 1.0)
    }

    private val weights: List<Double>
        get() = parameters.weights

    private fun elapsedDays(card: CardState, reviewedAt: Instant): Double {
        val lastReviewedAt = card.lastReviewedAt ?: return 0.0
        val elapsedMillis = Duration.between(lastReviewedAt, reviewedAt).toMillis().coerceAtLeast(0)
        return elapsedMillis.toDouble() / MILLIS_PER_DAY
    }

    private fun initialDifficulty(grade: Int): Double =
        (weights[4] - exp(weights[5] * (grade - 1)) + 1.0).coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)

    private fun nextDifficulty(difficulty: Double, grade: Int): Double {
        val delta = -weights[6] * (grade - 3)
        val damped = delta * (MAX_DIFFICULTY - difficulty) / (MAX_DIFFICULTY - MIN_DIFFICULTY)
        val next = difficulty + damped
        val meanReversionTarget = initialDifficulty(4)
        return (weights[7] * meanReversionTarget + (1.0 - weights[7]) * next)
            .coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }

    private fun nextShortTermStability(stability: Double, grade: Int): Double {
        var increase = exp(weights[17] * (grade - 3 + weights[18])) * stability.pow(-weights[19])
        if (grade >= 3) increase = increase.coerceAtLeast(1.0)
        return stability * increase
    }

    private fun nextRecallStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        feedback: Feedback,
    ): Double {
        val hardPenalty = if (feedback == Feedback.HARD) weights[15] else 1.0
        val easyBonus = if (feedback == Feedback.EASY) weights[16] else 1.0
        val increase = exp(weights[8]) *
            (11.0 - difficulty) *
            stability.pow(-weights[9]) *
            (exp((1.0 - retrievability) * weights[10]) - 1.0) *
            hardPenalty *
            easyBonus
        return stability * (1.0 + increase)
    }

    private fun nextForgetStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
    ): Double {
        val calculated = weights[11] *
            difficulty.pow(-weights[12]) *
            ((stability + 1.0).pow(weights[13]) - 1.0) *
            exp((1.0 - retrievability) * weights[14])
        val shortTermLimit = stability / exp(weights[17] * weights[18])
        return minOf(calculated, shortTermLimit).coerceAtLeast(MIN_STABILITY)
    }

    private fun forgettingCurve(elapsedDays: Double, stability: Double): Double {
        val decay = weights[20]
        val factor = 0.9.pow(-1.0 / decay) - 1.0
        return (1.0 + factor * elapsedDays.coerceAtLeast(0.0) / stability.coerceAtLeast(MIN_STABILITY))
            .pow(-decay)
    }

    private fun nextInterval(stability: Double): Int {
        val decay = weights[20]
        val factor = 0.9.pow(-1.0 / decay) - 1.0
        val rawDays = stability / factor *
            (parameters.desiredRetention.pow(-1.0 / decay) - 1.0)
        return rawDays.roundToInt().coerceIn(1, parameters.maximumIntervalDays)
    }

    private fun nextStatus(status: CardStatus, feedback: Feedback): CardStatus = when {
        status == CardStatus.NEW -> CardStatus.LEARNING
        feedback == Feedback.AGAIN && status == CardStatus.REVIEW -> CardStatus.RELEARNING
        feedback == Feedback.AGAIN -> status
        status == CardStatus.LEARNING || status == CardStatus.RELEARNING -> CardStatus.REVIEW
        else -> CardStatus.REVIEW
    }

    private val Feedback.grade: Int
        get() = when (this) {
            Feedback.AGAIN -> 1
            Feedback.HARD -> 2
            Feedback.GOOD -> 3
            Feedback.EASY -> 4
        }

    private companion object {
        const val MIN_DIFFICULTY = 1.0
        const val MAX_DIFFICULTY = 10.0
        const val MIN_STABILITY = 0.01
        const val MILLIS_PER_DAY = 86_400_000.0
    }
}
