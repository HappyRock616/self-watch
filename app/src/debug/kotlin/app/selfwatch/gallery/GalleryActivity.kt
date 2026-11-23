/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.gallery

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import app.selfwatch.R
import app.selfwatch.me.MeFragment
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.time.TimeRange
import app.selfwatch.ui.SwActivity
import app.selfwatch.ui.SwTheme
import app.selfwatch.ui.charts.Axis
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.charts.ChartSamples
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.charts.OverlayChartView
import app.selfwatch.ui.charts.StageLayer
import app.selfwatch.ui.charts.TrendChartView
import app.selfwatch.ui.charts.WeightTrendView
import app.selfwatch.ui.components.BarSeries
import app.selfwatch.ui.components.BarsView
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.NumberStepper
import app.selfwatch.ui.components.RingView
import app.selfwatch.ui.components.SourceTag
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.components.SwSheet
import app.selfwatch.ui.components.SwSnackbar
import app.selfwatch.ui.components.ThemeSwatch
import app.selfwatch.ui.components.ToggleChipGroup
import app.selfwatch.ui.components.ToggleItem
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import app.selfwatch.ui.R as UiR

/**
 * Debug-only component gallery (task C03): every ui component with sample values, a dark / light /
 * system switch, the five color themes and a font-size switch (1.0×, 1.5×, 2.0×) for checking layouts at
 * 200% text. Launched from its own "SW Gallery" launcher entry.
 */
class GalleryActivity : SwActivity() {

    override val colorTheme: SwTheme.Theme get() = themeChoice

    private lateinit var componentHost: LinearLayout
    private lateinit var fontNote: TextView

    override fun attachBaseContext(newBase: Context) {
        // App-wide night mode, as the real app sets it from the Appearance setting (SwTheme.applyNightMode).
        if (AppCompatDelegate.getDefaultNightMode() != nightChoice) AppCompatDelegate.setDefaultNightMode(nightChoice)
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(pad, pad, pad, pad)
        }
        val scroll = NestedScrollView(this).apply {
            clipToPadding = true
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        column.addView(text(this, UiR.style.TextAppearance_SelfWatch_Headline, getString(R.string.gallery_title)))
        addControls(column)
        fontNote = text(this, UiR.style.TextAppearance_SelfWatch_Caption, "")
        column.addView(fontNote)
        componentHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(componentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        renderComponents()

        setRootContent(scroll)
    }

    // ---- Controls ----

    private fun addControls(parent: LinearLayout) {
        parent.addView(label(getString(R.string.gallery_theme)))
        parent.addView(
            toggleGroup(
                listOf(
                    getString(R.string.gallery_dark) to AppCompatDelegate.MODE_NIGHT_YES,
                    getString(R.string.gallery_light) to AppCompatDelegate.MODE_NIGHT_NO,
                    getString(R.string.gallery_system) to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                ),
                nightChoice,
            ) { mode ->
                if (mode != nightChoice) {
                    nightChoice = mode
                    AppCompatDelegate.setDefaultNightMode(mode)
                }
            },
        )
        parent.addView(label(getString(R.string.gallery_color_theme)))
        parent.addView(
            toggleGroup(
                listOf(
                    getString(R.string.theme_turquoise) to SwTheme.Theme.TURQUOISE,
                    getString(R.string.theme_purple) to SwTheme.Theme.PURPLE,
                    getString(R.string.theme_orange) to SwTheme.Theme.ORANGE,
                    getString(R.string.theme_yellow) to SwTheme.Theme.YELLOW,
                    getString(R.string.theme_forest) to SwTheme.Theme.FOREST,
                ),
                themeChoice,
            ) { t ->
                if (t != themeChoice) {
                    themeChoice = t
                    recreate()
                }
            },
        )
        parent.addView(label(getString(R.string.gallery_font)))
        parent.addView(
            toggleGroup(
                FONT_SCALES.map { getString(R.string.gallery_font_scale, Fmt.number(it.toDouble(), 1)) to it },
                fontScaleChoice,
            ) { s ->
                if (s != fontScaleChoice) {
                    fontScaleChoice = s
                    renderComponents()
                }
            },
        )
    }

    private fun <T> toggleGroup(options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit): View {
        val group = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val ids = HashMap<Int, T>()
        for ((text, value) in options) {
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                this.text = text
                isAllCaps = false
                val h = dp(this@GalleryActivity, 4)
                setPaddingRelative(h, paddingTop, h, paddingBottom)
            }
            ids[b.id] = value
            group.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (value == selected) group.check(b.id)
        }
        group.addOnButtonCheckedListener { _, id, checked -> if (checked) ids[id]?.let(onPick) }
        return group
    }

    // ---- Components at the chosen font scale ----

    private fun renderComponents() {
        fontNote.text = getString(R.string.gallery_font_note, Fmt.number(fontScaleChoice.toDouble(), 1))
        componentHost.removeAllViews()
        val ctx = scaledContext(fontScaleChoice)
        val host = componentHost
        chartSection(ctx, host)
        typeSection(ctx, host)
        colorSection(ctx, host)
        cardSection(ctx, host)
        ringSection(ctx, host)
        tileSection(ctx, host)
        chipSection(ctx, host)
        rowSection(ctx, host)
        stepperSection(ctx, host)
        emptySection(ctx, host)
        sheetSection(ctx, host)
        fmtSection(ctx, host)
    }

    /** A themed context whose resources use [scale] as the font scale. */
    private fun scaledContext(scale: Float): Context {
        val cfg = Configuration(resources.configuration).apply { fontScale = scale }
        return ContextThemeWrapper(this, UiR.style.Theme_SelfWatch).also {
            it.applyOverrideConfiguration(cfg)
            SwTheme.applyTheme(it, themeChoice)
        }
    }

    /** Charts (tasks C07 and C09) with generated data: a night, 12 weeks of walking, 90 days of weights. */
    private fun chartSection(ctx: Context, host: LinearLayout) {
        section(ctx, host, R.string.gallery_sec_charts)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val nightStart = today.minusDays(1).atTime(23, 0).atZone(zone).toEpochSecond()
        val night = ChartSamples.night(nightStart)

        val sleep = SwCard(ctx).apply { title = ctx.getString(R.string.gallery_sleep_title) }
        val readout = text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, ctx.getString(R.string.gallery_scrub_hint))
        val chart = OverlayChartView(ctx).apply {
            id = R.id.gallery_overlay_chart
            range = TimeRange(night.startSec, night.endSec)
            layers = listOf(
                StageLayer("stages", ctx.getString(R.string.gallery_stages), palette(ctx).secondary, night.stages),
                LineLayer("hr", ctx.getString(R.string.gallery_hr), palette(ctx).series(0), Axis.LEFT, night.hrT, night.hr, unit = ctx.getString(R.string.gallery_bpm)),
                LineLayer("hrv", ctx.getString(R.string.gallery_hrv), palette(ctx).series(1), Axis.RIGHT, night.hrvT, night.hrv, unit = ctx.getString(R.string.gallery_ms)),
                LineLayer("breathing", ctx.getString(R.string.gallery_breathing), palette(ctx).series(2), Axis.HIDDEN, night.breathT, night.breathX10, scale = 10, unit = ctx.getString(R.string.gallery_br_min)),
            )
            onScrub = { t, values ->
                readout.text = ctx.getString(R.string.gallery_scrub_values, Fmt.time(ctx, t, zone), values.joinToString(" · ") { it.text })
            }
        }
        sleep.addView(chart, matchWrap())
        val chips = ToggleChipGroup(ctx).apply { chartId = "gallery_sleep" }
        sleep.addView(chips, matchWrap())
        chart.bindChips(chips)
        sleep.addView(readout)
        host.addView(sleep, matchWrap())

        val weeks = 12
        val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks((weeks - 1).toLong())
        val dateFmt = android.icu.text.DateFormat.getInstanceForSkeleton("dMMM", Locale.getDefault())
        val weekLabels = (0 until weeks).map {
            val d = weekStart.plusWeeks(it.toLong())
            dateFmt.format(java.util.Date(d.atStartOfDay(zone).toEpochSecond() * 1000L))
        }
        val walking = SwCard(ctx).apply { title = ctx.getString(R.string.gallery_walking) }
        walking.addView(
            TrendChartView(ctx).apply {
                id = R.id.gallery_trend_chart
                values = ChartSamples.weeklyDistanceX10(weeks)
                scale = 10
                unit = ctx.getString(R.string.gallery_km)
                labels = weekLabels
                longLabels = weekLabels.map { ctx.getString(R.string.gallery_week_of, it) }
                goal = 250
                onSelect = { i -> if (i >= 0) toast(ctx.getString(R.string.gallery_selected, longLabels[i])) }
            },
            matchWrap(),
        )
        host.addView(walking, matchWrap().apply { topMargin = dp(ctx, 8) })

        val (wt, kg) = ChartSamples.weights(today.minusDays(89).atStartOfDay(zone).toEpochSecond())
        val weight = SwCard(ctx).apply { title = ctx.getString(R.string.gallery_weight_90) }
        weight.addView(
            WeightTrendView(ctx).apply {
                id = R.id.gallery_weight_chart
                units = Units.forLocale(Locale.getDefault())
                goalKgX100 = 7800
                setEntries(wt, kg)
            },
            matchWrap(),
        )
        host.addView(weight, matchWrap().apply { topMargin = dp(ctx, 8) })
    }

    private fun typeSection(ctx: Context, host: LinearLayout) {
        section(ctx, host, R.string.gallery_sec_type)
        val card = SwCard(ctx)
        val n = Fmt.number(7842L)
        card.addView(text(ctx, UiR.style.TextAppearance_SelfWatch_Display_Number, n))
        card.addView(text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, ctx.getString(R.string.gallery_type_display)))
        card.addView(text(ctx, UiR.style.TextAppearance_SelfWatch_Headline, ctx.getString(R.string.gallery_type_headline)))
        card.addView(text(ctx, UiR.style.TextAppearance_SelfWatch_Title, ctx.getString(R.string.gallery_type_title)))
        card.addView(text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, ctx.getString(R.string.gallery_type_body_large)))
        card.addView(text(ctx, UiR.style.TextAppearance_SelfWatch_Body, ctx.getString(R.string.gallery_type_body)))
        card.addView(text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, ctx.getString(R.string.gallery_type_caption)))
        host.addView(card, matchWrap())
    }

    private fun colorSection(ctx: Context, host: LinearLayout) {
        section(ctx, host, R.string.gallery_sec_colors)
        // Every theme's swatch pair (primary + secondary) in this look.
        for (t in SwTheme.Theme.entries) {
            val name = ctx.getString(MeFragment.themeLabel(t))
            host.addView(text(ctx, UiR.style.TextAppearance_SelfWatch_Body, ThemeSwatch.label(ctx, t, name)))
        }
        // The current theme's chart palette: series 1–6, sleep stages, neutrals and the alarm red.
        val p = palette(ctx)
        val named = buildList {
            for (i in 0 until p.seriesCount) add(ctx.getString(R.string.gallery_series, i + 1) to p.series(i))
            for (s in listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE)) {
                add(ctx.getString(R.string.gallery_stage, s.name.lowercase()) to p.stage(s))
            }
            add(ctx.getString(R.string.gallery_text) to p.text)
            add(ctx.getString(R.string.gallery_muted) to p.muted)
            add(ctx.getString(R.string.gallery_track) to p.track)
            add(ctx.getString(R.string.gallery_alarm) to p.alarm)
        }
        val flow = com.google.android.material.chip.ChipGroup(ctx).apply {
            chipSpacingHorizontal = dp(ctx, 6)
            chipSpacingVertical = dp(ctx, 6)
        }
        for ((name, c) in named) {
            val swatch = View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(ctx, 6).toFloat()
                    setColor(c)
                    setStroke(dp(ctx, 1), ContextCompat.getColor(ctx, UiR.color.sw_outline))
                }
                contentDescription = name
            }
            flow.addView(swatch, ViewGroup.LayoutParams(dp(ctx, 36), dp(ctx, 36)))
        }
        host.addView(flow, matchWrap())
    }

    private fun cardSection(ctx: Context, host: LinearLayout) {
        section(ctx, host, R.string.gallery_sec_cards)
        val card = SwCard(ctx).apply {
            title = ctx.getString(R.string.gallery_energy_today)
            actionText = ctx.getString(R.string.gallery_all)
            onAction = { toast(ctx.getString(R.string.gallery_all)) }
        }
        val days = ctx.resources.getStringArray(R.array.gallery_days).toList()
        card.addView(
            BarsView(ctx).apply {
                labels = days
                series = listOf(
                    BarSeries(ctx.getString(R.string.gallery_in), palette(ctx).primary, intArrayOf(1850, 2300, 1700, 2600, 1950, 2900, 1180)),
                    BarSeries(ctx.getString(R.string.gallery_out), palette(ctx).secondary, intArrayOf(2350, 2200, 2450, 2300, 2500, 2100, 2420)),
                )
                goalLine = 2100f
            },
            matchWrap(),
        )
        card.addView(text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, ctx.getString(R.string.gallery_last7)))
        host.addView(card, matchWrap())
    }

    private fun ringSection(ctx: Context, host: LinearLayout) {
        section(ctx, host, R.string.gallery_sec_rings)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun ring(p: Float, center: String, sub: String, seriesColor: Int?) = SwCard(ctx).apply {
            addView(
                RingView(ctx).apply {
                    progress = p
                    if (seriesColor != null) color = seriesColor
                    centerText = center
                    subText = sub
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        val gap = dp(ctx, 8)
        row.addView(ring(0.78f, Fmt.number(7842L), ctx.getString(R.string.gallery_of, Fmt.number(10000L)), null), weighted(0))
        row.addView(ring(1.13f, ctx.getString(R.string.gallery_min, "34"), ctx.getString(R.string.gallery_active), palette(ctx).secondary), weighted(gap))
        row.addView(ring(0.84f, Fmt.number(420L), ctx.getString(R.string.gallery_active_kcal), palette(ctx).series(2)), weighted(gap))
        host.addView(row, matchWrap())
        // Animated ring.
        val big = RingView(ctx).apply {
            centerText = Fmt.percent(0.62)
            subText = ctx.getString(R.string.gallery_today)
            ringWidth = dp(ctx, 14).toFloat()
            setOnClickListener { animateProgress(if (progress > 0.5f) 0.2f else 0.62f) }
        }
        host.addView(big, LinearLayout.LayoutParams(dp(ctx, 160), dp(ctx, 160)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = gap
        })
        big.animateProgress(0.62f)
    }

    private fun tileSection(ctx: Context, host: LinearLayout) {
        section(ctx, host, R.string.gallery_sec_tiles)
        val weight = SwCard(ctx).apply { title = ctx.getString(R.string.gallery_weight) }
        weight.addView(
            StatTile(ctx).apply {
                setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
                value = Fmt.number(82.4, 1)
                unit = ctx.getString(UiR.string.sw_unit_kg)
                delta = -0.6f
                deltaText = ctx.getString(R.string.gallery_this_week, Fmt.number(0.6, 1))
                deltaGoodWhenDown = true
            },
        )
        host.addView(weight, matchWrap())

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 8), 0, 0)
        }
        val gap = dp(ctx, 8)
        fun tile(v: String, u: String?, l: String, d: Float?, dt: String?, goodDown: Boolean) = SwCard(ctx).apply {
            addView(
                StatTile(ctx).apply {
                    value = v
                    unit = u
                    label = l
                    delta = d
                    deltaText = dt
                    deltaGoodWhenDown = goodDown
                },
            )
        }
        row.addView(tile("91", ctx.getString(UiR.string.sw_unit_cm), ctx.getString(R.string.gallery_waist), -1.5f, Fmt.number(1.5, 1), true), weighted(0))
        row.addView(tile("54", null, ctx.getString(R.string.gallery_rest_hr), 2f, "2", true), weighted(gap))
        row.addView(tile("48", ctx.getString(R.string.gallery_ms), ctx.getString(R.string.gallery_hrv), 3f, "3", false), weighted(gap))
        host.addView(row, matchWrap())
    }

    private fun chipSection(ctx: Context, host: LinearLayout) {
        section(ctx, host, R.string.gallery_sec_chips)
        host.addView(
            ToggleChipGroup(ctx).apply {
                chartId = "gallery"
                items = listOf(
                    ToggleItem("distance", ctx.getString(R.string.gallery_distance), palette(ctx).series(0)),
                    ToggleItem("active", ctx.getString(R.string.gallery_active_min), palette(ctx).series(1)),
                    ToggleItem("steps", ctx.getString(R.string.gallery_steps), palette(ctx).series(2), initiallyOn = false),
                    ToggleItem("kcal", ctx.getString(R.string.gallery_active_kcal), palette(ctx).series(3), initiallyOn = false),
                )
                onChange = { id, on, _ ->
                    toast(ctx.getString(R.string.gallery_chips_changed, id, ctx.getString(if (on) R.string.gallery_on else R.string.gallery_off)))
                }
            },
            matchWrap(),
        )
    }

    private fun rowSection(ctx: Context, host: LinearLayout) {
        section(ctx, host, R.string.gallery_sec_rows)
        val card = SwCard(ctx)
        card.addView(
            ListRow(ctx).apply {
                title = ctx.getString(R.string.gallery_walk)
                subtitle = ctx.getString(R.string.gallery_walk_sub)
                trailing = Fmt.energy(210)
                onClick = { toast(ctx.getString(R.string.gallery_tapped, ctx.getString(R.string.gallery_walk))) }
            },
        )
        card.addView(
            ListRow(ctx).apply {
                title = ctx.getString(R.string.gallery_yogurt)
                subtitle = ctx.getString(R.string.gallery_yogurt_sub)
                addTitleAccessory(SourceTag(ctx).apply { text = ctx.getString(R.string.gallery_tag_usda) })
                onClick = { toast(ctx.getString(R.string.gallery_tapped, ctx.getString(R.string.gallery_yogurt))) }
            },
        )
        card.addView(
            ListRow(ctx).apply {
                title = ctx.getString(R.string.gallery_burger)
                subtitle = ctx.getString(R.string.gallery_burger_sub)
                addTitleAccessory(SourceTag(ctx).apply { text = ctx.getString(R.string.gallery_tag_2018); style = SourceTag.Style.CHAIN })
            },
        )
        card.addView(
            ListRow(ctx).apply {
                title = ctx.getString(R.string.gallery_weight_trend)
                subtitle = ctx.getString(R.string.gallery_weight_trend_sub)
                trailing = Fmt.weight(8240, Units.forLocale(Locale.getDefault()))
                addTrailingAccessory(Sparkline(ctx).apply { values = intArrayOf(840, 838, 839, 836, 835, 833, 834, 831, 829, 828, 826, 824) })
                onClick = { toast(ctx.getString(R.string.gallery_tapped, ctx.getString(R.string.gallery_weight_trend))) }
            },
        )
        card.addView(
            ListRow(ctx).apply {
                title = ctx.getString(R.string.gallery_watch_apps)
                trailing = ctx.getString(R.string.gallery_installed)
                showChevron = true
                onClick = { toast(ctx.getString(R.string.gallery_tapped, ctx.getString(R.string.gallery_watch_apps))) }
            },
        )
        host.addView(card, matchWrap())

        val tags = com.google.android.material.chip.ChipGroup(ctx).apply {
            setPadding(0, dp(ctx, 8), 0, 0)
            chipSpacingHorizontal = dp(ctx, 8)
        }
        listOf(
            R.string.gallery_tag_usda to SourceTag.Style.DATABASE,
            R.string.gallery_tag_off to SourceTag.Style.DATABASE,
            R.string.gallery_tag_2018 to SourceTag.Style.CHAIN,
            R.string.gallery_tag_mine to SourceTag.Style.MINE,
            R.string.gallery_tag_meal to SourceTag.Style.MEAL,
        ).forEach { (s, st) -> tags.addView(SourceTag(ctx).apply { text = ctx.getString(s); style = st }) }
        host.addView(tags, matchWrap())
    }

    private fun stepperSection(ctx: Context, host: LinearLayout) {
        section(ctx, host, R.string.gallery_sec_stepper)
        val card = SwCard(ctx)
        val readout = text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, ctx.getString(R.string.gallery_value, "150"))
        card.addView(
            NumberStepper(ctx).apply {
                unit = ctx.getString(R.string.gallery_cups)
                stepX100 = 25
                maxX100 = 2000
                valueX100 = 150
                onValueChange = { readout.text = ctx.getString(R.string.gallery_value, it.toString()) }
            },
            matchWrap(),
        )
        card.addView(readout)
        host.addView(card, matchWrap())
    }

    private fun emptySection(ctx: Context, host: LinearLayout) {
        section(ctx, host, R.string.gallery_sec_empty)
        val card = SwCard(ctx)
        card.addView(
            EmptyState(ctx).apply {
                message = ctx.getString(R.string.gallery_empty_message)
                buttonText = ctx.getString(R.string.gallery_add_food)
                onButton = { toast(ctx.getString(R.string.gallery_add_food)) }
            },
            matchWrap(),
        )
        host.addView(card, matchWrap())
    }

    private fun sheetSection(ctx: Context, host: LinearLayout) {
        section(ctx, host, R.string.gallery_sec_sheet)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        row.addView(
            MaterialButton(ctx).apply {
                text = ctx.getString(R.string.gallery_open_sheet)
                isAllCaps = false
                setOnClickListener { GallerySheet().show(supportFragmentManager, "gallery_sheet") }
            },
            matchWrap(),
        )
        row.addView(
            MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = ctx.getString(R.string.gallery_show_undo)
                isAllCaps = false
                setOnClickListener { v ->
                    SwSnackbar.undo(v, ctx.getString(R.string.gallery_deleted)) { toast(ctx.getString(R.string.gallery_undone)) }
                }
            },
            matchWrap(),
        )
        host.addView(row, matchWrap())
    }

    private fun fmtSection(ctx: Context, host: LinearLayout) {
        section(ctx, host, R.string.gallery_sec_fmt)
        val today = LocalDate.now()
        val units = Units.forLocale(Locale.getDefault())
        val nowSec = System.currentTimeMillis() / 1000L
        val lines = listOf(
            Fmt.day(ctx, Fmt.dateToDay(today)),
            Fmt.day(ctx, Fmt.dateToDay(today.minusDays(1))),
            Fmt.day(ctx, Fmt.dateToDay(today.minusDays(3))),
            Fmt.time(ctx, nowSec, ZoneId.systemDefault()),
            Fmt.duration(7 * 3600L + 12 * 60L),
            Fmt.duration(42 * 60L),
            Fmt.energy(-1240),
            Fmt.weight(8240, Units.METRIC) + " · " + Fmt.weight(8240, Units.IMPERIAL),
            Fmt.length(91.0, Units.METRIC) + " · " + Fmt.length(91.0, Units.IMPERIAL),
            Fmt.distance(3200.0, units) + " · " + Fmt.distance(850.0, units),
            Fmt.percent(0.24),
        )
        val card = SwCard(ctx)
        lines.forEach { card.addView(text(ctx, UiR.style.TextAppearance_SelfWatch_Body_Number, it)) }
        host.addView(card, matchWrap())
    }

    // ---- Helpers ----

    private fun section(ctx: Context, host: LinearLayout, title: Int) {
        host.addView(
            text(ctx, UiR.style.TextAppearance_SelfWatch_Title, ctx.getString(title)).apply {
                androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 24)
                bottomMargin = dp(ctx, 8)
            },
        )
    }

    private fun label(s: String): TextView =
        text(this, UiR.style.TextAppearance_SelfWatch_Caption, s).apply { setPadding(0, dp(this@GalleryActivity, 12), 0, dp(this@GalleryActivity, 4)) }

    private fun text(ctx: Context, appearance: Int, s: CharSequence): TextView =
        TextView(ctx).apply {
            TextViewCompat.setTextAppearance(this, appearance)
            text = s
        }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun weighted(startMargin: Int) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = startMargin }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun palette(ctx: Context): ChartPalette = ChartPalette.of(ctx)

    private fun toast(s: String) {
        SwSnackbar.show(componentHost, s)
    }

    private companion object {
        val FONT_SCALES: List<Float> = listOf(1.0f, 1.5f, 2.0f)

        // Gallery choices survive recreate() (light/dark and color theme changes) for the life of the process.
        var nightChoice: Int = AppCompatDelegate.MODE_NIGHT_YES
        var themeChoice: SwTheme.Theme = SwTheme.DEFAULT_THEME
        var fontScaleChoice: Float = 1.0f
    }
}

/** Sample sheet for the gallery. */
class GallerySheet : SwSheet() {
    override val sheetTitle: CharSequence get() = getString(R.string.gallery_sheet_title)

    override fun onCreateSheetContent(inflater: LayoutInflater, container: ViewGroup?): View {
        val ctx = inflater.context
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            TextView(ctx).apply {
                TextViewCompat.setTextAppearance(this, UiR.style.TextAppearance_SelfWatch_Body)
                text = getString(R.string.gallery_sheet_body)
            },
        )
        column.addView(
            ListRow(ctx).apply {
                title = getString(R.string.gallery_quick_add)
                trailing = getString(R.string.gallery_add)
                onClick = { dismiss() }
            },
        )
        column.addView(
            NumberStepper(ctx).apply {
                unit = getString(UiR.string.sw_unit_kcal)
                stepX100 = 5000
                maxX100 = 500_000
                decimals = 0
                valueX100 = 25_000
            },
        )
        return column
    }
}
