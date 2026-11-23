/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.shell.MaxWidthLinearLayout
import app.selfwatch.ui.SwActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import app.selfwatch.ui.R as UiR

/**
 * Custom food editor (WP-10 `FoodEditorActivity`): name, brand, serving, nutrients and barcode; save
 * and delete. Returns the saved food's id as [RESULT_FOOD_ID].
 */
class FoodEditorActivity : SwActivity() {

    private val vm: FoodEditorViewModel by viewModels {
        val g = graph
        val origin = FoodOrigin.fromCode(intent.getIntExtra(EXTRA_ORIGIN, 0))
        val ref = origin?.let { FoodRef(it, intent.getLongExtra(EXTRA_ID, 0L)) }
        val code = intent.getLongExtra(EXTRA_BARCODE, 0L).takeIf { it != 0L }
        viewModelFactory { initializer { FoodEditorViewModel(ref, code) { GraphFoodBackend(g.food) } } }
    }

    private val fields = HashMap<String, Pair<TextInputLayout, TextInputEditText>>()
    private lateinit var perServing: RadioButton
    private lateinit var per100: RadioButton
    private lateinit var toolbar: MaterialToolbar
    private var filled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx: Context = this
        val root = FoodViews.column(ctx)
        toolbar = MaterialToolbar(ctx).apply {
            title = getString(R.string.editor_title_new)
            setNavigationIcon(UiR.drawable.sw_ic_close)
            navigationContentDescription = getString(UiR.string.sw_close)
            setNavigationOnClickListener { finish() }
            menu.add(0, MENU_DELETE, 0, R.string.food_delete).isVisible = false
            setOnMenuItemClickListener {
                confirmDelete()
                true
            }
        }
        root.addView(toolbar, FoodViews.matchWrap())
        val scroll = NestedScrollView(ctx)
        val pad = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        val form = MaxWidthLinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(pad, 0, pad, pad)
        }
        val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        add(form, "name", R.string.editor_name, text)
        add(form, "brand", R.string.editor_brand, text)
        val basis = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
        perServing = RadioButton(ctx).apply { id = android.view.View.generateViewId(); setText(R.string.editor_per_serving); minHeight = touch() }
        per100 = RadioButton(ctx).apply { id = android.view.View.generateViewId(); setText(R.string.editor_per_100g); minHeight = touch() }
        basis.addView(perServing)
        basis.addView(per100)
        perServing.isChecked = true
        form.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(R.string.editor_nutrients_per)), gapParams())
        form.addView(basis, FoodViews.matchWrap())
        val serving = FoodViews.row(ctx)
        add(serving, "serving", R.string.editor_serving_label, text, FoodViews.weight(2f))
        add(serving, "grams", R.string.editor_serving_grams, DECIMAL, FoodViews.weight(1f).apply { marginStart = pad / 2 })
        form.addView(serving, gapParams())
        add(form, "kcal", R.string.food_calories, DECIMAL)
        pairRow(form, "protein" to R.string.food_protein_g, "carbs" to R.string.food_carbs_g)
        pairRow(form, "fat" to R.string.food_fat_g, "satfat" to R.string.editor_satfat_g)
        pairRow(form, "sugar" to R.string.editor_sugar_g, "fibre" to R.string.editor_fibre_g)
        pairRow(form, "sodium" to R.string.editor_sodium_mg, "cholesterol" to R.string.editor_cholesterol_mg)
        add(form, "barcode", R.string.editor_barcode, InputType.TYPE_CLASS_NUMBER)
        form.addView(FoodViews.filledButton(ctx, getString(R.string.food_save)) { vm.save(readForm()) }.apply { tag = "save" }, gapParams())
        scroll.addView(form, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setRootContent(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.state.collect { s ->
                        if (!s.loading && !filled && savedInstanceState == null) {
                            filled = true
                            fill(s.form)
                        }
                        toolbar.title = getString(if (s.id != 0L) R.string.editor_title_edit else R.string.editor_title_new)
                        toolbar.menu.findItem(MENU_DELETE)?.isVisible = s.id != 0L
                    }
                }
                launch {
                    vm.events.collect { e ->
                        when (e) {
                            is FoodEditorEvent.Saved -> {
                                setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_FOOD_ID, e.id))
                                finish()
                            }
                            FoodEditorEvent.Deleted -> finish()
                            is FoodEditorEvent.Invalid -> showErrors(e.errors)
                            FoodEditorEvent.Failed -> Toast.makeText(ctx, R.string.food_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun touch(): Int = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)

    private fun gapParams(): LinearLayout.LayoutParams = FoodViews.matchWrap().apply {
        topMargin = resources.getDimensionPixelSize(UiR.dimen.sw_space_1)
    }

    private fun add(parent: LinearLayout, key: String, hint: Int, type: Int, lp: LinearLayout.LayoutParams = gapParams()) {
        val pair = FoodViews.field(this, hint, type)
        pair.second.tag = key
        fields[key] = pair
        parent.addView(pair.first, lp)
    }

    private fun pairRow(parent: LinearLayout, a: Pair<String, Int>, b: Pair<String, Int>) {
        val row = FoodViews.row(this)
        add(row, a.first, a.second, DECIMAL, FoodViews.weight())
        add(row, b.first, b.second, DECIMAL, FoodViews.weight().apply { marginStart = resources.getDimensionPixelSize(UiR.dimen.sw_space_1) })
        parent.addView(row, gapParams())
    }

    private fun value(key: String): String = fields[key]?.second?.text?.toString().orEmpty()

    private fun set(key: String, v: String) {
        fields[key]?.second?.setText(v)
    }

    private fun fill(f: FoodForm) {
        set("name", f.name)
        set("brand", f.brand)
        set("serving", f.servingLabel)
        set("grams", f.servingGrams)
        set("kcal", f.kcal)
        set("protein", f.protein)
        set("fat", f.fat)
        set("satfat", f.satfat)
        set("carbs", f.carbs)
        set("sugar", f.sugar)
        set("fibre", f.fibre)
        set("sodium", f.sodiumMg)
        set("cholesterol", f.cholesterolMg)
        set("barcode", f.barcode)
        if (f.perServing) perServing.isChecked = true else per100.isChecked = true
    }

    private fun readForm(): FoodForm = FoodForm(
        name = value("name"),
        brand = value("brand"),
        perServing = perServing.isChecked,
        servingLabel = value("serving"),
        servingGrams = value("grams"),
        kcal = value("kcal"),
        protein = value("protein"),
        fat = value("fat"),
        satfat = value("satfat"),
        carbs = value("carbs"),
        sugar = value("sugar"),
        fibre = value("fibre"),
        sodiumMg = value("sodium"),
        cholesterolMg = value("cholesterol"),
        barcode = value("barcode"),
    )

    private fun showErrors(errors: Set<FoodFormError>) {
        fields["name"]?.first?.error = if (FoodFormError.NAME in errors) getString(R.string.editor_name_needed) else null
        fields["kcal"]?.first?.error = if (FoodFormError.KCAL in errors) getString(R.string.food_calories_needed) else null
        fields["grams"]?.first?.error = if (FoodFormError.SERVING_GRAMS in errors) getString(R.string.editor_bad_number) else null
        fields["barcode"]?.first?.error = if (FoodFormError.BARCODE in errors) getString(R.string.scan_bad_code) else null
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.editor_delete_confirm)
            .setPositiveButton(R.string.food_delete) { _, _ -> vm.delete() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Intents and result keys. */
    companion object {
        /** Result extra: the saved custom food id (Long). */
        const val RESULT_FOOD_ID: String = "food_id"
        private const val EXTRA_ORIGIN = "origin"
        private const val EXTRA_ID = "id"
        private const val EXTRA_BARCODE = "barcode"
        private const val MENU_DELETE = 1
        private const val DECIMAL = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        /** A new custom food, with [barcode] filled in when known. */
        fun newIntent(ctx: Context, barcode: Long?): Intent =
            Intent(ctx, FoodEditorActivity::class.java).putExtra(EXTRA_BARCODE, barcode ?: 0L)

        /** Edits custom food [ref], or starts a custom copy of any other food. */
        fun intent(ctx: Context, ref: FoodRef): Intent =
            Intent(ctx, FoodEditorActivity::class.java).putExtra(EXTRA_ORIGIN, ref.origin.code).putExtra(EXTRA_ID, ref.id)
    }
}
