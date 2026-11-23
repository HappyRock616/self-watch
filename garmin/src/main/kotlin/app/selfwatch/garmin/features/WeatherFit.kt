/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.session.GarminTime
import app.selfwatch.model.watch.WeatherCondition
import app.selfwatch.model.watch.WeatherDay
import app.selfwatch.model.watch.WeatherNow
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * FIT records for one weather push: [definitions] (all definition records, sent in one FIT_DEFINITION)
 * and [data] (one entry per data record, packed into FIT_DATA messages).
 */
public class WeatherRecords(public val definitions: ByteArray, public val data: List<ByteArray>)

/**
 * Encodes weather as FIT `weather_conditions` messages (global message 128): one "current" record and
 * one "daily forecast" record per day (at most [MAX_DAYS]). Definitions are big-endian, as the
 * reference's builder writes them; missing values are FIT invalid values.
 *
 * Fields (Ref: Gadgetbridge 160beab41 FitCodeGenerator/src/main/resources/fit_profile.json WEATHER_CONDITIONS):
 * weather_report 0 (enum: current 0, hourly 1, daily 2), temperature 1 (sint8 °C), condition 2 (enum
 * WeatherStatus), wind_direction 3 (uint16 °), wind_speed 4 (uint16 m/s ×1000), precipitation_probability 5
 * (uint8 %), temperature_feels_like 6 (sint8), relative_humidity 7 (uint8 %), location 8 (string),
 * observed_at_time 9 (uint32 FIT time), day_of_week 12 (enum, Sunday 0), high_temperature 13 (sint8),
 * low_temperature 14 (sint8), timestamp 253 (uint32 FIT time).
 *
 * Which fields each record carries follows Ref: Gadgetbridge 160beab41 service/devices/garmin/GarminSupport.java
 * (encodeWeather), limited to what [WeatherNow] and [WeatherDay] hold.
 */
public object WeatherFit {
    /** Most forecast days sent (the reference sends today plus four). */
    public const val MAX_DAYS: Int = 5

    /** FIT global message number of weather_conditions. */
    public const val GLOBAL_WEATHER_CONDITIONS: Int = 128

    private const val LOCAL_CURRENT = 0
    private const val LOCAL_DAILY = 1

    private const val REPORT_CURRENT = 0
    private const val REPORT_DAILY = 2

    // FIT base types and their invalid values.
    private const val ENUM = 0x00
    private const val SINT8 = 0x01
    private const val UINT8 = 0x02
    private const val UINT16 = 0x84
    private const val UINT32 = 0x86
    private const val STRING = 0x07

    private class Field(val num: Int, val size: Int, val type: Int, val value: Long?, val text: ByteArray? = null)

    /** Encodes [now] and [days] (days before [now]'s day are skipped). */
    public fun encode(now: WeatherNow, days: List<WeatherDay>, zone: java.time.ZoneId): WeatherRecords {
        val today = app.selfwatch.model.time.Day.of(now.tSec, zone)
        val upcoming = days.filter { it.day >= today }.sortedBy { it.day }.take(MAX_DAYS)
        val todayForecast = upcoming.firstOrNull { it.day == today }
        val ts = garmin(now.tSec)
        val current = mutableListOf(
            Field(0, 1, ENUM, REPORT_CURRENT.toLong()),
            Field(253, 4, UINT32, ts),
            Field(9, 4, UINT32, ts),
            Field(1, 1, SINT8, celsius(now.tempC10)),
            Field(14, 1, SINT8, todayForecast?.let { celsius(it.minC10) }),
            Field(13, 1, SINT8, todayForecast?.let { celsius(it.maxC10) }),
            Field(2, 1, ENUM, status(now.condition)),
            Field(3, 2, UINT16, now.windDeg?.takeIf { it >= 0 }?.let { (it % 360).toLong() }),
            Field(5, 1, UINT8, todayForecast?.precipPct?.let { percent(it) }),
            Field(4, 2, UINT16, now.windKmhX10?.takeIf { it >= 0 }?.let { windSpeed(it) }),
            Field(6, 1, SINT8, now.feelsLikeC10?.let { celsius(it) }),
            Field(7, 1, UINT8, now.humidityPct?.let { percent(it) }),
        )
        now.location?.takeIf { it.isNotEmpty() }?.let {
            val b = app.selfwatch.garmin.gfdi.utf8Prefix(it, 254) + byteArrayOf(0)
            current.add(Field(8, b.size, STRING, null, b))
        }
        val dailyFields = { d: WeatherDay ->
            listOf(
                Field(0, 1, ENUM, REPORT_DAILY.toLong()),
                Field(253, 4, UINT32, ts),
                Field(14, 1, SINT8, celsius(d.minC10)),
                Field(13, 1, SINT8, celsius(d.maxC10)),
                Field(2, 1, ENUM, status(d.condition)),
                Field(5, 1, UINT8, d.precipPct?.let { percent(it) }),
                Field(12, 1, ENUM, (d.day.toLocalDate().dayOfWeek.value % 7).toLong()),
            )
        }
        val defs = ByteArrayOutputStream()
        writeDefinition(defs, LOCAL_CURRENT, current)
        val data = mutableListOf(dataRecord(LOCAL_CURRENT, current))
        if (upcoming.isNotEmpty()) {
            writeDefinition(defs, LOCAL_DAILY, dailyFields(upcoming.first()))
            for (d in upcoming) data.add(dataRecord(LOCAL_DAILY, dailyFields(d)))
        }
        return WeatherRecords(defs.toByteArray(), data)
    }

    /** The FIT WeatherStatus for [c]; null (invalid) for UNKNOWN. Ref: fit_profile.json WeatherStatus. */
    public fun status(c: WeatherCondition): Long? = when (c) {
        WeatherCondition.CLEAR -> 0L
        WeatherCondition.PARTLY_CLOUDY -> 1L
        WeatherCondition.RAIN -> 3L
        WeatherCondition.SNOW -> 4L
        WeatherCondition.WINDY -> 5L
        WeatherCondition.THUNDER -> 6L
        WeatherCondition.SLEET -> 7L // wintry mix
        WeatherCondition.FOG -> 8L
        WeatherCondition.HAZE -> 11L
        WeatherCondition.SHOWERS -> 13L // scattered showers
        WeatherCondition.CLOUDY -> 22L
        WeatherCondition.UNKNOWN -> null
    }

    // °C ×10 → whole °C (sint8). The reference converts Kelvin by subtracting 273 (its #4313); we round.
    private fun celsius(c10: Int): Long = (c10 / 10.0).roundToInt().coerceIn(-127, 126).toLong()

    private fun percent(p: Int): Long = p.coerceIn(0, 100).toLong()

    // km/h ×10 → m/s ×1000, capped at 65.53 m/s as in the reference.
    private fun windSpeed(kmhX10: Int): Long = (kmhX10 / 10.0 / 3.6 * 1000).roundToInt().coerceAtMost(65_530).toLong()

    private fun garmin(unixSec: Long): Long = GarminTime.fromUnix(unixSec).toLong() and 0xFFFF_FFFFL

    private fun writeDefinition(out: ByteArrayOutputStream, local: Int, fields: List<Field>) {
        out.write(0x40 or local) // definition record header
        out.write(0) // reserved
        out.write(1) // architecture: big-endian
        out.write(GLOBAL_WEATHER_CONDITIONS shr 8)
        out.write(GLOBAL_WEATHER_CONDITIONS and 0xFF)
        out.write(fields.size)
        for (f in fields) {
            out.write(f.num)
            out.write(f.size)
            out.write(f.type)
        }
    }

    private fun dataRecord(local: Int, fields: List<Field>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(local)
        for (f in fields) {
            if (f.type == STRING) {
                out.write(f.text ?: ByteArray(f.size))
                continue
            }
            val v = f.value ?: invalid(f.type)
            for (i in f.size - 1 downTo 0) out.write(((v shr (8 * i)) and 0xFF).toInt())
        }
        return out.toByteArray()
    }

    private fun invalid(type: Int): Long = when (type) {
        SINT8 -> 0x7FL
        UINT16 -> 0xFFFFL
        UINT32 -> 0xFFFF_FFFFL
        else -> 0xFFL
    }
}
