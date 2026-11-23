/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import app.selfwatch.R
import app.selfwatch.food.source.FoodHit
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.ui.components.SourceTag
import app.selfwatch.ui.format.Fmt
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import app.selfwatch.ui.R as UiR

/** Small view builders for the food screens (code-built layouts, 48 dp targets, font-scale safe). */
internal object FoodViews {

    /** dp to px. */
    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    /** A text view with a Self Watch text appearance. */
    fun text(ctx: Context, @StyleRes appearance: Int, value: CharSequence? = null): AppCompatTextView =
        AppCompatTextView(ctx).apply {
            TextViewCompat.setTextAppearance(this, appearance)
            text = value
        }

    /** A vertical column. */
    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    /** A horizontal row, centred vertically. */
    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }

    /** Match-width, wrap-height params. */
    fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Weighted params for a row. */
    fun weight(w: Float = 1f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

    /** A text button at least 48 dp tall. */
    fun textButton(ctx: Context, label: CharSequence, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            minHeight = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            minimumHeight = minHeight
            setOnClickListener { onClick() }
        }

    /** A filled button. */
    fun filledButton(ctx: Context, label: CharSequence, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx).apply {
            text = label
            isAllCaps = false
            minHeight = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            minimumHeight = minHeight
            setOnClickListener { onClick() }
        }

    /** An outlined button. */
    fun outlinedButton(ctx: Context, label: CharSequence, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            minHeight = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            minimumHeight = minHeight
            setOnClickListener { onClick() }
        }

    /** The round outlined "+" used for one-tap logging, 48 dp. */
    fun plusButton(ctx: Context, description: CharSequence, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialIconButtonOutlinedStyle).apply {
            setIconResource(UiR.drawable.sw_ic_plus)
            contentDescription = description
            val touch = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            minimumWidth = touch
            minimumHeight = touch
            minWidth = touch
            minHeight = touch
            setOnClickListener { onClick() }
        }

    /** An icon button (48 dp). */
    fun iconButton(ctx: Context, icon: Int, description: CharSequence, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(icon)
            contentDescription = description
            val touch = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            minimumWidth = touch
            minimumHeight = touch
            minWidth = touch
            minHeight = touch
            setOnClickListener { onClick() }
        }

    /** A labelled text field. */
    fun field(ctx: Context, @StringRes hint: Int, inputType: Int = InputType.TYPE_CLASS_TEXT): Pair<TextInputLayout, TextInputEditText> {
        val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        layout.hint = ctx.getString(hint)
        val edit = TextInputEditText(layout.context)
        edit.inputType = inputType
        layout.addView(edit, matchWrap())
        return layout to edit
    }

    /** A number field that accepts decimals. */
    fun numberField(ctx: Context, @StringRes hint: Int): Pair<TextInputLayout, TextInputEditText> =
        field(ctx, hint, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)

    /** The source pill for [hit]. */
    fun sourceTag(ctx: Context, hit: FoodHit): SourceTag = SourceTag(ctx).apply {
        val (label, style) = tagOf(hit)
        text = ctx.getString(label)
        this.style = style
    }

    /** Label and tint for [hit]'s source (Mine, USDA, OFF, Chain, Recipe). */
    fun tagOf(hit: FoodHit): Pair<Int, SourceTag.Style> = when (hit.sourceTag) {
        FoodHit.TAG_MY_FOOD -> R.string.tag_mine to SourceTag.Style.MINE
        FoodHit.TAG_RECIPE -> R.string.tag_recipe to SourceTag.Style.MEAL
        FoodHit.TAG_OPEN_FOOD_FACTS -> R.string.tag_off to SourceTag.Style.DATABASE
        FoodHit.TAG_FOODS_DB -> dbTag(hit.sourceCode, hit.kind)
        else -> when (hit.ref.origin) {
            FoodOrigin.MY_FOOD -> R.string.tag_mine to SourceTag.Style.MINE
            FoodOrigin.RECIPE -> R.string.tag_recipe to SourceTag.Style.MEAL
            FoodOrigin.OPEN_FOOD_FACTS -> R.string.tag_off to SourceTag.Style.DATABASE
            FoodOrigin.FOODS_DB -> dbTag(hit.sourceCode, hit.kind)
        }
    }

    /**
     * A foods.db hit's tag: by its data source when known (FOODDB_SPEC §6: 1 Open Food Facts, 6 NYC
     * MenuStat chain menus, 2–5 USDA), else by its kind.
     */
    private fun dbTag(sourceCode: Int?, kind: Int?): Pair<Int, SourceTag.Style> = when {
        sourceCode == SOURCE_OFF -> R.string.tag_off to SourceTag.Style.DATABASE
        sourceCode == SOURCE_MENUSTAT || kind == KIND_RESTAURANT -> R.string.tag_chain to SourceTag.Style.CHAIN
        kind == KIND_PACKAGED -> R.string.tag_packaged to SourceTag.Style.DATABASE
        else -> R.string.tag_usda to SourceTag.Style.DATABASE
    }

    private const val SOURCE_OFF = 1
    private const val SOURCE_MENUSTAT = 6
    private const val KIND_PACKAGED = 1
    private const val KIND_RESTAURANT = 2

    /** "1 cup · 150 kcal" style subtitle for a hit (brand first when known). */
    fun hitSubtitle(ctx: Context, hit: FoodHit): String {
        val parts = ArrayList<String>(3)
        hit.brand?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        hit.defaultServing?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        hit.kcalX10PerDefault?.let { parts.add(kcalText(ctx, FoodRows.kcalOfX10(it))) }
        return parts.joinToString(" · ")
    }

    /** "150 kcal". */
    fun kcalText(ctx: Context, kcal: Int): String = ctx.getString(R.string.food_kcal, Fmt.number(kcal.toLong()))

    /** Shows or hides [v]. */
    fun show(v: View, visible: Boolean) {
        v.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
