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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.withStyledAttributes
import androidx.core.view.ViewCompat
import app.selfwatch.ui.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * Navy card with an optional title row (title at the start, text action at the end). Child views added
 * in XML or with [addView] go into the card body below the title row, stacked vertically.
 */
class SwCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val titleView = context.swText(R.style.TextAppearance_SelfWatch_BodyLarge).apply {
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val actionButton = MaterialButton(
        context,
        null,
        androidx.appcompat.R.attr.borderlessButtonStyle,
    ).apply {
        minHeight = resources.getDimensionPixelSize(R.dimen.sw_touch_target)
        minimumHeight = minHeight
        minWidth = resources.getDimensionPixelSize(R.dimen.sw_touch_target)
        minimumWidth = minWidth
        insetTop = 0
        insetBottom = 0
        val pad = resources.getDimensionPixelSize(R.dimen.sw_space_1)
        setPaddingRelative(pad, 0, pad, 0)
        isAllCaps = false
    }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var built = false

    /** Title at the top of the card; null hides the title row (unless there is an action). */
    var title: CharSequence? = null
        set(value) {
            field = value
            titleView.text = value
            updateHeader()
        }

    /** Text of the end-aligned action ("All", "Add"); null hides it. */
    var actionText: CharSequence? = null
        set(value) {
            field = value
            actionButton.text = value
            updateHeader()
        }

    /** Called when the action is tapped. */
    var onAction: (() -> Unit)? = null

    init {
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            actionButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        actionButton.setOnClickListener { onAction?.invoke() }
        column.addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        super.addView(column, -1, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        built = true

        context.withStyledAttributes(attrs, R.styleable.SwCard, defStyleAttr, 0) {
            title = getText(R.styleable.SwCard_swTitle)
            actionText = getText(R.styleable.SwCard_swActionText)
        }
    }

    /** The body container, for code that wants to manage the card's children directly. */
    val content: ViewGroup get() = body

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (!built) {
            super.addView(child, index, params)
            return
        }
        val lp = if (params is LinearLayout.LayoutParams) {
            params
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, params.height)
        }
        body.addView(child, index, lp)
    }

    private fun updateHeader() {
        val hasTitle = !title.isNullOrEmpty()
        val hasAction = !actionText.isNullOrEmpty()
        titleView.visibility = if (hasTitle) View.VISIBLE else View.INVISIBLE
        actionButton.visibility = if (hasAction) View.VISIBLE else View.GONE
        header.visibility = if (hasTitle || hasAction) View.VISIBLE else View.GONE
        header.minimumHeight = if (hasAction) resources.getDimensionPixelSize(R.dimen.sw_touch_target) else 0
    }
}
