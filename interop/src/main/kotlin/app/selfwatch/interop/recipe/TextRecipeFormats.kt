/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.recipe

import app.selfwatch.interop.io.Numbers
import app.selfwatch.interop.io.Texts
import app.selfwatch.model.food.Recipe
import app.selfwatch.model.food.RecipeItem

/**
 * Meal-Master one-column text recipes (`.mmf`, `.mm`, `.txt`), any number per file.
 *
 * A recipe starts at a line beginning `MMMMM` or `-----` that mentions Meal-Master and ends at a line
 * of only `MMMMM` or `-----`. The header has `Title:`, `Categories:` and `Yield:` (or `Servings:`).
 * Ingredient lines have the amount in columns 1-7, the unit code in 9-10 and the text from column 12;
 * a text starting with `-` continues the line above; `MMMMM---NAME---` lines start an ingredient
 * group. The first other line starts the directions, which run to the end marker.
 */
object MealMasterReader {
    private val START = Regex("""^(MMMMM|-{5}).*meal-?master""", RegexOption.IGNORE_CASE)
    private val END = Regex("""^(M{5,}|-{5,})\s*$""")
    private val GROUP = Regex("""^(?:MMMMM|-{5})-*\s*([^-].*?)\s*-+\s*$""")
    private val INGREDIENT = Regex("""^([ \d/.]{7}) ([ A-Za-z]{2}) (.*)$""")
    private val UNITS = mapOf(
        "x" to "per serving", "sm" to "small", "md" to "medium", "lg" to "large", "cn" to "can", "pk" to "package",
        "pn" to "pinch", "dr" to "drop", "ds" to "dash", "ct" to "carton", "bn" to "bunch", "sl" to "slice",
        "ea" to "each", "t" to "tsp", "ts" to "tsp", "T" to "tbsp", "tb" to "tbsp", "fl" to "fl oz", "c" to "cup",
        "pt" to "pint", "qt" to "quart", "ga" to "gallon", "oz" to "oz", "lb" to "lb", "ml" to "ml", "cb" to "cubic cm",
        "cl" to "cl", "dl" to "dl", "l" to "l", "mg" to "mg", "cg" to "cg", "dg" to "dg", "g" to "g", "kg" to "kg",
    )

    /** Recipes in [text]. */
    fun read(text: String): List<Recipe> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<Recipe>()
        var i = 0
        while (i < lines.size) {
            if (START.containsMatchIn(lines[i])) {
                var j = i + 1
                while (j < lines.size && !END.matches(lines[j].trimEnd())) j++
                parseOne(lines.subList(i + 1, j))?.let { out.add(it) }
                i = j + 1
            } else {
                i++
            }
        }
        return out
    }

    private class Ing(var line: String, val amount: Double?, val unit: String?)

    private fun parseOne(body: List<String>): Recipe? {
        var title: String? = null
        var categories: List<String> = emptyList()
        var yieldText: String? = null
        var k = 0
        // Header: label lines, then the ingredients after the first blank line following them.
        while (k < body.size) {
            val t = body[k].trim()
            when {
                t.startsWith("Title:", true) -> title = t.substringAfter(':').trim()
                t.startsWith("Categories:", true) -> categories = t.substringAfter(':').split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("None", true) }
                t.startsWith("Yield:", true) || t.startsWith("Servings:", true) -> yieldText = t.substringAfter(':').trim()
                t.isEmpty() && title != null -> {
                    k++
                    break
                }
            }
            k++
        }
        val name = title?.takeIf { it.isNotEmpty() } ?: return null
        val ings = ArrayList<Ing>()
        while (k < body.size) {
            val raw = body[k].trimEnd()
            if (raw.isBlank()) {
                k++
                continue
            }
            val group = GROUP.matchEntire(raw)
            if (group != null) {
                ings.add(Ing(group.groupValues[1].trim() + ":", null, null))
                k++
                continue
            }
            val m = INGREDIENT.matchEntire(raw) ?: break
            val amountText = m.groupValues[1].trim()
            val unitCode = m.groupValues[2].trim()
            val rest = m.groupValues[3].trim()
            if (amountText.isEmpty() && unitCode.isEmpty() && rest.startsWith("-") && ings.isNotEmpty()) {
                ings.last().line += " " + rest.removePrefix("-").trim()
            } else {
                val line = listOf(amountText, unitCode, rest).filter { it.isNotEmpty() }.joinToString(" ")
                ings.add(Ing(line, amountText.takeIf { it.isNotEmpty() }?.let { Numbers.leadingQuantity(it)?.first }, UNITS[unitCode] ?: unitCode.ifEmpty { null }))
            }
            k++
        }
        val steps = ArrayList<String>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotEmpty()) steps.add(para.toString())
            para.setLength(0)
        }
        while (k < body.size) {
            val t = body[k].trim()
            if (t.isEmpty()) flush() else {
                if (para.isNotEmpty()) para.append(' ')
                para.append(t)
            }
            k++
        }
        flush()
        val (servings, label) = RecipeSupport.servings(yieldText)
        val items: List<RecipeItem> = ings.mapIndexed { idx, ing -> RecipeSupport.item(idx + 1, ing.line, ing.amount, ing.unit) }
        return RecipeSupport.recipe(
            name = name, lines = emptyList(), servings = servings, servingLabel = label,
            steps = steps.joinToString("\n"), tags = categories, items = items,
        )
    }
}

/**
 * Cooklang (`.cook`) recipes: ingredients inline as `@name{quantity%unit}` (or `@word`), cookware
 * `#name{}`, timers `~{quantity%unit}`, comments `--` and `[- … -]`, metadata as `>> key: value` lines
 * or YAML front matter, `= Section` headings and `>` notes. Each paragraph is a step; each ingredient
 * mention becomes an item line `quantity unit name`.
 */
object CooklangReader {
    private val TOKEN = Regex("""([@#~])(?:([^@#~{}\n]*?)\{([^}]*)}|([\p{L}\p{N}_\-]+))""")
    private val BLOCK_COMMENT = Regex("""\[-.*?-]""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The recipe in [text]; [fileName] gives the title when the metadata has none. Null for binary
     * data and for text without a single `@ingredient` (any text would otherwise be a valid recipe).
     */
    fun read(text: String, fileName: String): Recipe? {
        if (Texts.looksBinary(text)) return null
        val meta = LinkedHashMap<String, String>()
        var lines = BLOCK_COMMENT.replace(text.replace("\r\n", "\n"), "").split('\n')
        if (lines.firstOrNull()?.trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) {
                for (l in lines.subList(1, end + 1)) {
                    val key = l.substringBefore(':', "").trim().lowercase()
                    if (key.isNotEmpty()) meta[key] = l.substringAfter(':').trim().trim('"')
                }
                lines = lines.drop(end + 2)
            }
        }
        val items = ArrayList<RecipeItem>()
        val steps = ArrayList<String>()
        val notes = ArrayList<String>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) steps.add(para.toString().trim())
            para.setLength(0)
        }
        for (raw in lines) {
            val line = raw.substringBefore("--").trimEnd()
            val t = line.trim()
            when {
                t.startsWith(">>") -> {
                    val kv = t.removePrefix(">>")
                    meta[kv.substringBefore(':').trim().lowercase()] = kv.substringAfter(':', "").trim()
                }
                t.startsWith(">") -> notes.add(t.removePrefix(">").trim())
                t.startsWith("=") -> {
                    flush()
                    t.trim('=', ' ').takeIf { it.isNotEmpty() }?.let { steps.add("$it:") }
                }
                t.isEmpty() -> flush()
                else -> {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(plainStep(t, items))
                }
            }
        }
        flush()
        val title = meta["title"] ?: fileName.substringAfterLast('/').substringBeforeLast('.').trim()
        if (title.isEmpty() || items.isEmpty()) return null
        val (servings, label) = RecipeSupport.servings(meta["servings"] ?: meta["serves"] ?: meta["yield"])
        val tags = (meta["tags"] ?: "").trim('[', ']').split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        return RecipeSupport.recipe(
            name = title,
            lines = emptyList(),
            servings = servings,
            servingLabel = label,
            prepMin = RecipeSupport.minutes(meta["prep time"] ?: meta["time.prep"] ?: meta["prep"]),
            cookMin = RecipeSupport.minutes(meta["cook time"] ?: meta["time.cook"] ?: meta["cook"] ?: meta["time"]),
            steps = steps.joinToString("\n"),
            notes = (listOfNotNull(meta["description"]) + notes).joinToString("\n").ifEmpty { null },
            sourceName = meta["author"] ?: meta["source.name"],
            sourceUrl = meta["source"]?.takeIf { it.startsWith("http") } ?: meta["source.url"],
            tags = tags,
            items = items,
        )
    }

    /** [line] with markup turned into plain words; ingredient mentions are added to [items]. */
    private fun plainStep(line: String, items: MutableList<RecipeItem>): String = TOKEN.replace(line) { m ->
        val kind = m.groupValues[1]
        val braced = m.groupValues[2].trim()
        val bare = m.groupValues[4]
        val name = braced.ifEmpty { bare }
        val qtyText = m.groupValues[3].trim().removePrefix("=").trim()
        val qty = qtyText.substringBefore('%').trim()
        val unit = qtyText.substringAfter('%', "").trim()
        when (kind) {
            "@" -> {
                if (items.size < RecipeSupport.MAX_ITEMS) {
                    val itemLine = listOf(qty, unit, name).filter { it.isNotEmpty() }.joinToString(" ")
                    items.add(RecipeSupport.item(items.size + 1, itemLine, Numbers.leadingQuantity(qty)?.first, unit.ifEmpty { null }))
                }
                name
            }
            "#" -> name
            else -> listOf(qty, unit).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { name }
        }
    }
}
