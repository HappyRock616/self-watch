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
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.selfwatch.R
import app.selfwatch.data.CreateFile
import app.selfwatch.data.ImportActivity
import app.selfwatch.data.Saf
import app.selfwatch.food.recipes.IngredientParser
import app.selfwatch.food.recipes.NutritionSource
import app.selfwatch.food.source.FoodHit
import app.selfwatch.graph
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.RecipeKind
import app.selfwatch.shell.MaxWidthLinearLayout
import app.selfwatch.ui.SwActivity
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.NumberStepper
import app.selfwatch.ui.components.SourceTag
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.components.SwSheet
import app.selfwatch.ui.components.SwSnackbar
import app.selfwatch.ui.format.Fmt
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import app.selfwatch.ui.R as UiR

/** Hosts the recipe book and recipe details (fragments on a back stack). */
class RecipeActivity : SwActivity(), FoodLoggedListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).apply { id = R.id.recipe_container }
        setRootContent(container)
        if (savedInstanceState == null) {
            val id = intent.getLongExtra(EXTRA_ID, 0L)
            val first: Fragment = if (id != 0L) RecipeDetailFragment.newInstance(id) else RecipeBookFragment()
            supportFragmentManager.beginTransaction().add(R.id.recipe_container, first).commitNow()
        }
    }

    /** Opens recipe [id] over the book. */
    fun openRecipe(id: Long) {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.recipe_container, RecipeDetailFragment.newInstance(id))
            .addToBackStack(null)
            .commit()
    }

    override fun onFoodLogged(name: String, entryId: Long) {
        SwSnackbar.show(findViewById(R.id.recipe_container), getString(R.string.food_added, name))
    }

    /** Intents. */
    companion object {
        private const val EXTRA_ID = "recipe_id"

        /** The recipe book. */
        fun bookIntent(ctx: Context): Intent = Intent(ctx, RecipeActivity::class.java)

        /** Recipe [id]'s detail. */
        fun detailIntent(ctx: Context, id: Long): Intent = Intent(ctx, RecipeActivity::class.java).putExtra(EXTRA_ID, id)
    }
}

/** Shared bits of the recipe screens. */
internal abstract class RecipeScreen : Fragment() {
    /** Collects [flow] while the view is started. */
    protected fun <T> collect(flow: Flow<T>, block: (T) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collect { block(it) } }
        }
    }

    /** A toolbar with a back arrow that pops or finishes. */
    protected fun toolbar(title: CharSequence): MaterialToolbar = MaterialToolbar(requireContext()).apply {
        this.title = title
        setNavigationIcon(R.drawable.ic_day_prev)
        navigationContentDescription = getString(R.string.recipe_back)
        setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
    }
}

/** Recipe book (WP-10 `RecipeBookFragment`): search, tag filter, open, create. */
internal class RecipeBookFragment : RecipeScreen() {

    private val vm: RecipeBookViewModel by viewModels {
        val g = requireContext().graph
        viewModelFactory { initializer { RecipeBookViewModel { GraphFoodBackend(g.food) } } }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val pad = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        val frame = FrameLayout(ctx)
        val root = FoodViews.column(ctx)
        val bar = toolbar(getString(R.string.menu_recipe_book)).apply {
            inflateMenu(R.menu.menu_recipe_book)
            setOnMenuItemClickListener { item -> onMenuItem(item.itemId) }
        }
        root.addView(bar, FoodViews.matchWrap())
        val (searchL, search) = FoodViews.field(ctx, R.string.recipe_search_hint)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = vm.setQuery(s?.toString().orEmpty())
        })
        root.addView(searchL, FoodViews.matchWrap().apply { marginStart = pad; marginEnd = pad })
        val tags = ChipGroup(ctx).apply {
            isSingleSelection = true
            tag = "tags"
            setPaddingRelative(pad, 0, pad, 0)
        }
        root.addView(tags, FoodViews.matchWrap())
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            setPaddingRelative(pad, 0, pad, resources.getDimensionPixelSize(R.dimen.tab_bottom_space))
            clipToPadding = false
        }
        val listFrame = FrameLayout(ctx)
        listFrame.addView(list)
        val empty = EmptyState(ctx).apply {
            visibility = View.GONE
            buttonText = getString(R.string.recipe_create)
            onButton = { startActivity(RecipeEditorActivity.intent(ctx, 0L)) }
        }
        listFrame.addView(empty)
        root.addView(listFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(root)
        val fab = FloatingActionButton(ctx).apply {
            setImageResource(UiR.drawable.sw_ic_plus)
            contentDescription = getString(R.string.recipe_create)
            setOnClickListener { startActivity(RecipeEditorActivity.intent(ctx, 0L)) }
        }
        frame.addView(
            fab,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
                setMargins(pad, pad, pad, pad)
            },
        )
        val adapter = RecipeAdapter { (requireActivity() as RecipeActivity).openRecipe(it) }
        list.adapter = adapter
        render = { s ->
            adapter.submitList(s.recipes)
            FoodViews.show(empty, !s.loading && s.recipes.isEmpty())
            empty.message = getString(if (s.query.isNotEmpty() || s.tag != null) R.string.food_no_results else R.string.recipe_empty)
            renderTags(tags, s)
        }
        return frame
    }

    private var render: (RecipeBookState) -> Unit = {}

    private val createExport = registerForActivityResult(CreateFile()) { uri ->
        if (uri != null) vm.export(Saf.output(requireContext(), uri))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collect(vm.state) { render(it) }
        collect(vm.events) { e ->
            val text = when (e) {
                is RecipeBookEvent.Exported -> resources.getQuantityString(R.plurals.recipe_exported, e.count, e.count)
                RecipeBookEvent.Failed -> getString(R.string.food_failed)
            }
            SwSnackbar.show(view, text)
        }
    }

    /** The book's menu: import, export, tags. */
    private fun onMenuItem(id: Int): Boolean {
        val ctx = requireContext()
        when (id) {
            R.id.menu_import_recipes -> startActivity(ImportActivity.intent(ctx, pick = true, recipesOnly = true))
            R.id.menu_export_recipes -> {
                if (vm.state.value.recipes.isEmpty() && vm.state.value.tag == null && vm.state.value.query.isEmpty()) {
                    SwSnackbar.show(requireView(), getString(R.string.recipe_export_empty))
                } else {
                    createExport.launch(CreateFile.Request(vm.exportFileName(), vm.exportMimeType))
                }
            }
            R.id.menu_tags -> pickTag()
            else -> return false
        }
        return true
    }

    /** "Tags": show only the recipes with one tag (or all). */
    private fun pickTag() {
        val s = vm.state.value
        if (s.tags.isEmpty()) {
            SwSnackbar.show(requireView(), getString(R.string.recipe_no_tags))
            return
        }
        val options = listOf<String?>(null) + s.tags
        val labels = options.map { it ?: getString(R.string.recipe_all) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.menu_tags)
            .setSingleChoiceItems(labels, options.indexOf(s.tag).coerceAtLeast(0)) { d, which ->
                vm.selectTag(options[which])
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderTags(group: ChipGroup, s: RecipeBookState) {
        val wanted = listOf<String?>(null) + s.tags
        val have = (0 until group.childCount).map { (group.getChildAt(it) as Chip).tag as String? }
        if (have != wanted) {
            group.removeAllViews()
            for (t in wanted) {
                group.addView(
                    Chip(requireContext()).apply {
                        id = View.generateViewId()
                        tag = t
                        text = t ?: getString(R.string.recipe_all)
                        isCheckable = true
                        setOnClickListener { vm.selectTag(t) }
                    },
                )
            }
        }
        for (i in 0 until group.childCount) (group.getChildAt(i) as Chip).let { it.isChecked = it.tag == s.tag }
    }

    private class RecipeAdapter(private val onOpen: (Long) -> Unit) :
        androidx.recyclerview.widget.ListAdapter<app.selfwatch.food.recipes.RecipeSummary, RecipeAdapter.Holder>(Diff) {
        class Holder(val row: ListRow, val tag: SourceTag) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = ListRow(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tag = SourceTag(parent.context).apply { style = SourceTag.Style.MEAL }
            row.addTitleAccessory(tag)
            return Holder(row, tag)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = getItem(position)
            val ctx = holder.row.context
            holder.row.title = r.name
            holder.tag.text = ctx.getString(if (r.kind == RecipeKind.SAVED_MEAL) R.string.tag_meal else R.string.tag_recipe)
            val servings = ctx.getString(R.string.recipe_servings_short, Fmt.x100(r.servingsX10 * 10L, 1))
            holder.row.subtitle = (listOf(servings) + r.tags.sorted()).joinToString(" · ")
            holder.row.trailing = r.kcalX10PerServing?.let { ctx.getString(R.string.recipe_kcal_per_serving, Fmt.number(FoodRows.kcalOfX10(it).toLong())) }
            holder.row.onClick = { onOpen(r.id) }
        }

        private object Diff : androidx.recyclerview.widget.DiffUtil.ItemCallback<app.selfwatch.food.recipes.RecipeSummary>() {
            override fun areItemsTheSame(a: app.selfwatch.food.recipes.RecipeSummary, b: app.selfwatch.food.recipes.RecipeSummary) = a.id == b.id
            override fun areContentsTheSame(a: app.selfwatch.food.recipes.RecipeSummary, b: app.selfwatch.food.recipes.RecipeSummary) = a == b
        }
    }
}

/** Recipe detail (WP-10 `RecipeDetailFragment`): nutrition per serving, scaler, ingredients, steps. */
internal class RecipeDetailFragment : RecipeScreen() {

    private val recipeId: Long by lazy { requireArguments().getLong(ARG_ID) }

    private val vm: RecipeDetailViewModel by viewModels {
        val g = requireContext().graph
        val id = recipeId
        viewModelFactory { initializer { RecipeDetailViewModel(id) { GraphFoodBackend(g.food) } } }
    }

    private lateinit var body: LinearLayout
    private lateinit var bar: MaterialToolbar
    private var stepper: NumberStepper? = null
    private var builtFor: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val root = FoodViews.column(ctx)
        bar = toolbar("").apply {
            menu.add(0, MENU_EDIT, 0, R.string.recipe_edit)
            setOnMenuItemClickListener {
                startActivity(RecipeEditorActivity.intent(ctx, recipeId))
                true
            }
        }
        root.addView(bar, FoodViews.matchWrap())
        val scroll = androidx.core.widget.NestedScrollView(ctx)
        val pad = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        body = MaxWidthLinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(pad, 0, pad, pad)
        }
        scroll.addView(body, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collect(vm.state) { render(it) }
    }

    override fun onResume() {
        super.onResume()
        if (builtFor != 0) vm.reload()
    }

    /** The match picker chose [ref] for line [seq] (or null to skip it). */
    fun onMatchPicked(seq: Int, ref: FoodRef?) {
        if (ref == null) vm.skip(seq) else vm.confirm(seq, ref)
    }

    private var lastKey: Any? = null
    private var linesBox: LinearLayout? = null

    private fun render(s: RecipeDetailState) {
        if (s.loading) return
        // A changed servings count only rewrites the ingredient lines, so the stepper keeps focus.
        val key = Triple(s.recipe, s.nutrition, s.busy)
        val box = linesBox
        if (key == lastKey && box != null) {
            renderLines(box, s)
            return
        }
        lastKey = key
        renderAll(s)
    }

    private fun renderLines(box: LinearLayout, s: RecipeDetailState) {
        val ctx = requireContext()
        box.removeAllViews()
        if (s.lines.isEmpty()) box.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(R.string.recipe_no_ingredients)))
        for (line in s.lines) {
            val row = ListRow(ctx).apply {
                title = line.text
                subtitle = matchText(line)
                showChevron = false
                onClick = { MatchPickerSheet.newInstance(line.item.seq, IngredientParser.parse(line.item.line).words).show(childFragmentManager, MatchPickerSheet.TAG) }
                contentDescription = getString(R.string.recipe_line_description, line.text, subtitle)
            }
            box.addView(row, FoodViews.matchWrap())
        }
    }

    private fun renderAll(s: RecipeDetailState) {
        val ctx = requireContext()
        body.removeAllViews()
        builtFor++
        val r = s.recipe
        if (r == null) {
            body.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, getString(R.string.recipe_missing)))
            return
        }
        bar.title = r.name
        val gap = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)

        // Nutrition per serving and its honesty label.
        val card = SwCard(ctx)
        card.title = getString(R.string.recipe_per_serving)
        val n = s.nutrition
        val label = when (n?.source) {
            NutritionSource.STATED -> getString(R.string.recipe_stated)
            NutritionSource.COMPUTED -> getString(R.string.recipe_computed)
            NutritionSource.PARTIAL -> resources.getQuantityString(R.plurals.recipe_partial, n.unmatched, n.unmatched)
            null -> ""
        }
        card.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Headline_Number, n?.perServing?.kcalX10?.let { FoodViews.kcalText(ctx, FoodRows.kcalOfX10(it)) } ?: getString(R.string.dash)))
        n?.perServing?.let { p ->
            card.addView(
                FoodViews.text(
                    ctx,
                    UiR.style.TextAppearance_SelfWatch_Body,
                    getString(R.string.recipe_macros, grams(p.proteinX10), grams(p.fatX10), grams(p.carbX10)),
                ),
            )
        }
        card.addView(SourceTag(ctx).apply {
            text = label
            style = if (n?.source == NutritionSource.PARTIAL) SourceTag.Style.CHAIN else SourceTag.Style.DATABASE
            tag = "nutrition_source"
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(
            FoodViews.filledButton(ctx, getString(R.string.recipe_log_serving)) {
                FoodDetailSheet.forFood(FoodRef(FoodOrigin.RECIPE, r.id), null, 0L).show(childFragmentManager, FoodDetailSheet.TAG)
            }.apply { tag = "log_serving" },
            FoodViews.matchWrap().apply { topMargin = gap / 2 },
        )
        body.addView(card, FoodViews.matchWrap())

        // Servings scaler.
        body.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(R.string.recipe_servings)), FoodViews.matchWrap().apply { topMargin = gap })
        val st = stepper ?: NumberStepper(ctx).apply {
            decimals = 1
            stepX100 = 100
            minX100 = 10
            tag = "servings"
            onValueChange = { vm.setServings(((it + 5) / 10).toInt()) }
        }.also { stepper = it }
        (st.parent as? ViewGroup)?.removeView(st)
        if (st.valueX100 != s.servingsX10 * 10L) st.valueX100 = s.servingsX10 * 10L
        body.addView(st, FoodViews.matchWrap())

        // Ingredients.
        val ing = FoodViews.row(ctx)
        ing.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, getString(R.string.recipe_ingredients)), FoodViews.weight())
        if (s.needsMatching) ing.addView(FoodViews.textButton(ctx, getString(R.string.recipe_suggest)) { vm.suggest() }.apply { isEnabled = !s.busy })
        body.addView(ing, FoodViews.matchWrap().apply { topMargin = gap })
        val box = FoodViews.column(ctx)
        linesBox = box
        body.addView(box, FoodViews.matchWrap())
        renderLines(box, s)

        // Steps.
        val steps = r.steps
        if (!steps.isNullOrBlank()) {
            body.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, getString(R.string.recipe_steps)), FoodViews.matchWrap().apply { topMargin = gap })
            body.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, steps))
        }
        val source = r.sourceName ?: r.sourceUrl
        if (!source.isNullOrBlank()) body.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(R.string.recipe_source, source)), FoodViews.matchWrap().apply { topMargin = gap })
    }

    private fun matchText(line: IngredientUi): String = when (line.item.match) {
        MatchState.CONFIRMED -> getString(R.string.recipe_match_confirmed, line.foodName ?: getString(R.string.recipe_match_gone))
        MatchState.SUGGESTED -> getString(R.string.recipe_match_suggested, line.foodName ?: getString(R.string.recipe_match_gone))
        MatchState.SKIPPED -> getString(R.string.recipe_match_skipped)
        MatchState.UNMATCHED -> getString(R.string.recipe_match_none)
    }

    private fun grams(x10: Int?): String = x10?.let { Fmt.x100(it * 10L, 1) } ?: getString(R.string.dash)

    /** Arguments. */
    companion object {
        private const val ARG_ID = "id"
        private const val MENU_EDIT = 1

        /** A detail for recipe [id]. */
        fun newInstance(id: Long): RecipeDetailFragment = RecipeDetailFragment().apply { arguments = Bundle().apply { putLong(ARG_ID, id) } }
    }
}

/** Picks the food for one ingredient line ("fix match"), or skips the line. */
class MatchPickerSheet : SwSheet() {

    private val vm: MatchPickerViewModel by viewModels {
        val g = requireContext().graph
        val q = requireArguments().getString(ARG_QUERY).orEmpty()
        viewModelFactory { initializer { MatchPickerViewModel(q) { GraphFoodBackend(g.food) } } }
    }

    override val sheetTitle: CharSequence get() = getString(R.string.recipe_fix_match)

    private val seq: Int get() = requireArguments().getInt(ARG_SEQ)

    override fun onCreateSheetContent(inflater: LayoutInflater, container: ViewGroup?): View {
        val ctx = inflater.context
        val col = FoodViews.column(ctx)
        val (l, edit) = FoodViews.field(ctx, R.string.food_search_hint)
        edit.setText(requireArguments().getString(ARG_QUERY).orEmpty())
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = vm.setQuery(s?.toString().orEmpty())
        })
        col.addView(l, FoodViews.matchWrap())
        val rows = FoodViews.column(ctx).apply { tag = "match_rows" }
        col.addView(rows, FoodViews.matchWrap())
        col.addView(FoodViews.textButton(ctx, getString(R.string.recipe_skip_line)) { pick(null) })
        val none = FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(R.string.food_no_results))
        col.addView(none)
        rowsBox = rows
        noneText = none
        return col
    }

    private var rowsBox: LinearLayout? = null
    private var noneText: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.hits.collect { hits ->
                    val rows = rowsBox
                    val none = noneText
                    if (rows != null && none != null) renderHits(rows, none, hits)
                }
            }
        }
    }

    private fun renderHits(rows: LinearLayout, none: TextView, hits: List<FoodHit>) {
        rows.removeAllViews()
        FoodViews.show(none, hits.isEmpty())
        for (h in hits.take(MAX_ROWS)) {
            val row = ListRow(requireContext()).apply {
                title = h.name
                subtitle = FoodViews.hitSubtitle(context, h)
                showChevron = false
                onClick = { pick(h.ref) }
            }
            row.addTitleAccessory(FoodViews.sourceTag(requireContext(), h))
            rows.addView(row, FoodViews.matchWrap())
        }
    }

    private fun pick(ref: FoodRef?) {
        (parentFragment as? RecipeDetailFragment)?.onMatchPicked(seq, ref)
        dismissAllowingStateLoss()
    }

    /** Arguments. */
    companion object {
        /** Fragment tag. */
        const val TAG: String = "match_picker"
        private const val ARG_SEQ = "seq"
        private const val ARG_QUERY = "query"
        private const val MAX_ROWS = 20

        /** A picker for ingredient line [seq], searching [query] first. */
        fun newInstance(seq: Int, query: String): MatchPickerSheet = MatchPickerSheet().apply {
            arguments = Bundle().apply {
                putInt(ARG_SEQ, seq)
                putString(ARG_QUERY, query)
            }
        }
    }
}
