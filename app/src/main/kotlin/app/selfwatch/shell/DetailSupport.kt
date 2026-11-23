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
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MenuRes
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.selfwatch.R
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.model.food.UnitSystem
import app.selfwatch.ui.components.InsetsPadding
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Units
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import app.selfwatch.ui.R as UiR

/** Opens detail screens: fragments on the back stack in MainActivity's full-screen `detail_container`. */
object Nav {
    /** Shows [fragment] above everything; Back (or the up arrow) returns. */
    fun open(from: Fragment, fragment: Fragment) {
        val fm = from.requireActivity().supportFragmentManager
        fm.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.detail_container, fragment)
            .addToBackStack(fragment.javaClass.simpleName)
            .commit()
    }
}

/** Display units from the stored profile choice ("follow the phone" uses the locale). */
object DisplayUnits {
    /** Maps the profile's [system] to Fmt's units. */
    fun of(system: UnitSystem, locale: Locale = Locale.getDefault()): Units = when (system) {
        UnitSystem.METRIC -> Units.METRIC
        UnitSystem.IMPERIAL -> Units.IMPERIAL
        UnitSystem.FOLLOW_PHONE -> Units.forLocale(locale)
    }

    /** The display units now and after every settings change. */
    fun observe(db: SelfWatchDb): Flow<Units> =
        db.changes.observe(setOf(Table.SETTING)).map { of(db.settings.profile().units) }.distinctUntilChanged()
}

/**
 * Base for detail screens (Sleep, Weight, Workout…): a top bar with an up arrow and an optional menu,
 * and a scrolling column ([column]) that subclasses fill in [onContent]. The root covers the tabs, is
 * padded for every system bar and cut-out, and keeps the content no wider than 640 dp.
 */
abstract class DetailFragment(@param:MenuRes private val menuRes: Int = 0) : Fragment() {

    /** The top bar. */
    protected lateinit var toolbar: MaterialToolbar
        private set

    /** The scrolling column for cards. */
    protected lateinit var column: LinearLayout
        private set

    /** The screen title. */
    protected abstract fun title(): CharSequence

    /** Builds the screen's content into [column]. */
    protected abstract fun onContent(column: LinearLayout, savedInstanceState: Bundle?)

    /** Handles a menu item; return false to ignore it. */
    protected open fun onMenuItem(item: MenuItem): Boolean = false

    final override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        InsetsPadding.apply(view, top = true, bottom = true)
        toolbar = view.findViewById(R.id.toolbar)
        column = view.findViewById(R.id.detail_column)
        toolbar.title = title()
        toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        if (menuRes != 0) {
            toolbar.inflateMenu(menuRes)
            toolbar.setOnMenuItemClickListener { onMenuItem(it) }
        }
        onContent(column, savedInstanceState)
    }

    /** Collects [flow] while the view is at least STARTED. */
    protected fun <T> collectWhileStarted(flow: Flow<T>, block: (T) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect { block(it) }
            }
        }
    }

    /** Closes this screen. */
    protected fun close() {
        if (isAdded) parentFragmentManager.popBackStack()
    }
}

/** Small builders for views made in code, so every screen looks and scales the same. */
object Views {
    /** Layout params: full width, wrap height, with [topDp] margin. */
    fun matchWrap(ctx: Context, topDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, topDp)
        }

    /** Layout params for one of several equal views side by side. */
    fun weighted(ctx: Context, startDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(ctx, startDp) }

    /** Density pixels to pixels. */
    fun dp(ctx: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics).toInt()

    /** A text view in [style]. */
    fun text(ctx: Context, @StyleRes style: Int, value: CharSequence? = null): TextView = TextView(ctx).apply {
        setTextAppearance(style)
        text = value
    }

    /** A muted caption. */
    fun caption(ctx: Context, value: CharSequence? = null): TextView = text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, value).apply {
        setTextColor(ContextCompat.getColor(ctx, UiR.color.sw_muted))
    }

    /** A card with [title] added to [parent] (16 dp below the previous card unless [first]). */
    fun card(parent: LinearLayout, title: CharSequence?, first: Boolean = false): SwCard {
        val ctx = parent.context
        val card = SwCard(ctx).apply { this.title = title }
        parent.addView(card, matchWrap(ctx, if (first) 0 else 16))
        return card
    }

    /** A horizontal row for tiles. */
    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
    }

    /**
     * A single-choice segmented control (Night · Week · Month). [labels] are shown in order; [onPick]
     * gets the index when the user picks one. Returns the group; check one with [selectSegment].
     */
    fun segments(ctx: Context, labels: List<CharSequence>, onPick: (Int) -> Unit): MaterialButtonToggleGroup {
        val group = MaterialButtonToggleGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        labels.forEachIndexed { i, label ->
            val b = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = label
                tag = i
                minHeight = dp(ctx, 48)
                minWidth = 0
                isAllCaps = false
                setPaddingRelative(dp(ctx, 4), paddingTop, dp(ctx, 4), paddingBottom)
            }
            group.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        group.addOnButtonCheckedListener { g, checkedId, isChecked ->
            if (isChecked) (g.findViewById<View>(checkedId)?.tag as? Int)?.let(onPick)
        }
        return group
    }

    /** Checks segment [index] of a group made by [segments] without firing its listener twice. */
    fun selectSegment(group: MaterialButtonToggleGroup, index: Int) {
        val child = group.getChildAt(index) ?: return
        if (group.checkedButtonId != child.id) group.check(child.id)
    }
}
