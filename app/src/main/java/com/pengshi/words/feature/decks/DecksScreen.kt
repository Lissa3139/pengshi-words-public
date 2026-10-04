package com.pengshi.words.feature.decks

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.pengshi.words.model.DeckSourceType
import com.pengshi.words.model.PersonalWordInput
import com.pengshi.words.model.BatchImportSources
import com.pengshi.words.importer.DefaultWordImportParser
import com.pengshi.words.model.ImportFormat
import com.pengshi.words.model.normalizeDictionaryText
import com.pengshi.words.speech.SpeechVoiceOption

data class DeckSummary(
    val name: String,
    val wordCount: Int,
    val dueCount: Int,
    val id: Long = 0,
    val newCount: Int = 0,
    val totalWordCount: Int = 0,
    val sourceType: DeckSourceType = DeckSourceType.IMPORTED,
    val isEditable: Boolean = sourceType != DeckSourceType.BUILTIN,
) {
    val corpusSharePercent: Double
        get() = if (totalWordCount > 0) wordCount * 100.0 / totalWordCount else 0.0
}

data class WordListItemUi(
    val spelling: String,
    val phonetic: String?,
    val partOfSpeech: String,
    val definitionCn: String,
    val definitionSource: String = com.pengshi.words.model.SOURCE_UNVERIFIED,
    val mnemonic: String = "",
    val id: Long = 0,
    val deckId: Long? = null,
    val deckName: String? = null,
    val examples: List<WordExampleUi> = emptyList(),
    val senses: List<com.pengshi.words.model.WordSense> = emptyList(),
)

data class WordExampleUi(
    val sentenceEn: String,
    val sentenceCn: String?,
    val id: Long = 0,
    val source: String = com.pengshi.words.model.SOURCE_UNVERIFIED,
)

@Composable
fun DecksScreen(words: List<WordListItemUi>) {
    DecksScreen(decks = emptyList(), words = words)
}

@Composable
fun DecksScreen(
    decks: List<DeckSummary>,
    words: List<WordListItemUi>,
    resetToken: Int = 0,
    isLoadingDecks: Boolean = false,
    isLoadingWords: Boolean = false,
    createDeckMessage: String? = null,
    addWordMessage: String? = null,
    onCreateUserDeck: (String) -> Unit = {},
    onDeleteUserDeck: (Long) -> Unit = {},
    onAddWordToDeck: (Long, PersonalWordInput) -> Unit = { _, _ -> },
    onUpdateWordInDeck: (Long, Long, PersonalWordInput) -> Unit = { _, _, _ -> },
    onDeleteWordFromDeck: (Long, Long) -> Unit = { _, _ -> },
    onBulkAddWordsToDeck: (Long, String, BatchImportSources) -> Unit = { _, _, _ -> },
    onImportWordsToDeck: (Long, Uri, BatchImportSources) -> Unit = { _, _, _ -> },
    onOpenDeck: (Long) -> Unit = {},
    onAddWordToToday: (Long) -> Unit = {},
    onOpenSelectionDashboard: () -> Unit = {},
    manualExampleMessage: String? = null,
    onAddExample: (Long, String, String?, String) -> Unit = { _, _, _, _ -> },
    onUpdateExample: (Long, String, String?, String) -> Unit = { _, _, _, _ -> },
    onDeleteExample: (Long) -> Unit = {},
    speechVoices: List<SpeechVoiceOption> = emptyList(),
    onSpeakWordWithVoice: (String, SpeechVoiceOption) -> Unit = { _, _ -> },
) {
    var selectedDeckId by rememberSaveable { mutableStateOf<Long?>(null) }
    var createDeckDialogOpen by rememberSaveable { mutableStateOf(false) }
    var deleteDeckDialogOpen by rememberSaveable { mutableStateOf(false) }
    var deckName by rememberSaveable { mutableStateOf("") }
    var batchDefinitionSource by rememberSaveable { mutableStateOf("") }
    var batchExampleSource by rememberSaveable { mutableStateOf("") }
    val batchSources = BatchImportSources(batchDefinitionSource, batchExampleSource)
    val importWordsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedDeckId?.let { deckId -> onImportWordsToDeck(deckId, it, batchSources) } }
    }
    LaunchedEffect(resetToken) { selectedDeckId = null }
    val selectedDeck = decks.firstOrNull { it.id == selectedDeckId }
    BackHandler(enabled = selectedDeck != null) { selectedDeckId = null }
    if (selectedDeck == null) {
        DeckLibrary(
            decks = decks,
            isLoading = decks.isEmpty() && isLoadingDecks,
            createDeckMessage = createDeckMessage,
            onOpenDeck = { selectedDeckId = it; onOpenDeck(it) },
            onOpenCreateDeckDialog = { createDeckDialogOpen = true },
            onOpenSelectionDashboard = onOpenSelectionDashboard,
        )
    } else {
        DeckDetail(
            deck = selectedDeck,
            words = words.filter { it.deckId == selectedDeck.id },
            isLoadingWords = isLoadingWords,
            addWordMessage = addWordMessage,
            onAddWordToDeck = onAddWordToDeck,
            onDeleteDeck = { deleteDeckDialogOpen = true },
            onUpdateWordInDeck = onUpdateWordInDeck,
            onDeleteWordFromDeck = onDeleteWordFromDeck,
            onBulkAddWordsToDeck = onBulkAddWordsToDeck,
            batchDefinitionSource = batchDefinitionSource,
            onBatchDefinitionSourceChange = { batchDefinitionSource = it },
            batchExampleSource = batchExampleSource,
            onBatchExampleSourceChange = { batchExampleSource = it },
            onImportWordsFromFile = { importWordsLauncher.launch(arrayOf("text/*", "text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) },
            manualExampleMessage = manualExampleMessage,
            onAddExample = onAddExample,
            onUpdateExample = onUpdateExample,
            onDeleteExample = onDeleteExample,
            onBack = { selectedDeckId = null },
            speechVoices = speechVoices,
            onSpeakWordWithVoice = onSpeakWordWithVoice,
            onAddWordToToday = onAddWordToToday,
        )
    }
    if (createDeckDialogOpen) {
        AlertDialog(
            onDismissRequest = { createDeckDialogOpen = false },
            title = { Text("新建词库") },
            text = {
                OutlinedTextField(
                    value = deckName,
                    onValueChange = { deckName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("词库名称") },
                )
            },
            confirmButton = {
                Button(
                    enabled = deckName.isNotBlank(),
                    onClick = {
                        onCreateUserDeck(deckName)
                        deckName = ""
                        createDeckDialogOpen = false
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { createDeckDialogOpen = false }) { Text("取消") } },
        )
    }
    if (deleteDeckDialogOpen && selectedDeck != null) {
        AlertDialog(
            onDismissRequest = { deleteDeckDialogOpen = false },
            title = { Text("删除“${selectedDeck.name}”？") },
            text = { Text("这会移除整个词库及其中的词条关联。已经产生的学习记录会保留。") },
            confirmButton = {
                Button(onClick = {
                    onDeleteUserDeck(selectedDeck.id)
                    selectedDeckId = null
                    deleteDeckDialogOpen = false
                }) { Text("删除词库") }
            },
            dismissButton = { TextButton(onClick = { deleteDeckDialogOpen = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DeckLibrary(
    decks: List<DeckSummary>,
    isLoading: Boolean,
    createDeckMessage: String?,
    onOpenDeck: (Long) -> Unit,
    onOpenCreateDeckDialog: () -> Unit,
    onOpenSelectionDashboard: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("词库", style = MaterialTheme.typography.headlineMedium)
        Text("这里管理实际存在的原始词库；自动选词范围请在仪表盘中设置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onOpenCreateDeckDialog, modifier = Modifier.weight(1f)) { Text("新建词库") }
            OutlinedButton(onClick = onOpenSelectionDashboard, modifier = Modifier.weight(1f)) { Text("选词仪表盘") }
        }
        createDeckMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (decks.isEmpty() && isLoading) {
            Text("正在读取本地词库…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (decks.isEmpty()) {
            Text("还没有词库，请先新建或在设置中导入本地词库。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(decks, key = { it.id }) { deck ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenDeck(deck.id) }) {
                        Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(deck.name, style = MaterialTheme.typography.titleLarge)
                                Text(if (deck.isEditable) "可编辑" else "内置只读", color = MaterialTheme.colorScheme.primary)
                            }
                            val coverage = if (deck.totalWordCount > 0) " · 总库占比 ${"%.1f".format(deck.corpusSharePercent)}%" else ""
                            Text("${deck.wordCount} 个词$coverage · 待复习 ${deck.dueCount} · 新词 ${deck.newCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckDetail(
    deck: DeckSummary,
    words: List<WordListItemUi>,
    isLoadingWords: Boolean,
    addWordMessage: String?,
    onAddWordToDeck: (Long, PersonalWordInput) -> Unit,
    onDeleteDeck: () -> Unit,
    onUpdateWordInDeck: (Long, Long, PersonalWordInput) -> Unit,
    onDeleteWordFromDeck: (Long, Long) -> Unit,
    onBulkAddWordsToDeck: (Long, String, BatchImportSources) -> Unit,
    batchDefinitionSource: String,
    onBatchDefinitionSourceChange: (String) -> Unit,
    batchExampleSource: String,
    onBatchExampleSourceChange: (String) -> Unit,
    onImportWordsFromFile: () -> Unit,
    manualExampleMessage: String?,
    onAddExample: (Long, String, String?, String) -> Unit,
    onUpdateExample: (Long, String, String?, String) -> Unit,
    onDeleteExample: (Long) -> Unit,
    onBack: () -> Unit,
    speechVoices: List<SpeechVoiceOption>,
    onSpeakWordWithVoice: (String, SpeechVoiceOption) -> Unit,
    onAddWordToToday: (Long) -> Unit,
) {
    var selectedWordId by rememberSaveable(deck.id) { mutableStateOf<Long?>(null) }
    var addWordDialogOpen by rememberSaveable("library-add-word-dialog-v2", deck.id) { mutableStateOf(false) }
    var bulkDialogOpen by rememberSaveable("library-bulk-word-dialog-v1", deck.id) { mutableStateOf(false) }
    val selectedWord = words.firstOrNull { it.id == selectedWordId }
    BackHandler(enabled = selectedWordId != null) { selectedWordId = null }
    if (selectedWord != null) {
        WordDetail(
            word = selectedWord,
            manualExampleMessage = manualExampleMessage,
            onAddExample = onAddExample,
            onUpdateExample = onUpdateExample,
            onDeleteExample = onDeleteExample,
            onBack = { selectedWordId = null },
            deckEditable = deck.isEditable,
            onUpdateWord = { input -> onUpdateWordInDeck(deck.id, selectedWord.id, input) },
            onDeleteWord = { onDeleteWordFromDeck(deck.id, selectedWord.id); selectedWordId = null },
            speechVoices = speechVoices,
            onSpeakWordWithVoice = { voice -> onSpeakWordWithVoice(selectedWord.englishSpelling(), voice) },
            onAddWordToToday = { onAddWordToToday(selectedWord.id) },
        )
        return
    }
    var query by rememberSaveable(deck.id) { mutableStateOf("") }
    val normalized = query.trim().lowercase()
    val filtered = if (normalized.isBlank()) words else words.filter {
        it.spelling.lowercase().contains(normalized) || it.definitionCn.contains(query.trim()) ||
            it.senses.any { sense -> sense.definitionCn.contains(query.trim()) }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Column(modifier = Modifier.weight(1f)) {
                Text(deck.name, style = MaterialTheme.typography.headlineSmall)
                Text("${words.size} 个词条", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (deck.isEditable) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { bulkDialogOpen = true }) { Text("批量添加") }
                OutlinedButton(onClick = { addWordDialogOpen = true }) { Text("添加单词") }
                TextButton(onClick = onDeleteDeck) { Text("删除词库") }
            }
        }
        addWordMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索单词或中文释义") },
        )
        if (filtered.isEmpty() && isLoadingWords) {
            Text("正在读取此词库…", modifier = Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (filtered.isEmpty()) {
            Text("没有匹配词条", modifier = Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(filtered, key = { if (it.id != 0L) it.id else it.spelling }) { word ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { selectedWordId = word.id }) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(word.spelling, style = MaterialTheme.typography.titleMedium)
                                Text(word.partOfSpeech, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            word.phonetic?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(word.definitionCn, style = MaterialTheme.typography.bodySmall)
                            word.senses.forEach { sense ->
                                Text("${sense.partOfSpeech.takeIf(String::isNotBlank)?.let { "$it · " }.orEmpty()}${sense.definitionCn}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    if (addWordDialogOpen) {
        AddWordDialog(
            resetKey = deck.id,
            onDismiss = { addWordDialogOpen = false },
            onSave = { input ->
                onAddWordToDeck(deck.id, input)
                addWordDialogOpen = false
            },
        )
    }
    if (bulkDialogOpen) {
        BulkAddWordsDialog(
            definitionSource = batchDefinitionSource,
            onDefinitionSourceChange = onBatchDefinitionSourceChange,
            exampleSource = batchExampleSource,
            onExampleSourceChange = onBatchExampleSourceChange,
            onImportFile = { onImportWordsFromFile(); bulkDialogOpen = false },
            onDismiss = { bulkDialogOpen = false },
            onSave = { text ->
                onBulkAddWordsToDeck(deck.id, text, BatchImportSources(batchDefinitionSource, batchExampleSource))
                bulkDialogOpen = false
            },
        )
    }
}

@Composable
private fun AddWordDialog(
    resetKey: Long,
    onDismiss: () -> Unit,
    onSave: (PersonalWordInput) -> Unit,
) {
    var spelling by rememberSaveable("library-add-word-spelling-v2", resetKey) { mutableStateOf("") }
    var definitionCn by rememberSaveable("library-add-word-definition-v2", resetKey) { mutableStateOf("") }
    var partOfSpeech by rememberSaveable("library-add-word-pos-v2", resetKey) { mutableStateOf("") }
    var phonetic by rememberSaveable("library-add-word-phonetic-v2", resetKey) { mutableStateOf("") }
    var definitionSource by rememberSaveable("library-add-word-source-v1", resetKey) { mutableStateOf("") }
    var mnemonic by rememberSaveable("library-add-word-mnemonic-v1", resetKey) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加单词") },
        text = {
            Column(modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(spelling, { spelling = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("单词（必填）") })
                OutlinedTextField(definitionCn, { definitionCn = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("中文释义（可选）") })
                OutlinedTextField(partOfSpeech, { partOfSpeech = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("词性（可选）") })
                OutlinedTextField(phonetic, { phonetic = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("音标（可选）") })
                OutlinedTextField(definitionSource, { definitionSource = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("释义来源（如词典、书名）") })
                OutlinedTextField(mnemonic, { mnemonic = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("助记（可选）") })
            }
        },
        confirmButton = {
            Button(enabled = spelling.isNotBlank(), onClick = { onSave(PersonalWordInput(spelling, definitionCn, partOfSpeech, phonetic,
                definitionSource = definitionSource, mnemonic = mnemonic)) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BulkAddWordsDialog(
    definitionSource: String,
    onDefinitionSourceChange: (String) -> Unit,
    exampleSource: String,
    onExampleSourceChange: (String) -> Unit,
    onImportFile: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable("library-bulk-word-text-v1") { mutableStateOf("") }
    var step by rememberSaveable("library-bulk-word-step-v2") { mutableStateOf(0) }
    val preview = androidx.compose.runtime.remember(text) {
        DefaultWordImportParser().parseForUserDeck(text.byteInputStream(), ImportFormat.TXT)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step == 0) "先填写本批来源" else "批量添加单词") },
        text = {
            Column(modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step == 0) {
                    Text("这两项各填一次，会分别用于本批所有单词的释义和例句。")
                    OutlinedTextField(definitionSource, onDefinitionSourceChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("单词释义来源") })
                    OutlinedTextField(exampleSource, onExampleSourceChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("例句来源") })
                } else {
                    Text("每行一个单词，按 7 列排列，列间用 Tab。空列也保留 Tab；不要用空格代替。")
                    SelectionContainer { Text("单词\t音标\t词性\t中文释义\t英文例句\t例句翻译\t助记") }
                    Text("例如：productivity⇥[ˌproʊdʌkˈtɪvəti]⇥n.⇥生产强度⇥High productivity matters.⇥高生产强度很重要。⇥记住产出效率\n⇥ 表示 Tab。")
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 8,
                        maxLines = 14,
                        label = { Text("粘贴多行单词") },
                    )
                    Text("可添加 ${preview.rows.size} 条" +
                        (preview.errors.firstOrNull()?.let { "；第 ${it.rowNumber} 行：${it.message}" } ?: ""),
                        color = if (preview.errors.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    TextButton(onClick = onImportFile) { Text("从 TXT / CSV / XLSX 文件导入") }
                }
            }
        },
        confirmButton = {
            if (step == 0) Button(onClick = { step = 1 }) { Text("下一步") }
            else Button(enabled = preview.rows.isNotEmpty() && preview.errors.isEmpty(), onClick = { onSave(text) }) { Text("添加") }
        },
        dismissButton = {
            Row {
                if (step == 1) {
                    TextButton(onClick = { step = 0 }) { Text("上一步") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun WordDetail(
    word: WordListItemUi,
    manualExampleMessage: String?,
    onAddExample: (Long, String, String?, String) -> Unit,
    onUpdateExample: (Long, String, String?, String) -> Unit,
    onDeleteExample: (Long) -> Unit,
    onBack: () -> Unit,
    deckEditable: Boolean,
    onUpdateWord: (PersonalWordInput) -> Unit,
    onDeleteWord: () -> Unit,
    speechVoices: List<SpeechVoiceOption>,
    onSpeakWordWithVoice: (SpeechVoiceOption) -> Unit,
    onAddWordToToday: () -> Unit,
) {
    var exampleDialogOpen by rememberSaveable(word.id) { mutableStateOf(false) }
    var editingExample by rememberSaveable("edit-example-id-v1", word.id) { mutableStateOf<Long?>(null) }
    var deletingExampleId by rememberSaveable("delete-example-id-v1", word.id) { mutableStateOf<Long?>(null) }
    var editDialogOpen by rememberSaveable("edit-word-dialog-v1", word.id) { mutableStateOf(false) }
    var deleteDialogOpen by rememberSaveable("delete-word-dialog-v1", word.id) { mutableStateOf(false) }
    var sentenceEn by rememberSaveable(word.id) { mutableStateOf("") }
    var sentenceCn by rememberSaveable(word.id) { mutableStateOf("") }
    var sentenceSource by rememberSaveable("new-example-source", word.id) { mutableStateOf("") }
    var addedToToday by rememberSaveable(word.id) { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 返回") }
                Column(modifier = Modifier.weight(1f)) {
                    Text(word.spelling, style = MaterialTheme.typography.headlineMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(word.partOfSpeech, color = MaterialTheme.colorScheme.primary)
                        word.phonetic?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (deckEditable) {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { editDialogOpen = true }) { Text("编辑") }
                        TextButton(onClick = { deleteDialogOpen = true }) { Text("删除") }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { onAddWordToToday(); addedToToday = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (addedToToday) "已加入今日记单词 ✓" else "加入今日记单词")
                }
                if (addedToToday) Text("该词已加入今日额外学习队列，不占 30 词额度。", color = MaterialTheme.colorScheme.primary)
            }
        }
        if (speechVoices.isNotEmpty()) {
            item {
                WordDetailVoiceOptions(speechVoices, onSpeakWordWithVoice)
            }
        }
        item {
            if (word.mnemonic.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .45f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("助记", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(word.mnemonic, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("词义", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("释义来源：${word.definitionSource}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    word.definitionCn.normalizeDictionaryText()
                        .split(Regex("\\r?\\n|[；;]"))
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .forEachIndexed { index, meaning -> Text("${index + 1}. $meaning") }
                    word.senses.forEach { sense ->
                        Text("${sense.partOfSpeech.takeIf(String::isNotBlank)?.let { "$it · " }.orEmpty()}${sense.definitionCn}")
                        Text("释义来源：${sense.definitionSource}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Column {
                Text("例句", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
            }
        }
        item {
            OutlinedButton(onClick = { exampleDialogOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("补充例句和翻译") }
        }
        manualExampleMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
        if (word.examples.isEmpty()) {
            item {
                Text("暂无例句。可以在这里补充英文例句和中文翻译。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            }
        } else {
            items(word.examples) { example ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(example.sentenceEn, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (deckEditable) {
                            TextButton(onClick = { editingExample = example.id }) { Text("编辑") }
                            TextButton(onClick = { deletingExampleId = example.id }) { Text("删除") }
                        }
                    }
                    example.sentenceCn?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("例句来源：${example.source}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (exampleDialogOpen) {
        AlertDialog(
            onDismissRequest = { exampleDialogOpen = false },
            title = { Text("补充例句") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(sentenceEn, { sentenceEn = it }, modifier = Modifier.fillMaxWidth(), label = { Text("英文例句（必填）") })
                    OutlinedTextField(sentenceCn, { sentenceCn = it }, modifier = Modifier.fillMaxWidth(), label = { Text("中文翻译（可选）") })
                    OutlinedTextField(sentenceSource, { sentenceSource = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("例句来源（如书名、网址）") })
                }
            },
            confirmButton = {
                Button(
                    enabled = sentenceEn.isNotBlank(),
                    onClick = {
                        onAddExample(word.id, sentenceEn, sentenceCn.ifBlank { null }, sentenceSource)
                        sentenceEn = ""
                        sentenceCn = ""
                        sentenceSource = ""
                        exampleDialogOpen = false
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { exampleDialogOpen = false }) { Text("取消") } },
        )
    }
    if (editDialogOpen) {
        EditWordDialog(
            word = word,
            onDismiss = { editDialogOpen = false },
            onSave = { input ->
                onUpdateWord(input)
                editDialogOpen = false
            },
        )
    }
    if (deleteDialogOpen) {
        AlertDialog(
            onDismissRequest = { deleteDialogOpen = false },
            title = { Text("从当前词库删除？") },
            text = { Text("只会移除“${word.spelling}”与当前词库的关联，不会删除其他词库或学习记录。") },
            confirmButton = {
                Button(onClick = { onDeleteWord(); deleteDialogOpen = false }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteDialogOpen = false }) { Text("取消") } },
        )
    }
    val editing = word.examples.firstOrNull { it.id == editingExample }
    if (editing != null) {
        EditExampleDialog(
            example = editing,
            onDismiss = { editingExample = null },
            onSave = { updatedEn, updatedCn, updatedSource ->
                onUpdateExample(editing.id, updatedEn, updatedCn, updatedSource)
                editingExample = null
            },
        )
    }
    val deleting = word.examples.firstOrNull { it.id == deletingExampleId }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deletingExampleId = null },
            title = { Text("删除例句？") },
            text = { Text(deleting.sentenceEn) },
            confirmButton = {
                Button(onClick = { onDeleteExample(deleting.id); deletingExampleId = null }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deletingExampleId = null }) { Text("取消") } },
        )
    }
}

private fun WordListItemUi.englishSpelling(): String = spelling

@Composable
internal fun WordDetailVoiceOptions(
    voices: List<SpeechVoiceOption>,
    onSpeak: (SpeechVoiceOption) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("播放音色", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            voices.forEach { voice ->
                OutlinedButton(
                    onClick = { onSpeak(voice) },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            voice.engineLabel.substringBefore(" · "),
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        voice.engineLabel.substringAfter(" · ", "").takeIf(String::isNotBlank)?.let { voiceType ->
                            Text(
                                voiceType,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditWordDialog(
    word: WordListItemUi,
    onDismiss: () -> Unit,
    onSave: (PersonalWordInput) -> Unit,
) {
    var definitionCn by rememberSaveable("edit-word-definition-v1", word.id) { mutableStateOf(word.definitionCn) }
    var partOfSpeech by rememberSaveable("edit-word-pos-v1", word.id) { mutableStateOf(word.partOfSpeech) }
    var phonetic by rememberSaveable("edit-word-phonetic-v1", word.id) { mutableStateOf(word.phonetic.orEmpty()) }
    var definitionSource by rememberSaveable("edit-word-source-v1", word.id) { mutableStateOf(word.definitionSource) }
    var mnemonic by rememberSaveable("edit-word-mnemonic-v1", word.id) { mutableStateOf(word.mnemonic) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 ${word.spelling}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(definitionCn, { definitionCn = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("中文释义") })
                OutlinedTextField(partOfSpeech, { partOfSpeech = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("词性") })
                OutlinedTextField(phonetic, { phonetic = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("音标") })
                OutlinedTextField(definitionSource, { definitionSource = it }, modifier = Modifier.fillMaxWidth(), label = { Text("释义来源") })
                OutlinedTextField(mnemonic, { mnemonic = it }, modifier = Modifier.fillMaxWidth(), label = { Text("助记") })
            }
        },
        confirmButton = {
            Button(onClick = { onSave(PersonalWordInput(word.spelling, definitionCn, partOfSpeech, phonetic,
                definitionSource = definitionSource, mnemonic = mnemonic)) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EditExampleDialog(
    example: WordExampleUi,
    onDismiss: () -> Unit,
    onSave: (String, String?, String) -> Unit,
) {
    var sentenceEn by rememberSaveable("edit-example-en-v1", example.id) { mutableStateOf(example.sentenceEn) }
    var sentenceCn by rememberSaveable("edit-example-cn-v1", example.id) { mutableStateOf(example.sentenceCn.orEmpty()) }
    var source by rememberSaveable("edit-example-source-v1", example.id) { mutableStateOf(example.source) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑例句") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sentenceEn, { sentenceEn = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("英文例句") })
                OutlinedTextField(sentenceCn, { sentenceCn = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("中文翻译") })
                OutlinedTextField(source, { source = it }, modifier = Modifier.fillMaxWidth(), label = { Text("例句来源") })
            }
        },
        confirmButton = {
            Button(enabled = sentenceEn.isNotBlank(), onClick = { onSave(sentenceEn, sentenceCn.ifBlank { null }, source) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
