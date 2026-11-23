/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.io

import app.selfwatch.interop.api.ColumnMapping

/**
 * Maps a file's header names to target fields through a table of aliases per field.
 *
 * Matching is case-insensitive, ignores units in brackets (`Fat (g)` matches the alias `fat`), treats
 * `_` and `-` as spaces and collapses whitespace. Each header goes to at most one field; aliases are
 * tried in order, so put the most specific first.
 *
 * @param aliases target field to its accepted header names.
 */
class ColumnMatcher(private val aliases: Map<String, List<String>>) {
    /** Matches [header] (the first row of a file). */
    fun match(header: List<String>): ColumnMap {
        val norm = header.map { normalize(it) }
        val used = BooleanArray(header.size)
        val result = LinkedHashMap<String, Int>()
        for ((field, names) in aliases) {
            for (alias in names) {
                val a = normalize(alias)
                val idx = norm.indices.firstOrNull { !used[it] && norm[it] == a } ?: continue
                used[idx] = true
                result[field] = idx
                break
            }
        }
        return ColumnMap(header, result)
    }

    /** Helpers. */
    companion object {
        private val BRACKETS = Regex("""\([^)]*\)|\[[^]]*]""")
        private val SPACES = Regex("""[\s_\-]+""")
        private val UNIT = Regex("""[(\[]\s*([^)\]]+?)\s*[)\]]""")

        /** Lower-case, without bracketed units, byte order mark or extra spaces. */
        fun normalize(header: String): String =
            header.replace(0xFEFF.toChar().toString(), "").replace(BRACKETS, " ").replace(SPACES, " ").trim().lowercase()

        /** The unit written in brackets in [header] (`Sodium (mg)` gives "mg"), lower-case, or null. */
        fun unitOf(header: String): String? = UNIT.find(header)?.groupValues?.get(1)?.lowercase()
    }
}

/** The result of [ColumnMatcher.match]: where each field is, and helpers to read rows by field. */
class ColumnMap(val header: List<String>, private val indexByField: Map<String, Int>) {
    /** Column index of [field], or -1. */
    fun index(field: String): Int = indexByField[field] ?: -1

    /** True if [field] was found. */
    fun has(field: String): Boolean = field in indexByField

    /** The trimmed cell of [field] in [row], or null if the column is missing or the cell blank. */
    operator fun get(row: List<String>, field: String): String? {
        val i = indexByField[field] ?: return null
        return row.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The unit in brackets in [field]'s header, or null. */
    fun unit(field: String): String? = indexByField[field]?.let { ColumnMatcher.unitOf(header[it]) }

    /** Header text of [field], or null. */
    fun headerOf(field: String): String? = indexByField[field]?.let { header[it] }

    /** One mapping per field in [fields] (found or not), for the preview. */
    fun mappings(fields: Collection<String>): List<ColumnMapping> =
        fields.map { f -> ColumnMapping(f, headerOf(f), index(f)) }

    /** Headers no field claimed. */
    fun unmatched(): List<String> {
        val used = indexByField.values.toSet()
        return header.indices.filter { it !in used && header[it].isNotBlank() }.map { header[it] }
    }
}
