/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.log

import android.content.Context
import app.selfwatch.model.log.SwLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Optional Bluetooth capture ("Bluetooth capture" in About → Debug). While [enabled], every write
 * and notification is appended to `<dir>/<yyyy-mm-dd>.bin` in [BleLogFormat]. Off by default.
 * Files older than [KEEP_DAYS] days are deleted when capture is turned on and when a new day's file
 * starts. Thread-safe; each record is flushed at once so a crash loses at most a partial record,
 * which the reader skips.
 *
 * Captures contain message payloads (possibly notification text), so they stay on the phone unless
 * the user shares them from the export screen.
 */
public class BleLog(
    /** Directory holding the day files. Created on first write. */
    public val dir: File,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val lock = Any()
    private var out: OutputStream? = null
    private var outDay: LocalDate? = null

    /** Whether records are written. Turning it on runs the 7-day cleanup; turning it off closes the file. */
    @Volatile
    public var enabled: Boolean = false
        set(value) {
            synchronized(lock) {
                field = value
                if (value) cleanup() else closeFile()
            }
        }

    /** Appends one record if [enabled]. [direction] is a `BleLogFormat.DIR_*` value. Never throws. */
    public fun record(direction: Int, pipe: Int, bytes: ByteArray) {
        if (!enabled) return
        synchronized(lock) {
            if (!enabled) return
            val now = clockMs()
            try {
                val stream = streamFor(day(now))
                val payload = if (bytes.size > BleLogFormat.MAX_PAYLOAD) bytes.copyOf(BleLogFormat.MAX_PAYLOAD) else bytes
                stream.write(BleLogFormat.encode(BleLogRecord(now, direction, pipe, payload)))
                stream.flush()
            } catch (e: IOException) {
                SwLog.w(TAG, "capture write failed; turning capture off", e)
                closeFile()
                enabled = false
            }
        }
    }

    /** The capture files, oldest first. */
    public fun files(): List<File> =
        dir.listFiles { f -> f.isFile && parseDay(f.name) != null }?.sortedBy { it.name } ?: emptyList()

    /** Deletes capture files more than [keepDays] days old. */
    public fun cleanup(keepDays: Int = KEEP_DAYS) {
        val oldest = day(clockMs()).minusDays(keepDays.toLong())
        synchronized(lock) {
            for (f in files()) {
                val d = parseDay(f.name) ?: continue
                if (d.isBefore(oldest) && d != outDay && !f.delete()) SwLog.w(TAG, "could not delete ${f.name}")
            }
        }
    }

    /** Flushes and closes the current file (capture stays [enabled] and reopens on the next record). */
    public fun close() {
        synchronized(lock) { closeFile() }
    }

    private fun streamFor(d: LocalDate): OutputStream {
        val current = out
        if (current != null && outDay == d) return current
        closeFile()
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val stream = FileOutputStream(File(dir, "$d$SUFFIX"), true)
        out = stream
        outDay = d
        cleanup()
        return stream
    }

    private fun closeFile() {
        try {
            out?.close()
        } catch (e: IOException) {
            SwLog.w(TAG, "close failed", e)
        }
        out = null
        outDay = null
    }

    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private fun parseDay(name: String): LocalDate? {
        if (!name.endsWith(SUFFIX)) return null
        return try {
            LocalDate.parse(name.removeSuffix(SUFFIX))
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Constants and the app-default instance. */
    public companion object {
        private const val TAG = "BleLog"
        private const val SUFFIX = ".bin"

        /** Days of captures kept. */
        public const val KEEP_DAYS: Int = 7

        /** A log in the app's `files/blelog/` directory. */
        public fun forContext(context: Context): BleLog = BleLog(File(context.filesDir, "blelog"))
    }
}
