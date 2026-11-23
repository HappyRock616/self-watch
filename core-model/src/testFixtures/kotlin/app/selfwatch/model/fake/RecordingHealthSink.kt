/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.fake

import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.FileStore
import app.selfwatch.model.watch.HealthSink
import app.selfwatch.model.watch.IngestBatch
import app.selfwatch.model.watch.SyncFileKey

/**
 * A [HealthSink] that keeps every batch in memory. A successful ingest of a keyed batch marks the key
 * as already had, like the real sink. Set [failNext] to make the next ingest fail (and record nothing).
 */
public class RecordingHealthSink : HealthSink {
    private val recorded = ArrayList<IngestBatch>()
    private val have = HashSet<SyncFileKey>()

    /** When non-null, the next [ingest] fails with this error, then it resets to null. */
    @Volatile
    public var failNext: SwError? = null

    override suspend fun ingest(batch: IngestBatch): Outcome<Unit> = synchronized(this) {
        failNext?.let {
            failNext = null
            return Outcome.Fail(it)
        }
        recorded.add(batch)
        batch.key?.let { have.add(it) }
        Outcome.Ok(Unit)
    }

    override suspend fun alreadyHave(key: SyncFileKey): Boolean = synchronized(this) { key in have }

    /** Marks [key] as already ingested without a batch (to test skipping). */
    @Synchronized
    public fun markHave(key: SyncFileKey) {
        have.add(key)
    }

    /** Every successfully ingested batch, in order. */
    @Synchronized
    public fun batches(): List<IngestBatch> = recorded.toList()
}

/** A [FileStore] that keeps saved files in memory under the path `mem://<deviceId>/<startSec>.fit`. */
public class FakeFileStore : FileStore {
    private val files = LinkedHashMap<String, ByteArray>()

    override suspend fun saveWorkoutFit(deviceId: Long, startSec: Long, bytes: ByteArray): String {
        val path = "mem://$deviceId/$startSec.fit"
        synchronized(this) { files[path] = bytes.copyOf() }
        return path
    }

    /** Saved files by path, in save order (copies). */
    @Synchronized
    public fun saved(): Map<String, ByteArray> = files.mapValues { it.value.copyOf() }
}
