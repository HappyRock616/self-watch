/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.recipe

import app.selfwatch.interop.io.JsonBuild
import app.selfwatch.interop.io.Numbers
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.RecipeItem
import app.selfwatch.model.food.RecipeKind
import app.selfwatch.model.json.JsonValue

/** Shared helpers for the recipe readers. */
object RecipeSupport {
    /** Most ingredient lines kept per recipe (guards against hostile files). */
    const val MAX_ITEMS: Int = 500

    /**
     * Builds an imported recipe: id 0, kind RECIPE, one serving when [servings] is unknown, and one
     * UNMATCHED item per non-blank line of [lines] (kept verbatim, trimmed) unless [items] are given.
     */
    fun recipe(
        name: String,
        lines: List<String>,
        servings: Double? = null,
        servingLabel: String? = null,
        prepMin: Int? = null,
        cookMin: Int? = null,
        steps: String? = null,
        notes: String? = null,
        sourceName: String? = null,
        sourceUrl: String? = null,
        stated: Nutrients? = null,
        tags: Collection<String> = emptyList(),
        items: List<RecipeItem>? = null,
    ): Recipe = Recipe(
        id = 0,
        kind = RecipeKind.RECIPE,
        name = name.trim().take(500),
        servingsX10 = servings?.takeIf { it > 0 && it < 100_000 }?.let { Numbers.scaled(it, 10) }?.takeIf { it > 0 } ?: 10,
        servingLabel = servingLabel?.trim()?.takeIf { it.isNotEmpty() },
        prepMin = prepMin?.takeIf { it >= 0 },
        cookMin = cookMin?.takeIf { it >= 0 },
        steps = steps?.trim()?.takeIf { it.isNotEmpty() },
        notes = notes?.trim()?.takeIf { it.isNotEmpty() },
        sourceName = sourceName?.trim()?.takeIf { it.isNotEmpty() },
        sourceUrl = sourceUrl?.trim()?.takeIf { it.isNotEmpty() },
        statedPerServing = stated?.takeIf { it != Nutrients.UNKNOWN },
        tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet()),
        items = (items ?: lines.map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, l -> item(i + 1, l) }).take(MAX_ITEMS),
        importId = null,
    )

    /** An UNMATCHED item for [line] with an optional parsed amount and unit. */
    fun item(seq: Int, line: String, amount: Double? = null, unit: String? = null): RecipeItem =
        RecipeItem(seq, line, null, amount?.let { Numbers.scaled(it, 100) }, unit?.takeIf { it.isNotBlank() }, null, MatchState.UNMATCHED)

    private val ISO_DURATION = Regex(
        """P(?:(\d+(?:\.\d+)?)Y)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)W)?(?:(\d+(?:\.\d+)?)D)?""" +
            """(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?""",
        RegexOption.IGNORE_CASE,
    )
    private val FREE_DURATION = Regex("""(\d+(?:[.,]\d+)?)\s*(d|days?|h|hrs?|hours?|m|mins?|minutes?|s|secs?|seconds?)?\b""", RegexOption.IGNORE_CASE)

    /** Minutes in an ISO 8601 duration (`PT1H30M`) or free text (`1 hr 30 mins`, `45`), or null. */
    fun minutes(text: String?): Int? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        ISO_DURATION.matchEntire(t)?.let { m ->
            if (m.groupValues.drop(1).all { it.isEmpty() }) return null
            fun g(i: Int): Double = m.groupValues[i].toDoubleOrNull() ?: 0.0
            val min = g(4) * 1440 + g(3) * 7 * 1440 + g(5) * 60 + g(6) + g(7) / 60
            return Numbers.scaled(min, 1).takeIf { it in 0..100_000 }
        }
        var total = 0.0
        var any = false
        for (m in FREE_DURATION.findAll(t)) {
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val u = m.groupValues[2].lowercase()
            total += when {
                u.startsWith("d") -> v * 1440
                u.startsWith("h") -> v * 60
                u.startsWith("s") -> v / 60
                else -> v
            }
            any = true
        }
        return if (any) Numbers.scaled(total, 1).takeIf { it in 0..100_000 } else null
    }

    /** ISO 8601 duration for [min] minutes, for example `PT1H30M`. */
    fun isoDuration(min: Int): String {
        val h = min / 60
        val m = min % 60
        return "PT" + (if (h > 0) "${h}H" else "") + (if (m > 0 || h == 0) "${m}M" else "")
    }

    private val TAGS = Regex("""<[^>]*>""")
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "deg" to "°", "frac12" to "½")

    /** [s] with HTML character references decoded. */
    fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
            else -> NAMED[e.lowercase()] ?: m.value
        }
    }

    private fun codePoint(cp: Int): String? =
        if (Character.isValidCodePoint(cp) && cp != 0) String(Character.toChars(cp)) else null

    /** [s] as plain text: `<br>`, `</p>` and `</li>` become line breaks, other tags go, entities are decoded. */
    fun htmlToText(s: String): String {
        val withBreaks = s.replace(Regex("""<\s*br\s*/?\s*>|</\s*(p|li|div|h\d)\s*>""", RegexOption.IGNORE_CASE), "\n")
        return decodeEntities(withBreaks.replace(TAGS, "")).lines().joinToString("\n") { it.trim() }.trim()
    }

    /** Servings from a yield such as `4`, `4 servings`, `Serves 6`, `12 cookies`; and its label. */
    fun servings(text: String?): Pair<Double?, String?> {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
        val m = Regex("""(\d+(?:[.,]\d+)?)""").find(t) ?: return null to t
        val n = m.value.replace(',', '.').toDoubleOrNull()
        val rest = t.substring(m.range.last + 1).trim().trimStart('-', ' ').trim()
        return n to rest.ifEmpty { null }
    }

    /**
     * Nutrients from schema.org-style fields: values are numbers or strings like `250 kcal`, `10 g`,
     * `300 mg`. [keys] maps each nutrient to the field names to try.
     */
    fun nutrients(obj: JsonValue?, keys: NutritionKeys = NutritionKeys.SCHEMA_ORG): Nutrients? {
        if (obj !is JsonValue.Obj) return null
        fun value(names: List<String>): Pair<Double, String>? {
            for (n in names) {
                val text = JsonBuild.text(obj[n])?.trim() ?: continue
                val q = Numbers.leadingQuantity(text.replace(",", ".")) ?: continue
                return q.first to q.second.lowercase()
            }
            return null
        }
        fun mass(names: List<String>): Int? = value(names)?.let { (v, u) ->
            when {
                u.startsWith("mg") -> Numbers.scaled(v / 100, 1)
                u.startsWith("mcg") || u.startsWith("µg") -> Numbers.scaled(v / 100_000, 1)
                else -> Numbers.scaled(v, 10)
            }
        }
        fun mg(names: List<String>, defaultGrams: Boolean): Int? = value(names)?.let { (v, u) ->
            when {
                u.startsWith("mg") -> Numbers.scaled(v, 1)
                u.startsWith("g") || (u.isEmpty() && defaultGrams) -> Numbers.scaled(v, 1000)
                else -> Numbers.scaled(v, 1)
            }
        }
        val kcal = value(keys.kcal)?.let { (v, u) -> if (u.startsWith("kj")) Numbers.scaled(v / 4.184, 10) else Numbers.scaled(v, 10) }
        val n = Nutrients(
            kcalX10 = kcal,
            proteinX10 = mass(keys.protein),
            fatX10 = mass(keys.fat),
            satfatX10 = mass(keys.satfat),
            carbX10 = mass(keys.carb),
            sugarX10 = mass(keys.sugar),
            fibreX10 = mass(keys.fibre),
            sodiumMg = mg(keys.sodium, keys.sodiumDefaultGrams),
            cholesterolMg = mg(keys.cholesterol, false),
        )
        return n.takeIf { it != Nutrients.UNKNOWN }
    }

    /** Text of a JSON string, number, or a list/objects of them (names), joined by [sep]. */
    fun textOf(v: JsonValue?, sep: String = ", "): String? = when (v) {
        null, JsonValue.Null -> null
        is JsonValue.Str -> v.v
        is JsonValue.Num -> v.text
        is JsonValue.Arr -> v.items.mapNotNull { textOf(it, sep) }.filter { it.isNotBlank() }.joinToString(sep).ifEmpty { null }
        is JsonValue.Obj -> textOf(v["name"] ?: v["text"] ?: v["@value"], sep)
        is JsonValue.Bool -> null
    }

    /** A list of strings from a string (split on [splitOn]), an array, or objects with `name`. */
    fun strings(v: JsonValue?, splitOn: Regex = Regex(",")): List<String> = when (v) {
        is JsonValue.Str -> v.v.split(splitOn).map { decodeEntities(it).trim() }.filter { it.isNotEmpty() }
        is JsonValue.Arr -> v.items.flatMap { strings(it, splitOn) }
        is JsonValue.Obj -> listOfNotNull(textOf(v["name"])?.trim()?.takeIf { it.isNotEmpty() })
        is JsonValue.Num -> listOf(v.text)
        else -> emptyList()
    }

    /** Number with trailing zeros trimmed (`2.000` → `2`, `0.50` → `0.5`). */
    fun trimNumber(s: String): String = Numbers.parseDecimal(s)?.let { Numbers.format(it, 3) } ?: s
}

/** Field names for nutrients in a recipe format's nutrition object. */
class NutritionKeys(
    val kcal: List<String>,
    val protein: List<String>,
    val fat: List<String>,
    val satfat: List<String>,
    val carb: List<String>,
    val sugar: List<String>,
    val fibre: List<String>,
    val sodium: List<String>,
    val cholesterol: List<String>,
    /** True when a sodium value without a unit is in grams. */
    val sodiumDefaultGrams: Boolean = false,
) {
    /** Known layouts. */
    companion object {
        /** schema.org NutritionInformation (also Mealie's API JSON). */
        val SCHEMA_ORG: NutritionKeys = NutritionKeys(
            kcal = listOf("calories"),
            protein = listOf("proteinContent"),
            fat = listOf("fatContent"),
            satfat = listOf("saturatedFatContent"),
            carb = listOf("carbohydrateContent"),
            sugar = listOf("sugarContent"),
            fibre = listOf("fiberContent"),
            sodium = listOf("sodiumContent"),
            cholesterol = listOf("cholesterolContent"),
        )

        /** Mealie's database tables (snake case). */
        val SNAKE: NutritionKeys = NutritionKeys(
            kcal = listOf("calories"),
            protein = listOf("protein_content"),
            fat = listOf("fat_content"),
            satfat = listOf("saturated_fat_content"),
            carb = listOf("carbohydrate_content"),
            sugar = listOf("sugar_content"),
            fibre = listOf("fiber_content"),
            sodium = listOf("sodium_content"),
            cholesterol = listOf("cholesterol_content"),
        )

        /** Tandoor's nutrition object. */
        val TANDOOR: NutritionKeys = NutritionKeys(
            kcal = listOf("calories"),
            protein = listOf("proteins"),
            fat = listOf("fats"),
            satfat = emptyList(),
            carb = listOf("carbohydrates"),
            sugar = emptyList(),
            fibre = emptyList(),
            sodium = emptyList(),
            cholesterol = emptyList(),
        )
    }
}
