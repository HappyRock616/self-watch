/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import app.selfwatch.ui.R

/**
 * Two-line row: title, optional subtitle, optional trailing value and a chevron when it is tappable.
 * At least 56 dp tall and grows with large fonts. Extra views (a [SourceTag], a [Sparkline]) can be put
 * after the title with [addTitleAccessory] or before the trailing value with [addTrailingAccessory].
 */
class ListRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val titleRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val titleView = context.swText(R.style.TextAppearance_SelfWatch_BodyLarge)
    private val subtitleView = context.swText(R.style.TextAppearance_SelfWatch_Caption)
    private val textColumn = LinearLayout(context).apply { orientation = VERTICAL }
    private val trailingView = context.swText(R.style.TextAppearance_SelfWatch_Body_Number).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    private val chevron = ImageView(context).apply {
        setImageResource(R.drawable.sw_ic_chevron_end)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** First line. */
    var title: CharSequence? = null
        set(v) {
            field = v
            titleView.text = v
        }

    /** Second line in muted text; null hides it. */
    var subtitle: CharSequence? = null
        set(v) {
            field = v
            subtitleView.text = v
            subtitleView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

    /** Value at the end ("210 kcal"); null hides it. */
    var trailing: CharSequence? = null
        set(v) {
            field = v
            trailingView.text = v
            trailingView.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

    /** Whether the chevron shows; by default it follows whether [onClick] is set. */
    var showChevron: Boolean? = null
        set(v) {
            field = v
            updateChevron()
        }

    /** Tap handler; null makes the row non-interactive. */
    var onClick: (() -> Unit)? = null
        set(v) {
            field = v
            if (v == null) {
                setOnClickListener(null)
                isClickable = false
                isFocusable = false
            } else {
                setOnClickListener { onClick?.invoke() }
            }
            updateChevron()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(R.dimen.sw_list_row_min_height)
        val v = resources.getDimensionPixelSize(R.dimen.sw_space_1)
        if (paddingTop == 0 && paddingBottom == 0) setPaddingRelative(paddingStart, v, paddingEnd, v)
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        if (background == null && outValue.resourceId != 0) {
            background = ContextCompat.getDrawable(context, outValue.resourceId)
        }

        titleRow.addView(titleView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textColumn.addView(titleRow)
        textColumn.addView(subtitleView)
        addView(textColumn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            trailingView,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.sw_space_1)
            },
        )
        addView(chevron, LayoutParams(context.dp(20f).toInt(), context.dp(20f).toInt()))

        context.withStyledAttributes(attrs, R.styleable.ListRow, defStyleAttr, 0) {
            title = getText(R.styleable.ListRow_swTitle)
            subtitle = getText(R.styleable.ListRow_swSubtitle)
            trailing = getText(R.styleable.ListRow_swTrailing)
            if (hasValue(R.styleable.ListRow_swShowChevron)) showChevron = getBoolean(R.styleable.ListRow_swShowChevron, true)
        }
        updateChevron()
    }

    /** Adds a small view right after the title (for example a [SourceTag]). */
    fun addTitleAccessory(view: View) {
        titleRow.addView(
            view,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.sw_space_1)
            },
        )
    }

    /** Adds a view before the trailing value (for example a [Sparkline] or a + button). */
    fun addTrailingAccessory(view: View) {
        addView(
            view,
            indexOfChild(trailingView),
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.sw_space_1)
            },
        )
    }

    private fun updateChevron() {
        chevron.visibility = if (showChevron ?: (onClick != null)) View.VISIBLE else View.GONE
    }
}
