/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.withStyledAttributes
import androidx.core.widget.TextViewCompat
import app.selfwatch.ui.R
import app.selfwatch.ui.charts.ChartPalette

/**
 * Small pill naming where a food came from: "USDA", "OFF", "2018" (chain menu year), "Mine", "Meal".
 * The [style] picks the tint; the text is whatever the caller sets.
 */
class SourceTag @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** Tint families, colored from the theme ([ChartPalette]): neutral, primary tint or secondary tint. */
    enum class Style {
        /** A public database (USDA, Open Food Facts): neutral gray. */
        DATABASE,

        /** The user's own food: primary tint. */
        MINE,

        /** Chain restaurant data, usually with its year: secondary tint. */
        CHAIN,

        /** A saved meal or recipe: primary tint. */
        MEAL,
    }

    private val pill = GradientDrawable()

    /** Tint family. */
    var style: Style = Style.DATABASE
        set(v) {
            field = v
            applyStyle()
        }

    init {
        TextViewCompat.setTextAppearance(this, R.style.TextAppearance_SelfWatch_Caption)
        maxLines = 1
        val h = context.dp(6f).toInt()
        val v = context.dp(1f).toInt()
        setPaddingRelative(h, v, h, v)
        background = pill
        var idx = 0
        context.withStyledAttributes(attrs, R.styleable.SourceTag, defStyleAttr, 0) {
            idx = getInt(R.styleable.SourceTag_swTagStyle, 0)
        }
        style = Style.entries.getOrElse(idx) { Style.DATABASE }
        applyStyle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pill.cornerRadius = h / 2f
    }

    private fun applyStyle() {
        val p = ChartPalette.of(context)
        when (style) {
            Style.DATABASE -> {
                pill.setColor(p.neutralTagFill)
                setTextColor(p.neutralTagText)
            }
            Style.MINE, Style.MEAL, Style.CHAIN -> {
                val primary = style != Style.CHAIN
                pill.setColor(p.tagFill(primary))
                setTextColor(p.tagText(primary))
            }
        }
    }
}
