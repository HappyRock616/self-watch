/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch

import app.selfwatch.ui.SwTheme

/**
 * The Appearance settings (`theme` = night mode, `accent` = color theme) as theme values.
 *
 * The color theme is stored under the old `accent` key as the theme's primary color (`#RRGGBB`), so the
 * default (`#FF8A1F`) is Orange as before. Values from the old four-accent picker are read as the nearest
 * theme and rewritten once ([migratedValue]): orange to Orange, blue to Turquoise, green to Forest,
 * violet to Purple.
 */
data class Appearance(
    /** Night mode. */
    val night: SwTheme.NightMode = SwTheme.DEFAULT_NIGHT_MODE,
    /** Color theme. */
    val theme: SwTheme.Theme = SwTheme.DEFAULT_THEME,
) {
    /** Parsing of the stored setting values. */
    companion object {
        /** The value stored for each theme. */
        val STORED: Map<SwTheme.Theme, String> = mapOf(
            SwTheme.Theme.TURQUOISE to "#19D3C5",
            SwTheme.Theme.PURPLE to "#BD93F9",
            SwTheme.Theme.ORANGE to "#FF8A1F",
            SwTheme.Theme.YELLOW to "#F5B841",
            SwTheme.Theme.FOREST to "#2E9E5B",
        )

        private val CURRENT: Map<String, SwTheme.Theme> = STORED.entries.associate { (k, v) -> v to k }

        /** The old accent picker's values (both palettes) and the theme each becomes. */
        private val LEGACY: Map<String, SwTheme.Theme> = mapOf(
            "#E67400" to SwTheme.Theme.ORANGE,
            "#4DABF7" to SwTheme.Theme.TURQUOISE, "#1C7FC4" to SwTheme.Theme.TURQUOISE,
            "#5BC08A" to SwTheme.Theme.FOREST, "#2E8B57" to SwTheme.Theme.FOREST,
            "#9B8CFF" to SwTheme.Theme.PURPLE, "#6B5BD6" to SwTheme.Theme.PURPLE,
        )

        /** `dark` / `light` / `system`; anything else (or null) is the default, dark. */
        fun nightOf(theme: String?): SwTheme.NightMode = when (theme?.trim()?.lowercase()) {
            "light" -> SwTheme.NightMode.LIGHT
            "system" -> SwTheme.NightMode.SYSTEM
            else -> SwTheme.DEFAULT_NIGHT_MODE
        }

        /** A stored value (current or old) to a color theme; unknown values give Orange. */
        fun themeOf(stored: String?): SwTheme.Theme {
            val key = stored?.trim()?.uppercase() ?: return SwTheme.DEFAULT_THEME
            return CURRENT[key] ?: LEGACY[key] ?: SwTheme.DEFAULT_THEME
        }

        /** The value to store for [theme]. */
        fun storedValue(theme: SwTheme.Theme): String = STORED.getValue(theme)

        /** The new value for an old (or unreadable) stored value, or null when [stored] needs no change. */
        fun migratedValue(stored: String?): String? {
            if (stored == null || stored.trim().uppercase() in CURRENT) return null
            return storedValue(themeOf(stored))
        }

        /** Both settings at once. */
        fun parse(theme: String?, accent: String?): Appearance = Appearance(nightOf(theme), themeOf(accent))
    }
}
