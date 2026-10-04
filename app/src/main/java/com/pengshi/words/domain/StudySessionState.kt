package com.pengshi.words.domain

import com.pengshi.words.model.CardState
import com.pengshi.words.model.DailyPlan
import com.pengshi.words.model.DailyPlanItem
import com.pengshi.words.model.ExampleSentence
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.RelatedWord
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.isNewWord
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.Word
import com.pengshi.words.model.WordSense

data class StudySessionState(
    val plan: DailyPlan,
    val mode: StudyMode,
    val currentItem: DailyPlanItem?,
    val currentEvent: IntradayReviewEvent?,
    val currentCard: CardState?,
    val currentWord: Word?,
    val currentExamples: List<ExampleSentence> = emptyList(),
    val currentWordSenses: List<WordSense> = emptyList(),
    val currentRelatedWords: List<RelatedWord> = emptyList(),
    val pendingEventCount: Int,
    val completedUniqueWordCount: Int = plan.completedUniqueWordCount,
    val isComplete: Boolean = pendingEventCount == 0,
    val reviewPlannedCount: Int = 0,
    val reviewCompletedCount: Int = 0,
    val newPlannedCount: Int = 0,
    val newCompletedCount: Int = 0,
    val extraPlannedCount: Int = 0,
    val extraCompletedCount: Int = 0,
    val currentMemoryProgress: Int = 0,
)

fun StudySessionState.stageRemainingCount(): Int = when (currentItem?.source) {
    PlanSource.DUE_REVIEW -> (reviewPlannedCount - reviewCompletedCount).coerceAtLeast(0)
    PlanSource.NEW, PlanSource.MANUAL_NEW -> (newPlannedCount - newCompletedCount).coerceAtLeast(0)
    PlanSource.EXTRA, null -> 0
}
