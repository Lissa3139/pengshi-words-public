package com.pengshi.words.stats

/** Assigns x-axis labels to the first row where their measured bounds do not overlap. */
object ChartLabelRows {
    fun assign(
        centers: List<Float>,
        widths: List<Float>,
        gap: Float = 8f,
    ): List<Int> {
        require(centers.size == widths.size) { "Each label center must have a measured width" }
        require(gap >= 0f) { "Label gap cannot be negative" }

        val rows = mutableListOf<MutableList<Int>>()
        return centers.indices.map { index ->
            val row = rows.indexOfFirst { occupied ->
                occupied.none { other ->
                    kotlin.math.abs(centers[index] - centers[other]) <
                        (widths[index] + widths[other]) / 2f + gap
                }
            }.takeIf { it >= 0 } ?: rows.size
            if (row == rows.size) rows.add(mutableListOf())
            rows[row] += index
            row
        }
    }
}
