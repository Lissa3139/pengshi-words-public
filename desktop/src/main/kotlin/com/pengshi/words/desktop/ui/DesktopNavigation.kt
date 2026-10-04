package com.pengshi.words.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.loadImageBitmap

enum class DesktopDestination(val label: String, val mark: String) {
    HOME("首页", "⌂"),
    DECKS("词库", "▤"),
    STATS("统计", "◫"),
    SETTINGS("设置", "⚙"),
    STUDY("学习", "✦"),
}

data class DesktopHistoryEntry<T>(val value: T, val revisable: Boolean)

/** A single step of browsing history. The active study queue remains owned by the domain use cases. */
data class DesktopStudyHistory<T>(
    val current: T,
    val previous: List<DesktopHistoryEntry<T>> = emptyList(),
    val browsing: DesktopHistoryEntry<T>? = null,
    val currentRevisable: Boolean = false,
) {
    val displayed: T get() = browsing?.value ?: current
    val isHistorical: Boolean get() = browsing != null
    val canGoPrevious: Boolean get() = browsing == null && previous.isNotEmpty()
    val canGoNext: Boolean get() = browsing != null
    val canReviseDisplayed: Boolean get() = browsing?.revisable == true || browsing == null && currentRevisable

    fun present(next: T, revisablePrevious: Boolean): DesktopStudyHistory<T> = copy(
        current = next,
        previous = previous.map { it.copy(revisable = false) } + DesktopHistoryEntry(current, revisablePrevious),
        browsing = null,
        currentRevisable = false,
    )

    fun refreshCurrent(next: T, hasMatchingUndoToken: Boolean): DesktopStudyHistory<T> = copy(
        current = next,
        previous = previous.map { it.copy(revisable = hasMatchingUndoToken && it.revisable) },
        browsing = null,
        currentRevisable = hasMatchingUndoToken,
    )

    fun goPrevious(): DesktopStudyHistory<T> = if (canGoPrevious) copy(
        previous = previous.dropLast(1),
        browsing = previous.last(),
    ) else this

    fun goNext(): DesktopStudyHistory<T> = browsing?.let { copy(
        previous = previous + it,
        browsing = null,
        currentRevisable = false,
    ) } ?: this

    fun replaceDisplayed(value: T): DesktopStudyHistory<T> = if (browsing == null) copy(current = value)
    else copy(browsing = browsing.copy(value = value))
}

@Composable
fun DesktopNavigation(
    selected: DesktopDestination,
    studyAvailable: Boolean,
    onNavigate: (DesktopDestination) -> Unit,
) {
    Column(
        modifier = Modifier.width(216.dp).fillMaxHeight().background(DesktopPalette.ink)
            .padding(horizontal = 16.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val logo = remember {
            DesktopNavigationAssets::class.java.getResourceAsStream("/pengshi_logo.png")?.use(::loadImageBitmap)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            logo?.let {
                androidx.compose.foundation.Image(
                    bitmap = it,
                    contentDescription = "品牌 Logo",
                    modifier = Modifier.size(72.dp),
                    colorFilter = ColorFilter.tint(Color(0xFFE7FFF9)),
                    filterQuality = FilterQuality.High,
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 22.dp))
        DesktopDestination.entries.forEach { destination ->
            if (destination != DesktopDestination.STUDY || studyAvailable) {
                val active = selected == destination
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(if (active) DesktopPalette.railSelected else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { onNavigate(destination) }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(destination.mark, color = if (active) DesktopPalette.aqua else DesktopPalette.railMuted)
                    Text(destination.label, color = if (active) Color.White else DesktopPalette.railMuted,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        Text("离线学习已就绪", color = DesktopPalette.railMuted,
            style = androidx.compose.material.MaterialTheme.typography.caption,
            modifier = Modifier.padding(start = 16.dp))
    }
}

private object DesktopNavigationAssets
