/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import com.google.protobuf.ByteString
import kotlinx.coroutines.isActive
import java.util.UUID

/** UUID ↔ 16 bytes, most significant first (Ref: Gadgetbridge 160beab41 util/UuidUtil.java). */
internal object UuidBytes {
    fun toBytes(u: UUID): ByteString {
        val out = ByteArray(16)
        for (i in 0 until 8) out[i] = (u.mostSignificantBits ushr (56 - 8 * i)).toByte()
        for (i in 0 until 8) out[8 + i] = (u.leastSignificantBits ushr (56 - 8 * i)).toByte()
        return ByteString.copyFrom(out)
    }

    /** The UUID in [b], or null unless it is exactly 16 bytes. */
    fun fromBytes(b: ByteString): UUID? {
        if (b.size() != 16) return null
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (b.byteAt(i).toLong() and 0xFF)
        for (i in 0 until 8) lsb = (lsb shl 8) or (b.byteAt(8 + i).toLong() and 0xFF)
        return UUID(msb, lsb)
    }
}

/**
 * Remembers the current connection for features that also offer calls outside [app.selfwatch.model.watch.WatchCommand]
 * (refresh the app list, read app settings).
 */
internal class SessionHolder {
    @Volatile
    private var session: GarminSessionContext? = null

    fun set(s: GarminSessionContext) {
        session = s
    }

    /** The live session, or a failure when there is none (or it has ended). */
    fun live(): Outcome<GarminSessionContext> {
        val s = session ?: return Outcome.Fail(SwError.Io("no Garmin session"))
        return if (s.scope.isActive) Outcome.Ok(s) else Outcome.Fail(SwError.Io("Garmin session ended"))
    }
}
