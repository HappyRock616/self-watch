/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.charts

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.time.TimeRange
import app.selfwatch.ui.R
import app.selfwatch.ui.components.SwContrast
import app.selfwatch.ui.components.ToggleChipGroup
import app.selfwatch.ui.components.ToggleItem
import app.selfwatch.ui.components.dp
import app.selfwatch.ui.components.sp
import app.selfwatch.ui.components.themeColor
import app.selfwatch.ui.format.Fmt
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Self Watch's overlay chart: several related series over one time range, e.g. a night's sleep stages
 * with heart rate, HRV and breathing on top. Pair it with a [ToggleChipGroup] ([bindChips]) to switch
 * layers on and off.
 *
 * **Layout.** When a [StageLayer] is visible together with other layers, the stages take the top half of
 * the plot as blocks (one row per stage) and the other layers share the bottom half; otherwise each fills
 * the plot. Layers on [Axis.LEFT] and [Axis.RIGHT] get labelled scales (3–5 nice ticks); [Axis.HIDDEN]
 * layers are scaled to their own range. The time axis uses the phone's 12/24-hour setting.
 *
 * **Touch.** Touch and drag scrubs: a vertical line and a bubble with every visible layer's value (also
 * sent to [onScrub]). Double tap zooms 2× around the finger (at full zoom it resets). Pinch zooms; while
 * zoomed, a two-finger drag pans. One-finger drags are kept for scrubbing so the chart works inside a
 * scrolling page (vertical drags still scroll the page).
 *
 * **Right-to-left.** Time always runs left to right, as on a clock or in the watch apps; the LEFT and RIGHT
 * axes are physical sides. Labels and the bubble text follow the locale.
 *
 * **Accessibility.** TalkBack sees about 12 time slices as virtual views ("02:00: Deep, Heart rate 52 bpm")
 * plus a "Read as table" action that lists the whole window ([onReadAsTable], or a simple dialog).
 *
 * **Performance.** Paths are built only when data, size, visibility or the zoom window change, from data
 * down-sampled to at most two points per pixel column, so `onDraw` only strokes a few paths.
 *
 * Zoom window and hidden layers survive configuration changes when the view has an id.
 */
class OverlayChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ---- Public API ----

    /** The full time range shown when not zoomed. Setting it resets the zoom. */
    var range: TimeRange = TimeRange(0, 0)
        set(v) {
            field = v
            full = Window(v.startSec, v.endSec)
            val pending = pendingWindow
            win = if (pending != null && pendingRange == full) pending else full
            pendingWindow = null
            scrubT = null
            dataChanged()
        }

    /** The layers, drawn stages and bands first, then bars, then lines, then dot-trend layers (stable within each group). */
    var layers: List<ChartLayer> = emptyList()
        set(v) {
            field = v
            ordered = v.sortedBy { drawOrder(it) }
            dataChanged()
        }

    /** Called while scrubbing with the time under the finger and every visible layer's value there. */
    var onScrub: ((tSec: Long, values: List<LayerValue>) -> Unit)? = null

    /**
     * Called by the "Read as table" accessibility action with one line per time step. Null shows the
     * lines in a simple dialog.
     */
    var onReadAsTable: ((title: String, rows: List<String>) -> Unit)? = null

    /** Time zone for the time axis and labels. */
    var zone: ZoneId = ZoneId.systemDefault()
        set(v) {
            field = v
            format = ChartFormat(context, v)
            dataChanged()
        }

    /** Colours of the hypnogram blocks; defaults to the theme's stage colours. */
    var stageColors: Map<SleepStage, Int> = SleepStage.entries.associateWith { defaultStageColor(context, it) }
        set(v) {
            field = v
            invalidate()
        }

    /** The time window currently shown (equal to [range] unless zoomed). */
    val visibleWindow: TimeRange get() = TimeRange(win.start, win.end)

    /** The scrub time, or null when the scrub line is hidden. */
    val scrubTime: Long? get() = scrubT

    /** Shows or hides one layer. Unknown ids are remembered and apply if that layer is added later. */
    fun setVisible(layerId: String, visible: Boolean) {
        val changed = if (visible) hidden.remove(layerId) else hidden.add(layerId)
        if (changed) dataChanged()
    }

    /** True unless the layer was hidden with [setVisible]. */
    fun isLayerVisible(layerId: String): Boolean = layerId !in hidden

    /**
     * Fills [group] with one chip per layer (label and colour) and keeps the chart's visibility in step with
     * it. Replaces the group's [ToggleChipGroup.onChange]. Set the group's `chartId` first to remember the
     * choice across app restarts.
     */
    fun bindChips(group: ToggleChipGroup) {
        boundChips = group
        group.items = layers.map { ToggleItem(it.id, it.label, it.color, initiallyOn = it.id !in hidden, locked = it.lockedOn) }
        val on = group.onIds
        for (l in layers) {
            if (l.id in on || l.lockedOn) hidden.remove(l.id) else hidden.add(l.id)
        }
        group.onChange = { id, isOn, _ -> setVisible(id, isOn) }
        dataChanged()
    }

    /** Zooms by [factor] (2 = twice as close) around [anchorSec]; the result stays inside [range]. */
    fun zoomBy(factor: Double, anchorSec: Long = (win.start + win.end) / 2) {
        setWindow(ChartMath.zoom(win, full, factor, anchorSec))
    }

    /** Shows the whole [range] again. */
    fun resetZoom() {
        setWindow(full)
    }

    /** Moves the scrub line to [tSec] (null hides it) and fires [onScrub]. */
    fun scrubTo(tSec: Long?) {
        if (tSec == scrubT) return
        scrubT = tSec
        scrubLabel = null
        if (tSec != null) onScrub?.invoke(tSec, valuesAt(tSec))
        invalidate()
    }

    /** Every visible layer's value at [tSec], in drawing order (stages first). */
    fun valuesAt(tSec: Long): List<LayerValue> = visibleLayers().map { valueOf(it, tSec) }

    /** The rows the "Read as table" action shows: one line per time step across the visible window. */
    fun tableRows(): List<String> {
        if (win.span <= 0) return emptyList()
        val step = ChartMath.timeStep(win.start, win.end, TABLE_ROWS, format.offsetSec(win.start))
        return ChartMath.timeTicks(win.start, win.end, TABLE_ROWS, format.offsetSec(win.start))
            .filter { it < win.end }
            .map { describeAt(it, step) }
    }

    // ---- State ----

    private var ordered: List<ChartLayer> = emptyList()
    private val hidden = LinkedHashSet<String>()
    private var full = Window(0, 0)
    private var win = Window(0, 0)
    private var pendingWindow: Window? = null
    private var pendingRange: Window? = null
    private var scrubT: Long? = null
    private var scrubLabel: String? = null
    private var boundChips: ToggleChipGroup? = null
    private var format = ChartFormat(context, zone)
    private var dirty = true

    // ---- Layout and caches (rebuilt when dirty) ----

    private val plot = RectF()
    private val stageRect = RectF()
    private val valueRect = RectF()
    private var leftTicks: Ticks? = null
    private var rightTicks: Ticks? = null
    private var leftLabels: Array<String> = emptyArray()
    private var rightLabels: Array<String> = emptyArray()
    private var leftLabelColor = 0
    private var rightLabelColor = 0
    private var timeTickX = FloatArray(0)
    private var timeTickLabels: Array<String> = emptyArray()
    private val caches = ArrayList<LayerCache>()
    private val stagePaths = Array(ChartMath.STAGE_ROWS) { Path() }
    private val stageRowStage = arrayOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)
    private var hasStages = false
    private var hasData = false
    private var layoutDirty = true
    private var visibleCache: List<ChartLayer> = emptyList()
    private val layerTicks = HashMap<String, Ticks>()
    private var timeLabelW = 0f

    /**
     * A layer's prepared drawing: a [path] and, for straight lines, per-column hairlines in [columns]; dot-trend
     * layers add their [trendPath] and dashed [goalPath].
     */
    private class LayerCache(
        val layer: ChartLayer,
        val path: Path,
        val lo: Double,
        val hi: Double,
        val columns: FloatArray?,
        val trendPath: Path? = null,
        val goalPath: Path? = null,
    )

    // ---- Paints ----

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(context.dp(5f), context.dp(4f)), 0f)
    }
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f // hairline: exactly one device pixel per column
    }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, context.dp(0.75f))
    }
    private val scrubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(12f) }
    private val bubbleText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.sp(13f) }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
    }
    private val rect = RectF()

    private val mutedColor = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface)
    private val surface = themeColor(com.google.android.material.R.attr.colorSurface)

    init {
        gridPaint.color = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
        scrubPaint.color = onSurface
        textPaint.color = mutedColor
        bubbleText.color = onSurface
        bubblePaint.color = themeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        bubbleStroke.color = themeColor(com.google.android.material.R.attr.colorOutline)
        isFocusable = true
        isClickable = true
        isSaveEnabled = true
    }

    // ---- Measuring and drawing ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = context.dp(DEFAULT_HEIGHT_DP).toInt() + textHeight().toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dirty = true
        layoutDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        if (dirty) rebuild()
        if (!hasData) {
            drawEmpty(canvas)
            return
        }
        // Grid for the primary scale.
        val grid = leftTicks ?: rightTicks
        if (grid != null) {
            for (v in grid.values()) {
                val y = ChartMath.valueToY(v, grid.lo, grid.hi, valueRect.top, valueRect.bottom)
                canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            }
        }
        canvas.withClip(plot.left, plot.top - context.dp(2f), plot.right, plot.bottom + context.dp(2f)) { drawLayers(this) }
        drawAxes(canvas)
        scrubT?.let { drawScrub(canvas, it) }
    }

    private fun drawLayers(canvas: Canvas) {
        if (hasStages) {
            for (row in 0 until ChartMath.STAGE_ROWS) {
                fillPaint.color = stageColors[stageRowStage[row]] ?: onSurface
                canvas.drawPath(stagePaths[row], fillPaint)
            }
        }
        for (c in caches) {
            when (val l = c.layer) {
                is BandLayer -> {
                    fillPaint.color = ColorUtils.setAlphaComponent(l.color, BAND_ALPHA)
                    canvas.drawPath(c.path, fillPaint)
                }
                is BarLayer -> {
                    // Low backdrop bars (a height fraction under 1) are lighter so the lines over them stay clear.
                    val alpha = if (l.axis == Axis.HIDDEN && l.heightFraction < 1f) BACKDROP_BAR_ALPHA else BAR_ALPHA
                    fillPaint.color = ColorUtils.setAlphaComponent(l.color, alpha)
                    canvas.drawPath(c.path, fillPaint)
                }
                is LineLayer -> if (l.smooth) {
                    linePaint.color = l.color
                    canvas.drawPath(c.path, linePaint)
                } else {
                    fillPaint.color = l.color
                    canvas.drawPath(c.path, fillPaint)
                    val cols = c.columns
                    if (cols != null && cols.isNotEmpty()) {
                        hairPaint.color = l.color
                        canvas.drawLines(cols, hairPaint)
                    }
                }
                is DotTrendLayer -> {
                    c.goalPath?.let {
                        goalPaint.color = l.color
                        canvas.drawPath(it, goalPaint)
                    }
                    fillPaint.color = ColorUtils.setAlphaComponent(l.color, DOT_ALPHA)
                    canvas.drawPath(c.path, fillPaint)
                    c.trendPath?.let {
                        linePaint.color = l.color
                        canvas.drawPath(it, linePaint)
                    }
                }
                is StageLayer -> Unit
            }
        }
    }

    private fun drawEmpty(canvas: Canvas) {
        val text = context.getString(R.string.sw_chart_no_data)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, width / 2f, height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawAxes(canvas: Canvas) {
        val half = (textPaint.ascent() + textPaint.descent()) / 2f
        leftTicks?.let { t ->
            textPaint.color = leftLabelColor
            textPaint.textAlign = Paint.Align.RIGHT
            t.values().forEachIndexed { i, v ->
                val y = ChartMath.valueToY(v, t.lo, t.hi, valueRect.top, valueRect.bottom)
                canvas.drawText(leftLabels[i], plot.left - context.dp(4f), y - half, textPaint)
            }
        }
        rightTicks?.let { t ->
            textPaint.color = rightLabelColor
            textPaint.textAlign = Paint.Align.LEFT
            t.values().forEachIndexed { i, v ->
                val y = ChartMath.valueToY(v, t.lo, t.hi, valueRect.top, valueRect.bottom)
                canvas.drawText(rightLabels[i], plot.right + context.dp(4f), y - half, textPaint)
            }
        }
        textPaint.color = mutedColor
        textPaint.textAlign = Paint.Align.CENTER
        val baseline = plot.bottom + context.dp(4f) - textPaint.ascent()
        for (i in timeTickX.indices) {
            val label = timeTickLabels[i]
            val w = textPaint.measureText(label) / 2f
            val x = timeTickX[i].coerceIn(w + paddingLeft, width - paddingRight - w)
            canvas.drawText(label, x, baseline, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawScrub(canvas: Canvas, t: Long) {
        if (t < win.start || t > win.end) return
        val x = ChartMath.timeToX(t, win, plot.left, plot.right)
        canvas.drawLine(x, plot.top, x, plot.bottom, scrubPaint)
        val values = valuesAt(t)
        // Dots on lines.
        for (c in caches) {
            val l = c.layer
            val pts: LongArray
            val vals: IntArray
            val scale: Int
            val gap: Long
            when (l) {
                is LineLayer -> {
                    pts = l.points; vals = l.values; scale = l.scale; gap = l.gapSec
                }
                is DotTrendLayer -> {
                    pts = l.points; vals = l.values; scale = l.scale; gap = l.gapSec
                }
                else -> continue
            }
            val i = ChartMath.lineIndexAt(pts, min(pts.size, vals.size), t, gap)
            if (i < 0) continue
            val y = ChartMath.valueToY(vals[i].toDouble() / scale, c.lo, c.hi, valueRect.top, valueRect.bottom)
            fillPaint.color = l.color
            canvas.drawCircle(ChartMath.timeToX(pts[i], win, plot.left, plot.right), y, context.dp(4f), fillPaint)
        }
        // Bubble: the clock time on charts of a day or less, the date on longer ones.
        val title = scrubLabel ?: (if (full.span >= 2 * DAY_SEC) format.date(t) else format.time(t)).also { scrubLabel = it }
        val pad = context.dp(8f)
        val dot = context.dp(8f)
        val lineH = bubbleText.descent() - bubbleText.ascent()
        var textW = bubbleText.measureText(title)
        val rows = ArrayList<String>(values.size)
        for (v in values) {
            // Stage rows show just the stage ("Light"), in its colour; others "Heart rate 52 bpm".
            val s = if (v.stage != null) v.text else context.getString(R.string.sw_chart_layer_value, v.label, v.text)
            rows += s
            textW = max(textW, bubbleText.measureText(s) + dot + pad / 2f)
        }
        val bw = textW + 2 * pad
        val bh = lineH * (rows.size + 1) + 2 * pad
        val left = ChartMath.bubbleLeft(x, bw, paddingLeft.toFloat(), (width - paddingRight).toFloat(), context.dp(8f))
        val top = plot.top
        rect.set(left, top, left + bw, top + bh)
        val r = context.dp(8f)
        canvas.drawRoundRect(rect, r, r, bubblePaint)
        canvas.drawRoundRect(rect, r, r, bubbleStroke)
        var baseline = top + pad - bubbleText.ascent()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        bubbleText.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        val textStart = if (rtl) rect.right - pad else rect.left + pad
        canvas.drawText(title, textStart, baseline, bubbleText)
        for (i in rows.indices) {
            baseline += lineH
            fillPaint.color = values[i].color
            val cy = baseline + (bubbleText.ascent() + bubbleText.descent()) / 2f
            val cx = if (rtl) textStart - dot / 2f else textStart + dot / 2f
            canvas.drawCircle(cx, cy, dot / 2f, fillPaint)
            val tx = if (rtl) textStart - dot - pad / 2f else textStart + dot + pad / 2f
            canvas.drawText(rows[i], tx, baseline, bubbleText)
        }
        bubbleText.textAlign = Paint.Align.LEFT
    }

    private fun textHeight(): Float = textPaint.descent() - textPaint.ascent()

    // ---- Rebuilding paths ----

    /** Rebuilds what is stale: the layout (scales, plot area) after data or size changes, then the paths. */
    private fun rebuild() {
        dirty = false
        if (layoutDirty) {
            layoutDirty = false
            rebuildLayout()
        }
        if (hasData) rebuildPaths()
    }

    /** Scales, axis labels and plot area: depend on data, visibility and size, not on the zoom window. */
    private fun rebuildLayout() {
        visibleCache = visibleLayers()
        val visible = visibleCache
        hasData = width > 0 && height > 0 && full.span > 0 && visible.any { hasPoints(it) }
        if (!hasData) return

        val values = visible.filter { it !is StageLayer }
        hasStages = visible.any { it is StageLayer && it.spans.isNotEmpty() }

        // Vertical layout.
        val top = paddingTop + textHeight() / 2f + context.dp(4f)
        val bottom = height - paddingBottom - textHeight() - context.dp(6f)
        val plotH = max(0f, bottom - top)
        val stageOnly = values.none { hasPoints(it) }
        val stageH = when {
            !hasStages -> 0f
            stageOnly -> plotH
            else -> plotH * STAGE_FRACTION
        }
        val valueTop = if (hasStages && !stageOnly) top + stageH + context.dp(8f) else top
        val maxTicks = (max(0f, bottom - valueTop) / (textHeight() * 1.8f)).toInt().coerceIn(2, 5)

        // Scales.
        val leftLayers = values.filter { it.axis == Axis.LEFT }
        val rightLayers = values.filter { it.axis == Axis.RIGHT }
        leftTicks = domainOf(leftLayers)?.let { ChartMath.niceTicks(it.first, it.second, maxTicks) }
        rightTicks = domainOf(rightLayers)?.let { ChartMath.niceTicks(it.first, it.second, maxTicks) }
        leftLabels = labelsFor(leftTicks, leftLayers)
        rightLabels = labelsFor(rightTicks, rightLayers)
        // Axis labels take their layer's colour, darkened or lightened to 4.5:1 on the card if needed.
        leftLabelColor = if (rightTicks != null && leftLayers.size == 1) SwContrast.ensure(leftLayers[0].color, surface) else mutedColor
        rightLabelColor = if (rightLayers.size == 1) SwContrast.ensure(rightLayers[0].color, surface) else mutedColor
        val gap = context.dp(8f)
        val leftW = if (leftTicks != null) leftLabels.maxOf { textPaint.measureText(it) } + gap else 0f
        val rightW = if (rightTicks != null) rightLabels.maxOf { textPaint.measureText(it) } + gap else 0f
        // Whole-pixel left edge so line columns fall exactly on device pixels.
        plot.set(ceil(paddingLeft + leftW), top, width - paddingRight - rightW, bottom)
        stageRect.set(plot.left, top, plot.right, top + stageH)
        valueRect.set(plot.left, valueTop, plot.right, bottom)
        layerTicks.clear()
        for (l in values) {
            val ticks = when (l.axis) {
                Axis.LEFT -> leftTicks
                Axis.RIGHT -> rightTicks
                Axis.HIDDEN -> domainOf(listOf(l))?.let {
                    val top = if (l is BarLayer) ChartMath.stretchTop(it.first, it.second, l.heightFraction) else it.second
                    ChartMath.niceTicks(it.first, top, 3)
                }
            } ?: continue
            layerTicks[l.id] = ticks
        }
        timeLabelW = textPaint.measureText(format.time(full.start + 12 * 3600L)) * 1.6f
    }

    /** Layer paths and time-axis labels for the current zoom window. */
    private fun rebuildPaths() {
        caches.clear()
        stagePaths.forEach { it.reset() }
        val widthPx = max(1, plot.width().toInt())
        if (hasStages) {
            for (l in visibleCache) if (l is StageLayer) buildStages(l)
        }
        for (l in visibleCache) {
            if (l is StageLayer) continue
            val ticks = layerTicks[l.id] ?: continue
            val path = Path()
            var columns: FloatArray? = null
            var trendPath: Path? = null
            var goalPath: Path? = null
            when (l) {
                is LineLayer -> columns = buildLine(l, path, ticks, widthPx)
                is BarLayer -> buildBars(l, path, ticks)
                is BandLayer -> buildBand(l, path, ticks, widthPx)
                is DotTrendLayer -> {
                    trendPath = Path()
                    goalPath = Path()
                    buildDotTrend(l, path, trendPath, goalPath, ticks)
                }
                is StageLayer -> Unit
            }
            caches += LayerCache(l, path, ticks.lo, ticks.hi, columns, trendPath, goalPath)
        }

        // Time axis.
        val timeTicks = (plot.width() / max(1f, timeLabelW)).toInt().coerceIn(2, 6)
        val offset = format.offsetSec(win.start)
        val step = ChartMath.timeStep(win.start, win.end, timeTicks, offset)
        val ticks = ChartMath.timeTicks(win.start, win.end, timeTicks, offset)
        timeTickX = FloatArray(ticks.size) { ChartMath.timeToX(ticks[it], win, plot.left, plot.right) }
        timeTickLabels = Array(ticks.size) { format.axis(ticks[it], step) }
    }

    /** Axis labels for [t]; "m:ss" when every layer on the axis ([ls]) is a [ValueStyle.MIN_SEC] layer. */
    private fun labelsFor(t: Ticks?, ls: List<ChartLayer>): Array<String> {
        if (t == null) return emptyArray()
        val d = t.decimals
        val minSec = ls.isNotEmpty() && ls.all { it is DotTrendLayer && it.style == ValueStyle.MIN_SEC }
        return t.values().map { if (minSec) Fmt.minSec(it * SEC_PER_MIN) else format.number(it, d) }.toTypedArray()
    }

    /** [v] (a shown value, `raw / scale`) as text in [style], with [decimals] for plain numbers. */
    private fun valueText(v: Double, decimals: Int, style: ValueStyle): String = when (style) {
        ValueStyle.NUMBER -> format.number(v, decimals)
        ValueStyle.MIN_SEC -> Fmt.minSec(v * SEC_PER_MIN)
    }

    private fun buildStages(l: StageLayer) {
        val rowH = stageRect.height() / ChartMath.STAGE_ROWS
        val inset = min(context.dp(1f), rowH / 4f)
        for (s in l.spans) {
            if (s.endSec <= win.start || s.startSec >= win.end) continue
            val row = ChartMath.stageRow(s.stage)
            if (row < 0) continue
            val x0 = ChartMath.timeToX(max(s.startSec, win.start), win, plot.left, plot.right)
            val x1 = ChartMath.timeToX(min(s.endSec, win.end), win, plot.left, plot.right)
            val y0 = stageRect.top + row * rowH + inset
            stagePaths[row].addRect(x0, y0, max(x1, x0 + 1f), y0 + rowH - 2 * inset, Path.Direction.CW)
        }
    }

    private fun buildLine(l: LineLayer, path: Path, t: Ticks, widthPx: Int): FloatArray? {
        val s = ChartMath.downsample(l.points, l.values, win, widthPx, l.gapSec)
        if (s.isEmpty) return null
        if (l.smooth) {
            buildSmoothLine(l, s, path, t)
            return null
        }
        return buildEnvelopeLine(l, s, path, t, widthPx)
    }

    /**
     * Straight lines as one vertical hairline per pixel column, spanning the line's extent in that column
     * plus half the line width either side (see [ChartMath.envelope]). Anti-aliased hairlines are the
     * cheapest thing to draw in both software and hardware rendering, and on whole-pixel columns they look
     * exactly like a stroked line. Isolated single samples become dots in [path].
     */
    private fun buildEnvelopeLine(l: LineLayer, s: Sampled, path: Path, t: Ticks, widthPx: Int): FloatArray {
        val ys = FloatArray(s.size) {
            ChartMath.valueToY(s.a[it].toDouble() / l.scale, t.lo, t.hi, valueRect.top, valueRect.bottom)
        }
        val hw = linePaint.strokeWidth / 2f
        val bounds = IntArray(s.breaks.size + 2)
        s.breaks.copyInto(bounds, 1)
        bounds[bounds.size - 1] = s.size
        val parts = ArrayList<Envelope>(bounds.size)
        var total = 0
        for (k in 0 until bounds.size - 1) {
            val from = bounds[k]
            val to = bounds[k + 1]
            if (to - from == 1) {
                path.addCircle(plot.left + s.xs[from], ys[from], hw * 1.5f, Path.Direction.CW)
                continue
            }
            val e = ChartMath.envelope(s.xs, ys, from, to, -1, widthPx)
            if (e.size == 0) continue
            parts += e
            total += e.size
        }
        val out = FloatArray(total * 4)
        var o = 0
        for (e in parts) for (i in 0 until e.size) {
            val x = plot.left + e.x[i]
            out[o++] = x
            out[o++] = e.top[i] - hw
            out[o++] = x
            out[o++] = e.bottom[i] + hw
        }
        return out
    }

    /** Curved lines, stroked (slower than [buildEnvelopeLine]; meant for sparse series). */
    private fun buildSmoothLine(l: LineLayer, s: Sampled, path: Path, t: Ticks) {
        var b = 0
        var segStart = 0
        var px = 0f
        var py = 0f
        val dotR = linePaint.strokeWidth / 2f
        for (i in 0 until s.size) {
            val x = plot.left + s.xs[i]
            val y = ChartMath.valueToY(s.a[i].toDouble() / l.scale, t.lo, t.hi, valueRect.top, valueRect.bottom)
            val newSeg = i == 0 || (b < s.breaks.size && s.breaks[b] == i)
            if (newSeg) {
                if (i > 0) {
                    b++
                    if (i - segStart == 1) path.addCircle(px, py, dotR, Path.Direction.CW)
                }
                segStart = i
                path.moveTo(x, y)
            } else {
                val mx = (px + x) / 2f
                val my = (py + y) / 2f
                path.quadTo(px, py, mx, my)
                if (i == s.size - 1 || (b < s.breaks.size && s.breaks[b] == i + 1)) path.lineTo(x, y)
            }
            px = x
            py = y
        }
        if (s.size - segStart == 1) path.addCircle(px, py, dotR, Path.Direction.CW)
    }

    private fun buildBars(l: BarLayer, path: Path, t: Ticks) {
        if (l.bucketSec <= 0) return
        val base = ChartMath.valueToY(0.0.coerceIn(t.lo, t.hi), t.lo, t.hi, valueRect.top, valueRect.bottom)
        val first = max(0L, (win.start - l.startSec) / l.bucketSec).toInt()
        val gap = context.dp(1f)
        for (i in first until l.values.size) {
            val start = l.startSec + i * l.bucketSec
            if (start >= win.end) break
            if (!l.hasValue(i)) continue
            val v = l.values[i]
            val x0 = ChartMath.timeToX(start, win, plot.left, plot.right)
            val x1 = ChartMath.timeToX(start + l.bucketSec, win, plot.left, plot.right)
            val g = min(gap, (x1 - x0) / 4f)
            val y = ChartMath.valueToY(v.toDouble() / l.scale, t.lo, t.hi, valueRect.top, valueRect.bottom)
            path.addRect(x0 + g, min(y, base), max(x0 + g + 1f, x1 - g), max(y, base), Path.Direction.CW)
        }
    }

    /** Dots for the readings in the window, a curve through the trend and a dashed goal line. */
    private fun buildDotTrend(l: DotTrendLayer, dots: Path, trend: Path, goal: Path, t: Ticks) {
        val n = l.size
        fun y(raw: Int): Float = ChartMath.valueToY(raw.toDouble() / l.scale, t.lo, t.hi, valueRect.top, valueRect.bottom)
        val r = context.dp(DOT_RADIUS_DP)
        // One reading either side of the window keeps the trend running to the edges.
        val from = max(0, ChartMath.lowerBound(l.points, n, win.start) - 1)
        val to = min(n, ChartMath.lowerBound(l.points, n, win.end) + 1)
        for (i in from until to) {
            dots.addCircle(ChartMath.timeToX(l.points[i], win, plot.left, plot.right), y(l.values[i]), r, Path.Direction.CW)
        }
        val tn = min(n, l.trend.size)
        var px = 0f
        var py = 0f
        for (i in from until min(to, tn)) {
            val x = ChartMath.timeToX(l.points[i], win, plot.left, plot.right)
            val yy = y(l.trend[i])
            if (i == from) {
                trend.moveTo(x, yy)
            } else {
                trend.quadTo(px, py, (px + x) / 2f, (py + yy) / 2f)
                if (i == min(to, tn) - 1) trend.lineTo(x, yy)
            }
            px = x
            py = yy
        }
        l.goal?.let {
            val gy = y(it)
            goal.moveTo(plot.left, gy)
            goal.lineTo(plot.right, gy)
        }
    }

    private fun buildBand(l: BandLayer, path: Path, t: Ticks, widthPx: Int) {
        val s = ChartMath.downsampleBand(l.points, l.low, l.high, win, widthPx, l.gapSec)
        val hi = s.b ?: return
        if (s.isEmpty) return
        val bounds = IntArray(s.breaks.size + 2)
        bounds[0] = 0
        s.breaks.copyInto(bounds, 1)
        bounds[bounds.size - 1] = s.size
        val minW = context.dp(2f)
        for (k in 0 until bounds.size - 1) {
            val from = bounds[k]
            val to = bounds[k + 1]
            if (to <= from) continue
            fun y(v: Float): Float = ChartMath.valueToY(v.toDouble() / l.scale, t.lo, t.hi, valueRect.top, valueRect.bottom)
            if (to - from == 1) {
                val x = plot.left + s.xs[from]
                path.addRect(x - minW, y(hi[from]), x + minW, y(s.a[from]), Path.Direction.CW)
                continue
            }
            path.moveTo(plot.left + s.xs[from], y(hi[from]))
            for (i in from + 1 until to) path.lineTo(plot.left + s.xs[i], y(hi[i]))
            for (i in to - 1 downTo from) path.lineTo(plot.left + s.xs[i], y(s.a[i]))
            path.close()
        }
    }

    // ---- Data helpers ----

    private fun visibleLayers(): List<ChartLayer> = ordered.filter { it.id !in hidden }

    private fun hasPoints(l: ChartLayer): Boolean = when (l) {
        is LineLayer -> l.points.isNotEmpty() && l.values.isNotEmpty()
        is BarLayer -> l.values.indices.any { l.hasValue(it) }
        is DotTrendLayer -> l.size > 0
        is BandLayer -> l.points.isNotEmpty() && l.low.isNotEmpty() && l.high.isNotEmpty()
        is StageLayer -> l.spans.any { ChartMath.stageRow(it.stage) >= 0 }
    }

    /** Display-unit min and max over every point of [ls] (bars include zero), or null with no data. */
    private fun domainOf(ls: List<ChartLayer>): Pair<Double, Double>? {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (l in ls) when (l) {
            is LineLayer -> for (i in 0 until min(l.points.size, l.values.size)) {
                val v = l.values[i].toDouble() / l.scale
                lo = min(lo, v); hi = max(hi, v)
            }
            is BarLayer -> ChartMath.barDomain(l)?.let {
                lo = min(lo, it.first); hi = max(hi, it.second)
            }
            is DotTrendLayer -> ChartMath.dotTrendDomain(l)?.let {
                lo = min(lo, it.first); hi = max(hi, it.second)
            }
            is BandLayer -> for (i in 0 until min(l.points.size, min(l.low.size, l.high.size))) {
                lo = min(lo, l.low[i].toDouble() / l.scale); hi = max(hi, l.high[i].toDouble() / l.scale)
            }
            is StageLayer -> Unit
        }
        return if (lo <= hi) lo to hi else null
    }

    private fun valueOf(l: ChartLayer, t: Long): LayerValue {
        val none = context.getString(R.string.sw_chart_no_value)
        return when (l) {
            is LineLayer -> {
                val i = ChartMath.lineIndexAt(l.points, min(l.points.size, l.values.size), t, l.gapSec)
                if (i < 0) {
                    LayerValue(l.id, l.label, l.color, null, none)
                } else {
                    val v = l.values[i].toDouble() / l.scale
                    LayerValue(l.id, l.label, l.color, v, withUnit(format.number(v, decimalsForScale(l.scale)), l.unit))
                }
            }
            is BarLayer -> {
                val i = ChartMath.bucketIndex(l.startSec, l.bucketSec, l.values.size, t)
                if (i < 0 || !l.hasValue(i)) {
                    LayerValue(l.id, l.label, l.color, null, none)
                } else {
                    val v = l.values[i].toDouble() / l.scale
                    val text = format.number(v, decimalsForScale(l.scale))
                    LayerValue(l.id, l.label, l.color, v, withUnit(if (l.signed && v > 0) "+$text" else text, l.unit))
                }
            }
            is DotTrendLayer -> {
                val i = ChartMath.lineIndexAt(l.points, l.size, t, l.gapSec)
                if (i < 0) {
                    LayerValue(l.id, l.label, l.color, null, none)
                } else {
                    val v = l.values[i].toDouble() / l.scale
                    LayerValue(l.id, l.label, l.color, v, withUnit(valueText(v, decimalsForScale(l.scale), l.style), l.unit))
                }
            }
            is BandLayer -> {
                val n = min(l.points.size, min(l.low.size, l.high.size))
                val i = ChartMath.lineIndexAt(l.points, n, t, l.gapSec)
                if (i < 0) {
                    LayerValue(l.id, l.label, l.color, null, none)
                } else {
                    val d = decimalsForScale(l.scale)
                    val lo = l.low[i].toDouble() / l.scale
                    val hi = l.high[i].toDouble() / l.scale
                    val text = context.getString(R.string.sw_chart_range, format.number(lo, d), format.number(hi, d))
                    LayerValue(l.id, l.label, l.color, (lo + hi) / 2, withUnit(text, l.unit), lo, hi)
                }
            }
            is StageLayer -> {
                val starts = LongArray(l.spans.size) { l.spans[it].startSec }
                val ends = LongArray(l.spans.size) { l.spans[it].endSec }
                val i = ChartMath.spanIndexAt(starts, ends, t)
                if (i < 0) {
                    LayerValue(l.id, l.label, l.color, null, none)
                } else {
                    val st = l.spans[i].stage
                    LayerValue(l.id, l.label, stageColors[st] ?: l.color, null, stageName(context, st), stage = st)
                }
            }
        }
    }

    private fun withUnit(text: String, unit: String): String =
        if (unit.isEmpty()) text else context.getString(R.string.sw_chart_value_unit, text, unit)

    /** One spoken or table line: "02:00: Deep, Heart rate 52 bpm, HRV 61 ms". */
    private fun describeAt(t: Long, stepSec: Long): String {
        val parts = valuesAt(t).joinToString(", ") { v ->
            if (v.stage != null) v.text else context.getString(R.string.sw_chart_layer_value, v.label, v.text)
        }
        return context.getString(R.string.sw_chart_slice, format.axis(t, stepSec), parts)
    }

    private fun dataChanged() {
        dirty = true
        layoutDirty = true
        scrubLabel = null
        updateDescription()
        a11y.invalidateRoot()
        invalidate()
    }

    private fun setWindow(w: Window) {
        if (w == win) return
        win = w
        dirty = true
        a11y.invalidateRoot()
        invalidate()
    }

    private fun updateDescription() {
        if (full.span <= 0) {
            contentDescription = context.getString(R.string.sw_chart_no_data)
            return
        }
        val names = visibleLayers().joinToString(", ") { it.label }
        contentDescription = context.getString(
            R.string.sw_chart_description,
            format.time(full.start),
            format.time(full.end),
            names.ifEmpty { context.getString(R.string.sw_chart_no_data) },
        )
    }

    private fun drawOrder(l: ChartLayer): Int = when (l) {
        is StageLayer, is BandLayer -> 0
        is BarLayer -> 1
        is LineLayer -> 2
        is DotTrendLayer -> 3
    }

    // ---- Touch ----

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var claimed = false
    private var multiTouch = false
    private var lastFocusX = Float.NaN

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!hasData) return false
            val anchor = ChartMath.xToTime(e.x, win, plot.left, plot.right)
            val minSpan = ChartMath.minSpan(full)
            if (win.span <= minSpan) resetZoom() else zoomBy(2.0, anchor)
            return true
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            lastFocusX = detector.focusX
            return hasData
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val prev = detector.previousSpanX
            val factor = if (prev > touchSlop) detector.currentSpanX / prev else detector.scaleFactor
            val anchor = ChartMath.xToTime(detector.focusX, win, plot.left, plot.right)
            var next = ChartMath.zoom(win, full, factor.toDouble(), anchor)
            if (!lastFocusX.isNaN() && plot.width() > 0f) {
                val secPerPx = next.span / plot.width()
                next = ChartMath.pan(next, full, (-(detector.focusX - lastFocusX) * secPerPx).toLong())
            }
            lastFocusX = detector.focusX
            setWindow(next)
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                claimed = false
                multiTouch = false
                if (hasData) scrubTo(ChartMath.xToTime(event.x, win, plot.left, plot.right))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubTo(null)
            }
            MotionEvent.ACTION_MOVE -> if (!multiTouch && !scaler.isInProgress && hasData) {
                if (!claimed && abs(event.x - downX) > touchSlop && abs(event.x - downX) > abs(event.y - downY)) {
                    claimed = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                scrubTo(ChartMath.xToTime(event.x, win, plot.left, plot.right))
            }
            MotionEvent.ACTION_UP -> {
                if (!claimed && !multiTouch) performClick()
                lastFocusX = Float.NaN
            }
            MotionEvent.ACTION_CANCEL -> {
                scrubTo(null)
                lastFocusX = Float.NaN
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    // ---- Accessibility ----

    private val a11y = object : ExploreByTouchHelper(this) {
        private val bounds = Rect()

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (!hasData || x < plot.left || x > plot.right || y < plot.top || y > plot.bottom) return INVALID_ID
            return ChartMath.sliceAt(x, plot.left, plot.right, A11Y_SLICES)
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            if (!hasData) return
            for (i in 0 until A11Y_SLICES) virtualViewIds += i
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val t = ChartMath.sliceCentre(win, virtualViewId, A11Y_SLICES)
            node.contentDescription = describeAt(t, 0L)
            val w = plot.width() / A11Y_SLICES
            bounds.set(
                (plot.left + virtualViewId * w).toInt(),
                plot.top.toInt(),
                (plot.left + (virtualViewId + 1) * w).toInt(),
                max(plot.top.toInt() + 1, plot.bottom.toInt()),
            )
            @Suppress("DEPRECATION")
            node.setBoundsInParent(bounds)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
            node.addAction(AccessibilityActionCompat(R.id.sw_action_read_table, context.getString(R.string.sw_chart_read_table)))
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            scrubTo(ChartMath.sliceCentre(win, virtualViewId, A11Y_SLICES))
            invalidateVirtualView(virtualViewId)
            sendEventForVirtualView(virtualViewId, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
            return true
        }
    }

    init {
        ViewCompat.setAccessibilityDelegate(this, a11y)
        updateDescription()
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == R.id.sw_action_read_table) {
            readAsTable()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    /** Shows (or hands to [onReadAsTable]) the table of every layer across the visible window. */
    fun readAsTable() {
        val title = context.getString(R.string.sw_chart_table_title, format.time(win.start), format.time(win.end))
        val rows = tableRows()
        val handler = onReadAsTable
        if (handler != null) {
            handler(title, rows)
        } else {
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setItems(rows.toTypedArray(), null)
                .setPositiveButton(R.string.sw_close, null)
                .show()
        }
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = a11y.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        a11y.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    // ---- Saved state ----

    override fun onSaveInstanceState(): Parcelable {
        val s = SavedState(super.onSaveInstanceState())
        s.rangeStart = full.start
        s.rangeEnd = full.end
        s.winStart = win.start
        s.winEnd = win.end
        s.hiddenIds = hidden.toTypedArray()
        return s
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        hidden.clear()
        hidden.addAll(state.hiddenIds)
        val savedRange = Window(state.rangeStart, state.rangeEnd)
        val savedWin = Window(state.winStart, state.winEnd)
        if (savedRange == full && full.span > 0) {
            win = ChartMath.clampWindow(savedWin.start, savedWin.span, full)
        } else {
            pendingRange = savedRange
            pendingWindow = savedWin
        }
        boundChips?.let { g -> layers.forEach { g.setOn(it.id, it.id !in hidden) } }
        dataChanged()
    }

    /** Zoom window and hidden layers across configuration changes. */
    internal class SavedState : BaseSavedState {
        var rangeStart = 0L
        var rangeEnd = 0L
        var winStart = 0L
        var winEnd = 0L
        var hiddenIds: Array<String> = emptyArray()

        constructor(superState: Parcelable?) : super(superState)

        private constructor(p: Parcel) : super(p) {
            rangeStart = p.readLong()
            rangeEnd = p.readLong()
            winStart = p.readLong()
            winEnd = p.readLong()
            hiddenIds = p.createStringArray() ?: emptyArray()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeLong(rangeStart)
            out.writeLong(rangeEnd)
            out.writeLong(winStart)
            out.writeLong(winEnd)
            out.writeStringArray(hiddenIds)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(p: Parcel): SavedState = SavedState(p)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }

    private companion object {
        const val DEFAULT_HEIGHT_DP: Float = 220f
        const val STAGE_FRACTION: Float = 0.5f
        const val BAND_ALPHA: Int = 0x55
        const val BAR_ALPHA: Int = 0xCC
        const val BACKDROP_BAR_ALPHA: Int = 0x80
        const val DOT_ALPHA: Int = 0x80
        const val DOT_RADIUS_DP: Float = 3f
        const val DAY_SEC: Long = 86_400L
        const val SEC_PER_MIN: Double = 60.0
        const val A11Y_SLICES: Int = 12
        const val TABLE_ROWS: Int = 48
    }
}
