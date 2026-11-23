/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.files

import app.selfwatch.garmin.gfdi.ByteReader
import app.selfwatch.garmin.gfdi.ByteWriter
import app.selfwatch.garmin.gfdi.MalformedException
import app.selfwatch.garmin.session.GarminTime
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError

/**
 * One 16-byte directory entry: index u16, data type u8, subtype u8, number u16, specific flags u8,
 * file flags u8, size u32, timestamp u32 (Garmin epoch; 0 = no date). Little-endian.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/FileTransferHandler.java (parseDirectoryEntries layout)
 */
public data class DirEntry(
    val index: Int,
    val dataType: Int,
    val subType: Int,
    val number: Int,
    val specificFlags: Int,
    val fileFlags: Int,
    val size: Long,
    val garminTime: Long,
) {
    /** Unix seconds of the file's timestamp, or null when the watch gave none. */
    public val tSec: Long? get() = if (garminTime == 0L) null else GarminTime.toUnix(garminTime)

    /** True when the archive flag (0x10) is already set. */
    public val archived: Boolean get() = fileFlags and 0x10 != 0

    /** The FIT file kind we fetch, or null for anything else. */
    public val kind: FitFileKind? get() = if (dataType == FitFileKind.FIT_DATA_TYPE) FitFileKind.of(subType) else null

    /** The 16 wire bytes. */
    public fun encode(): ByteArray = ByteWriter(SIZE).u16(index).u8(dataType).u8(subType).u16(number).u8(specificFlags)
        .u8(fileFlags).i32(size.toInt()).i32(garminTime.toInt()).toByteArray()

    /** Parsing. */
    public companion object {
        /** Bytes per entry. */
        public const val SIZE: Int = 16

        /** Reads one entry from [r]. */
        public fun read(r: ByteReader): DirEntry = DirEntry(r.u16(), r.u8(), r.u8(), r.u16(), r.u8(), r.u8(), r.u32(), r.u32())

        /** Parses a whole directory file (file index 0). */
        public fun parseDirectory(bytes: ByteArray): Outcome<List<DirEntry>> {
            if (bytes.size % SIZE != 0) return Outcome.Fail(SwError.BadInput("directory length ${bytes.size} is not a multiple of $SIZE"))
            return try {
                val r = ByteReader(bytes)
                Outcome.Ok(List(bytes.size / SIZE) { read(r) })
            } catch (e: MalformedException) {
                Outcome.Fail(SwError.BadInput("directory: ${e.message}"))
            }
        }
    }
}

/**
 * The FIT file subtypes Self Watch fetches (data type 128 = FIT).
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/FileType.java (FILETYPE data type / subtype pairs)
 */
public enum class FitFileKind(public val subType: Int, public val group: Int) {
    /** Workouts: saved as received, then decoded for the summary. */
    ACTIVITY(4, 0),

    /** Weight entries. */
    WEIGHT(9, 3),

    /** Monitoring (older "A" variant). */
    MONITOR_A(15, 2),

    /** Daily monitoring summary. */
    MONITOR_DAILY(28, 2),

    /** Monitoring: steps, heart rate, stress, ... */
    MONITOR(32, 2),

    /** Metrics. */
    METRICS(44, 3),

    /** Sleep. */
    SLEEP(49, 1),

    /** HRV status. */
    HRV_STATUS(68, 3),

    /** Skin temperature. */
    SKIN_TEMP(73, 3),
    ;

    /** Lookup. */
    public companion object {
        /** FIT data type in directory entries. */
        public const val FIT_DATA_TYPE: Int = 128

        /** The kind with [subType], or null. */
        public fun of(subType: Int): FitFileKind? = entries.firstOrNull { it.subType == subType }
    }
}
