package com.pengshi.words.feature.decks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pengshi.words.model.WordPoolDraft
import com.pengshi.words.model.WordPoolSelection

data class SelectionDashboardUiState(
    val wordPoolSelection: WordPoolSelection = WordPoolSelection(),
    val decks: List<DeckSummary> = emptyList(),
    val candidateCount: Int = 0,
    val dueCount: Int = 0,
    val newCount: Int = 0,
    val excludedWords: List<WordListItemUi> = emptyList(),
    val deckWeights: Map<Long, Int> = emptyMap(),
    val isSaving: Boolean = false,
    val saveError: String? = null,
)

@Composable
fun SelectionDashboardScreen(
    state: SelectionDashboardUiState,
    onBack: () -> Unit,
    onToggleExcludedWord: (Long) -> Unit,
    onSaveSelection: (WordPoolSelection) -> Unit,
) {
    val allDeckIds = state.decks.mapTo(linkedSetOf()) { it.id }
    var draft by remember(state.wordPoolSelection, state.deckWeights, allDeckIds) {
        mutableStateOf(
            WordPoolDraft.from(
                selection = state.wordPoolSelection.copy(deckWeights = state.deckWeights),
                allDeckIds = allDeckIds,
            ),
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 返回") }
                Text("选词仪表盘", style = MaterialTheme.typography.headlineSmall)
            }
        }
        item {
            Text(
                "这里管理自动选新词的范围，不会修改原始词库。到期复习仍按单词的复习状态执行。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (draft.selectedDeckIds == allDeckIds) "待保存组合：全部词库"
                        else "待保存组合：${draft.selectedDeckIds.size} 个词库",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("已保存候选 ${state.candidateCount} 个")
                    Text("已保存范围内：待复习 ${state.dueCount} 个 · 新词 ${state.newCount} 个")
                    Text("排除单词 ${state.wordPoolSelection.excludedWordIds.size} 个")
                    OutlinedButton(
                        onClick = { draft = draft.selectAllDecks() },
                        enabled = draft.selectedDeckIds != allDeckIds,
                    ) { Text("恢复全部词库") }
                }
            }
        }
        item { Text("选择自动选词词库", style = MaterialTheme.typography.titleMedium) }
        items(state.decks, key = { it.id }) { deck ->
            val selected = deck.id in draft.selectedDeckIds
            Card(
                modifier = Modifier.fillMaxWidth().clickable { draft = draft.toggleDeck(deck.id) },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(deck.name, style = MaterialTheme.typography.titleMedium)
                        Text("${deck.wordCount} 个词 · ${if (deck.isEditable) "可编辑" else "内置只读"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (selected) "已选中" else "未选中", color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (selected) {
                    OutlinedTextField(
                        value = draft.weightInputs[deck.id].orEmpty(),
                        onValueChange = { draft = draft.withWeightInput(deck.id, it) },
                        label = { Text("每日选词占比（%）") },
                        suffix = { Text("%") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("占比合计：${draft.totalPercent}%", style = MaterialTheme.typography.titleMedium)
                    draft.validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (draft.canSave && draft.hasUnsavedChanges) {
                        Text("修改尚未保存", color = MaterialTheme.colorScheme.tertiary)
                    }
                    Button(
                        onClick = { draft.toSelectionOrNull()?.let(onSaveSelection) },
                        enabled = draft.canSave && draft.hasUnsavedChanges && !state.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.isSaving) "正在保存…" else "保存选词设置") }
                }
            }
        }
        if (state.isSaving) item { Text("正在保存…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.saveError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        if (state.excludedWords.isNotEmpty()) {
            item { Text("已排除单词", style = MaterialTheme.typography.titleMedium) }
            items(state.excludedWords, key = { it.id }) { word ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(word.spelling)
                        Text(word.definitionCn, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onToggleExcludedWord(word.id) }) { Text("恢复") }
                }
            }
        }
    }
}
