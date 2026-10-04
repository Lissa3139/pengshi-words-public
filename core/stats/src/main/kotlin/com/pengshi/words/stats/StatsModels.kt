package com.pengshi.words.stats

import com.pengshi.words.model.CardState
import com.pengshi.words.model.DailyPlan
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.Feedback
import com.pengshi.words.model.ReviewLog
import com.pengshi.words.model.StudyDataSnapshot
import com.pengshi.words.model.StudyMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.pow

enum class StatsTab(val title: String) { FORGETTING("遗忘曲线"), LEARNING("学习情况"), RETENTION("记忆持久度") }
enum class StatsPeriod(val title: String) { DAY("日"), WEEK("周"), MONTH("月") }

data class StatsPoint(
    val label: String,
    val value: Float,
    val xDay: Int = 0,
    val elapsedMinutes: Double = xDay * 24.0 * 60.0,
)

object ForgettingTimeScale {
    const val DISPLAY_WIDTH_RATIO = 1.0
    private const val MINUTES_PER_DAY = 24.0 * 60.0
    private val tickDays = (0..13).toList() + listOf(30, 60, 90, 120, 150, 180, 365)
    val tickElapsedMinutes: List<Double> = tickDays.map { it * MINUTES_PER_DAY }
    val tickLabels: List<String> = listOf(
        "今天", "明天", "后天", "3天后", "4天后", "5天后", "6天后", "7天后",
        "8天后", "9天后", "10天后", "11天后", "12天后", "13天后",
        "1个月后", "2个月后", "3个月后", "4个月后", "5个月后", "6个月后", "1年后",
    )
    /** Compact two-line labels keep every milestone on the same baseline on phone and desktop. */
    val tickAxisLabels: List<String> = listOf(
        "今\n天", "明\n天", "后\n天", "3天\n后", "4天\n后", "5天\n后", "6天\n后",
        "7天\n后", "8天\n后", "9天\n后", "10\n天后", "11\n天后", "12\n天后", "13\n天后",
        "1月\n后", "2月\n后", "3月\n后", "4月\n后", "5月\n后", "6月\n后", "1年\n后",
    )
    private val boundaries = tickElapsedMinutes.toDoubleArray()

    /** Maps actual elapsed times between named milestones onto equally spaced tick positions. */
    fun position(elapsedMinutes: Double, maxElapsedMinutes: Double): Double {
        val maximum = maxElapsedMinutes.coerceAtLeast(boundaries.last())
        val elapsed = elapsedMinutes.coerceIn(0.0, maximum)
        if (elapsed <= boundaries.first()) return 0.0
        val tick = boundaries.indexOfLast { it <= elapsed }.coerceAtLeast(0)
        if (tick >= boundaries.lastIndex) return DISPLAY_WIDTH_RATIO
        val start = boundaries[tick]
        val end = boundaries[tick + 1]
        val local = ((elapsed - start) / (end - start)).coerceIn(0.0, 1.0)
        return ((tick + local) / boundaries.lastIndex) * DISPLAY_WIDTH_RATIO
    }
}

data class LearningBar(
    val label: String,
    val familiar: Int,
    val remembered: Int,
    val forgotten: Int,
    val learning: Int,
    val planned: Int,
    val isToday: Boolean,
    val studyTimeMs: Long = 0L,
)

data class RetentionSeries(
    val thresholdDays: Int?,
    val label: String,
    val values: List<Float?>,
    val total: Int,
    val percent: Float,
)

/** Minimal review-history row needed by the statistics UI and fitted forgetting model. */
data class StatsReviewLog(
    val id: Long,
    val wordId: Long,
    val mode: StudyMode,
    val reviewedAt: Instant,
    val feedback: Feedback,
    val responseTimeMs: Long,
    val nextStateSnapshot: String,
)

/** Targeted statistics input; unrelated dictionary, example, deck and event tables are excluded. */
data class StatsStudyData(
    val totalWordCount: Int,
    val cardStates: List<CardState>,
    val dailyPlans: List<DailyPlan>,
    val dailyPlanItems: List<DailyPlanItem>,
    val reviewLogs: List<StatsReviewLog>,
)

data class StatsResult(
    val totalWords: Int = 0,
    val newWords: Int = 0,
    val reviewCount: Int = 0,
    val rememberedCount: Int = 0,
    val completedDays: Int = 0,
    val estimatedRecallPercent: Int = 0,
    val chartLabels: List<String> = emptyList(),
    val chartDates: List<LocalDate> = emptyList(),
    val forgettingCurve: List<StatsPoint> = emptyList(),
    val referenceForgettingCurve: List<StatsPoint> = emptyList(),
    val personalForgettingCurve: List<StatsPoint> = emptyList(),
    val referencePersonalForgettingCurve: List<StatsPoint> = emptyList(),
    val forgettingSampleCount: Int = 0,
    val forgettingPreliminary: Boolean = true,
    val learningBars: List<LearningBar> = emptyList(),
    val retentionSeries: List<RetentionSeries> = emptyList(),
    val anchorDate: LocalDate = LocalDate.now(),
    val statsStartDate: LocalDate = LocalDate.now(),
    val period: StatsPeriod = StatsPeriod.DAY,
)

object StatsCalculator {
    private val zone = ZoneId.systemDefault()
    fun calculate(
        snapshot: StudyDataSnapshot,
        anchorDate: LocalDate = LocalDate.now(),
        statsStartDate: LocalDate = anchorDate,
        period: StatsPeriod = StatsPeriod.DAY,
    ): StatsResult = calculate(
        StatsStudyData(
            totalWordCount = snapshot.words.size,
            cardStates = snapshot.cardStates,
            dailyPlans = snapshot.dailyPlans,
            dailyPlanItems = snapshot.dailyPlanItems,
            reviewLogs = snapshot.reviewLogs.map { it.toStatsReviewLog() },
        ),
        anchorDate,
        statsStartDate,
        period,
    )

    fun calculate(
        snapshot: StatsStudyData,
        anchorDate: LocalDate = LocalDate.now(),
        statsStartDate: LocalDate = anchorDate,
        period: StatsPeriod = StatsPeriod.DAY,
    ): StatsResult {
        val availableThrough = LocalDate.now(zone)
        val effectiveAnchor = anchorDate.coerceAtMost(availableThrough).coerceAtLeast(statsStartDate)
        val dates = chartDates(effectiveAnchor, statsStartDate, period)
        val labels = dates.map { labelFor(it, effectiveAnchor, period) }
        val allLogs = snapshot.reviewLogs
        val logs = allLogs.filter { localDate(it) >= statsStartDate }
        val logsByDate = logs.groupBy(::localDate)
        val logsByCard = allLogs.groupBy { it.wordId to it.mode }
        val cards = snapshot.cardStates.filter { (it.wordId to it.mode) in logsByCard }
            .groupBy { it.wordId }.values.mapNotNull { it.maxByOrNull(CardState::stability) }
        val plansByDate = snapshot.dailyPlans.associateBy { it.localDate }
        val planItemsById = snapshot.dailyPlanItems.groupBy { it.dailyPlanId }
        val fittedCurve = ForgettingCurveFitter.curveStats(allLogs)
        val forgetting = fittedCurve.personalPoints
        val reference = fittedCurve.referencePoints
        val bars = dates.mapIndexed { index, date ->
            learningBar(logsByDate, plansByDate, planItemsById, labels[index], date, effectiveAnchor, availableThrough, statsStartDate, period)
        }
        val currentEnd = periodEnd(periodStart(effectiveAnchor, period), period).coerceAtMost(effectiveAnchor)
        val retentionDates = (dates.filterNot { it.isAfter(availableThrough) }
            .map { periodEnd(it, period).coerceAtMost(availableThrough) } + currentEnd).distinct()
        val stabilitiesByDate = retentionDates.associateWith { date ->
            cards.mapNotNull { card ->
                val log = logsByCard[card.wordId to card.mode].orEmpty().asSequence()
                    .filter { localDate(it).let { reviewed -> reviewed >= statsStartDate && !reviewed.isAfter(date) } }
                    .maxWithOrNull(compareBy({ it.reviewedAt }, { it.id })) ?: return@mapNotNull null
                stabilityFromSnapshot(log.nextStateSnapshot).takeIf { it > 0.0 }
                    ?: if (date == effectiveAnchor) card.stability else 0.0
            }
        }
        val currentLearned = stabilitiesByDate[currentEnd].orEmpty().size
        val retention = buildList {
            addRetention(null, "已完成记忆反馈的全部单词", dates, availableThrough, period, currentLearned, currentEnd, stabilitiesByDate)
            listOf(10, 30, 60, 90).forEach { threshold ->
                addRetention(threshold, "记忆持久度≥${threshold}天的词汇量", dates, availableThrough, period, currentLearned, currentEnd, stabilitiesByDate)
            }
        }
        val positive = logs.count { it.feedback == Feedback.GOOD || it.feedback == Feedback.EASY }
        return StatsResult(
            totalWords = snapshot.totalWordCount,
            newWords = cards.count { it.status.name == "NEW" },
            reviewCount = logs.size,
            rememberedCount = positive,
            completedDays = snapshot.dailyPlans.count { it.localDate in safeRange(statsStartDate, effectiveAnchor) && it.status.name == "COMPLETED" },
            estimatedRecallPercent = if (logs.isEmpty()) 0 else positive * 100 / logs.size,
            chartLabels = labels,
            chartDates = dates,
            forgettingCurve = forgetting,
            referenceForgettingCurve = reference,
            personalForgettingCurve = fittedCurve.personalPoints,
            referencePersonalForgettingCurve = fittedCurve.referencePoints,
            forgettingSampleCount = fittedCurve.sampleCount,
            forgettingPreliminary = fittedCurve.isPreliminary,
            learningBars = bars,
            retentionSeries = retention,
            anchorDate = effectiveAnchor,
            statsStartDate = statsStartDate,
            period = period,
        )
    }

    private fun safeRange(start: LocalDate, end: LocalDate): ClosedRange<LocalDate> = start..end

    private fun ReviewLog.toStatsReviewLog() = StatsReviewLog(
        id = id,
        wordId = wordId,
        mode = mode,
        reviewedAt = reviewedAt,
        feedback = feedback,
        responseTimeMs = responseTimeMs,
        nextStateSnapshot = nextStateSnapshot,
    )

    private fun MutableList<RetentionSeries>.addRetention(
        threshold: Int?,
        label: String,
        dates: List<LocalDate>,
        availableThrough: LocalDate,
        period: StatsPeriod,
        currentLearned: Int,
        currentEnd: LocalDate,
        stabilitiesByDate: Map<LocalDate, List<Double>>,
    ) {
        fun count(date: LocalDate): Int = stabilitiesByDate[date].orEmpty().count { threshold == null || it >= threshold }
        val current = count(currentEnd)
        add(
            RetentionSeries(
            thresholdDays = threshold,
            label = label,
            values = dates.map { date ->
                if (date.isAfter(availableThrough)) null else count(periodEnd(date, period).coerceAtMost(availableThrough)).toFloat()
            },
            total = current,
            percent = if (currentLearned == 0) 0f else current * 100f / currentLearned,
            ),
        )
    }

    private fun learningBar(
        logsByDate: Map<LocalDate, List<StatsReviewLog>>,
        plansByDate: Map<LocalDate, DailyPlan>, planItemsById: Map<Long, List<DailyPlanItem>>, label: String,
        date: LocalDate, anchor: LocalDate, availableThrough: LocalDate, start: LocalDate, period: StatsPeriod,
    ): LearningBar {
        if (date.isAfter(availableThrough)) return LearningBar(label, 0, 0, 0, 0, 0, false)
        val dayBars = periodDates(date, period).filter { it >= start && it <= availableThrough }
            .map { dailyBar(logsByDate, plansByDate, planItemsById, it) }
        return LearningBar(label, dayBars.sumOf { it.familiar }, dayBars.sumOf { it.remembered }, dayBars.sumOf { it.forgotten },
            dayBars.sumOf { it.learning }, dayBars.sumOf { it.planned }, date == periodStart(anchor, period), dayBars.sumOf { it.studyTimeMs })
    }

    private fun dailyBar(
        logsByDate: Map<LocalDate, List<StatsReviewLog>>,
        plansByDate: Map<LocalDate, DailyPlan>,
        planItemsById: Map<Long, List<DailyPlanItem>>,
        date: LocalDate,
    ): LearningBar {
        val logs = logsByDate[date].orEmpty()
        val plan = plansByDate[date]
        val plannedIds = plan?.let { current ->
            planItemsById[current.id].orEmpty().map { it.wordId }.toSet()
        } ?: logs.map { it.wordId }.toSet()
        val first = logs.filter { it.wordId in plannedIds }.groupBy { it.wordId }
            .mapValues { (_, values) -> values.minWith(compareBy({ it.reviewedAt }, { it.id })) }.values
        val totalPlanned = plan?.let { maxOf(it.plannedUniqueWordCount, plannedIds.size) } ?: 0
        val unstarted = if (plan == null) 0 else (totalPlanned - first.size).coerceAtLeast(0)
        return LearningBar("", first.count { it.feedback == Feedback.EASY }, first.count { it.feedback == Feedback.GOOD },
            first.count { it.feedback == Feedback.AGAIN }, first.count { it.feedback == Feedback.HARD }, unstarted, false,
            logs.sumOf { it.responseTimeMs.coerceAtLeast(0L) })
    }

    private fun predictedRecall(cards: Collection<CardState>, date: LocalDate, baseline: LocalDate): Float {
        val elapsed = ChronoUnit.DAYS.between(baseline, date).coerceAtLeast(0)
        if (elapsed == 0L) return 100f
        if (cards.isEmpty()) return 0f
        return cards.map { card ->
            val last = card.lastReviewedAt?.atZone(zone)?.toLocalDate() ?: baseline
            val days = ChronoUnit.DAYS.between(last, date).coerceAtLeast(0)
            fsrsRecall(days.toDouble(), card.stability.coerceAtLeast(1.0)) * 100.0
        }.average().toFloat()
    }

    private fun fsrsRecall(elapsed: Double, stability: Double): Double {
        val decay = 0.1542
        val factor = 0.9.pow(-1.0 / decay) - 1.0
        return (1.0 + factor * elapsed.coerceAtLeast(0.0) / stability.coerceAtLeast(0.01)).pow(-decay).coerceIn(0.0, 1.0)
    }

    private fun standardEbbinghausRecall(day: Int): Float = (100.0 / (1.0 + day.coerceAtLeast(0)).toDouble().pow(0.65)).toFloat()
    private fun stabilityFromSnapshot(snapshot: String): Double = Regex("stability=([-+0-9.Ee]+)").find(snapshot)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    private fun localDate(log: StatsReviewLog): LocalDate = log.reviewedAt.atZone(zone).toLocalDate()

    private fun chartDates(anchor: LocalDate, start: LocalDate, period: StatsPeriod): List<LocalDate> = when (period) {
        StatsPeriod.DAY -> (-5L..5L).map { anchor.plusDays(it) }.filter { it >= start }
        StatsPeriod.WEEK -> { val monday = anchor.with(DayOfWeek.MONDAY); (-5L..5L).map { monday.plusWeeks(it) }.filter { periodEnd(it, period) >= start } }
        StatsPeriod.MONTH -> (-5L..5L).map { anchor.withDayOfMonth(1).plusMonths(it) }.filter { periodEnd(it, period) >= start }
    }.ifEmpty { listOf(anchor) }

    private fun periodDates(start: LocalDate, period: StatsPeriod): List<LocalDate> = when (period) {
        StatsPeriod.DAY -> listOf(start)
        StatsPeriod.WEEK -> (0L..6L).map { start.plusDays(it) }
        StatsPeriod.MONTH -> (1..start.lengthOfMonth()).map { start.withDayOfMonth(it) }
    }
    private fun periodStart(date: LocalDate, period: StatsPeriod): LocalDate = when (period) {
        StatsPeriod.DAY -> date
        StatsPeriod.WEEK -> date.with(DayOfWeek.MONDAY)
        StatsPeriod.MONTH -> date.withDayOfMonth(1)
    }
    private fun periodEnd(date: LocalDate, period: StatsPeriod): LocalDate = when (period) {
        StatsPeriod.DAY -> date
        StatsPeriod.WEEK -> date.plusDays(6)
        StatsPeriod.MONTH -> date.withDayOfMonth(date.lengthOfMonth())
    }
    private fun labelFor(date: LocalDate, anchor: LocalDate, period: StatsPeriod): String = when (period) {
        StatsPeriod.DAY -> when {
            date == anchor -> "今天"
            date == anchor.minusDays(1) -> "昨天"
            date == anchor.plusDays(1) -> "明天"
            date.isBefore(anchor) -> "${ChronoUnit.DAYS.between(date, anchor)}天前"
            else -> "${ChronoUnit.DAYS.between(anchor, date)}天后"
        }
        StatsPeriod.WEEK -> if (date == anchor.with(DayOfWeek.MONDAY)) "本周" else "${date.monthValue}/${date.dayOfMonth}"
        StatsPeriod.MONTH -> if (date == anchor.withDayOfMonth(1)) "本月" else "${date.year}/${date.monthValue}"
    }
}
