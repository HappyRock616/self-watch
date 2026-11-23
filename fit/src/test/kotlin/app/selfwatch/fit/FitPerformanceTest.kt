/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit

import app.selfwatch.fit.decode.WorkoutDecoder
import app.selfwatch.model.result.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Streams a generated ~10 MB activity file. The WP-04 target is under 1 s on a laptop; that strict limit
 * applies with `-Dfit.perf=true`. By default (CI, shared machines) the limit is a generous 6 s so the
 * test does not flake, while still catching an accidental quadratic slowdown.
 */
class FitPerformanceTest {
    @Test
    fun tenMegabyteActivityStreamsQuickly() {
        val bytes = Synth.activity(310_000)
        assertTrue("file is ${bytes.size} bytes", bytes.size in 9_500_000..12_000_000)
        val strict = System.getProperty("fit.perf") == "true"
        val limitMs = if (strict) 1000L else 6000L

        // One warm-up pass so the JIT is not measured.
        WorkoutDecoder.track(ByteArrayInputStream(bytes)) { }

        var best = Long.MAX_VALUE
        repeat(3) {
            val t0 = System.nanoTime()
            val n = WorkoutDecoder.track(ByteArrayInputStream(bytes)) { }.valueOrNull()
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(310_000, n)
            best = minOf(best, ms)
        }
        println("FitPerformanceTest: ${bytes.size} bytes streamed in $best ms (limit $limitMs ms)")
        assertTrue("took $best ms, limit $limitMs ms", best < limitMs)
    }
}
