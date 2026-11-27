/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.model.health.HrZoneMath
import app.selfwatch.model.health.HrZoneMethod
import app.selfwatch.model.health.MaxHrOrigin
import app.selfwatch.model.health.RestingHrOrigin
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.SystemClock
import app.selfwatch.service.health.HrZoneStore
import app.selfwatch.service.health.SummaryBuilder
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.R as UiR
import app.selfwatch.SelfWatchApp
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Goals › Heart-rate zones, as on Garmin: Auto (zones as % of the maximum heart rate, which is picked up
 * from workouts, else 220 − age) with a manual override for the maximum, the resting heart rate, the
 * method (% of max or % of heart-rate reserve) and each zone's starting bpm. Saving re-works out the
 * stored active minutes (zone 2 and up) with the new zones.
 */
class HrZonesFragment : DetailFragment() {
    private lateinit var method: MaterialButtonToggleGroup
    private lateinit var maxField: Pair<TextInputLayout, TextInputEditText>
    private lateinit var restField: Pair<TextInputLayout, TextInputEditText>
    private val zoneFields = ArrayList<Pair<TextInputLayout, TextInputEditText>>()
    private lateinit var status: TextView
    private lateinit var save: MaterialButton

    private var picked = HrZoneMethod.MAX_HR
    private var autoMax = HrZoneMath.DEFAULT_MAX_HR
    private var autoRest = HrZoneMath.DEFAULT_RESTING_HR
    private var manualZones = false
    private var settingText = false
    private var loaded = false
    private lateinit var suggestText: TextView
    private lateinit var suggestButton: MaterialButton

    override fun title(): CharSequence = getString(R.string.zones_title)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val top = Views.card(column, null, first = true)
        top.addView(Views.caption(ctx, getString(R.string.zones_intro)), Views.matchWrap(ctx))
        top.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, getString(R.string.zones_method)), Views.matchWrap(ctx, 12))
        method = Views.segments(ctx, listOf(getString(R.string.zones_method_max), getString(R.string.zones_method_hrr))) { i ->
            picked = HrZoneMethod.entries[i]
            restField.first.isEnabled = picked == HrZoneMethod.HRR
            refreshAuto()
        }
        top.addView(method, Views.matchWrap(ctx, 4))
        top.addView(Views.caption(ctx, getString(R.string.zones_method_hrr_help)), Views.matchWrap(ctx, 4))
        maxField = numberField(top, getString(R.string.zones_max_hr), "zones_max")
        suggestText = Views.caption(ctx).apply { visibility = android.view.View.GONE }
        top.addView(suggestText, Views.matchWrap(ctx, 4))
        suggestButton = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            tag = "zones_suggest"
            visibility = android.view.View.GONE
        }
        top.addView(suggestButton, Views.matchWrap(ctx))
        restField = numberField(top, getString(R.string.zones_resting_hr), "zones_rest")

        val zonesCard = Views.card(column, getString(R.string.zones_card))
        status = Views.caption(ctx)
        zonesCard.addView(status, Views.matchWrap(ctx))
        val names = listOf(R.string.zones_zone_1, R.string.zones_zone_2, R.string.zones_zone_3, R.string.zones_zone_4, R.string.zones_zone_5)
        names.forEachIndexed { i, name ->
            val f = numberField(zonesCard, getString(name), "zones_z${i + 1}")
            f.second.addTextChangedListener(watcher {
                manualZones = true
                showStatus()
                showRanges()
            })
            zoneFields += f
        }
        zonesCard.addView(Views.caption(ctx, getString(R.string.zones_active_note)), Views.matchWrap(ctx, 12))
        val back = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.zones_back_to_auto)
            tag = "zones_auto"
            setOnClickListener {
                setText(maxField.second, "")
                setText(restField.second, "")
                manualZones = false
                refreshAuto()
            }
        }
        zonesCard.addView(back, Views.matchWrap(ctx, 4))

        save = MaterialButton(ctx).apply {
            text = getString(R.string.measure_save)
            tag = "zones_save"
            setOnClickListener { saveZones() }
        }
        column.addView(save, Views.matchWrap(ctx, 16))
        maxField.second.addTextChangedListener(watcher { refreshAuto() })
        restField.second.addTextChangedListener(watcher { refreshAuto() })
        load()
    }

    private fun load() {
        val db = requireContext().graph.db
        viewLifecycleOwner.lifecycleScope.launch {
            val store = HrZoneStore(db, SystemClock)
            val (zones, max, rest) = withContext(Dispatchers.IO) { Triple(store.current(), store.maxHr(null), store.restingHr(null)) }
            val suggested = withContext(Dispatchers.IO) { HrZoneMath.suggestedMax(store.highestWorkoutHr(), zones.maxHr) }
            if (suggested != null) {
                // As on Garmin watches without auto-detect: a higher workout heart rate is offered, never applied by itself.
                suggestText.text = getString(R.string.zones_suggest, Fmt.number(suggested.toLong()))
                suggestButton.text = getString(R.string.zones_suggest_use, Fmt.number(suggested.toLong()))
                suggestButton.setOnClickListener {
                    maxField.second.setText(suggested.toString())
                    suggestText.visibility = android.view.View.GONE
                    suggestButton.visibility = android.view.View.GONE
                }
                suggestText.visibility = android.view.View.VISIBLE
                suggestButton.visibility = android.view.View.VISIBLE
            }
            autoMax = max.first
            autoRest = rest.first
            maxField.first.helperText = getString(R.string.zones_auto_value, Fmt.number(max.first.toLong()), getString(maxOrigin(max.second)))
            restField.first.helperText = getString(R.string.zones_auto_value, Fmt.number(rest.first.toLong()), getString(restOrigin(rest.second)))
            picked = zones.method
            Views.selectSegment(method, zones.method.ordinal)
            restField.first.isEnabled = picked == HrZoneMethod.HRR
            setText(maxField.second, if (zones.maxOrigin == MaxHrOrigin.MANUAL) zones.maxHr.toString() else "")
            setText(restField.second, if (zones.restingOrigin == RestingHrOrigin.MANUAL) zones.restingHr.toString() else "")
            manualZones = zones.manual
            setZones(zones.bounds)
            loaded = true
        }
    }

    /** Fills the zones from the method, maximum and resting heart rate unless the user set them. */
    private fun refreshAuto() {
        if (settingText || manualZones) {
            showStatus()
            return
        }
        setZones(HrZoneMath.bounds(picked, typed(maxField) ?: autoMax, typed(restField) ?: autoRest))
    }

    private fun setZones(bounds: List<Int>) {
        settingText = true
        bounds.forEachIndexed { i, b -> zoneFields[i].second.setText(b.toString()) }
        settingText = false
        showStatus()
        showRanges()
    }

    private fun showStatus() {
        status.text = getString(if (manualZones) R.string.zones_status_manual else R.string.zones_status_auto)
    }

    private fun showRanges() {
        val b = zoneFields.map { typed(it) }
        for (i in 0 until 5) {
            val lo = b[i]
            val hi = b.getOrNull(i + 1)
            zoneFields[i].first.helperText = when {
                lo == null -> null
                i == 4 || hi == null -> getString(R.string.zones_range_top, Fmt.number(lo.toLong()))
                else -> getString(R.string.zones_range, Fmt.number(lo.toLong()), Fmt.number((hi - 1).toLong()))
            }
            zoneFields[i].first.error = null
        }
    }

    private fun saveZones() {
        if (!loaded) return
        val bounds = zoneFields.map { typed(it) ?: 0 }
        if (manualZones && !HrZoneMath.valid(bounds)) {
            zoneFields[0].first.error = getString(R.string.zones_bad_order)
            return
        }
        val app = requireContext().applicationContext as SelfWatchApp
        val db = app.graph.db
        val max = typed(maxField)
        val rest = typed(restField)
        val method = picked
        val manual = if (manualZones) bounds else null
        save.isEnabled = false
        // The app scope: re-working out the stored days continues after this screen closes; the cards
        // update as the day summaries change.
        app.appScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val store = HrZoneStore(db, SystemClock)
                    store.save(method, max, rest, manual)
                    store.applyToHistory(SummaryBuilder(db, SystemClock))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e(TAG, "saving heart-rate zones failed", e)
            }
        }
        close()
    }

    private fun numberField(parent: android.view.ViewGroup, hint: String, tag: String): Pair<TextInputLayout, TextInputEditText> {
        val ctx = requireContext()
        val layout = TextInputLayout(ctx).apply {
            this.hint = hint
            suffixText = getString(R.string.zones_bpm)
        }
        val edit = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            id = android.view.View.generateViewId()
            this.tag = tag
        }
        layout.addView(edit)
        parent.addView(layout, Views.matchWrap(ctx, 8))
        return layout to edit
    }

    private fun typed(f: Pair<TextInputLayout, TextInputEditText>): Int? = f.second.text?.toString()?.trim()?.toIntOrNull()

    private fun setText(e: TextInputEditText, text: String) {
        settingText = true
        e.setText(text)
        settingText = false
    }

    private fun watcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (!settingText) onChange()
        }
    }

    private companion object {
        const val TAG = "HrZones"

        fun maxOrigin(o: MaxHrOrigin): Int = when (o) {
            MaxHrOrigin.DEFAULT -> R.string.zones_max_default
            else -> R.string.zones_max_age
        }

        fun restOrigin(o: RestingHrOrigin): Int = when (o) {
            RestingHrOrigin.DEFAULT -> R.string.zones_rest_default
            else -> R.string.zones_rest_measured
        }
    }
}
