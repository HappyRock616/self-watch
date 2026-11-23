/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.recipes

/** What a unit measures. */
enum class UnitKind {
    /** A weight: [Units.Unit.perUnitX1000] is milligrams per unit. */
    MASS,

    /** A volume: [Units.Unit.perUnitX1000] is microlitres per unit (ml ×1000). */
    VOLUME,

    /** A count or a container ("clove", "can"); its weight comes from the food or the package size. */
    COUNT,
}

/**
 * The recipe unit dictionary: canonical names (FOODDB_SPEC §4 unit words where they exist), their
 * aliases, and standard sizes. US kitchen volumes on a 240 ml cup: tsp 5 ml, tbsp 15 ml, fl oz 30 ml,
 * pint 480 ml, quart 960 ml, gallon 3840 ml.
 */
object Units {
    /** One canonical unit: its [name], [kind] and size per unit ×1000 (mg for mass, µl for volume, 0 for counts). */
    data class Unit(val name: String, val kind: UnitKind, val perUnitX1000: Long) {
        /** True for package-like units whose size can come from "(15 oz)". */
        val isContainer: Boolean get() = name in CONTAINERS
    }

    private val units: Map<String, Unit> = listOf(
        Unit("g", UnitKind.MASS, 1_000),
        Unit("kg", UnitKind.MASS, 1_000_000),
        Unit("mg", UnitKind.MASS, 1),
        Unit("oz", UnitKind.MASS, 28_350),
        Unit("lb", UnitKind.MASS, 453_592),
        Unit("ml", UnitKind.VOLUME, 1_000),
        Unit("l", UnitKind.VOLUME, 1_000_000),
        Unit("tsp", UnitKind.VOLUME, 5_000),
        Unit("tbsp", UnitKind.VOLUME, 15_000),
        Unit("cup", UnitKind.VOLUME, 240_000),
        Unit("fl oz", UnitKind.VOLUME, 30_000),
        Unit("pint", UnitKind.VOLUME, 480_000),
        Unit("quart", UnitKind.VOLUME, 960_000),
        Unit("gallon", UnitKind.VOLUME, 3_840_000),
    ).associateBy { it.name } + listOf(
        "can", "clove", "slice", "pinch", "dash", "piece", "stick", "package", "jar", "bottle", "bunch", "head",
        "sprig", "stalk", "handful", "bag", "box", "container", "carton", "envelope", "packet", "sheet", "cube",
        "tub", "pouch", "drop", "ear", "fillet", "scoop",
    ).associateWith { Unit(it, UnitKind.COUNT, 0) }

    private val CONTAINERS = setOf(
        "can", "package", "jar", "bottle", "box", "bag", "container", "carton", "envelope", "packet", "tub", "pouch",
    )

    /** Alias (lowercase, no trailing dot) → canonical name. "T" and "t" are handled case-sensitively in [lookup]. */
    private val aliases: Map<String, String> = buildMap {
        fun add(canonical: String, vararg names: String) {
            put(canonical, canonical)
            for (n in names) put(n, canonical)
        }
        add("g", "gram", "grams", "gramme", "grammes", "gr", "grs", "gm", "gms")
        add("kg", "kgs", "kilo", "kilos", "kilogram", "kilograms", "kilogramme", "kilogrammes")
        add("mg", "milligram", "milligrams")
        add("oz", "ounce", "ounces", "ozs")
        add("lb", "lbs", "pound", "pounds")
        add("ml", "mls", "milliliter", "milliliters", "millilitre", "millilitres", "cc")
        add("l", "liter", "liters", "litre", "litres", "ltr", "ltrs")
        add("tsp", "tsps", "teaspoon", "teaspoons", "tspn", "tspns")
        add("tbsp", "tbsps", "tablespoon", "tablespoons", "tbs", "tbl", "tbls", "tblsp", "tblspn", "tbsps")
        add("cup", "cups", "c")
        add("fl oz", "floz", "fl. oz", "fluid ounce", "fluid ounces", "fl ounce", "fl ounces")
        add("pint", "pints", "pt", "pts")
        add("quart", "quarts", "qt", "qts")
        add("gallon", "gallons", "gal", "gals")
        add("can", "cans", "tin", "tins")
        add("clove", "cloves")
        add("slice", "slices")
        add("pinch", "pinches")
        add("dash", "dashes")
        add("piece", "pieces", "pc", "pcs")
        add("stick", "sticks")
        add("package", "packages", "pkg", "pkgs", "pack", "packs")
        add("jar", "jars")
        add("bottle", "bottles")
        add("bunch", "bunches")
        add("head", "heads")
        add("sprig", "sprigs")
        add("stalk", "stalks")
        add("handful", "handfuls")
        add("bag", "bags")
        add("box", "boxes")
        add("container", "containers")
        add("carton", "cartons")
        add("envelope", "envelopes")
        add("packet", "packets", "sachet", "sachets")
        add("sheet", "sheets")
        add("cube", "cubes")
        add("tub", "tubs")
        add("pouch", "pouches")
        add("drop", "drops")
        add("ear", "ears")
        add("fillet", "fillets", "filet", "filets")
        add("scoop", "scoops")
    }

    /**
     * The canonical unit for [word] as written, or null. Case matters only for the single letters:
     * "T" is a tablespoon and "t" a teaspoon; "C"/"c" is a cup. A trailing dot is ignored ("tbsp.").
     */
    fun lookup(word: String): Unit? {
        val w = word.trimEnd('.')
        if (w == "T" || w == "Tb" || w == "Tbsp" || w == "TBSP") return units["tbsp"]
        if (w == "t") return units["tsp"]
        val name = aliases[w.lowercase()] ?: return null
        return units[name]
    }

    /** The unit with canonical [name], or null. */
    fun byName(name: String): Unit? = units[name]

    /** Canonical names of every unit. */
    val names: Set<String> get() = units.keys

    /** Fallback weights ×10 for tiny amounts that no food lists: a pinch (0.4 g) and a dash (0.6 g). */
    internal val FALLBACK_GRAMS_X10: Map<String, Int> = mapOf("pinch" to 4, "dash" to 6, "drop" to 1)
}
