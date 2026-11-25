/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import app.selfwatch.model.health.Workout
import app.selfwatch.model.time.Day
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import java.time.ZoneId
import app.selfwatch.ui.R as UiR

/**
 * One workout in a list: the sport icon (theme accent), the sport name and date, and the summary stats
 * (duration, distance, pace or speed, average heart rate, calories). [onClick] opens it.
 */
object WorkoutSummaryRow {
    /** The row for [w] in [units]. */
    fun create(ctx: Context, w: Workout, units: Units, zone: ZoneId = ZoneId.systemDefault(), onClick: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            isClickable = true
            isFocusable = true
            val pad = Views.dp(ctx, 8)
            setPaddingRelative(0, pad, 0, pad)
            val ripple = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onClick() }
        }
        val icon = ImageView(ctx).apply {
            setImageResource(Sports.icon(w.sport))
            imageTintList = ColorStateList.valueOf(ChartPalette.of(ctx).primaryText)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val size = Views.dp(ctx, ICON_DP)
        row.addView(icon, LinearLayout.LayoutParams(size, size).apply { marginEnd = Views.dp(ctx, 12) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = ctx.getString(Sports.label(w.sport))
        val date = Fmt.day(ctx, Day.of(w.startSec, zone).yyyymmdd)
        top.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Views.caption(ctx, date), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        texts.addView(top, Views.matchWrap(ctx))
        val stats = Sports.stats(ctx, w, units)
        texts.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, stats), Views.matchWrap(ctx, 2))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.contentDescription = "$name, $date. $stats"
        return row
    }

    private const val ICON_DP = 32
}
