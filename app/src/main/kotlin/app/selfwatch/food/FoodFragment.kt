/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.cards.CardStack
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.EditCardsFragment
import app.selfwatch.data.CreateFile
import app.selfwatch.data.Saf
import app.selfwatch.db.SettingKeys
import app.selfwatch.food.diary.FastStatus
import app.selfwatch.graph
import app.selfwatch.me.SettingsActivity
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.SystemClock
import app.selfwatch.shell.Nav
import app.selfwatch.shell.TabFragment
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.components.SwSnackbar
import app.selfwatch.ui.format.Fmt
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import app.selfwatch.ui.R as UiR

/**
 * Food diary tab (WP-10 `DiaryFragment`): day switcher and calories left (fixed), then the Food cards
 * the user picked (see `FoodCards`: water, macros…) for the day shown, then the meals and fasting.
 */
class FoodFragment : TabFragment(R.layout.fragment_food, R.menu.menu_food), EntryDeleteHost {

    override fun deleteEntry(entry: DiaryEntry) {
        vm.delete(entry)
    }

    private val vm: DiaryViewModel by viewModels { DiaryViewModel.factory(requireContext().graph) }

    private lateinit var diary: LinearLayout
    private lateinit var header: LinearLayout

    /** The Food card strip (tests). */
    var cards: CardStack? = null
        private set
    private lateinit var dayLabel: MaterialButton
    private lateinit var dayNext: View
    private var fastText: android.widget.TextView? = null
    private var lastState: DiaryUiState = DiaryUiState()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        diary = view.findViewById(R.id.food_diary)
        header = view.findViewById(R.id.food_header)
        val main = view.findViewById<View>(R.id.food_main)
        val stack = CardStack(this, CardTab.FOOD, view.findViewById(R.id.food_cards), requireContext().graph.db)
        cards = stack
        stack.start(vm.state.map { it.day }.filterNotNull())
        val off = view.findViewById<EmptyState>(R.id.food_off)
        val switcher = view.findViewById<View>(R.id.day_switcher)
        dayLabel = view.findViewById(R.id.day_label)
        dayNext = view.findViewById(R.id.day_next)
        off.onButton = { askTurnOn() }
        savedInstanceState?.getInt(KEY_EXPORT_DAY, 0)?.takeIf { it != 0 }?.let { pendingExportDay = Day(it) }
        view.findViewById<View>(R.id.day_prev).setOnClickListener { vm.previousDay() }
        dayNext.setOnClickListener { vm.nextDay() }
        dayLabel.setOnClickListener { vm.goToToday() }

        collectWhileStarted(vm.state) { s ->
            lastState = s
            val enabled = s.enabled ?: true
            FoodViews.show(main, enabled)
            FoodViews.show(switcher, enabled && s.day != null)
            FoodViews.show(off, !enabled)
            if (enabled && s.day != null) {
                vm.checkVegHint()
                render(s)
            }
        }
        collectWhileStarted(vm.vegHint) {
            val s = lastState
            if ((s.enabled ?: true) && s.day != null) render(s)
        }
        collectWhileStarted(vm.events) { e ->
            when (e) {
                is DiaryEvent.Deleted -> SwSnackbar.undo(requireView(), getString(R.string.food_deleted, e.entry.name), anchor()) {
                    vm.undoDelete(e.entry)
                }
                is DiaryEvent.Logged -> {
                    val meal = lastState.meals.firstOrNull { it.slot.id == e.slot }?.slot?.name?.ifBlank { null } ?: getString(R.string.food_other_meal)
                    SwSnackbar.undo(requireView(), getString(R.string.food_recent_logged, e.name, meal), anchor()) { vm.undoLog(e.id) }
                }
                is DiaryEvent.Copied -> SwSnackbar.show(
                    requireView(),
                    resources.getQuantityString(R.plurals.food_copied, e.count, e.count),
                    anchor = anchor(),
                )
                is DiaryEvent.CopiedTo -> {
                    val label = Fmt.day(requireContext(), e.to.yyyymmdd, lastState.today?.toLocalDate() ?: LocalDate.now())
                    val text = resources.getQuantityString(R.plurals.food_copied_to, e.ids.size, e.ids.size, label)
                    SwSnackbar.undo(requireView(), text, anchor()) { vm.undoCopy(e.ids) }
                }
                DiaryEvent.CopyUndone -> SwSnackbar.show(requireView(), getString(R.string.food_copy_undone), anchor = anchor())
                DiaryEvent.NothingLogged -> SwSnackbar.show(requireView(), getString(R.string.food_nothing_logged), anchor = anchor())
                is DiaryEvent.Exported -> SwSnackbar.show(
                    requireView(),
                    resources.getQuantityString(R.plurals.food_exported, e.count, e.count),
                    anchor = anchor(),
                )
                DiaryEvent.Failed -> SwSnackbar.show(requireView(), getString(R.string.food_failed), anchor = anchor())
            }
        }
        // Re-time the running fast every 30 s.
        collectWhileStarted(
            flow {
                while (true) {
                    emit(Unit)
                    delay(FAST_TICK_MS)
                }
            },
        ) { renderFastText(lastState.fast) }
    }

    override fun onMenuItem(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_edit_cards -> {
            Nav.open(this, EditCardsFragment.newInstance(CardTab.FOOD))
            true
        }
        R.id.menu_day_nutrition -> {
            Nav.open(this, DayNutritionFragment.newInstance(lastState.day ?: SystemClock.today()))
            true
        }
        R.id.menu_recipe_book -> {
            startActivity(RecipeActivity.bookIntent(requireContext()))
            true
        }
        R.id.menu_go_to_date -> {
            pickDate(lastState.day ?: SystemClock.today()) { vm.showDay(it) }
            true
        }
        R.id.menu_copy_day -> {
            copyDay()
            true
        }
        R.id.menu_meal_names -> {
            MealNamesSheet().show(childFragmentManager, MealNamesSheet.TAG)
            true
        }
        R.id.menu_export_day -> {
            exportDay()
            true
        }
        else -> false
    }

    private val createExport = registerForActivityResult(CreateFile()) { uri ->
        val day = pendingExportDay
        pendingExportDay = null
        if (uri != null && day != null) vm.exportDay(day, Saf.output(requireContext(), uri))
    }

    /** The day waiting for the user to pick where its export goes. */
    private var pendingExportDay: Day? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingExportDay?.let { outState.putInt(KEY_EXPORT_DAY, it.yyyymmdd) }
    }

    private fun pickDate(start: Day, onPick: (Day) -> Unit) {
        val d = start.toLocalDate()
        DatePickerDialog(requireContext(), { _, y, m, dom -> onPick(Day.of(LocalDate.of(y, m + 1, dom))) }, d.year, d.monthValue - 1, d.dayOfMonth)
            .apply { datePicker.maxDate = System.currentTimeMillis() }
            .show()
    }

    /** "Copy day": the whole day or one meal, then the day to copy it to. */
    private fun copyDay() {
        val day = lastState.day
        val meals = lastState.meals.filter { it.entries.isNotEmpty() }
        if (day == null || meals.isEmpty()) {
            SwSnackbar.show(requireView(), getString(R.string.food_nothing_logged), anchor = anchor())
            return
        }
        val total = meals.sumOf { it.entries.size }
        val labels = listOf(resources.getQuantityString(R.plurals.food_copy_whole_day, total, total)) +
            meals.map { m -> getString(R.string.food_copy_meal, m.slot.name.ifBlank { getString(R.string.food_other_meal) }, m.entries.size) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.food_copy_what)
            .setItems(labels.toTypedArray()) { _, which ->
                val slot = if (which == 0) null else meals[which - 1].slot.id
                val today = lastState.today ?: SystemClock.today()
                val start = if (day == today) day.plusDays(-1) else today
                pickDate(start) { to ->
                    if (to == day) {
                        SwSnackbar.show(requireView(), getString(R.string.food_copy_same_day), anchor = anchor())
                    } else {
                        vm.copyTo(to, slot)
                    }
                }
            }
            .show()
    }

    /** "Export day": asks where to save the day's CSV. */
    private fun exportDay() {
        val day = lastState.day
        if (day == null || !vm.hasEntries()) {
            SwSnackbar.show(requireView(), getString(R.string.food_nothing_logged), anchor = anchor())
            return
        }
        pendingExportDay = day
        createExport.launch(CreateFile.Request(DayExport.fileName(day), DayExport.MIME_TYPE))
    }

    /** "Food tracking is off": turn it on here, or open Settings. */
    private fun askTurnOn() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.food_turn_on_title)
            .setMessage(R.string.food_turn_on_message)
            .setPositiveButton(R.string.food_turn_on) { _, _ ->
                val settings = ctx.graph.settings
                viewLifecycleOwner.lifecycleScope.launch { settings.setBool(SettingKeys.FOOD_ENABLED, true) }
            }
            .setNeutralButton(R.string.me_settings) { _, _ -> startActivity(android.content.Intent(ctx, SettingsActivity::class.java)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun anchor(): View? = activity?.findViewById<View>(R.id.add_fab)?.takeIf { it.isShown }

    private fun render(s: DiaryUiState) {
        val ctx = requireContext()
        val day = s.day ?: return
        val label = Fmt.day(ctx, day.yyyymmdd, s.today?.toLocalDate() ?: LocalDate.now())
        toolbar.subtitle = null // the day switcher below names the day
        dayLabel.text = label
        dayLabel.contentDescription = getString(R.string.food_day_label, label)
        dayNext.isEnabled = !s.isToday
        dayNext.alpha = if (s.isToday) DISABLED_ALPHA else 1f

        diary.removeAllViews()
        header.removeAllViews()
        // The calories-left header is now the Daily balance card (first in the card strip by default).
        header.visibility = View.GONE
        if (vm.vegHint.value) diary.addView(vegHintCard(), cardParams())
        for (m in s.meals) diary.addView(mealCard(day, m), cardParams())
        diary.addView(fastCard(s.fast), cardParams())
    }

    private fun cardParams(first: Boolean = false): LinearLayout.LayoutParams = FoodViews.matchWrap().apply {
        topMargin = if (first) 0 else resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
    }


    /** The one-time "Set vegetarian?" offer: yes sets the diet, no thanks dismisses it for good. */
    private fun vegHintCard(): View {
        val ctx = requireContext()
        val card = SwCard(ctx).apply { tag = "veg_hint" }
        card.title = getString(R.string.veg_hint_title)
        card.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, getString(R.string.veg_hint_text)), FoodViews.matchWrap())
        val actions = FoodViews.row(ctx).apply { gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL }
        actions.addView(FoodViews.textButton(ctx, getString(R.string.veg_hint_no)) { vm.answerVegHint(false) }.apply { tag = "veg_hint_no" })
        actions.addView(
            FoodViews.filledButton(ctx, getString(R.string.veg_hint_yes)) {
                vm.answerVegHint(true)
                SwSnackbar.show(requireView(), getString(R.string.veg_hint_done), anchor = anchor())
            }.apply { tag = "veg_hint_yes" },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = resources.getDimensionPixelSize(UiR.dimen.sw_space_1)
            },
        )
        card.addView(actions, FoodViews.matchWrap())
        return card
    }

    private fun mealCard(day: Day, m: MealUi): View {
        val ctx = requireContext()
        val card = SwCard(ctx)
        val header = FoodViews.row(ctx)
        val name = m.slot.name.ifBlank { getString(R.string.food_other_meal) }
        header.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, name).also {
            ViewCompat.setAccessibilityHeading(it, true)
        }, FoodViews.weight())
        header.addView(FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body_Number, if (m.entries.isEmpty()) getString(R.string.dash) else Fmt.number(m.kcal.toLong())))
        // Tap the header: the meal's nutrition summary (the chevron says it opens something).
        val chevron = android.widget.ImageView(ctx).apply {
            setImageResource(UiR.drawable.sw_ic_chevron_end)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val chevronSize = FoodViews.dp(ctx, CHEVRON_DP)
        header.addView(chevron, LinearLayout.LayoutParams(chevronSize, chevronSize).apply { marginStart = resources.getDimensionPixelSize(UiR.dimen.sw_space_1) })
        header.contentDescription = getString(R.string.meal_summary_open, getString(R.string.food_meal_description, name, FoodViews.kcalText(ctx, m.kcal)))
        header.minimumHeight = resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
        header.tag = "meal_header_${m.slot.id}"
        header.isClickable = true
        header.isFocusable = true
        val ripple = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        header.setBackgroundResource(ripple.resourceId)
        header.setOnClickListener { Nav.open(this, MealSummaryFragment.newInstance(day, m.slot.id, m.slot.name)) }
        card.addView(header)
        if (m.entries.isNotEmpty() && (m.totals.carbX10 != null || m.totals.fatX10 != null || m.totals.proteinX10 != null)) {
            val t = m.totals
            val c = macroGrams(t.carbX10)
            val f = macroGrams(t.fatX10)
            val p = macroGrams(t.proteinX10)
            card.addView(
                FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption, getString(R.string.meal_macros, c, f, p)).apply {
                    tag = "macros_${m.slot.id}"
                    contentDescription = getString(R.string.meal_macros_description, c, f, p)
                },
                FoodViews.matchWrap(),
            )
        }
        for (e in m.entries) card.addView(entryRow(e), FoodViews.matchWrap())
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val add = FoodViews.textButton(ctx, getString(R.string.food_add_food_plus)) {
            startActivity(AddFoodActivity.intent(ctx, day, m.slot.id))
        }
        add.contentDescription = getString(R.string.food_add_to_meal, name)
        actions.addView(add)
        val offer = m.yesterday
        if (offer != null) {
            val copy = FoodViews.outlinedButton(ctx, getString(R.string.food_copy_yesterday, name.lowercase(), Fmt.number(offer.kcal.toLong()))) {
                vm.copyYesterday(m.slot.id)
            }
            copy.tag = "copy_${m.slot.id}"
            actions.addView(copy, FoodViews.weight().apply { marginStart = resources.getDimensionPixelSize(UiR.dimen.sw_space_1) })
        }
        card.addView(actions, FoodViews.matchWrap())
        if (m.recents.isNotEmpty()) {
            card.addView(RecentStrip.build(childFragmentManager, ctx, day, m.slot.id, name, m.recents) { vm.logRecent(m.slot.id, it) }, FoodViews.matchWrap())
        }
        return card
    }

    /** Whole grams of a meal's macro (×10), "0" when no entry reports it. */
    private fun macroGrams(x10: Int?): String = Fmt.number(((x10 ?: 0) + 5L) / 10)

    /** One food: tap opens the entry editor; swipe sideways or the (−) removes it (with Undo). */
    @android.annotation.SuppressLint("ClickableViewAccessibility") // the swipe is an extra; the (−) and a TalkBack action do the same
    private fun entryRow(e: DiaryEntry): View {
        val ctx = requireContext()
        val remove = { vm.delete(e) }
        return ListRow(ctx).apply {
            tag = "entry_${e.id}"
            title = e.name
            subtitle = amountText(ctx, e)
            trailing = Fmt.number(FoodRows.kcal(e.nutrients).toLong())
            showChevron = false
            onClick = { FoodDetailSheet.forEntry(e.id).show(childFragmentManager, FoodDetailSheet.TAG) }
            contentDescription = getString(R.string.food_entry_description, e.name, subtitle, FoodViews.kcalText(ctx, FoodRows.kcal(e.nutrients)))
            val label = getString(R.string.food_remove_entry, e.name)
            val minus = FoodViews.iconButton(ctx, UiR.drawable.sw_ic_minus, label) { remove() }.apply {
                tag = "remove_${e.id}"
                iconTint = android.content.res.ColorStateList.valueOf(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary))
            }
            addView(minus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnTouchListener(SwipeToDelete(remove))
            ViewCompat.addAccessibilityAction(this, label) { _, _ ->
                remove()
                true
            }
        }
    }

    private fun fastCard(f: FastStatus?): View {
        val ctx = requireContext()
        val card = SwCard(ctx)
        card.title = getString(R.string.food_fasting)
        val row = FoodViews.row(ctx)
        val text = FoodViews.text(ctx, UiR.style.TextAppearance_SelfWatch_Body_Number)
        fastText = text
        renderFastText(f)
        row.addView(text, FoodViews.weight())
        val button = FoodViews.outlinedButton(ctx, getString(if (f == null) R.string.food_fast_start else R.string.food_fast_end)) { vm.toggleFast() }
        button.tag = "fast_toggle"
        row.addView(button)
        card.addView(row)
        return card
    }

    private fun renderFastText(f: FastStatus?) {
        val v = fastText ?: return
        if (f == null) {
            v.text = getString(R.string.food_fasting_empty)
            return
        }
        val now = FastStatus.of(f.fast, SystemClock.nowSec())
        v.text = getString(R.string.food_fast_progress, hm(now.elapsedSec), hm(f.fast.goalMin * 60L))
    }

    /** Constants and formatting. */
    companion object {
        private const val FAST_TICK_MS = 30_000L
        private const val KEY_EXPORT_DAY = "export_day"
        private const val DISABLED_ALPHA = 0.38f
        private const val CHEVRON_DP = 20f

        /** "14:20" from seconds. */
        fun hm(sec: Long): String {
            val m = sec.coerceAtLeast(0) / 60
            return String.format(java.util.Locale.ROOT, "%d:%02d", m / 60, m % 60)
        }

        /** "1 cup", "150 g", "1.5 × slice", "Quick add". */
        fun amountText(ctx: android.content.Context, e: DiaryEntry): String = when {
            e.food == null && e.servingLabel == app.selfwatch.food.diary.DiaryService.QUICK_ADD_SERVING -> ctx.getString(R.string.food_quick_add_label)
            e.servingLabel == "g" -> ctx.getString(R.string.food_grams, Fmt.x100(e.amountX100.toLong(), 0))
            e.amountX100 == FoodRows.ONE_X100 -> e.servingLabel
            else -> ctx.getString(R.string.food_times_serving, Fmt.x100(e.amountX100.toLong()), e.servingLabel)
        }
    }
}
