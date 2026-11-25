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
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.selfwatch.R
import app.selfwatch.food.nutrition.DietFilter
import app.selfwatch.graph
import app.selfwatch.me.AutoFoodDbState
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.time.Day
import app.selfwatch.ui.SwActivity
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.SwSnackbar
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import app.selfwatch.ui.R as UiR

/**
 * Add food (WP-10 `AddFoodActivity`): search with a Scan button, tabs Recent · Favorites · My foods ·
 * Recipes, rows with a source pill and a one-tap "+", quick add and new custom food.
 */
class AddFoodActivity : SwActivity(), FoodLoggedListener {

    private val day: Day? by lazy { intent.getIntExtra(EXTRA_DAY, 0).takeIf { it != 0 }?.let { Day(it) } }
    private val slot: Long by lazy { intent.getLongExtra(EXTRA_SLOT, 0L) }

    private val vm: AddFoodViewModel by viewModels {
        val g = graph
        viewModelFactory { initializer { AddFoodViewModel(day, slot) { GraphFoodBackend(g.food) } } }
    }

    private lateinit var list: RecyclerView
    private lateinit var tabs: TabLayout
    private lateinit var empty: EmptyState
    private lateinit var toolbar: MaterialToolbar
    private lateinit var chainHeader: android.widget.TextView
    private lateinit var filterChips: com.google.android.material.chip.ChipGroup
    private lateinit var hiddenNote: android.widget.TextView
    private lateinit var dbStatus: android.widget.TextView
    private lateinit var dbProgress: LinearProgressIndicator
    private var chipsFor: List<DietFilter>? = null
    private val adapter = FoodHitAdapter(onOpen = { vm.open(it) }, onPlus = { vm.quickLog(it) })

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = r.data?.getLongExtra(FoodEditorActivity.RESULT_FOOD_ID, 0L) ?: 0L
        vm.refresh()
        if (id != 0L) openDetail(FoodRef(FoodOrigin.MY_FOOD, id))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx: Context = this
        val root = FoodViews.column(ctx)
        toolbar = MaterialToolbar(ctx).apply {
            title = getString(R.string.food_add_food)
            setNavigationIcon(UiR.drawable.sw_ic_close)
            navigationContentDescription = getString(UiR.string.sw_close)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.menu_add_food)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_create_food -> editor.launch(FoodEditorActivity.newIntent(ctx, null))
                    R.id.menu_create_recipe -> startActivity(RecipeEditorActivity.intent(ctx, 0L))
                    R.id.menu_quick_add -> openQuickAdd()
                }
                true
            }
        }
        root.addView(toolbar, FoodViews.matchWrap())

        val pad = resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        val search = FoodViews.row(ctx).apply { setPaddingRelative(pad, 0, pad, 0) }
        val (searchLayout, searchEdit) = FoodViews.field(ctx, R.string.food_search_hint, InputType.TYPE_CLASS_TEXT)
        searchEdit.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchEdit.tag = "search"
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = vm.setQuery(s?.toString().orEmpty())
        })
        search.addView(searchLayout, FoodViews.weight())
        val scan = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.food_scan)
            setIconResource(R.drawable.ic_scan)
            isAllCaps = false
            minHeight = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
            contentDescription = getString(R.string.add_scan)
            setOnClickListener { startActivity(ScanActivity.intent(ctx, day, slot)) }
        }
        search.addView(scan, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = resources.getDimensionPixelSize(UiR.dimen.sw_space_1)
        })
        root.addView(search, FoodViews.matchWrap())

        // The automatic food database download (first use of food entry): one status line and a bar.
        dbStatus = FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption).apply {
            tag = "food_db_status"
            visibility = View.GONE
            setPaddingRelative(pad, pad / 2, pad, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(dbStatus, FoodViews.matchWrap())
        dbProgress = LinearProgressIndicator(ctx).apply {
            tag = "food_db_progress"
            visibility = View.GONE
            max = PROGRESS_MAX
            setPaddingRelative(pad, pad / 4, pad, pad / 2)
        }
        root.addView(dbProgress, FoodViews.matchWrap())

        // Diet filter chips, only for users with a diet or allergen set.
        filterChips = com.google.android.material.chip.ChipGroup(ctx).apply {
            tag = "diet_filters"
            visibility = View.GONE
            setPaddingRelative(pad, 0, pad, 0)
        }
        root.addView(filterChips, FoodViews.matchWrap())
        hiddenNote = FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption).apply {
            tag = "filter_hidden"
            visibility = View.GONE
            setPaddingRelative(pad, 0, pad, 0)
        }
        root.addView(hiddenNote, FoodViews.matchWrap())

        tabs = TabLayout(ctx).apply {
            tabMode = TabLayout.MODE_SCROLLABLE
            for (t in AddFoodTab.entries) addTab(newTab().setText(tabLabel(t)).setTag(t))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    (tab.tag as? AddFoodTab)?.let { vm.selectTab(it) }
                }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        root.addView(tabs, FoodViews.matchWrap())

        chainHeader = FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge).apply {
            tag = "chain_header"
            visibility = View.GONE
            setPaddingRelative(pad, pad / 2, pad, 0)
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }
        root.addView(chainHeader, FoodViews.matchWrap())

        val content = android.widget.FrameLayout(ctx)
        list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = this@AddFoodActivity.adapter
            setPaddingRelative(pad, 0, pad / 2, 0)
            clipToPadding = false
            tag = "results"
        }
        content.addView(list)
        empty = EmptyState(ctx).apply { visibility = View.GONE }
        content.addView(empty)
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = FoodViews.column(ctx).apply { setPaddingRelative(pad, 0, pad, 0) }
        footer.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(R.string.food_plus_hint)))
        val actions = FoodViews.row(ctx)
        actions.addView(FoodViews.outlinedButton(ctx, getString(R.string.add_quick)) { openQuickAdd() }.apply { tag = "quick_add" }, FoodViews.weight())
        actions.addView(
            FoodViews.outlinedButton(ctx, getString(R.string.food_new_food)) { editor.launch(FoodEditorActivity.newIntent(ctx, null)) },
            FoodViews.weight().apply { marginStart = resources.getDimensionPixelSize(UiR.dimen.sw_space_1) },
        )
        footer.addView(actions, FoodViews.matchWrap())
        root.addView(footer, FoodViews.matchWrap())

        setRootContent(root)

        val auto = graph.food.autoInstall
        auto.ensure()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.state.collect { render(it) } }
                launch { auto.state.collect { renderDb(it) } }
                launch {
                    vm.events.collect { e ->
                        when (e) {
                            is AddFoodEvent.Logged -> SwSnackbar.undo(list, getString(R.string.food_logged, e.name, FoodViews.kcalText(ctx, e.kcal))) {
                                vm.undo(e.entryId)
                            }
                            is AddFoodEvent.OpenDetail -> openDetail(e.hit.ref)
                            AddFoodEvent.Failed -> SwSnackbar.show(list, getString(R.string.food_failed))
                        }
                    }
                }
            }
        }
    }

    override fun onRestart() {
        super.onRestart()
        vm.refresh()
    }

    /** The automatic download: progress while it runs, a note while it waits for Wi-Fi, one message at the end. */
    private fun renderDb(s: AutoFoodDbState) {
        val text: String? = when (s) {
            AutoFoodDbState.Idle -> null
            AutoFoodDbState.WaitingForWifi -> getString(R.string.fooddata_auto_wifi)
            AutoFoodDbState.Checking -> getString(R.string.fooddata_auto_checking)
            is AutoFoodDbState.Downloading -> {
                val total = s.total?.takeIf { it > 0 }
                if (total != null) {
                    getString(R.string.fooddata_auto_progress, (s.done * 100 / total).toInt().coerceIn(0, 100))
                } else {
                    getString(R.string.fooddata_auto_progress_bytes, android.text.format.Formatter.formatShortFileSize(this, s.done))
                }
            }
            is AutoFoodDbState.Installed, is AutoFoodDbState.Failed, AutoFoodDbState.NeedsNewerApp -> {
                val msg = when (s) {
                    is AutoFoodDbState.Failed -> getString(R.string.fooddata_auto_failed, s.detail)
                    AutoFoodDbState.NeedsNewerApp -> getString(R.string.fooddata_auto_needs_app)
                    else -> getString(R.string.fooddata_auto_done)
                }
                if (s is AutoFoodDbState.Installed) vm.refresh()
                SwSnackbar.show(list, msg)
                graph.food.autoInstall.acknowledge()
                null
            }
        }
        dbStatus.text = text
        FoodViews.show(dbStatus, text != null)
        val running = s == AutoFoodDbState.Checking || s is AutoFoodDbState.Downloading
        FoodViews.show(dbProgress, running)
        if (running) {
            val d = s as? AutoFoodDbState.Downloading
            val total = d?.total?.takeIf { it > 0 }
            dbProgress.isIndeterminate = d == null || total == null
            if (d != null && total != null) dbProgress.setProgressCompat((d.done * PROGRESS_MAX / total).toInt(), false)
        }
    }

    private fun render(s: AddFoodState) {
        toolbar.title = s.slot?.let { getString(R.string.food_add_to, it.name) } ?: getString(R.string.food_add_food)
        FoodViews.show(tabs, s.results == null)
        val wanted = tabs.getTabAt(s.tab.ordinal)
        if (wanted != null && !wanted.isSelected) wanted.select()
        adapter.prefs = s.prefs
        adapter.unsure = s.unsure
        adapter.submitList(s.rows)
        renderFilters(s)
        val chain = s.chain
        chainHeader.text = chain?.let { getString(R.string.search_chain_menu, it) }
        FoodViews.show(chainHeader, chain != null)
        val showEmpty = s.rows.isEmpty() && !s.searching && (s.results != null || !s.tabLoading)
        FoodViews.show(empty, showEmpty)
        if (showEmpty) {
            empty.message = getString(
                when {
                    s.results != null -> R.string.food_no_results
                    s.tab == AddFoodTab.RECENT -> R.string.food_empty_recent
                    s.tab == AddFoodTab.FAVORITES -> R.string.food_empty_favorites
                    s.tab == AddFoodTab.MINE -> R.string.food_empty_mine
                    else -> R.string.food_empty_recipes
                },
            )
        }
    }

    /** The filter chips (built once per set of filters) and the "n foods hidden" note. */
    private fun renderFilters(s: AddFoodState) {
        if (chipsFor != s.filters) {
            chipsFor = s.filters
            filterChips.removeAllViews()
            for (f in s.filters) {
                val chip = com.google.android.material.chip.Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                    id = View.generateViewId()
                    text = filterLabel(f)
                    tag = "filter_${f.id}"
                    isCheckable = true
                    minHeight = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
                }
                filterChips.addView(chip)
            }
        }
        for (i in 0 until filterChips.childCount) {
            val chip = filterChips.getChildAt(i) as com.google.android.material.chip.Chip
            val f = s.filters.getOrNull(i) ?: continue
            chip.setOnCheckedChangeListener(null)
            chip.isChecked = f in s.activeFilters
            chip.setOnCheckedChangeListener { _, on -> vm.setFilter(f, on) }
        }
        FoodViews.show(filterChips, s.filters.isNotEmpty())
        val hidden = s.hiddenCount
        hiddenNote.text = if (hidden > 0) resources.getQuantityString(R.plurals.filter_hidden_count, hidden, hidden) else null
        FoodViews.show(hiddenNote, hidden > 0)
    }

    private fun filterLabel(f: DietFilter): String = when (f) {
        DietFilter.Vegan -> getString(R.string.filter_vegan)
        DietFilter.Vegetarian -> getString(R.string.filter_vegetarian)
        DietFilter.GlutenFree -> getString(R.string.filter_gluten_free)
        is DietFilter.Without -> getString(R.string.filter_without, getString(NutrientUi.allergen(f.allergen)))
    }

    private fun tabLabel(t: AddFoodTab): String = getString(
        when (t) {
            AddFoodTab.RECENT -> R.string.food_tab_recent
            AddFoodTab.FAVORITES -> R.string.food_tab_favorites
            AddFoodTab.MINE -> R.string.food_tab_mine
            AddFoodTab.RECIPES -> R.string.food_tab_recipes
        },
    )

    private fun openDetail(ref: FoodRef) {
        if (supportFragmentManager.findFragmentByTag(FoodDetailSheet.TAG) != null) return
        FoodDetailSheet.forFood(ref, vm.state.value.day, vm.state.value.slot?.id ?: slot).show(supportFragmentManager, FoodDetailSheet.TAG)
    }

    private fun openQuickAdd() {
        if (supportFragmentManager.findFragmentByTag(QuickAddSheet.TAG) != null) return
        QuickAddSheet.newInstance(vm.state.value.day, vm.state.value.slot?.id ?: slot).show(supportFragmentManager, QuickAddSheet.TAG)
    }

    override fun onFoodLogged(name: String, entryId: Long) {
        SwSnackbar.undo(list, getString(R.string.food_added, name)) { vm.undo(entryId) }
        vm.refresh()
    }

    /** Intents. */
    companion object {
        private const val EXTRA_DAY = "day"
        private const val EXTRA_SLOT = "slot"
        private const val PROGRESS_MAX = 1_000

        /** Opens Add food for meal [slot] (0: guess from the time) on [day] (null: today). */
        fun intent(ctx: Context, day: Day?, slot: Long): Intent =
            Intent(ctx, AddFoodActivity::class.java).putExtra(EXTRA_DAY, day?.yyyymmdd ?: 0).putExtra(EXTRA_SLOT, slot)
    }
}
