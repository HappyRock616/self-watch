/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.LayoutRes
import androidx.annotation.MenuRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.selfwatch.R
import app.selfwatch.ui.components.InsetsPadding
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import app.selfwatch.ui.R as UiR

/** "Coming soon" feedback for menu items and buttons whose screens are not built yet. */
object ComingSoon {
    /** Shows a short toast naming [what]. */
    fun show(ctx: Context, what: CharSequence?) {
        val text = if (what.isNullOrBlank()) {
            ctx.getString(R.string.coming_soon)
        } else {
            ctx.getString(R.string.coming_soon_named, what)
        }
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Base for the tab screens: a layout with a `toolbar` (title, subtitle, overflow menu from [menuRes])
 * at the top. The root is padded for the status bar, cut-outs and side navigation bars; the bottom bar
 * sits below, so no bottom inset. Menu items show "coming soon" unless [onMenuItem] handles them.
 */
abstract class TabFragment(@LayoutRes layout: Int, @param:MenuRes private val menuRes: Int) : Fragment(layout) {

    /** The top bar. */
    protected lateinit var toolbar: MaterialToolbar
        private set

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        InsetsPadding.apply(view, top = true, bottom = false)
        toolbar = view.findViewById(R.id.toolbar)
        toolbar.inflateMenu(menuRes)
        toolbar.setOnMenuItemClickListener { item ->
            if (!onMenuItem(item)) ComingSoon.show(requireContext(), item.title)
            true
        }
    }

    /** Handles a menu item; return false for the default "coming soon". */
    protected open fun onMenuItem(item: MenuItem): Boolean = false

    /** Collects [flow] while the view is at least STARTED. */
    protected fun <T> collectWhileStarted(flow: Flow<T>, block: (T) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect { block(it) }
            }
        }
    }

    /** Makes [view] say "coming soon" (with its [label]) when tapped. */
    protected fun comingSoonOnTap(view: View, label: CharSequence?) {
        view.setOnClickListener { ComingSoon.show(requireContext(), label) }
    }
}

/**
 * A vertical [LinearLayout] no wider than the content maximum (640 dp), for tablets, foldables and
 * landscape (COMPATIBILITY §6). Centre it with `layout_gravity="center_horizontal"`.
 */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val maxWidthPx: Int = resources.getDimensionPixelSize(UiR.dimen.sw_content_max_width)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (size > maxWidthPx) {
            MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.getMode(widthMeasureSpec))
        } else {
            widthMeasureSpec
        }
        super.onMeasure(spec, heightMeasureSpec)
    }
}
