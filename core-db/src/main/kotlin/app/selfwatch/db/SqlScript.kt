/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

/**
 * Splits an SQL script into single statements, because `SQLiteDatabase.execSQL` runs only one.
 *
 * Comments (`--` and C style) are removed. Semicolons inside quotes (`'…'`, `"…"`, `` `…` ``,
 * `[…]`) do not split. A `CREATE TRIGGER` statement runs until its closing `END;`.
 */
internal object SqlScript {

    /** The statements of [script], trimmed, without comments and without the trailing semicolon. */
    fun split(script: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        val n = script.length
        while (i < n) {
            val c = script[i]
            when {
                c == '-' && i + 1 < n && script[i + 1] == '-' -> {
                    while (i < n && script[i] != '\n') i++
                    sb.append(' ')
                }
                c == '/' && i + 1 < n && script[i + 1] == '*' -> {
                    val end = script.indexOf("*/", i + 2)
                    i = if (end < 0) n else end + 2
                    sb.append(' ')
                }
                c == '\'' || c == '"' || c == '`' || c == '[' -> {
                    val close = if (c == '[') ']' else c
                    sb.append(c)
                    i++
                    while (i < n) {
                        val d = script[i]
                        sb.append(d)
                        i++
                        if (d == close) {
                            // A doubled quote is an escaped quote, not the end.
                            if (close != ']' && i < n && script[i] == close) {
                                sb.append(close)
                                i++
                            } else {
                                break
                            }
                        }
                    }
                }
                c == ';' -> {
                    i++
                    if (isOpenTrigger(sb)) {
                        sb.append(';')
                    } else {
                        flush(sb, out)
                    }
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        flush(sb, out)
        return out
    }

    private fun flush(sb: StringBuilder, out: MutableList<String>) {
        val s = sb.toString().trim()
        if (s.isNotEmpty()) out.add(s)
        sb.setLength(0)
    }

    private val triggerStart = Regex("^\\s*CREATE\\s+(TEMP\\s+|TEMPORARY\\s+)?TRIGGER\\b", RegexOption.IGNORE_CASE)
    private val endsWithEnd = Regex("\\bEND\\s*$", RegexOption.IGNORE_CASE)

    /** True while [sb] holds a CREATE TRIGGER whose body has not reached `END`. */
    private fun isOpenTrigger(sb: StringBuilder): Boolean =
        triggerStart.containsMatchIn(sb) && !endsWithEnd.containsMatchIn(sb)
}
