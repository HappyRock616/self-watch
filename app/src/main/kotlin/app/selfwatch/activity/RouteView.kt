/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import app.selfwatch.R
import app.selfwatch.ui.charts.ChartPalette
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import app.selfwatch.ui.R as UiR

/** Fits a route into a box: an equirectangular projection scaled to keep its true shape. */
object RouteProjection {
    /**
     * Pixel positions (x0, y0, x1, y1, …) of [route] inside a [width] × [height] box with [pad] on
     * every side, north up, centred, same scale on both axes.
     */
    fun project(route: List<GeoPoint>, width: Float, height: Float, pad: Float): FloatArray {
        if (route.isEmpty()) return FloatArray(0)
        val meanLat = route.sumOf { it.latE7.toDouble() } / route.size / 1e7
        val k = cos(Math.toRadians(meanLat)).coerceAtLeast(0.01)
        val xs = DoubleArray(route.size) { route[it].lonE7 * k }
        val ys = DoubleArray(route.size) { route[it].latE7.toDouble() }
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        val spanX = (maxX - minX).coerceAtLeast(1.0)
        val spanY = (maxY - minY).coerceAtLeast(1.0)
        val w = (width - 2 * pad).coerceAtLeast(1f)
        val h = (height - 2 * pad).coerceAtLeast(1f)
        val scale = min(w / spanX, h / spanY)
        val offX = pad + (w - spanX * scale) / 2
        val offY = pad + (h - spanY * scale) / 2
        val out = FloatArray(route.size * 2)
        for (i in route.indices) {
            out[2 * i] = (offX + (xs[i] - minX) * scale).toFloat()
            out[2 * i + 1] = (offY + (maxY - ys[i]) * scale).toFloat()
        }
        return out
    }
}

/**
 * The GPS route of a workout drawn on a plain canvas (no map tiles, so no network): the track in the
 * theme primary, a start dot in the secondary, a finish dot in the text color and a numbered marker at each kilometre (or mile).
 * TalkBack reads the [contentDescription] the screen sets.
 */
class RouteView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** The route, in order. */
    var route: List<GeoPoint> = emptyList()
        set(value) {
            field = value
            dirty = true
            invalidate()
        }

    /** Indices into [route] of the distance markers (1, 2, 3 … in order). */
    var markers: List<Int> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private fun dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val palette = ChartPalette.of(context)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = palette.primary
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = ContextCompat.getColor(context, UiR.color.sw_on_surface)
    }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, UiR.color.sw_surface_high)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        color = ContextCompat.getColor(context, UiR.color.sw_on_surface)
        isFakeBoldText = true
    }
    private val path = Path()
    private var pts = FloatArray(0)
    private var dirty = true

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resources.getDimensionPixelSize(R.dimen.route_height)
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dirty = true
    }

    private fun rebuild() {
        val pad = dp(16f)
        pts = RouteProjection.project(route, width.toFloat(), height.toFloat(), pad)
        path.reset()
        for (i in 0 until pts.size / 2) {
            if (i == 0) path.moveTo(pts[0], pts[1]) else path.lineTo(pts[2 * i], pts[2 * i + 1])
        }
        dirty = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (route.size < 2) return
        if (dirty) rebuild()
        canvas.drawPath(path, line)
        val r = dp(9f)
        markers.forEachIndexed { n, idx ->
            if (idx < 0 || 2 * idx + 1 >= pts.size) return@forEachIndexed
            val x = pts[2 * idx]
            val y = pts[2 * idx + 1]
            canvas.drawCircle(x, y, r, markerFill)
            canvas.drawCircle(x, y, r, ring)
            canvas.drawText((n + 1).toString(), x, y - (label.ascent() + label.descent()) / 2, label)
        }
        val endR = max(dp(6f), r * 0.7f)
        dot.color = palette.text
        canvas.drawCircle(pts[pts.size - 2], pts[pts.size - 1], endR, dot)
        canvas.drawCircle(pts[pts.size - 2], pts[pts.size - 1], endR, ring)
        dot.color = palette.secondary
        canvas.drawCircle(pts[0], pts[1], endR, dot)
        canvas.drawCircle(pts[0], pts[1], endR, ring)
    }
}
