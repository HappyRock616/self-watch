/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.io

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FitTimeTest {
    @Test
    fun epochConversion() {
        assertEquals(Instant.parse("1989-12-31T00:00:00Z").epochSecond, FitTime.toEpoch(0))
        assertEquals(1_000_000_000L, FitTime.fromEpoch(FitTime.toEpoch(1_000_000_000L)))
    }

    @Test
    fun timestamp16Forward() {
        val ref = 0x4321_1000L
        assertEquals(ref + 0x20, FitTime.fromTimestamp16(ref, 0x1020))
        assertEquals(ref, FitTime.fromTimestamp16(ref, 0x1000))
    }

    @Test
    fun timestamp16Rollover() {
        val ref = 0x4321_FFF0L
        assertEquals(ref + 0x20, FitTime.fromTimestamp16(ref, 0x0010))
    }

    @Test
    fun timestamp16SlightlyBackward() {
        val ref = 0x4322_0005L
        assertEquals(ref - 10, FitTime.fromTimestamp16(ref, 0xFFFB))
        assertEquals(ref - 300, FitTime.fromTimestamp16(ref, ((ref - 300) and 0xFFFF).toInt()))
    }

    @Test
    fun compressed() {
        val ref = 0x1000L + 30
        assertEquals(ref + 1, FitTime.fromCompressed(ref, 31))
        assertEquals(ref + 4, FitTime.fromCompressed(ref, 2))
        assertEquals(ref, FitTime.fromCompressed(ref, 30))
    }
}
