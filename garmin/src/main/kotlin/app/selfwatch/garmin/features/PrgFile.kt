/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.WatchAppKind
import java.util.UUID

/**
 * What Self Watch needs from a Connect IQ `.prg` file: the app UUID and app type of its first entry point.
 *
 * @property appUuid the entry point's 16-byte UUID (big-endian, as in the file).
 * @property appType raw app type (watch face 0, watch app 1, data field 2, widget 3, background 4, audio
 *   content provider 5, glance 6).
 */
public data class PrgInfo(val appUuid: UUID, val appType: Int) {
    /** The app kind for [app.selfwatch.model.watch.WatchApp]. */
    val kind: WatchAppKind
        get() = when (appType) {
            PrgFile.TYPE_WATCH_FACE -> WatchAppKind.WATCH_FACE
            PrgFile.TYPE_DATA_FIELD -> WatchAppKind.DATA_FIELD
            PrgFile.TYPE_WIDGET -> WatchAppKind.WIDGET
            PrgFile.TYPE_WATCH_APP, PrgFile.TYPE_GLANCE -> WatchAppKind.APP
            else -> WatchAppKind.OTHER
        }

    /**
     * The watch capability flag that must be set to install this kind of app. The reference uploads every
     * PRG the same way; the flag per type is Self Watch's gate (the reference maps the types to the
     * IQWatchFaces / IQDataFields / IQWidgets / IQWatchApps file types).
     */
    val requiredCapability: GarminCapability
        get() = when (appType) {
            PrgFile.TYPE_WATCH_FACE -> GarminCapability.CONNECTIQ_WATCH_FACE_DOWNLOAD
            PrgFile.TYPE_DATA_FIELD -> GarminCapability.CONNECTIQ_DATA_FIELD_DOWNLOAD
            PrgFile.TYPE_WIDGET -> GarminCapability.CONNECTIQ_WIDGET_DOWNLOAD
            else -> GarminCapability.CONNECTIQ_WATCH_APP_DOWNLOAD
        }
}

/**
 * Reads the header of a Connect IQ `.prg` file (our own reader).
 *
 * Layout (big-endian): a sequence of sections, each `magic(u32) length(u32) content[length]`, until a
 * section with magic 0 or the end of the file. The file starts with the head section (`d0 00 d0 00` or
 * `d0 00 d0 0d`; the reference checks only the first three bytes `d0 00 d0`). The entry-points section
 * (`60 60 c0 de`) holds `count(u16)` then per entry `uuid[16] moduleId(u32) classId(u32) labelId(u32)
 * iconLabelId(u32) appType(u32)`.
 *
 * Ref: Gadgetbridge 160beab41 app/src/main/ksy/garmin_prg.ksy (section layout, magics, entry point, app types) and
 * service/devices/garmin/GarminPrgFile.kt (magic d0 00 d0, 10 MiB limit, app type from the first entry point)
 */
public object PrgFile {
    /** Largest PRG accepted (the reference's limit). */
    public const val MAX_SIZE: Int = 10 * 1024 * 1024

    /** App type: watch face. */
    public const val TYPE_WATCH_FACE: Int = 0

    /** App type: watch app. */
    public const val TYPE_WATCH_APP: Int = 1

    /** App type: data field. */
    public const val TYPE_DATA_FIELD: Int = 2

    /** App type: widget. */
    public const val TYPE_WIDGET: Int = 3

    /** App type: glance. */
    public const val TYPE_GLANCE: Int = 6

    /** Section magic of the head section. */
    public const val MAGIC_HEAD: Int = 0xd000d000.toInt()

    /** Section magic of the entry-points section. */
    public const val MAGIC_ENTRY_POINTS: Int = 0x6060c0de

    private const val ENTRY_SIZE = 16 + 5 * 4

    /** Parses [bytes]; fails with [SwError.BadInput] when they are not a PRG with an entry point. */
    public fun parse(bytes: ByteArray): Outcome<PrgInfo> {
        if (bytes.size > MAX_SIZE) return bad("PRG larger than ${MAX_SIZE / (1024 * 1024)} MiB")
        if (bytes.size < 8 || bytes[0] != 0xd0.toByte() || bytes[1] != 0.toByte() || bytes[2] != 0xd0.toByte()) {
            return bad("not a Connect IQ PRG file")
        }
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val magic = u32(bytes, pos)
            val len = u32(bytes, pos + 4).toLong() and 0xFFFF_FFFFL
            pos += 8
            if (magic == 0) break
            if (len > bytes.size - pos) return bad("PRG section 0x%08x runs past the end".format(magic))
            if (magic == MAGIC_ENTRY_POINTS) return entryPoint(bytes, pos, len.toInt())
            pos += len.toInt()
        }
        return bad("PRG has no entry point")
    }

    private fun entryPoint(b: ByteArray, start: Int, len: Int): Outcome<PrgInfo> {
        if (len < 2) return bad("PRG entry points truncated")
        val count = ((b[start].toInt() and 0xFF) shl 8) or (b[start + 1].toInt() and 0xFF)
        if (count == 0) return bad("PRG has no entry point")
        if (2 + ENTRY_SIZE > len) return bad("PRG entry point truncated")
        val p = start + 2
        val uuid = UUID(u64(b, p), u64(b, p + 8))
        val type = u32(b, p + 32)
        return Outcome.Ok(PrgInfo(uuid, type))
    }

    private fun bad(msg: String): Outcome<PrgInfo> = Outcome.Fail(SwError.BadInput(msg))

    private fun u32(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p).toLong() shl 32) or (u32(b, p + 4).toLong() and 0xFFFF_FFFFL)
}
