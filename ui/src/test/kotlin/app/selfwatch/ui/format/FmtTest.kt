/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.format


import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Robolectric because day and time labels use ICU (android.icu). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FmtTest {
    private val us = Locale.US
    private val gb = Locale.UK
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 23) // a Monday

    /** ICU puts narrow no-break spaces before AM/PM and Fmt joins units with a no-break space. */
    private fun norm(s: String): String = s.replace(Char(0x202F), ' ').replace(Char(0x00A0), ' ')

    private fun tSec(h: Int, m: Int): Long =
        LocalDateTime.of(2026, 11, 22, h, m).toEpochSecond(ZoneOffset.UTC)

    @Test
    fun minutesAndSeconds() {
        assertEquals("5:07", Fmt.minSec(307.0, us))
        assertEquals("8:03", Fmt.minSec(483.0, us))
        assertEquals("0:09", Fmt.minSec(9.0, us))
        assertEquals("12:00", Fmt.minSec(720.0, us))
        // Rounded before splitting, so the seconds never read 60.
        assertEquals("5:00", Fmt.minSec(299.6, us))
        assertEquals("0:00", Fmt.minSec(-3.0, us))
        assertEquals("0:00", Fmt.minSec(Double.NaN, us))
    }

    @Test
    fun numbers() {
        assertEquals("1,240", Fmt.number(1240L, us))
        assertEquals("1,240", Fmt.number(1240L, gb))
        assertEquals("1.240", Fmt.number(1240L, Locale.GERMANY))
        assertEquals("82.40", Fmt.number(82.4, 2, us))
    }

    @Test
    fun energy() {
        assertEquals("1,240 kcal", norm(Fmt.energy(1240, us)))
        assertEquals("1,240 kcal", norm(Fmt.energy(1240, gb)))
        assertEquals("+150 kcal", norm(Fmt.energy(150, us, signed = true)))
        assertEquals("-150 kcal", norm(Fmt.energy(-150, us, signed = true)))
    }

    @Test
    fun weight() {
        assertEquals("82.4 kg", norm(Fmt.weight(8240, Units.METRIC, gb)))
        assertEquals("82.0 kg", norm(Fmt.weight(8200, Units.METRIC, gb)))
        assertEquals("181.7 lb", norm(Fmt.weight(8240, Units.IMPERIAL, us)))
    }

    @Test
    fun length() {
        assertEquals("91 cm", norm(Fmt.length(91.0, Units.METRIC, gb)))
        assertEquals("91.5 cm", norm(Fmt.length(91.5, Units.METRIC, gb)))
        assertEquals("35.8 in", norm(Fmt.length(91.0, Units.IMPERIAL, us)))
    }

    @Test
    fun distance() {
        assertEquals("850 m", norm(Fmt.distance(850.0, Units.METRIC, gb)))
        assertEquals("3.2 km", norm(Fmt.distance(3200.0, Units.METRIC, gb)))
        assertEquals("123 km", norm(Fmt.distance(123_400.0, Units.METRIC, gb)))
        assertEquals("2.0 mi", norm(Fmt.distance(3218.7, Units.IMPERIAL, us)))
        assertEquals("328 ft", norm(Fmt.distance(100.0, Units.IMPERIAL, us)))
        // A short walk never rounds to "0 mi".
        assertEquals("0.3 mi", norm(Fmt.distance(483.0, Units.IMPERIAL, us)))
    }

    @Test
    fun volumeElevationAndTemperature() {
        assertEquals("350 ml", norm(Fmt.volume(350.0, Units.METRIC, gb)))
        assertEquals("2.8 L", norm(Fmt.volume(2_800.0, Units.METRIC, gb)))
        assertEquals("12 fl oz", norm(Fmt.volume(355.0, Units.IMPERIAL, us)))
        assertEquals("96 fl oz", norm(Fmt.volume(2_840.0, Units.IMPERIAL, us)))
        assertEquals("420 m", norm(Fmt.elevation(420.0, Units.METRIC, gb)))
        assertEquals("1,378 ft", norm(Fmt.elevation(420.0, Units.IMPERIAL, us)))
        assertEquals("21 °C", norm(Fmt.temperature(21.0, Units.METRIC, locale = gb)))
        assertEquals("70 °F", norm(Fmt.temperature(21.0, Units.IMPERIAL, locale = us)))
        assertEquals(0.54, Fmt.temperatureDelta(0.3, Units.IMPERIAL), 1e-9)
    }

    @Test
    fun duration() {
        assertEquals("42 min", Fmt.duration(42 * 60L, us))
        assertEquals("7h 12m", Fmt.duration(7 * 3600L + 12 * 60L, us))
        assertEquals("1h 05m", Fmt.duration(3600L + 5 * 60L + 59L, gb))
        assertEquals("0 min", Fmt.duration(-10L, us))
    }

    @Test
    fun timeFollowsLocaleOrForcedCycle() {
        assertEquals("3:10 PM", norm(Fmt.time(tSec(15, 10), utc, us)))
        assertEquals("15:10", Fmt.time(tSec(15, 10), utc, gb))
        assertEquals("15:10", Fmt.time(tSec(15, 10), utc, us, is24h = true))
        assertEquals("3:10 pm", norm(Fmt.time(tSec(15, 10), utc, gb, is24h = false)))
    }

    @Test
    fun dayLabels() {
        assertEquals("Today", Fmt.day(20261123, today, us))
        assertEquals("Yesterday", Fmt.day(20261122, today, gb))
        assertEquals("Sat, 21 Nov", Fmt.day(20261121, today, gb))
        assertEquals("Sat, Nov 21", Fmt.day(20261121, today, us))
        assertEquals("Thu, 25 Dec 2025", Fmt.day(20251225, today, gb))
        // Tomorrow is not "Today"/"Yesterday".
        assertEquals("Tue, 24 Nov", Fmt.day(20261124, today, gb))
    }

    @Test
    fun dayLabelsFromResources() {
        val ctx = RuntimeEnvironment.getApplication()
        assertEquals("Yesterday", Fmt.day(ctx, 20261122, today))
    }

    @Test
    fun percent() {
        assertEquals("24%", Fmt.percent(0.24, locale = us))
        assertEquals("24%", Fmt.percent(0.24, locale = gb))
        assertEquals("24.5%", Fmt.percent(0.245, 1, us))
    }

    @Test
    fun dayConversionRoundTrips() {
        assertEquals(LocalDate.of(2026, 2, 3), Fmt.dayToDate(20260203))
        assertEquals(20260203, Fmt.dateToDay(LocalDate.of(2026, 2, 3)))
    }

    @Test
    fun x100FormatAndParse() {
        assertEquals("1.5", Fmt.x100(150L, 2, us))
        assertEquals("1,5", Fmt.x100(150L, 2, Locale.GERMANY))
        assertEquals("1250", Fmt.x100(125_000L, 2, us))
        assertEquals(150L, Fmt.parseX100("1.5", us))
        assertEquals(150L, Fmt.parseX100("1,5", Locale.GERMANY))
        assertEquals(150L, Fmt.parseX100("1,5", us))
        assertEquals(1234L, Fmt.parseX100(" 12.34 ", us))
        assertNull(Fmt.parseX100("", us))
        assertNull(Fmt.parseX100("abc", us))
    }

    @Test
    fun unitsFromLocale() {
        assertEquals(Units.IMPERIAL, Units.forLocale(us))
        assertEquals(Units.METRIC, Units.forLocale(gb))
        assertEquals(Units.METRIC, Units.forLocale(Locale.GERMANY))
        assertEquals(Units.IMPERIAL, Units.forLocale(Locale.forLanguageTag("my-MM")))
    }
}
