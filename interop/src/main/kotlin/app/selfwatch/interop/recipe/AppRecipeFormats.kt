/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.recipe

import app.selfwatch.interop.io.Texts
import app.selfwatch.interop.io.ZipItem
import app.selfwatch.interop.io.Zips
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.result.Outcome

private fun parseJson(bytes: ByteArray): JsonValue? = (Json.parse(Texts.decode(bytes)) as? Outcome.Ok)?.value

/**
 * Paprika's `.paprikarecipes` export: a zip of `.paprikarecipe` entries, each a gzipped JSON object
 * with `name`, `ingredients` and `directions` (newline-separated text), `servings`, `prep_time`,
 * `cook_time`, `notes`, `nutritional_info` (free text, kept in the notes), `source`, `source_url` and
 * `categories`. A single `.paprikarecipe` file is read too.
 */
object PaprikaReader {
    /** Recipes in [bytes] (the archive or one gzipped recipe). */
    fun read(bytes: ByteArray): List<Recipe> {
        val entries = if (Zips.isZip(bytes)) Zips.readAll(bytes).map { it.bytes } else listOf(bytes)
        return entries.mapNotNull { e ->
            val json = if (Zips.isGzip(e)) Zips.gunzip(e) else e
            (parseJson(json) as? JsonValue.Obj)?.let { toRecipe(it) }
        }
    }

    /** One Paprika recipe object. */
    fun toRecipe(o: JsonValue.Obj): Recipe? {
        val name = RecipeSupport.textOf(o["name"])?.takeIf { it.isNotBlank() } ?: return null
        val (servings, label) = RecipeSupport.servings(RecipeSupport.textOf(o["servings"]))
        val nutrition = RecipeSupport.textOf(o["nutritional_info"])?.trim()?.takeIf { it.isNotEmpty() }
        val notes = listOfNotNull(
            RecipeSupport.textOf(o["description"])?.takeIf { it.isNotBlank() },
            RecipeSupport.textOf(o["notes"])?.takeIf { it.isNotBlank() },
            nutrition?.let { "Nutrition: $it" },
        ).joinToString("\n\n").ifEmpty { null }
        return RecipeSupport.recipe(
            name = name,
            lines = RecipeSupport.textOf(o["ingredients"]).orEmpty().lines(),
            servings = servings,
            servingLabel = label,
            prepMin = RecipeSupport.minutes(RecipeSupport.textOf(o["prep_time"])),
            cookMin = RecipeSupport.minutes(RecipeSupport.textOf(o["cook_time"])),
            steps = RecipeSupport.textOf(o["directions"])?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString("\n"),
            notes = notes,
            sourceName = RecipeSupport.textOf(o["source"]),
            sourceUrl = RecipeSupport.textOf(o["source_url"]),
            tags = RecipeSupport.strings(o["categories"]),
        )
    }
}

/**
 * Mealie backups and exports, in either shape:
 * - a v1 backup zip with `database.json` holding tables (`recipes`, `recipes_ingredients`,
 *   `recipe_instructions`, `recipe_nutrition`, `tags` + `recipes_to_tags`, `categories` +
 *   `recipes_to_categories`, `ingredient_units`, `ingredient_foods`), joined by recipe id;
 * - per-recipe JSON files in Mealie's API shape (`recipeIngredient` objects with `originalText`,
 *   `display` or `note`; `recipeInstructions` with `text`; `nutrition` with schema.org names).
 * Table and field names are from public descriptions, so every field is optional.
 */
object MealieReader {
    /** Recipes in the zip [bytes] (or a single JSON file). */
    fun read(bytes: ByteArray): List<Recipe> {
        val files = if (Zips.isZip(bytes)) Zips.readAll(bytes) else listOf(ZipItem("recipe.json", bytes))
        val out = ArrayList<Recipe>()
        for (f in files) {
            if (Texts.extension(f.name) != "json") continue
            val v = parseJson(f.bytes) ?: continue
            when {
                v["recipes"] is JsonValue.Arr -> out.addAll(fromDatabase(v))
                v is JsonValue.Obj && v["name"] != null && (v["recipeIngredient"] != null || v["recipe_ingredient"] != null) ->
                    fromApi(v)?.let { out.add(it) }
                v is JsonValue.Arr -> v.items.filterIsInstance<JsonValue.Obj>().mapNotNullTo(out) { fromApi(it) }
            }
        }
        return out
    }

    private fun rows(db: JsonValue, table: String): List<JsonValue.Obj> = db[table]?.asList?.filterIsInstance<JsonValue.Obj>().orEmpty()

    private fun idOf(v: JsonValue?): String? = RecipeSupport.textOf(v)

    private fun position(o: JsonValue.Obj): Double = o["position"]?.asDouble ?: 0.0

    private fun fromDatabase(db: JsonValue): List<Recipe> {
        val units = rows(db, "ingredient_units").associate { idOf(it["id"]) to RecipeSupport.textOf(it["name"]) }
        val foods = rows(db, "ingredient_foods").associate { idOf(it["id"]) to RecipeSupport.textOf(it["name"]) }
        val ingredients = rows(db, "recipes_ingredients").groupBy { idOf(it["recipe_id"]) }
        val steps = rows(db, "recipe_instructions").groupBy { idOf(it["recipe_id"]) }
        val nutrition = rows(db, "recipe_nutrition").associateBy { idOf(it["recipe_id"]) }
        val tagNames = rows(db, "tags").associate { idOf(it["id"]) to RecipeSupport.textOf(it["name"]) }
        val catNames = rows(db, "categories").associate { idOf(it["id"]) to RecipeSupport.textOf(it["name"]) }
        val tagLinks = rows(db, "recipes_to_tags").groupBy({ idOf(it["recipe_id"]) }, { tagNames[idOf(it["tag_id"])] })
        val catLinks = rows(db, "recipes_to_categories").groupBy({ idOf(it["recipe_id"]) }, { catNames[idOf(it["category_id"])] })
        return rows(db, "recipes").mapNotNull { r ->
            val id = idOf(r["id"])
            val name = RecipeSupport.textOf(r["name"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lines = ingredients[id].orEmpty().sortedBy { position(it) }.map { ing ->
                val title = RecipeSupport.textOf(ing["title"])?.takeIf { it.isNotBlank() }
                val text = RecipeSupport.textOf(ing["original_text"])?.takeIf { it.isNotBlank() } ?: listOfNotNull(
                    RecipeSupport.textOf(ing["quantity"])?.let { RecipeSupport.trimNumber(it) }?.takeIf { it != "0" },
                    units[idOf(ing["unit_id"])],
                    foods[idOf(ing["food_id"])],
                    RecipeSupport.textOf(ing["note"])?.takeIf { it.isNotBlank() },
                ).joinToString(" ")
                if (title != null) "$title:\n$text" else text
            }.flatMap { it.lines() }
            val stepLines = steps[id].orEmpty().sortedBy { position(it) }.flatMap { s ->
                listOfNotNull(RecipeSupport.textOf(s["title"])?.takeIf { it.isNotBlank() }?.let { "$it:" }) +
                    RecipeSupport.textOf(s["text"]).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
            }
            val (servings, label) = RecipeSupport.servings(RecipeSupport.textOf(r["recipe_yield"]) ?: RecipeSupport.textOf(r["recipe_servings"]))
            RecipeSupport.recipe(
                name = name,
                lines = lines,
                servings = servings,
                servingLabel = label,
                prepMin = RecipeSupport.minutes(RecipeSupport.textOf(r["prep_time"])),
                cookMin = RecipeSupport.minutes(RecipeSupport.textOf(r["perform_time"]) ?: RecipeSupport.textOf(r["cook_time"])),
                steps = stepLines.joinToString("\n"),
                notes = RecipeSupport.textOf(r["description"]),
                sourceUrl = RecipeSupport.textOf(r["org_url"]),
                stated = RecipeSupport.nutrients(nutrition[id], NutritionKeys.SNAKE),
                tags = (tagLinks[id].orEmpty() + catLinks[id].orEmpty()).filterNotNull(),
            )
        }
    }

    /** One recipe in Mealie's API shape. */
    fun fromApi(o: JsonValue.Obj): Recipe? {
        val name = RecipeSupport.textOf(o["name"])?.takeIf { it.isNotBlank() } ?: return null
        val ingredients = (o["recipeIngredient"] ?: o["recipe_ingredient"])?.asList.orEmpty().map { ing ->
            if (ing is JsonValue.Obj) {
                RecipeSupport.textOf(ing["originalText"])?.takeIf { it.isNotBlank() }
                    ?: RecipeSupport.textOf(ing["display"])?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(
                        RecipeSupport.textOf(ing["quantity"])?.let { RecipeSupport.trimNumber(it) }?.takeIf { it != "0" },
                        RecipeSupport.textOf(ing["unit"]),
                        RecipeSupport.textOf(ing["food"]),
                        RecipeSupport.textOf(ing["note"])?.takeIf { it.isNotBlank() },
                    ).joinToString(" ")
            } else {
                RecipeSupport.textOf(ing).orEmpty()
            }
        }
        val steps = (o["recipeInstructions"] ?: o["recipe_instructions"])?.asList.orEmpty().flatMap { s ->
            val title = (s as? JsonValue.Obj)?.let { RecipeSupport.textOf(it["title"]) }?.takeIf { it.isNotBlank() }
            val text = if (s is JsonValue.Obj) RecipeSupport.textOf(s["text"]) else RecipeSupport.textOf(s)
            listOfNotNull(title?.let { "$it:" }) + text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        val (servings, label) = RecipeSupport.servings(RecipeSupport.textOf(o["recipeYield"]) ?: RecipeSupport.textOf(o["recipeServings"]))
        return RecipeSupport.recipe(
            name = name,
            lines = ingredients,
            servings = servings,
            servingLabel = label,
            prepMin = RecipeSupport.minutes(RecipeSupport.textOf(o["prepTime"])),
            cookMin = RecipeSupport.minutes(RecipeSupport.textOf(o["performTime"]) ?: RecipeSupport.textOf(o["cookTime"])),
            steps = steps.joinToString("\n"),
            notes = RecipeSupport.textOf(o["description"]),
            sourceUrl = RecipeSupport.textOf(o["orgURL"]) ?: RecipeSupport.textOf(o["org_url"]),
            stated = RecipeSupport.nutrients(o["nutrition"]),
            tags = RecipeSupport.strings(o["tags"]) + RecipeSupport.strings(o["recipeCategory"]),
        )
    }
}

/**
 * Tandoor's export: a zip holding one zip per recipe (each with `recipe.json` and maybe an image), or
 * `recipe.json` files directly. `recipe.json` has `name`, `description`, `keywords` (objects with
 * `name`), `steps` (each with `name`, `instruction` and `ingredients` of `amount`, `unit.name`,
 * `food.name`, `note`, `original_text`), `working_time`, `waiting_time`, `servings`, `servings_text`,
 * `source_url` and `nutrition` (`calories`, `proteins`, `fats`, `carbohydrates`).
 */
object TandoorReader {
    /** Recipes in [bytes]. */
    fun read(bytes: ByteArray): List<Recipe> {
        val out = ArrayList<Recipe>()
        val top = if (Zips.isZip(bytes)) Zips.readAll(bytes) else listOf(ZipItem("recipe.json", bytes))
        for (item in top) {
            if (Zips.isZip(item.bytes)) {
                // One level of nesting only: recipe zips inside the export zip.
                for (inner in Zips.readAll(item.bytes)) {
                    if (Texts.baseName(inner.name).equals("recipe.json", ignoreCase = true)) toRecipe(inner.bytes)?.let { out.add(it) }
                }
            } else if (Texts.extension(item.name) == "json") {
                toRecipe(item.bytes)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun toRecipe(bytes: ByteArray): Recipe? = (parseJson(bytes) as? JsonValue.Obj)?.let { fromJson(it) }

    /** One `recipe.json` object. */
    fun fromJson(o: JsonValue.Obj): Recipe? {
        val name = RecipeSupport.textOf(o["name"])?.takeIf { it.isNotBlank() } ?: return null
        val lines = ArrayList<String>()
        val stepLines = ArrayList<String>()
        for (s in o["steps"]?.asList.orEmpty().filterIsInstance<JsonValue.Obj>().sortedBy { it["order"]?.asDouble ?: 0.0 }) {
            val title = RecipeSupport.textOf(s["name"])?.takeIf { it.isNotBlank() }
            title?.let { stepLines.add("$it:") }
            stepLines.addAll(RecipeSupport.textOf(s["instruction"]).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() })
            for (ing in s["ingredients"]?.asList.orEmpty().filterIsInstance<JsonValue.Obj>().sortedBy { it["order"]?.asDouble ?: 0.0 }) {
                val original = RecipeSupport.textOf(ing["original_text"])?.takeIf { it.isNotBlank() }
                val line = original ?: if (ing["is_header"]?.asBoolean == true) {
                    RecipeSupport.textOf(ing["note"]).orEmpty()
                } else {
                    val noAmount = ing["no_amount"]?.asBoolean == true
                    listOfNotNull(
                        if (noAmount) null else RecipeSupport.textOf(ing["amount"])?.let { RecipeSupport.trimNumber(it) }?.takeIf { it != "0" },
                        RecipeSupport.textOf(ing["unit"]),
                        RecipeSupport.textOf(ing["food"]),
                        RecipeSupport.textOf(ing["note"])?.takeIf { it.isNotBlank() },
                    ).joinToString(" ")
                }
                lines.add(line)
            }
        }
        val servings = o["servings"]?.asDouble
        return RecipeSupport.recipe(
            name = name,
            lines = lines,
            servings = servings,
            servingLabel = RecipeSupport.textOf(o["servings_text"]),
            prepMin = o["working_time"]?.asLong?.toInt()?.takeIf { it > 0 },
            cookMin = o["waiting_time"]?.asLong?.toInt()?.takeIf { it > 0 },
            steps = stepLines.joinToString("\n"),
            notes = RecipeSupport.textOf(o["description"]),
            sourceUrl = RecipeSupport.textOf(o["source_url"]),
            stated = RecipeSupport.nutrients(o["nutrition"], NutritionKeys.TANDOOR),
            tags = RecipeSupport.strings(o["keywords"]),
        )
    }
}

