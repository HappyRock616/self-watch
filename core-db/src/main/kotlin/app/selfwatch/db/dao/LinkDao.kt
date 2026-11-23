/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import android.database.Cursor
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.db.bindLongOrNull
import app.selfwatch.db.bool
import app.selfwatch.db.longOrNull
import app.selfwatch.db.queryList
import app.selfwatch.db.queryOne
import app.selfwatch.db.read
import app.selfwatch.db.withStatement
import app.selfwatch.db.write
import java.util.UUID

/**
 * The user's decision on whether app [packageName] (signed with [certSha256]) may talk to watch app
 * [appUuid] through the link.
 */
public data class LinkPermission(
    val packageName: String,
    val appUuid: UUID,
    val certSha256: String,
    val allowed: Boolean,
    val decidedSec: Long,
    val lastUsedSec: Long?,
)

/** Link permissions (table `link_permission`). */
public class LinkDao internal constructor(private val db: SelfWatchDb) {

    /** Stores [p], replacing any earlier decision for the same package and watch app. */
    public suspend fun set(p: LinkPermission) {
        db.write(setOf(Table.LINK_PERMISSION)) { d ->
            d.withStatement(
                "INSERT OR REPLACE INTO link_permission (package, app_uuid, cert_sha256, allowed, decided_sec, last_used_sec) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
            ) { st ->
                st.bindString(1, p.packageName)
                st.bindString(2, p.appUuid.toString())
                st.bindString(3, p.certSha256)
                st.bindLong(4, if (p.allowed) 1 else 0)
                st.bindLong(5, p.decidedSec)
                st.bindLongOrNull(6, p.lastUsedSec)
                st.executeInsert()
            }
        }
    }

    /** The decision for [packageName] and [appUuid], or null if never asked. */
    public suspend fun get(packageName: String, appUuid: UUID): LinkPermission? = db.read {
        it.queryOne("$SELECT WHERE package = ? AND app_uuid = ?", arrayOf(packageName, appUuid.toString()), ::map)
    }

    /** Every decision, by package then watch app. */
    public suspend fun list(): List<LinkPermission> = db.read { it.queryList("$SELECT ORDER BY package, app_uuid", null, ::map) }

    /** Records a use at [tSec]. Returns true if the permission exists. */
    public suspend fun touch(packageName: String, appUuid: UUID, tSec: Long): Boolean = db.write(setOf(Table.LINK_PERMISSION)) { d ->
        d.withStatement("UPDATE link_permission SET last_used_sec = ? WHERE package = ? AND app_uuid = ?") { st ->
            st.bindLong(1, tSec)
            st.bindString(2, packageName)
            st.bindString(3, appUuid.toString())
            st.executeUpdateDelete() > 0
        }
    }

    /** Forgets the decision (the app will be asked again). Returns true if it existed. */
    public suspend fun delete(packageName: String, appUuid: UUID): Boolean = db.write(setOf(Table.LINK_PERMISSION)) {
        it.delete("link_permission", "package = ? AND app_uuid = ?", arrayOf(packageName, appUuid.toString())) > 0
    }

    private companion object {
        const val SELECT = "SELECT package, app_uuid, cert_sha256, allowed, decided_sec, last_used_sec FROM link_permission"

        fun map(c: Cursor): LinkPermission =
            LinkPermission(c.getString(0), UUID.fromString(c.getString(1)), c.getString(2), c.bool(3), c.getLong(4), c.longOrNull(5))
    }
}
