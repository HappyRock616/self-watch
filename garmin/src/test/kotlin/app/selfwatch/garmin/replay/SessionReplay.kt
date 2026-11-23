/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.replay

import app.selfwatch.garmin.link.GarminUuids
import app.selfwatch.model.fake.FakeBlePort
import app.selfwatch.model.fake.FakeBytePipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/**
 * One BleLog record (WP-05): 8-byte time in ms, 1 byte direction, 1 byte pipe index, 2-byte length,
 * then the bytes. Big-endian (Java DataOutputStream). Direction [TO_WATCH] is a write, [FROM_WATCH] a
 * notification. Pipe index is the order the pipes were opened: 0 = the GFDI pipe (multi-link or V1),
 * 1 = the watch-app pipe.
 */
class BleRecord(val timeMs: Long, val direction: Int, val pipe: Int, val bytes: ByteArray) {
    companion object {
        const val TO_WATCH = 0
        const val FROM_WATCH = 1
    }
}

/** Reads BleLog files; a truncated last record is ignored. */
object BleLogReader {
    fun read(file: File): List<BleRecord> {
        val out = ArrayList<BleRecord>()
        DataInputStream(file.inputStream().buffered()).use { inp ->
            try {
                while (true) {
                    val t = inp.readLong()
                    val dir = inp.readUnsignedByte()
                    val pipe = inp.readUnsignedByte()
                    val len = inp.readUnsignedShort()
                    val b = ByteArray(len)
                    inp.readFully(b)
                    out.add(BleRecord(t, dir, pipe, b))
                }
            } catch (e: EOFException) {
                // end of file (or a truncated last record)
            }
        }
        return out
    }
}

/**
 * Plays the watch side of a recording into a [FakeBlePort]: each notification is queued once the code
 * under test has made at least as many writes on that pipe as the original phone had made by then.
 * Sessions are assumed to be multi-link unless the file name contains ".v1.".
 */
class SessionReplay(val records: List<BleRecord>, val v1: Boolean, val maxWrite: Int = 244) {
    val port = FakeBlePort(maxWrite = maxWrite)
    private val pipes: Map<Int, FakeBytePipe>

    init {
        val gfdi = if (v1) {
            port.pipe(GarminUuids.V1_SERVICE, GarminUuids.V1_RX, GarminUuids.V1_TX)
        } else {
            port.pipe(GarminUuids.ML_SERVICE, GarminUuids.ML_RX[0], GarminUuids.ML_TX[0])
        }
        val app = port.pipe(GarminUuids.WATCH_APP_SERVICE, GarminUuids.WATCH_APP_RX, GarminUuids.WATCH_APP_TX)
        pipes = mapOf(0 to gfdi, 1 to app)
        val services = mutableSetOf(if (v1) GarminUuids.V1_SERVICE else GarminUuids.ML_SERVICE)
        if (records.any { it.pipe == 1 }) services.add(GarminUuids.WATCH_APP_SERVICE)
        port.services = services
    }

    /** Feeds every notification in order; returns how many were queued before their writes caught up (mismatches). */
    suspend fun play(waitMs: Long = 10_000): Int {
        val writesSoFar = HashMap<Int, Int>()
        var late = 0
        for (r in records) {
            val pipe = pipes[r.pipe] ?: continue
            if (r.direction == BleRecord.TO_WATCH) {
                writesSoFar[r.pipe] = (writesSoFar[r.pipe] ?: 0) + 1
                continue
            }
            val need = writesSoFar[r.pipe] ?: 0
            val ok = withTimeoutOrNull(waitMs) {
                while (pipe.writes().size < need) delay(10)
                true
            }
            if (ok == null) late++
            pipe.queueIncoming(r.bytes)
        }
        return late
    }

    /** Ends the link. */
    fun end() = pipes.values.forEach { it.endIncoming() }
}
