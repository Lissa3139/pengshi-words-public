package com.pengshi.words.desktop.stats

import com.pengshi.words.model.StudyDataSnapshot
import java.time.LocalDate

typealias DesktopStatsTab = com.pengshi.words.stats.StatsTab
typealias DesktopStatsPeriod = com.pengshi.words.stats.StatsPeriod
typealias DesktopStatsPoint = com.pengshi.words.stats.StatsPoint
typealias DesktopLearningBar = com.pengshi.words.stats.LearningBar
typealias DesktopRetentionSeries = com.pengshi.words.stats.RetentionSeries
typealias DesktopStatsState = com.pengshi.words.stats.StatsResult

object DesktopStatsCalculator {
    fun calculate(
        snapshot: StudyDataSnapshot,
        anchorDate: LocalDate = LocalDate.now(),
        statsStartDate: LocalDate = snapshot.reviewLogs.minOfOrNull { it.reviewedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate() } ?: anchorDate,
        period: DesktopStatsPeriod = DesktopStatsPeriod.DAY,
    ): DesktopStatsState = com.pengshi.words.stats.StatsCalculator.calculate(snapshot, anchorDate, statsStartDate, period)
}
