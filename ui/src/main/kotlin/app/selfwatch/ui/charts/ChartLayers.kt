/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.charts

import androidx.annotation.ColorInt
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample

/**
 * Which value scale a layer is drawn against. At most two scales are visible (LEFT and RIGHT); layers on
 * the same side share one scale. HIDDEN layers are normalised to their own range, draw no axis labels
 * and show their value only in the scrub bubble.
 */
enum class Axis { LEFT, RIGHT, HIDDEN }

/**
 * How a layer's shown value (`value / scale`) is written in the scrub bubble, TalkBack and axis labels.
 * [NUMBER] is a plain decimal. [MIN_SEC] reads the shown value as minutes and writes "m:ss" (pace): give
 * such a layer seconds as raw values and `scale = 60`, so the chart still draws minutes.
 */
enum class ValueStyle { NUMBER, MIN_SEC }

/**
 * One layer of an [OverlayChartView]. The chart draws stages and bands first, then bars, then lines,
 * whatever order the list is in. [id] is stable (it keys visibility, chips and saved state); [label] is
 * shown on chips, in the scrub bubble and to TalkBack; [color] is the series colour.
 */
sealed interface ChartLayer {
    /** Stable id, unique within one chart. */
    val id: String

    /** User-facing name, e.g. "Heart rate". */
    val label: String

    /** Series colour (for stage layers: the chip colour; blocks use the per-stage colours). */
    @get:ColorInt
    val color: Int

    /** Scale this layer is drawn against. */
    val axis: Axis

    /**
     * True for the chart's anchor series (e.g. weight on the Weight screen): its chip stays on and cannot be
     * switched off, so it works as a legend entry only.
     */
    val lockedOn: Boolean get() = false
}

/**
 * A line through timed samples, e.g. heart rate once a second. [points] are epoch seconds in ascending
 * order, [values] the raw readings; the shown value is `value / scale` (HRV stored ×1 has scale 1,
 * breathing stored ×10 has scale 10). The line breaks where two samples are more than [gapSec] apart.
 * [unit] is appended in the scrub bubble ("bpm"). [smooth] draws curves instead of straight segments.
 */
data class LineLayer(
    override val id: String,
    override val label: String,
    @param:ColorInt @get:ColorInt override val color: Int,
    override val axis: Axis,
    val points: LongArray,
    val values: IntArray,
    val scale: Int = 1,
    val smooth: Boolean = false,
    val gapSec: Long = 600,
    val unit: String = "",
) : ChartLayer {
    override fun equals(other: Any?): Boolean =
        other is LineLayer && other.id == id && other.label == label && other.color == color && other.axis == axis &&
            other.points.contentEquals(points) && other.values.contentEquals(values) && other.scale == scale &&
            other.smooth == smooth && other.gapSec == gapSec && other.unit == unit

    override fun hashCode(): Int = (id.hashCode() * 31 + points.contentHashCode()) * 31 + values.contentHashCode()
}

/**
 * Bars of equal width: bucket `i` covers `[startSec + i × bucketSec, startSec + (i + 1) × bucketSec)` and
 * has the raw value `values[i]` (shown as `value / scale`). Negative values mean "no data" and draw nothing,
 * unless [signed] is true: then negative bars hang below the zero line (e.g. a calorie deficit) and only
 * [NO_DATA] means "no data". [heightFraction] (0..1] keeps the bars in the bottom part of the plot so they
 * sit under the other layers (drawn lighter); it only applies to [Axis.HIDDEN] layers, which have their own scale.
 */
data class BarLayer(
    override val id: String,
    override val label: String,
    @param:ColorInt @get:ColorInt override val color: Int,
    override val axis: Axis,
    val startSec: Long,
    val bucketSec: Long,
    val values: IntArray,
    val scale: Int = 1,
    val unit: String = "",
    val signed: Boolean = false,
    val heightFraction: Float = 1f,
) : ChartLayer {
    /** True when bucket [i] holds a value that is drawn. */
    fun hasValue(i: Int): Boolean = i in values.indices && (if (signed) values[i] != NO_DATA else values[i] >= 0)

    override fun equals(other: Any?): Boolean =
        other is BarLayer && other.id == id && other.label == label && other.color == color && other.axis == axis &&
            other.startSec == startSec && other.bucketSec == bucketSec && other.values.contentEquals(values) &&
            other.scale == scale && other.unit == unit && other.signed == signed && other.heightFraction == heightFraction

    override fun hashCode(): Int = (id.hashCode() * 31 + startSec.hashCode()) * 31 + values.contentHashCode()

    /** Constants. */
    companion object {
        /** "No data" in a [signed] bar layer. */
        const val NO_DATA: Int = Int.MIN_VALUE
    }
}

/**
 * Scattered readings with a smoothed trend through them, e.g. weigh-ins: a dot per reading at [points]
 * (epoch seconds, ascending) with raw [values], a smooth line through [trend] (one value per point; empty
 * draws no line), and an optional dashed [goal] line. All values are shown divided by [scale] and written
 * as [style] says. The scrub reading is the nearest reading within [gapSec] of the finger.
 */
data class DotTrendLayer(
    override val id: String,
    override val label: String,
    @param:ColorInt @get:ColorInt override val color: Int,
    override val axis: Axis,
    val points: LongArray,
    val values: IntArray,
    val trend: IntArray = IntArray(0),
    val goal: Int? = null,
    val scale: Int = 1,
    val gapSec: Long = 43_200L,
    val unit: String = "",
    override val lockedOn: Boolean = false,
    val style: ValueStyle = ValueStyle.NUMBER,
) : ChartLayer {
    /** Number of usable readings. */
    val size: Int get() = minOf(points.size, values.size)

    override fun equals(other: Any?): Boolean =
        other is DotTrendLayer && other.id == id && other.label == label && other.color == color && other.axis == axis &&
            other.points.contentEquals(points) && other.values.contentEquals(values) && other.trend.contentEquals(trend) &&
            other.goal == goal && other.scale == scale && other.gapSec == gapSec && other.unit == unit && other.lockedOn == lockedOn &&
            other.style == style

    override fun hashCode(): Int = (id.hashCode() * 31 + points.contentHashCode()) * 31 + values.contentHashCode()
}

/** One block of a hypnogram: [stage] from [startSec] (inclusive) to [endSec] (exclusive). */
data class StageSpan(val startSec: Long, val endSec: Long, val stage: SleepStage) {
    /** Helpers for building spans. */
    companion object {
        /**
         * Spans from stage samples (each lasts until the next sample; the last until [endSec]). Samples are
         * sorted by time first; zero-length spans are dropped.
         */
        fun fromSamples(samples: List<SleepStageSample>, endSec: Long): List<StageSpan> {
            val sorted = samples.sortedBy { it.tSec }
            val out = ArrayList<StageSpan>(sorted.size)
            for (i in sorted.indices) {
                val start = sorted[i].tSec
                val end = if (i + 1 < sorted.size) sorted[i + 1].tSec else endSec
                if (end > start) out += StageSpan(start, end, sorted[i].stage)
            }
            return out
        }
    }
}

/**
 * Sleep stages as coloured blocks, each stage on its own row (awake at the top, deep at the bottom).
 * Spans must be sorted and must not overlap. Unmeasurable spans leave a gap. The axis is ignored.
 */
data class StageLayer(
    override val id: String,
    override val label: String,
    @param:ColorInt @get:ColorInt override val color: Int,
    val spans: List<StageSpan>,
    override val axis: Axis = Axis.HIDDEN,
) : ChartLayer

/**
 * A min–max range per time point, e.g. the daily heart-rate range in a month view. [points] are epoch
 * seconds ascending; [low] and [high] the raw bounds (shown divided by [scale]). The band breaks at gaps
 * longer than [gapSec].
 */
data class BandLayer(
    override val id: String,
    override val label: String,
    @param:ColorInt @get:ColorInt override val color: Int,
    override val axis: Axis,
    val points: LongArray,
    val low: IntArray,
    val high: IntArray,
    val scale: Int = 1,
    val gapSec: Long = 2 * 86_400L,
    val unit: String = "",
) : ChartLayer {
    override fun equals(other: Any?): Boolean =
        other is BandLayer && other.id == id && other.label == label && other.color == color && other.axis == axis &&
            other.points.contentEquals(points) && other.low.contentEquals(low) && other.high.contentEquals(high) &&
            other.scale == scale && other.gapSec == gapSec && other.unit == unit

    override fun hashCode(): Int = (id.hashCode() * 31 + points.contentHashCode()) * 31 + low.contentHashCode()
}

/**
 * One layer's reading at a scrub time, for the bubble, [OverlayChartView.onScrub] and TalkBack.
 * [value] is in display units (already divided by the scale); [low]/[high] are set for bands and [stage]
 * for stage layers. Everything is null when the layer has no data at that time. [text] is the formatted
 * reading with its unit ("52 bpm", "Deep", "48–61 ms") or "–" for no data.
 */
data class LayerValue(
    val layerId: String,
    val label: String,
    @param:ColorInt @get:ColorInt val color: Int,
    val value: Double?,
    val text: String,
    val low: Double? = null,
    val high: Double? = null,
    val stage: SleepStage? = null,
)
