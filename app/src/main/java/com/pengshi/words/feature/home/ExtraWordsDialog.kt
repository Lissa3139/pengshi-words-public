package com.pengshi.words.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import com.pengshi.words.feature.decks.DeckSummary

fun parseRequestedExtraWords(text: String): Result<Int> {
    val value = text.trim().toLongOrNull()
        ?: return Result.failure(IllegalArgumentException("请输入正整数"))
    if (value <= 0) return Result.failure(IllegalArgumentException("请输入正整数"))
    if (value > Int.MAX_VALUE) return Result.failure(IllegalArgumentException("数量过大，超过当前可用词数"))
    return Result.success(value.toInt())
}

@Composable
fun ExtraWordsDeckDialog(
    decks: List<DeckSummary>,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择词库") },
        text = {
            if (decks.isEmpty()) {
                Text("当前没有可用词库。")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(decks, key = { it.id }) { deck ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { onSelect(deck.id) },
                                modifier = Modifier.fillMaxWidth().padding(2.dp),
                            ) {
                                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                                    Text(deck.name)
                                    Text("${deck.wordCount} 个词条", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ExtraWordsDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    title: String = "加入额外单词",
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("完成每日主任务后仍可继续添加，数量不受 30 个主任务限制。")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("自定义数量") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 20, 30).forEach { count ->
                        OutlinedButton(onClick = { text = count.toString(); error = null }) { Text("${count}词") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                parseRequestedExtraWords(text).fold(
                    onSuccess = onConfirm,
                    onFailure = { error = it.message },
                )
            }) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
