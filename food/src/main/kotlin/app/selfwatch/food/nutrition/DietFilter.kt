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

/** How a food stands against one [DietFilter]. */
enum class FilterFit {
    /** Known to fit. */
    FITS,

    /** Known to clash (hidden while the filter is on). */
    CONFLICTS,

    /** Not known (shown, marked "?"). */
    UNKNOWN,
}

/**
 * One Add food filter chip (FOOD_UI_PLAN §4.3): "Vegan", "Vegetarian", "Gluten-free" or "No milk"…
 * A filter hides only foods known to clash; foods with unknown diet data stay in the list.
 */
sealed interface DietFilter {
    /** How a food with [info] stands against this filter. */
    fun fit(info: DietInfo?): FilterFit

    /** Stable id (view tags, saved state). */
    val id: String

    /** Vegan foods only. */
    data object Vegan : DietFilter {
        override val id: String = "vegan"
        override fun fit(info: DietInfo?): FilterFit = known(info?.vegan)
    }

    /** Vegetarian foods only. */
    data object Vegetarian : DietFilter {
        override val id: String = "vegetarian"
        override fun fit(info: DietInfo?): FilterFit = known(info?.vegetarian)
    }

    /** No gluten. */
    data object GlutenFree : DietFilter {
        override val id: String = "gluten_free"
        override fun fit(info: DietInfo?): FilterFit = known(info?.has(Allergen.GLUTEN)?.not())
    }

    /** Without [allergen] (gluten has its own filter). */
    data class Without(val allergen: Allergen) : DietFilter {
        override val id: String = "no_" + allergen.name.lowercase()
        override fun fit(info: DietInfo?): FilterFit = known(info?.has(allergen)?.not())
    }

    /** Filters for a user's prefs, and applying them. */
    companion object {
        /**
         * The chips for [prefs], in a fixed order: Vegan (or Vegetarian), Gluten-free, then one "No …"
         * per allergen. Empty when nothing is set (no chips are shown).
         */
        fun forPrefs(prefs: DietPrefs): List<DietFilter> {
            val out = ArrayList<DietFilter>()
            when {
                Diet.VEGAN in prefs.diets -> out.add(Vegan)
                Diet.VEGETARIAN in prefs.diets -> out.add(Vegetarian)
            }
            if (prefs.avoidsGluten) out.add(GlutenFree)
            for (a in Allergen.entries) if (a != Allergen.GLUTEN && a in prefs.allergens) out.add(Without(a))
            return out
        }

        /** How a food with [info] stands against all of [active]: any clash wins, then any unknown. */
        fun fitAll(info: DietInfo?, active: Collection<DietFilter>): FilterFit {
            var unknown = false
            for (f in active) {
                when (f.fit(info)) {
                    FilterFit.CONFLICTS -> return FilterFit.CONFLICTS
                    FilterFit.UNKNOWN -> unknown = true
                    FilterFit.FITS -> Unit
                }
            }
            return if (unknown) FilterFit.UNKNOWN else FilterFit.FITS
        }

        private fun known(fits: Boolean?): FilterFit = when (fits) {
            true -> FilterFit.FITS
            false -> FilterFit.CONFLICTS
            null -> FilterFit.UNKNOWN
        }
    }
}
