/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.widget.TextViewCompat
import app.selfwatch.ui.R

/**
 * A big number with its unit, a label below and an optional change line with an arrow ("▼ 0.6 this
 * week"). The arrow is green when the change is good and red when it is bad; [deltaGoodWhenDown] flips
 * which direction is good (weight, resting heart rate).
 */
class StatTile @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val valueView = FitTextView(context).apply {
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SelfWatch_Headline_Number)
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val labelView = context.swText(R.style.TextAppearance_SelfWatch_Caption).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val deltaView = context.swText(R.style.TextAppearance_SelfWatch_Caption_Number).apply {
        gravity = Gravity.CENTER_VERTICAL
        compoundDrawablePadding = context.dp(2f).toInt()
    }

    /** The big number, already formatted ("82.4"). */
    var value: CharSequence = ""
        set(v) {
            field = v
            render()
        }

    /** Unit shown after the number in smaller type ("kg"); null for none. */
    var unit: CharSequence? = null
        set(v) {
            field = v
            render()
        }

    /** Label under the number ("rest HR"). */
    var label: CharSequence? = null
        set(v) {
            field = v
            labelView.text = v
            labelView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
            updateDescription()
        }

    /** Signed change; its sign picks the arrow. Null or 0 hides the arrow. */
    var delta: Float? = null
        set(v) {
            field = v
            renderDelta()
        }

    /** Formatted change text next to the arrow ("0.6 this week"); null hides the change line. */
    var deltaText: CharSequence? = null
        set(v) {
            field = v
            renderDelta()
        }

    /** True when a falling value is good news (weight loss, lower resting heart rate). */
    var deltaGoodWhenDown: Boolean = false
        set(v) {
            field = v
            renderDelta()
        }

    /** Text appearance for the number; Headline (28 sp) by default, Display (40 sp) for hero numbers. */
    fun setValueAppearance(resId: Int) {
        TextViewCompat.setTextAppearance(valueView, resId)
        render()
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val wrap = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(valueView, wrap)
        addView(labelView, LayoutParams(wrap))
        addView(
            deltaView,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        context.withStyledAttributes(attrs, R.styleable.StatTile, defStyleAttr, 0) {
            value = getText(R.styleable.StatTile_swValue) ?: ""
            unit = getText(R.styleable.StatTile_swUnit)
            label = getText(R.styleable.StatTile_swLabel)
            deltaGoodWhenDown = getBoolean(R.styleable.StatTile_swDeltaGoodWhenDown, false)
            if (hasValue(R.styleable.StatTile_swDelta)) delta = getFloat(R.styleable.StatTile_swDelta, 0f)
            deltaText = getText(R.styleable.StatTile_swDeltaText)
        }
        renderDelta()
    }

    private fun render() {
        val u = unit
        valueView.text = if (u.isNullOrEmpty()) {
            value
        } else {
            android.text.SpannableStringBuilder(value).apply {
                append(' ')
                val start = length
                append(u)
                setSpan(
                    android.text.style.RelativeSizeSpan(0.5f),
                    start,
                    length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        updateDescription()
    }

    private fun renderDelta() {
        val text = deltaText
        if (text.isNullOrEmpty()) {
            deltaView.visibility = View.GONE
            updateDescription()
            return
        }
        deltaView.visibility = View.VISIBLE
        deltaView.text = text
        val d = delta ?: 0f
        if (d == 0f) {
            deltaView.setCompoundDrawablesRelative(null, null, null, null)
        } else {
            val down = d < 0f
            val good = down == deltaGoodWhenDown
            val icon = ContextCompat.getDrawable(context, if (down) R.drawable.sw_ic_arrow_down else R.drawable.sw_ic_arrow_up)
            val size = (deltaView.textSize).toInt()
            icon?.setBounds(0, 0, size, size)
            // Good: the theme's accent; bad: red (alarm).
            val color = if (good) themeColor(R.attr.swColorAccentText) else ContextCompat.getColor(context, R.color.sw_bad)
            TextViewCompat.setCompoundDrawableTintList(deltaView, ColorStateList.valueOf(color))
            deltaView.setCompoundDrawablesRelative(icon, null, null, null)
        }
        updateDescription()
    }

    private fun updateDescription() {
        val parts = ArrayList<CharSequence>(4)
        parts += if (unit.isNullOrEmpty()) value else context.getString(R.string.sw_value_with_unit, value, unit)
        label?.let { if (it.isNotEmpty()) parts += it }
        val dt = deltaText
        val d = delta ?: 0f
        if (!dt.isNullOrEmpty()) {
            parts += when {
                d < 0f -> context.getString(R.string.sw_delta_down, dt)
                d > 0f -> context.getString(R.string.sw_delta_up, dt)
                else -> dt
            }
        }
        contentDescription = parts.joinToString(", ")
        valueView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        labelView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        deltaView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
