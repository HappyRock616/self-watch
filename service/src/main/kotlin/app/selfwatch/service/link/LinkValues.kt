/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.CiqValue

/**
 * Converts between the link's Java values (what `LinkCodec` reads and writes), JSON (the Gadgetbridge
 * Intent and the settings editor) and [CiqValue] (what the watch driver sends). WP-13 "LinkCodec ↔
 * CiqValue mapping" has the table. There is one codec: bytes always go through `LinkCodec`.
 */
public object LinkValues {
    private const val TAG = "LinkValues"

    /** Deepest nesting converted (as `LinkCodec.MAX_DEPTH`). */
    public const val MAX_DEPTH: Int = 16

    /**
     * The [CiqValue] for a value `LinkCodec.decode` returned: null, Boolean, Long (also Byte, Short,
     * Integer), Double (also Float), String (also CharSequence, Character), List and Map.
     *
     * @throws IllegalArgumentException for any other type or nesting deeper than [MAX_DEPTH].
     */
    public fun toCiq(v: Any?): CiqValue = toCiq(v, 0)

    private fun toCiq(v: Any?, depth: Int): CiqValue {
        require(depth <= MAX_DEPTH) { "nested too deeply" }
        return when (v) {
            null -> CiqValue.Null
            is Boolean -> CiqValue.Bool(v)
            is Long -> CiqValue.Num(v)
            is Int -> CiqValue.Num(v.toLong())
            is Short -> CiqValue.Num(v.toLong())
            is Byte -> CiqValue.Num(v.toLong())
            is Double -> CiqValue.Dbl(v)
            is Float -> CiqValue.Dbl(v.toDouble())
            is CharSequence -> CiqValue.Str(v.toString())
            is Char -> CiqValue.Str(v.toString())
            is List<*> -> CiqValue.Arr(v.map { toCiq(it, depth + 1) })
            is Map<*, *> -> {
                val out = LinkedHashMap<CiqValue, CiqValue>()
                for ((k, x) in v) out[toCiq(k, depth + 1)] = toCiq(x, depth + 1)
                CiqValue.Dict(out)
            }
            else -> throw IllegalArgumentException("unsupported type ${v.javaClass.simpleName}")
        }
    }

    /**
     * The Java value `LinkCodec.encode` takes for [v]: null, Boolean, Long, Double, String, ArrayList and
     * LinkedHashMap. A dictionary entry whose key is a list or dictionary can't be passed to a phone app:
     * it is dropped (logged at DEBUG).
     */
    public fun fromCiq(v: CiqValue): Any? = when (v) {
        CiqValue.Null -> null
        is CiqValue.Bool -> v.v
        is CiqValue.Num -> v.v
        is CiqValue.Dbl -> v.v
        is CiqValue.Str -> v.v
        is CiqValue.Arr -> v.v.mapTo(ArrayList(v.v.size)) { fromCiq(it) }
        is CiqValue.Dict -> {
            val out = LinkedHashMap<Any?, Any?>()
            for ((k, x) in v.v) {
                if (k is CiqValue.Arr || k is CiqValue.Dict) {
                    SwLog.d(TAG) { "dropped a dictionary entry with a container key" }
                    continue
                }
                out[fromCiq(k)] = fromCiq(x)
            }
            out
        }
    }

    /**
     * The [CiqValue] for JSON [j]: objects become dictionaries with string keys, whole numbers that fit
     * in a Long become [CiqValue.Num], other numbers [CiqValue.Dbl].
     */
    public fun fromJson(j: JsonValue): CiqValue = when (j) {
        JsonValue.Null -> CiqValue.Null
        is JsonValue.Bool -> CiqValue.Bool(j.v)
        is JsonValue.Num -> {
            val isWhole = j.text.none { it == '.' || it == 'e' || it == 'E' }
            val l = if (isWhole) j.toLongOrNull() else null
            if (l != null) CiqValue.Num(l) else CiqValue.Dbl(j.toDouble())
        }
        is JsonValue.Str -> CiqValue.Str(j.v)
        is JsonValue.Arr -> CiqValue.Arr(j.items.map { fromJson(it) })
        is JsonValue.Obj -> CiqValue.Dict(j.fields.entries.associateTo(LinkedHashMap()) { (k, x) -> CiqValue.Str(k) to fromJson(x) })
    }

    /**
     * JSON for [v]. Dictionary keys become strings (a number key 3 becomes "3"; container keys are
     * dropped); non-finite doubles become null.
     */
    public fun toJson(v: CiqValue): JsonValue = when (v) {
        CiqValue.Null -> JsonValue.Null
        is CiqValue.Bool -> JsonValue.Bool(v.v)
        is CiqValue.Num -> JsonValue.Num.of(v.v)
        is CiqValue.Dbl -> if (v.v.isFinite()) JsonValue.Num.of(v.v) else JsonValue.Null
        is CiqValue.Str -> JsonValue.Str(v.v)
        is CiqValue.Arr -> JsonValue.Arr(v.v.map { toJson(it) })
        is CiqValue.Dict -> {
            val out = LinkedHashMap<String, JsonValue>()
            for ((k, x) in v.v) {
                val key = when (k) {
                    CiqValue.Null -> "null"
                    is CiqValue.Bool -> k.v.toString()
                    is CiqValue.Num -> k.v.toString()
                    is CiqValue.Dbl -> k.v.toString()
                    is CiqValue.Str -> k.v
                    is CiqValue.Arr, is CiqValue.Dict -> continue
                }
                out[key] = toJson(x)
            }
            JsonValue.Obj(out)
        }
    }

    /** [v] as JSON text. */
    public fun toJsonText(v: CiqValue): String = Json.write(toJson(v))

    /**
     * A Gadgetbridge `SEND_WATCH_APP_MESSAGE` payload: valid JSON is converted ([fromJson]); anything else
     * (including a bare word) is sent as a string.
     */
    public fun gadgetbridgePayload(payload: String): CiqValue = when (val j = Json.parse(payload)) {
        is Outcome.Ok -> fromJson(j.value)
        is Outcome.Fail -> CiqValue.Str(payload)
    }

    /** A JSON object text of setting key → value as a map of [CiqValue]s, or null when it is not an object. */
    public fun settingsFromJson(text: String): Map<String, CiqValue>? {
        val obj = (Json.parse(text) as? Outcome.Ok)?.value as? JsonValue.Obj ?: return null
        return obj.fields.entries.associateTo(LinkedHashMap()) { (k, v) -> k to fromJson(v) }
    }

    /** [values] (setting key → value) as JSON object text. */
    public fun settingsToJson(values: Map<String, CiqValue>): String =
        Json.write(JsonValue.Obj(values.entries.associateTo(LinkedHashMap()) { (k, v) -> k to toJson(v) }))
}
