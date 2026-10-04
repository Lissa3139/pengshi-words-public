package com.pengshi.words.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pengshi.words.desktop.DesktopDeckSummary
import com.pengshi.words.desktop.DesktopSelectionDashboardState
import com.pengshi.words.desktop.DesktopWordDetails
import com.pengshi.words.model.DeckSourceType
import com.pengshi.words.model.BatchImportSources
import com.pengshi.words.model.PersonalExampleInput
import com.pengshi.words.importer.DefaultWordImportParser
import com.pengshi.words.model.ImportFormat
import com.pengshi.words.model.PersonalWordInput
import com.pengshi.words.model.StudyDataSnapshot
import com.pengshi.words.model.WordPoolDraft
import com.pengshi.words.model.WordPoolSelection
import com.pengshi.words.speech.SpeechVoiceOption

@Composable
fun DesktopDecksScreen(
    snapshot: StudyDataSnapshot,
    selectionState: DesktopSelectionDashboardState,
    selectedDetails: DesktopWordDetails?,
    onSelectWord: (Long) -> Unit,
    onSpeakWord: () -> Unit,
    speechVoices: List<SpeechVoiceOption> = emptyList(),
    onSpeakWordWithVoice: (SpeechVoiceOption) -> Unit = {},
    onSpeakExamples: () -> Unit,
    onAddWordToToday: (Long) -> Unit,
    onCreateUserDeck: (String) -> Unit,
    onImportIntoDeck: (Long, BatchImportSources) -> Unit,
    onBatchAddWords: (Long, String, BatchImportSources) -> Unit,
    onAddWord: (Long, PersonalWordInput) -> Unit,
    onDeleteDeck: (Long) -> Unit,
    onUpdateWord: (Long, Long, PersonalWordInput) -> Unit,
    onDeleteWord: (Long, Long) -> Unit,
    onAddExample: (Long, String, String?, String) -> Unit,
    onUpdateExample: (Long, String, String?, String) -> Unit,
    onDeleteExample: (Long) -> Unit,
    onToggleExcludedWord: (Long) -> Unit,
    onSaveSelection: (WordPoolSelection) -> Unit,
) {
    var selectedDeckId by remember { mutableStateOf<Long?>(null) }
    var dashboardOpen by remember { mutableStateOf(false) }
    var createDialogOpen by remember { mutableStateOf(false) }
    var deckName by remember { mutableStateOf("") }
    var bulkDialogOpen by remember { mutableStateOf(false) }
    var addWordDialogOpen by remember { mutableStateOf(false) }
    var deleteDeckDialogOpen by remember { mutableStateOf(false) }

    if (dashboardOpen) {
        DesktopSelectionDashboard(
            state = selectionState,
            onBack = { dashboardOpen = false },
            onToggleExcludedWord = onToggleExcludedWord,
            onSaveSelection = onSaveSelection,
        )
    } else {
        val selectedDeck = snapshot.decks.firstOrNull { it.id == selectedDeckId }
        val deckWordIds = selectedDeck?.let { deck ->
            snapshot.deckWords.filter { it.deckId == deck.id }.map { it.wordId }.toSet()
        }
        var query by remember(selectedDeckId) { mutableStateOf("") }
        val words = deckWordIds?.let { ids -> snapshot.words.filter { it.id in ids } } ?: snapshot.words
        val normalizedQuery = query.trim().lowercase()
        val filteredWords = if (normalizedQuery.isBlank()) words else words.filter {
            it.spelling.lowercase().contains(normalizedQuery) || it.definitionCn.contains(query.trim())
        }
        Row(Modifier.fillMaxSize().padding(30.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (selectedDeck == null) "词库" else selectedDeck.name,
                            style = MaterialTheme.typography.h4, fontWeight = FontWeight.Bold)
                        Text(
                            if (selectedDeck == null) "原始词库与个人词库分开管理；自动选词范围在仪表盘中设置。"
                            else "${filteredWords.size} / ${words.size} 个词条 · 点击单词查看例句、助记和相关词",
                            color = DesktopPalette.muted,
                        )
                    }
                    if (selectedDeck != null) {
                        if (selectedDeck.sourceType != DeckSourceType.BUILTIN) {
                            OutlinedButton(onClick = { addWordDialogOpen = true }) { Text("添加单词") }
                            OutlinedButton(onClick = { bulkDialogOpen = true }) { Text("批量添加") }
                            TextButton(onClick = { deleteDeckDialogOpen = true }) { Text("删除词库") }
                        }
                        TextButton(onClick = { selectedDeckId = null }) { Text("← 返回词库") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { createDialogOpen = true }) { Text("新建词库") }
                    OutlinedButton(onClick = { dashboardOpen = true }) { Text("选词仪表盘") }
                }
                if (selectedDeck == null) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(selectionState.decks, key = { it.id }) {
                            deck -> DeckCard(deck, snapshot.words.size) { selectedDeckId = deck.id }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索单词或中文释义") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    DesktopPanel(Modifier.fillMaxWidth().weight(1f)) {
                        if (filteredWords.isEmpty()) Text("没有匹配词条", color = DesktopPalette.muted)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(filteredWords, key = { it.id }) { word ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onSelectWord(word.id) }.padding(vertical = 11.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(word.spelling, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(word.partOfSpeech.ifBlank { "词义" }, style = MaterialTheme.typography.caption, color = DesktopPalette.teal)
                                            word.phonetic?.takeIf(String::isNotBlank)?.let {
                                                Text(it, style = MaterialTheme.typography.caption, color = DesktopPalette.muted)
                                            }
                                        }
                                        Text(word.definitionCn, style = MaterialTheme.typography.body2, color = DesktopPalette.muted, maxLines = 1)
                                    }
                                    Text("查看 →", color = DesktopPalette.teal)
                                }
                            }
                        }
                    }
                }
            }
            if (selectedDetails != null) {
                DesktopWordDetailsPanel(
                    details = selectedDetails,
                    onAddWordToToday = onAddWordToToday,
                    deckEditable = selectedDeck?.sourceType != DeckSourceType.BUILTIN,
                    onUpdateWord = selectedDeck?.let { deck -> { input -> onUpdateWord(deck.id, selectedDetails.word.id, input) } },
                    onDeleteWord = selectedDeck?.let { deck -> { onDeleteWord(deck.id, selectedDetails.word.id) } },
                    onAddExample = { sentenceEn, sentenceCn, source -> onAddExample(selectedDetails.word.id, sentenceEn, sentenceCn, source) },
                    onUpdateExample = onUpdateExample,
                    onDeleteExample = onDeleteExample,
                    onSpeakWord = onSpeakWord,
                    speechVoices = speechVoices,
                    onSpeakWordWithVoice = onSpeakWordWithVoice,
                    onSpeakExamples = onSpeakExamples,
                    allowAddCurrentWord = true,
                    modifier = Modifier.width(360.dp).fillMaxHeight(),
                )
            }
        }
    }

    if (createDialogOpen) {
        AlertDialog(
            onDismissRequest = { createDialogOpen = false },
            title = { Text("新建个人词库") },
            text = {
                OutlinedTextField(value = deckName, onValueChange = { deckName = it }, label = { Text("词库名称") }, singleLine = true)
            },
            confirmButton = {
                Button(enabled = deckName.isNotBlank(), onClick = {
                    onCreateUserDeck(deckName.trim())
                    deckName = ""
                    createDialogOpen = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { createDialogOpen = false }) { Text("取消") } },
        )
    }
    val activeDeck = snapshot.decks.firstOrNull { it.id == selectedDeckId }
    if (bulkDialogOpen && activeDeck != null) {
        DesktopBulkAddDialog(
            onDismiss = { bulkDialogOpen = false },
            onSave = { value, sources ->
                onBatchAddWords(activeDeck.id, value, sources)
                bulkDialogOpen = false
            },
            onImportFile = { sources ->
                onImportIntoDeck(activeDeck.id, sources)
                bulkDialogOpen = false
            },
        )
    }
    if (addWordDialogOpen && activeDeck != null) {
        DesktopAddWordDialog(
            onDismiss = { addWordDialogOpen = false },
            onSave = { input ->
                onAddWord(activeDeck.id, input)
                addWordDialogOpen = false
            },
        )
    }
    if (deleteDeckDialogOpen && activeDeck != null) {
        AlertDialog(
            onDismissRequest = { deleteDeckDialogOpen = false },
            title = { Text("删除“${activeDeck.name}”？") },
            text = { Text("这会移除整个词库及其中的词条关联。已经产生的学习记录会保留。") },
            confirmButton = {
                Button(onClick = {
                    onDeleteDeck(activeDeck.id)
                    selectedDeckId = null
                    deleteDeckDialogOpen = false
                }) { Text("删除词库") }
            },
            dismissButton = { TextButton(onClick = { deleteDeckDialogOpen = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DesktopBulkAddDialog(
    onDismiss: () -> Unit,
    onSave: (String, BatchImportSources) -> Unit,
    onImportFile: (BatchImportSources) -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    var definitionSource by remember { mutableStateOf("") }
    var exampleSource by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    val preview = remember(content) {
        DefaultWordImportParser().parseForUserDeck(content.byteInputStream(), ImportFormat.TXT)
    }
    val sources = BatchImportSources(definitionSource, exampleSource)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step == 0) "先填写本批来源" else "批量添加单词") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step == 0) {
                    Text("这两项各填一次，会分别用于本批所有单词的释义和例句。")
                    OutlinedTextField(definitionSource, { definitionSource = it }, label = { Text("单词释义来源") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(exampleSource, { exampleSource = it }, label = { Text("例句来源") }, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("每行一个单词，严格按以下 7 列排列，列与列之间按 Tab 键。空列也要保留 Tab；不需要在每行重复填写来源。")
                    SelectionContainer { Text("单词\t音标\t词性\t中文释义\t英文例句\t例句翻译\t助记") }
                    Text("例如：productivity⇥[ˌproʊdʌkˈtɪvəti]⇥n.⇥生产强度⇥High productivity matters.⇥高生产强度很重要。⇥记住产出效率\n这里的 ⇥ 表示一个 Tab，不能用空格代替。")
                    OutlinedTextField(content, { content = it }, label = { Text("在这里粘贴多行单词") }, modifier = Modifier.fillMaxWidth(), maxLines = 12)
                    Text("可添加 ${preview.rows.size} 条" +
                        (preview.errors.firstOrNull()?.let { "；第 ${it.rowNumber} 行：${it.message}" } ?: ""),
                        color = if (preview.errors.isEmpty()) DesktopPalette.teal else MaterialTheme.colors.error)
                }
            }
        },
        confirmButton = {
            if (step == 0) Button(onClick = { step = 1 }) { Text("下一步") }
            else Button(onClick = { onSave(content, sources) }, enabled = preview.rows.isNotEmpty() && preview.errors.isEmpty()) { Text("添加") }
        },
        dismissButton = {
            Row {
                if (step == 1) {
                    TextButton(onClick = { step = 0 }) { Text("上一步") }
                    TextButton(onClick = { onImportFile(sources) }) { Text("从文件导入") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun DesktopAddWordDialog(onDismiss: () -> Unit, onSave: (PersonalWordInput) -> Unit) {
    var spelling by remember { mutableStateOf("") }
    var phonetic by remember { mutableStateOf("") }
    var partOfSpeech by remember { mutableStateOf("") }
    var definition by remember { mutableStateOf("") }
    var definitionSource by remember { mutableStateOf("") }
    var exampleEn by remember { mutableStateOf("") }
    var exampleCn by remember { mutableStateOf("") }
    var exampleSource by remember { mutableStateOf("") }
    var mnemonic by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加单词") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                OutlinedTextField(spelling, { spelling = it }, label = { Text("单词（必填）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phonetic, { phonetic = it }, label = { Text("音标") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(partOfSpeech, { partOfSpeech = it }, label = { Text("词性") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(definition, { definition = it }, label = { Text("中文释义") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(definitionSource, { definitionSource = it }, label = { Text("释义来源") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(exampleEn, { exampleEn = it }, label = { Text("英文例句") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(exampleCn, { exampleCn = it }, label = { Text("例句翻译") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(exampleSource, { exampleSource = it }, label = { Text("例句来源") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mnemonic, { mnemonic = it }, label = { Text("助记") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = spelling.isNotBlank(), onClick = {
                onSave(PersonalWordInput(spelling, definition, partOfSpeech, phonetic,
                    examples = if (exampleEn.isBlank()) emptyList() else listOf(PersonalExampleInput(exampleEn, exampleCn, exampleSource)),
                    definitionSource = definitionSource, mnemonic = mnemonic))
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeckCard(deck: DesktopDeckSummary, totalWordCount: Int, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(deck.name, style = MaterialTheme.typography.h6, fontWeight = FontWeight.SemiBold)
                Text(if (deck.isEditable) "可编辑" else "内置只读", color = DesktopPalette.teal)
            }
            val share = if (totalWordCount > 0) " · 总库占比 ${"%.1f".format(deck.wordCount * 100.0 / totalWordCount)}%" else ""
            Text("${deck.wordCount} 个词$share · 待复习 ${deck.dueCount} · 新词 ${deck.newCount}", color = DesktopPalette.muted)
            Text(if (deck.sourceType == DeckSourceType.BUILTIN) "官方词库" else "个人/导入词库",
                style = MaterialTheme.typography.caption, color = DesktopPalette.muted)
        }
    }
}

@Composable
private fun DesktopSelectionDashboard(
    state: DesktopSelectionDashboardState,
    onBack: () -> Unit,
    onToggleExcludedWord: (Long) -> Unit,
    onSaveSelection: (WordPoolSelection) -> Unit,
) {
    val allDeckIds = state.decks.mapTo(linkedSetOf()) { it.id }
    var draft by remember(state.selection, state.deckWeights, allDeckIds) {
        mutableStateOf(
            WordPoolDraft.from(
                selection = state.selection.copy(deckWeights = state.deckWeights),
                allDeckIds = allDeckIds,
            ),
        )
    }
    Column(Modifier.fillMaxSize().padding(30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回词库") }
            Text("选词仪表盘", style = MaterialTheme.typography.h4, fontWeight = FontWeight.Bold)
        }
        Text("这里管理自动选新词的范围，不会修改原始词库。到期复习仍按单词的复习状态执行。", color = DesktopPalette.muted)
        DesktopPanel(Modifier.fillMaxWidth()) {
            Text(if (draft.selectedDeckIds == allDeckIds) "待保存组合：全部词库"
                else "待保存组合：${draft.selectedDeckIds.size} 个词库", style = MaterialTheme.typography.h6)
            Text("已保存候选 ${state.candidateCount} 个 · 待复习 ${state.dueCount} 个 · 新词 ${state.newCount} 个",
                color = DesktopPalette.muted, modifier = Modifier.padding(top = 8.dp))
            Text("排除单词 ${state.selection.excludedWordIds.size} 个", color = DesktopPalette.muted,
                modifier = Modifier.padding(top = 4.dp))
            OutlinedButton(onClick = { draft = draft.selectAllDecks() }, enabled = draft.selectedDeckIds != allDeckIds,
                modifier = Modifier.padding(top = 10.dp)) { Text("恢复全部词库") }
        }
        Text("选择自动选词词库", style = MaterialTheme.typography.h6)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.decks, key = { it.id }) { deck ->
                val selected = deck.id in draft.selectedDeckIds
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.weight(1f).clickable { draft = draft.toggleDeck(deck.id) },
                            ) {
                                Text(deck.name, fontWeight = FontWeight.SemiBold)
                                Text("${deck.wordCount} 个词 · ${if (deck.isEditable) "可编辑" else "内置只读"}", color = DesktopPalette.muted)
                            }
                            if (selected) {
                                OutlinedTextField(
                                    value = draft.weightInputs[deck.id].orEmpty(),
                                    onValueChange = { draft = draft.withWeightInput(deck.id, it) },
                                    label = { Text("占比（%）") },
                                    singleLine = true,
                                    modifier = Modifier.width(132.dp),
                                )
                            }
                            Text(
                                if (selected) "已选中" else "未选中",
                                color = if (selected) DesktopPalette.teal else DesktopPalette.muted,
                            )
                        }
                    }
                }
            }
            item {
                DesktopPanel(Modifier.fillMaxWidth()) {
                    Text("占比合计：${draft.totalPercent}%", style = MaterialTheme.typography.h6)
                    draft.validationMessage?.let {
                        Text(it, color = MaterialTheme.colors.error, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (draft.canSave && draft.hasUnsavedChanges) {
                        Text("修改尚未保存", color = DesktopPalette.teal, modifier = Modifier.padding(top = 6.dp))
                    }
                    Button(
                        onClick = { draft.toSelectionOrNull()?.let(onSaveSelection) },
                        enabled = draft.canSave && draft.hasUnsavedChanges && !state.isSaving,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) { Text(if (state.isSaving) "正在保存…" else "保存选词设置") }
                    state.saveError?.let {
                        Text(it, color = MaterialTheme.colors.error, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (state.excludedWords.isNotEmpty()) {
                item { Text("已排除单词", style = MaterialTheme.typography.h6, modifier = Modifier.padding(top = 12.dp)) }
                items(state.excludedWords, key = { it.id }) { word ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(word.spelling)
                            Text(word.definitionCn, color = DesktopPalette.muted, maxLines = 1)
                        }
                        TextButton(onClick = { onToggleExcludedWord(word.id) }) { Text("恢复") }
                    }
                }
            }
        }
    }
}
