/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.data

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.annotation.StringRes
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.food.FoodViews
import app.selfwatch.graph
import app.selfwatch.interop.api.WeightUnit
import app.selfwatch.me.MeViews
import app.selfwatch.shell.DisplayUnits
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.launch
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import app.selfwatch.ui.R as UiR

/**
 * Export sheet (C18): pick a format and a date range, then "Choose where to save" opens the system
 * file picker (`CREATE_DOCUMENT`) and the export runs into the chosen file. Hosted by [DataActivity].
 */
class ExportSheet : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val pad = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(pad, pad, pad, pad)
        }
        MeViews.add(col, MeViews.heading(ctx, getString(R.string.export_title)), gap = false)

        MeViews.add(col, MeViews.caption(ctx, getString(R.string.export_format)))
        val formats = RadioGroup(ctx)
        DataViewModel.EXPORT_ORDER.forEachIndexed { i, id ->
            formats.addView(radio(FORMAT_BASE + i, formatLabel(id)), FoodViews.matchWrap())
        }
        MeViews.add(col, formats, gap = false)

        val unitLabel = MeViews.caption(ctx, getString(R.string.export_weight_unit))
        MeViews.add(col, unitLabel)
        val units = RadioGroup(ctx).apply { orientation = RadioGroup.HORIZONTAL }
        units.addView(radio(UNIT_KG, getString(R.string.import_unit_kg)))
        units.addView(radio(UNIT_LB, getString(R.string.import_unit_lb)))
        MeViews.add(col, units, gap = false)

        MeViews.add(col, MeViews.caption(ctx, getString(R.string.export_range)))
        val ranges = RadioGroup(ctx)
        ExportRange.entries.forEachIndexed { i, r -> ranges.addView(radio(RANGE_BASE + i, getString(rangeLabel(r))), FoodViews.matchWrap()) }
        MeViews.add(col, ranges, gap = false)

        formats.check(savedInstanceState?.getInt(KEY_FORMAT) ?: FORMAT_BASE)
        ranges.check(savedInstanceState?.getInt(KEY_RANGE) ?: RANGE_BASE)
        units.check(savedInstanceState?.getInt(KEY_UNIT) ?: UNIT_KG)
        // The weight unit starts at the Units setting (pounds for US units).
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                val shown = DisplayUnits.of(ctx.graph.db.settings.profile().units)
                units.check(if (shown == Units.IMPERIAL) UNIT_LB else UNIT_KG)
            }
        }
        fun syncUnits() {
            val body = DataViewModel.EXPORT_ORDER.getOrNull(formats.checkedRadioButtonId - FORMAT_BASE) == GARMIN_BODY
            unitLabel.visibility = if (body) View.VISIBLE else View.GONE
            units.visibility = unitLabel.visibility
        }
        syncUnits()
        formats.setOnCheckedChangeListener { _, _ -> syncUnits() }

        MeViews.add(
            col,
            FoodViews.filledButton(ctx, getString(R.string.export_save)) {
                val id = DataViewModel.EXPORT_ORDER.getOrNull(formats.checkedRadioButtonId - FORMAT_BASE) ?: return@filledButton
                val range = ExportRange.entries.getOrNull(ranges.checkedRadioButtonId - RANGE_BASE) ?: ExportRange.ALL
                val unit = if (units.checkedRadioButtonId == UNIT_LB) WeightUnit.LB else WeightUnit.KG
                dismiss()
                (activity as? DataActivity)?.startExport(id, range, unit)
            },
        )
        this.formats = formats
        this.ranges = ranges
        this.units = units
        return NestedScrollView(ctx).apply { addView(col) }
    }

    private var formats: RadioGroup? = null
    private var ranges: RadioGroup? = null
    private var units: RadioGroup? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        formats?.let { outState.putInt(KEY_FORMAT, it.checkedRadioButtonId) }
        ranges?.let { outState.putInt(KEY_RANGE, it.checkedRadioButtonId) }
        units?.let { outState.putInt(KEY_UNIT, it.checkedRadioButtonId) }
    }

    private fun radio(id: Int, label: CharSequence): RadioButton = RadioButton(requireContext()).apply {
        this.id = id
        text = label
        minHeight = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
        minimumHeight = minHeight
    }

    /** Labels. */
    companion object {
        /** Fragment tag. */
        const val TAG: String = "export_sheet"
        private const val KEY_FORMAT = "format"
        private const val KEY_RANGE = "range"
        private const val KEY_UNIT = "unit"
        private const val FORMAT_BASE = 1
        private const val RANGE_BASE = 50
        private const val UNIT_KG = 100
        private const val UNIT_LB = 101
        private const val GARMIN_BODY = "garmin-body-csv"

        @StringRes
        private fun formatRes(id: String): Int = when (id) {
            "gpx" -> R.string.export_gpx
            "fit" -> R.string.export_fit
            "tcx" -> R.string.export_tcx
            "cronometer-csv" -> R.string.export_cronometer
            "json" -> R.string.export_json
            "garmin-body-csv" -> R.string.export_garmin_body
            "fit-weight" -> R.string.export_fit_weight
            "recipes-nextcloud" -> R.string.export_recipes
            "foodlist-csv" -> R.string.export_foods
            else -> 0
        }

        /** The range label. */
        @StringRes
        fun rangeLabel(r: ExportRange): Int = when (r) {
            ExportRange.ALL -> R.string.export_range_all
            ExportRange.LAST_30_DAYS -> R.string.export_range_30
            ExportRange.LAST_YEAR -> R.string.export_range_year
            ExportRange.THIS_YEAR -> R.string.export_range_this_year
        }
    }

    private fun formatLabel(id: String): String = formatRes(id).let { if (it == 0) id else getString(it) }
}
