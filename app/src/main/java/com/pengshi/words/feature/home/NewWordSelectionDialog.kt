package com.pengshi.words.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import com.pengshi.words.model.ManualWordCatalog

@Composable
fun NewWordChoiceDialog(
    remaining: Int,
    onAutomatic: () -> Unit,
    onManual: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("复习完成，选择今天的新词") },
        text = { Text("今天还可以安排 $remaining 个新词。自动选词会使用选词仪表盘的词库与比例；也可以自己跨词库挑选。") },
        confirmButton = {
            Column {
                Button(onClick = onAutomatic) { Text("按仪表盘自动选词") }
                TextButton(onClick = onManual) { Text("自己挑选") }
            }
        },
        dismissButton = { TextButton(onClick = onLater) { Text("稍后再选") } },
    )
}

@Composable
fun ManualNewWordDialog(
    catalog: ManualWordCatalog,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var deckId by remember { mutableStateOf(-1L) }
    var query by remember { mutableStateOf("") }
    var exact by remember { mutableStateOf(false) }
    val normalizedQuery = query.trim().lowercase()
    val visible = remember(catalog, deckId, normalizedQuery, exact, selectedIds) {
        catalog.words.filter { option ->
            (deckId == -1L || deckId == -2L && option.word.id in selectedIds || deckId in option.deckIds) &&
                (normalizedQuery.isEmpty() || if (exact) option.word.spelling.equals(normalizedQuery, ignoreCase = true)
                else option.word.spelling.contains(normalizedQuery, ignoreCase = true))
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("自己挑选今日新词")
                Text("已选 ${selectedIds.size} / 今日可选 ${catalog.selectionLimit} · 词库候选 ${catalog.words.size} 个")
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索英文单词") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row {
                    Checkbox(checked = exact, onCheckedChange = { exact = it })
                    Text("完全匹配", modifier = Modifier.padding(top = 12.dp))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { deckId = -1L }) { Text("所有词库") }
                    OutlinedButton(onClick = { deckId = -2L }) { Text("已选") }
                    catalog.decks.forEach { deck ->
                        OutlinedButton(onClick = { deckId = deck.id }) { Text(deck.name) }
                    }
                }
                Text("当前显示 ${visible.size} 个候选词")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(visible, key = { it.word.id }) { option ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(option.word.spelling)
                                Text(option.word.definitionCn, maxLines = 2)
                            }
                            Checkbox(
                                checked = option.word.id in selectedIds,
                                enabled = option.word.id in selectedIds || selectedIds.size < catalog.selectionLimit,
                                onCheckedChange = { onToggle(option.word.id) },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("稍后再选") }
                    Button(onClick = onConfirm, enabled = selectedIds.isNotEmpty()) { Text("开始学习所选单词") }
                }
            }
        }
    }
}
