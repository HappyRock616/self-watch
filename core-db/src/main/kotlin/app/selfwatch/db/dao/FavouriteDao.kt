/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef

/** Favourite foods of any origin (table `favourite`). */
public class FavouriteDao internal constructor(private val db: SelfWatchDb) {

    /** Marks [ref] a favourite at [addedSec] (already a favourite: the time is updated). */
    public suspend fun add(ref: FoodRef, addedSec: Long) {
        db.write(setOf(Table.FAVOURITE)) { d ->
            d.withStatement("INSERT OR REPLACE INTO favourite (food_origin, food_id, added_sec) VALUES (?, ?, ?)") { st ->
                st.bindLong(1, ref.origin.code.toLong())
                st.bindLong(2, ref.id)
                st.bindLong(3, addedSec)
                st.executeInsert()
            }
        }
    }

    /** Removes [ref] from the favourites. Returns true if it was one. */
    public suspend fun remove(ref: FoodRef): Boolean = db.write(setOf(Table.FAVOURITE)) {
        it.delete("favourite", "food_origin = ? AND food_id = ?", args(ref.origin.code, ref.id)) > 0
    }

    /** True if [ref] is a favourite. */
    public suspend fun isFavourite(ref: FoodRef): Boolean = db.read {
        it.queryOne("SELECT 1 FROM favourite WHERE food_origin = ? AND food_id = ?", args(ref.origin.code, ref.id)) { true } ?: false
    }

    /** All favourites, newest first. */
    public suspend fun list(): List<FoodRef> = db.read { d ->
        d.queryList("SELECT food_origin, food_id FROM favourite ORDER BY added_sec DESC, food_origin, food_id", null) { c ->
            FoodOrigin.fromCode(c.getInt(0))?.let { FoodRef(it, c.getLong(1)) }
        }.filterNotNull()
    }
}
