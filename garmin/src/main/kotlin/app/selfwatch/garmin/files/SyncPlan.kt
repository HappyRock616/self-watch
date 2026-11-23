/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.files

import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.HealthSink
import app.selfwatch.model.watch.IngestBatch
import app.selfwatch.model.watch.SyncFileKey

/**
 * Turns downloaded FIT files into [IngestBatch]es. Implemented with the `fit` module's decoders; kept
 * as an interface so `garmin` does not depend on decoder internals and tests can fake it.
 */
public interface FitDecoders {
    /** True when files of [kind] can be decoded; others are skipped by [SyncPlan]. */
    public fun supports(kind: FitFileKind): Boolean

    /**
     * Decodes [bytes] of [kind] for device [deviceId]. For [FitFileKind.ACTIVITY], [fitPath] is where
     * the original was saved and belongs in the workout. The driver sets the batch key itself.
     */
    public fun decode(kind: FitFileKind, bytes: ByteArray, deviceId: Long, fitPath: String?): Outcome<IngestBatch>

    /** No decoders: nothing is fetched. */
    public object None : FitDecoders {
        override fun supports(kind: FitFileKind): Boolean = false
        override fun decode(kind: FitFileKind, bytes: ByteArray, deviceId: Long, fitPath: String?): Outcome<IngestBatch> =
            Outcome.Fail(app.selfwatch.model.result.SwError.BadInput("no decoder for $kind"))
    }
}

/** One file to fetch, with its sync key. */
public data class PlannedFile(val entry: DirEntry, val kind: FitFileKind, val key: SyncFileKey)

/**
 * Which directory entries to download and in what order.
 *
 * Rules (WP-06 §5): FIT files of the kinds in [FitFileKind] that the decoders support; skip what the
 * sink already has and anything over [maxBytes]; activities first, then sleep, then monitoring, then
 * the rest; oldest first within each group. Entries already ingested but not archived are listed in
 * [toArchive] so the watch stops offering them.
 */
public data class SyncPlan(val files: List<PlannedFile>, val toArchive: List<PlannedFile>, val skipped: Int) {
    /** Building plans. */
    public companion object {
        /** Files larger than this are skipped (20 MB). */
        public const val MAX_FILE_BYTES: Long = 20L * 1024 * 1024

        /** Builds the plan for [entries] of device [deviceId]. */
        public suspend fun build(
            entries: List<DirEntry>,
            deviceId: Long,
            sink: HealthSink,
            decoders: FitDecoders,
            maxBytes: Long = MAX_FILE_BYTES,
        ): SyncPlan {
            val fetch = ArrayList<PlannedFile>()
            val archive = ArrayList<PlannedFile>()
            var skipped = 0
            for (e in entries) {
                val kind = e.kind
                if (kind == null || !decoders.supports(kind) || e.index == 0) {
                    skipped++
                    continue
                }
                if (e.size <= 0 || e.size > maxBytes) {
                    skipped++
                    continue
                }
                val key = keyOf(deviceId, e)
                val p = PlannedFile(e, kind, key)
                if (sink.alreadyHave(key)) {
                    if (!e.archived) archive.add(p)
                    continue
                }
                fetch.add(p)
            }
            fetch.sortWith(compareBy<PlannedFile>({ it.kind.group }, { it.entry.garminTime }, { it.entry.index }))
            return SyncPlan(fetch, archive, skipped)
        }

        /** The sync key of directory entry [e]. */
        public fun keyOf(deviceId: Long, e: DirEntry): SyncFileKey =
            SyncFileKey(deviceId, e.index, e.dataType, e.subType, e.tSec ?: 0L, e.size.toInt())
    }
}
