/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.data

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.db.dao.ImportBatch
import app.selfwatch.food.FoodViews
import app.selfwatch.graph
import app.selfwatch.interop.api.ImportPlan
import app.selfwatch.interop.api.WeightUnit
import app.selfwatch.me.MeScreenActivity
import app.selfwatch.me.MeViews
import app.selfwatch.me.SwitchRow
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Import (C18, DESIGN §7.1): choose a file, see who wrote it, what it holds (counts, duplicates,
 * warnings and the first rows), check or change the detected date format and weight unit, then
 * import or cancel. Below: every past import with a one-step undo.
 */
class ImportActivity : MeScreenActivity() {

    private val recipesOnly: Boolean get() = intent.getBooleanExtra(EXTRA_RECIPES, false)

    private val vm: ImportViewModel by viewModels { ImportViewModel.factory(graph, recipesOnly) }

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(Saf.input(this, uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen(if (recipesOnly) R.string.menu_import_recipes else R.string.import_title)

        val intro = MeViews.card(column, getString(R.string.import_intro_title))
        MeViews.add(intro.content, MeViews.body(this, getString(if (recipesOnly) R.string.import_recipes_intro else R.string.import_intro)), gap = false)
        val formats = if (recipesOnly) {
            getString(R.string.import_recipes_formats, ImportViewModel.RECIPE_IDS.joinToString(", ") { importerLabel(this, it) })
        } else {
            getString(R.string.import_formats)
        }
        MeViews.add(intro.content, MeViews.caption(this, formats))
        val choose = FoodViews.filledButton(this, getString(R.string.import_choose)) { pick.launch(arrayOf("*/*")) }
        MeViews.add(intro.content, choose)

        val previewCard = MeViews.card(column, null)
        val preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previewCard.content.addView(preview, FoodViews.matchWrap())

        val historyCard = MeViews.card(column, getString(R.string.import_history))
        val history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyCard.content.addView(history, FoodViews.matchWrap())

        collect(vm.state) { s ->
            intro.visibility = if (s.stage == ImportStage.IDLE) View.VISIBLE else View.GONE
            previewCard.visibility = if (s.stage == ImportStage.IDLE) View.GONE else View.VISIBLE
            preview.removeAllViews()
            previewCard.title = s.fileName
            renderStage(preview, s)
            history.removeAllViews()
            if (s.history.isEmpty()) {
                MeViews.add(history, MeViews.caption(this, getString(R.string.import_history_empty)), gap = false)
            } else {
                for (b in s.history) history.addView(historyRow(b), FoodViews.matchWrap())
            }
            s.undoneRows?.let {
                toast(resources.getQuantityString(R.plurals.import_undone, it, it))
                vm.undoShown()
            }
        }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_PICK, false)) pick.launch(arrayOf("*/*"))
    }

    private fun renderStage(box: LinearLayout, s: ImportUiState) {
        when (s.stage) {
            ImportStage.IDLE -> Unit
            ImportStage.READING, ImportStage.SAVING -> {
                MeViews.add(box, MeViews.body(this, getString(if (s.stage == ImportStage.READING) R.string.import_reading else R.string.import_saving)), gap = false)
                MeViews.add(box, LinearProgressIndicator(this).apply { isIndeterminate = true })
            }
            ImportStage.PREVIEW -> s.plan?.let { renderPreview(box, s, it) }
            ImportStage.DONE -> {
                val r = s.result
                val n = r?.counts?.values?.sum() ?: 0
                MeViews.add(box, MeViews.body(this, resources.getQuantityString(R.plurals.import_done, n, n)), gap = false)
                r?.counts?.forEach { (k, v) -> MeViews.add(box, MeViews.caption(this, countLine(k, v))) }
                if (r != null && r.importId > 0) {
                    MeViews.add(box, FoodViews.outlinedButton(this, getString(R.string.import_undo)) { confirmUndo(r.importId) })
                }
                MeViews.add(box, FoodViews.textButton(this, getString(R.string.import_another)) { vm.cancel() })
            }
            ImportStage.BACKUP_FILE -> {
                MeViews.add(box, MeViews.body(this, getString(R.string.import_backup_file)), gap = false)
                MeViews.add(box, FoodViews.filledButton(this, getString(R.string.import_open_data)) { startActivity(Intent(this, DataActivity::class.java)) })
                MeViews.add(box, FoodViews.textButton(this, getString(android.R.string.cancel)) { vm.cancel() })
            }
            ImportStage.FAILED -> {
                val text = if (s.error == null) getString(R.string.import_unknown) else getString(R.string.import_failed, s.error)
                MeViews.add(box, MeViews.body(this, text), gap = false)
                MeViews.add(box, FoodViews.filledButton(this, getString(R.string.import_choose)) { pick.launch(arrayOf("*/*")) })
                MeViews.add(box, FoodViews.textButton(this, getString(android.R.string.cancel)) { vm.cancel() })
            }
        }
    }

    private fun renderPreview(box: LinearLayout, s: ImportUiState, plan: ImportPlan) {
        val choices = s.choices ?: plan.defaultChoices()
        MeViews.row(box, getString(R.string.import_read_as), null, if (s.candidates.size > 1) ({ chooseImporter(s) }) else null).trailing =
            importerLabel(this, plan.importerId)

        val total = plan.counts.values.sum()
        MeViews.add(box, MeViews.body(this, resources.getQuantityString(R.plurals.import_found, total, total)))
        plan.counts.forEach { (k, v) -> MeViews.add(box, MeViews.caption(this, countLine(k, v)), gap = false) }

        if (plan.dateFormat.candidates.isNotEmpty() || plan.dateFormat.best != null) {
            val dateRow = MeViews.row(box, getString(R.string.import_date_format), dateHelp(plan), if (plan.dateFormat.candidates.size > 1) ({ chooseDate(plan, choices.dateFormat) }) else null)
            dateRow.trailing = dateLabel(choices.dateFormat)
        }
        if (plan.units.unit.isNotEmpty()) {
            val unitRow = MeViews.row(box, getString(R.string.import_weight_unit), unitHelp(plan)) {
                val units = listOf(WeightUnit.KG, WeightUnit.LB)
                MeViews.choose(this, getString(R.string.import_weight_unit), units.map { unitLabel(it) }, units.indexOf(choices.weightUnit)) {
                    vm.setWeightUnit(units[it])
                }
            }
            unitRow.trailing = unitLabel(choices.weightUnit)
        }
        if (plan.duplicates > 0) {
            val dup = SwitchRow(this, getString(R.string.import_skip_duplicates), resources.getQuantityString(R.plurals.import_duplicates, plan.duplicates, plan.duplicates))
            dup.checked = choices.skipDuplicates
            dup.onChange = { vm.setSkipDuplicates(it) }
            MeViews.add(box, dup)
        }
        if (plan.warnings.isNotEmpty()) {
            MeViews.add(box, MeViews.heading(this, getString(R.string.import_warnings)))
            for (w in plan.warnings.take(MAX_WARNINGS)) MeViews.add(box, MeViews.caption(this, "• $w"), gap = false)
        }
        if (plan.preview.isNotEmpty()) {
            MeViews.add(box, MeViews.heading(this, getString(R.string.import_first_rows)))
            val table = MeViews.caption(this, plan.preview.take(PREVIEW_ROWS).joinToString("\n") { row -> row.joinToString("  |  ") { it.take(CELL_CHARS) } }).apply {
                typeface = Typeface.MONOSPACE
                setHorizontallyScrolling(true)
            }
            MeViews.add(box, HorizontalScrollView(this).apply { addView(table) }, gap = false)
        }
        val buttons = FoodViews.row(this)
        buttons.addView(FoodViews.textButton(this, getString(android.R.string.cancel)) { vm.cancel() }, FoodViews.weight())
        buttons.addView(FoodViews.filledButton(this, getString(R.string.import_confirm)) { vm.confirm() }, FoodViews.weight())
        MeViews.add(box, buttons)
    }

    private fun chooseImporter(s: ImportUiState) {
        MeViews.choose(this, getString(R.string.import_read_as), s.candidates.map { importerLabel(this, it) }, s.candidates.indexOf(s.importerId)) {
            vm.useImporter(s.candidates[it])
        }
    }

    private fun chooseDate(plan: ImportPlan, current: String) {
        val ids = plan.dateFormat.candidates
        MeViews.choose(this, getString(R.string.import_date_format), ids.map { dateLabel(it) }, ids.indexOf(current)) { vm.setDateFormat(ids[it]) }
    }

    private fun dateHelp(plan: ImportPlan): String? {
        val g = plan.dateFormat
        if (!g.ambiguous) return getString(R.string.import_date_detected)
        val sample = g.ambiguousSamples.firstOrNull() ?: return getString(R.string.import_date_ambiguous)
        return getString(R.string.import_date_ambiguous_sample, sample)
    }

    private fun unitHelp(plan: ImportPlan): String =
        getString(if (plan.units.confident) R.string.import_unit_detected else R.string.import_unit_guess)

    private fun dateLabel(id: String): String = when (id) {
        "yyyy-MM-dd" -> getString(R.string.import_date_iso)
        "MM/dd/yyyy" -> getString(R.string.import_date_us)
        "dd/MM/yyyy" -> getString(R.string.import_date_eu)
        "dd.MM.yyyy" -> getString(R.string.import_date_dotted)
        else -> id
    }

    private fun unitLabel(unit: String): String = getString(if (unit == WeightUnit.LB) R.string.import_unit_lb else R.string.import_unit_kg)

    private fun countLine(key: String, n: Number): String = getString(R.string.import_count_line, countLabel(key), n.toString())

    private fun countLabel(key: String): String = countRes(key)?.let { getString(it) } ?: key

    private fun historyRow(b: ImportBatch) = app.selfwatch.ui.components.ListRow(this).apply {
        title = getString(R.string.import_history_row, importerLabel(this@ImportActivity, b.importer), b.fileName ?: "")
        val n = b.counts.values.sum().toInt()
        subtitle = getString(R.string.import_history_detail, whenText(b.tSec), resources.getQuantityString(R.plurals.import_items, n, n))
        if (b.undone) {
            trailing = getString(R.string.import_undone_label)
        } else {
            trailing = getString(R.string.import_undo)
            onClick = { confirmUndo(b.id) }
            contentDescription = getString(R.string.import_undo_description, title)
        }
    }

    private fun confirmUndo(id: Long) {
        MeViews.confirm(this, getString(R.string.import_undo_title), getString(R.string.import_undo_message), getString(R.string.import_undo)) { vm.undo(id) }
    }

    private fun whenText(tSec: Long): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochSecond(tSec).atZone(ZoneId.systemDefault()))

    /** Intents and labels. */
    companion object {
        private const val EXTRA_PICK = "pick"
        private const val EXTRA_RECIPES = "recipes_only"
        private const val MAX_WARNINGS = 10
        private const val PREVIEW_ROWS = 6
        private const val CELL_CHARS = 24

        /**
         * Opens the import screen and, when [pick] is true, the file picker straight away. With
         * [recipesOnly] (the recipe book's "Import recipes") only recipe files are read and listed.
         */
        fun intent(ctx: Context, pick: Boolean = false, recipesOnly: Boolean = false): Intent =
            Intent(ctx, ImportActivity::class.java).putExtra(EXTRA_PICK, pick).putExtra(EXTRA_RECIPES, recipesOnly)

        /** A readable name for importer [id]. */
        fun importerLabel(ctx: Context, id: String): String = importerRes(id)?.let { ctx.getString(it) } ?: id

        @StringRes
        private fun importerRes(id: String): Int? = when (id) {
            "mfp" -> R.string.importer_mfp
            "loseit" -> R.string.importer_loseit
            "cronometer" -> R.string.importer_cronometer
            "fit" -> R.string.importer_fit
            "gpx" -> R.string.importer_gpx
            "tcx" -> R.string.importer_tcx
            "foodlist-csv" -> R.string.importer_foodlist
            "gadgetbridge" -> R.string.importer_gadgetbridge
            "garmin-export" -> R.string.importer_garmin
            "selfwatch-backup" -> R.string.importer_backup
            "recipe-jsonld" -> R.string.importer_recipe_jsonld
            "paprika" -> R.string.importer_paprika
            "mealie" -> R.string.importer_mealie
            "tandoor" -> R.string.importer_tandoor
            "mealmaster" -> R.string.importer_mealmaster
            "cooklang" -> R.string.importer_cooklang
            "health-connect" -> R.string.importer_hc
            else -> null
        }

        @StringRes
        private fun countRes(key: String): Int? = when (key) {
            "diary", "food_log", "servings" -> R.string.count_diary
            "body", "weight", "biometrics" -> R.string.count_body
            "exercise" -> R.string.count_exercise
            "note" -> R.string.count_note
            "workout" -> R.string.count_workout
            "recipe" -> R.string.count_recipe
            "food" -> R.string.count_food
            "water" -> R.string.count_water
            "activity" -> R.string.count_activity
            "metric" -> R.string.count_metric
            "sleep_stage", "sleep_session" -> R.string.count_sleep
            else -> null
        }
    }
}
