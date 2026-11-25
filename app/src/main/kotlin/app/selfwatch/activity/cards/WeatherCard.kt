/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.SmallFace
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.Table
import app.selfwatch.devices.WeatherFragment
import app.selfwatch.model.time.Day
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WeatherCondition
import app.selfwatch.model.watch.WeatherDay
import app.selfwatch.service.relay.RelaySettings
import app.selfwatch.service.relay.WeatherStore
import app.selfwatch.shell.Views
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.StatTile
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import app.selfwatch.ui.format.Units
import kotlin.math.roundToInt
import app.selfwatch.ui.R as UiR

/**
 * Weather (optional, Activity): today's temperature, condition, high and low, and how old the forecast
 * is, from the last forecast the weather-app relay stored ([RelaySettings.WEATHER_LAST]; Self Watch makes
 * no weather network calls of its own). Without a forecast it says how to get one (Devices > Weather).
 */
object WeatherCard : Card<WeatherCard.Data>("weather", CardTab.ACTIVITY, R.string.card_weather, R.string.card_weather_summary) {

    /** The stored [forecast] (null: none yet), whether the relay is [enabled], [today] and the display [units]. */
    data class Data(val forecast: WatchCommand.Weather?, val enabled: Boolean, val today: Day, val units: Units)

    override val tables: Set<Table> = setOf(Table.SETTING)

    override suspend fun load(env: CardEnv): Data = Data(
        forecast = env.db.settings.get(RelaySettings.WEATHER_LAST)?.let { WeatherStore.decode(it) },
        enabled = env.db.settings.getBool(SettingKeys.WEATHER_ENABLED),
        today = env.clock.today(),
        units = env.units,
    )

    /** Today's forecast day in [w] (or the first one when today is not in it), or null. */
    fun todayOf(w: WatchCommand.Weather, today: Day): WeatherDay? = w.days.firstOrNull { it.day == today } ?: w.days.firstOrNull { it.day > today }

    /** A temperature in °C ×10 as a whole number in [units] (°C or °F). */
    fun degrees(c10: Int, units: Units): Int = if (units == Units.METRIC) (c10 / 10.0).roundToInt() else (c10 * 9 / 50.0 + 32).roundToInt()

    /** A forecast this old (6 h) or older is shown as old. */
    const val OLD_SEC: Long = WeatherStore.MAX_AGE_SEC

    @StringRes
    fun conditionText(c: WeatherCondition): Int = when (c) {
        WeatherCondition.CLEAR -> R.string.weather_clear
        WeatherCondition.PARTLY_CLOUDY -> R.string.weather_partly_cloudy
        WeatherCondition.CLOUDY -> R.string.weather_cloudy
        WeatherCondition.RAIN -> R.string.weather_rain
        WeatherCondition.SHOWERS -> R.string.weather_showers
        WeatherCondition.THUNDER -> R.string.weather_thunder
        WeatherCondition.SNOW -> R.string.weather_snow
        WeatherCondition.SLEET -> R.string.weather_sleet
        WeatherCondition.FOG -> R.string.weather_fog
        WeatherCondition.WINDY -> R.string.weather_windy
        WeatherCondition.HAZE -> R.string.weather_haze
        WeatherCondition.UNKNOWN -> R.string.weather_unknown
    }

    private fun unit(ctx: Context, units: Units): String = ctx.getString(if (units == Units.METRIC) R.string.unit_celsius else R.string.unit_fahrenheit)

    /** "High 14° · Low 6°" for [d]. */
    fun highLow(ctx: Context, d: WeatherDay, units: Units): String =
        ctx.getString(R.string.weather_high_low, degrees(d.maxC10, units).toString(), degrees(d.minC10, units).toString())

    /** "Updated 25 minutes ago" (or "Forecast from …" once [OLD_SEC] old). */
    fun age(ctx: Context, tSec: Long, nowSec: Long): String {
        val ago = (nowSec - tSec).coerceAtLeast(0)
        return when {
            ago < MINUTE -> ctx.getString(R.string.weather_updated_now)
            ago < HOUR -> ctx.getString(R.string.weather_updated, ctx.resources.getQuantityString(R.plurals.hrn_minutes_ago, (ago / MINUTE).toInt(), (ago / MINUTE).toInt()))
            ago < OLD_SEC -> ctx.getString(R.string.weather_updated, ctx.resources.getQuantityString(R.plurals.hrn_hours_ago, (ago / HOUR).toInt(), (ago / HOUR).toInt()))
            else -> ctx.getString(R.string.weather_old, Fmt.time(ctx, tSec))
        }
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val w = data.forecast ?: return null
        val now = System.currentTimeMillis() / 1000
        val day = todayOf(w, data.today)
        return SmallFace(
            value = degrees(w.now.tempC10, data.units).toString(),
            unit = unit(ctx, data.units),
            label = ctx.getString(conditionText(w.now.condition)),
            note = listOfNotNull(day?.let { highLow(ctx, it, data.units) }, if (now - w.now.tSec >= OLD_SEC) age(ctx, w.now.tSec, now) else null).joinToString("\n").ifEmpty { null },
            status = if (now - w.now.tSec >= OLD_SEC) CardStatus.OFF else CardStatus.NONE,
        )
    }

    override fun openDetail(host: CardHost): Boolean {
        host.open(WeatherFragment())
        return true
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = Holder(host, parent)

    private const val MINUTE = 60L
    private const val HOUR = 3_600L

    private class Holder(host: CardHost, parent: ViewGroup) : CardView<Data>(SwCard(parent.context)) {
        private val ctx: Context = parent.context
        private val tile = StatTile(ctx)
        private val highLowText = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Body)
        private val ageText = Views.caption(ctx)
        private val empty = EmptyState(ctx)

        init {
            val card = root as SwCard
            card.title = ctx.getString(R.string.card_weather)
            tile.setValueAppearance(UiR.style.TextAppearance_SelfWatch_Display_Number)
            card.addView(tile, Views.matchWrap(ctx))
            card.addView(highLowText, Views.matchWrap(ctx, 4))
            card.addView(ageText, Views.matchWrap(ctx, 4))
            card.addView(empty, Views.matchWrap(ctx))
            for (v in listOf(tile, highLowText, ageText)) v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            card.setOnClickListener { host.open(WeatherFragment()) }
        }

        override fun bind(data: Data) {
            val w = data.forecast
            for (v in listOf(tile, highLowText, ageText)) v.visibility = if (w == null) View.GONE else View.VISIBLE
            empty.visibility = if (w == null) View.VISIBLE else View.GONE
            if (w == null) {
                empty.message = ctx.getString(if (data.enabled) R.string.weather_waiting else R.string.weather_off)
                root.contentDescription = listOf(ctx.getString(R.string.card_weather), empty.message).joinToString(". ")
                return
            }
            val now = System.currentTimeMillis() / 1000
            tile.value = degrees(w.now.tempC10, data.units).toString()
            tile.unit = unit(ctx, data.units)
            tile.label = listOfNotNull(ctx.getString(conditionText(w.now.condition)), w.now.location).joinToString(" · ")
            val day = todayOf(w, data.today)
            highLowText.text = day?.let { d ->
                listOfNotNull(highLow(ctx, d, data.units), d.precipPct?.let { ctx.getString(R.string.weather_rain_chance, it) }).joinToString(" · ")
            }
            highLowText.visibility = if (day == null) View.GONE else View.VISIBLE
            ageText.text = age(ctx, w.now.tSec, now)
            root.contentDescription = listOfNotNull(
                ctx.getString(R.string.card_weather),
                listOfNotNull(tile.value, tile.unit, tile.label).joinToString(" "),
                highLowText.text?.takeIf { day != null },
                ageText.text,
            ).joinToString(". ")
        }
    }
}
