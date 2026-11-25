/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import app.selfwatch.R
import app.selfwatch.food.nutrition.Diet
import app.selfwatch.food.nutrition.DietConflict
import app.selfwatch.food.nutrition.DietPrefs
import app.selfwatch.food.nutrition.TargetStatus
import app.selfwatch.model.food.Allergen
import app.selfwatch.model.food.DietInfo
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Nutrients.Field
import app.selfwatch.ui.ThemeColors
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.SwContrast
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.R as UiR

/** Names, amounts, colors and small views for nutrients, diets and allergens (food detail, Day nutrition). */
object NutrientUi {

    /** The display name of [f]. */
    @StringRes
    fun name(f: Field): Int = when (f) {
        Field.KCAL -> R.string.nutrient_kcal
        Field.PROTEIN -> R.string.nutrient_protein
        Field.FAT -> R.string.nutrient_fat
        Field.SATFAT -> R.string.nutrient_satfat
        Field.CARB -> R.string.nutrient_carb
        Field.SUGAR -> R.string.nutrient_sugar
        Field.FIBER -> R.string.nutrient_fiber
        Field.SODIUM -> R.string.nutrient_sodium
        Field.CHOLESTEROL -> R.string.nutrient_cholesterol
        Field.TRANSFAT -> R.string.nutrient_transfat
        Field.MONOFAT -> R.string.nutrient_monofat
        Field.POLYFAT -> R.string.nutrient_polyfat
        Field.ADDSUGAR -> R.string.nutrient_addsugar
        Field.POTASSIUM -> R.string.nutrient_potassium
        Field.CALCIUM -> R.string.nutrient_calcium
        Field.IRON -> R.string.nutrient_iron
        Field.VITA -> R.string.nutrient_vita
        Field.VITC -> R.string.nutrient_vitc
        Field.VITD -> R.string.nutrient_vitd
    }

    /** The title of a `NutrientTargets.GROUPS` key. */
    @StringRes
    fun group(key: String): Int = when (key) {
        "energy" -> R.string.nutrient_group_energy
        "fats" -> R.string.nutrient_group_fats
        "carbs" -> R.string.nutrient_group_carbs
        "protein" -> R.string.nutrient_group_protein
        "minerals" -> R.string.nutrient_group_minerals
        else -> R.string.nutrient_group_vitamins
    }

    /** The number of a stored [value] of [f] without the unit: "12.5", "460", "250". */
    fun number(f: Field, value: Int): String = when {
        f == Field.KCAL -> Fmt.number(FoodRows.kcalOfX10(value).toLong())
        f.scale == 10 -> Fmt.x100(value * 10L, 1)
        else -> Fmt.number(value.toLong())
    }

    /**
     * A typed amount of [f] in its display unit ("12.5", "1,500") as the stored value (×10 for `X10`
     * fields, kcal ×10 for calories), rounded half up; null when it does not parse or is negative.
     */
    fun parse(f: Field, text: String): Int? {
        val x100 = Fmt.parseX100(text.trim()) ?: return null
        if (x100 < 0) return null
        // x100 is the display value ×100; stored = display × scale.
        val stored = (x100 * f.scale + 50) / 100
        return stored.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** A stored [value] of [f] as editable text in its display unit, without grouping ("1500", "12.5"), so [parse] reads it back. */
    fun editText(f: Field, value: Int): String {
        val nf = java.text.NumberFormat.getNumberInstance()
        nf.isGroupingUsed = false
        nf.maximumFractionDigits = if (f.scale == 10) 1 else 0
        return nf.format(value / f.scale.toDouble())
    }

    /** "12.5 g", "460 mg", "250 kcal", or "—" when unknown. */
    fun amount(ctx: Context, f: Field, value: Int?): String =
        if (value == null) ctx.getString(R.string.dash) else ctx.getString(R.string.nutrient_amount, number(f, value), f.unit)

    /** The lower-case allergen word ("milk", "tree nuts"). */
    @StringRes
    fun allergen(a: Allergen): Int = when (a) {
        Allergen.GLUTEN -> R.string.allergen_gluten
        Allergen.MILK -> R.string.allergen_milk
        Allergen.EGG -> R.string.allergen_egg
        Allergen.PEANUT -> R.string.allergen_peanut
        Allergen.TREE_NUT -> R.string.allergen_tree_nut
        Allergen.SOY -> R.string.allergen_soy
        Allergen.FISH -> R.string.allergen_fish
        Allergen.SHELLFISH -> R.string.allergen_shellfish
        Allergen.SESAME -> R.string.allergen_sesame
    }

    /** The capitalized allergen name for check boxes ("Tree nuts"). */
    @StringRes
    fun allergenName(a: Allergen): Int = when (a) {
        Allergen.GLUTEN -> R.string.allergen_name_gluten
        Allergen.MILK -> R.string.allergen_name_milk
        Allergen.EGG -> R.string.allergen_name_egg
        Allergen.PEANUT -> R.string.allergen_name_peanut
        Allergen.TREE_NUT -> R.string.allergen_name_tree_nut
        Allergen.SOY -> R.string.allergen_name_soy
        Allergen.FISH -> R.string.allergen_name_fish
        Allergen.SHELLFISH -> R.string.allergen_name_shellfish
        Allergen.SESAME -> R.string.allergen_name_sesame
    }

    /** The name of [d] ("Heart-healthy"). */
    @StringRes
    fun dietName(d: Diet): Int = when (d) {
        Diet.HIGH_PROTEIN -> R.string.diet_high_protein
        Diet.HEART_HEALTHY -> R.string.diet_heart_healthy
        Diet.VEGETARIAN -> R.string.diet_vegetarian
        Diet.VEGAN -> R.string.diet_vegan
        Diet.GLUTEN_FREE -> R.string.diet_gluten_free
    }

    /** "Vegan, heart-healthy · no peanuts" style summary of [p], or "None set". */
    fun prefsSummary(ctx: Context, p: DietPrefs): String {
        if (p.isEmpty) return ctx.getString(R.string.goal_diet_none)
        val parts = Diet.entries.filter { it in p.diets }.map { ctx.getString(dietName(it)) } +
            Allergen.entries.filter { it in p.allergens }.map { ctx.getString(allergenName(it)) }
        return parts.joinToString(ctx.getString(R.string.diet_list_separator))
    }

    /** The short red badge text for a conflict: "Not vegan", "Contains gluten". */
    fun conflictText(ctx: Context, c: DietConflict): String = when (c) {
        DietConflict.NotVegan -> ctx.getString(R.string.diet_badge_not_vegan)
        DietConflict.NotVegetarian -> ctx.getString(R.string.diet_badge_not_vegetarian)
        is DietConflict.Contains -> ctx.getString(R.string.diet_contains_one, ctx.getString(allergen(c.allergen)))
    }

    /** One diet badge: its [text] and whether it clashes with the user's diet. */
    data class Badge(val text: String, val conflict: Boolean)

    /**
     * The diet line of a food: what is known to be true ("Vegan", "Gluten-free"…), then "Contains: milk,
     * soy". Known "no" answers only show when they clash with the user's diet ("Not vegan" for a vegan).
     * Unknown shows nothing. [conflicts] are the food's clashes with the user's diet.
     */
    fun badges(ctx: Context, info: DietInfo, conflicts: List<DietConflict>): List<Badge> {
        val out = ArrayList<Badge>()
        when {
            info.vegan == true -> out.add(Badge(ctx.getString(R.string.diet_badge_vegan), false))
            info.vegetarian == true -> out.add(Badge(ctx.getString(R.string.diet_badge_vegetarian), false))
        }
        if (DietConflict.NotVegetarian in conflicts) out.add(Badge(ctx.getString(R.string.diet_badge_not_vegetarian), true))
        if (DietConflict.NotVegan in conflicts) out.add(Badge(ctx.getString(R.string.diet_badge_not_vegan), true))
        if (info.glutenFreeLabel) {
            out.add(Badge(ctx.getString(R.string.diet_badge_gluten_free), false))
        } else if (info.glutenFreeNatural) {
            out.add(Badge(ctx.getString(R.string.diet_badge_natural_gluten_free), false))
        }
        val contains = info.contains
        if (contains.isNotEmpty()) {
            val clash = conflicts.filterIsInstance<DietConflict.Contains>().map { it.allergen }.toSet()
            val words = contains.joinToString(ctx.getString(R.string.diet_list_separator)) { ctx.getString(allergen(it)) }
            out.add(Badge(ctx.getString(R.string.diet_contains, words), contains.any { it in clash }))
        }
        return out
    }

    /** The fill color of a bar for [status]: primary when a goal is met, the softened red near a limit, red over it. */
    @ColorInt
    fun statusColor(ctx: Context, status: TargetStatus): Int {
        val p = ChartPalette.of(ctx)
        return when (status) {
            TargetStatus.OVER -> p.alarm
            TargetStatus.NEAR_LIMIT -> caution(p)
            TargetStatus.MET -> p.primary
            TargetStatus.UNDER_LIMIT, TargetStatus.BELOW, TargetStatus.NONE, TargetStatus.UNKNOWN -> p.secondary
        }
    }

    /** The "near a limit" color: the alarm red softened towards the surface (amber role, theme safe). */
    @ColorInt
    fun caution(p: ChartPalette): Int = SwContrast.mix(p.alarm, p.colors.neutrals.surface, CAUTION_MIX) or OPAQUE

    /** Text in the alarm red, readable on the surface. */
    @ColorInt
    fun alarmText(ctx: Context): Int {
        val p = ChartPalette.of(ctx)
        return ThemeColors.ensureAll(p.alarm, intArrayOf(p.colors.neutrals.surface), SwContrast.TEXT_MIN)
    }

    /** The macro split of [n]: carbs, fat and protein as shares of their calories (whole percents), or null. */
    fun macroSplit(n: Nutrients?): Triple<Int, Int, Int>? {
        val c = (n?.carbX10 ?: 0) * 4.0
        val f = (n?.fatX10 ?: 0) * 9.0
        val p = (n?.proteinX10 ?: 0) * 4.0
        val total = c + f + p
        if (total <= 0.0) return null
        val cp = Math.round(c * 100 / total).toInt()
        val fp = Math.round(f * 100 / total).toInt()
        return Triple(cp, fp, (100 - cp - fp).coerceAtLeast(0))
    }

    private const val CAUTION_MIX = 0.35
    private const val OPAQUE = 0xFF shl 24
}

/** A small pill for a diet badge: neutral, or red when it clashes with the user's diet. */
class DietBadgeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatTextView(context, attrs) {
    private val pill = GradientDrawable()

    /** Red when true. */
    var conflict: Boolean = false
        set(value) {
            field = value
            applyColors()
        }

    init {
        TextViewCompat.setTextAppearance(this, UiR.style.TextAppearance_SelfWatch_Caption)
        val h = (6 * resources.displayMetrics.density).toInt()
        val v = (2 * resources.displayMetrics.density).toInt()
        setPaddingRelative(h, v, h, v)
        background = pill
        applyColors()
    }

    private fun applyColors() {
        val p = ChartPalette.of(context)
        if (conflict) {
            val fill = SwContrast.mix(p.alarm, p.colors.neutrals.surface, CONFLICT_FILL_MIX) or (0xFF shl 24)
            pill.setColor(fill)
            setTextColor(ThemeColors.ensureAll(p.alarm, intArrayOf(fill), SwContrast.TEXT_MIN))
        } else {
            pill.setColor(p.neutralTagFill)
            setTextColor(p.neutralTagText)
        }
    }

    private companion object {
        const val CONFLICT_FILL_MIX = 0.82
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pill.cornerRadius = minOf(h / 2f, 12 * resources.displayMetrics.density)
    }
}

/** A slim bar: [fraction] of the track filled in [color] (capped at the end), drawn only. */
class NutrientBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ChartPalette.of(context).track }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fraction = 0f

    /** Shows [fraction] (0..1, more is capped) in [color]. */
    fun set(fraction: Float, @ColorInt color: Int) {
        this.fraction = fraction.coerceIn(0f, 1f)
        fill.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (HEIGHT_DP * density).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left) return
        val r = (bottom - top) / 2
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, r, r, track)
        if (fraction > 0f) {
            rect.set(left, top, maxOf(left + (right - left) * fraction, left + 2 * r), bottom)
            canvas.drawRoundRect(rect, r, r, fill)
        }
    }

    private companion object {
        const val HEIGHT_DP = 8
    }
}

/** Three joined segments (carbs, fat, protein) sized by their share of the calories. */
class MacroSplitView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val paints = Array(3) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shares = intArrayOf(0, 0, 0)

    init {
        val p = ChartPalette.of(context)
        paints[0].color = p.series(0)
        paints[1].color = p.series(1)
        paints[2].color = p.series(2)
        trackPaint.color = p.track
    }

    /** Sets the three percents (carbs, fat, protein). */
    fun set(carbs: Int, fat: Int, protein: Int) {
        shares = intArrayOf(carbs, fat, protein)
        invalidate()
    }

    /** The color of segment [i] (0 carbs, 1 fat, 2 protein), for a legend. */
    @ColorInt
    fun color(i: Int): Int = paints[i].color

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (HEIGHT_DP * density).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val w = right - left
        if (w <= 0f) return
        val r = (bottom - top) / 2
        rect.set(left, top, right, bottom)
        val total = shares.sum()
        if (total <= 0) {
            canvas.drawRoundRect(rect, r, r, trackPaint)
            return
        }
        val gap = 2 * density
        var x = left
        canvas.save()
        val clip = android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        for (i in 0 until 3) {
            if (shares[i] <= 0) continue
            val segW = w * shares[i] / total
            rect.set(x, top, x + segW - if (x + segW < right - 0.5f) gap else 0f, bottom)
            canvas.drawRect(rect, paints[i])
            x += segW
        }
        canvas.restore()
    }

    private companion object {
        const val HEIGHT_DP = 12
    }
}
