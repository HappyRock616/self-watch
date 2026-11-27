/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.decode

import app.selfwatch.fit.io.FitReader
import app.selfwatch.fit.io.FitTime
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.IngestBatch
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/**
 * Cumulative counter state carried from one monitoring file to the next of the same watch.
 *
 * Garmin monitoring messages hold running totals since local midnight, per activity type. The first
 * message of a type in a file is turned into a delta against this state; with a fresh state it counts as
 * "everything since midnight". Process a watch's files in time order with one carry to avoid counting
 * twice when a file starts in the middle of a day. The decoder only updates the carry when it returns
 * [Outcome.Ok]. Not thread-safe.
 */
class MonitoringCarry {
    internal class Counter(var day: Long, var steps: Long, var distCm: Long, var kcal: Long) {
        fun copy(): Counter = Counter(day, steps, distCm, kcal)
    }

    internal val counters: HashMap<Int, Counter> = HashMap()

    internal fun snapshot(): HashMap<Int, Counter> = HashMap(counters.mapValues { it.value.copy() })
}

/**
 * Decodes monitoring files (`GARMIN/Monitor`, file types 15/28/32) and metrics files into activity
 * minutes, heart rate, stress, respiration, SpO2, resting heart rate, skin temperature and HRV samples.
 *
 * **Rules**
 * - Each monitoring message carries cumulative steps (`cycles`), distance and active kcal for its
 *   activity type. The decoder keeps one counter per type, converts to deltas and sums the deltas of all
 *   types. The counters restart at local midnight; a message whose period starts on a new local day
 *   counts its full value. A counter that goes down within a day adds nothing and becomes the new
 *   baseline.
 * - A message at time t covers the time up to t, so its delta goes to the minute containing t − 1 s
 *   (the message stamped 00:00:00 still belongs to the day before).
 * - Minutes: steps, distance and kcal summed; activity type the last seen. Cycles of cycling (2) and
 *   swimming (5) are not counted as steps (they are pedal and stroke counts; the watch's daily steps
 *   leave them out).
 * - Intensity: a first mark only. The watch's credited minutes (`moderate_activity_minutes` 33,
 *   `vigorous_activity_minutes` 34) are marked as [IntensityLevel.MODERATE] / [IntensityLevel.VIGOROUS]
 *   on the minutes before the message; the raw movement level (field 24, bits 5–7) is kept only as 0
 *   (still) or 1 (moving). After ingest the service re-marks every minute from its heart-rate zone
 *   (`IntensityClassifier`: zone 2 and up), which is what the app counts.
 * - Heart rate: the last value in each minute, one [app.selfwatch.model.health.MetricKind.HEART_RATE]
 *   sample stamped at the minute start.
 * - Resting metabolic rate (`monitoring_info` field 5, kcal per day): one
 *   [app.selfwatch.model.health.MetricKind.RESTING_KCAL] sample per local day, stamped at the start of
 *   the day the `monitoring_info` message falls on.
 * - VO2 max (`max_met_data`, mostly in metrics files): running or cycling, see [Vo2Max].
 * - Sleep stages (`sleep_level`): the watch puts the part of a night before a midnight sync in the
 *   monitoring file, not the sleep file; they become stage samples and one session for that piece
 *   (joined with the rest of the night when read, see `SleepDao.night`).
 * - Local midnight comes from [zone] if given, otherwise from the file's own local time offset
 *   (`monitoring_info.local_timestamp` − `timestamp`), otherwise UTC.
 */
object MonitoringDecoder {
    private const val TAG = "MonitoringDecoder"
    private const val F_DISTANCE = 2
    private const val F_CYCLES = 3
    private const val F_ACTIVITY_TYPE = 5
    private const val F_LOCAL_TIMESTAMP = 11
    private const val F_CYCLES_16 = 9
    private const val F_ACTIVE_CALORIES = 19
    private const val F_TYPE_INTENSITY = 24
    private const val F_TIMESTAMP_16 = 26
    private const val F_HEART_RATE = 27
    /** FIT `activity_type` values whose cycles are not steps: 2 cycling, 5 swimming. */
    private val NOT_STEPS = setOf(2, 5)
    private const val F_MODERATE_MIN = 33
    private const val F_VIGOROUS_MIN = 34

    /** Most credited minutes accepted from one message (guards against a corrupt value). */
    private const val MAX_CREDIT_MIN = 60
    private const val MAX_TZ_SEC = 14 * 3600

    /** `monitoring_info.resting_metabolic_rate`, kcal per day. */
    private const val F_RESTING_METABOLIC_RATE = 5
    private const val MAX_RMR = 20_000

    private class Bucket {
        var steps = 0L
        var distCm = 0L
        var kcal = 0L
        var intensity = 0
        var type: Int? = null
    }

    /**
     * Decodes [file]. Same as the four-argument form with no zone and a fresh [MonitoringCarry].
     */
    fun decode(file: InputStream, deviceId: Long): Outcome<IngestBatch> = decode(file, deviceId, null, null)

    /**
     * Decodes [file] for the watch [deviceId]. [zone] overrides the file's own time offset for finding
     * local midnight; [carry] holds counters from the watch's previous file (see [MonitoringCarry]).
     */
    fun decode(file: InputStream, deviceId: Long, zone: ZoneId?, carry: MonitoringCarry?): Outcome<IngestBatch> {
        val counters = carry?.snapshot() ?: HashMap()
        val metrics = MetricExtractor(deviceId)
        val minutes = TreeMap<Long, Bucket>()
        val credit = HashMap<Long, Int>()
        val hr = TreeMap<Long, Int>()
        var refFit: Long? = null
        var offsetSec: Int? = null
        val restingKcal = ArrayList<Pair<Long, Int>>()
        val sleep = TreeMap<Long, app.selfwatch.model.health.SleepStageSample>()

        fun localDay(t: Long): Long {
            val off = if (zone != null) zone.rules.getOffset(Instant.ofEpochSecond(t)).totalSeconds else (offsetSec ?: 0)
            return Math.floorDiv(t + off, 86_400L)
        }

        fun noteOffset(localFit: Long?, utcFit: Long?) {
            if (localFit == null || utcFit == null || offsetSec != null) return
            val d = localFit - utcFit
            if (d in -MAX_TZ_SEC..MAX_TZ_SEC) offsetSec = d.toInt()
        }

        val result = FitReader(file).read { rec ->
            if (rec.ownTime) rec.tSec?.let { refFit = FitTime.fromEpoch(it) }
            if (metrics.accept(rec)) return@read
            when (rec.global) {
                FitMsg.SLEEP_LEVEL -> SleepDecoder.stageOf(rec, deviceId)?.let { sleep[it.tSec] = it }
                FitMsg.MONITORING_INFO -> {
                    noteOffset(rec.long(0), rec.long(F_TIMESTAMP))
                    val rmr = rec.int(F_RESTING_METABOLIC_RATE)
                    val t = rec.tSec ?: rec.long(F_TIMESTAMP)?.let { FitTime.toEpoch(it) }
                    if (rmr != null && rmr in 1..MAX_RMR && t != null) restingKcal += t to rmr
                }
                FitMsg.MONITORING -> {
                    // Ref: Gadgetbridge 160beab41 service/devices/garmin/fit/messages/FitMonitoring.java (timestamp_16 against the last timestamp; activity type from field 5, else low 5 bits of field 24; intensity bits 5-7)
                    val own = rec.long(F_TIMESTAMP)
                    noteOffset(rec.long(F_LOCAL_TIMESTAMP), own)
                    val t16 = rec.int(F_TIMESTAMP_16)
                    val ref = refFit
                    val fit: Long = when {
                        own != null -> own
                        t16 != null && ref != null -> FitTime.fromTimestamp16(ref, t16).also { refFit = it }
                        else -> ref ?: return@read
                    }
                    val ts = FitTime.toEpoch(fit)

                    val bpm = rec.int(F_HEART_RATE)
                    if (bpm != null && bpm in 1..254) hr[Math.floorDiv(ts, 60L) * 60] = bpm

                    val packed = rec.int(F_TYPE_INTENSITY)
                    val type = rec.int(F_ACTIVITY_TYPE) ?: packed?.and(0x1F)
                    val intensity = packed?.let { if ((it shr 5) and 0x07 > 0) IntensityLevel.MOVING else IntensityLevel.STILL }
                    // Ref: Gadgetbridge 160beab41 service/devices/garmin/fit/FitImporter.java (moderate/vigorous_activity_minutes summed per message; GarminPaiSampleProvider totals moderate + 2 × vigorous)
                    val modMin = rec.int(F_MODERATE_MIN)?.takeIf { it in 1..MAX_CREDIT_MIN } ?: 0
                    val vigMin = rec.int(F_VIGOROUS_MIN)?.takeIf { it in 1..MAX_CREDIT_MIN } ?: 0
                    val steps = rec.long(F_CYCLES)
                    val steps16 = rec.long(F_CYCLES_16)
                    val dist = rec.long(F_DISTANCE)
                    val kcal = rec.long(F_ACTIVE_CALORIES)
                    if (steps == null && steps16 == null && dist == null && kcal == null && intensity == null && modMin == 0 && vigMin == 0) return@read

                    val a = ts - 1
                    val day = localDay(a)
                    val c = counters.getOrPut(type ?: -1) { MonitoringCarry.Counter(day, 0, 0, 0) }
                    if (c.day != day) {
                        // Counters restart at local midnight.
                        c.day = day; c.steps = 0; c.distCm = 0; c.kcal = 0
                    }
                    val minuteStart = Math.floorDiv(a, 60L) * 60
                    val b = minutes.getOrPut(minuteStart) { Bucket() }
                    for (i in 0 until vigMin + modMin) {
                        val level = if (i < vigMin) IntensityLevel.VIGOROUS else IntensityLevel.MODERATE
                        val t = minuteStart - i * 60L
                        if ((credit[t] ?: 0) < level) credit[t] = level
                    }
                    // Ref: Gadgetbridge 160beab41 service/devices/garmin/fit/FitImporter.java (cycles are cumulative per activity type; steps = raw cycles value, i.e. scaled cycles × 2, summed over types)
                    val curSteps = steps ?: steps16?.let { s16 -> c.steps + ((s16 - (c.steps and 0xFFFF)) and 0xFFFF) }
                    // Cycling and swimming cycles are pedal or stroke counts, not steps: the watch leaves them out of its daily steps.
                    if (curSteps != null) { if (type !in NOT_STEPS) b.steps += delta(curSteps, c.steps); c.steps = curSteps }
                    if (dist != null) { b.distCm += delta(dist, c.distCm); c.distCm = dist }
                    if (kcal != null) { b.kcal += delta(kcal, c.kcal); c.kcal = kcal }
                    if (intensity != null && intensity > b.intensity) b.intensity = intensity
                    if (type != null) b.type = type
                }
            }
        }
        return when (result) {
            is Outcome.Fail -> result
            is Outcome.Ok -> {
                if (!result.value.crcOk) SwLog.w(TAG, "monitoring file CRC mismatch; using its data anyway")
                carry?.counters?.let { it.clear(); it.putAll(counters) }
                for ((t, v) in hr) metrics.addHeartRate(t, v)
                // One RESTING_KCAL sample per local day, stamped at the day's start (a later value wins).
                for ((t, kcal) in restingKcal) {
                    val day = localDay(t)
                    val start = if (zone != null) {
                        LocalDate.ofEpochDay(day).atStartOfDay(zone).toEpochSecond()
                    } else {
                        day * 86_400L - (offsetSec ?: 0)
                    }
                    metrics.addRestingKcal(start, kcal)
                }
                for (t in credit.keys) minutes.getOrPut(t) { Bucket() }
                val activity = minutes.map { (t, b) ->
                    ActivityMinute(
                        deviceId = deviceId,
                        tSec = t,
                        steps = clampInt(b.steps),
                        activeKcalX10 = clampInt(b.kcal * 10),
                        distanceCm = clampInt(b.distCm),
                        intensity = credit[t] ?: b.intensity,
                        activityType = b.type,
                    )
                }
                val stages = sleep.values.toList()
                Outcome.Ok(
                    IngestBatch(
                        key = null,
                        activity = activity,
                        metrics = metrics.result(),
                        sleepStages = stages,
                        sleepSessions = SleepDecoder.sessionFor(stages, deviceId, score = null),
                    ),
                )
            }
        }
    }

    private fun delta(cur: Long, base: Long): Long = if (cur >= base) cur - base else 0L

    private fun clampInt(v: Long): Int = v.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

/** Values of [ActivityMinute.intensity] written by the decoders. */
object IntensityLevel {
    /** No movement. */
    const val STILL: Int = 0

    /** Moving, but no intensity minute credited by the watch. */
    const val MOVING: Int = 1

    /** A moderate intensity minute credited by the watch (counts 1). */
    const val MODERATE: Int = 2

    /** A vigorous intensity minute credited by the watch (counts 2). */
    const val VIGOROUS: Int = 3
}
