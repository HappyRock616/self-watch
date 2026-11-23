/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.io

import app.selfwatch.model.json.JsonValue

/** Small builders for [JsonValue] trees. */
object JsonBuild {
    /** An object of [fields]; null values are written as JSON null. */
    fun obj(vararg fields: Pair<String, Any?>): JsonValue.Obj = obj(fields.asList())

    /** An object of [fields]; null values are written as JSON null. */
    fun obj(fields: List<Pair<String, Any?>>): JsonValue.Obj {
        val m = LinkedHashMap<String, JsonValue>()
        for ((k, v) in fields) m[k] = of(v)
        return JsonValue.Obj(m)
    }

    /** An object without the fields whose value is null. */
    fun objNoNulls(vararg fields: Pair<String, Any?>): JsonValue.Obj = obj(fields.filter { it.second != null })

    /** Converts strings, numbers, booleans, lists, maps and [JsonValue]s. */
    fun of(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is JsonValue -> v
        is String -> JsonValue.Str(v)
        is Boolean -> JsonValue.Bool(v)
        is Int -> JsonValue.Num.of(v.toLong())
        is Long -> JsonValue.Num.of(v)
        is Double -> if (v.isFinite()) JsonValue.Num.of(v) else JsonValue.Null
        is Collection<*> -> JsonValue.Arr(v.map { of(it) })
        is Map<*, *> -> JsonValue.Obj(v.entries.associate { it.key.toString() to of(it.value) })
        else -> JsonValue.Str(v.toString())
    }

    /** The text of a string value, or a number's text, or null. */
    fun text(v: JsonValue?): String? = when (v) {
        is JsonValue.Str -> v.v
        is JsonValue.Num -> v.text
        else -> null
    }
}
