/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ContestDetectorTest {
    private fun t(hms: String): Long = Instant.parse("2026-11-23T${hms}Z").epochSecond

    /** READY and link-down times from the Pixel 5 log of 2026-11-23 (the two-phone evening). */
    @Test
    fun theRealEveningIsSeenAsContested() {
        val d = ContestDetector()
        assertFalse(d.onLinkEnded(t("22:50:07"), t("22:50:37"))) // 30 s
        assertFalse(d.onLinkEnded(null, t("22:51:01"))) // never ready: does not count
        assertFalse(d.onLinkEnded(t("23:18:04"), t("23:20:55"))) // 171 s: not short
        assertFalse(d.onLinkEnded(t("23:27:48"), t("23:28:06"))) // 18 s
        assertFalse(d.onLinkEnded(t("23:28:50"), t("23:28:57"))) // 7 s, two short in the window
        assertTrue(d.onLinkEnded(t("23:34:57"), t("23:35:09"))) // 12 s: third short link within 15 min
    }

    @Test
    fun aWatchOutOfRangeIsNotContested() {
        val d = ContestDetector()
        var now = t("12:00:00")
        repeat(20) {
            now += 60
            assertFalse(d.onLinkEnded(null, now)) // connect attempts that never reach READY
        }
    }

    @Test
    fun shortLinksSpreadOverHoursAreNotContested() {
        val d = ContestDetector()
        assertFalse(d.onLinkEnded(t("08:00:00"), t("08:00:20")))
        assertFalse(d.onLinkEnded(t("08:20:00"), t("08:20:20")))
        assertFalse(d.onLinkEnded(t("08:40:00"), t("08:40:20")))
    }

    @Test
    fun resetClearsTheContest() {
        val d = ContestDetector()
        repeat(3) { d.onLinkEnded(t("09:00:0$it"), t("09:00:3$it")) }
        assertTrue(d.contested)
        d.reset()
        assertFalse(d.contested)
        assertFalse(d.onLinkEnded(t("09:05:00"), t("09:05:30")))
    }
}
