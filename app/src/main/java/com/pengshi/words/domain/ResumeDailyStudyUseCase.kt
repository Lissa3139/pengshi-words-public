package com.pengshi.words.domain

import com.pengshi.words.model.DailyPlanRepository
import com.pengshi.words.model.StudyMode
import com.pengshi.words.scheduler.StudyScheduler
import java.time.Instant
import java.time.LocalDate

class ResumeDailyStudyUseCase(
    private val repository: DailyPlanRepository,
    private val defaultMode: StudyMode,
    private val scheduler: StudyScheduler = com.pengshi.words.scheduler.DefaultStudyScheduler(),
    private val reconciler: ReconcileDailyPlanUseCase = ReconcileDailyPlanUseCase(repository, scheduler),
    private val quotaProvider: suspend () -> Int = { DailyQuotaPolicy.DEFAULT_QUOTA },
) {
    suspend fun resumeDailyStudy(localDate: LocalDate, now: Instant): StudySessionState {
        val plan = repository.getPlan(localDate)
        return if (plan == null) {
            StartDailyStudyUseCase(repository, scheduler, quotaProvider).startDailyStudy(localDate, now, defaultMode)
        } else {
            var persistedMode: StudyMode? = null
            for (item in repository.getItems(plan.id)) {
                persistedMode = repository.getEvents(item.id).firstOrNull()?.mode
                if (persistedMode != null) break
            }
            val mode = persistedMode ?: defaultMode
            val reconciled = reconciler.reconcile(localDate, mode, now)
            StartDailyStudyUseCase(repository, scheduler, quotaProvider).render(reconciled.plan, mode)
        }
    }
}
