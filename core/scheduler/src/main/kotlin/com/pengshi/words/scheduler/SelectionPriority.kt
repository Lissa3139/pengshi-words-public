package com.pengshi.words.scheduler

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object SelectionPriority {
    /**
     * Explainable approximation of the public MoMo behavior: older calendar-day
     * overdue cards first, then the cards with the lowest current retrievability
     * (highest forgetting risk), followed by lapses and difficulty.
     */
    fun dueComparator(localDate: LocalDate, zoneId: ZoneId): Comparator<PlanCandidate> =
        compareByDescending<PlanCandidate> { naturalOverdueDays(it, localDate, zoneId) }
            .thenBy { it.retrievability ?: 1.0 }
            .thenByDescending { it.overdueSeconds }
            .thenByDescending { it.lapses }
            .thenByDescending { it.difficulty ?: 0.0 }
            .thenBy { it.wordId }

    private fun naturalOverdueDays(candidate: PlanCandidate, localDate: LocalDate, zoneId: ZoneId): Long {
        val dueDate = candidate.dueAt?.atZone(zoneId)?.toLocalDate() ?: localDate
        return ChronoUnit.DAYS.between(dueDate, localDate).coerceAtLeast(0L)
    }
}
