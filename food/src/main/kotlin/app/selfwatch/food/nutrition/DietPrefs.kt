/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.nutrition

import app.selfwatch.model.food.Allergen
import app.selfwatch.model.food.DietInfo

/** A diet the user can follow (Me → Goals → Diet). More than one may be set. [id] is stored in settings. */
enum class Diet(val id: String) {
    HIGH_PROTEIN("high_protein"), HEART_HEALTHY("heart_healthy"), VEGETARIAN("vegetarian"), VEGAN("vegan"), GLUTEN_FREE("gluten_free");

    /** Lookup. */
    companion object {
        /** The diet stored as [id], or null. */
        fun byId(id: String): Diet? = entries.firstOrNull { it.id == id }
    }
}

/** Why a food does not fit the user's diet, as known from the food database (never guessed). */
sealed interface DietConflict {
    /** Known not vegan (the user is vegan). */
    data object NotVegan : DietConflict

    /** Known not vegetarian (the user is vegetarian or vegan). */
    data object NotVegetarian : DietConflict

    /** The food lists [allergen], which the user avoids (gluten also for a gluten-free diet). */
    data class Contains(val allergen: Allergen) : DietConflict
}

/** The user's diets and the allergens they avoid. */
data class DietPrefs(val diets: Set<Diet>, val allergens: Set<Allergen>) {
    /** True when nothing is set. */
    val isEmpty: Boolean get() = diets.isEmpty() && allergens.isEmpty()

    /** True when gluten is to be avoided (gluten-free diet or the gluten allergen). */
    val avoidsGluten: Boolean get() = Diet.GLUTEN_FREE in diets || Allergen.GLUTEN in allergens

    /**
     * The known conflicts of a food with [info] (empty when it fits or nothing is known). Only what the
     * database states counts: an unknown food never conflicts and is never called a fit.
     */
    fun conflicts(info: DietInfo?): List<DietConflict> {
        if (info == null || isEmpty) return emptyList()
        val out = ArrayList<DietConflict>(2)
        if (Diet.VEGAN in diets && info.vegan == false) out.add(DietConflict.NotVegan)
        if ((Diet.VEGETARIAN in diets || Diet.VEGAN in diets) && info.vegetarian == false) {
            // "Not vegetarian" says more than "not vegan"; show one of them.
            out.remove(DietConflict.NotVegan)
            out.add(DietConflict.NotVegetarian)
        }
        val avoid = LinkedHashSet(allergens)
        if (Diet.GLUTEN_FREE in diets) avoid.add(Allergen.GLUTEN)
        for (a in Allergen.entries) if (a in avoid && info.has(a) == true) out.add(DietConflict.Contains(a))
        return out
    }

    /** Settings encoding. */
    companion object {
        /** No diet, no allergens. */
        val NONE: DietPrefs = DietPrefs(emptySet(), emptySet())

        /** Prefs from the stored lists (unknown ids are skipped). */
        fun of(diets: List<String>, allergens: List<String>): DietPrefs = DietPrefs(
            diets.mapNotNullTo(LinkedHashSet()) { Diet.byId(it.trim()) },
            allergens.mapNotNullTo(LinkedHashSet()) { a -> Allergen.entries.firstOrNull { it.name.equals(a.trim(), ignoreCase = true) } },
        )

        /** The stored ids of [p]'s diets. */
        fun dietIds(p: DietPrefs): List<String> = Diet.entries.filter { it in p.diets }.map { it.id }

        /** The stored ids of [p]'s allergens. */
        fun allergenIds(p: DietPrefs): List<String> = Allergen.entries.filter { it in p.allergens }.map { it.name.lowercase() }
    }
}
