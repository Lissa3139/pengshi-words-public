package com.pengshi.words.desktop.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pengshi.words.model.ManualWordCatalog

@Composable
fun DesktopNewWordChoiceDialog(remaining: Int, onAutomatic: () -> Unit, onManual: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("复习完成，选择今天的新词") },
        text = { Text("今天还可以安排 $remaining 个新词。自动选词会使用选词仪表盘的词库与比例；也可以自己跨词库挑选。") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onManual) { Text("自己挑选") }
                Button(onClick = onAutomatic) { Text("按仪表盘自动选词") }
            }
        },
        dismissButton = { TextButton(onClick = onLater) { Text("稍后再选") } },
    )
}

@Composable
fun DesktopManualNewWordDialog(
    catalog: ManualWordCatalog,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var deckId by remember { mutableStateOf(-1L) }
    var query by remember { mutableStateOf("") }
    var exact by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    val normalizedQuery = query.trim().lowercase()
    val visible = catalog.words.filter { option ->
        (deckId == -1L || deckId == -2L && option.word.id in selectedIds || deckId in option.deckIds) &&
            (normalizedQuery.isEmpty() || if (exact) option.word.spelling.equals(normalizedQuery, ignoreCase = true)
            else option.word.spelling.contains(normalizedQuery, ignoreCase = true))
    }
    val pageCount = ((visible.size + 49) / 50).coerceAtLeast(1)
    val safePage = page.coerceIn(0, pageCount - 1)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自己挑选今日新词") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("已选 ${selectedIds.size} / 今日可选 ${catalog.selectionLimit} · 词库候选 ${catalog.words.size} 个")
                TextField(query, onValueChange = { query = it; page = 0 }, label = { Text("搜索英文单词") }, singleLine = true)
                Row {
                    Checkbox(exact, onCheckedChange = { exact = it; page = 0 })
                    Text("完全匹配", Modifier.padding(top = 12.dp))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { deckId = -1L; page = 0 }) { Text("所有词库") }
                    OutlinedButton(onClick = { deckId = -2L; page = 0 }) { Text("已选") }
                    catalog.decks.forEach { deck ->
                        OutlinedButton(onClick = { deckId = deck.id; page = 0 }) { Text(deck.name) }
                    }
                }
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                    visible.drop(safePage * 50).take(50).forEach { option ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(option.word.spelling)
                                Text(option.word.definitionCn, maxLines = 2)
                            }
                            Checkbox(option.word.id in selectedIds,
                                enabled = option.word.id in selectedIds || selectedIds.size < catalog.selectionLimit,
                                onCheckedChange = { onToggle(option.word.id) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { page = (safePage - 1).coerceAtLeast(0) }, enabled = safePage > 0) { Text("上一页") }
                    Text("${safePage + 1} / $pageCount 页", Modifier.padding(top = 12.dp))
                    TextButton(onClick = { page = (safePage + 1).coerceAtMost(pageCount - 1) }, enabled = safePage < pageCount - 1) { Text("下一页") }
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = selectedIds.isNotEmpty()) { Text("开始学习所选单词") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后再选") } },
    )
}
