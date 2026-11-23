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
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.content.withStyledAttributes
import androidx.core.widget.ImageViewCompat
import app.selfwatch.ui.R
import com.google.android.material.button.MaterialButton

/** Centred icon, one sentence and an optional button ("No food logged yet." · "Add food"). */
class EmptyState @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconView = ImageView(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val messageView = context.swText(R.style.TextAppearance_SelfWatch_BodyLarge).apply {
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val button = MaterialButton(context).apply { isAllCaps = false }

    /** The sentence. */
    var message: CharSequence? = null
        set(v) {
            field = v
            messageView.text = v
        }

    /** Button text; null hides the button. */
    var buttonText: CharSequence? = null
        set(v) {
            field = v
            button.text = v
            button.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

    /** Button handler. */
    var onButton: (() -> Unit)? = null

    /** Sets the icon (a vector drawable, tinted with the muted colour). */
    fun setIcon(@DrawableRes resId: Int) {
        iconView.setImageResource(resId)
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = resources.getDimensionPixelSize(R.dimen.sw_space_3)
        if (paddingTop == 0) setPaddingRelative(pad, pad, pad, pad)
        val icon = resources.getDimensionPixelSize(R.dimen.sw_empty_icon)
        ImageViewCompat.setImageTintList(
            iconView,
            ColorStateList.valueOf(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)),
        )
        addView(iconView, LayoutParams(icon, icon))
        val gap = resources.getDimensionPixelSize(R.dimen.sw_space_2)
        addView(
            messageView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap },
        )
        addView(
            button,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap },
        )
        button.setOnClickListener { onButton?.invoke() }

        context.withStyledAttributes(attrs, R.styleable.EmptyState, defStyleAttr, 0) {
            setIcon(getResourceId(R.styleable.EmptyState_swIcon, R.drawable.sw_ic_empty))
            message = getText(R.styleable.EmptyState_swMessage)
            buttonText = getText(R.styleable.EmptyState_swButtonText)
        }
    }
}
