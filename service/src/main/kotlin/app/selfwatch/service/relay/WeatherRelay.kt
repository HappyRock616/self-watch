/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SettingsStore
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Day
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WeatherCondition
import app.selfwatch.model.watch.WeatherDay
import app.selfwatch.model.watch.WeatherNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/**
 * Reads the Gadgetbridge-format weather that weather apps such as Breezy Weather broadcast
 * (`nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER`). Temperatures arrive in kelvin,
 * wind in km/h, condition codes are OpenWeatherMap codes; today's min and max are on the top level and
 * `forecasts` starts with tomorrow.
 *
 * Ref: Gadgetbridge 160beab41 externalevents/GenericWeatherReceiver.java (extras WeatherJson,
 * WeatherSecondaryJson, WeatherGz; field names), model/WeatherSpec.kt (units: kelvin, km/h)
 */
public object GenericWeather {
    /** The broadcast action. */
    public const val ACTION: String = "nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER"

    /** Extra: the primary location's weather as a JSON object (`String`). */
    public const val EXTRA_JSON: String = "WeatherJson"

    /** Extra: gzipped JSON array of locations, the primary first (`byte[]`). */
    public const val EXTRA_GZ: String = "WeatherGz"

    private const val MAX_JSON_CHARS = 512 * 1024
    private const val MAX_GZ_BYTES = 1024 * 1024
    private const val MAX_DAYS = 10
    private const val KELVIN_X10 = 2731.5

    /** The weather in [intent]'s extras (gzip first, then plain JSON). */
    public fun fromIntent(intent: Intent, zone: ZoneId): Outcome<WatchCommand.Weather> {
        val gz = intent.getByteArrayExtra(EXTRA_GZ)
        if (gz != null) {
            val text = gunzip(gz) ?: return Outcome.Fail(SwError.BadInput("weather gzip unreadable"))
            return parse(text, zone)
        }
        val json = intent.getStringExtra(EXTRA_JSON) ?: return Outcome.Fail(SwError.BadInput("no weather extra"))
        return parse(json, zone)
    }

    /** Parses one weather object, or an array whose first element is the primary location. */
    public fun parse(text: String, zone: ZoneId): Outcome<WatchCommand.Weather> {
        if (text.length > MAX_JSON_CHARS) return Outcome.Fail(SwError.BadInput("weather JSON too large"))
        val root = when (val r = Json.parse(text, maxDepth = 16)) {
            is Outcome.Fail -> return r
            is Outcome.Ok -> r.value
        }
        val primary = if (root is JsonValue.Arr) root[0] else root
        if (primary !is JsonValue.Obj) return Outcome.Fail(SwError.BadInput("weather is not an object"))
        return map(primary, zone)
    }

    private fun map(w: JsonValue.Obj, zone: ZoneId): Outcome<WatchCommand.Weather> {
        val tSec = w.num("timestamp")?.toLong() ?: return Outcome.Fail(SwError.BadInput("weather has no timestamp"))
        val currentK = w.num("currentTemp") ?: return Outcome.Fail(SwError.BadInput("weather has no temperature"))
        val condition = condition(w.num("currentConditionCode")?.toInt())
        val now = WeatherNow(
            tSec = tSec,
            tempC10 = c10(currentK),
            feelsLikeC10 = w.num("feelsLikeTemp")?.takeIf { it > 0 }?.let { c10(it) },
            condition = condition,
            humidityPct = w.num("currentHumidity")?.toInt()?.takeIf { it in 0..100 },
            windKmhX10 = w.num("windSpeed")?.takeIf { it >= 0 }?.let { (it * 10).roundToInt() },
            windDeg = w.num("windDirection")?.toInt()?.takeIf { it in 0..360 },
            location = w["location"]?.asString?.takeIf { it.isNotBlank() },
        )
        val today = Day.of(tSec, zone)
        val days = ArrayList<WeatherDay>()
        val tMin = w.num("todayMinTemp")
        val tMax = w.num("todayMaxTemp")
        if (tMin != null && tMax != null && tMax > 0) {
            days.add(WeatherDay(today, c10(tMin), c10(tMax), condition, w.num("precipProbability")?.toInt()?.takeIf { it in 0..100 }))
        }
        val forecasts = w["forecasts"]?.asList.orEmpty()
        for ((i, f) in forecasts.take(MAX_DAYS).withIndex()) {
            if (f !is JsonValue.Obj) continue
            val min = f.num("minTemp") ?: continue
            val max = f.num("maxTemp") ?: continue
            days.add(
                WeatherDay(
                    day = today.plusDays(i + 1),
                    minC10 = c10(min),
                    maxC10 = c10(max),
                    condition = condition(f.num("conditionCode")?.toInt()),
                    precipPct = f.num("precipProbability")?.toInt()?.takeIf { it in 0..100 },
                ),
            )
        }
        return Outcome.Ok(WatchCommand.Weather(now, days))
    }

    private fun JsonValue.num(key: String): Double? = (this[key] as? JsonValue.Num)?.let {
        try {
            it.toDouble().takeIf { d -> d.isFinite() }
        } catch (e: NumberFormatException) {
            SwLog.d(TAG) { "bad number for $key: $e" }
            null
        }
    }

    /** Kelvin to °C ×10. */
    public fun c10(kelvin: Double): Int = (kelvin * 10 - KELVIN_X10).roundToInt()

    /** OpenWeatherMap condition code to the watch's condition. */
    public fun condition(code: Int?): WeatherCondition = when (code) {
        null -> WeatherCondition.UNKNOWN
        in 200..299 -> WeatherCondition.THUNDER
        in 300..399 -> WeatherCondition.SHOWERS
        511 -> WeatherCondition.SLEET
        in 520..531 -> WeatherCondition.SHOWERS
        in 500..599 -> WeatherCondition.RAIN
        in 611..616 -> WeatherCondition.SLEET
        in 600..699 -> WeatherCondition.SNOW
        701, 741 -> WeatherCondition.FOG
        711, 721, 731, 751, 761, 762 -> WeatherCondition.HAZE
        771, 781 -> WeatherCondition.WINDY
        800 -> WeatherCondition.CLEAR
        801, 802 -> WeatherCondition.PARTLY_CLOUDY
        803, 804 -> WeatherCondition.CLOUDY
        900, 901, 902, 905, 957 -> WeatherCondition.WINDY
        else -> WeatherCondition.UNKNOWN
    }

    private fun gunzip(bytes: ByteArray): String? = try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_GZ_BYTES) return null
            }
            out.toString(Charsets.UTF_8.name())
        }
    } catch (e: IOException) {
        SwLog.w(TAG, "weather gzip unreadable", e)
        null
    }

    private const val TAG = "GenericWeather"
}

/** Keeps the last forecast (setting [RelaySettings.WEATHER_LAST]) so a watch that connects later gets it. */
public class WeatherStore(private val settings: SettingsStore) {

    /** Saves [w]. */
    public suspend fun save(w: WatchCommand.Weather) {
        settings.set(RelaySettings.WEATHER_LAST, encode(w))
    }

    /** The last forecast if it is at most [maxAgeSec] old at [nowSec], else null. */
    public suspend fun fresh(nowSec: Long, maxAgeSec: Long = MAX_AGE_SEC): WatchCommand.Weather? {
        val w = settings.get(RelaySettings.WEATHER_LAST)?.let { decode(it) } ?: return null
        return w.takeIf { nowSec - it.now.tSec in 0..maxAgeSec }
    }

    /** Encoding and limits. */
    public companion object {
        /** A forecast older than this (6 h) is not pushed on connect. */
        public const val MAX_AGE_SEC: Long = 6 * 3600

        /** [w] as compact JSON. */
        public fun encode(w: WatchCommand.Weather): String {
            val n = w.now
            val now = buildMap<String, JsonValue> {
                put("t", JsonValue.Num.of(n.tSec))
                put("temp", JsonValue.Num.of(n.tempC10.toLong()))
                n.feelsLikeC10?.let { put("feels", JsonValue.Num.of(it.toLong())) }
                put("cond", JsonValue.Str(n.condition.name))
                n.humidityPct?.let { put("hum", JsonValue.Num.of(it.toLong())) }
                n.windKmhX10?.let { put("wind", JsonValue.Num.of(it.toLong())) }
                n.windDeg?.let { put("windDeg", JsonValue.Num.of(it.toLong())) }
                n.location?.let { put("loc", JsonValue.Str(it)) }
            }
            val days = w.days.map { d ->
                JsonValue.Obj(
                    buildMap {
                        put("d", JsonValue.Num.of(d.day.yyyymmdd.toLong()))
                        put("min", JsonValue.Num.of(d.minC10.toLong()))
                        put("max", JsonValue.Num.of(d.maxC10.toLong()))
                        put("cond", JsonValue.Str(d.condition.name))
                        d.precipPct?.let { put("p", JsonValue.Num.of(it.toLong())) }
                    },
                )
            }
            return Json.write(JsonValue.Obj(mapOf("now" to JsonValue.Obj(now), "days" to JsonValue.Arr(days))))
        }

        /** The forecast in [text] (from [encode]), or null when unreadable. */
        public fun decode(text: String): WatchCommand.Weather? {
            val root = (Json.parse(text) as? Outcome.Ok)?.value ?: return null
            val n = root["now"] ?: return null
            val now = WeatherNow(
                tSec = n["t"]?.asLong ?: return null,
                tempC10 = n["temp"]?.asLong?.toInt() ?: return null,
                feelsLikeC10 = n["feels"]?.asLong?.toInt(),
                condition = cond(n["cond"]?.asString),
                humidityPct = n["hum"]?.asLong?.toInt(),
                windKmhX10 = n["wind"]?.asLong?.toInt(),
                windDeg = n["windDeg"]?.asLong?.toInt(),
                location = n["loc"]?.asString,
            )
            val days = root["days"]?.asList.orEmpty().mapNotNull { d ->
                WeatherDay(
                    day = Day(d["d"]?.asLong?.toInt() ?: return@mapNotNull null),
                    minC10 = d["min"]?.asLong?.toInt() ?: return@mapNotNull null,
                    maxC10 = d["max"]?.asLong?.toInt() ?: return@mapNotNull null,
                    condition = cond(d["cond"]?.asString),
                    precipPct = d["p"]?.asLong?.toInt(),
                )
            }
            return WatchCommand.Weather(now, days)
        }

        private fun cond(name: String?): WeatherCondition =
            WeatherCondition.entries.firstOrNull { it.name == name } ?: WeatherCondition.UNKNOWN
    }
}

/**
 * Receives weather from weather apps (B22; exported so they can reach it, process `:watch`).
 * Off by default: does nothing unless `weather.enabled` is on. Each forecast is stored
 * ([WeatherStore]) and sent to every connected watch; [app.selfwatch.service.SyncService] sends the
 * stored one to a watch when it connects. No weather network calls of our own.
 */
public class WeatherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GenericWeather.ACTION) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                handle(context.applicationContext ?: context, intent)
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private suspend fun handle(ctx: Context, intent: Intent) {
        val settings = SelfWatchDb.get(ctx).settings
        if (!settings.getBool(SettingKeys.WEATHER_ENABLED)) {
            SwLog.d(TAG) { "weather broadcast ignored (weather off)" }
            return
        }
        when (val r = GenericWeather.fromIntent(intent, ZoneId.systemDefault())) {
            is Outcome.Fail -> SwLog.w(TAG, "weather broadcast unreadable: ${r.error}")
            is Outcome.Ok -> {
                WeatherStore(settings).save(r.value)
                RelayHub.sendToAll(r.value)
                SwLog.i(TAG, "weather received (${r.value.days.size} days)")
            }
        }
    }

    private companion object {
        const val TAG = "WeatherReceiver"
    }
}
