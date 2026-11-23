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
import app.selfwatch.fit.io.FitReader
import app.selfwatch.model.log.LogSink
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

/**
 * 10,000 random mutations of generated files. No exception may escape the reader or any decoder, and a
 * small maxBytes keeps memory bounded whatever sizes a mutated header claims.
 */
class FitFuzzTest {
    private val seeds: List<ByteArray> = listOf(
        Synth.monitoringDay().first,
        Synth.sleepNight(),
        Synth.hrvNight(),
        Synth.activity(400),
        Synth.activity(200, compressed = true, bigEndian = true),
        Synth.oddities(),
        Synth.weight(),
    )

    private fun mutate(src: ByteArray, rnd: Random): ByteArray {
        var b = src.copyOf()
        repeat(1 + rnd.nextInt(3)) {
            when (rnd.nextInt(7)) {
                0 -> repeat(1 + rnd.nextInt(8)) { if (b.isNotEmpty()) b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
                1 -> if (b.isNotEmpty()) { val i = rnd.nextInt(b.size); b[i] = (b[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
                2 -> b = b.copyOf(rnd.nextInt(b.size + 1))
                3 -> { // delete a chunk
                    if (b.size > 2) {
                        val i = rnd.nextInt(b.size - 1)
                        val n = 1 + rnd.nextInt(minOf(64, b.size - i))
                        b = b.copyOfRange(0, i) + b.copyOfRange(i + n, b.size)
                    }
                }
                4 -> { // insert random bytes
                    val i = rnd.nextInt(b.size + 1)
                    b = b.copyOfRange(0, i) + Random(rnd.nextInt()).nextBytes(1 + rnd.nextInt(32)) + b.copyOfRange(i, b.size)
                }
                5 -> { // duplicate a chunk (repeats definitions and data)
                    if (b.size > 16) {
                        val i = rnd.nextInt(b.size - 8)
                        val n = 1 + rnd.nextInt(minOf(200, b.size - i))
                        b = b.copyOfRange(0, i + n) + b.copyOfRange(i, b.size)
                    }
                }
                else -> { // header damage: sizes and signature
                    if (b.size > 14) b[rnd.nextInt(14)] = rnd.nextInt(256).toByte()
                }
            }
        }
        // Half the time make the CRC right again so the damage reaches the decoders as "valid" data.
        if (b.size >= 16 && rnd.nextBoolean()) b = fixFileCrc(b)
        return b
    }

    @Test
    fun tenThousandMutationsNeverThrow() {
        // The reader turns an internal RuntimeException into Fail and logs it at ERROR; count those as bugs.
        val parserBugs = AtomicInteger()
        val old = SwLog.sink
        SwLog.sink = LogSink { level, _, _, _ -> if (level == LogSink.ERROR) parserBugs.incrementAndGet() }
        try {
            fuzz()
        } finally {
            SwLog.sink = old
        }
        assertEquals("internal parser errors", 0, parserBugs.get())
    }

    private fun fuzz() {
        val rnd = Random(20261123)
        var ok = 0
        var fail = 0
        repeat(10_000) { i ->
            val b = mutate(seeds[i % seeds.size], rnd)
            val r = FitReader(ByteArrayInputStream(b), maxBytes = 256L * 1024).read { }
            if (r is Outcome.Ok) ok++ else fail++
            val s = { ByteArrayInputStream(b) }
            when (i % 6) {
                0 -> MonitoringDecoder.decode(s(), 1)
                1 -> SleepDecoder.decode(s(), 1)
                2 -> HrvDecoder.decode(s(), 1)
                3 -> { WorkoutDecoder.summary(s(), 1); WorkoutDecoder.track(s()) { }; WorkoutDecoder.routePreview(s()) }
                4 -> WeightDecoder.decode(s())
                else -> HrvDecoder.nightly(s())
            }
        }
        assertEquals(10_000, ok + fail)
        assertTrue("some mutations should still read: ok=$ok", ok > 100)
        assertTrue("some mutations should fail: fail=$fail", fail > 100)
    }
}
