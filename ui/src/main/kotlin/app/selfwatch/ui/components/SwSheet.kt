/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import app.selfwatch.ui.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

/**
 * Bottom sheet base (Add sheet, food detail, quick add): a navy sheet with 16 dp top corners, a title row
 * with a close button, and scrolling content from [onCreateSheetContent]. Opens fully expanded, clears
 * the navigation bar and the keyboard.
 */
abstract class SwSheet : BottomSheetDialogFragment() {

    /** Sheet title ("Add to lunch"); null hides the title row's text. */
    protected abstract val sheetTitle: CharSequence?

    /** Builds the sheet body. */
    protected abstract fun onCreateSheetContent(inflater: LayoutInflater, container: ViewGroup?): View

    private var titleView: android.widget.TextView? = null

    /** Changes the title shown (for sheets whose title is known only after loading, like a food's name). */
    protected fun updateSheetTitle(text: CharSequence?) {
        titleView?.text = text
    }

    override fun getTheme(): Int = R.style.ThemeOverlay_SelfWatch_BottomSheetDialog

    final override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val pad = ctx.resources.getDimensionPixelSize(R.dimen.sw_space_2)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(pad, pad / 2, pad / 2, 0)
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = ctx.swText(R.style.TextAppearance_SelfWatch_Title).apply {
            text = sheetTitle
            ViewCompat.setAccessibilityHeading(this, true)
        }
        titleView = title
        val close = MaterialButton(ctx, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(R.drawable.sw_ic_close)
            contentDescription = ctx.getString(R.string.sw_close)
            setOnClickListener { dismiss() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = NestedScrollView(ctx).apply {
            clipToPadding = false
            setPaddingRelative(0, 0, pad / 2, pad)
        }
        scroll.addView(onCreateSheetContent(inflater, scroll))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        InsetsPadding.apply(scroll, top = false, bottom = true, ime = true)
        return root
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }
}
