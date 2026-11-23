/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import android.app.DatePickerDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.viewModels
import app.selfwatch.R
import app.selfwatch.food.FoodViews
import app.selfwatch.graph
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import app.selfwatch.shell.DetailFragment
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.ListRow
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import app.selfwatch.ui.R as UiR

/**
 * Export activities (Activity ⋮ Export activities): every workout in a date range as GPX, FIT or
 * TCX, one file per workout in a zip, saved through the Storage Access Framework, with progress and
 * a result line.
 */
class ActivityExportFragment : DetailFragment() {

    private val vm: ActivityExportViewModel by viewModels {
        ActivityExportViewModel.factory(requireContext().graph, requireContext().applicationContext.filesDir)
    }

    private lateinit var createDoc: ActivityResultLauncher<ExportTarget>
    private lateinit var ui: Ui

    private class Ui(
        val format: MaterialButtonToggleGroup,
        val range: ListRow,
        val from: ListRow,
        val to: ListRow,
        val summary: TextView,
        val export: MaterialButton,
        val progress: LinearProgressIndicator,
        val progressText: TextView,
        val result: TextView,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createDoc = registerForActivityResult(CreateDocumentFor()) { uri -> onDocument(uri) }
    }

    override fun title(): CharSequence = getString(R.string.export_act_title)

    override fun onContent(column: LinearLayout, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        column.addView(Views.caption(ctx, getString(R.string.export_act_intro)), Views.matchWrap(ctx))

        val formatCard = Views.card(column, getString(R.string.export_act_format))
        val labels = WorkoutExport.FORMATS.map { it.uppercase(Locale.ROOT) as CharSequence }
        val format = Views.segments(ctx, labels) { i -> vm.setFormat(WorkoutExport.FORMATS[i]) }
        format.contentDescription = getString(R.string.export_act_format)
        formatCard.content.addView(format, FoodViews.matchWrap())
        formatCard.content.addView(Views.caption(ctx, getString(R.string.export_act_format_help)), FoodViews.matchWrap())

        val rangeCard = Views.card(column, getString(R.string.export_act_range))
        val range = row(rangeCard.content, getString(R.string.export_act_range)) { pickRange() }
        val from = row(rangeCard.content, getString(R.string.export_act_from)) { pickDay(first = true) }
        val to = row(rangeCard.content, getString(R.string.export_act_to)) { pickDay(first = false) }

        val runCard = Views.card(column, null)
        val summary = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body)
        runCard.content.addView(summary, FoodViews.matchWrap())
        val export = FoodViews.filledButton(ctx, getString(R.string.export_act_button)) { createDoc.launch(vm.target()) }
        runCard.content.addView(export, Views.matchWrap(ctx, 8))
        val progress = LinearProgressIndicator(ctx).apply { max = 1 }
        runCard.content.addView(progress, Views.matchWrap(ctx, 8))
        val progressText = Views.caption(ctx)
        runCard.content.addView(progressText, FoodViews.matchWrap())
        val result = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        runCard.content.addView(result, Views.matchWrap(ctx, 8))

        ui = Ui(format, range, from, to, summary, export, progress, progressText, result)
        collectWhileStarted(vm.state) { render(it) }
    }

    private fun row(parent: ViewGroup, title: String, onClick: () -> Unit): ListRow {
        val r = ListRow(requireContext()).apply {
            this.title = title
            this.onClick = onClick
        }
        parent.addView(r, FoodViews.matchWrap())
        return r
    }

    private fun render(s: ActivityExportUi) {
        val v = ui
        val ctx = requireContext()
        Views.selectSegment(v.format, WorkoutExport.FORMATS.indexOf(s.format).coerceAtLeast(0))
        v.range.trailing = getString(s.range.label)
        val custom = s.range == ExportRange.CUSTOM
        v.from.visibility = if (custom) View.VISIBLE else View.GONE
        v.to.visibility = if (custom) View.VISIBLE else View.GONE
        s.days?.let {
            v.from.trailing = dayText(it.first)
            v.to.trailing = dayText(it.last)
        }
        v.summary.text = when {
            s.total == null -> getString(R.string.export_act_counting)
            s.total == 0 -> getString(R.string.export_act_none)
            s.format == "fit" && s.withFit < s.total ->
                resources.getQuantityString(R.plurals.export_act_count, s.total, s.total) + " " + getString(R.string.export_act_fit_some, s.withFit)
            else -> resources.getQuantityString(R.plurals.export_act_count, s.total, s.total)
        }
        v.export.isEnabled = s.canExport
        v.format.isEnabled = !s.running
        v.progress.visibility = if (s.running) View.VISIBLE else View.GONE
        v.progressText.visibility = v.progress.visibility
        val total = s.total ?: 0
        if (s.running) {
            v.progress.max = total.coerceAtLeast(1)
            v.progress.setProgressCompat(s.done.coerceAtMost(total), true)
            v.progressText.text = getString(R.string.export_act_progress, s.done.coerceAtMost(total).coerceAtLeast(1), total)
        }
        v.result.text = s.result?.let { resultText(ctx, it) }
        v.result.visibility = if (s.result == null) View.GONE else View.VISIBLE
    }

    private fun pickRange() {
        val s = vm.state.value
        if (s.running) return
        val labels = ExportRange.entries.map { getString(it.label) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_act_range)
            .setSingleChoiceItems(labels, s.range.ordinal) { d, which ->
                d.dismiss()
                vm.setRange(ExportRange.entries[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickDay(first: Boolean) {
        if (vm.state.value.running) return
        val current = vm.customDays()
        val d = (if (first) current.first else current.last).toLocalDate()
        DatePickerDialog(requireContext(), { _, y, m, dom ->
            val picked = Day.of(LocalDate.of(y, m + 1, dom))
            if (first) vm.setCustom(picked, current.last) else vm.setCustom(current.first, picked)
        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    private fun onDocument(uri: Uri?) {
        if (uri == null) return
        val app = requireContext().applicationContext
        val name = vm.target().name
        vm.export(OutputSink.ofUri(app.contentResolver, uri, name)) {
            withContext(Dispatchers.IO) {
                try {
                    DocumentsContract.deleteDocument(app.contentResolver, uri)
                } catch (e: FileNotFoundException) {
                    SwLog.w(TAG, "could not remove the empty export", e)
                } catch (e: SecurityException) {
                    SwLog.w(TAG, "could not remove the empty export", e)
                } catch (e: UnsupportedOperationException) {
                    SwLog.w(TAG, "could not remove the empty export", e)
                } catch (e: IllegalArgumentException) {
                    SwLog.w(TAG, "could not remove the empty export", e)
                }
            }
        }
    }

    /** Factory and text helpers. */
    companion object {
        private const val TAG = "ActivityExport"

        /** The result line for [r]. */
        fun resultText(ctx: Context, r: ActivityExportResult): String = when (r) {
            is ActivityExportResult.Ok -> {
                val done = ctx.resources.getQuantityString(R.plurals.export_act_done, r.written, r.written)
                if (r.skipped > 0) done + " " + ctx.resources.getQuantityString(R.plurals.export_act_skipped, r.skipped, r.skipped) else done
            }
            ActivityExportResult.NoFit -> ctx.getString(R.string.export_act_no_fit)
            is ActivityExportResult.Failed -> ctx.getString(R.string.export_act_failed, r.detail)
        }

        private fun dayText(d: Day): String = d.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}
