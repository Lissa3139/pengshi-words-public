package com.pengshi.words.feature.stats

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import java.time.LocalDate
import kotlin.math.ceil
import com.pengshi.words.stats.EbbinghausReference
import com.pengshi.words.stats.ChartLabelRows
import com.pengshi.words.stats.ForgettingTimeScale

private val StatsTeal = Color(0xFF08B69D)
private val StatsOrange = Color(0xFFF16A38)
private val StatsAmber = Color(0xFFF4A000)
private val StatsMint = Color(0xFF6FC1AC)
private val StatsDarkTeal = Color(0xFF075A47)
private val StatsGrid = Color(0xFFE5E7E6)
private val StatsBand = Color(0xFFF7F8F7)
private val StatsMuted = Color(0xFF9A9D9C)
private val StatsFuture = Color(0xFFE5E6E6)
private val StatsBlue = Color(0xFF4D83C2)

@Composable
fun StatsScreen(
    state: StatsUiState,
    onQueryChange: (LocalDate, StatsPeriod) -> Unit = { _, _ -> },
    initialTab: StatsTab = StatsTab.RETENTION,
    onTabChange: (StatsTab) -> Unit = {},
) {
    var selectedTabName by rememberSaveable(initialTab.name) { mutableStateOf(initialTab.name) }
    val selectedTab = StatsTab.valueOf(selectedTabName)
    val labels = state.chartLabels.ifEmpty { (1..11).map { "" } }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp, bottom = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatsTab.values().forEach { tab ->
                val active = tab == selectedTab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            selectedTabName = tab.name
                            onTabChange(tab)
                        }
                        .padding(vertical = 13.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        tab.title,
                        color = if (active) StatsTeal else StatsMuted,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(9.dp))
                    Box(
                        modifier = Modifier
                            .width(if (active) 78.dp else 1.dp)
                            .height(3.dp)
                            .background(if (active) StatsTeal else Color.Transparent, RoundedCornerShape(3.dp)),
                    )
                }
            }
            Text(
                text = "↗",
                color = Color.Black,
                fontSize = 29.sp,
                modifier = Modifier.padding(start = 4.dp, end = 2.dp),
            )
        }

        if (selectedTab != StatsTab.FORGETTING) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatsPeriod.values().forEach { period ->
                    val active = state.period == period
                    Surface(
                        color = if (active) StatsTeal else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.clickable { onQueryChange(state.anchorDate, period) },
                    ) {
                        Text(
                            period.title,
                            color = if (active) Color.White else StatsMuted,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                DatePickerButton(state, context, onQueryChange)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("刻度等距显示，曲线按真实时长计算", color = StatsMuted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                DatePickerButton(state, context, onQueryChange)
            }
        }
        Text(
            "统计起点：${state.statsStartDate} · ${if (state.forgettingPreliminary) "初步估计" else "个人拟合"} · 有效样本 ${state.forgettingSampleCount}",
            style = MaterialTheme.typography.labelSmall,
            color = StatsMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        when (selectedTab) {
            StatsTab.FORGETTING -> ForgettingTab(state)
            StatsTab.LEARNING -> LearningTab(state)
            StatsTab.RETENTION -> RetentionTab(state, labels)
        }
    }
}

@Composable
private fun DatePickerButton(
    state: StatsUiState,
    context: android.content.Context,
    onQueryChange: (LocalDate, StatsPeriod) -> Unit,
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clickable {
            DatePickerDialog(
                context,
                { _, year, month, day -> onQueryChange(LocalDate.of(year, month + 1, day), state.period) },
                state.anchorDate.year,
                state.anchorDate.monthValue - 1,
                state.anchorDate.dayOfMonth,
            ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
        },
    ) { Text("选择日期：${state.anchorDate}", color = StatsTeal, modifier = Modifier.padding(7.dp)) }
}

@Composable
private fun ForgettingTab(state: StatsUiState) {
    val timeAxisScroll = rememberScrollState()
    val points = state.personalForgettingCurve.ifEmpty {
        state.forgettingCurve.ifEmpty {
            ForgettingTimeScale.tickElapsedMinutes.zip(ForgettingTimeScale.tickLabels)
                .mapIndexed { index, (elapsedMinutes, label) ->
                    val elapsedDays = (elapsedMinutes / (24.0 * 60.0)).toInt()
                    StatsPoint(label, if (index == 0) 100f else 0f, elapsedDays, elapsedMinutes)
                }
        }
    }
    val referencePoints = state.referencePersonalForgettingCurve.ifEmpty {
        state.referenceForgettingCurve.ifEmpty {
        points.map { point ->
            StatsPoint(
                point.label,
                (EbbinghausReference.retention(point.elapsedMinutes) * 100.0).toFloat(),
                point.xDay,
                point.elapsedMinutes,
            )
        }
        }
    }
    val series = listOf(
        ChartSeries("你的学习遗忘曲线", points, StatsOrange),
        ChartSeries("艾宾浩斯遗忘曲线 The Ebbinghaus Forgetting Curve", referencePoints, StatsTeal),
    )
    val curveLabels = points.mapIndexed { index, point ->
        ForgettingTimeScale.tickAxisLabels.getOrNull(index) ?: point.label
    }
    ChartSection(
        yTicks = listOf("100%", "80%", "60%", "40%", "20%", "0%"),
        labels = curveLabels,
        highlightedIndex = 0,
        xCoordinates = points.map { it.elapsedMinutes },
        horizontalScroll = timeAxisScroll,
        chartWidth = 780.dp,
        logarithmicTimeScale = true,
    ) {
        LineChartCanvas(
            series,
            pointCount = series.maxOfOrNull { it.points.size } ?: points.size,
            yMax = 100f,
            realTimeScale = true,
            axisCoordinates = points.map { it.elapsedMinutes },
            logarithmicTimeScale = true,
        )
    }
    LegendColumn(series)
    Surface(
        color = StatsBand,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(
            "坚持使用彭式背单词的时间越长，你的遗忘曲线统计将越精准。",
            color = StatsMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun LearningTab(state: StatsUiState) {
    val bars = if (state.learningBars.isEmpty()) {
        state.chartLabels.map { label -> LearningBar(label, 0, 0, 0, 0, 0, label == "今天") }
    } else state.learningBars
    StackedBarChart(bars)
    val today = bars.firstOrNull { it.isToday } ?: LearningBar("今天", 0, 0, 0, 0, 0, true)
    LearningLegend(today, state.period)
}

@Composable
private fun RetentionTab(state: StatsUiState, labels: List<String>) {
    val series = if (state.retentionSeries.isEmpty()) {
        listOf(RetentionSeries(null, "已加入记忆规划的全部单词", labels.map { 0f }, 0, 0f))
    } else state.retentionSeries
    val chartSeries = series.mapIndexed { index, item ->
        ChartSeries(
            item.label,
            item.values.mapIndexed { pointIndex, value ->
                StatsPoint(labels[pointIndex], value ?: Float.NaN, pointIndex)
            },
            retentionColor(index),
        )
    }
    val max = niceMax(series.flatMap { it.values }.filterNotNull().maxOrNull() ?: 0f)
    ChartSection(
        yTicks = retentionTicks(max),
        labels = labels,
        highlightedIndex = selectedDateIndex(state),
    ) {
        LineChartCanvas(chartSeries, pointCount = labels.size, yMax = max)
    }
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "持久度 = FSRS 稳定性（预计在目标记忆率下保持的天数），不是下次复习日期。",
            color = StatsMuted,
            fontSize = 12.sp,
        )
        series.forEachIndexed { index, item ->
            LegendLine(
                color = retentionColor(index),
                text = "${item.label} ${item.total} | ${formatPercent(item.percent)}%",
            )
        }
    }
}

private fun selectedDateIndex(state: StatsUiState): Int {
    val selectedDate = when (state.period) {
        StatsPeriod.DAY -> state.anchorDate
        StatsPeriod.WEEK -> state.anchorDate.minusDays(
            state.anchorDate.dayOfWeek.value.toLong() - 1L,
        )
        StatsPeriod.MONTH -> state.anchorDate.withDayOfMonth(1)
    }
    return state.chartDates.indexOf(selectedDate).takeIf { it >= 0 } ?: 0
}

private data class ChartSeries(val label: String, val points: List<StatsPoint>, val color: Color)

@Composable
private fun ChartSection(
    yTicks: List<String>,
    labels: List<String>,
    highlightedIndex: Int,
    xCoordinates: List<Double>? = null,
    horizontalScroll: ScrollState? = null,
    chartWidth: Dp? = null,
    logarithmicTimeScale: Boolean = false,
    chart: @Composable () -> Unit,
) {
    val safeLabels = labels.ifEmpty { listOf("") }
    Row(modifier = Modifier.padding(top = 13.dp, start = 12.dp, end = 12.dp)) {
        Column(
            modifier = Modifier
                .width(54.dp)
                .height(310.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            yTicks.forEach { Text(it, color = StatsMuted, fontSize = 12.sp) }
        }
        Box(modifier = Modifier.weight(1f)) {
            val chartModifier = if (horizontalScroll != null && chartWidth != null) {
                Modifier.horizontalScroll(horizontalScroll).width(chartWidth)
            } else {
                Modifier.fillMaxWidth()
            }
            Box(modifier = chartModifier) { chart() }
        }
    }
    if (xCoordinates == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 66.dp, end = 12.dp, top = 7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            safeLabels.forEachIndexed { index, label ->
                ChartLabel(label, index == highlightedIndex, Modifier.weight(1f))
            }
        }
    } else {
        Row(modifier = Modifier.padding(start = 66.dp, end = 12.dp, top = 7.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                val labelModifier = if (horizontalScroll != null && chartWidth != null) {
                    Modifier.horizontalScroll(horizontalScroll).width(chartWidth)
                } else {
                    Modifier.fillMaxWidth()
                }
                PositionedChartLabels(
                    labels = safeLabels,
                    xCoordinates = xCoordinates,
                    highlightedIndex = highlightedIndex,
                    logarithmicTimeScale = logarithmicTimeScale,
                    modifier = labelModifier,
                )
            }
        }
    }
}

@Composable
private fun ChartLabel(label: String, highlighted: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(horizontal = 1.dp)
            .background(if (highlighted) StatsTeal else Color.Transparent, RoundedCornerShape(7.dp))
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (highlighted) Color.White else StatsMuted,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun PositionedChartLabels(
    labels: List<String>,
    xCoordinates: List<Double>,
    highlightedIndex: Int,
    logarithmicTimeScale: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            labels.forEachIndexed { index, label ->
                ChartLabel(label, index == highlightedIndex)
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val rowHeight = placeables.maxOfOrNull { it.height } ?: 0
        val maximum = xCoordinates.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val centers = xCoordinates.map { coordinate ->
            val normalized = if (logarithmicTimeScale) {
                ForgettingTimeScale.position(coordinate, maximum).toFloat()
            } else {
                coordinate.coerceIn(0.0, maximum).toFloat() / maximum.toFloat()
            }
            (width.toFloat() * normalized).coerceIn(0f, width.toFloat())
        }
        val rowForLabel = ChartLabelRows.assign(centers, placeables.map { it.width.toFloat() })
        val height = rowHeight * ((rowForLabel.maxOrNull() ?: 0) + 1)
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val x = centers.getOrElse(index) { 0f }.toInt()
                val left = (x - placeable.width / 2).coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                placeable.place(left, rowForLabel[index] * rowHeight)
            }
        }
    }
}

@Composable
private fun LineChartCanvas(
    series: List<ChartSeries>,
    pointCount: Int,
    yMax: Float,
    realTimeScale: Boolean = false,
    axisCoordinates: List<Double>? = null,
    logarithmicTimeScale: Boolean = false,
) {
    Canvas(modifier = Modifier.fillMaxWidth().height(310.dp)) {
        val columns = pointCount.coerceAtLeast(2)
        val max = yMax.coerceAtLeast(1f)
        val maximumCoordinate = series.flatMap { it.points }
            .maxOfOrNull { if (realTimeScale) it.elapsedMinutes else it.xDay.toDouble() }
            ?.coerceAtLeast(1.0) ?: 1.0
        val xPosition = { coordinate: Double ->
            if (logarithmicTimeScale) {
                ForgettingTimeScale.position(coordinate, maximumCoordinate).toFloat()
            } else {
                coordinate.coerceIn(0.0, maximumCoordinate).toFloat() / maximumCoordinate.toFloat()
            }
        }
        val xFor = { index: Int, point: StatsPoint ->
            if (realTimeScale) size.width * xPosition(point.elapsedMinutes)
            else size.width * index / (columns - 1)
        }
        val gridX = if (realTimeScale) {
            (axisCoordinates ?: series.firstOrNull()?.points?.map { it.elapsedMinutes }.orEmpty())
                .map { coordinate -> size.width * xPosition(coordinate) }
        } else {
            (0 until columns).map { column -> size.width * column / (columns - 1) }
        }
        gridX.distinct().forEachIndexed { index, x ->
            if (index % 2 == 1) {
                drawRect(
                    color = StatsBand,
                    topLeft = Offset(x - size.width / columns / 2, 0f),
                    size = androidx.compose.ui.geometry.Size(size.width / columns, size.height),
                )
            }
            drawLine(StatsGrid, Offset(x, 0f), Offset(x, size.height), 1f)
        }
        for (row in 0..5) {
            val y = size.height * row / 5f
            drawLine(StatsGrid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        series.forEach { item ->
            if (item.points.none { it.value.isFinite() }) return@forEach
            var path: Path? = null
            item.points.forEachIndexed { index, point ->
                if (!point.value.isFinite()) {
                    path?.let { drawPath(it, item.color, style = Stroke(width = 4f, cap = StrokeCap.Round)) }
                    path = null
                    return@forEachIndexed
                }
                val x = xFor(index, point)
                val y = size.height - (point.value.coerceIn(0f, max) / max * size.height)
                val activePath = path ?: Path().also { path = it }
                if (activePath.isEmpty) activePath.moveTo(x, y) else activePath.lineTo(x, y)
            }
            path?.let { drawPath(it, item.color, style = Stroke(width = 4f, cap = StrokeCap.Round)) }
            item.points.forEachIndexed { index, point ->
                if (!point.value.isFinite()) return@forEachIndexed
                val x = xFor(index, point)
                val y = size.height - (point.value.coerceIn(0f, max) / max * size.height)
                drawCircle(item.color, radius = 5.5f, center = Offset(x, y))
            }
        }
    }
}

@Composable
private fun StackedBarChart(bars: List<LearningBar>) {
    val max = niceMax(bars.maxOfOrNull { it.familiar + it.remembered + it.forgotten + it.learning + it.planned }?.toFloat() ?: 0f)
    ChartSection(
        yTicks = retentionTicks(max),
        labels = bars.map { it.label },
        highlightedIndex = bars.indexOfFirst { it.isToday }.takeIf { it >= 0 } ?: 0,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(310.dp)) {
            val columns = bars.size.coerceAtLeast(1)
            val step = size.width / columns
            val barWidth = step * 0.62f
            for (column in bars.indices) {
                val bar = bars[column]
                val x = column * step + (step - barWidth) / 2
                var bottom = size.height
                val values = listOf(
                    bar.familiar.toFloat() to StatsTeal,
                    bar.remembered.toFloat() to StatsMint,
                    bar.forgotten.toFloat() to StatsOrange,
                    bar.learning.toFloat() to StatsAmber,
                    bar.planned.toFloat() to StatsFuture,
                )
                values.forEach { (value, color) ->
                    val height = value / max * size.height
                    drawRect(color, Offset(x, bottom - height), androidx.compose.ui.geometry.Size(barWidth, height))
                    bottom -= height
                }
                drawLine(StatsGrid, Offset(column * step, 0f), Offset(column * step, size.height), 1f)
            }
            for (row in 0..5) {
                val y = size.height * row / 5f
                drawLine(StatsGrid, Offset(0f, y), Offset(size.width, y), 1f)
            }
        }
    }
}

@Composable
private fun LearningLegend(today: LearningBar, period: StatsPeriod) {
    val prefix = when (period) {
        StatsPeriod.DAY -> "今日"
        StatsPeriod.WEEK -> "本周"
        StatsPeriod.MONTH -> "本月"
    }
    Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(
            "按每个单词当天的第一次选择统计；待学表示还没有首次反馈。",
            color = StatsMuted,
            fontSize = 12.sp,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            LegendLine(StatsTeal, "${prefix}熟知：${today.familiar}", Modifier.weight(1f))
            LegendLine(StatsMint, "${prefix}认识：${today.remembered}", Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            LegendLine(StatsAmber, "${prefix}模糊：${today.learning}", Modifier.weight(1f))
            LegendLine(StatsOrange, "${prefix}忘记：${today.forgotten}", Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            LegendLine(StatsFuture, "${prefix}待学：${today.planned}", Modifier.weight(1f))
            LegendLine(StatsBlue, "${prefix}时长：${formatStudyTime(today.studyTimeMs)}", Modifier.weight(1f))
        }
    }
}

@Composable
private fun LegendColumn(series: List<ChartSeries>) {
    Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        series.forEach { LegendLine(it.color, it.label) }
    }
}

@Composable
private fun LegendLine(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.width(32.dp).height(16.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 5f, cap = StrokeCap.Round)
            drawCircle(color, 5f, Offset(size.width / 2, size.height / 2))
        }
        Text(text, color = color, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun retentionColor(index: Int): Color = when (index) {
    0 -> StatsOrange
    1 -> StatsAmber
    2 -> StatsMint
    3 -> StatsTeal
    else -> StatsDarkTeal
}

private fun niceMax(value: Float): Float {
    if (!value.isFinite() || value <= 0f) return 10f
    return (ceil(value / 10f) * 10f).coerceAtLeast(10f)
}

private fun retentionTicks(max: Float): List<String> = (5 downTo 0).map { (max * it / 5f).toInt().toString() }

private fun formatPercent(value: Float): String = "%.2f".format(java.util.Locale.US, value)

private fun formatStudyTime(milliseconds: Long): String {
    val minutes = milliseconds / 60_000L
    return if (minutes > 0) "${minutes}min" else "${milliseconds / 1_000L}s"
}
