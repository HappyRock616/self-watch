/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.selfwatch.R
import app.selfwatch.food.FoodViews
import app.selfwatch.shell.MaxWidthLinearLayout
import app.selfwatch.ui.SwActivity
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.SwCard
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import app.selfwatch.ui.R as UiR

/**
 * Base for the Me screens (Privacy center, Network activity, Settings, About, Food data, Data,
 * Import): a top bar with Back and a scrolling column no wider than 640 dp, padded for the system
 * bars, cut-outs and the keyboard (edge-to-edge through [SwActivity]).
 */
abstract class MeScreenActivity : SwActivity() {

    /** The top bar. */
    protected lateinit var toolbar: MaterialToolbar
        private set

    /** The scrolling content column; screens add their cards here. */
    protected lateinit var column: LinearLayout
        private set

    /**
     * Builds the scaffold with [title] and an optional fixed [bottom] bar below the scrolling column.
     * Call from `onCreate` after `super.onCreate`.
     */
    protected fun buildScreen(@StringRes title: Int, bottom: View? = null) {
        val root = FoodViews.column(this)
        toolbar = MaterialToolbar(this).apply {
            this.title = getString(title)
            setNavigationIcon(R.drawable.me_ic_back)
            navigationContentDescription = getString(R.string.me_back)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        root.addView(toolbar, FoodViews.matchWrap())
        val pad = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        column = MaxWidthLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(pad, 0, pad, pad * 2)
        }
        val scroll = NestedScrollView(this).apply { isFillViewport = false }
        scroll.addView(
            column,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL),
        )
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (bottom != null) root.addView(bottom, FoodViews.matchWrap())
        setRootContent(root)
    }

    /** Collects [flow] while the activity is at least STARTED. */
    protected fun <T> collect(flow: Flow<T>, block: (T) -> Unit) {
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collect { block(it) } } }
    }

    /** A short toast. */
    protected fun toast(text: CharSequence) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}

/** View builders shared by the Me screens (code-built, 48 dp targets, grow with the font size). */
internal object MeViews {

    /** A card with [title], with the column's top margin, added to [parent]. */
    fun card(parent: LinearLayout, title: CharSequence?): SwCard {
        val card = SwCard(parent.context).apply { this.title = title }
        parent.addView(card, FoodViews.matchWrap().apply { topMargin = parent.resources.getDimensionPixelSize(UiR.dimen.sw_space_2) })
        return card
    }

    /** Muted small text. */
    fun caption(ctx: Context, text: CharSequence?): AppCompatTextView =
        FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, text)

    /** Body text. */
    fun body(ctx: Context, text: CharSequence?): AppCompatTextView =
        FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, text)

    /** A heading (announced as one by TalkBack). */
    fun heading(ctx: Context, text: CharSequence?): AppCompatTextView =
        FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, text).also { ViewCompat.setAccessibilityHeading(it, true) }

    /** Adds [view] to [parent] at full width with a small top gap. */
    fun add(parent: ViewGroup, view: View, gap: Boolean = true): View {
        val lp = FoodViews.matchWrap()
        if (gap) lp.topMargin = parent.resources.getDimensionPixelSize(UiR.dimen.sw_space_1)
        parent.addView(view, lp)
        return view
    }

    /** A tappable row ([ListRow]) added to [parent]. */
    fun row(parent: ViewGroup, title: CharSequence, subtitle: CharSequence? = null, onClick: (() -> Unit)? = null): ListRow {
        val r = ListRow(parent.context).apply {
            this.title = title
            this.subtitle = subtitle
            this.onClick = onClick
        }
        parent.addView(r, FoodViews.matchWrap())
        return r
    }

    /** Shows a single-choice dialog of [labels]; [onPick] gets the chosen index. */
    fun choose(ctx: Context, title: CharSequence, labels: List<CharSequence>, selected: Int, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { d, which ->
                d.dismiss()
                onPick(which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A yes/no confirmation; [onYes] runs only on the positive button. */
    fun confirm(ctx: Context, title: CharSequence?, message: CharSequence, yes: CharSequence, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/**
 * A switch with its label (the whole row toggles, at least 48 dp tall) and an optional muted line
 * below. Setting [checked] from code never calls [onChange].
 */
@SuppressLint("ViewConstructor") // built in code only, never inflated
class SwitchRow(ctx: Context, title: CharSequence, subtitle: CharSequence? = null) : LinearLayout(ctx) {

    private val switch = MaterialSwitch(ctx).apply {
        text = title
        minHeight = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
        minimumHeight = minHeight
        TextViewCompatHelper.body(this)
    }
    private val sub = MeViews.caption(ctx, subtitle)
    private var binding = false

    /** Called when the user flips the switch. */
    var onChange: ((Boolean) -> Unit)? = null

    /** The switch state. */
    var checked: Boolean
        get() = switch.isChecked
        set(v) {
            if (switch.isChecked == v) return
            binding = true
            switch.isChecked = v
            binding = false
        }

    /** The muted line; null hides it. */
    var subtitle: CharSequence?
        get() = sub.text
        set(v) {
            sub.text = v
            sub.visibility = if (v.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

    init {
        orientation = VERTICAL
        addView(switch, FoodViews.matchWrap())
        addView(sub, FoodViews.matchWrap())
        this.subtitle = subtitle
        switch.setOnCheckedChangeListener { _, isChecked -> if (!binding) onChange?.invoke(isChecked) }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        switch.isEnabled = enabled
    }
}

/** Text appearance helper for views built in code. */
internal object TextViewCompatHelper {
    /** Gives [v] the body text appearance. */
    fun body(v: android.widget.TextView) {
        androidx.core.widget.TextViewCompat.setTextAppearance(v, UiR.style.TextAppearance_SelfWatch_Body)
    }
}
