/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import java.io.File

/** Reads the colour tokens straight from the module's colors.xml files (tests run in the module dir). */
internal object ColorTokens {
    const val LIGHT: String = "light"
    const val DARK: String = "dark"
    val THEMES: List<String> = listOf(LIGHT, DARK)

    private val COLOR = Regex("""<color\s+name="([^"]+)">\s*#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})\s*</color>""")

    /** Token name to opaque ARGB for [theme]; dark falls back to light for tokens it doesn't override. */
    fun load(theme: String): Map<String, Int> {
        val light = parse(file("values"))
        return if (theme == LIGHT) light else light + parse(file("values-night"))
    }

    private fun file(dir: String): File {
        val f = File("src/main/res/$dir/colors.xml")
        return if (f.exists()) f else File("ui/src/main/res/$dir/colors.xml")
    }

    private fun parse(f: File): Map<String, Int> {
        val text = f.readText()
        val out = LinkedHashMap<String, Int>()
        for (m in COLOR.findAll(text)) {
            val hex = m.groupValues[2]
            val argb = if (hex.length == 6) 0xFF000000L or hex.toLong(16) else hex.toLong(16)
            out[m.groupValues[1]] = argb.toInt()
        }
        return out
    }
}
