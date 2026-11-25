/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit

import app.selfwatch.fit.decode.FitFileType
import app.selfwatch.fit.decode.FitMsg
import app.selfwatch.fit.encode.WeightFileWriter
import app.selfwatch.fit.io.DevFieldDef
import app.selfwatch.fit.io.FieldDef
import app.selfwatch.fit.io.FitBaseType
import app.selfwatch.fit.io.FitBaseType.BYTE
import app.selfwatch.fit.io.FitBaseType.ENUM
import app.selfwatch.fit.io.FitBaseType.FLOAT32
import app.selfwatch.fit.io.FitBaseType.SINT16
import app.selfwatch.fit.io.FitBaseType.SINT32
import app.selfwatch.fit.io.FitBaseType.SINT8
import app.selfwatch.fit.io.FitBaseType.STRING
import app.selfwatch.fit.io.FitBaseType.UINT16
import app.selfwatch.fit.io.FitBaseType.UINT32
import app.selfwatch.fit.io.FitBaseType.UINT32Z
import app.selfwatch.fit.io.FitBaseType.UINT8
import app.selfwatch.fit.io.FitReader
import app.selfwatch.fit.io.FitRecord
import app.selfwatch.fit.io.FitSummary
import app.selfwatch.fit.io.FitTime
import app.selfwatch.fit.io.FitWriter
import app.selfwatch.model.crc.Crc16
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.result.Outcome
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** One field with its value, for [msg]. */
data class F(val num: Int, val type: Int, val value: Any?, val size: Int = FitBaseType.size(type))

/** Defines local type [local] as [global] with the fields of [fs] and writes one data message. */
fun FitWriter.msg(local: Int, global: Int, vararg fs: F, bigEndian: Boolean = false) {
    define(local, global, fs.map { FieldDef(it.num, it.size, it.type) }, bigEndian = bigEndian)
    data(local, fs.map { it.value })
}

/** A complete FIT file built by [block]. */
fun fit(block: FitWriter.() -> Unit): ByteArray {
    val out = ByteArrayOutputStream()
    val w = FitWriter(out)
    w.header()
    w.block()
    w.finish()
    return out.toByteArray()
}

/** FIT time of epoch seconds [t]. */
fun ft(t: Long): Long = FitTime.fromEpoch(t)

/** Reads [bytes], collecting every record. */
fun readAll(bytes: ByteArray, maxBytes: Long = 64L shl 20): Pair<Outcome<FitSummary>, List<FitRecord>> {
    val list = ArrayList<FitRecord>()
    val r = FitReader(ByteArrayInputStream(bytes), maxBytes).read { list.add(it) }
    return r to list
}

/** Rewrites the trailing file CRC of a single FIT file after a deliberate change. */
fun fixFileCrc(bytes: ByteArray): ByteArray {
    val c = Crc16.compute(bytes, 0, bytes.size - 2)
    val out = bytes.copyOf()
    out[out.size - 2] = (c and 0xFF).toByte()
    out[out.size - 1] = ((c shr 8) and 0xFF).toByte()
    return out
}

/** The repository's testdata/fit directory (tests run with the module directory as working directory). */
fun testdataFit(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        val t = File(dir, "testdata")
        if (File(dir, "settings.gradle.kts").exists()) return File(t, "fit")
        dir = dir.parentFile
    }
    return File("../testdata/fit")
}

/** Generators of realistic synthetic watch files. */
object Synth {
    /** 2026-11-20 00:00:00 UTC. */
    val T0: Long = Instant.parse("2026-11-20T00:00:00Z").epochSecond

    /** UTC offset used by the synthetic monitoring day (UTC−6, like Chicago in winter). */
    const val TZ_SEC: Int = -6 * 3600

    fun fileId(w: FitWriter, type: Int, created: Long) {
        w.msg(
            0, FitMsg.FILE_ID,
            F(0, ENUM, type), F(1, UINT16, 1), F(2, UINT16, 4426), F(3, UINT32Z, 3_400_000_001L), F(4, UINT32, ft(created)),
        )
    }

    /**
     * One local day (UTC−6) of monitoring from local 00:00 to the next local 01:00: cumulative counters
     * every 15 minutes (walking, plus a running block), heart rate every 2 minutes with timestamp_16,
     * stress every 3 minutes, respiration, SpO2 at night, resting heart rate. Returns the file and the
     * expected step total of the first local day.
     */
    fun monitoringDay(): Pair<ByteArray, Int> {
        val dayStart = T0 - TZ_SEC // local midnight in UTC
        var stepsDay = 0
        val bytes = fit {
            fileId(this, FitFileType.MONITORING_B, dayStart)
            msg(1, FitMsg.MONITORING_INFO, F(253, UINT32, ft(dayStart)), F(0, UINT32, ft(dayStart) + TZ_SEC), F(5, UINT16, 1650))
            define(2, FitMsg.MONITORING, listOf(FieldDef(253, 4, UINT32), FieldDef(3, 4, UINT32), FieldDef(5, 1, ENUM),
                FieldDef(19, 2, UINT16), FieldDef(2, 4, UINT32), FieldDef(24, 1, BYTE)))
            define(3, FitMsg.MONITORING, listOf(FieldDef(26, 2, UINT16), FieldDef(27, 1, UINT8)))
            define(4, FitMsg.STRESS_LEVEL, listOf(FieldDef(0, 2, SINT16), FieldDef(1, 4, UINT32)))
            define(5, FitMsg.RESPIRATION_RATE, listOf(FieldDef(253, 4, UINT32), FieldDef(0, 2, SINT16)))
            define(6, FitMsg.SPO2_DATA, listOf(FieldDef(253, 4, UINT32), FieldDef(0, 1, UINT8), FieldDef(1, 1, UINT8)))
            var walk = 0L
            var run = 0L
            var day2Walk = 0L
            val end = dayStart + 25 * 3600
            var t = dayStart + 60
            while (t <= end) {
                val local = t - dayStart
                if (local % 900 == 0L) {
                    val inRun = local in 7 * 3600..8 * 3600
                    val newDay = t > dayStart + 86_400
                    val add = if (local in 6 * 3600..22 * 3600) 180 + (local / 900 % 7) * 20 else 0
                    if (inRun) {
                        run += 1500
                        data(2, listOf(ft(t), run, 1, run / 20, run * 100, 1 or (3 shl 5)))
                        if (!newDay) stepsDay += 1500
                    } else if (newDay) {
                        day2Walk += 40
                        data(2, listOf(ft(t), day2Walk, 6, day2Walk / 25, day2Walk * 70, 6 or (1 shl 5)))
                    } else {
                        walk += add
                        data(2, listOf(ft(t), walk, 6, walk / 25, walk * 70, 6 or ((if (add > 0) 1 else 0) shl 5)))
                        stepsDay += add.toInt()
                    }
                }
                if (local % 120 == 0L) data(3, listOf((ft(t) and 0xFFFF), 55 + (local / 120 % 40).toInt()))
                if (local % 180 == 0L) data(4, listOf((local / 180 % 90).toInt(), ft(t)))
                if (local % 600 == 0L) data(5, listOf(ft(t), 1400 + (local / 600 % 5).toInt() * 50))
                if (local < 6 * 3600 && local % 1800 == 0L) data(6, listOf(ft(t), 94 + (local / 1800 % 4).toInt(), 3))
                t += 60
            }
            msg(7, FitMsg.MONITORING_HR_DATA, F(253, UINT32, ft(end)), F(0, UINT8, 52), F(1, UINT8, 50))
        }
        return bytes to stepsDay
    }

    /** One night: stage samples every 60 s from 23:00 to 06:30 local, an assessment, SpO2 and HRV. */
    fun sleepNight(): ByteArray {
        val start = T0 - TZ_SEC - 3600 // 23:00 local
        val levels = intArrayOf(1, 2, 2, 3, 3, 3, 2, 4, 4, 2, 1)
        return fit {
            fileId(this, FitFileType.SLEEP, start)
            define(1, FitMsg.SLEEP_LEVEL, listOf(FieldDef(253, 4, UINT32), FieldDef(0, 1, ENUM)))
            for (i in 0 until 450) data(1, listOf(ft(start + i * 60L), levels[(i / 41) % levels.size]))
            msg(2, FitMsg.SLEEP_ASSESSMENT, F(6, UINT8, 82), F(11, UINT8, 3), F(15, UINT16, 1850))
            msg(3, FitMsg.SPO2_DATA, F(253, UINT32, ft(start + 7200)), F(0, UINT8, 96), F(1, UINT8, 3))
            msg(4, FitMsg.HRV_VALUE, F(253, UINT32, ft(start + 3600)), F(0, UINT16, 48 * 128))
            msg(5, FitMsg.SKIN_TEMP_OVERNIGHT, F(253, UINT32, ft(start + 27_000)), F(1, FLOAT32, -0.4))
        }
    }

    /** An HRV status file: 5-minute values for one night and the nightly summary. */
    fun hrvNight(): ByteArray {
        val start = T0 - TZ_SEC - 3600
        return fit {
            fileId(this, FitFileType.HRV_STATUS, start)
            define(1, FitMsg.HRV_VALUE, listOf(FieldDef(253, 4, UINT32), FieldDef(0, 2, UINT16)))
            for (i in 0 until 90) data(1, listOf(ft(start + i * 300L), (40 + i % 15) * 128))
            msg(
                2, FitMsg.HRV_STATUS_SUMMARY,
                F(253, UINT32, ft(start + 27_000)), F(0, UINT16, 47 * 128), F(1, UINT16, 46 * 128), F(6, ENUM, 4),
            )
        }
    }

    /**
     * A GPS run of [points] one-second records around a 1 km loop, with session totals, written with
     * compressed timestamp headers when [compressed] and a big-endian record definition when [bigEndian].
     */
    fun activity(points: Int, compressed: Boolean = false, bigEndian: Boolean = false): ByteArray {
        val start = T0 + 15 * 3600
        val lat0 = 41.88
        val lon0 = -87.63
        val rM = 160.0
        return fit {
            fileId(this, FitFileType.ACTIVITY, start)
            msg(1, FitMsg.EVENT, F(253, UINT32, ft(start)), F(0, ENUM, 0), F(1, ENUM, 0))
            val fields = listOf(
                FieldDef(0, 4, SINT32), FieldDef(1, 4, SINT32), FieldDef(2, 2, UINT16), FieldDef(3, 1, UINT8),
                FieldDef(4, 1, UINT8), FieldDef(5, 4, UINT32), FieldDef(6, 2, UINT16), FieldDef(13, 1, SINT8),
                FieldDef(73, 4, UINT32), FieldDef(78, 4, UINT32),
            )
            define(2, FitMsg.RECORD, listOf(FieldDef(253, 4, UINT32)) + fields, bigEndian = bigEndian)
            if (compressed) define(0, FitMsg.RECORD, fields, bigEndian = bigEndian)
            var dist = 0.0
            for (i in 0 until points) {
                val t = start + i
                val a = i * 2 * Math.PI / 400.0
                val lat = lat0 + (rM * sin(a)) / 111_320.0
                val lon = lon0 + (rM * cos(a)) / (111_320.0 * cos(Math.toRadians(lat0)))
                val sLat = (lat / 180.0 * 2147483648.0).roundToLong()
                val sLon = (lon / 180.0 * 2147483648.0).roundToLong()
                dist += 2.5
                val altRaw = ((180.0 + 5 * sin(a)) + 500) * 5
                val vals = listOf(sLat, sLon, altRaw.toInt(), 140 + i % 20, 85, (dist * 100).toLong(), 2500, 18, 2500, altRaw.toLong())
                if (compressed && i % 10 != 0) compressed(0, (ft(t) and 0x1F).toInt(), vals) else data(2, listOf(ft(t)) + vals)
            }
            val total = points.toLong()
            msg(
                3, FitMsg.SESSION,
                F(253, UINT32, ft(start + total)), F(2, UINT32, ft(start)), F(5, ENUM, 1), F(6, ENUM, 0),
                F(7, UINT32, total * 1000 + 30_000), F(8, UINT32, total * 1000), F(9, UINT32, (dist * 100).toLong()),
                F(11, UINT16, total / 10), F(16, UINT8, 149), F(17, UINT8, 159), F(22, UINT16, 12), F(23, UINT16, 11),
            )
            msg(4, FitMsg.ACTIVITY, F(253, UINT32, ft(start + total)), F(0, UINT32, total * 1000), F(1, UINT16, 1), F(5, UINT32, ft(start + total) + TZ_SEC))
        }
    }

    /** `update_time` of the VO2 max estimates in [metricsVo2Max]: the evening run's save, 2026-11-20 16:05 UTC. */
    val VO2_UPDATE: Long = T0 + 16 * 3600 + 5 * 60

    /**
     * A metrics file (type 44) as a multisport watch writes it after a run: `max_met_data` for running
     * (48.3 ml/kg/min) and for cycling (51.0), both stamped with [VO2_UPDATE], the running one written
     * twice (the watch repeats it in every day's file), plus a record with a placeholder value.
     */
    fun metricsVo2Max(): ByteArray = fit {
        fileId(this, FitFileType.METRICS, VO2_UPDATE)
        val fields = listOf(
            FieldDef(253, 4, UINT32), FieldDef(0, 4, UINT32), FieldDef(1, 4, SINT32), FieldDef(2, 2, UINT16),
            FieldDef(3, 1, SINT8), FieldDef(5, 1, ENUM), FieldDef(6, 1, ENUM), FieldDef(8, 1, ENUM), FieldDef(9, 1, ENUM),
        )
        define(1, FitMsg.MAX_MET_DATA, fields)
        val now = ft(T0 + 23 * 3600)
        // met_max is METs ×65536: 48.3 / 3.5 = 13.8 METs.
        data(1, listOf(now, ft(VO2_UPDATE), (48.3 / 3.5 * 65536).toLong(), 483, 38, 1, 0, 0, 1))
        data(1, listOf(now, ft(VO2_UPDATE), (51.0 / 3.5 * 65536).toLong(), 510, 36, 2, 0, 1, 1))
        data(1, listOf(now + 60, ft(VO2_UPDATE), (48.3 / 3.5 * 65536).toLong(), 483, 38, 1, 0, 0, 1))
        // 0 is not a VO2 max (the watch writes it before its first estimate).
        data(1, listOf(now + 60, ft(VO2_UPDATE - 86_400), 0L, 0, null, 1, 0, 0, 0))
    }

    /** A file using everything awkward: big-endian definitions, developer fields, strings and arrays. */
    fun oddities(): ByteArray = fit {
        fileId(this, FitFileType.ACTIVITY, T0)
        msg(
            1, FitMsg.FIELD_DESCRIPTION,
            F(0, UINT8, 0), F(1, UINT8, 7), F(2, UINT8, UINT16), F(3, STRING, "power2", 16),
        )
        describeDevField(0, 7, UINT16)
        define(
            2, FitMsg.RECORD,
            listOf(FieldDef(253, 4, UINT32), FieldDef(3, 1, UINT8)),
            listOf(DevFieldDef(7, 2, 0), DevFieldDef(9, 3, 1)),
            bigEndian = true,
        )
        for (i in 0 until 20) data(2, listOf(ft(T0 + i), 120 + i), listOf(200 + i, byteArrayOf(1, 2, 3)))
        msg(3, 0xFF01, F(0, UINT16, listOf(1, null, 3), 6), F(1, STRING, "unknown msg", 12))
    }

    /** A weight file from [WeightFileWriter] with two weigh-ins. */
    fun weight(): ByteArray {
        val out = ByteArrayOutputStream()
        val e = listOf(
            BodyMeasure(0, T0 + 7 * 3600, BodyKind.WEIGHT, null, 8245, DataSource.MANUAL, null, null),
            BodyMeasure(0, T0 + 7 * 3600, BodyKind.BODY_FAT, null, 2210, DataSource.MANUAL, null, null),
            BodyMeasure(0, T0 + 31 * 3600, BodyKind.WEIGHT, null, 8190, DataSource.MANUAL, null, null),
        )
        WeightFileWriter.write(out, e, T0 + 32 * 3600)
        return out.toByteArray()
    }
}
