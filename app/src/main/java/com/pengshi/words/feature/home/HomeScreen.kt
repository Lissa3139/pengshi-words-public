package com.pengshi.words.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.pengshi.words.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import com.pengshi.words.model.StudyMode
import com.pengshi.words.feature.decks.DeckSummary
import java.time.LocalDate
import java.time.YearMonth

data class HomeUiState(
    val completedUniqueWordCount: Int = 0,
    val quota: Int = 30,
    val dueCount: Int = 0,
    val newCount: Int = 0,
    val reviewPlanned: Int = 0,
    val reviewCompleted: Int = 0,
    val newPlanned: Int = 0,
    val newCompleted: Int = 0,
    val extraPlanned: Int = 0,
    val extraCompleted: Int = 0,
    val phase: HomeStudyPhase = HomeStudyPhase.NOT_STARTED,
    val isTodayComplete: Boolean = false,
    val checkInDates: Set<LocalDate> = emptySet(),
    val plannedUniqueWordCount: Int = 0,
)

enum class HomeStudyPhase {
    NOT_STARTED,
    REVIEW,
    CHOOSE_NEW,
    NEW_WORDS,
    COMPLETE,
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    decks: List<DeckSummary> = emptyList(),
    onStart: (StudyMode) -> Unit,
    onAddNewWords: (Int) -> Unit = {},
    onAppendExtraWords: (Int, Long, (Int) -> Unit) -> Unit = { _, _, result -> result(0) },
) {
    var showAddWords by remember { mutableStateOf(false) }
    var showExtraDecks by remember { mutableStateOf(false) }
    var requestedExtraCount by remember { mutableStateOf<Int?>(null) }
    var extraAppendMessage by remember { mutableStateOf<String?>(null) }
    val plannedCount = state.reviewPlanned + state.newPlanned
    val remainingQuota = (state.quota - plannedCount).coerceAtLeast(0)
    val canAddWords = state.phase == HomeStudyPhase.NEW_WORDS
    Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        extraAppendMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .22f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.pengshi_logo),
                    contentDescription = "彭式背单词 logo",
                    modifier = Modifier.size(52.dp),
                )
                Column {
                    Text(
                        "彭式背单词",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            letterSpacing = androidx.compose.ui.unit.TextUnit(0.04f, androidx.compose.ui.unit.TextUnitType.Em),
                        ),
                    )
                    Text("记一词，志更远", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.isTodayComplete) {
            Text("今日任务已完成，自动打卡成功。", style = MaterialTheme.typography.bodyLarge)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("学习打卡", style = MaterialTheme.typography.titleLarge)
                    Text("连续记录你的学习节奏", style = MaterialTheme.typography.bodyMedium)
                    CheckInCalendar(state.checkInDates)
                    Text("额外词 ${state.extraCompleted} / ${state.extraPlanned}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { showAddWords = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("追加额外单词")
                    }
                }
            }
        } else {
            Text("今天也稳稳记住几个词。", style = MaterialTheme.typography.bodyLarge)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("今日任务", style = MaterialTheme.typography.titleLarge)
                    Text("${state.completedUniqueWordCount} / ${maxOf(state.quota, state.plannedUniqueWordCount)} 个单词")
                    Text("每日额度 ${state.quota} · 今日计划 ${state.plannedUniqueWordCount} 个单词",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("到期复习 ${state.dueCount} · 新词 ${state.newCount}")
                    if (state.reviewPlanned > 0 || state.newPlanned > 0 || state.phase == HomeStudyPhase.CHOOSE_NEW) {
                        TaskProgressRow(
                            label = "复习词",
                            completed = state.reviewCompleted,
                            planned = state.reviewPlanned,
                            active = state.phase == HomeStudyPhase.REVIEW || state.reviewPlanned == state.reviewCompleted,
                        )
                        if (state.phase == HomeStudyPhase.REVIEW && state.newPlanned == 0) {
                            Text(
                                "新词：复习完成后可自动选词，也可以跨词库自己挑选（剩余 ${remainingQuota} 个）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TaskProgressRow(
                            label = "新词",
                            completed = state.newCompleted,
                            planned = state.newPlanned,
                            active = state.phase == HomeStudyPhase.NEW_WORDS,
                        )
                        Text(
                            when (state.phase) {
                                HomeStudyPhase.REVIEW -> "先完成复习词，再进入新词学习"
                                HomeStudyPhase.CHOOSE_NEW -> "复习已完成，今日新词还可选择 ${minOf(remainingQuota, state.newCount)} 个"
                                HomeStudyPhase.NEW_WORDS -> "复习已完成，现在学习新词"
                                HomeStudyPhase.COMPLETE -> "今日两阶段任务已完成"
                                HomeStudyPhase.NOT_STARTED -> "点击开始后按复习词、新词分阶段进行"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onStart(StudyMode.EN_TO_CN) }, modifier = Modifier.weight(1f)) {
                    Text(if (state.phase == HomeStudyPhase.CHOOSE_NEW) "继续学习 · 英译中" else "英译中")
                }
                OutlinedButton(onClick = { onStart(StudyMode.CN_TO_EN) }, modifier = Modifier.weight(1f)) {
                    Text(if (state.phase == HomeStudyPhase.CHOOSE_NEW) "继续学习 · 中译英" else "中译英")
                }
            }
            if (canAddWords && remainingQuota > 0) {
                OutlinedButton(onClick = { showAddWords = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("继续加入新词（还可加入${remainingQuota}个）")
                }
            }
        }
    }
    if (showAddWords) {
        ExtraWordsDialog(
            onDismiss = { showAddWords = false },
            onConfirm = { count ->
                showAddWords = false
                if (state.isTodayComplete) {
                    requestedExtraCount = count
                    showExtraDecks = true
                } else onAddNewWords(count)
            },
            title = if (state.isTodayComplete) "追加额外单词" else "加入新词",
        )
    }
    if (showExtraDecks) {
        ExtraWordsDeckDialog(
            decks = decks,
            onDismiss = { showExtraDecks = false; requestedExtraCount = null },
            onSelect = { deckId ->
                val count = requestedExtraCount ?: return@ExtraWordsDeckDialog
                showExtraDecks = false
                requestedExtraCount = null
                onAppendExtraWords(count, deckId) { appended ->
                    if (appended == 0) extraAppendMessage = "所选词库暂无可加入的未学习词。"
                }
            },
        )
    }
}

@Composable
private fun TaskProgressRow(label: String, completed: Int, planned: Int, active: Boolean) {
    val remaining = (planned - completed).coerceAtLeast(0)
    val progress = if (planned == 0) 0f else (completed.toFloat() / planned).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(remaining.toString(), style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun CheckInCalendar(completedDates: Set<LocalDate>) {
    val month = YearMonth.now()
    val firstDayOffset = month.atDay(1).dayOfWeek.value % 7
    val emptyDays: List<LocalDate?> = (0 until firstDayOffset).map { null }
    val days = emptyDays +
        (1..month.lengthOfMonth()).map { month.atDay(it) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                Text(it, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Color.Gray)
            }
        }
        days.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(modifier = Modifier.weight(1f).size(35.dp), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            val completed = date in completedDates
                            Text(
                                text = if (completed) "✓" else date.dayOfMonth.toString(),
                                color = if (completed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = if (completed) Modifier
                                    .size(29.dp)
                                    .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                                    .padding(top = 5.dp)
                                else Modifier,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
