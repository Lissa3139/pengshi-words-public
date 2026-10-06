package com.pengshi.words.feature.study

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pengshi.words.domain.StudySessionState
import com.pengshi.words.domain.stageRemainingCount
import com.pengshi.words.model.Feedback
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.distinctSupplementaryMeanings
import com.pengshi.words.model.normalizeDictionaryText
import com.pengshi.words.speech.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

class StudyViewModel(
    private val submitFeedback: suspend (Long, Feedback, Instant) -> StudySessionState,
    private val speechEngine: SpeechEngine,
    private val scope: CoroutineScope,
    private val nowProvider: () -> Instant = Instant::now,
) {
    var state by mutableStateOf<StudyScreenState?>(null)
        private set

    fun show(session: StudySessionState) {
        state = session.toScreenState()
    }

    fun onRevealAnswer() {
        state = state?.copy(isAnswerRevealed = true, feedbackEnabled = true)
    }

    fun onFeedback(feedback: Feedback) {
        val current = state ?: return
        if (!current.isAnswerRevealed) return
        // The screen remains responsive while the atomic use-case writes the event and card.
        state = current.copy(feedbackEnabled = false)
        current.itemId?.let { submit(it, feedback) }
    }

    fun onSpeakWord() {
        state?.let { speechEngine.speak(it.englishWord.ifBlank { it.prompt }, "word", 1.0f) }
    }

    fun onSpeakSentence() {
        state?.let { speechEngine.speakSequence(it.examples.map(ExampleSentenceUi::sentenceEn), 1.0f) }
    }

    fun submit(itemId: Long, feedback: Feedback) {
        scope.launch(Dispatchers.Main.immediate) {
            val session = submitFeedback(itemId, feedback, nowProvider())
            show(session)
        }
    }

    fun close() {
        speechEngine.stop()
        speechEngine.release()
    }
}

fun StudySessionState.toScreenState(): StudyScreenState {
    val word = requireNotNull(currentWord) { "A study session must have a current word" }
    val additionalSenses = distinctSupplementaryMeanings(word.definitionCn, currentWordSenses)
    val completeMeaning = (listOf(word.definitionCn) + additionalSenses.map { it.definitionCn })
        .filter(String::isNotBlank).joinToString("；")
    val prompt = if (currentEvent?.mode == StudyMode.CN_TO_EN) completeMeaning else word.spelling
    val answer = if (currentEvent?.mode == StudyMode.CN_TO_EN) word.spelling else word.definitionCn
    return StudyScreenState(
        mode = currentEvent?.mode ?: mode,
        itemId = currentItem?.id,
        eventId = currentEvent?.id,
        wordId = word.id,
        englishWord = word.spelling,
        prompt = prompt,
        answer = answer,
        definitionCn = word.definitionCn,
        definitionSource = word.definitionSource,
        additionalMeanings = additionalSenses.map { WordMeaningUi(it.partOfSpeech, it.definitionCn, it.definitionSource) },
        mnemonic = word.mnemonic,
        phonetic = word.phonetic,
        partOfSpeech = word.partOfSpeech.ifBlank { inferPartOfSpeech(word.definitionCn) },
        examples = currentExamples.map { ExampleSentenceUi(it.sentenceEn, it.sentenceCn, it.source) },
        relatedWords = currentRelatedWords.map {
            RelatedWordUi(
                spelling = it.word.spelling,
                definitionCn = it.word.definitionCn,
                kindLabel = it.reason,
                wordId = it.word.id,
            )
        },
        progressLabel = "今日进度 $completedUniqueWordCount / ${plan.quota}",
        memoryProgressPoints = currentMemoryProgress,
        stageLabel = when (currentItem?.source) {
            PlanSource.DUE_REVIEW -> "复习"
            PlanSource.NEW, PlanSource.MANUAL_NEW -> "新词"
            PlanSource.EXTRA -> "额外"
            null -> "学习中"
        },
        remainingCount = stageRemainingCount(),
    )
}

private fun inferPartOfSpeech(definitionCn: String): String = definitionCn.normalizeDictionaryText()
    .lineSequence()
    .mapNotNull { line -> Regex("^\\s*((?:[A-Za-z]+\\.\\s*)+)").find(line)?.groupValues?.getOrNull(1) }
    .map { it.trim().replace(Regex("\\s+"), " ") }
    .distinct()
    .joinToString(" / ")
