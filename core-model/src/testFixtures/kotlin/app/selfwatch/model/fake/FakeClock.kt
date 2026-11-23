/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.fake

import app.selfwatch.model.time.Clock
import java.time.ZoneId

/**
 * A [Clock] that only moves when told to. Thread-safe.
 *
 * @param startSec initial time, UTC epoch seconds (default 2026-11-23 12:00 UTC).
 * @param zone initial time zone (default America/Detroit, to exercise DST).
 */
public class FakeClock(
    startSec: Long = 1_795_435_200L,
    zone: ZoneId = ZoneId.of("America/Detroit"),
) : Clock {
    @Volatile
    private var now: Long = startSec

    @Volatile
    private var currentZone: ZoneId = zone

    override fun nowSec(): Long = now

    override fun zone(): ZoneId = currentZone

    /** Sets the current time. */
    public fun set(tSec: Long) {
        now = tSec
    }

    /** Moves time forward by [seconds] (backward if negative). */
    @Synchronized
    public fun advance(seconds: Long) {
        now += seconds
    }

    /** Changes the time zone, as when the user travels. */
    public fun setZone(zone: ZoneId) {
        currentZone = zone
    }
}
