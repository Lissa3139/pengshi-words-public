package com.pengshi.words.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pengshi.words.feature.decks.WordListItemUi
import com.pengshi.words.model.Feedback
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.normalizeDictionaryText
import com.pengshi.words.speech.SpeechVoiceOption
import kotlinx.coroutines.delay
import android.os.SystemClock

data class StudyScreenState(
    val mode: StudyMode,
    val itemId: Long? = null,
    val eventId: Long? = null,
    val wordId: Long? = null,
    val englishWord: String = "",
    val prompt: String,
    val answer: String,
    val definitionCn: String = "",
    val definitionSource: String = com.pengshi.words.model.SOURCE_UNVERIFIED,
    val additionalMeanings: List<WordMeaningUi> = emptyList(),
    val mnemonic: String = "",
    val phonetic: String? = null,
    val partOfSpeech: String? = null,
    val examples: List<ExampleSentenceUi> = emptyList(),
    val relatedWords: List<RelatedWordUi> = emptyList(),
    val progressLabel: String = "今日进度 0 / 30",
    val stageLabel: String = "学习中",
    val remainingCount: Int = 30,
    val isFavorite: Boolean = false,
    val isAnswerRevealed: Boolean = false,
    val feedbackEnabled: Boolean = false,
    val isHistorical: Boolean = false,
    val memoryProgressPoints: Int = 0,
) {
    companion object {
        fun sample() = StudyScreenState(
            mode = StudyMode.EN_TO_CN,
            prompt = "abandon",
            englishWord = "abandon",
            answer = "放弃",
            definitionCn = "放弃；离弃",
            phonetic = "/əˈbændən/",
            partOfSpeech = "v.",
            examples = listOf(
                ExampleSentenceUi("He abandoned the plan.", "他放弃了这个计划。"),
                ExampleSentenceUi("They abandoned the old building.", "他们放弃了那座旧建筑。"),
            ),
            progressLabel = "今日进度 0 / 30",
            remainingCount = 30,
        )
    }
}

data class ExampleSentenceUi(
    val sentenceEn: String,
    val sentenceCn: String?,
    val source: String = com.pengshi.words.model.SOURCE_UNVERIFIED,
)

data class WordMeaningUi(
    val partOfSpeech: String,
    val definitionCn: String,
    val source: String,
)

data class RelatedWordUi(
    val spelling: String,
    val definitionCn: String,
    val kindLabel: String,
    val wordId: Long = 0,
)

sealed interface StudyAction {
    data object RevealAnswer : StudyAction
    data object SpeakWord : StudyAction
    data class SpeakWordWithVoice(val voice: SpeechVoiceOption) : StudyAction
    data object SpeakSentence : StudyAction
    data class SpeakExample(val sentenceEn: String) : StudyAction
    data object StopSpeech : StudyAction
    data object ToggleFavorite : StudyAction
    data class SubmitFeedback(val feedback: Feedback, val responseTimeMs: Long = 0L) : StudyAction
}

@Composable
fun StudyScreen(
    state: StudyScreenState,
    onAction: (StudyAction) -> Unit,
    onBack: (() -> Unit)? = null,
    canGoPrevious: Boolean = false,
    onPrevious: () -> Unit = {},
    canGoNext: Boolean = false,
    onNext: () -> Unit = {},
    searchWords: List<WordListItemUi> = emptyList(),
    onSearchOpen: () -> Unit = {},
    onAddWordToToday: (Long) -> Unit = {},
    speechVoices: List<SpeechVoiceOption> = emptyList(),
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var marked by rememberSaveable { mutableStateOf(false) }
    var elapsedMinutes by rememberSaveable { mutableStateOf(0) }
    var reviewStartedAt by rememberSaveable { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(searchOpen) { if (searchOpen) onSearchOpen() }
    LaunchedEffect(state.eventId) { reviewStartedAt = SystemClock.elapsedRealtime() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            elapsedMinutes += 1
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) IconButton(onClick = { onAction(StudyAction.StopSpeech); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                if (state.isHistorical) {
                    IconButton(onClick = onNext, enabled = canGoNext) { Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "回到当前单词") }
                } else {
                    IconButton(onClick = onPrevious, enabled = canGoPrevious) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "回看上一个单词") }
                }
                Text("${elapsedMinutes} min", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 2.dp))
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { searchOpen = true }) { Icon(Icons.Default.Search, contentDescription = "搜索词条") }
                IconButton(onClick = { onAction(StudyAction.SpeakWord) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "朗读单词") }
                IconButton(onClick = { onAction(StudyAction.ToggleFavorite) }) {
                    Icon(if (state.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = if (state.isFavorite) "取消收藏" else "收藏", tint = if (state.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { marked = !marked }) { Icon(Icons.Default.Flag, contentDescription = if (marked) "取消重点标记" else "标记为重点", tint = if (marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                androidx.compose.foundation.layout.Box {
                    IconButton(onClick = { moreOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多") }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (marked) "取消重点标记" else "标记为重点") },
                            onClick = { marked = !marked; moreOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text("停止朗读") },
                            onClick = { onAction(StudyAction.StopSpeech); moreOpen = false },
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (state.isAnswerRevealed) {
                Surface(shadowElevation = 8.dp) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            if (state.isHistorical) "修改这次记忆程度" else "请选择记忆程度",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FeedbackButtons(
                            enabled = state.feedbackEnabled,
                            onAction = { action ->
                                if (action is StudyAction.SubmitFeedback) {
                                    onAction(action.copy(
                                        responseTimeMs = (SystemClock.elapsedRealtime() - reviewStartedAt).coerceAtLeast(0L),
                                    ))
                                } else {
                                    onAction(action)
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (state.isAnswerRevealed || state.mode == StudyMode.EN_TO_CN) state.englishWord else state.prompt,
                            style = MaterialTheme.typography.displaySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif),
                            modifier = Modifier.weight(1f),
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.stageLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = androidx.compose.ui.graphics.Color(0xFFE53935),
                            ) {
                                Text(
                                    state.remainingCount.toString(),
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                                    color = androidx.compose.ui.graphics.Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("美", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        state.phonetic?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    }
                    VoiceButtons(
                        voices = speechVoices,
                        onSpeak = { voice -> onAction(StudyAction.SpeakWordWithVoice(voice)) },
                    )
                    Text(
                        "当前词记忆进度  ${state.memoryProgressPoints} / 100",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { (state.memoryProgressPoints / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (!state.isAnswerRevealed) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (state.mode == StudyMode.EN_TO_CN) "先回想中文释义" else "先回想英文单词", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { onAction(StudyAction.RevealAnswer) }, modifier = Modifier.fillMaxWidth()) { Text("显示答案") }
                    }
                }
            } else {
                item { MeaningSection(state) }
                item { ExamplesHeader(onSpeakAll = { onAction(StudyAction.SpeakSentence) }) }
                itemsIndexed(state.examples) { index, example ->
                    ExampleRow(index = index, example = example, onSpeak = { onAction(StudyAction.SpeakExample(example.sentenceEn)) })
                }
                item { MnemonicSection(state, onAddWordToToday) }
            }
        }
    }

    if (searchOpen) {
        SearchDialog(
            words = searchWords,
            onDismiss = { searchOpen = false },
            onAddWordToToday = { wordId ->
                onAddWordToToday(wordId)
                searchOpen = false
            },
        )
    }
}

@Composable
private fun MeaningSection(state: StudyScreenState) {
    val meanings = state.definitionCn.ifBlank { state.answer }
        .normalizeDictionaryText()
        .split(Regex("\\r?\\n|[；;]"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .ifEmpty { listOf(state.answer) }
    Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .42f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Text(state.partOfSpeech?.ifBlank { "词义" } ?: "词义", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    meanings.forEachIndexed { index, meaning ->
                        Text("${index + 1}  $meaning", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("释义来源：${state.definitionSource}", style = MaterialTheme.typography.bodySmall)
                    state.additionalMeanings.forEachIndexed { index, sense ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 7.dp)) {
                            Text("补充释义 ${index + 1}${sense.partOfSpeech.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(sense.definitionCn, style = MaterialTheme.typography.titleSmall)
                            Text("释义来源：${sense.source}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExamplesHeader(onSpeakAll: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("例句", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onSpeakAll, modifier = Modifier.heightIn(min = 36.dp)) { Text("朗读第一句") }
    }
}

@Composable
private fun VoiceButtons(
    voices: List<SpeechVoiceOption>,
    onSpeak: (SpeechVoiceOption) -> Unit,
) {
    if (voices.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("音色", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        voices.forEach { voice ->
            OutlinedButton(
                onClick = { onSpeak(voice) },
                modifier = Modifier.heightIn(min = 34.dp),
            ) { Text(voice.engineLabel.substringBefore(" · ")) }
        }
    }
}

@Composable
private fun ExampleRow(index: Int, example: ExampleSentenceUi, onSpeak: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${index + 1}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(example.sentenceEn, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = onSpeak) { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "朗读例句") }
        }
        example.sentenceCn?.takeIf { it.isNotBlank() }?.let {
            Text(it, modifier = Modifier.padding(start = 22.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("例句来源：${example.source}", modifier = Modifier.padding(start = 22.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MnemonicSection(state: StudyScreenState, onAddWordToToday: (Long) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("助记", style = MaterialTheme.typography.titleMedium)
            if (state.mnemonic.isNotBlank()) {
                Text(state.mnemonic, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text("尚未添加自定义助记", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.relatedWords.isEmpty()) {
                Text("词族 / 相似词：暂无数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                state.relatedWords.forEach { related ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${related.spelling}  ${related.kindLabel} · ${related.definitionCn}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (related.wordId != 0L) {
                            OutlinedButton(onClick = { onAddWordToToday(related.wordId) }, modifier = Modifier.heightIn(min = 34.dp)) { Text("加入今日") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchDialog(
    words: List<WordListItemUi>,
    onDismiss: () -> Unit,
    onAddWordToToday: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val normalized = query.trim().lowercase()
    val results = if (normalized.isBlank()) emptyList() else words.filter {
        it.spelling.lowercase().contains(normalized) || it.definitionCn.contains(query.trim()) ||
            it.senses.any { sense -> sense.definitionCn.contains(query.trim()) }
    }.distinctBy { it.id.takeIf { id -> id != 0L } ?: it.spelling }.take(12)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索词条") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("输入英文或中文") })
                if (results.isEmpty() && normalized.isNotBlank()) Text("没有找到匹配词条", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(results, key = { if (it.id != 0L) it.id else it.spelling }) { word ->
                        Card {
                            Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(word.spelling, style = MaterialTheme.typography.titleSmall)
                                    Text(word.definitionCn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    word.senses.forEach { sense ->
                                        Text("${sense.partOfSpeech.takeIf(String::isNotBlank)?.let { "$it · " }.orEmpty()}${sense.definitionCn}",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                OutlinedButton(onClick = { onAddWordToToday(word.id) }, enabled = word.id != 0L) { Text("加入今日") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text("关闭") } },
    )
}
