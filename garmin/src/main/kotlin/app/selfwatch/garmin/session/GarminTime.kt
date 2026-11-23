/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.session

import app.selfwatch.garmin.gfdi.GfdiReplies
import java.time.Instant
import java.time.ZoneId

/** Garmin (FIT) time: seconds since 1989-12-31 00:00 UTC, as an unsigned 32-bit number. */
public object GarminTime {
    /** Unix seconds at the Garmin epoch. */
    public const val EPOCH_UNIX_SEC: Long = 631_065_600L

    /** Garmin seconds to Unix seconds. */
    public fun toUnix(garmin: Long): Long = (garmin and 0xFFFF_FFFFL) + EPOCH_UNIX_SEC

    /** Unix seconds to Garmin seconds (as the 32-bit wire value). */
    public fun fromUnix(unixSec: Long): Int = (unixSec - EPOCH_UNIX_SEC).toInt()

    /**
     * The answer to a CURRENT_TIME_REQUEST at [nowSec] in [zone]: Garmin time, whole UTC offset, and the
     * next two offset transitions (0 when the zone has none).
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/CurrentTimeRequestMessage.java (fields and order)
     */
    public fun reply(referenceId: Int, nowSec: Long, zone: ZoneId): GfdiReplies.TimeReply {
        val now = Instant.ofEpochSecond(nowSec)
        val rules = zone.rules
        val offset = rules.getOffset(now).totalSeconds
        val next = try {
            rules.nextTransition(now)
        } catch (e: RuntimeException) {
            null
        }
        val after = next?.let {
            try {
                rules.nextTransition(it.instant)
            } catch (e: RuntimeException) {
                null
            }
        }
        return GfdiReplies.TimeReply(
            referenceId = referenceId,
            garminTime = fromUnix(nowSec),
            tzOffsetSec = offset,
            transitionAfterNext = after?.let { fromUnix(it.toEpochSecond()) } ?: 0,
            nextTransition = next?.let { fromUnix(it.toEpochSecond()) } ?: 0,
        )
    }
}
