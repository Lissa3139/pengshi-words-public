package com.pengshi.words.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pengshi.words.desktop.stats.DesktopLearningBar
import com.pengshi.words.desktop.stats.DesktopRetentionSeries
import com.pengshi.words.desktop.stats.DesktopStatsPeriod
import com.pengshi.words.desktop.stats.DesktopStatsPoint
import com.pengshi.words.desktop.stats.DesktopStatsTab
import com.pengshi.words.desktop.stats.DesktopStatsCalculator
import com.pengshi.words.model.StudyDataSnapshot
import com.pengshi.words.stats.ChartLabelRows
import com.pengshi.words.stats.ForgettingTimeScale
import java.time.LocalDate
import java.time.YearMonth
import java.time.DayOfWeek
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

private val StatsOrange = Color(0xFFE96A3A)
private val StatsAmber = Color(0xFFF0A10B)
private val StatsMint = Color(0xFF6FC0AC)
private val StatsTeal = Color(0xFF08A88D)
private val StatsDarkTeal = Color(0xFF075B4B)
private val StatsGrid = Color(0xFFE1E7E9)
private val StatsMuted = Color(0xFF718087)

@Composable
fun DesktopStatsScreen(snapshot: StudyDataSnapshot, statsStartDate: LocalDate = LocalDate.now()) {
    val today = LocalDate.now()
    var selectedTab by remember { mutableStateOf(DesktopStatsTab.FORGETTING) }
    var period by remember { mutableStateOf(DesktopStatsPeriod.DAY) }
    var anchorDate by remember { mutableStateOf(today) }
    var calendarOpen by remember { mutableStateOf(false) }
    var calendarMonth by remember { mutableStateOf(YearMonth.from(today)) }
    val state = remember(snapshot, anchorDate, period, statsStartDate) {
        DesktopStatsCalculator.calculate(snapshot, anchorDate, statsStartDate, period)
    }
    val learningYMax = remember(snapshot, period) { stableLearningYMax(snapshot, period) }
    val retentionYMax = remember(snapshot) { niceMax(snapshot.reviewLogs.map { it.wordId }.distinct().size.toFloat()) }
    LazyColumn(
        Modifier.fillMaxSize().padding(30.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("学习统计", style = MaterialTheme.typography.h4, fontWeight = FontWeight.Bold)
                    Text("从你的第一次有效记忆反馈开始统计，周/月为对应周期累加。", color = DesktopPalette.muted)
                }
                Box {
                    OutlinedButton(onClick = { calendarMonth = YearMonth.from(anchorDate); calendarOpen = true }) {
                        Text("选择日期：$anchorDate")
                    }
                    DropdownMenu(expanded = calendarOpen, onDismissRequest = { calendarOpen = false }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { calendarMonth = calendarMonth.minusMonths(1) }) { Text("‹") }
                            Text("${calendarMonth.year} 年 ${calendarMonth.monthValue} 月")
                            TextButton(onClick = { calendarMonth = calendarMonth.plusMonths(1) },
                                enabled = calendarMonth < YearMonth.from(today)) { Text("›") }
                        }
                        Row {
                            listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                                Text(day, modifier = Modifier.width(42.dp), textAlign = TextAlign.Center)
                            }
                        }
                        val firstOffset = calendarMonth.atDay(1).dayOfWeek.value - 1
                        val cells = ((firstOffset + calendarMonth.lengthOfMonth() + 6) / 7) * 7
                        repeat(cells / 7) { week ->
                            Row {
                                repeat(7) { weekday ->
                                    val number = week * 7 + weekday - firstOffset + 1
                                    val date = number.takeIf { it in 1..calendarMonth.lengthOfMonth() }
                                        ?.let(calendarMonth::atDay)
                                    TextButton(
                                        onClick = { date?.let { anchorDate = it; calendarOpen = false } },
                                        enabled = date != null && !date.isAfter(today),
                                        modifier = Modifier.width(42.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                    ) { Text(date?.dayOfMonth?.toString().orEmpty()) }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DesktopStatsTab.entries.forEach { tab ->
                    TextButton(onClick = { selectedTab = tab }) {
                        Text(tab.title, color = if (tab == selectedTab) DesktopPalette.teal else DesktopPalette.muted,
                            fontWeight = if (tab == selectedTab) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                Spacer(Modifier.weight(1f))
                DesktopStatsPeriod.entries.forEach { item ->
                    TextButton(onClick = { period = item }) {
                        Text(item.title, color = if (period == item) DesktopPalette.teal else DesktopPalette.muted,
                            fontWeight = if (period == item) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Divider(color = DesktopPalette.line)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("词库单词", state.totalWords.toString(), Modifier.weight(1f))
                StatCard("有效反馈", state.reviewCount.toString(), Modifier.weight(1f))
                StatCard("完成天数", state.completedDays.toString(), Modifier.weight(1f))
                StatCard("记得/熟知", state.rememberedCount.toString(), Modifier.weight(1f))
            }
        }
        item {
            DesktopPanel(Modifier.fillMaxWidth()) {
                Text("统计起点：${state.statsStartDate} · 当前查看：${state.anchorDate}", color = StatsMuted, fontSize = 12.sp)
                when (selectedTab) {
                    DesktopStatsTab.FORGETTING -> ForgettingTab(state)
                    DesktopStatsTab.LEARNING -> LearningTab(state, learningYMax)
                    DesktopStatsTab.RETENTION -> RetentionTab(state, retentionYMax)
                }
            }
        }
    }
}

@Composable
private fun ForgettingTab(state: DesktopStatsState) {
    val personal = state.personalForgettingCurve.ifEmpty { state.forgettingCurve }
    val reference = state.referencePersonalForgettingCurve.ifEmpty { state.referenceForgettingCurve }
    val series = listOf(
        "你的学习遗忘曲线" to personal,
        "艾宾浩斯遗忘曲线 The Ebbinghaus Forgetting Curve" to reference,
    )
    DesktopLineChart(series, yMax = 100f, realTime = true, percent = true)
    val axisLabels = personal.mapIndexed { index, point ->
        ForgettingTimeScale.tickAxisLabels.getOrNull(index) ?: point.label
    }
    AxisLabels(axisLabels, personal.map { it.elapsedMinutes }, highlighted = 0, logarithmicTimeScale = true)
    series.forEachIndexed { index, item -> LegendLine(if (index == 0) StatsOrange else StatsTeal, item.first) }
    Text("橙色根据全部学习、复习和对错记录拟合（${state.forgettingSampleCount} 个单词${if (state.forgettingPreliminary) "，当前为初步模型" else ""}）；青绿色为艾宾浩斯标准参考。今天均从 100% 开始；刻度等距，曲线按真实时长计算。",
        color = StatsMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
}

@Composable
private fun LearningTab(state: DesktopStatsState, yMax: Float) {
    val bars = state.learningBars
    DesktopStackedBarChart(bars, yMax)
    AxisLabels(bars.map { it.label }, bars.indices.map { it.toDouble() }, bars.indexOfFirst { it.isToday })
    val today = bars.firstOrNull { it.isToday } ?: DesktopLearningBar("今天", 0, 0, 0, 0, 0, true)
    Text("按每个单词在对应周期内的第一次选择统计；五种记忆程度加起来等于该周期计划词数。",
        color = StatsMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        LegendLine(StatsTeal, "熟知 ${today.familiar}", Modifier.weight(1f))
        LegendLine(StatsMint, "认识 ${today.remembered}", Modifier.weight(1f))
        LegendLine(StatsOrange, "忘记 ${today.forgotten}", Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        LegendLine(StatsAmber, "模糊 ${today.learning}", Modifier.weight(1f))
        LegendLine(StatsMuted, "待学 ${today.planned}", Modifier.weight(1f))
        LegendLine(DesktopPalette.ink, "时长 ${formatTime(today.studyTimeMs)}", Modifier.weight(1f))
    }
}

@Composable
private fun RetentionTab(state: DesktopStatsState, yMax: Float) {
    val labels = state.chartLabels
    val series = state.retentionSeries
    val chartSeries = series.mapIndexed { index, item ->
        item.label to item.values.mapIndexed { point, value -> DesktopStatsPoint(labels.getOrElse(point) { "" }, value ?: Float.NaN, point) }
    }
    DesktopLineChart(chartSeries, yMax = yMax, realTime = false)
    AxisLabels(labels, labels.indices.map { it.toDouble() }, state.chartDates.indexOf(state.anchorDate).coerceAtLeast(0))
    Text("持久度 = FSRS 稳定性（预计在目标记忆率下保持的天数），只统计已经完成过有效记忆反馈的单词；未来没有数据的点保持空白。",
        color = StatsMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
    series.forEachIndexed { index, item ->
        LegendLine(retentionColor(index), "${item.label} ${item.total} | ${"%.2f".format(item.percent)}%")
    }
}

@Composable
private fun DesktopLineChart(
    series: List<Pair<String, List<DesktopStatsPoint>>>,
    yMax: Float,
    realTime: Boolean,
    percent: Boolean = false,
) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(Modifier.width(52.dp).height(280.dp), verticalArrangement = Arrangement.SpaceBetween) {
            desktopYAxisTicks(yMax, percent).forEach { Text(it, color = StatsMuted, fontSize = 11.sp) }
        }
        Canvas(Modifier.fillMaxWidth().height(280.dp)) {
            val max = yMax.coerceAtLeast(1f)
            val maximum = series.flatMap { it.second }
                .maxOfOrNull { if (realTime) it.elapsedMinutes else it.xDay.toDouble() }
                ?.coerceAtLeast(1.0) ?: 1.0
            val xFor = { point: DesktopStatsPoint ->
                val normalized = if (realTime) {
                    ForgettingTimeScale.position(point.elapsedMinutes, maximum).toFloat()
                } else {
                    (point.xDay.toDouble() / maximum).toFloat()
                }
                size.width * normalized
            }
            for (row in 0..5) drawLine(StatsGrid, Offset(0f, size.height * row / 5f), Offset(size.width, size.height * row / 5f), 1f)
            val gridCoordinates = series.firstOrNull()?.second.orEmpty()
                .map { if (realTime) it.elapsedMinutes else it.xDay.toDouble() }
                .distinct()
            gridCoordinates.forEach { coordinate ->
                val x = if (realTime) {
                    size.width * ForgettingTimeScale.position(coordinate, maximum).toFloat()
                } else {
                    size.width * (coordinate / maximum).toFloat()
                }
                drawLine(StatsGrid, Offset(x, 0f), Offset(x, size.height), 1f)
            }
            series.forEachIndexed { index, item ->
                val color = if (realTime) if (index == 0) StatsOrange else StatsTeal else retentionColor(index)
                var path: Path? = null
                item.second.forEach { point ->
                    if (!point.value.isFinite()) {
                        path?.let { drawPath(it, color, style = Stroke(4f, cap = StrokeCap.Round)) }
                        path = null
                    } else {
                        val x = xFor(point)
                        val y = size.height - point.value.coerceIn(0f, max) / max * size.height
                        val current = path ?: Path().also { path = it }
                        if (current.isEmpty) current.moveTo(x, y) else current.lineTo(x, y)
                    }
                }
                path?.let { drawPath(it, color, style = Stroke(4f, cap = StrokeCap.Round)) }
                item.second.filter { it.value.isFinite() }.forEach { point ->
                    val y = size.height - point.value.coerceIn(0f, max) / max * size.height
                    drawCircle(color, radius = 4.5f, center = Offset(xFor(point), y))
                }
            }
        }
    }
}

@Composable
private fun DesktopStackedBarChart(bars: List<DesktopLearningBar>, max: Float) {
    Canvas(Modifier.fillMaxWidth().height(280.dp).padding(start = 52.dp, top = 8.dp)) {
        val step = size.width / bars.size.coerceAtLeast(1)
        val barWidth = step * .62f
        bars.forEachIndexed { index, bar ->
            val x = index * step + (step - barWidth) / 2
            var bottom = size.height
            listOf(bar.familiar to StatsTeal, bar.remembered to StatsMint, bar.forgotten to StatsOrange,
                bar.learning to StatsAmber, bar.planned to Color(0xFFE4E6E7)).forEach { (value, color) ->
                val height = value / max * size.height
                drawRect(color, topLeft = Offset(x, bottom - height), size = androidx.compose.ui.geometry.Size(barWidth, height))
                bottom -= height
            }
        }
        for (row in 0..5) drawLine(StatsGrid, Offset(0f, size.height * row / 5f), Offset(size.width, size.height * row / 5f), 1f)
    }
}

@Composable
private fun AxisLabels(
    labels: List<String>,
    xCoordinates: List<Double>,
    highlighted: Int,
    logarithmicTimeScale: Boolean = false,
) {
    val horizontalLabelPadding = if (logarithmicTimeScale) 0.dp else 3.dp
    Layout(
        modifier = Modifier.fillMaxWidth().padding(start = 52.dp, top = 6.dp),
        content = { labels.forEachIndexed { index, label ->
            Box(Modifier.background(if (index == highlighted) StatsTeal else Color.Transparent)) {
                Text(label, color = if (index == highlighted) Color.White else StatsMuted, fontSize = 10.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = horizontalLabelPadding, vertical = 4.dp))
            }
        } },
        measurePolicy = { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val rowHeight = placeables.maxOfOrNull { it.height } ?: 0
        val maximum = xCoordinates.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val centers = placeables.indices.map { index ->
            val coordinate = xCoordinates.getOrElse(index) { index.toDouble() }
            val normalized = if (logarithmicTimeScale) {
                ForgettingTimeScale.position(coordinate, maximum).toFloat()
            } else {
                (coordinate / maximum).toFloat()
            }
            width * normalized
        }
        val rowForLabel = ChartLabelRows.assign(centers, placeables.map { it.width.toFloat() })
        val rowCount = (rowForLabel.maxOrNull() ?: 0) + 1
        layout(width, rowHeight * rowCount) {
            placeables.forEachIndexed { index, placeable ->
                val x = centers[index].toInt()
                placeable.place(
                    (x - placeable.width / 2).coerceIn(0, (width - placeable.width).coerceAtLeast(0)),
                    rowForLabel[index] * rowHeight,
                )
            }
        }
        },
    )
}

@Composable
private fun LegendLine(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(26.dp).height(14.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4f, cap = StrokeCap.Round)
        }
        Text(text, color = color, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    DesktopPanel(modifier) {
        Text(label, color = DesktopPalette.muted)
        Text(value, style = MaterialTheme.typography.h5, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    }
}

private fun retentionColor(index: Int): Color = when (index) {
    0 -> StatsOrange
    1 -> StatsAmber
    2 -> StatsMint
    3 -> StatsTeal
    else -> StatsDarkTeal
}

internal fun desktopYAxisTicks(max: Float, percent: Boolean = false): List<String> =
    (5 downTo 0).map { index ->
        val value = (max.coerceAtLeast(1f) * index / 5f).roundToInt()
        if (percent) "$value%" else value.toString()
    }

private fun stableLearningYMax(snapshot: StudyDataSnapshot, period: DesktopStatsPeriod): Float {
    val wordsByPlan = snapshot.dailyPlanItems.groupBy { it.dailyPlanId }
        .mapValues { (_, items) -> items.map { it.wordId }.distinct().size }
    val groups = snapshot.dailyPlans.groupBy { plan ->
        when (period) {
            DesktopStatsPeriod.DAY -> plan.localDate
            DesktopStatsPeriod.WEEK -> plan.localDate.with(DayOfWeek.MONDAY)
            DesktopStatsPeriod.MONTH -> plan.localDate.withDayOfMonth(1)
        }
    }
    val largestPeriod = groups.values.maxOfOrNull { plans ->
        plans.sumOf { plan -> maxOf(plan.plannedUniqueWordCount, wordsByPlan[plan.id] ?: 0) }
    } ?: 0
    return niceMax(largestPeriod.toFloat())
}

private fun niceMax(value: Float): Float = if (!value.isFinite() || value <= 0f) 10f else (ceil(value / 10f) * 10f).coerceAtLeast(10f)
private fun formatTime(ms: Long): String = if (ms >= 60_000) "${ms / 60_000}min" else "${ms / 1_000}s"
