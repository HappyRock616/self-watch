/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorInt
import androidx.core.content.withStyledAttributes
import app.selfwatch.ui.R
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.format.Fmt
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Goal ring: a track circle with a progress arc starting at 12 o'clock and running clockwise, plus
 * optional centre text (big number) and sub text (small label) that shrink to fit the ring. Progress
 * above 1 draws a full ring. TalkBack reads "centre text, NN% of goal" unless a content description is set.
 */
class RingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val defaultTrack: Int = ChartPalette.of(context).track
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val centerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        fontFeatureSettings = "tnum"
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()
    private var animator: ValueAnimator? = null
    private var drawnProgress: Float = 0f
    private var userDescription: CharSequence? = null

    /** Progress toward the goal, 0..1 (values above 1 draw a full ring). */
    var progress: Float = 0f
        set(v) {
            field = if (v.isNaN()) 0f else max(0f, v)
            animator?.cancel()
            drawnProgress = field
            updateDescription()
            invalidate()
        }

    /** Arc colour; defaults to the theme primary (ChartPalette series 1). */
    @ColorInt
    var color: Int = ChartPalette.of(context).primary
        set(v) {
            field = v
            arcPaint.color = v
            invalidate()
        }

    /** Track colour; defaults to the theme's neutral track gray ([ChartPalette.track]). */
    @ColorInt
    var trackColor: Int? = null
        set(v) {
            field = v
            invalidate()
        }

    /** Stroke width in pixels. */
    var ringWidth: Float = context.dp(10f)
        set(v) {
            field = v
            requestLayout()
            invalidate()
        }

    /** Big text in the middle ("7,842"); null for none. */
    var centerText: CharSequence? = null
        set(v) {
            field = v
            updateDescription()
            invalidate()
        }

    /** Small text under [centerText] ("of 10,000"); null for none. */
    var subText: CharSequence? = null
        set(v) {
            field = v
            updateDescription()
            invalidate()
        }

    init {
        userDescription = contentDescription
        context.withStyledAttributes(attrs, R.styleable.RingView, defStyleAttr, 0) {
            progress = getFloat(R.styleable.RingView_swProgress, 0f)
            color = getColor(R.styleable.RingView_swColor, color)
            if (hasValue(R.styleable.RingView_swTrackColor)) trackColor = getColor(R.styleable.RingView_swTrackColor, 0)
            ringWidth = getDimension(R.styleable.RingView_swRingWidth, ringWidth)
            centerText = getText(R.styleable.RingView_swCenterText)
            subText = getText(R.styleable.RingView_swSubText)
        }
        centerPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurface)
        subPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        updateDescription()
    }

    /** Sets [progress] with a short animation from the current value (skipped when animations are off). */
    fun animateProgress(target: Float) {
        val to = if (target.isNaN()) 0f else max(0f, target)
        val from = drawnProgress
        progress = to
        drawnProgress = from
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = 600L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawnProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Overrides the automatic TalkBack text. */
    override fun setContentDescription(contentDescription: CharSequence?) {
        super.setContentDescription(contentDescription ?: autoDescription())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = resources.getDimensionPixelSize(R.dimen.sw_ring_size)
        val w = resolveSize(size + paddingLeft + paddingRight, widthMeasureSpec)
        val h = resolveSize(size + paddingTop + paddingBottom, heightMeasureSpec)
        val side = minOf(w - paddingLeft - paddingRight, h - paddingTop - paddingBottom)
        setMeasuredDimension(
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) w else side + paddingLeft + paddingRight,
            if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) h else side + paddingTop + paddingBottom,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val box = RingMath.arcBox(
            width.toFloat(),
            height.toFloat(),
            ringWidth,
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            paddingRight.toFloat(),
            paddingBottom.toFloat(),
        )
        oval.set(box.left, box.top, box.right, box.bottom)
        trackPaint.strokeWidth = ringWidth
        arcPaint.strokeWidth = ringWidth
        arcPaint.color = color
        trackPaint.color = trackColor ?: defaultTrack
        canvas.drawOval(oval, trackPaint)
        val sweep = RingMath.sweepDegrees(drawnProgress)
        if (sweep > 0f) canvas.drawArc(oval, -90f, sweep, false, arcPaint)

        val available = RingMath.innerTextWidth(box, ringWidth)
        val cx = oval.centerX()
        val cy = oval.centerY()
        val center = centerText?.toString().orEmpty()
        val sub = subText?.toString().orEmpty()
        val bigBase = minOf(context.sp(20f), box.width * 0.3f)
        centerPaint.textSize = bigBase
        centerPaint.textSize = bigBase * fitTextScale(centerPaint.measureText(center), available)
        subPaint.textSize = minOf(context.sp(12f), box.width * 0.18f)
        subPaint.textSize *= fitTextScale(subPaint.measureText(sub), available)

        val bigH = if (center.isEmpty()) 0f else centerPaint.descent() - centerPaint.ascent()
        val subH = if (sub.isEmpty()) 0f else subPaint.descent() - subPaint.ascent()
        var y = cy - (bigH + subH) / 2f
        if (center.isNotEmpty()) {
            canvas.drawText(center, cx, y - centerPaint.ascent(), centerPaint)
            y += bigH
        }
        if (sub.isNotEmpty()) canvas.drawText(sub, cx, y - subPaint.ascent(), subPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun autoDescription(): CharSequence {
        val pct = Fmt.percent((progress * 100f).roundToInt() / 100.0)
        val label = listOfNotNull(centerText, subText).joinToString(" ")
        return context.getString(R.string.sw_ring_description, label, pct)
    }

    private fun updateDescription() {
        if (userDescription == null) super.setContentDescription(autoDescription())
    }
}
