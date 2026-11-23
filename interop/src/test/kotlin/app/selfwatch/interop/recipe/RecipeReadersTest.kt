/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.recipe

import app.selfwatch.interop.TestSupport
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.MemorySink
import app.selfwatch.interop.io.Zips
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeReadersTest {
    private fun recipes(importer: RecipeImporter, path: String): List<Recipe> =
        TestSupport.data(TestSupport.plan(importer, path.substringAfterLast('/'), TestSupport.bytes(path))).recipes

    @Test
    fun schemaOrgJsonWithSectionsAndNutrition() {
        val r = recipes(RecipeImporters.jsonLd(), "recipes/lemon-lentil-soup.json").single()
        assertEquals("Lemon Lentil Soup", r.name)
        assertEquals(40, r.servingsX10)
        assertEquals("bowls", r.servingLabel)
        assertEquals(10, r.prepMin)
        assertEquals(25, r.cookMin)
        assertEquals(7, r.items.size)
        assertEquals("½ tsp ground cumin", r.items[5].line)
        assertTrue(r.items.all { it.match == MatchState.UNMATCHED && it.food == null })
        assertEquals(
            listOf("Base:", "Warm the oil in a pot and soften the onion for 5 minutes.", "Add the garlic and cumin and stir for 1 minute.", "Simmer:"),
            r.steps!!.lines().take(4),
        )
        assertEquals(Nutrients(2400, 140, 45, 6, 360, 40, 80, 620, null), r.statedPerServing)
        assertEquals(setOf("Soup", "Mediterranean", "lentils", "quick", "vegetarian"), r.tags)
        assertEquals("Self Watch test kitchen", r.sourceName)
        assertEquals("https://example.org/recipes/lemon-lentil-soup", r.sourceUrl)
        assertTrue(r.notes!!.contains("this test set & nothing"))
    }

    @Test
    fun schemaOrgInsideHtmlGraph() {
        val r = recipes(RecipeImporters.jsonLd(), "recipes/recipe-page.html").single()
        assertEquals("Oat Banana Pancakes", r.name)
        assertEquals(80, r.servingsX10)
        assertEquals("pancakes", r.servingLabel)
        assertEquals(5, r.prepMin)
        assertEquals(15, r.cookMin)
        assertEquals(3, r.steps!!.lines().size)
        assertEquals(950, r.statedPerServing!!.kcalX10)
        assertEquals("Three-ingredient pancakes, written for the test set.", r.notes)
        assertEquals("Example Kitchen", r.sourceName)
        assertEquals(setOf("breakfast", "gluten free"), r.tags)
    }

    @Test
    fun schemaOrgEdgeCases() {
        val text = """[{"@type":"schema:Recipe","name":"A &amp; B","recipeYield":6,"ingredients":"x\ny","recipeInstructions":["one","two"]},
            {"@type":"Thing","name":"no"},{"@type":"Recipe"}]"""
        val list = SchemaOrgRecipe.readText(text)
        assertEquals(1, list.size)
        assertEquals("A & B", list[0].name)
        assertEquals(60, list[0].servingsX10)
        assertEquals(listOf("x", "y"), list[0].items.map { it.line })
        assertEquals("one\ntwo", list[0].steps)
        assertEquals(emptyList<Recipe>(), SchemaOrgRecipe.readText("<html>no recipe</html>"))
        // Raw line breaks inside strings are tolerated.
        val raw = "{\"@type\":\"Recipe\",\"name\":\"So" + 10.toChar() + "up\"}"
        assertEquals("So up", SchemaOrgRecipe.readText(raw).single().name)
    }

    @Test
    fun durations() {
        assertEquals(90, RecipeSupport.minutes("PT1H30M"))
        assertEquals(20, RecipeSupport.minutes("P0DT0H20M"))
        assertEquals(90, RecipeSupport.minutes("1 hr 30 mins"))
        assertEquals(45, RecipeSupport.minutes("45"))
        assertNull(RecipeSupport.minutes("PT"))
        assertNull(RecipeSupport.minutes("soon"))
        assertEquals("PT1H30M", RecipeSupport.isoDuration(90))
        assertEquals("PT0M", RecipeSupport.isoDuration(0))
    }

    @Test
    fun paprika() {
        val list = recipes(RecipeImporters.paprika(), "recipes/paprika-export.paprikarecipes")
        assertEquals(listOf("Chickpea Spinach Curry", "Overnight Oats"), list.map { it.name })
        val curry = list[0]
        assertEquals(6, curry.items.size)
        assertEquals("1 can (400 g) chickpeas, drained", curry.items[2].line)
        assertEquals(40, curry.servingsX10)
        assertEquals(10, curry.prepMin)
        assertEquals(20, curry.cookMin)
        assertEquals(setOf("Dinner", "Vegan"), curry.tags)
        assertTrue(curry.notes!!.contains("Nutrition: Calories: 380"))
        assertEquals("https://example.org/curry", curry.sourceUrl)
        assertEquals(3, curry.steps!!.lines().size)
        assertEquals(10, list[1].servingsX10)
    }

    @Test
    fun mealieDatabaseBackup() {
        val list = recipes(RecipeImporters.mealie(), "recipes/mealie-backup.zip")
        assertEquals(2, list.size)
        val hummus = list[0]
        assertEquals("Roasted Carrot Hummus", hummus.name)
        assertEquals(listOf("Roast:", "300 g carrots peeled", "400 g chickpeas drained", "2 tbsp tahini", "juice of 1 lemon"), hummus.items.map { it.line })
        assertEquals(60, hummus.servingsX10)
        assertEquals(15, hummus.prepMin)
        assertEquals(35, hummus.cookMin)
        assertEquals(setOf("Dip", "Vegan", "Snacks"), hummus.tags)
        assertEquals(Nutrients(1800, 60, 80, null, 210, 50, 60, 240, null), hummus.statedPerServing)
        assertEquals(listOf("1 banana, frozen", "1 cup spinach", "1 cup oat milk"), list[1].items.map { it.line })
    }

    @Test
    fun mealieApiJson() {
        val json = """{"name":"Toast","slug":"toast","recipeYield":"2 servings","recipeIngredient":[{"note":"butter","quantity":1,"unit":{"name":"tbsp"},"food":null,"display":""},
            {"originalText":"2 slices bread"}],"recipeInstructions":[{"title":"","text":"Toast it."}],"tags":[{"name":"quick"}],
            "nutrition":{"calories":"150","proteinContent":"4"}}"""
        val r = MealieReader.read(json.toByteArray()).single()
        assertEquals(listOf("1 tbsp butter", "2 slices bread"), r.items.map { it.line })
        assertEquals("Toast it.", r.steps)
        assertEquals(1500, r.statedPerServing!!.kcalX10)
    }

    @Test
    fun tandoorNestedZips() {
        val list = recipes(RecipeImporters.tandoor(), "recipes/tandoor-export.zip")
        assertEquals(listOf("Sheet Pan Chicken and Vegetables", "Cucumber Yogurt Dip"), list.map { it.name })
        val pan = list[0]
        assertEquals(listOf("600 g chicken thighs boneless", "500 g potatoes cubed", "2 tbsp olive oil", "paprika to taste"), pan.items.map { it.line })
        assertEquals(40, pan.servingsX10)
        assertEquals("plates", pan.servingLabel)
        assertEquals(15, pan.prepMin)
        assertEquals(35, pan.cookMin)
        assertEquals(Nutrients(4200, 340, 180, null, 280, null, null, null, null), pan.statedPerServing)
        assertEquals(setOf("dinner", "one pan"), pan.tags)
        assertEquals("Prepare:", pan.steps!!.lines()[0])
        assertEquals("1 clove garlic, grated", list[1].items[2].line)
    }

    @Test
    fun mealMasterTwoRecipes() {
        val list = recipes(RecipeImporters.mealMaster(), "recipes/two-recipes.mmf")
        assertEquals(listOf("Herb Rice Pilaf", "Quick Tomato Salsa"), list.map { it.name })
        val pilaf = list[0]
        assertEquals(40, pilaf.servingsX10)
        assertEquals(setOf("Side dish", "Rice"), pilaf.tags)
        assertEquals(
            listOf("1 c Long-grain rice", "2 c Water", "1 T Butter", "1/2 t Salt", "2 T Chopped parsley or other soft green herbs", "TOPPING:", "2 T Toasted almonds"),
            pilaf.items.map { it.line },
        )
        assertEquals(50, pilaf.items[3].amountX100)
        assertEquals("tsp", pilaf.items[3].unit)
        assertEquals("tbsp", pilaf.items[2].unit)
        assertEquals(2, pilaf.steps!!.lines().size)
        assertTrue(pilaf.steps!!.startsWith("Melt the butter in a saucepan, add the rice and stir until it smells toasty."))
        val salsa = list[1]
        assertEquals(listOf("3 Tomatoes; diced", "1/4 c Red onion; minced", "1 T Lime juice", "Salt to taste"), salsa.items.map { it.line })
        assertEquals(60, salsa.servingsX10)
    }

    @Test
    fun cooklang() {
        val r = recipes(RecipeImporters.cooklang(), "recipes/tomato-toast.cook").single()
        assertEquals("Garlic Tomato Toast", r.name)
        assertEquals(20, r.servingsX10)
        assertEquals(5, r.prepMin)
        assertEquals(5, r.cookMin)
        assertEquals(setOf("breakfast", "quick"), r.tags)
        assertEquals(listOf("4 slices bread", "1 clove garlic", "2 tomatoes", "1 tbsp olive oil", "salt", "black pepper"), r.items.map { it.line })
        assertEquals(400, r.items[0].amountX100)
        assertEquals("slices", r.items[0].unit)
        assertEquals(
            listOf(
                "Toast bread in a pan or toaster for 3 minutes.",
                "Rub each slice with garlic and top with tomatoes sliced thin.",
                "Finish:",
                "Drizzle with olive oil and sprinkle with salt and black pepper.",
            ),
            r.steps!!.lines(),
        )
        assertEquals("A made-up recipe for the Self Watch test set.", r.notes)
    }

    @Test
    fun nextcloudExportReadsBackAsTheSameRecipes() = runBlocking {
        val originals = recipes(RecipeImporters.jsonLd(), "recipes/lemon-lentil-soup.json") +
            recipes(RecipeImporters.paprika(), "recipes/paprika-export.paprikarecipes") +
            recipes(RecipeImporters.mealMaster(), "recipes/two-recipes.mmf")
        val dup = originals[0].copy(notes = "second copy")
        val sink = MemorySink()
        val res = NextcloudRecipeExporter(TestSupport.FakeSource(recipeList = originals + dup)).export(ExportRequest(), sink)
        assertEquals(6, (res as Outcome.Ok).value.counts["recipe"])
        val entries = Zips.readAll(sink.bytes())
        assertEquals(
            listOf(
                "Recipes/Lemon Lentil Soup/recipe.json", "Recipes/Chickpea Spinach Curry/recipe.json", "Recipes/Overnight Oats/recipe.json",
                "Recipes/Herb Rice Pilaf/recipe.json", "Recipes/Quick Tomato Salsa/recipe.json", "Recipes/Lemon Lentil Soup (2)/recipe.json",
            ),
            entries.map { it.name },
        )
        val back = entries.map { SchemaOrgRecipe.readText(String(it.bytes, Charsets.UTF_8)).single() }
        for ((o, b) in (originals + dup).zip(back)) {
            assertEquals(o.name, b.name)
            assertEquals(o.servingsX10, b.servingsX10)
            assertEquals(o.prepMin, b.prepMin)
            assertEquals(o.cookMin, b.cookMin)
            assertEquals(o.steps, b.steps)
            assertEquals(o.notes, b.notes)
            assertEquals(o.sourceUrl, b.sourceUrl)
            assertEquals(o.sourceName, b.sourceName)
            assertEquals(o.statedPerServing, b.statedPerServing)
            assertEquals(o.tags, b.tags)
            assertEquals(o.items.map { it.line }, b.items.map { it.line })
        }
        assertEquals("a_b_c", NextcloudRecipeExporter.folderName("a/b:c"))
    }
}
