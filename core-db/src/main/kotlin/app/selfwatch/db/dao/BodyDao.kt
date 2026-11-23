/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.args
import app.selfwatch.db.bindLongOrNull
import app.selfwatch.db.bool
import app.selfwatch.db.longOrNull
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.toTimeRange
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.time.DayRange
import app.selfwatch.model.time.TimeRange
import java.time.ZoneId

/** A user-defined measurement kind (table `measure_kind`), used with [BodyKind.CUSTOM]. */
public data class MeasureKind(val id: Long, val name: String, val unit: String, val hidden: Boolean)

/** Body measurements (table `body_measure`) and custom measurement kinds. */
public class BodyDao internal constructor(private val db: SelfWatchDb) {

    /** Stores [m] (its `id` is ignored) and returns the new row id. */
    public suspend fun insert(m: BodyMeasure): Long = db.write(setOf(Table.BODY)) { insertIn(it, m) }

    /** Rewrites the measurement with `m.id`. Returns true if it existed. */
    public suspend fun update(m: BodyMeasure): Boolean = db.write(setOf(Table.BODY)) { d ->
        d.withStatement(
            "UPDATE body_measure SET t_sec = ?, kind = ?, custom_kind_id = ?, value_x100 = ?, source = ?, device_id = ?, " +
                "import_id = ? WHERE id = ?",
        ) { st ->
            bindCols(st, m)
            st.bindLong(8, m.id)
            st.executeUpdateDelete() > 0
        }
    }

    /** Deletes measurement [id]. Returns true if it existed. */
    public suspend fun delete(id: Long): Boolean = db.write(setOf(Table.BODY)) { it.delete("body_measure", "id = ?", args(id)) > 0 }

    /** The measurement [id], or null. */
    public suspend fun get(id: Long): BodyMeasure? = db.read { it.queryOne("$SELECT WHERE id = ?", args(id), ::map) }

    /** The newest measurement of [kind] (for [BodyKind.CUSTOM], of [customKindId]), or null. */
    public suspend fun latest(kind: BodyKind, customKindId: Long? = null): BodyMeasure? = db.read { d ->
        val (where, a) = kindFilter(kind, customKindId)
        d.queryOne("$SELECT WHERE $where ORDER BY t_sec DESC, id DESC LIMIT 1", a, ::map)
    }

    /** Measurements of [kind] taken within [days] in [zone], oldest first. */
    public suspend fun series(kind: BodyKind, days: DayRange, zone: ZoneId, customKindId: Long? = null): List<BodyMeasure> =
        db.read { d ->
            val r = days.toTimeRange(zone)
            val (where, a) = kindFilter(kind, customKindId)
            d.queryList("$SELECT WHERE $where AND t_sec >= ? AND t_sec < ? ORDER BY t_sec, id", a + args(r.startSec, r.endSec), ::map)
        }

    /** Every measurement (all kinds) taken within [range] (everything when null), oldest first. For exports. */
    public suspend fun all(range: TimeRange? = null): List<BodyMeasure> = db.read { d ->
        if (range == null) {
            d.queryList("$SELECT ORDER BY t_sec, id", null, ::map)
        } else {
            d.queryList("$SELECT WHERE t_sec >= ? AND t_sec < ? ORDER BY t_sec, id", args(range.startSec, range.endSec), ::map)
        }
    }

    /** Custom measurement kinds, hidden ones included, by name. */
    public suspend fun customKinds(): List<MeasureKind> = db.read { d ->
        d.queryList("SELECT id, name, unit, hidden FROM measure_kind ORDER BY name COLLATE NOCASE, id", null) { c ->
            MeasureKind(c.getLong(0), c.getString(1), c.getString(2), c.bool(3))
        }
    }

    /** Adds a custom measurement kind and returns its id. */
    public suspend fun addCustomKind(name: String, unit: String): Long = db.write(setOf(Table.MEASURE_KIND)) { d ->
        d.withStatement("INSERT INTO measure_kind (name, unit) VALUES (?, ?)") { st ->
            st.bindString(1, name)
            st.bindString(2, unit)
            st.executeInsert()
        }
    }

    /** Renames a custom kind or changes its unit or visibility. Returns true if it existed. */
    public suspend fun updateCustomKind(k: MeasureKind): Boolean = db.write(setOf(Table.MEASURE_KIND)) { d ->
        d.withStatement("UPDATE measure_kind SET name = ?, unit = ?, hidden = ? WHERE id = ?") { st ->
            st.bindString(1, k.name)
            st.bindString(2, k.unit)
            st.bindLong(3, if (k.hidden) 1 else 0)
            st.bindLong(4, k.id)
            st.executeUpdateDelete() > 0
        }
    }

    internal companion object {
        private const val SELECT =
            "SELECT id, t_sec, kind, custom_kind_id, value_x100, source, device_id, import_id FROM body_measure"

        private fun kindFilter(kind: BodyKind, customKindId: Long?): Pair<String, Array<String>> =
            if (kind == BodyKind.CUSTOM && customKindId != null) {
                "kind = ${kind.code} AND custom_kind_id = ?" to args(customKindId)
            } else {
                "kind = ${kind.code}" to emptyArray()
            }

        private fun bindCols(st: SQLiteStatement, m: BodyMeasure) {
            st.bindLong(1, m.tSec)
            st.bindLong(2, m.kind.code.toLong())
            st.bindLongOrNull(3, m.customKindId)
            st.bindLong(4, m.valueX100.toLong())
            st.bindLong(5, m.source.code.toLong())
            st.bindLongOrNull(6, m.deviceId)
            st.bindLongOrNull(7, m.importId)
        }

        internal fun insertIn(d: SQLiteDatabase, m: BodyMeasure): Long = d.withStatement(
            "INSERT INTO body_measure (t_sec, kind, custom_kind_id, value_x100, source, device_id, import_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ) { st ->
            bindCols(st, m)
            st.executeInsert()
        }

        private fun map(c: Cursor): BodyMeasure = BodyMeasure(
            id = c.getLong(0),
            tSec = c.getLong(1),
            kind = BodyKind.fromCode(c.getInt(2)) ?: BodyKind.CUSTOM,
            customKindId = c.longOrNull(3),
            valueX100 = c.getInt(4),
            source = DataSource.fromCode(c.getInt(5)) ?: DataSource.IMPORT,
            deviceId = c.longOrNull(6),
            importId = c.longOrNull(7),
        )
    }
}
