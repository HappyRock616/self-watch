/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.conn

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test
    fun followsTheStepsAndRepeatsTheLast() {
        val b = Backoff()
        val got = List(10) { b.nextDelayMs() / 1000 }
        assertEquals(listOf(1L, 2, 5, 10, 30, 60, 300, 300, 300, 300), got)
    }

    @Test
    fun resetStartsOver() {
        val b = Backoff()
        repeat(4) { b.nextDelayMs() }
        b.reset()
        assertEquals(1_000L, b.nextDelayMs())
        assertEquals(2_000L, b.nextDelayMs())
    }
}
