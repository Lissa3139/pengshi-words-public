package com.pengshi.words.stats

import com.pengshi.words.model.Feedback
import com.pengshi.words.model.ReviewLog
import com.pengshi.words.model.StudyMode
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

data class ForgettingCurveModel(
    val tauDays: Double,
    val beta: Double,
    val sampleCount: Int,
    val confidence: Double,
) {
    fun predict(elapsedDays: Double): Double = exp(-((elapsedDays.coerceAtLeast(0.0) / tauDays.coerceAtLeast(0.01)).pow(beta.coerceAtLeast(0.1))))
}

data class ForgettingCurveResult(
    val personalPoints: List<StatsPoint>,
    val referencePoints: List<StatsPoint>,
    val sampleCount: Int,
    val isPreliminary: Boolean,
)

object ForgettingCurveFitter {
    private data class Observation(val elapsedDays: Double, val target: Double)
    private data class FitKey(
        val id: Long,
        val wordId: Long,
        val mode: StudyMode,
        val reviewedAt: Instant,
        val feedback: Feedback,
    )
    private data class TimedFeedback(
        val id: Long,
        val wordId: Long,
        val mode: StudyMode,
        val reviewedAt: Instant,
        val feedback: Feedback,
    )
    private val anchors = ForgettingTimeScale.tickElapsedMinutes.zip(ForgettingTimeScale.tickLabels)
    private val statsCurveCacheLock = Any()
    private var cachedStatsCurveKey: List<FitKey>? = null
    private var cachedStatsCurve: ForgettingCurveResult? = null

    fun fit(logs: List<ReviewLog>): ForgettingCurveModel = fitEntries(logs.map {
        TimedFeedback(it.id, it.wordId, it.mode, it.reviewedAt, it.feedback)
    })

    fun fitStats(logs: List<StatsReviewLog>): ForgettingCurveModel = fitEntries(logs.map {
        TimedFeedback(it.id, it.wordId, it.mode, it.reviewedAt, it.feedback)
    })

    private fun fitEntries(logs: List<TimedFeedback>): ForgettingCurveModel {
        val grouped = logs.groupBy { it.wordId to it.mode }
        val sampleCount = grouped.size
        val observations = grouped.values.flatMap { entries ->
            val ordered = entries.sortedWith(compareBy<TimedFeedback> { it.reviewedAt }.thenBy { it.id })
            val adjacent = ordered.zipWithNext().mapNotNull { (previous, current) ->
                val elapsed = Duration.between(previous.reviewedAt, current.reviewedAt).toMinutes() / (24.0 * 60.0)
                if (elapsed <= 0.0) null else Observation(elapsed, target(current.feedback))
            }
            if (adjacent.isNotEmpty()) adjacent else ordered.lastOrNull()?.let { listOf(Observation(1.0, target(it.feedback))) }.orEmpty()
        }
        if (observations.isEmpty()) return ForgettingCurveModel(7.0, 1.0, sampleCount, confidence(sampleCount))
        var bestTau = 7.0
        var bestBeta = 1.0
        var bestLoss = Double.POSITIVE_INFINITY
        val taus = (1..72).map { 0.25 * 2.0.pow(it / 8.0) }.filter { it <= 365.0 }
        val betas = (4..24).map { it / 10.0 }
        for (tau in taus) for (beta in betas) {
            val loss = observations.sumOf { observation ->
                val prediction = exp(-((observation.elapsedDays / tau).pow(beta)))
                (prediction - observation.target).pow(2)
            }
            if (loss < bestLoss) { bestLoss = loss; bestTau = tau; bestBeta = beta }
        }
        return ForgettingCurveModel(bestTau, bestBeta, sampleCount, confidence(sampleCount))
    }

    fun curve(logs: List<ReviewLog>): ForgettingCurveResult {
        return curve(fit(logs))
    }

    fun curveStats(logs: List<StatsReviewLog>): ForgettingCurveResult {
        val key = logs.map { FitKey(it.id, it.wordId, it.mode, it.reviewedAt, it.feedback) }
        synchronized(statsCurveCacheLock) {
            if (key == cachedStatsCurveKey) cachedStatsCurve?.let { return it }
            val result = curve(fitStats(logs))
            cachedStatsCurveKey = key
            cachedStatsCurve = result
            return result
        }
    }

    private fun curve(model: ForgettingCurveModel): ForgettingCurveResult {
        var previous = 100.0
        val personal = anchors.mapIndexed { index, (minutes, label) ->
            val elapsedDays = minutes / (24.0 * 60.0)
            val value = if (index == 0) 100.0 else (model.predict(elapsedDays) * 100.0).coerceIn(0.0, 100.0).coerceAtMost(previous)
            previous = value
            StatsPoint(label, value.toFloat(), elapsedDays.toInt(), minutes)
        }
        val reference = anchors.map { (minutes, label) ->
            StatsPoint(
                label,
                (EbbinghausReference.retention(minutes) * 100.0).toFloat(),
                (minutes / (24.0 * 60.0)).toInt(),
                minutes,
            )
        }
        return ForgettingCurveResult(personal, reference, model.sampleCount, model.sampleCount < 30)
    }

    private fun target(feedback: Feedback): Double = when (feedback) {
        Feedback.AGAIN -> 0.0
        Feedback.HARD -> 0.35
        Feedback.GOOD -> 0.75
        Feedback.EASY -> 1.0
    }

    private fun confidence(samples: Int): Double = (samples / 30.0).coerceIn(0.0, 1.0)
}
