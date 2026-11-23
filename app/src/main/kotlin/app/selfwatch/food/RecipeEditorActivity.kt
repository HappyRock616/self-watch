/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import app.selfwatch.shell.MaxWidthLinearLayout
import app.selfwatch.ui.SwActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import app.selfwatch.ui.R as UiR

/**
 * Recipe editor (WP-10 `RecipeEditorActivity`): name, servings, ingredient lines (one per line),
 * steps and tags; save (new lines get suggested matches) and delete.
 */
class RecipeEditorActivity : SwActivity() {

    private val recipeId: Long by lazy { intent.getLongExtra(EXTRA_ID, 0L) }

    private val vm: RecipeEditorViewModel by viewModels {
        val g = graph
        val id = recipeId
        viewModelFactory { initializer { RecipeEditorViewModel(id) { GraphFoodBackend(g.food) } } }
    }

    private lateinit var name: Pair<TextInputLayout, TextInputEditText>
    private lateinit var servings: Pair<TextInputLayout, TextInputEditText>
    private lateinit var ingredients: Pair<TextInputLayout, TextInputEditText>
    private lateinit var steps: Pair<TextInputLayout, TextInputEditText>
    private lateinit var tags: Pair<TextInputLayout, TextInputEditText>
    private var filled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx: Context = this
        val root = FoodViews.column(ctx)
        val bar = MaterialToolbar(ctx).apply {
            title = getString(if (recipeId != 0L) R.string.recipe_edit else R.string.recipe_create)
            setNavigationIcon(UiR.drawable.sw_ic_close)
            navigationContentDescription = getString(UiR.string.sw_close)
            setNavigationOnClickListener { finish() }
            if (recipeId != 0L) menu.add(0, MENU_DELETE, 0, R.string.food_delete)
            setOnMenuItemClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setMessage(R.string.recipe_delete_confirm)
                    .setPositiveButton(R.string.food_delete) { _, _ -> vm.delete() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
        root.addView(bar, FoodViews.matchWrap())
        val pad = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        val form = MaxWidthLinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(pad, 0, pad, pad)
        }
        val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val multi = text or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        name = add(form, R.string.recipe_name, text, "name")
        servings = add(form, R.string.recipe_servings, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "servings")
        ingredients = add(form, R.string.recipe_ingredients_hint, multi, "ingredients")
        ingredients.second.minLines = INGREDIENT_LINES
        steps = add(form, R.string.recipe_steps, multi, "steps")
        steps.second.minLines = STEP_LINES
        tags = add(form, R.string.recipe_tags_hint, InputType.TYPE_CLASS_TEXT, "tags")
        form.addView(
            FoodViews.filledButton(ctx, getString(R.string.food_save)) { vm.save(read()) }.apply { tag = "save" },
            FoodViews.matchWrap().apply { topMargin = pad / 2 },
        )
        val scroll = NestedScrollView(ctx)
        scroll.addView(form, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setRootContent(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.state.collect { s ->
                        if (!s.loading && !filled && savedInstanceState == null) {
                            filled = true
                            name.second.setText(s.form.name)
                            servings.second.setText(s.form.servings)
                            ingredients.second.setText(s.form.ingredients)
                            steps.second.setText(s.form.steps)
                            tags.second.setText(s.form.tags)
                        }
                    }
                }
                launch {
                    vm.events.collect { e ->
                        when (e) {
                            is RecipeEditorEvent.Saved -> {
                                if (recipeId == 0L) startActivity(RecipeActivity.detailIntent(ctx, e.id))
                                finish()
                            }
                            RecipeEditorEvent.Deleted -> {
                                startActivity(RecipeActivity.bookIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                                finish()
                            }
                            RecipeEditorEvent.NameNeeded -> name.first.error = getString(R.string.editor_name_needed)
                            RecipeEditorEvent.Failed -> Toast.makeText(ctx, R.string.food_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun add(parent: LinearLayout, hint: Int, type: Int, key: String): Pair<TextInputLayout, TextInputEditText> {
        val pair = FoodViews.field(this, hint, type)
        pair.second.tag = key
        parent.addView(pair.first, FoodViews.matchWrap().apply { topMargin = resources.getDimensionPixelSize(UiR.dimen.sw_space_1) })
        return pair
    }

    private fun read(): RecipeForm = RecipeForm(
        name = name.second.text?.toString().orEmpty(),
        servings = servings.second.text?.toString().orEmpty(),
        ingredients = ingredients.second.text?.toString().orEmpty(),
        steps = steps.second.text?.toString().orEmpty(),
        tags = tags.second.text?.toString().orEmpty(),
    )

    /** Intents. */
    companion object {
        private const val EXTRA_ID = "recipe_id"
        private const val MENU_DELETE = 1
        private const val INGREDIENT_LINES = 5
        private const val STEP_LINES = 3

        /** Edits recipe [id], or creates one when [id] is 0. */
        fun intent(ctx: Context, id: Long): Intent = Intent(ctx, RecipeEditorActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
