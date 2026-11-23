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
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.result.Outcome

/**
 * Reads and writes schema.org `Recipe` (JSON-LD), the format recipe websites embed and Nextcloud
 * Cookbook, Mealie and Tandoor exchange.
 *
 * Reading accepts a single object, an array, `@graph` containers, and recipes nested anywhere in the
 * document (up to a small depth), as `.json` or inside HTML `<script type="application/ld+json">`
 * blocks. Instructions may be a string, strings, `HowToStep`s or `HowToSection`s; nutrition is read as
 * stated per serving.
 */
object SchemaOrgRecipe {
    private const val MAX_DEPTH = 8
    private val SCRIPT = Regex(
        """<script[^>]*type\s*=\s*["']?application/ld\+json["']?[^>]*>(.*?)</script\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** Recipes in [text], which is JSON or HTML. Unparseable JSON blocks are skipped. */
    fun readText(text: String): List<Recipe> {
        val trimmed = text.trimStart(0xFEFF.toChar(), ' ', '\n', '\r', '\t')
        val blocks = if (trimmed.startsWith("{") || trimmed.startsWith("[")) listOf(trimmed) else SCRIPT.findAll(text).map { it.groupValues[1] }.toList()
        val out = ArrayList<Recipe>()
        for (b in blocks) {
            // Many sites leave raw line breaks inside strings; JSON forbids them, so turn controls into spaces.
            val cleaned = b.trim().removePrefix("<!--").removeSuffix("-->").map { if (it < ' ') ' ' else it }.joinToString("")
            val v = Json.parse(cleaned)
            if (v is Outcome.Ok) out.addAll(fromJson(v.value))
        }
        return out
    }

    /** Recipes found in [v]. */
    fun fromJson(v: JsonValue): List<Recipe> {
        val out = ArrayList<Recipe>()
        collect(v, 0, out)
        return out
    }

    private fun isRecipe(o: JsonValue.Obj): Boolean = RecipeSupport.strings(o["@type"]).any {
        val t = it.substringAfterLast('/').substringAfterLast(':')
        t.equals("Recipe", ignoreCase = true)
    }

    private fun collect(v: JsonValue, depth: Int, out: MutableList<Recipe>) {
        if (depth > MAX_DEPTH) return
        when (v) {
            is JsonValue.Arr -> v.items.forEach { collect(it, depth + 1, out) }
            is JsonValue.Obj -> if (isRecipe(v)) {
                toRecipe(v)?.let { out.add(it) }
            } else {
                v.fields.values.forEach { collect(it, depth + 1, out) }
            }
            else -> {}
        }
    }

    /** One recipe object to a [Recipe], or null without a name. */
    fun toRecipe(o: JsonValue.Obj): Recipe? {
        val name = RecipeSupport.textOf(o["name"])?.let { RecipeSupport.decodeEntities(it).trim() }?.takeIf { it.isNotEmpty() } ?: return null
        val (servings, label) = yieldOf(o["recipeYield"] ?: o["yield"])
        val prep = RecipeSupport.minutes(RecipeSupport.textOf(o["prepTime"]))
        var cook = RecipeSupport.minutes(RecipeSupport.textOf(o["cookTime"]))
        val total = RecipeSupport.minutes(RecipeSupport.textOf(o["totalTime"]))
        if (cook == null && total != null) cook = (total - (prep ?: 0)).takeIf { it > 0 }
        val ingredients = (o["recipeIngredient"] ?: o["ingredients"]).let { ingredientLines(it) }
        val tags = LinkedHashSet<String>()
        tags.addAll(RecipeSupport.strings(o["recipeCategory"]))
        tags.addAll(RecipeSupport.strings(o["recipeCuisine"]))
        tags.addAll(RecipeSupport.strings(o["keywords"]))
        val author = o["author"]?.let { a -> RecipeSupport.textOf(if (a is JsonValue.Arr) a[0] else a) }
        val url = RecipeSupport.textOf(o["url"]) ?: RecipeSupport.textOf(o["mainEntityOfPage"])?.takeIf { it.startsWith("http") }
        return RecipeSupport.recipe(
            name = name,
            lines = ingredients,
            servings = servings,
            servingLabel = label,
            prepMin = prep,
            cookMin = cook,
            steps = instructions(o["recipeInstructions"]).joinToString("\n").ifEmpty { null },
            notes = RecipeSupport.textOf(o["description"])?.let { RecipeSupport.htmlToText(it) },
            sourceName = author?.let { RecipeSupport.decodeEntities(it) },
            sourceUrl = url,
            stated = RecipeSupport.nutrients(o["nutrition"]),
            tags = tags,
        )
    }

    private fun ingredientLines(v: JsonValue?): List<String> = when (v) {
        is JsonValue.Str -> v.v.lines().map { RecipeSupport.htmlToText(it) }
        is JsonValue.Arr -> v.items.mapNotNull { RecipeSupport.textOf(it)?.let { s -> RecipeSupport.htmlToText(s) } }
        else -> emptyList()
    }

    private fun yieldOf(v: JsonValue?): Pair<Double?, String?> = when (v) {
        is JsonValue.Num -> v.toDouble() to null
        is JsonValue.Str -> RecipeSupport.servings(v.v)
        is JsonValue.Arr -> v.items.map { yieldOf(it) }.let { all ->
            val n = all.firstOrNull { it.first != null }?.first
            n to all.firstOrNull { it.second != null }?.second
        }
        else -> null to null
    }

    /** Instruction lines; a section's name comes first as `Name:`. */
    private fun instructions(v: JsonValue?, depth: Int = 0): List<String> {
        if (depth > MAX_DEPTH) return emptyList()
        return when (v) {
            is JsonValue.Str -> RecipeSupport.htmlToText(v.v).lines().map { it.trim() }.filter { it.isNotEmpty() }
            is JsonValue.Arr -> v.items.flatMap { instructions(it, depth + 1) }
            is JsonValue.Obj -> {
                val types = RecipeSupport.strings(v["@type"])
                if (types.any { it.endsWith("HowToSection") } || (v["itemListElement"] != null && v["text"] == null)) {
                    val title = RecipeSupport.textOf(v["name"])?.trim()
                    listOfNotNull(title?.let { "$it:" }) + instructions(v["itemListElement"], depth + 1)
                } else {
                    val text = RecipeSupport.textOf(v["text"]) ?: RecipeSupport.textOf(v["name"]) ?: ""
                    RecipeSupport.htmlToText(text).lines().map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
            else -> emptyList()
        }
    }

    /**
     * [r] as a schema.org Recipe object, in the shape Nextcloud Cookbook writes: `recipeYield` a whole
     * number where possible, times as ISO 8601 durations, one `HowToStep` per step line, keywords comma
     * separated, nutrition as strings with units.
     */
    fun toJson(r: Recipe): JsonValue.Obj {
        val servings = r.servingsX10 / 10.0
        val yieldValue: Any = if (r.servingsX10 % 10 == 0) r.servingsX10 / 10 else Numbers.format(servings, 1)
        val total = if (r.prepMin != null || r.cookMin != null) (r.prepMin ?: 0) + (r.cookMin ?: 0) else null
        val n = r.statedPerServing
        val nutrition = n?.let {
            JsonBuild.objNoNulls(
                "@type" to "NutritionInformation",
                "calories" to it.kcalX10?.let { v -> Numbers.formatScaled(v, 10) + " kcal" },
                "proteinContent" to it.proteinX10?.let { v -> Numbers.formatScaled(v, 10) + " g" },
                "fatContent" to it.fatX10?.let { v -> Numbers.formatScaled(v, 10) + " g" },
                "saturatedFatContent" to it.satfatX10?.let { v -> Numbers.formatScaled(v, 10) + " g" },
                "carbohydrateContent" to it.carbX10?.let { v -> Numbers.formatScaled(v, 10) + " g" },
                "sugarContent" to it.sugarX10?.let { v -> Numbers.formatScaled(v, 10) + " g" },
                "fiberContent" to it.fibreX10?.let { v -> Numbers.formatScaled(v, 10) + " g" },
                "sodiumContent" to it.sodiumMg?.let { v -> "$v mg" },
                "cholesterolContent" to it.cholesterolMg?.let { v -> "$v mg" },
            )
        }
        return JsonBuild.objNoNulls(
            "@context" to "https://schema.org",
            "@type" to "Recipe",
            "name" to r.name,
            "description" to r.notes,
            "recipeYield" to yieldValue,
            "prepTime" to r.prepMin?.let { RecipeSupport.isoDuration(it) },
            "cookTime" to r.cookMin?.let { RecipeSupport.isoDuration(it) },
            "totalTime" to total?.let { RecipeSupport.isoDuration(it) },
            "recipeIngredient" to r.items.sortedBy { it.seq }.map { it.line },
            "recipeInstructions" to r.steps?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.map { JsonBuild.obj("@type" to "HowToStep", "text" to it) }.orEmpty(),
            "keywords" to r.tags.takeIf { it.isNotEmpty() }?.joinToString(","),
            "url" to r.sourceUrl,
            "author" to r.sourceName?.let { JsonBuild.obj("@type" to "Person", "name" to it) },
            "nutrition" to nutrition,
        )
    }
}
