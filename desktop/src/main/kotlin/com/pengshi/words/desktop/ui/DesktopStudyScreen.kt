package com.pengshi.words.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pengshi.words.desktop.DesktopWordDetails
import com.pengshi.words.desktop.shouldShowStudyAnswers
import com.pengshi.words.domain.StudySessionState
import com.pengshi.words.domain.stageRemainingCount
import com.pengshi.words.model.Feedback
import com.pengshi.words.model.PersonalWordInput
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.StudyMode
import com.pengshi.words.model.Word
import com.pengshi.words.model.WordSense
import com.pengshi.words.model.normalizeDictionaryText
import com.pengshi.words.speech.SpeechVoiceOption

data class DesktopStudyViewState(
    val session: StudySessionState,
    val revealed: Boolean = false,
    val favorite: Boolean = false,
) {
    val word: Word get() = requireNotNull(session.currentWord)
    val stageLabel: String get() = when (session.currentItem?.source) {
        PlanSource.DUE_REVIEW -> "复习"
        PlanSource.NEW, PlanSource.MANUAL_NEW -> "新词"
        PlanSource.EXTRA -> "额外"
        null -> "学习"
    }
    val remainingCount: Int get() = session.stageRemainingCount()
    val prompt: String get() = if (session.currentEvent?.mode == StudyMode.CN_TO_EN) {
        (listOf(word.definitionCn) + session.currentWordSenses.map { it.definitionCn }).filter(String::isNotBlank).joinToString("；")
    } else word.spelling

    companion object {
        fun from(session: StudySessionState, favorite: Boolean): DesktopStudyViewState =
            DesktopStudyViewState(session = session, favorite = favorite)
    }
}

@Composable
fun DesktopStudyScreen(
    history: DesktopStudyHistory<DesktopStudyViewState>,
    words: List<Word>,
    onReveal: () -> Unit,
    onFeedback: (Feedback) -> Unit,
    onFavorite: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onAddWordToToday: (Long) -> Unit,
    onSpeakWord: () -> Unit,
    onSpeakExamples: () -> Unit,
    speechVoices: List<SpeechVoiceOption> = emptyList(),
    onSpeakWordWithVoice: (SpeechVoiceOption) -> Unit = {},
    wordSenses: List<WordSense> = emptyList(),
) {
    val view = history.displayed
    val answersVisible = shouldShowStudyAnswers(view.revealed, history.isHistorical)
    var searchOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().padding(30.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回首页") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { searchOpen = true }) { Text("⌕ 搜索词条") }
                TextButton(onClick = onFavorite) { Text(if (view.favorite) "★ 已收藏" else "☆ 收藏") }
            }
            DesktopPanel(Modifier.fillMaxWidth().weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(if (history.isHistorical) "正在回看" else "今日学习  /  ${view.stageLabel}",
                            color = DesktopPalette.teal, style = MaterialTheme.typography.subtitle2)
                        Text(view.prompt, style = MaterialTheme.typography.h3.copy(fontFamily = FontFamily.Serif),
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 36.dp, bottom = 8.dp))
                        view.word.phonetic?.takeIf(String::isNotBlank)?.let {
                            Text(it, color = DesktopPalette.muted, style = MaterialTheme.typography.subtitle1)
                        }
                        if (view.session.mode == StudyMode.EN_TO_CN) {
                            TextButton(onClick = onSpeakWord) { Text("🔊 朗读单词") }
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(view.stageLabel, color = DesktopPalette.muted, style = MaterialTheme.typography.caption)
                        Surface(shape = CircleShape, color = DesktopPalette.coral, modifier = Modifier.padding(top = 6.dp)) {
                            Text("${view.remainingCount}", color = Color.White,
                                fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                        }
                    }
                }
                Text(
                    "当前词记忆进度  ${view.session.currentMemoryProgress} / 100",
                    color = DesktopPalette.muted,
                    style = MaterialTheme.typography.caption,
                )
                LinearProgressIndicator(
                    progress = (view.session.currentMemoryProgress / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                    color = DesktopPalette.teal,
                )
                Spacer(Modifier.height(34.dp))
                if (!answersVisible) {
                    Text(if (view.session.mode == StudyMode.EN_TO_CN) "先回想中文释义" else "先回想英文单词",
                        color = DesktopPalette.muted)
                    Button(onClick = onReveal, modifier = Modifier.padding(top = 16.dp)) { Text("显示答案") }
                } else {
                    Divider(color = DesktopPalette.line)
                    Text("释义", color = DesktopPalette.teal, style = MaterialTheme.typography.overline,
                        modifier = Modifier.padding(top = 20.dp))
                    Text(view.word.definitionCn.normalizeDictionaryText(), style = MaterialTheme.typography.h6,
                        modifier = Modifier.padding(top = 8.dp))
                    if (view.session.mode == StudyMode.CN_TO_EN) {
                        Text(view.word.spelling, style = MaterialTheme.typography.h6.copy(fontFamily = FontFamily.Serif),
                            modifier = Modifier.padding(top = 8.dp))
                        TextButton(onClick = onSpeakWord) { Text("🔊 朗读单词") }
                    }
                    view.session.currentWordSenses.forEach { sense ->
                        val part = sense.partOfSpeech.takeIf(String::isNotBlank)?.let { "$it · " }.orEmpty()
                        Text("$part${sense.definitionCn}", style = MaterialTheme.typography.body1,
                            modifier = Modifier.padding(top = 6.dp))
                        Text("释义来源：${sense.definitionSource}", color = DesktopPalette.muted,
                            style = MaterialTheme.typography.caption)
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(if (history.isHistorical || history.currentRevisable) {
                        if (history.canReviseDisplayed) "可修改最近一次记忆程度" else "较早的记录仅供回看"
                    } else "这次记得怎么样？", color = DesktopPalette.muted)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("忘记" to Feedback.AGAIN, "困难" to Feedback.HARD,
                            "记得" to Feedback.GOOD, "熟知" to Feedback.EASY).forEach { (label, feedback) ->
                            OutlinedButton(onClick = { onFeedback(feedback) },
                                enabled = !history.isHistorical || history.canReviseDisplayed,
                                modifier = Modifier.weight(1f)) { Text(label) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = onPrevious, enabled = history.canGoPrevious) { Text("← 上一个") }
                Text("${view.session.completedUniqueWordCount} / ${view.session.plan.quota} 个已完成",
                    color = DesktopPalette.muted, modifier = Modifier.padding(top = 12.dp))
                OutlinedButton(onClick = onNext, enabled = history.canGoNext) { Text("回到当前 →") }
            }
        }
        DesktopWordDetailsPanel(
            details = DesktopWordDetails(view.word, view.session.currentExamples, view.session.currentRelatedWords, view.session.currentWordSenses),
            showAnswers = answersVisible,
            onSpeakWord = onSpeakWord,
            speechVoices = speechVoices,
            onSpeakWordWithVoice = onSpeakWordWithVoice,
            onSpeakExamples = onSpeakExamples,
            onAddWordToToday = onAddWordToToday,
            modifier = Modifier.width(340.dp).fillMaxHeight(),
        )
    }
    if (searchOpen) DesktopSearchDialog(words, wordSenses, onDismiss = { searchOpen = false },
        onAddWordToToday = { onAddWordToToday(it); searchOpen = false })
}

@Composable
fun DesktopWordDetailsPanel(
    details: DesktopWordDetails,
    onAddWordToToday: (Long) -> Unit,
    deckEditable: Boolean = false,
    onUpdateWord: ((PersonalWordInput) -> Unit)? = null,
    onDeleteWord: (() -> Unit)? = null,
    onAddExample: ((String, String?, String) -> Unit)? = null,
    onUpdateExample: ((Long, String, String?, String) -> Unit)? = null,
    onDeleteExample: ((Long) -> Unit)? = null,
    showAnswers: Boolean = true,
    onSpeakWord: () -> Unit = {},
    onSpeakExamples: () -> Unit = {},
    speechVoices: List<SpeechVoiceOption> = emptyList(),
    onSpeakWordWithVoice: (SpeechVoiceOption) -> Unit = {},
    allowAddCurrentWord: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var wordDialog by remember { mutableStateOf(false) }
    var exampleDialog by remember { mutableStateOf<ExampleDraft?>(null) }
    var currentWordAdded by remember(details.word.id) { mutableStateOf(false) }
    fun openWordDialog() { wordDialog = true }
    fun openExampleDialog(id: Long? = null, en: String = "", cn: String? = null, source: String = "") {
        exampleDialog = ExampleDraft(id, en, cn.orEmpty(), source)
    }
    DesktopPanel(modifier) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("词条笔记", style = MaterialTheme.typography.h6)
            if (!showAnswers) {
                Text("先回想答案，再点击“显示答案”。", color = DesktopPalette.muted)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(details.word.spelling, style = MaterialTheme.typography.h5.copy(fontFamily = FontFamily.Serif))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onSpeakWord) { Text("🔊") }
                }
                DesktopVoiceButtons(speechVoices, onSpeakWordWithVoice)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(details.word.partOfSpeech.ifBlank { "词义" }, color = DesktopPalette.teal)
                    details.word.phonetic?.takeIf(String::isNotBlank)?.let { Text(it, color = DesktopPalette.muted) }
                }
                if (allowAddCurrentWord) {
                    OutlinedButton(
                        onClick = { onAddWordToToday(details.word.id); currentWordAdded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (currentWordAdded) "已加入今日记单词 ✓" else "加入今日记单词") }
                    if (currentWordAdded) Text("该词已加入今日额外学习队列，不占 30 词额度。", color = DesktopPalette.teal)
                }
                if (deckEditable) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onUpdateWord?.let { TextButton(onClick = ::openWordDialog) { Text("编辑词条") } }
                        onDeleteWord?.let { TextButton(onClick = it) { Text("从当前词库删除") } }
                    }
                }
                Text("词义", color = DesktopPalette.teal, fontWeight = FontWeight.SemiBold)
                Text(details.word.definitionCn.normalizeDictionaryText(), color = DesktopPalette.ink)
                Text("释义来源：${details.word.definitionSource}", color = DesktopPalette.muted,
                    style = MaterialTheme.typography.caption)
                details.senses.forEach { sense ->
                    Text("${sense.partOfSpeech.takeIf(String::isNotBlank)?.let { "$it · " }.orEmpty()}${sense.definitionCn}", color = DesktopPalette.ink)
                    Text("释义来源：${sense.definitionSource}", color = DesktopPalette.muted,
                        style = MaterialTheme.typography.caption)
                }
                Text("助记", color = DesktopPalette.teal, fontWeight = FontWeight.SemiBold)
                Text(details.word.mnemonic.ifBlank { "尚未添加自定义助记" }, color = DesktopPalette.ink)
                Divider(color = DesktopPalette.line)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("例句", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (deckEditable) onAddExample?.let { TextButton(onClick = { openExampleDialog() }) { Text("补充例句") } }
                    if (details.examples.isNotEmpty()) {
                        TextButton(onClick = onSpeakExamples) { Text("🔊 朗读例句") }
                    }
                }
                if (details.examples.isEmpty()) Text("暂无例句", color = DesktopPalette.muted)
                details.examples.forEachIndexed { index, example ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            Text("${index + 1}. ${example.sentenceEn}", style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
                            if (deckEditable) {
                                onUpdateExample?.let { TextButton(onClick = { openExampleDialog(example.id, example.sentenceEn, example.sentenceCn, example.source) }) { Text("编辑") } }
                                onDeleteExample?.let { TextButton(onClick = { it(example.id) }) { Text("删除") } }
                            }
                        }
                        example.sentenceCn?.takeIf(String::isNotBlank)?.let { Text(it, color = DesktopPalette.muted,
                            style = MaterialTheme.typography.caption) }
                        Text("例句来源：${example.source}", color = DesktopPalette.muted,
                            style = MaterialTheme.typography.caption)
                    }
                }
                Divider(color = DesktopPalette.line)
                Text("助记 · 相关词", fontWeight = FontWeight.SemiBold)
                if (details.relatedWords.isEmpty()) Text("暂无词族或相似词；自定义助记尚未添加。", color = DesktopPalette.muted,
                    style = MaterialTheme.typography.body2)
                details.relatedWords.forEach { related ->
                    Column {
                        Text("${related.word.spelling}  ·  ${related.reason}", color = DesktopPalette.teal)
                        Text(related.word.definitionCn, style = MaterialTheme.typography.caption, color = DesktopPalette.muted)
                        TextButton(onClick = { onAddWordToToday(related.word.id) }) { Text("加入今日") }
                    }
                }
            }
        }
    }
    if (wordDialog && onUpdateWord != null) {
        WordEditDialogContent(details, onDismiss = { wordDialog = false }, onSave = { input -> onUpdateWord(input); wordDialog = false })
    }
    exampleDialog?.let { draft ->
        ExampleDialogContent(draft, onDismiss = { exampleDialog = null }, onSave = { en, cn, source ->
            if (draft.id == null) onAddExample?.invoke(en, cn, source) else onUpdateExample?.invoke(draft.id, en, cn, source)
            exampleDialog = null
        })
    }

}

@Composable
private fun DesktopVoiceButtons(voices: List<SpeechVoiceOption>, onSpeak: (SpeechVoiceOption) -> Unit) {
    if (voices.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("试听音色", color = DesktopPalette.muted, style = MaterialTheme.typography.caption)
        voices.forEach { voice ->
            OutlinedButton(onClick = { onSpeak(voice) }) { Text(voice.engineLabel.substringBefore(" · ")) }
        }
    }
}

private data class ExampleDraft(val id: Long?, val sentenceEn: String, val sentenceCn: String, val source: String)

@Composable
private fun WordEditDialogContent(
    details: DesktopWordDetails,
    onDismiss: () -> Unit,
    onSave: (PersonalWordInput) -> Unit,
) {
    var definition by remember(details.word.id) { mutableStateOf(details.word.definitionCn) }
    var partOfSpeech by remember(details.word.id) { mutableStateOf(details.word.partOfSpeech) }
    var phonetic by remember(details.word.id) { mutableStateOf(details.word.phonetic.orEmpty()) }
    var definitionSource by remember(details.word.id) { mutableStateOf(details.word.definitionSource) }
    var mnemonic by remember(details.word.id) { mutableStateOf(details.word.mnemonic) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 ${details.word.spelling}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(definition, { definition = it }, label = { Text("中文释义") }, minLines = 2)
                OutlinedTextField(partOfSpeech, { partOfSpeech = it }, label = { Text("词性") }, singleLine = true)
                OutlinedTextField(phonetic, { phonetic = it }, label = { Text("音标") }, singleLine = true)
                OutlinedTextField(definitionSource, { definitionSource = it }, label = { Text("释义来源") })
                OutlinedTextField(mnemonic, { mnemonic = it }, label = { Text("助记") }, minLines = 2)
            }
        },
        confirmButton = { Button(onClick = { onSave(PersonalWordInput(details.word.spelling, definition, partOfSpeech, phonetic,
            definitionSource = definitionSource, mnemonic = mnemonic)) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ExampleDialogContent(
    draft: ExampleDraft,
    onDismiss: () -> Unit,
    onSave: (String, String?, String) -> Unit,
) {
    var english by remember(draft.id) { mutableStateOf(draft.sentenceEn) }
    var chinese by remember(draft.id) { mutableStateOf(draft.sentenceCn) }
    var source by remember(draft.id) { mutableStateOf(draft.source) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "补充例句" else "编辑例句") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(english, { english = it }, label = { Text("英文例句") }, minLines = 2)
                OutlinedTextField(chinese, { chinese = it }, label = { Text("中文翻译") }, minLines = 2)
                OutlinedTextField(source, { source = it }, label = { Text("例句来源") })
            }
        },
        confirmButton = { Button(enabled = english.isNotBlank(), onClick = { onSave(english, chinese.ifBlank { null }, source) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DesktopSearchDialog(words: List<Word>, senses: List<WordSense>, onDismiss: () -> Unit, onAddWordToToday: (Long) -> Unit) {
    var query by remember { mutableStateOf("") }
    val normalized = query.trim().lowercase()
    val results = if (normalized.isEmpty()) emptyList() else words.filter {
        it.spelling.lowercase().contains(normalized) || it.definitionCn.contains(query.trim()) ||
            senses.any { sense -> sense.wordId == it.id && sense.definitionCn.contains(query.trim()) }
    }.take(12)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索词条") },
        text = {
            Column {
                OutlinedTextField(query, onValueChange = { query = it }, label = { Text("英文或中文") }, singleLine = true)
                if (normalized.isNotEmpty() && results.isEmpty()) Text("没有找到匹配词条", color = DesktopPalette.muted)
                LazyColumn(Modifier.height(330.dp)) {
                    items(results, key = { it.id }) { word ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(word.spelling, fontWeight = FontWeight.SemiBold)
                                Text(word.definitionCn, style = MaterialTheme.typography.caption)
                                senses.filter { it.wordId == word.id }.forEach { sense ->
                                    Text("${sense.partOfSpeech.takeIf(String::isNotBlank)?.let { "$it · " }.orEmpty()}${sense.definitionCn}",
                                        style = MaterialTheme.typography.caption, color = DesktopPalette.muted)
                                }
                            }
                            TextButton(onClick = { onAddWordToToday(word.id) }) { Text("加入今日") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
