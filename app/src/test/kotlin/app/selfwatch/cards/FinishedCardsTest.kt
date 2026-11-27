/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import android.app.Application
import app.selfwatch.activity.cards.LiveCard
import app.selfwatch.activity.cards.WeatherCard
import app.selfwatch.body.TestDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SyncFileState
import app.selfwatch.db.dao.BatteryReading
import app.selfwatch.db.dao.SyncFileCount
import app.selfwatch.devices.cards.BatteryCard
import app.selfwatch.devices.cards.DeviceCardMath
import app.selfwatch.devices.cards.DeviceCards
import app.selfwatch.devices.cards.LastSyncCard
import app.selfwatch.devices.cards.RoomClimateCard
import app.selfwatch.devices.cards.SolarCard
import app.selfwatch.devices.cards.SyncedKind
import app.selfwatch.food.cards.FoodGroup
import app.selfwatch.food.cards.FoodGroupHook
import app.selfwatch.food.cards.FoodGroupsCard
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.TimeRange
import app.selfwatch.model.watch.SyncFileKey
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WeatherCondition
import app.selfwatch.model.watch.WeatherDay
import app.selfwatch.model.watch.WeatherNow
import app.selfwatch.service.live.LiveReading
import app.selfwatch.service.relay.RelaySettings
import app.selfwatch.service.relay.WeatherStore
import app.selfwatch.ui.format.Units
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

/** Day clock, the device cards, Live, Weather and the food groups: their math and their loaders. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FinishedCardsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.of("America/Detroit")
    private val day = Day(20261125)
    private val range = day.toTimeRange(zone)
    private val start = range.startSec

    private fun minute(m: Int, steps: Int, intensity: Int, device: Long = 1) = ActivityMinute(device, start + m * 60L, steps, 0, 0, intensity, null)

    private fun device(address: String, kind: DeviceKind = DeviceKind.GARMIN_WATCH, features: Set<Feature> = emptySet(), name: String = "Watch") =
        DeviceInfo(0, kind, address, name, "model", null, null, null, SupportTier.VERIFIED, features, null)

    // ---- Device cards ----

    @Test
    fun syncFilesAreNamedByWhatTheyHeld() {
        assertEquals(SyncedKind.WORKOUTS, DeviceCardMath.kindOf(128, 4))
        assertEquals(SyncedKind.ACTIVITY, DeviceCardMath.kindOf(128, 32))
        assertEquals(SyncedKind.SLEEP, DeviceCardMath.kindOf(128, 49))
        assertEquals(SyncedKind.HRV, DeviceCardMath.kindOf(128, 68))
        assertEquals(SyncedKind.MEASUREMENTS, DeviceCardMath.kindOf(0x10000 + 0x2A9C, 0))
        assertEquals(SyncedKind.OTHER, DeviceCardMath.kindOf(255, 1))
        val sum = DeviceCardMath.summarize(listOf(SyncFileCount(128, 49, 1), SyncFileCount(128, 4, 2), SyncFileCount(128, 32, 1), SyncFileCount(128, 15, 2)))
        assertEquals(listOf(SyncedKind.WORKOUTS to 2, SyncedKind.ACTIVITY to 3, SyncedKind.SLEEP to 1), sum.toList())
    }

    @Test
    fun batteryDrainAndStatus() {
        val h = listOf(BatteryReading(1, 0, 100), BatteryReading(1, 43_200, 95), BatteryReading(1, 86_400, 90), BatteryReading(1, 100_000, 100), BatteryReading(1, 172_800, 92))
        // 18 percentage points dropped over two days (the charge in between ignored).
        assertEquals(9, DeviceCardMath.dailyDrain(h))
        assertNull(DeviceCardMath.dailyDrain(h.take(1)))
        assertNull(DeviceCardMath.dailyDrain(listOf(BatteryReading(1, 0, 80), BatteryReading(1, 600, 79))))
        assertEquals(CardStatus.GOOD, DeviceCardMath.batteryStatus(80))
        assertEquals(CardStatus.CLOSE, DeviceCardMath.batteryStatus(30))
        assertEquals(CardStatus.OFF, DeviceCardMath.batteryStatus(10))
        assertEquals(CardStatus.GOOD, DeviceCardMath.syncStatus(3_600))
        assertEquals(CardStatus.OFF, DeviceCardMath.syncStatus(5 * 86_400))
    }

    @Test
    fun batteryAndLastSyncLoadPerDevice() = runBlocking {
        val db = TestDb.fresh(ctx)
        val now = 1_795_435_200L
        val clock = FakeClock(now, zone)
        val watch = db.devices.upsert(device("AA:01", name = "vívoactive 5"))
        val strap = db.devices.upsert(device("AA:02", DeviceKind.HEART_RATE_SENSOR, name = "Strap"))
        db.battery.add(watch, now - 2 * 86_400, 90)
        db.battery.add(watch, now - 3_600, 72)
        db.battery.add(strap, now - 600, 15)
        val env = CardEnv(db, clock, day, Units.METRIC)
        val battery = BatteryCard.load(env)
        assertEquals(listOf(72, 15), battery.devices.map { it.pct })
        val face = BatteryCard.small(ctx, battery)!!
        assertEquals("15", face.value)
        assertEquals("Strap", face.label)
        assertEquals(CardStatus.OFF, face.status)

        // The watch synced: two files that sync, one long before.
        db.devices.setLastSync(watch, now - 1_800)
        db.syncFiles.mark(SyncFileKey(watch, 1, 128, 4, now - 9_000, 100), SyncFileState.DONE, now - 2_000)
        db.syncFiles.mark(SyncFileKey(watch, 2, 128, 32, now - 9_000, 200), SyncFileState.DONE, now - 1_900)
        db.syncFiles.mark(SyncFileKey(watch, 3, 128, 49, now - 99_000, 300), SyncFileState.DONE, now - 90_000)
        val sync = LastSyncCard.load(env)
        val w = sync.devices.first { it.id == watch }
        assertEquals(now - 1_800, w.lastSec)
        assertEquals(mapOf(SyncedKind.WORKOUTS to 1, SyncedKind.ACTIVITY to 1), w.what)
        assertNull(sync.devices.first { it.id == strap }.lastSec)
        val s = LastSyncCard.small(ctx, sync)!!
        assertEquals("30", s.value)
        assertEquals("vívoactive 5", s.label)
        assertEquals("Workouts 1 · Activity 1", s.note)
    }

    @Test
    fun deviceCardsHaveTheirOwnGroupInEditCards() {
        val rows = EditCardsUiState(slots = emptyList(), recorded = Recorded.ALL, showAll = true, loading = false).rows(CardTab.ACTIVITY)
        val headers = rows.filterIsInstance<EditRow.Header>().map { it.title }
        assertEquals(app.selfwatch.R.string.card_home_devices, headers.last())
        val afterGroup = rows.dropWhile { !(it is EditRow.Header && it.title == app.selfwatch.R.string.card_home_devices) }.drop(1)
        assertEquals(DeviceCards.ALL, afterGroup.map { (it as EditRow.Hidden).card })
        // Without "Show all", solar and room climate (never recorded yet) stay out; battery and sync show.
        val plain = EditCardsUiState(slots = emptyList(), recorded = Recorded.ALL, showAll = false, loading = false).rows(CardTab.BODY)
        val listed = plain.mapNotNull { (it as? EditRow.Hidden)?.card }
        assertTrue(BatteryCard in listed && LastSyncCard in listed)
        assertFalse(SolarCard in listed || RoomClimateCard in listed)
        assertTrue(CardRegistry.byId("battery") === BatteryCard)
    }

    @Test
    fun devicesBringLiveBatteryAndSyncCards() {
        val watch = RecordedLoader.fromDevice(device("AA:01"))
        assertTrue(DataKind.LIVE in watch && DataKind.BATTERY in watch && DataKind.DEVICE_SYNC in watch)
        val scale = RecordedLoader.fromDevice(device("AA:03", DeviceKind.WEIGHT_SCALE))
        assertFalse(DataKind.LIVE in scale)
        assertTrue(DataKind.DEVICE_SYNC in scale)
        assertTrue(DataKind.LIVE in RecordedLoader.fromDevice(device("AA:02", DeviceKind.HEART_RATE_SENSOR)))
    }

    // ---- Live ----

    @Test
    fun liveReadingsWinWhileFreshAndAStrapIsNamed() {
        val data = LiveCard.Data(61, 1_000, 5_000, mapOf(1L to "Watch", 2L to "Strap"))
        val stored = LiveCard.shown(data, null, 2_000)
        assertEquals(61, stored.bpm)
        assertFalse(stored.live)
        val fromWatch = LiveCard.shown(data, LiveReading(1, 88, null, 5_120, 10_000, 1_995), 2_000)
        assertEquals(88, fromWatch.bpm)
        assertEquals(5_120, fromWatch.steps)
        assertTrue(fromWatch.live)
        assertEquals("Watch", fromWatch.source)
        assertFalse(fromWatch.strap)
        val fromStrap = LiveCard.shown(data, LiveReading(1, 142, null, 5_200, null, 1_998, hrSourceId = 2), 2_000)
        assertEquals("Strap", fromStrap.source)
        assertTrue(fromStrap.strap)
        // A reading older than the live window is ignored.
        val stale = LiveCard.shown(data, LiveReading(1, 99, null, 6_000, null, 1_900), 2_000)
        assertEquals(61, stale.bpm)
        assertFalse(stale.live)
    }

    // ---- Weather ----

    @Test
    fun weatherUsesTheStoredForecast() = runBlocking {
        val db = TestDb.fresh(ctx)
        val clock = FakeClock(1_795_435_200L, zone)
        val today = clock.today()
        val env = CardEnv(db, clock, today, Units.IMPERIAL)
        val none = WeatherCard.load(env)
        assertNull(none.forecast)
        assertNull(WeatherCard.small(ctx, none))
        val w = WatchCommand.Weather(
            WeatherNow(System.currentTimeMillis() / 1000 - 600, 200, null, WeatherCondition.RAIN, null, null, null, null),
            listOf(WeatherDay(today.plusDays(-1), 0, 10, WeatherCondition.CLEAR, null), WeatherDay(today, 100, 250, WeatherCondition.RAIN, 80)),
        )
        db.settings.set(RelaySettings.WEATHER_LAST, WeatherStore.encode(w))
        db.settings.setBool(SettingKeys.WEATHER_ENABLED, true)
        val data = WeatherCard.load(env)
        assertEquals(w, data.forecast)
        assertTrue(data.enabled)
        val face = WeatherCard.small(ctx, data)!!
        assertEquals("68", face.value) // 20 °C
        assertEquals("°F", face.unit)
        assertEquals("Rain", face.label)
        assertEquals("High 77° · Low 50°", face.note)
        assertEquals(today, WeatherCard.todayOf(w, today)?.day)
        assertEquals(-18, WeatherCard.degrees(-178, Units.METRIC))
        assertEquals(0, WeatherCard.degrees(-178, Units.IMPERIAL))
    }

    // ---- Food groups ----

    @Test
    fun everyFoodCategoryHasItsGroup() {
        // The 42 categories of foods.db v2 (tools/fooddb/categories.csv): ids 101–406 map, the rest count as none.
        val expected = mapOf(
            101 to FoodGroup.FRUITS, 102 to FoodGroup.VEGETABLES, 103 to FoodGroup.VEGETABLES, 104 to FoodGroup.VEGETABLES,
            201 to FoodGroup.GRAINS, 202 to FoodGroup.GRAINS, 203 to FoodGroup.GRAINS,
            301 to FoodGroup.DAIRY, 302 to FoodGroup.DAIRY, 303 to FoodGroup.DAIRY, 304 to FoodGroup.PROTEIN, 305 to FoodGroup.DAIRY,
            401 to FoodGroup.PROTEIN, 402 to FoodGroup.PROTEIN, 403 to FoodGroup.PROTEIN, 404 to FoodGroup.PROTEIN,
            405 to FoodGroup.PROTEIN, 406 to FoodGroup.PROTEIN,
        )
        val none = listOf(501, 502, 503, 504, 601, 602, 603, 604, 605, 606, 701, 702, 703, 704, 705, 801, 802, 803, 804, 805, 806, 807, 901, 999)
        assertEquals(42, expected.size + none.size)
        for ((id, g) in expected) assertEquals("category $id", g, FoodGroupHook.groupOf(id))
        for (id in none) assertNull("category $id", FoodGroupHook.groupOf(id))
        assertNull(FoodGroupHook.groupOf(null as Int?))
        assertEquals(expected, FoodGroupHook.CATEGORY_GROUPS)
        // When the category list is in the repository, it has exactly these ids.
        val csv = listOf(File("../tools/fooddb/categories.csv"), File("tools/fooddb/categories.csv")).firstOrNull { it.exists() }
        if (csv != null) {
            val ids = csv.readLines().drop(1).filter { it.isNotBlank() }.map { it.substringBefore(',').toInt() }.toSet()
            assertEquals(expected.keys + none, ids)
        }
    }

    @Test
    fun foodGroupsCountTheDaysLoggedFoods() = runBlocking {
        val db = TestDb.fresh(ctx)
        val clock = FakeClock(1_795_435_200L, zone)
        fun entry(name: String, category: Int?) = DiaryEntry(0, day, 1, 1_795_435_200L, null, name, null, 100, "1", null, Nutrients.UNKNOWN.copy(kcalX10 = 500), null, category)
        db.diary.addAll(listOf(entry("Apple", 101), entry("Banana", 101), entry("Oats", 203), entry("Yogurt", 302), entry("Chips", 701), entry("Old line", null)))
        val data = FoodGroupsCard.load(CardEnv(db, clock, day, Units.METRIC))
        assertTrue(data.logged)
        assertEquals(mapOf(FoodGroup.FRUITS to 2, FoodGroup.VEGETABLES to 0, FoodGroup.GRAINS to 1, FoodGroup.PROTEIN to 0, FoodGroup.DAIRY to 1), data.portions)
        // Fruits (2 of 2) is the only group met.
        assertEquals("1", FoodGroupsCard.small(ctx, data)?.value)
    }
}
