/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import android.health.connect.datatypes.DataOrigin
import android.health.connect.datatypes.Metadata
import android.health.connect.datatypes.Record
import app.selfwatch.model.time.Clock
import java.time.Instant
import java.time.ZoneId

/** In-memory [HcApi]: records inserts, serves scripted changes and stored records. */
class FakeHcApi(var granted: Set<String> = HcPermissions.build(history = true, background = true)) : HcApi {
    /** Every insert call, in order. */
    val inserts: MutableList<List<Record>> = ArrayList()

    /** Records served by [readRecords] (filtered by type and time). */
    val stored: MutableList<Record> = ArrayList()

    /** Pages served by [getChanges], in order, per token. */
    val changes: MutableMap<String, HcChangesPage> = HashMap()

    /** Tokens handed out. */
    var tokenCounter: Int = 0

    /** Thrown by every [insert] when set. */
    var insertError: Exception? = null

    /** Permissions this fake Health Connect defines beyond the basic ones (history, background). */
    var supported: Set<String> = emptySet()

    override suspend fun grantedPermissions(): Set<String> = granted

    override fun permissionSupported(permission: String): Boolean = permission in supported

    override suspend fun insert(records: List<Record>): List<String> {
        insertError?.let { throw it }
        inserts += records
        return records.indices.map { "hc-$it" }
    }

    override suspend fun changesToken(types: Set<Class<out Record>>): String = "token-${++tokenCounter}"

    override suspend fun getChanges(token: String): HcChangesPage =
        changes[token] ?: HcChangesPage(emptyList(), emptyList(), token, hasMore = false, tokenExpired = false)

    override suspend fun readRecords(type: Class<out Record>, start: Instant, end: Instant, pageToken: String?): HcPage =
        HcPage(stored.filter { type.isInstance(it) }, null)

    /** Every inserted record. */
    val inserted: List<Record> get() = inserts.flatten()
}

/** A settable clock in UTC. */
class TestClock(var now: Long) : Clock {
    override fun nowSec(): Long = now
    override fun zone(): ZoneId = ZoneId.of("UTC")
}

/** Metadata as Health Connect returns it for a record from [pkg] (the platform builder sets the data origin). */
fun origin(pkg: String, clientId: String? = null): Metadata =
    Metadata.Builder()
        .setId("id-${pkg.hashCode()}-${clientId.hashCode()}")
        .setDataOrigin(DataOrigin.Builder().setPackageName(pkg).build())
        .setClientRecordId(clientId)
        .setRecordingMethod(Metadata.RECORDING_METHOD_MANUAL_ENTRY)
        .build()
