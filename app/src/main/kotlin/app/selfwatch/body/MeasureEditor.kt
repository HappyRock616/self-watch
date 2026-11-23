/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.body

import android.app.DatePickerDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.SwSnackbar
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/** Pure rules of the measurement editor. */
object MeasureInput {
    /** Highest value accepted per dimension, in stored units ×100. */
    private fun maxX100(kind: BodyKind): Int = when (BodyUnits.dim(kind)) {
        BodyUnits.Dim.LENGTH -> 300_00
        BodyUnits.Dim.MASS -> 500_00
        BodyUnits.Dim.PERCENT -> 100_00
        BodyUnits.Dim.PLAIN -> 1_000_000_00
    }

    /**
     * Parses [text] typed in display [units] into the stored value (kind unit ×100), or null when it is
     * not a number or out of range (zero, negative, or beyond a human body).
     */
    fun parse(kind: BodyKind, text: String, units: Units): Int? {
        val x100 = Fmt.parseX100(text) ?: return null
        if (x100 <= 0) return null
        val stored = BodyUnits.fromDisplay(kind, x100 / 100.0, units)
        return stored.takeIf { it in 1..maxX100(kind) }
    }

    /** A stored value as editable text in display units: one decimal for lengths and weights, two otherwise. */
    fun text(kind: BodyKind, valueX100: Int, units: Units): String {
        val decimals = if (BodyUnits.dim(kind) == BodyUnits.Dim.PLAIN) 2 else 1
        return Fmt.x100(Math.round(BodyUnits.toDisplay(kind, valueX100, units) * 100), decimals)
    }

    /** [tSec] moved to [day] in [zone], keeping its time of day. */
    fun moveToDay(tSec: Long, day: LocalDate, zone: ZoneId): Long {
        val time = Instant.ofEpochSecond(tSec).atZone(zone).toLocalTime()
        return day.atTime(time).atZone(zone).toEpochSecond()
    }
}

/**
 * Dialogs to log or edit one measurement (weight, waist, a custom kind…) in display units, with a
 * date button. Saving writes `body_measure` and asks the service to refresh that day's summary.
 */
object MeasureEditor {
    private const val TAG = "MeasureEditor"

    /** Opens the "log" dialog for [kind] (and [customKindId] / [customUnit] for custom kinds). */
    fun log(f: Fragment, kind: BodyKind, units: Units, customKindId: Long? = null, customUnit: String = "", label: CharSequence? = null) {
        val now = System.currentTimeMillis() / 1000
        val draft = BodyMeasure(0, now, kind, customKindId, 0, DataSource.MANUAL, null, null)
        show(f, draft, units, customUnit, label, isNew = true)
    }

    /** Opens the "edit" dialog for [m], with a Delete button (undo offered on the screen). */
    fun edit(f: Fragment, m: BodyMeasure, units: Units, customUnit: String = "", label: CharSequence? = null) {
        show(f, m, units, customUnit, label, isNew = false)
    }

    private fun show(f: Fragment, m: BodyMeasure, units: Units, customUnit: String, label: CharSequence?, isNew: Boolean) {
        val ctx = f.requireContext()
        val zone = ZoneId.systemDefault()
        var tSec = m.tSec
        val unit = BodyUnits.unit(m.kind, units, customUnit)
        val title = label ?: kindLabel(ctx, m.kind)

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Views.dp(ctx, 24)
            setPadding(pad, Views.dp(ctx, 8), pad, 0)
        }
        val field = TextInputLayout(ctx).apply {
            hint = if (unit.isEmpty()) ctx.getString(R.string.measure_value) else ctx.getString(R.string.measure_value_in, unit)
        }
        val edit = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (!isNew) setText(MeasureInput.text(m.kind, m.valueX100, units))
            setSelectAllOnFocus(true)
        }
        field.addView(edit)
        box.addView(field, Views.matchWrap(ctx))
        val dateButton = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle)
        fun showDate() {
            dateButton.text = ctx.getString(R.string.measure_date, Fmt.day(ctx, Day.of(tSec, zone).yyyymmdd))
        }
        showDate()
        dateButton.setOnClickListener {
            val d = Instant.ofEpochSecond(tSec).atZone(zone).toLocalDate()
            DatePickerDialog(ctx, { _, y, mo, dom ->
                tSec = MeasureInput.moveToDay(tSec, LocalDate.of(y, mo + 1, dom), zone)
                showDate()
            }, d.year, d.monthValue - 1, d.dayOfMonth).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
        box.addView(dateButton, Views.matchWrap(ctx, 4))

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (isNew) ctx.getString(R.string.measure_log_title, title) else ctx.getString(R.string.measure_edit_title, title))
            .setView(box)
            .setNegativeButton(R.string.measure_cancel, null)
            .setPositiveButton(R.string.measure_save, null)
        if (!isNew) builder.setNeutralButton(R.string.measure_delete) { _, _ -> deleteWithUndo(f, m, f.view) }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = edit.text.toString()
                if (!isNew && typed == MeasureInput.text(m.kind, m.valueX100, units) && tSec == m.tSec) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val value = MeasureInput.parse(m.kind, typed, units)
                if (value == null) {
                    field.error = ctx.getString(R.string.measure_invalid)
                    return@setOnClickListener
                }
                save(f, m.copy(tSec = tSec, valueX100 = value), isNew, oldTSec = m.tSec)
                dialog.dismiss()
            }
            edit.requestFocus()
        }
        dialog.show()
    }

    private fun save(f: Fragment, m: BodyMeasure, isNew: Boolean, oldTSec: Long) {
        val app = f.requireContext().applicationContext
        val g = app.graph
        f.lifecycleScope.launch {
            try {
                if (isNew) g.db.body.insert(m) else g.db.body.update(m)
                val zone = ZoneId.systemDefault()
                refresh(app, setOf(Day.of(m.tSec, zone), Day.of(oldTSec, zone)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e(TAG, "saving a measurement failed", e)
            }
        }
    }

    /** Deletes [m] and shows "Deleted · Undo" on [anchor] (re-inserts it on undo). */
    fun deleteWithUndo(f: Fragment, m: BodyMeasure, anchor: View?) {
        val app = f.requireContext().applicationContext
        val g = app.graph
        val scope = f.lifecycleScope
        scope.launch {
            try {
                g.db.body.delete(m.id)
                refresh(app, setOf(Day.of(m.tSec, ZoneId.systemDefault())))
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                SwLog.e(TAG, "deleting a measurement failed", e)
                return@launch
            }
            if (anchor != null && anchor.isAttachedToWindow) {
                SwSnackbar.undo(anchor, app.getString(R.string.measure_deleted)) {
                    scope.launch {
                        g.db.body.insert(m)
                        refresh(app, setOf(Day.of(m.tSec, ZoneId.systemDefault())))
                    }
                }
            }
        }
    }

    private fun refresh(ctx: Context, days: Set<Day>) {
        for (d in days) ctx.graph.service.refreshDay(d)
    }

    /** The user-facing name of [kind]. */
    fun kindLabel(ctx: Context, kind: BodyKind): String = ctx.getString(
        when (kind) {
            BodyKind.WEIGHT -> R.string.kind_weight
            BodyKind.BODY_FAT -> R.string.kind_body_fat
            BodyKind.BMI -> R.string.kind_bmi
            BodyKind.WAIST -> R.string.kind_waist
            BodyKind.HIPS -> R.string.kind_hips
            BodyKind.CHEST -> R.string.kind_chest
            BodyKind.ARM -> R.string.kind_arm
            BodyKind.THIGH -> R.string.kind_thigh
            BodyKind.NECK -> R.string.kind_neck
            BodyKind.MUSCLE_MASS -> R.string.kind_muscle
            BodyKind.BONE_MASS -> R.string.kind_bone
            BodyKind.BODY_WATER -> R.string.kind_water
            BodyKind.CUSTOM -> R.string.kind_custom
        },
    )
}

/** Shortcut for the weight dialog. */
object WeightEditor {
    /** Opens "Log weight". */
    fun log(f: Fragment, units: Units) {
        MeasureEditor.log(f, BodyKind.WEIGHT, units)
    }
}
