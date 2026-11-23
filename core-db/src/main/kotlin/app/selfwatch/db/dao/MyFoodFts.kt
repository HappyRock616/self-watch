/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.database.sqlite.SQLiteDatabase
import app.selfwatch.db.args
import app.selfwatch.db.bindStrOrNull
import app.selfwatch.db.withStatement
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef

/**
 * Upkeep of `my_food_fts`, the FTS4 index over the user's own foods and recipes. Each row's docid is
 * `origin.code * 2^40 + id`, so one index covers both tables. Callers update it inside the same
 * transaction as the row it describes.
 */
internal object MyFoodFts {
    private const val SHIFT = 40

    /** The docid for [origin] row [id]. */
    fun docid(origin: FoodOrigin, id: Long): Long = (origin.code.toLong() shl SHIFT) + id

    /** The food a docid points to, or null if its origin code is unknown. */
    fun refOf(docid: Long): FoodRef? {
        val origin = FoodOrigin.fromCode((docid ushr SHIFT).toInt()) ?: return null
        return FoodRef(origin, docid and ((1L shl SHIFT) - 1))
    }

    /** Lowest docid of [origin] (inclusive) and the next origin's lowest (exclusive). */
    fun docidRange(origin: FoodOrigin): LongRange = docid(origin, 0) until docid(origin, 0) + (1L shl SHIFT)

    /** Indexes (or re-indexes) [origin] row [id]. */
    fun put(d: SQLiteDatabase, origin: FoodOrigin, id: Long, name: String, brand: String?) {
        remove(d, origin, id)
        d.withStatement("INSERT INTO my_food_fts (docid, name, brand) VALUES (?, ?, ?)") { st ->
            st.bindLong(1, docid(origin, id))
            st.bindString(2, name)
            st.bindStrOrNull(3, brand)
            st.executeInsert()
        }
    }

    /** Removes [origin] row [id] from the index. */
    fun remove(d: SQLiteDatabase, origin: FoodOrigin, id: Long) {
        d.delete("my_food_fts", "docid = ?", args(docid(origin, id)))
    }

    /**
     * An FTS4 MATCH expression for what the user typed: every word becomes a prefix term and all must
     * match (`"chick bre"` → `chick* bre*`). Punctuation and FTS operators are dropped, so any input
     * is safe. Returns null when there are no words.
     */
    fun matchQuery(text: String): String? {
        val words = ArrayList<String>()
        val sb = StringBuilder()
        fun endWord() {
            if (sb.isNotEmpty()) words.add(sb.toString().lowercase())
            sb.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) {
                sb.appendCodePoint(cp)
            } else {
                endWord()
            }
            i += Character.charCount(cp)
        }
        endWord()
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "$it*" }
    }
}
