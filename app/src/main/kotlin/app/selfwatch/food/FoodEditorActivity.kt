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
import app.selfwatch.model.food.Allergen
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
    private var moreNutrients: LinearLayout? = null
    private var moreToggle: com.google.android.material.button.MaterialButton? = null
    private lateinit var veganBox: com.google.android.material.checkbox.MaterialCheckBox
    private lateinit var vegetarianBox: com.google.android.material.checkbox.MaterialCheckBox
    private lateinit var glutenFreeBox: com.google.android.material.checkbox.MaterialCheckBox
    private val allergenChips = LinkedHashMap<Allergen, com.google.android.material.chip.Chip>()
    private lateinit var extraBox: LinearLayout
    private val extraRows = ArrayList<Pair<TextInputEditText, TextInputEditText>>()
    private lateinit var categoryButton: com.google.android.material.button.MaterialButton
    private var categoryId: Int? = null

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
        // "+ Serving size": more named servings with their grams.
        extraBox = FoodViews.column(ctx).apply { tag = "extra_servings" }
        form.addView(extraBox, FoodViews.matchWrap())
        form.addView(FoodViews.textButton(ctx, getString(R.string.editor_add_serving)) { addExtraRow(ServingForm()) }.apply { tag = "add_serving" }, FoodViews.matchWrap())
        add(form, "kcal", R.string.food_calories, DECIMAL)
        pairRow(form, "protein" to R.string.food_protein_g, "carbs" to R.string.food_carbs_g)
        pairRow(form, "fat" to R.string.food_fat_g, "satfat" to R.string.editor_satfat_g)
        pairRow(form, "sugar" to R.string.editor_sugar_g, "fibre" to R.string.editor_fibre_g)
        pairRow(form, "sodium" to R.string.editor_sodium_mg, "cholesterol" to R.string.editor_cholesterol_mg)

        // "More nutrients": the food database v2 fields, folded.
        val more = FoodViews.column(ctx).apply { visibility = android.view.View.GONE; tag = "more_nutrients" }
        pairRow(more, "transfat" to R.string.editor_transfat_g, "addsugar" to R.string.editor_addsugar_g)
        pairRow(more, "monofat" to R.string.editor_monofat_g, "polyfat" to R.string.editor_polyfat_g)
        pairRow(more, "potassium" to R.string.editor_potassium_mg, "calcium" to R.string.editor_calcium_mg)
        pairRow(more, "iron" to R.string.editor_iron_mg, "vita" to R.string.editor_vita_ug)
        pairRow(more, "vitc" to R.string.editor_vitc_mg, "vitd" to R.string.editor_vitd_ug)
        moreNutrients = more
        moreToggle = FoodViews.textButton(ctx, getString(R.string.editor_more_nutrients)) { setMoreOpen(more.visibility != android.view.View.VISIBLE) }
            .apply { tag = "more_toggle" }
        form.addView(moreToggle, gapParams())
        form.addView(more, FoodViews.matchWrap())

        // Diet claims and allergens from the label.
        form.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(R.string.editor_diet)), gapParams())
        form.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(R.string.editor_diet_help)), FoodViews.matchWrap())
        veganBox = check(form, getString(R.string.diet_badge_vegan), "vegan")
        vegetarianBox = check(form, getString(R.string.diet_badge_vegetarian), "vegetarian")
        glutenFreeBox = check(form, getString(R.string.diet_badge_gluten_free), "gluten_free")
        form.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(R.string.editor_contains)), gapParams())
        val allergenGrid = com.google.android.material.chip.ChipGroup(ctx)
        for (a in Allergen.entries) {
            val chip = com.google.android.material.chip.Chip(ctx, null, com.google.android.material.R.attr.chipStyle).apply {
                id = android.view.View.generateViewId()
                this.text = getString(NutrientUi.allergenName(a))
                isCheckable = true
                tag = "allergen_" + a.name.lowercase()
            }
            allergenChips[a] = chip
            allergenGrid.addView(chip)
        }
        form.addView(allergenGrid, FoodViews.matchWrap())
        categoryButton = FoodViews.outlinedButton(ctx, getString(R.string.editor_category_value, getString(R.string.editor_category_none))) { pickCategory() }
            .apply { tag = "category" }
        form.addView(categoryButton, gapParams())
        add(form, "barcode", R.string.editor_barcode, InputType.TYPE_CLASS_NUMBER)
        form.addView(FoodViews.filledButton(ctx, getString(R.string.food_save)) { vm.save(readForm()) }.apply { tag = "save" }, gapParams())
        scroll.addView(form, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setRootContent(root)
        savedInstanceState?.let { restoreExtras(it) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.state.collect { s ->
                        if (!s.loading && !filled && savedInstanceState == null) {
                            filled = true
                            fill(s.form)
                        }
                        if (!s.loading) showCategory()
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

    private fun check(parent: LinearLayout, label: String, key: String): com.google.android.material.checkbox.MaterialCheckBox =
        com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = label
            tag = key
            minHeight = touch()
            parent.addView(this, FoodViews.matchWrap())
        }

    /** One extra serving row: name, grams and a remove button. */
    private fun addExtraRow(s: ServingForm) {
        val ctx: Context = this
        val row = FoodViews.row(ctx).apply { tag = "extra_serving_${extraRows.size}" }
        val (labelLayout, labelEdit) = FoodViews.field(ctx, R.string.editor_extra_serving_label, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val (gramsLayout, gramsEdit) = FoodViews.field(ctx, R.string.editor_serving_grams, DECIMAL)
        labelEdit.setText(s.label)
        gramsEdit.setText(s.grams)
        labelEdit.tag = "extra_label"
        gramsEdit.tag = "extra_grams"
        row.addView(labelLayout, FoodViews.weight(2f))
        row.addView(gramsLayout, FoodViews.weight(1f).apply { marginStart = resources.getDimensionPixelSize(UiR.dimen.sw_space_1) })
        val pair = labelEdit to gramsEdit
        val remove = FoodViews.iconButton(ctx, UiR.drawable.sw_ic_close, getString(R.string.editor_remove_serving, s.label.ifBlank { (extraRows.size + 2).toString() })) {
            extraRows.remove(pair)
            extraBox.removeView(row)
        }
        row.addView(remove)
        extraRows.add(pair)
        extraBox.addView(row, gapParams())
    }

    /** The category list (from foods.db, else the built-in 42), with "None" first. */
    private fun pickCategory() {
        val cats = vm.state.value.categories
        val labels = listOf(getString(R.string.editor_category_none)) + cats.map { it.name }
        val checked = categoryId?.let { id -> cats.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_category)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                categoryId = if (which == 0) null else cats[which - 1].id
                showCategory()
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCategory() {
        val name = categoryId?.let { id -> vm.state.value.categories.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } }
        categoryButton.text = getString(R.string.editor_category_value, name ?: getString(R.string.editor_category_none))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CATEGORY, categoryId ?: 0)
        outState.putStringArrayList(KEY_EXTRA_LABELS, ArrayList(extraRows.map { it.first.text?.toString().orEmpty() }))
        outState.putStringArrayList(KEY_EXTRA_GRAMS, ArrayList(extraRows.map { it.second.text?.toString().orEmpty() }))
    }

    private fun restoreExtras(state: Bundle) {
        categoryId = state.getInt(KEY_CATEGORY, 0).takeIf { it != 0 }
        val labels = state.getStringArrayList(KEY_EXTRA_LABELS).orEmpty()
        val grams = state.getStringArrayList(KEY_EXTRA_GRAMS).orEmpty()
        labels.forEachIndexed { i, l -> addExtraRow(ServingForm(l, grams.getOrNull(i).orEmpty())) }
    }

    private fun setMoreOpen(open: Boolean) {
        moreNutrients?.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
        moreToggle?.text = getString(if (open) R.string.editor_fewer_nutrients else R.string.editor_more_nutrients)
    }

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
        set("transfat", f.transfat)
        set("monofat", f.monofat)
        set("polyfat", f.polyfat)
        set("addsugar", f.addsugar)
        set("potassium", f.potassiumMg)
        set("calcium", f.calciumMg)
        set("iron", f.ironMg)
        set("vita", f.vitaUg)
        set("vitc", f.vitcMg)
        set("vitd", f.vitdUg)
        veganBox.isChecked = f.vegan
        vegetarianBox.isChecked = f.vegetarian
        glutenFreeBox.isChecked = f.glutenFree
        for ((a, chip) in allergenChips) chip.isChecked = a in f.allergens
        categoryId = f.categoryId
        showCategory()
        for (s in f.extraServings) addExtraRow(s)
        // Open the fold when the food already has any of its numbers.
        if (listOf(f.transfat, f.monofat, f.polyfat, f.addsugar, f.potassiumMg, f.calciumMg, f.ironMg, f.vitaUg, f.vitcMg, f.vitdUg).any { it.isNotBlank() }) {
            setMoreOpen(true)
        }
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
        transfat = value("transfat"),
        monofat = value("monofat"),
        polyfat = value("polyfat"),
        addsugar = value("addsugar"),
        potassiumMg = value("potassium"),
        calciumMg = value("calcium"),
        ironMg = value("iron"),
        vitaUg = value("vita"),
        vitcMg = value("vitc"),
        vitdUg = value("vitd"),
        vegan = veganBox.isChecked,
        vegetarian = vegetarianBox.isChecked,
        glutenFree = glutenFreeBox.isChecked,
        allergens = allergenChips.filterValues { it.isChecked }.keys,
        categoryId = categoryId,
        extraServings = extraRows.map { (l, g) -> ServingForm(l.text?.toString().orEmpty(), g.text?.toString().orEmpty()) },
    )

    private fun showErrors(errors: Set<FoodFormError>) {
        fields["name"]?.first?.error = if (FoodFormError.NAME in errors) getString(R.string.editor_name_needed) else null
        fields["kcal"]?.first?.error = if (FoodFormError.KCAL in errors) getString(R.string.food_calories_needed) else null
        fields["grams"]?.first?.error = if (FoodFormError.SERVING_GRAMS in errors) getString(R.string.editor_bad_number) else null
        fields["barcode"]?.first?.error = if (FoodFormError.BARCODE in errors) getString(R.string.scan_bad_code) else null
        for ((l, g) in extraRows) {
            val bad = FoodFormError.EXTRA_SERVING in errors && !(l.text.isNullOrBlank() && g.text.isNullOrBlank()) &&
                (l.text.isNullOrBlank() || g.text.isNullOrBlank())
            (g.parent?.parent as? TextInputLayout)?.error = if (bad) getString(R.string.editor_serving_needs_both) else null
        }
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
        private const val KEY_CATEGORY = "category"
        private const val KEY_EXTRA_LABELS = "extra_labels"
        private const val KEY_EXTRA_GRAMS = "extra_grams"
        private const val DECIMAL = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

        /** A new custom food, with [barcode] filled in when known. */
        fun newIntent(ctx: Context, barcode: Long?): Intent =
            Intent(ctx, FoodEditorActivity::class.java).putExtra(EXTRA_BARCODE, barcode ?: 0L)

        /** Edits custom food [ref], or starts a custom copy of any other food. */
        fun intent(ctx: Context, ref: FoodRef): Intent =
            Intent(ctx, FoodEditorActivity::class.java).putExtra(EXTRA_ORIGIN, ref.origin.code).putExtra(EXTRA_ID, ref.id)
    }
}
