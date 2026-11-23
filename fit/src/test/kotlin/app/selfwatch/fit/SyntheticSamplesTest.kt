/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit

import app.selfwatch.fit.decode.HrvDecoder
import app.selfwatch.fit.decode.MonitoringDecoder
import app.selfwatch.fit.decode.SleepDecoder
import app.selfwatch.fit.decode.WeightDecoder
import app.selfwatch.fit.decode.WorkoutDecoder
import app.selfwatch.model.result.Outcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The synthetic sample files in `testdata/fit/synthetic/` (for other modules' tests) must match what the
 * generators make now and decode cleanly. Regenerate them with `-Dfit.writeSamples=true`.
 */
class SyntheticSamplesTest {
    private val samples: Map<String, () -> ByteArray> = linkedMapOf(
        "monitor_day.fit" to { Synth.monitoringDay().first },
        "sleep_night.fit" to { Synth.sleepNight() },
        "hrv_status.fit" to { Synth.hrvNight() },
        "activity_run_gps.fit" to { Synth.activity(1800) },
        "activity_compressed_bigendian.fit" to { Synth.activity(600, compressed = true, bigEndian = true) },
        "oddities_devfields.fit" to { Synth.oddities() },
        "weight.fit" to { Synth.weight() },
    )

    @Test
    fun samplesAreCurrentAndDecode() {
        val dir = File(testdataFit(), "synthetic")
        val write = System.getProperty("fit.writeSamples") == "true"
        if (write) dir.mkdirs()
        for ((name, make) in samples) {
            val f = File(dir, name)
            val fresh = make()
            if (write) f.writeBytes(fresh)
            assertTrue("missing $f; run with -Dfit.writeSamples=true", f.exists())
            assertArrayEquals("stale $name; run with -Dfit.writeSamples=true", fresh, f.readBytes())
            val ok = when {
                name.startsWith("monitor") -> MonitoringDecoder.decode(f.inputStream(), 1)
                name.startsWith("sleep") -> SleepDecoder.decode(f.inputStream(), 1)
                name.startsWith("hrv") -> HrvDecoder.decode(f.inputStream(), 1)
                name.startsWith("weight") -> WeightDecoder.decode(f.inputStream())
                else -> WorkoutDecoder.summary(f.inputStream(), 1)
            }
            assertTrue("$name: $ok", ok is Outcome.Ok)
        }
    }
}
