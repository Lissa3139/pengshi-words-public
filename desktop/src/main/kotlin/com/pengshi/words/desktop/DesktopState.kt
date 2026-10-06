package com.pengshi.words.desktop

import com.pengshi.words.model.ExampleSentence
import com.pengshi.words.model.ImportResult
import com.pengshi.words.model.RelatedWord
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.DeckSourceType
import com.pengshi.words.model.WordPoolSelection
import com.pengshi.words.model.Word
import com.pengshi.words.model.WordSense
import com.pengshi.words.model.UserDeckBulkResult
import java.time.LocalDate

data class DesktopWordDetails(
    val word: Word,
    val examples: List<ExampleSentence>,
    val relatedWords: List<RelatedWord>,
    val senses: List<WordSense> = emptyList(),
)

enum class DesktopStudyPhase {
    NOT_STARTED,
    REVIEW,
    CHOOSE_NEW,
    NEW_WORDS,
    EXTRA,
    COMPLETE,
}

data class DesktopHomeState(
    val completedUniqueWordCount: Int = 0,
    val quota: Int = 30,
    val dueCount: Int = 0,
    val availableNewCount: Int = 0,
    val reviewTotal: Int = 0,
    val reviewCompleted: Int = 0,
    val newTotal: Int = 0,
    val newCompleted: Int = 0,
    val extraTotal: Int = 0,
    val extraCompleted: Int = 0,
    val phase: DesktopStudyPhase = DesktopStudyPhase.NOT_STARTED,
    val isTodayComplete: Boolean = false,
    val checkInDates: Set<LocalDate> = emptySet(),
    val isAvailable: Boolean = true,
    val plannedUniqueWordCount: Int = 0,
)

data class DesktopDeckSummary(
    val id: Long,
    val name: String,
    val wordCount: Int,
    val dueCount: Int,
    val newCount: Int,
    val sourceType: DeckSourceType,
) {
    val isEditable: Boolean get() = sourceType != DeckSourceType.BUILTIN
}

data class DesktopSelectionDashboardState(
    val selection: WordPoolSelection = WordPoolSelection(),
    val decks: List<DesktopDeckSummary> = emptyList(),
    val candidateCount: Int = 0,
    val dueCount: Int = 0,
    val newCount: Int = 0,
    val excludedWords: List<Word> = emptyList(),
    val deckWeights: Map<Long, Int> = emptyMap(),
    val isSaving: Boolean = false,
    val saveError: String? = null,
)

data class DesktopSpeechContent(
    val word: String?,
    val examples: List<String>,
)

fun shouldShowStudyAnswers(revealed: Boolean, historical: Boolean): Boolean = revealed || historical

fun desktopStudySpeechContent(
    mode: StudyMode,
    spelling: String,
    examples: List<String>,
    answersVisible: Boolean,
): DesktopSpeechContent = DesktopSpeechContent(
    word = if (mode == StudyMode.EN_TO_CN || answersVisible) spelling else null,
    examples = if (answersVisible) examples.filter(String::isNotBlank) else emptyList(),
)

fun retainCompletedStudyView(
    previous: com.pengshi.words.desktop.ui.DesktopStudyViewState,
    completedSession: com.pengshi.words.domain.StudySessionState,
): com.pengshi.words.desktop.ui.DesktopStudyViewState = previous.copy(
    session = previous.session.copy(
        plan = completedSession.plan,
        pendingEventCount = completedSession.pendingEventCount,
        completedUniqueWordCount = completedSession.completedUniqueWordCount,
        isComplete = completedSession.isComplete,
        reviewPlannedCount = completedSession.reviewPlannedCount,
        reviewCompletedCount = completedSession.reviewCompletedCount,
        newPlannedCount = completedSession.newPlannedCount,
        newCompletedCount = completedSession.newCompletedCount,
    ),
)

data class DesktopDateState(
    val localDate: LocalDate,
    val hasStudyHistory: Boolean = false,
    val hasUndoToken: Boolean = false,
    val shouldRefresh: Boolean = false,
) {
    fun observe(observedDate: LocalDate): DesktopDateState = if (observedDate == localDate) {
        copy(shouldRefresh = false)
    } else {
        DesktopDateState(
            localDate = observedDate,
            hasStudyHistory = false,
            hasUndoToken = false,
            shouldRefresh = true,
        )
    }
}

fun formatImportResult(result: ImportResult): String = if (result.errors.isNotEmpty()) {
    val details = result.errors.joinToString("；") { error ->
        "第 ${error.rowNumber} 行 [${error.field}] ${error.message}"
    }
    "导入失败：$details。"
} else {
    "已导入 ${result.importedRows} 个词条。"
}

fun formatUserDeckBulkResult(result: UserDeckBulkResult): String = if (result.errors.isNotEmpty()) {
    val details = result.errors.joinToString("；") { error ->
        "第 ${error.rowNumber} 行 [${error.field}] ${error.message}"
    }
    val protected = if (result.protectedBuiltinRows > 0) {
        "内置词条 ${result.protectedBuiltinRows} 条：保留官方释义和例句，可追加不同释义。"
    } else ""
    "导入完成：新增/关联 ${result.importedRows}，更新 ${result.updatedRows}，新增释义 ${result.addedDefinitions}，跳过 ${result.skippedRows}。$protected$details"
} else {
    buildList {
        add("新增/关联 ${result.importedRows}")
        add("更新 ${result.updatedRows}")
        if (result.linkedRows > 0) add("复用总词典 ${result.linkedRows}")
        if (result.skippedRows > 0) add("跳过 ${result.skippedRows}")
        if (result.protectedBuiltinRows > 0) add("内置词条 ${result.protectedBuiltinRows} 条：保留官方释义和例句，可追加不同释义")
        if (result.addedDefinitions > 0) add("新增释义 ${result.addedDefinitions}")
    }.joinToString("，") + "。"
}
