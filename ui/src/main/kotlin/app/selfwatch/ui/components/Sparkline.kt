/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.withStyledAttributes
import app.selfwatch.ui.R
import app.selfwatch.ui.charts.ChartPalette

/**
 * Tiny trend line for list rows and cards (no axes). Decorative for TalkBack unless a content
 * description is set; the row around it should say the trend in words.
 */
class Sparkline @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var pathDirty = true

    /** Values, oldest first. */
    var values: IntArray = IntArray(0)
        set(v) {
            field = v
            pathDirty = true
            invalidate()
        }

    /** Line colour; defaults to the theme's secondary (blue). */
    @ColorInt
    var color: Int = ChartPalette.of(context).secondary
        set(v) {
            field = v
            invalidate()
        }

    /** Line width in pixels. */
    var lineWidth: Float = context.dp(2f)
        set(v) {
            field = v
            pathDirty = true
            invalidate()
        }

    init {
        context.withStyledAttributes(attrs, R.styleable.Sparkline, defStyleAttr, 0) {
            color = getColor(R.styleable.Sparkline_swColor, color)
            lineWidth = getDimension(R.styleable.Sparkline_swLineWidth, lineWidth)
        }
        if (contentDescription == null) importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun setContentDescription(contentDescription: CharSequence?) {
        super.setContentDescription(contentDescription)
        importantForAccessibility =
            if (contentDescription == null) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(resources.getDimensionPixelSize(R.dimen.sw_sparkline_width), widthMeasureSpec),
            resolveSize(resources.getDimensionPixelSize(R.dimen.sw_sparkline_height), heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pathDirty = true
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val pts = SparkMath.points(values, width.toFloat(), height.toFloat(), lineWidth)
        if (pathDirty) {
            path.reset()
            for (i in pts.indices step 2) {
                val x = if (rtl) width - pts[i] else pts[i]
                if (i == 0) path.moveTo(x, pts[i + 1]) else path.lineTo(x, pts[i + 1])
            }
            pathDirty = false
        }
        linePaint.color = color
        linePaint.strokeWidth = lineWidth
        canvas.drawPath(path, linePaint)
        // Dot on the latest value.
        dotPaint.color = color
        val lx = pts[pts.size - 2]
        canvas.drawCircle(if (rtl) width - lx else lx, pts[pts.size - 1], lineWidth * 1.25f, dotPaint)
    }
}
