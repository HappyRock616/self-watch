/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.cards

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardStatusColors
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.Table
import app.selfwatch.food.SleepCaloriesFragment
import app.selfwatch.food.budget.EnergyBudget
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Nutrients
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.RingView
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.math.roundToInt
import app.selfwatch.ui.R as UiR

/** The nutrients the Daily balance card lists (each can show the foods behind it). */
enum class BalanceNutrient(@field:StringRes @get:StringRes val label: Int, val mg: Boolean = false) {
    PROTEIN(R.string.balance_protein), CARBS(R.string.balance_carbs), FAT(R.string.balance_fat),
    SAT_FAT(R.string.balance_sat_fat), TRANS_FAT(R.string.balance_trans_fat), MONO_FAT(R.string.balance_mono_fat), POLY_FAT(R.string.balance_poly_fat),
    FIBER(R.string.balance_fiber), SODIUM(R.string.balance_sodium, mg = true), ADDED_SUGAR(R.string.balance_added_sugar), SUGAR(R.string.balance_sugar),
    ;

    /** This nutrient of [n] in grams (mg for sodium), or null when unknown. */
    fun of(n: Nutrients): Double? = when (this) {
        PROTEIN -> DailyBalanceMath.g(n.proteinX10)
        CARBS -> DailyBalanceMath.g(n.carbX10)
        FAT -> DailyBalanceMath.g(n.fatX10)
        SAT_FAT -> DailyBalanceMath.g(n.satfatX10)
        TRANS_FAT -> DailyBalanceMath.g(NutrientsV2.transFatX10(n))
        MONO_FAT -> DailyBalanceMath.g(NutrientsV2.monoFatX10(n))
        POLY_FAT -> DailyBalanceMath.g(NutrientsV2.polyFatX10(n))
        FIBER -> DailyBalanceMath.g(n.fibreX10)
        SODIUM -> n.sodiumMg?.toDouble()
        ADDED_SUGAR -> DailyBalanceMath.g(NutrientsV2.addedSugarX10(n))
        SUGAR -> DailyBalanceMath.g(n.sugarX10)
    }
}

/**
 * Daily balance (the first card on Food; it replaced the fixed calories-left header): calories eaten,
 * burned (resting + active) and left as a ring; protein, carbs and fat against their targets with the
 * share of calories each gives against a balanced range; the fat types; fiber against its target;
 * sodium, saturated fat and added sugar against their limits. Tap a line for the foods behind it. For
 * the diary day shown.
 */
object DailyBalanceCard : Card<DailyBalanceCard.Data>("daily_balance", CardTab.FOOD, R.string.card_daily_balance, R.string.card_daily_balance_summary) {

    /**
     * The day's balance. [lines] per nutrient (only those with a value or a target), [shares] each macro's
     * share of calories, [top] the foods giving most of each nutrient (name, amount).
     */
    data class Data(
        val goalKcal: Int,
        val eatenKcal: Int,
        val activeKcal: Int,
        val restingKcal: Int,
        val leftKcal: Int,
        val lines: Map<BalanceNutrient, NutrientLine>,
        val shares: Map<Macro, Int>,
        val top: Map<BalanceNutrient, List<Pair<String, Double>>>,
    ) {
        /** Calories burned so far: resting plus active. */
        val burnedKcal: Int get() = restingKcal + activeKcal
    }

    override val tables: Set<Table> = setOf(Table.DIARY, Table.DAY_SUMMARY, Table.GOAL, Table.SETTING, Table.BODY)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override suspend fun load(env: CardEnv): Data {
        val b = EnergyBudget(env.db, env.clock).forDay(env.day)
        val entries = env.db.diary.day(env.day)
        val totals = entries.fold(Nutrients.UNKNOWN) { acc, e -> acc + e.nutrients }
        val goals = env.db.goals.allOn(env.day)
        val goal = b.goalKcal
        val lines = LinkedHashMap<BalanceNutrient, NutrientLine>()
        lines[BalanceNutrient.PROTEIN] = NutrientLine(BalanceNutrient.PROTEIN.of(totals), DailyBalanceMath.target(Macro.PROTEIN, goal, goals[GoalKind.PROTEIN_G]?.value), false)
        lines[BalanceNutrient.CARBS] = NutrientLine(BalanceNutrient.CARBS.of(totals), DailyBalanceMath.target(Macro.CARBS, goal, goals[GoalKind.CARB_G]?.value), false)
        lines[BalanceNutrient.FAT] = NutrientLine(BalanceNutrient.FAT.of(totals), DailyBalanceMath.target(Macro.FAT, goal, goals[GoalKind.FAT_G]?.value), false)
        lines[BalanceNutrient.SAT_FAT] = NutrientLine(BalanceNutrient.SAT_FAT.of(totals), DailyBalanceMath.satFatLimitG(goal), true)
        for (n in listOf(BalanceNutrient.TRANS_FAT, BalanceNutrient.MONO_FAT, BalanceNutrient.POLY_FAT)) {
            n.of(totals)?.let { lines[n] = NutrientLine(it, null, n == BalanceNutrient.TRANS_FAT) }
        }
        lines[BalanceNutrient.FIBER] = NutrientLine(BalanceNutrient.FIBER.of(totals), DailyBalanceMath.fiberTargetG(goal), false)
        lines[BalanceNutrient.SODIUM] = NutrientLine(BalanceNutrient.SODIUM.of(totals), DailyBalanceMath.SODIUM_LIMIT_MG.toDouble(), true)
        val added = BalanceNutrient.ADDED_SUGAR.of(totals)
        if (added != null) {
            lines[BalanceNutrient.ADDED_SUGAR] = NutrientLine(added, DailyBalanceMath.addedSugarLimitG(goal), true)
        } else {
            // Until food database v2 tells added sugar apart, total sugar is shown without a limit.
            BalanceNutrient.SUGAR.of(totals)?.let { lines[BalanceNutrient.SUGAR] = NutrientLine(it, null, false) }
        }
        val shares = DailyBalanceMath.shares(
            lines[BalanceNutrient.PROTEIN]?.value ?: 0.0,
            lines[BalanceNutrient.CARBS]?.value ?: 0.0,
            lines[BalanceNutrient.FAT]?.value ?: 0.0,
        )
        return Data(goal, b.eatenKcal, b.activeKcal, b.restingKcal, b.remainingKcal, lines, shares, topFoods(entries, lines.keys))
    }

    /** The foods giving most of each of [nutrients] in [entries] (up to five each, largest first). */
    fun topFoods(entries: List<DiaryEntry>, nutrients: Collection<BalanceNutrient>): Map<BalanceNutrient, List<Pair<String, Double>>> =
        nutrients.associateWith { n ->
            entries.mapNotNull { e -> n.of(e.nutrients)?.takeIf { it > 0 }?.let { e.name to it } }
                .groupBy({ it.first }, { it.second }).map { (name, v) -> name to v.sum() }
                .sortedByDescending { it.second }.take(TOP)
        }

    override fun small(ctx: Context, data: Data): SmallFace {
        val budget = data.goalKcal + data.activeKcal
        return SmallFace(
            value = Fmt.number(kotlin.math.abs(data.leftKcal).toLong()),
            unit = ctx.getString(R.string.unit_kcal),
            label = ctx.getString(if (data.leftKcal >= 0) R.string.balance_small_left else R.string.balance_small_over),
            status = when {
                data.leftKcal >= 0 -> CardStatus.GOOD
                data.leftKcal >= -budget / OVER_CLOSE -> CardStatus.CLOSE
                else -> CardStatus.OFF
            },
            note = ctx.getString(R.string.balance_small_note, Fmt.number(data.eatenKcal.toLong()), Fmt.number(data.burnedKcal.toLong())),
            progress = if (budget > 0) data.eatenKcal.toFloat() / budget else null,
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private class Holder(private val host: CardHost, parent: ViewGroup) : CardView<Data>(SwCard(parent.context)) {
        private val ctx = parent.context
        private val sw = root as SwCard
        private val ring = RingView(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val eaten = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body)
        private val burned = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body)
        private val goal = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Caption)
        private val lines = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        init {
            sw.id = R.id.daily_balance_card
            sw.title = ctx.getString(R.string.card_daily_balance)
            sw.actionText = ctx.getString(R.string.balance_how)
            sw.onAction = { explain() }
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val size = ctx.resources.getDimensionPixelSize(R.dimen.daily_balance_ring)
            top.addView(ring, LinearLayout.LayoutParams(size, size))
            val numbers = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            numbers.addView(eaten, Views.matchWrap(ctx))
            numbers.addView(burned, Views.matchWrap(ctx, 4))
            numbers.addView(goal, Views.matchWrap(ctx, 4))
            top.addView(numbers, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Views.dp(ctx, 16) })
            sw.addView(top, Views.matchWrap(ctx))
            sw.addView(lines, Views.matchWrap(ctx, 8))
        }

        private fun explain() {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.food_energy_formula_title)
                .setMessage(R.string.food_energy_formula)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.sleep_calories_link) { _, _ -> host.open(SleepCaloriesFragment()) }
                .show()
        }

        override fun bind(data: Data) {
            val budget = data.goalKcal + data.activeKcal
            val palette = ChartPalette.of(ctx)
            ring.progress = if (budget > 0) (data.eatenKcal.toFloat() / budget).coerceIn(0f, 1f) else 0f
            ring.color = if (data.leftKcal >= 0) palette.primary else palette.secondary
            ring.centerText = Fmt.number(kotlin.math.abs(data.leftKcal).toLong())
            ring.subText = ctx.getString(if (data.leftKcal >= 0) R.string.balance_left else R.string.balance_over)
            eaten.text = ctx.getString(R.string.balance_eaten, Fmt.number(data.eatenKcal.toLong()))
            burned.text = ctx.getString(R.string.balance_burned, Fmt.number(data.burnedKcal.toLong()), Fmt.number(data.activeKcal.toLong()))
            goal.text = ctx.getString(R.string.balance_goal, Fmt.number(data.goalKcal.toLong()))
            lines.removeAllViews()
            for ((n, line) in data.lines) {
                val v = line(n, line, data)
                lines.addView(v, Views.matchWrap(ctx, 10))
            }
            sw.contentDescription = listOf(
                sw.title, ring.centerText.toString() + " " + ring.subText, eaten.text, burned.text, goal.text,
            ).joinToString(". ")
        }

        private fun line(n: BalanceNutrient, line: NutrientLine, data: Data): View {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                minimumHeight = ctx.resources.getDimensionPixelSize(UiR.dimen.sw_touch_target)
                setOnClickListener { showFoods(n, data) }
            }
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = ctx.getString(n.label)
            val unit = ctx.getString(if (n.mg) R.string.balance_unit_mg else R.string.balance_unit_g)
            val value = line.value?.let { Fmt.number(it, if (n.mg) 0 else 0) } ?: ctx.getString(R.string.dash)
            val target = line.target?.let { Fmt.number(it, 0) }
            val text = when {
                target == null -> "$value $unit"
                line.isLimit -> ctx.getString(R.string.balance_of_limit, value, target, unit)
                else -> ctx.getString(R.string.balance_of_target, value, target, unit)
            }
            top.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body, name), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body_Number, text).apply { gravity = Gravity.END })
            row.addView(top, Views.matchWrap(ctx))
            val status = status(line)
            val p = line.value?.let { v -> line.target?.takeIf { it > 0 }?.let { (v / it).toFloat().coerceIn(0f, 1f) } }
            if (p != null) {
                row.addView(
                    LinearProgressIndicator(ctx).apply {
                        max = BAR_MAX
                        setIndicatorColor(CardStatusColors.of(ctx, status) ?: ChartPalette.of(ctx).primary)
                        trackColor = ChartPalette.of(ctx).track
                        setProgressCompat((p * BAR_MAX).roundToInt(), false)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    Views.matchWrap(ctx, 4),
                )
            }
            val macro = when (n) {
                BalanceNutrient.PROTEIN -> Macro.PROTEIN
                BalanceNutrient.CARBS -> Macro.CARBS
                BalanceNutrient.FAT -> Macro.FAT
                else -> null
            }
            val share = macro?.let { m -> data.shares[m]?.let { pct -> m to pct } }
            var shareText: String? = null
            if (share != null) {
                val (m, pct) = share
                shareText = ctx.getString(
                    if (DailyBalanceMath.balanced(m, pct)) R.string.balance_share_ok else R.string.balance_share_off,
                    pct, m.range.first, m.range.last,
                )
                row.addView(Views.caption(ctx, shareText), Views.matchWrap(ctx, 2))
            }
            row.contentDescription = listOfNotNull(name, text, shareText, CardStatusColors.spoken(ctx, status), ctx.getString(R.string.balance_tap_foods)).joinToString(". ")
            for (i in 0 until row.childCount) row.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            return row
        }

        private fun status(line: NutrientLine): CardStatus {
            val v = line.value ?: return CardStatus.NONE
            val t = line.target ?: return CardStatus.NONE
            return if (line.isLimit) {
                when {
                    v <= t * LIMIT_NEAR -> CardStatus.GOOD
                    v <= t -> CardStatus.CLOSE
                    else -> CardStatus.OFF
                }
            } else {
                CardStatusColors.ofProgress(v, t)
            }
        }

        private fun showFoods(n: BalanceNutrient, data: Data) {
            val foods = data.top[n].orEmpty()
            val unit = ctx.getString(if (n.mg) R.string.balance_unit_mg else R.string.balance_unit_g)
            val message = if (foods.isEmpty()) {
                ctx.getString(R.string.balance_no_foods)
            } else {
                foods.joinToString("\n") { (name, v) -> "$name — ${Fmt.number(v, if (v < 10 && !n.mg) 1 else 0)} $unit" }
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(ctx.getString(R.string.balance_foods_title, ctx.getString(n.label)))
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private const val TOP = 5
    private const val OVER_CLOSE = 10
    private const val LIMIT_NEAR = 0.9
    private const val BAR_MAX = 1000
}
