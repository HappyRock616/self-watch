/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.charts

import app.selfwatch.model.health.SleepStage
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Realistic generated chart data for the debug gallery, the benchmark and tests (debug builds only).
 * Everything is deterministic for a given seed.
 */
object ChartSamples {

    /** One generated night: stages, 1 Hz heart rate, HRV every 5 minutes and breathing every minute (×10). */
    class Night(
        val startSec: Long,
        val endSec: Long,
        val stages: List<StageSpan>,
        val hrT: LongArray,
        val hr: IntArray,
        val hrvT: LongArray,
        val hrv: IntArray,
        val breathT: LongArray,
        val breathX10: IntArray,
    )

    /**
     * An 8-hour night starting at [startSec]: ~90-minute cycles with more deep sleep early and more REM
     * late, short wake-ups, and a 4-minute heart-rate gap (watch moved) to show line breaks.
     */
    fun night(startSec: Long, hours: Int = 8, seed: Int = 42): Night {
        val r = Random(seed)
        val end = startSec + hours * 3600L
        val stages = ArrayList<StageSpan>()
        var t = startSec
        stages += StageSpan(t, t + 600, SleepStage.AWAKE).also { t = it.endSec }
        var cycle = 0
        while (t < end) {
            val early = 1.0 - cycle / 5.0
            val plan = listOf(
                SleepStage.LIGHT to (15 + r.nextInt(15)) * 60L,
                SleepStage.DEEP to ((20 + r.nextInt(20)) * early.coerceAtLeast(0.2)).toLong() * 60L,
                SleepStage.LIGHT to (10 + r.nextInt(15)) * 60L,
                SleepStage.REM to ((8 + r.nextInt(10)) * (1.0 + cycle * 0.4)).toLong() * 60L,
            )
            for ((stage, dur) in plan) {
                if (t >= end || dur <= 0) continue
                val e = minOf(end, t + dur)
                stages += StageSpan(t, e, stage)
                t = e
            }
            if (t < end && r.nextInt(3) == 0) {
                val e = minOf(end, t + (2 + r.nextInt(6)) * 60L)
                stages += StageSpan(t, e, SleepStage.AWAKE)
                t = e
            }
            cycle++
        }
        val starts = LongArray(stages.size) { stages[it].startSec }
        val ends = LongArray(stages.size) { stages[it].endSec }
        fun stageAt(time: Long): SleepStage {
            val i = ChartMath.spanIndexAt(starts, ends, time)
            return if (i < 0) SleepStage.LIGHT else stages[i].stage
        }

        val gapStart = startSec + 3 * 3600L + 1234
        val hrTimes = ArrayList<Long>(hours * 3600)
        val hrVals = ArrayList<Int>(hours * 3600)
        var smooth = 60.0
        for (s in startSec until end) {
            if (s in gapStart until gapStart + 240) continue
            val target = when (stageAt(s)) {
                SleepStage.AWAKE -> 66.0
                SleepStage.REM -> 60.0
                SleepStage.LIGHT -> 55.0
                SleepStage.DEEP -> 49.0
                SleepStage.UNMEASURABLE -> 56.0
            } - (s - startSec) / 3600.0 * 0.6
            smooth += (target - smooth) * 0.01
            val wobble = sin((s - startSec) * 2 * PI / 17.0) * 0.8
            hrTimes += s
            hrVals += (smooth + wobble + r.nextDouble(-0.6, 0.6)).roundToInt()
        }

        val hrvT = LongArray(hours * 12) { startSec + it * 300L + 150 }
        val hrv = IntArray(hrvT.size) {
            val base = when (stageAt(hrvT[it])) {
                SleepStage.DEEP -> 68
                SleepStage.LIGHT -> 56
                SleepStage.REM -> 44
                else -> 38
            }
            base + r.nextInt(-5, 6)
        }
        val breathT = LongArray(hours * 60) { startSec + it * 60L + 30 }
        val breath = IntArray(breathT.size) {
            val base = when (stageAt(breathT[it])) {
                SleepStage.DEEP -> 128
                SleepStage.LIGHT -> 140
                SleepStage.REM -> 152
                else -> 160
            }
            base + r.nextInt(-2, 3)
        }
        return Night(startSec, end, stages, hrTimes.toLongArray(), hrVals.toIntArray(), hrvT, hrv, breathT, breath)
    }

    /** [weeks] of weekly walking distance in tenths of a km, slowly rising, with one missing week (-1). */
    fun weeklyDistanceX10(weeks: Int = 12, seed: Int = 7): IntArray {
        val r = Random(seed)
        return IntArray(weeks) { if (it == 3) -1 else (180 + it * 9 + r.nextInt(-40, 41)) }
    }

    /** [days] of weigh-ins from [startSec]: most days, drifting from about 84.5 to 82 kg with daily noise. */
    fun weights(startSec: Long, days: Int = 90, seed: Int = 3): Pair<LongArray, IntArray> {
        val r = Random(seed)
        val t = ArrayList<Long>()
        val w = ArrayList<Int>()
        for (d in 0 until days) {
            if (r.nextInt(10) < 3) continue
            t += startSec + d * 86_400L + 7 * 3600L + r.nextInt(0, 3600)
            val base = 8450 - d * 2.8
            w += (base + r.nextDouble(-60.0, 60.0) + (if (d % 7 == 6) 40 else 0)).roundToInt()
        }
        return t.toLongArray() to w.toIntArray()
    }
}
