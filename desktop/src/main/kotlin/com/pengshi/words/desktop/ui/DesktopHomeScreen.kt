package com.pengshi.words.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.material.Button
import androidx.compose.material.AlertDialog
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pengshi.words.desktop.DesktopHomeState
import com.pengshi.words.desktop.DesktopStudyPhase
import com.pengshi.words.desktop.DesktopDeckSummary
import java.time.LocalDate
import java.time.YearMonth

val DesktopHomeState.progressLabels: List<String> get() = listOf(
    "复习 ${(reviewTotal - reviewCompleted).coerceAtLeast(0)}",
    "新词 ${(newTotal - newCompleted).coerceAtLeast(0)}" +
        if (phase == DesktopStudyPhase.CHOOSE_NEW) " · 待选择 ${minOf(remainingMainQuota, availableNewCount)}" else "",
)

val DesktopHomeState.remainingMainQuota: Int get() =
    (quota - reviewTotal - newTotal).coerceAtLeast(0)

@Composable
fun DesktopHomeScreen(
    state: DesktopHomeState,
    availableDecks: List<DesktopDeckSummary> = emptyList(),
    onStart: () -> Unit,
    onAddNewWords: (Int) -> Unit,
    onAppendExtraWords: (Int, Long) -> Unit = { _, _ -> },
) {
    var showExtraDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showExtraDeckDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var requestedExtraCount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    var extraText by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var extraError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(38.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        val logo = remember {
            DesktopAssets::class.java.getResourceAsStream("/pengshi_logo.png")?.use(::loadImageBitmap)
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            logo?.let {
                androidx.compose.foundation.Image(
                    bitmap = it,
                    contentDescription = "彭式背单词 logo",
                    modifier = Modifier.size(72.dp),
                    filterQuality = FilterQuality.High,
                )
            }
            Column {
                Text("彭式背单词", style = MaterialTheme.typography.h5, fontWeight = FontWeight.Bold)
                Text("记一词，志更远", color = DesktopPalette.muted)
            }
        }
        Text("今天，稳稳记住几个词。", style = MaterialTheme.typography.h4, fontWeight = FontWeight.Bold)
        Text("复习优先，完成后再进入新词。", color = DesktopPalette.muted)
        DesktopPanel(Modifier.fillMaxWidth()) {
            Text("今日任务", style = MaterialTheme.typography.h6, fontWeight = FontWeight.SemiBold)
            val progressTarget = maxOf(state.quota, state.plannedUniqueWordCount)
            Text("${state.completedUniqueWordCount} / $progressTarget 个单词",
                style = MaterialTheme.typography.h3,
                modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Text("每日额度 ${state.quota}  ·  今日计划 ${state.plannedUniqueWordCount} 个单词",
                color = DesktopPalette.muted)
            Text("到期复习 ${state.dueCount}  ·  可学新词 ${state.availableNewCount}", color = DesktopPalette.muted)
            Divider(Modifier.padding(vertical = 22.dp), color = DesktopPalette.line)
            state.progressLabels.forEachIndexed { index, label ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (index == 0) "① 复习词" else "② 新词", fontWeight = FontWeight.Medium)
                    Text(label, color = if (index == 0 && state.phase == DesktopStudyPhase.REVIEW ||
                        index == 1 && state.phase == DesktopStudyPhase.NEW_WORDS) DesktopPalette.teal else DesktopPalette.muted)
                }
            }
            if (state.phase == DesktopStudyPhase.REVIEW && state.newTotal == 0) {
                Text("完成复习后，可按仪表盘规则自动选词，也可以跨词库自己挑选。", color = DesktopPalette.muted,
                    style = MaterialTheme.typography.body2, modifier = Modifier.padding(top = 10.dp))
            }
            when (state.phase) {
                DesktopStudyPhase.COMPLETE -> Text("今日任务已完成，自动打卡成功。", color = DesktopPalette.teal,
                    modifier = Modifier.padding(top = 18.dp))
                DesktopStudyPhase.EXTRA -> Text("今日主任务已完成；还有手动加入的额外词可继续学习。", color = DesktopPalette.teal,
                    modifier = Modifier.padding(top = 18.dp))
                DesktopStudyPhase.CHOOSE_NEW -> {
                    Text("复习已完成，今天还可以选择 ${minOf(state.remainingMainQuota, state.availableNewCount)} 个新词。",
                        color = DesktopPalette.teal, modifier = Modifier.padding(top = 18.dp))
                    Button(onClick = onStart, modifier = Modifier.padding(top = 12.dp)) { Text("继续学习 · 选择新词") }
                }
                else -> Button(onClick = onStart, modifier = Modifier.padding(top = 20.dp)) {
                    Text(if (state.phase == DesktopStudyPhase.NOT_STARTED) "开始今日学习" else "继续学习")
                }
            }
            if (state.phase == DesktopStudyPhase.EXTRA) {
                Button(onClick = onStart, modifier = Modifier.padding(top = 20.dp)) { Text("继续学习额外词") }
            }
            if (state.remainingMainQuota > 0 && (state.phase == DesktopStudyPhase.NEW_WORDS || state.isTodayComplete)) {
                OutlinedButton(onClick = { onAddNewWords(state.remainingMainQuota) }, modifier = Modifier.padding(top = 10.dp)) {
                    Text("加入 ${state.remainingMainQuota} 个新词")
                }
            }
            if (state.isTodayComplete) {
                Text("学习打卡", style = MaterialTheme.typography.h6, modifier = Modifier.padding(top = 18.dp))
                DesktopCheckInCalendar(state.checkInDates)
                Text("额外词 ${state.extraCompleted} / ${state.extraTotal}", color = DesktopPalette.muted)
                OutlinedButton(onClick = { showExtraDialog = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("追加额外词")
                }
            }
        }
        DesktopPanel(Modifier.fillMaxWidth()) {
            Text("本地与同步", style = MaterialTheme.typography.h6)
            Text("本地仓库已启用 · 电脑端直接读取仓库数据", color = DesktopPalette.muted,
                modifier = Modifier.padding(top = 8.dp))
            Text("电脑端启动时会从 GitHub 获取最新数据，修改后也会自动上传。", style = MaterialTheme.typography.body2,
                color = DesktopPalette.muted, modifier = Modifier.padding(top = 4.dp))
        }
        if (state.checkInDates.isNotEmpty()) {
            DesktopPanel(Modifier.fillMaxWidth()) {
                Text("最近打卡", style = MaterialTheme.typography.h6)
                Text(state.checkInDates.sortedDescending().take(7).joinToString("  ·  "),
                    modifier = Modifier.padding(top = 10.dp), color = DesktopPalette.muted)
            }
        }
    }
    if (showExtraDialog) {
        AlertDialog(
            onDismissRequest = { showExtraDialog = false },
            title = { Text("追加额外词") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("完成主任务后仍可继续添加，数量不受 30 个主任务限制。")
                    TextField(value = extraText, onValueChange = { extraText = it; extraError = null }, label = { Text("自定义数量") }, isError = extraError != null)
                    extraError?.let { Text(it, color = DesktopPalette.coral) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(10, 20, 30).forEach { count -> OutlinedButton(onClick = { extraText = count.toString(); extraError = null }) { Text("${count}词") } }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val count = extraText.trim().toLongOrNull()?.takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()
                    if (count == null) extraError = "请输入正整数" else {
                        showExtraDialog = false
                        requestedExtraCount = count
                        showExtraDeckDialog = true
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showExtraDialog = false }) { Text("取消") } },
        )
    }
    if (showExtraDeckDialog) {
        AlertDialog(
            onDismissRequest = { showExtraDeckDialog = false; requestedExtraCount = null },
            title = { Text("选择词库") },
            text = {
                if (availableDecks.isEmpty()) {
                    Text("当前没有可用词库。")
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        availableDecks.forEach { deck ->
                            OutlinedButton(
                                onClick = {
                                    val count = requestedExtraCount ?: return@OutlinedButton
                                    showExtraDeckDialog = false
                                    requestedExtraCount = null
                                    onAppendExtraWords(count, deck.id)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(deck.name)
                                    Text("${deck.wordCount} 个词条", color = DesktopPalette.muted, style = MaterialTheme.typography.caption)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showExtraDeckDialog = false; requestedExtraCount = null }) { Text("取消") } },
        )
    }
}

internal fun desktopCalendarWeeks(month: YearMonth): List<List<LocalDate?>> {
    val firstColumn = month.atDay(1).dayOfWeek.value % 7
    val cells = List(firstColumn) { null as LocalDate? } + (1..month.lengthOfMonth()).map(month::atDay)
    return cells.chunked(7).map { week -> week + List(7 - week.size) { null } }
}

@Composable
private fun DesktopCheckInCalendar(completedDates: Set<LocalDate>) {
    val month = YearMonth.now()
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) { listOf("日", "一", "二", "三", "四", "五", "六").forEach { Text(it, Modifier.weight(1f), color = DesktopPalette.muted) } }
        desktopCalendarWeeks(month).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f).size(34.dp), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            val completed = date in completedDates
                            val todayMarker = date == today
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier.size(34.dp)
                                    .then(if (todayMarker) Modifier.border(2.dp, DesktopPalette.ink, CircleShape) else Modifier)
                                    .padding(3.dp)
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(if (completed) DesktopPalette.teal else androidx.compose.ui.graphics.Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(date.dayOfMonth.toString(), color = if (completed) androidx.compose.ui.graphics.Color.White else DesktopPalette.muted)
                                if (completed) {
                                    Text("✓", color = androidx.compose.ui.graphics.Color.White, fontSize = 8.sp,
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp, bottom = 1.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private object DesktopAssets
