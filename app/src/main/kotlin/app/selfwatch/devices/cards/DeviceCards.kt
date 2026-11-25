/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices.cards

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import app.selfwatch.R
import app.selfwatch.cards.Card
import app.selfwatch.cards.CardEnv
import app.selfwatch.cards.CardHost
import app.selfwatch.cards.CardSize
import app.selfwatch.cards.CardStatus
import app.selfwatch.cards.CardTab
import app.selfwatch.cards.CardView
import app.selfwatch.cards.DataKind
import app.selfwatch.cards.SmallFace
import app.selfwatch.cards.WaitingCard
import app.selfwatch.db.Table
import app.selfwatch.db.dao.BatteryReading
import app.selfwatch.model.time.TimeRange
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.shell.Views
import app.selfwatch.ui.charts.ChartPalette
import app.selfwatch.ui.components.EmptyState
import app.selfwatch.ui.components.ListRow
import app.selfwatch.ui.components.Sparkline
import app.selfwatch.ui.components.SwCard
import app.selfwatch.ui.format.Fmt
import java.time.Instant
import java.time.ZoneId

/**
 * The device cards (CARDS_PLAN.md D1–D4): device data, not health. There is no Devices tab of cards,
 * so their home is Body and Edit cards lists them under their own "Device cards" group; they can be
 * added to any tab.
 */
object DeviceCards {
    /** All device cards, in Edit cards order. */
    val ALL: List<Card<*>> by lazy { listOf(BatteryCard, LastSyncCard, SolarCard, RoomClimateCard) }

    /** "25" + "min ago", "3" + "h ago" or "2" + "d ago" for something [ageSec] ago. */
    fun ago(ctx: Context, ageSec: Long): Pair<String, String> {
        val a = ageSec.coerceAtLeast(0)
        return when {
            a < HOUR -> Fmt.number((a / MINUTE).coerceAtLeast(1)) to ctx.getString(R.string.ago_min)
            a < 2 * DAY -> Fmt.number(a / HOUR) to ctx.getString(R.string.ago_hours)
            else -> Fmt.number(a / DAY) to ctx.getString(R.string.ago_days)
        }
    }

    /** "Today 08:12" for [tSec]. */
    fun dayTime(ctx: Context, tSec: Long): String {
        val date = Instant.ofEpochSecond(tSec).atZone(ZoneId.systemDefault()).toLocalDate()
        return ctx.getString(R.string.card_line_pair_plain, Fmt.day(ctx, Fmt.dateToDay(date)), Fmt.time(ctx, tSec))
    }

    internal const val MINUTE = 60L
    internal const val HOUR = 3_600L
    internal const val DAY = 86_400L
}

/** D2 Solar charging: waits until a solar watch's intake is stored. */
val SolarCard = WaitingCard(
    "solar", CardTab.BODY, R.string.card_solar, R.string.card_solar_summary, DataKind.SOLAR, group = R.string.card_home_devices,
)

/** D3 Room climate: waits until a Bluetooth thermometer's readings are stored. */
val RoomClimateCard = WaitingCard(
    "room_climate", CardTab.BODY, R.string.card_room_climate, R.string.card_room_climate_summary, DataKind.ROOM_CLIMATE,
    group = R.string.card_home_devices,
)

/**
 * D1 Battery: each device's battery now and over the last week (one reading stored each time a
 * device reports a new level), with about how much it uses a day. The small form shows the lowest.
 */
object BatteryCard : Card<BatteryCard.Data>("battery", CardTab.BODY, R.string.card_battery, R.string.card_battery_summary) {

    /** One device: its latest [pct] (at [tSec], null when only the connection status had it) and the week's [history]. */
    data class Device(val id: Long, val name: String, val pct: Int?, val tSec: Long?, val history: List<BatteryReading>)

    /** The active devices and when this was loaded. */
    data class Data(val devices: List<Device>, val nowSec: Long)

    override val tables: Set<Table> = setOf(Table.DEVICE, Table.DEVICE_BATTERY)

    override val needs: Set<DataKind> = setOf(DataKind.BATTERY)

    override val group: Int get() = R.string.card_home_devices

    override suspend fun load(env: CardEnv): Data {
        val now = env.clock.nowSec()
        val week = TimeRange(now - HISTORY_SEC, now + 1)
        val devices = env.db.devices.active().map { d ->
            val history = env.db.battery.readings(week, d.id)
            val latest = history.lastOrNull() ?: env.db.battery.latest(d.id)
            Device(d.id, d.name, latest?.pct ?: env.db.settings.getInt(WatchStatus.batteryKey(d.id)), latest?.tSec, history)
        }
        return Data(devices, now)
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val known = data.devices.filter { it.pct != null }
        val low = known.minByOrNull { it.pct!! } ?: return null
        val pct = low.pct!!
        return SmallFace(
            value = pct.toString(),
            unit = ctx.getString(R.string.unit_percent),
            label = low.name,
            status = DeviceCardMath.batteryStatus(pct),
            note = if (known.size > 1) ctx.getString(R.string.battery_lowest_of, known.size) else DeviceCardMath.dailyDrain(low.history)?.let { ctx.getString(R.string.battery_drain, it) },
            progress = pct / 100f,
        )
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = object : CardView<Data>(SwCard(parent.context)) {
        private val ctx = parent.context
        private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val empty = EmptyState(ctx)

        init {
            (root as SwCard).title = ctx.getString(R.string.card_battery)
            root.addView(list, Views.matchWrap(ctx))
            empty.message = ctx.getString(R.string.battery_empty)
            root.addView(empty, Views.matchWrap(ctx))
        }

        override fun bind(data: Data) {
            list.removeAllViews()
            val none = data.devices.none { it.pct != null }
            empty.visibility = if (none) View.VISIBLE else View.GONE
            list.visibility = if (none) View.GONE else View.VISIBLE
            if (none) {
                root.contentDescription = listOf(ctx.getString(R.string.card_battery), empty.message).joinToString(". ")
                return
            }
            val palette = ChartPalette.of(ctx)
            val spoken = ArrayList<String>()
            for (d in data.devices) {
                val pct = d.pct ?: continue
                val subtitle = listOfNotNull(
                    d.tSec?.let { DeviceCards.ago(ctx, data.nowSec - it).let { (v, u) -> "$v $u" } },
                    DeviceCardMath.dailyDrain(d.history)?.let { ctx.getString(R.string.battery_drain, it) },
                ).joinToString(" · ").ifEmpty { null }
                val row = ListRow(ctx).apply {
                    title = d.name
                    trailing = ctx.getString(R.string.battery_pct, pct)
                    this.subtitle = subtitle
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                list.addView(row, Views.matchWrap(ctx))
                if (d.history.size >= 2) {
                    list.addView(
                        Sparkline(ctx).apply {
                            values = d.history.map { it.pct }.toIntArray()
                            color = CardColors.battery(palette, pct)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.resources.getDimensionPixelSize(R.dimen.trend_card_spark)),
                    )
                }
                spoken += listOfNotNull(d.name, ctx.getString(R.string.battery_pct, pct), subtitle).joinToString(", ")
            }
            root.contentDescription = (listOf(ctx.getString(R.string.card_battery)) + spoken).joinToString(". ")
        }
    }

    private const val HISTORY_SEC = 7 * DeviceCards.DAY
}

/** Theme colors for the device cards. */
internal object CardColors {
    /** A battery line: primary when fine, secondary when getting low, muted when low. */
    fun battery(p: ChartPalette, pct: Int): Int = when (DeviceCardMath.batteryStatus(pct)) {
        CardStatus.GOOD -> p.primary
        CardStatus.CLOSE -> p.secondary
        else -> p.muted
    }
}

/**
 * D4 Last sync: for each device, when it last sent data and what it was (workouts, activity, sleep…,
 * from the files stored in that sync). The small form shows the most recent sync.
 */
object LastSyncCard : Card<LastSyncCard.Data>("last_sync", CardTab.BODY, R.string.card_last_sync, R.string.card_last_sync_summary) {

    /** One device: its last sync (null: never) and what that sync stored. */
    data class Device(val id: Long, val name: String, val lastSec: Long?, val what: Map<SyncedKind, Int>)

    /** The active devices and when this was loaded. */
    data class Data(val devices: List<Device>, val nowSec: Long)

    override val tables: Set<Table> = setOf(Table.DEVICE, Table.SYNC_FILE)

    override val needs: Set<DataKind> = setOf(DataKind.DEVICE_SYNC)

    override val defaultSize: CardSize get() = CardSize.LARGE

    override val group: Int get() = R.string.card_home_devices

    override suspend fun load(env: CardEnv): Data {
        val devices = env.db.devices.active().map { d ->
            val last = env.db.devices.lastSync(d.id)
            val files = if (last == null) emptyList() else env.db.syncFiles.doneBetween(d.id, last - DeviceCardMath.SYNC_WINDOW_SEC, last + DeviceCards.MINUTE)
            Device(d.id, d.name, last, DeviceCardMath.summarize(files))
        }
        return Data(devices, env.clock.nowSec())
    }

    /** "Workouts 1 · Activity 3 · Sleep 1", or "No new data". */
    fun what(ctx: Context, d: Device): String = if (d.what.isEmpty()) {
        ctx.getString(R.string.last_sync_nothing)
    } else {
        d.what.entries.joinToString(" · ") { (k, n) -> ctx.getString(R.string.synced_count, ctx.getString(k.label), n) }
    }

    override fun small(ctx: Context, data: Data): SmallFace? {
        val latest = data.devices.filter { it.lastSec != null }.maxByOrNull { it.lastSec!! } ?: return null
        val age = data.nowSec - latest.lastSec!!
        val (v, u) = DeviceCards.ago(ctx, age)
        return SmallFace(value = v, unit = u, label = latest.name, status = DeviceCardMath.syncStatus(age), note = what(ctx, latest))
    }

    override fun createView(host: CardHost, parent: ViewGroup): CardView<Data> = object : CardView<Data>(SwCard(parent.context)) {
        private val ctx = parent.context
        private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val empty = EmptyState(ctx)

        init {
            (root as SwCard).title = ctx.getString(R.string.card_last_sync)
            root.addView(list, Views.matchWrap(ctx))
            empty.message = ctx.getString(R.string.last_sync_empty)
            root.addView(empty, Views.matchWrap(ctx))
        }

        override fun bind(data: Data) {
            list.removeAllViews()
            val none = data.devices.isEmpty()
            empty.visibility = if (none) View.VISIBLE else View.GONE
            list.visibility = if (none) View.GONE else View.VISIBLE
            if (none) {
                root.contentDescription = listOf(ctx.getString(R.string.card_last_sync), empty.message).joinToString(". ")
                return
            }
            val spoken = ArrayList<String>()
            for (d in data.devices) {
                val last = d.lastSec
                val row = ListRow(ctx).apply {
                    title = d.name
                    trailing = last?.let { DeviceCards.dayTime(ctx, it) } ?: ctx.getString(R.string.last_sync_never)
                    subtitle = if (last == null) null else what(ctx, d)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                list.addView(row, Views.matchWrap(ctx))
                spoken += listOfNotNull(row.title, row.trailing, row.subtitle).joinToString(", ")
            }
            root.contentDescription = (listOf(ctx.getString(R.string.card_last_sync)) + spoken).joinToString(". ")
        }
    }
}
