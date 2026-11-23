/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.recipe

import app.selfwatch.interop.api.BaseImporter
import app.selfwatch.interop.api.DateFormatGuess
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.ExportResult
import app.selfwatch.interop.api.ExportSource
import app.selfwatch.interop.api.Exporter
import app.selfwatch.interop.api.ImportData
import app.selfwatch.interop.api.ImportPlan
import app.selfwatch.interop.api.ImportSink
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.interop.api.UnitGuess
import app.selfwatch.interop.diary.createdDate
import app.selfwatch.interop.diary.writeTo
import app.selfwatch.interop.io.Numbers
import app.selfwatch.interop.io.Texts
import app.selfwatch.interop.io.Zips
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.json.Json
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError

/**
 * A recipe importer built from a sniffer and a reader. Use the factories in [RecipeImporters].
 *
 * @param reader turns the file name and bytes into recipes (may throw on bad input).
 */
class RecipeImporter(
    override val id: String,
    private val sniffer: (name: String, head: ByteArray) -> Int,
    private val reader: (name: String, bytes: ByteArray) -> List<Recipe>,
    sink: ImportSink = ImportSink.NONE,
) : BaseImporter(sink) {
    override fun sniffSafe(name: String, head: ByteArray): Int = sniffer(name, head)

    override fun parse(src: InputSource): Outcome<ImportPlan> {
        val bytes = src.open().use { Texts.readBounded(it) }
        val recipes = reader(src.name, bytes)
        if (recipes.isEmpty()) return Outcome.Fail(SwError.BadInput("no recipes found"))
        val data = ImportData(recipes = recipes)
        val warnings = recipes.filter { it.items.isEmpty() }.map { "${it.name}: no ingredients" }
        val preview = listOf(listOf("name", "servings", "ingredients", "steps")) + recipes.take(50).map { r ->
            listOf(r.name, Numbers.formatScaled(r.servingsX10, 10), r.items.size.toString(), (r.steps?.lines()?.size ?: 0).toString())
        }
        return Outcome.Ok(
            ImportPlan(
                importerId = id, fileName = src.name, counts = data.counts(), preview = preview, columns = emptyList(),
                dateFormat = DateFormatGuess.NONE, units = UnitGuess.NONE, duplicates = 0, warnings = warnings.take(20),
                handle = { Outcome.Ok(data) },
            ),
        )
    }
}

/** Factories for every recipe importer. */
object RecipeImporters {
    private fun head(head: ByteArray): String = Texts.headText(head)

    /** schema.org Recipe JSON-LD in `.json` or HTML. Id "recipe-jsonld". */
    fun jsonLd(sink: ImportSink = ImportSink.NONE): RecipeImporter = RecipeImporter(
        "recipe-jsonld",
        { name, h ->
            val ext = Texts.extension(name)
            val t = head(h)
            val recipeType = Regex(""""@type"\s*:\s*(\[[^]]*)?"(schema:|https?://schema\.org/)?Recipe"""").containsMatchIn(t)
            when {
                Zips.isZip(h) || Zips.isGzip(h) -> 0
                recipeType -> 85
                t.contains("application/ld+json", ignoreCase = true) -> 60
                ext == "json" && t.contains("schema.org") -> 50
                ext == "html" || ext == "htm" -> 20
                else -> 0
            }
        },
        { _, bytes -> SchemaOrgRecipe.readText(Texts.decode(bytes)) },
        sink,
    )

    /** Paprika `.paprikarecipes` (or one `.paprikarecipe`). Id "paprika". */
    fun paprika(sink: ImportSink = ImportSink.NONE): RecipeImporter = RecipeImporter(
        "paprika",
        { name, h ->
            val ext = Texts.extension(name)
            when {
                ext == "paprikarecipes" -> 95
                ext == "paprikarecipe" && Zips.isGzip(h) -> 95
                Zips.isZip(h) && Zips.namesInHead(h).any { it.endsWith(".paprikarecipe") } -> 90
                else -> 0
            }
        },
        { _, bytes -> PaprikaReader.read(bytes) },
        sink,
    )

    /** Mealie backup zip or recipe JSON. Id "mealie". */
    fun mealie(sink: ImportSink = ImportSink.NONE): RecipeImporter = RecipeImporter(
        "mealie",
        { name, h ->
            val names = if (Zips.isZip(h)) Zips.namesInHead(h).map { Texts.baseName(it).lowercase() } else emptyList()
            when {
                names.contains("database.json") -> 75
                Zips.isZip(h) && name.lowercase().contains("mealie") -> 60
                !Zips.isZip(h) && head(h).contains("\"recipeIngredient\"") && head(h).contains("\"slug\"") -> 55
                else -> 0
            }
        },
        { _, bytes -> MealieReader.read(bytes) },
        sink,
    )

    /** Tandoor export zip. Id "tandoor". */
    fun tandoor(sink: ImportSink = ImportSink.NONE): RecipeImporter = RecipeImporter(
        "tandoor",
        { _, h ->
            val names = if (Zips.isZip(h)) Zips.namesInHead(h) else emptyList()
            when {
                names.isNotEmpty() && names.all { it.lowercase().endsWith(".zip") } -> 65
                names.any { Texts.baseName(it).equals("recipe.json", true) } -> 55
                !Zips.isZip(h) && head(h).contains("\"steps\"") && head(h).contains("\"working_time\"") -> 60
                else -> 0
            }
        },
        { _, bytes -> TandoorReader.read(bytes) },
        sink,
    )

    /** Meal-Master text. Id "mealmaster". */
    fun mealMaster(sink: ImportSink = ImportSink.NONE): RecipeImporter = RecipeImporter(
        "mealmaster",
        { name, h ->
            val ext = Texts.extension(name)
            when {
                Zips.isZip(h) -> 0
                head(h).contains("Meal-Master", ignoreCase = true) -> 90
                ext == "mmf" || ext == "mm" -> 60
                else -> 0
            }
        },
        { _, bytes -> MealMasterReader.read(Texts.decode(bytes)) },
        sink,
    )

    /** Cooklang `.cook`. Id "cooklang". */
    fun cooklang(sink: ImportSink = ImportSink.NONE): RecipeImporter = RecipeImporter(
        "cooklang",
        { name, h -> if (Texts.extension(name) == "cook" && !Zips.isZip(h)) 95 else 0 },
        { name, bytes -> listOfNotNull(CooklangReader.read(Texts.decode(bytes), name)) },
        sink,
    )

    /** All recipe importers. */
    fun all(sink: ImportSink = ImportSink.NONE): List<RecipeImporter> =
        listOf(jsonLd(sink), paprika(sink), mealie(sink), tandoor(sink), mealMaster(sink), cooklang(sink))
}

/**
 * Exports the recipe book in Nextcloud Cookbook's layout: a zip with `Recipes/<name>/recipe.json`
 * (schema.org Recipe), which Nextcloud Cookbook, Tandoor and Mealie can import. Id "recipes-nextcloud".
 */
class NextcloudRecipeExporter(private val source: ExportSource) : Exporter {
    override val id: String = "recipes-nextcloud"

    override fun mimeType(req: ExportRequest): String = "application/zip"

    override fun suggestedName(req: ExportRequest): String = "selfwatch-recipes-${createdDate(req)}.zip"

    override suspend fun export(req: ExportRequest, out: OutputSink): Outcome<ExportResult> {
        val recipes = source.recipes()
        val r = writeTo(out) { stream -> write(stream, recipes) }
        return if (r is Outcome.Fail) r else Outcome.Ok(ExportResult(mapOf("recipe" to recipes.size)))
    }

    /** Writer, usable on its own. */
    companion object {
        private val UNSAFE = Regex("""[\\/:*?"<>|\x00-\x1F]""")

        /** A folder name for [name]: no path or reserved characters, at most 100 characters. */
        fun folderName(name: String): String =
            name.replace(UNSAFE, "_").trim().trim('.').take(100).trim().ifEmpty { "Recipe" }

        /** Writes [recipes] as a Nextcloud Cookbook zip to [out] (finished, not closed). */
        fun write(out: java.io.OutputStream, recipes: List<Recipe>) {
            val used = HashSet<String>()
            Zips.write(out) {
                for (r in recipes) {
                    val base = folderName(r.name)
                    var folder = base
                    var n = 2
                    while (!used.add(folder.lowercase())) folder = "$base (${n++})"
                    entry("Recipes/$folder/recipe.json") { it.write(Json.write(SchemaOrgRecipe.toJson(r)).toByteArray(Charsets.UTF_8)) }
                }
            }
        }
    }
}
