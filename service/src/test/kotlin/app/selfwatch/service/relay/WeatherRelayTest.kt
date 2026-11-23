/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import android.content.Intent
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Day
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WeatherCondition
import app.selfwatch.service.ServiceDbTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** B22: Gadgetbridge-format weather JSON to the watch model, storage, and the receiver. */
class WeatherRelayTest : ServiceDbTest() {

    /** 2026-11-23 12:00 in Detroit. */
    private val t = 1_795_453_200L

    // Own sample in the broadcast format: kelvin, km/h, OpenWeatherMap codes; forecasts start tomorrow.
    private val sample = """
        {"timestamp":$t,"location":"Traverse City","currentTemp":276,"currentConditionCode":803,
         "currentCondition":"Broken clouds","currentHumidity":81,"windSpeed":18.5,"windDirection":240,
         "feelsLikeTemp":272,"todayMinTemp":271,"todayMaxTemp":278,"precipProbability":20,
         "forecasts":[
           {"conditionCode":601,"minTemp":268,"maxTemp":273,"precipProbability":80},
           {"conditionCode":800,"minTemp":265,"maxTemp":272},
           {"conditionCode":211,"minTemp":"bad","maxTemp":280}
         ],
         "hourly":[{"timestamp":$t,"temp":276,"conditionCode":803}]}
    """.trimIndent()

    @Test
    fun mapsTheSample() {
        val w = (GenericWeather.parse(sample, zone) as Outcome.Ok).value
        val n = w.now
        assertEquals(t, n.tSec)
        assertEquals(29, n.tempC10) // 276 K = 2.85 °C
        assertEquals(-11, n.feelsLikeC10)
        assertEquals(WeatherCondition.CLOUDY, n.condition)
        assertEquals(81, n.humidityPct)
        assertEquals(185, n.windKmhX10)
        assertEquals(240, n.windDeg)
        assertEquals("Traverse City", n.location)
        assertEquals(3, w.days.size)
        assertEquals(Day(20261123), w.days[0].day)
        assertEquals(-21, w.days[0].minC10)
        assertEquals(49, w.days[0].maxC10)
        assertEquals(20, w.days[0].precipPct)
        assertEquals(Day(20261124), w.days[1].day)
        assertEquals(WeatherCondition.SNOW, w.days[1].condition)
        assertEquals(80, w.days[1].precipPct)
        assertEquals(Day(20261125), w.days[2].day)
        assertEquals(WeatherCondition.CLEAR, w.days[2].condition)
        assertNull(w.days[2].precipPct)
    }

    @Test
    fun arrayFormTakesThePrimaryAndBadInputFails() {
        val w = GenericWeather.parse("[$sample, {\"timestamp\":1,\"currentTemp\":300}]", zone)
        assertEquals("Traverse City", (w as Outcome.Ok).value.now.location)
        assertTrue(GenericWeather.parse("{\"currentTemp\":280}", zone) is Outcome.Fail)
        assertTrue(GenericWeather.parse("not json", zone) is Outcome.Fail)
        assertTrue(GenericWeather.parse("42", zone) is Outcome.Fail)
    }

    @Test
    fun conditionCodes() {
        assertEquals(WeatherCondition.THUNDER, GenericWeather.condition(202))
        assertEquals(WeatherCondition.SHOWERS, GenericWeather.condition(301))
        assertEquals(WeatherCondition.RAIN, GenericWeather.condition(501))
        assertEquals(WeatherCondition.SLEET, GenericWeather.condition(511))
        assertEquals(WeatherCondition.SHOWERS, GenericWeather.condition(521))
        assertEquals(WeatherCondition.SLEET, GenericWeather.condition(612))
        assertEquals(WeatherCondition.FOG, GenericWeather.condition(741))
        assertEquals(WeatherCondition.HAZE, GenericWeather.condition(721))
        assertEquals(WeatherCondition.PARTLY_CLOUDY, GenericWeather.condition(801))
        assertEquals(WeatherCondition.UNKNOWN, GenericWeather.condition(null))
        assertEquals(WeatherCondition.UNKNOWN, GenericWeather.condition(42))
    }

    @Test
    fun intentExtrasPlainAndGzip() {
        val plain = Intent(GenericWeather.ACTION).putExtra(GenericWeather.EXTRA_JSON, sample)
        assertTrue(GenericWeather.fromIntent(plain, zone) is Outcome.Ok)
        val bytes = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write("[$sample]".toByteArray()) } }.toByteArray()
        val gz = Intent(GenericWeather.ACTION).putExtra(GenericWeather.EXTRA_GZ, bytes)
        assertEquals(3, (GenericWeather.fromIntent(gz, zone) as Outcome.Ok).value.days.size)
        assertTrue(GenericWeather.fromIntent(Intent(GenericWeather.ACTION).putExtra(GenericWeather.EXTRA_GZ, byteArrayOf(1, 2)), zone) is Outcome.Fail)
        assertTrue(GenericWeather.fromIntent(Intent(GenericWeather.ACTION), zone) is Outcome.Fail)
    }

    @Test
    fun storeKeepsTheLastForecastWhileFresh() = runBlocking {
        val w = (GenericWeather.parse(sample, zone) as Outcome.Ok).value
        assertEquals(w, WeatherStore.decode(WeatherStore.encode(w)))
        val store = WeatherStore(db.settings)
        assertNull(store.fresh(t))
        store.save(w)
        assertEquals(w, store.fresh(t + 3600))
        assertNull("older than 6 h is not pushed", store.fresh(t + 7 * 3600))
    }

    @Test
    fun receiverIsOffByDefaultAndStoresAndSendsWhenOn() {
        val got = ArrayList<WatchCommand>()
        val b = WatchBroadcaster { got.add(it) }
        RelayHub.attach(b)
        try {
            val intent = Intent(GenericWeather.ACTION).putExtra(GenericWeather.EXTRA_JSON, sample)
            val start = System.currentTimeMillis()
            deliver(intent) { System.currentTimeMillis() - start > 500 }
            assertTrue(got.isEmpty())
            runBlocking { db.settings.setBool(SettingKeys.WEATHER_ENABLED, true) }
            deliver(intent) { got.isNotEmpty() }
            assertEquals(1, got.size)
            assertTrue(got[0] is WatchCommand.Weather)
            assertEquals(got[0], runBlocking { WeatherStore(db.settings).fresh(t) })
        } finally {
            RelayHub.detach(b)
        }
    }

    private fun deliver(intent: Intent, until: () -> Boolean) {
        app.sendBroadcast(intent.setClass(app, WeatherReceiver::class.java))
        // The receiver works on Dispatchers.IO.
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end && !until()) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
    }
}
