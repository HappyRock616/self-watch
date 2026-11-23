/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.content.Context
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors

/** Converts dp to pixels. */
internal fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

/** Converts sp to pixels (follows the font size setting). */
internal fun Context.sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

/** Resolves a colour attribute from the theme. */
@ColorInt
internal fun View.themeColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr)

/** Resolves a colour attribute from a context's theme. */
@ColorInt
internal fun Context.themeColor(@AttrRes attr: Int, @ColorInt fallback: Int): Int =
    MaterialColors.getColor(this, attr, fallback)

/** A [AppCompatTextView] with a Self Watch text appearance. */
internal fun Context.swText(@StyleRes appearance: Int): AppCompatTextView =
    AppCompatTextView(this).also { TextViewCompat.setTextAppearance(it, appearance) }

/**
 * Single-line text that shrinks (down to 40%) instead of clipping when it doesn't fit its width. Used for
 * big numbers that must stay readable at 200% font in narrow columns.
 */
internal class FitTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {
    private var baseSizePx: Float = textSize
    private var fitting: Boolean = false
    private val probe: TextPaint = TextPaint()

    init {
        maxLines = 1
    }

    /** Sets the full-size text size; the view may draw smaller to fit. */
    fun setBaseTextSize(px: Float) {
        baseSizePx = px
        requestLayout()
    }

    override fun setTextAppearance(resId: Int) {
        super.setTextAppearance(resId)
        if (!fitting) baseSizePx = textSize
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val available = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
        if (mode != MeasureSpec.UNSPECIFIED && available > 0) {
            fitting = true
            probe.set(paint)
            probe.textSize = baseSizePx
            val scale = fitTextScale(probe.measureText(text, 0, text.length), available.toFloat())
            val target = baseSizePx * scale
            if (target != textSize) setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
            fitting = false
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
