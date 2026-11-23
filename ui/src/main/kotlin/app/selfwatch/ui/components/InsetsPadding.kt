/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.selfwatch.ui.R
import kotlin.math.max

/**
 * Adds system bar, display cut-out and (optionally) keyboard insets to a view's own padding. The padding
 * the view had when [apply] was first called is kept as the base, so repeated insets never accumulate.
 * Start and end insets are always applied (cut-outs and 3-button navigation in landscape).
 */
object InsetsPadding {
    private val BASE_KEY: Int = R.id.sw_tag_insets_base

    private data class Base(val start: Int, val top: Int, val end: Int, val bottom: Int)

    /**
     * Pads [view] for insets. [top] and [bottom] choose which edges get the bar insets; [ime] makes the
     * bottom edge clear the keyboard too.
     */
    fun apply(view: View, top: Boolean = true, bottom: Boolean = true, ime: Boolean = false) {
        val base = (view.getTag(BASE_KEY) as? Base)
            ?: Base(view.paddingStart, view.paddingTop, view.paddingEnd, view.paddingBottom)
                .also { view.setTag(BASE_KEY, it) }
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val keyboard = if (ime) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            val rtl = v.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val startInset = if (rtl) bars.right else bars.left
            val endInset = if (rtl) bars.left else bars.right
            v.setPaddingRelative(
                base.start + startInset,
                base.top + if (top) bars.top else 0,
                base.end + endInset,
                base.bottom + if (bottom) max(bars.bottom, keyboard) else keyboard,
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }
}
