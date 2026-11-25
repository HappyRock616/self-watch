/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import android.app.Application
import app.selfwatch.db.dao.CustomFood
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.TimeRange
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Migration harness. For every past schema version N it builds a database at N, fills it, upgrades
 * it to the newest version and checks that the data survived and the new schema is in place.
 *
 * Shipped scripts come from `assets/db/V{n}.sql`. Scripts newer than [SelfWatchDb.VERSION] may be
 * staged in test resources `db/V{n}.sql` (today: a dummy V5, never shipped) so the harness always
 * runs one upgrade past the shipped schema. When a real V5 lands, rename the dummy to V6.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private lateinit var app: Application
    private val name = "migration-test.db"

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        app.deleteDatabase(name)
    }

    /** V{n}.sql from assets if shipped, else from test resources. */
    private fun script(v: Int): String {
        val asset = File("src/main/assets/db/V$v.sql")
        if (asset.exists()) return asset.readText()
        val res = javaClass.classLoader?.getResource("db/V$v.sql") ?: error("no script for V$v")
        return res.readText()
    }

    /** The newest version that has a script (shipped or staged). */
    private val latest: Int by lazy {
        var v = 1
        while (File("src/main/assets/db/V${v + 1}.sql").exists() || javaClass.classLoader?.getResource("db/V${v + 1}.sql") != null) v++
        v
    }

    private fun open(version: Int): SelfWatchDb = SelfWatchDb.create(app, name, version, ::script, DbChanges(app))

    private fun schema(db: SelfWatchDb): Map<String, String> =
        db.readableDatabase.rawQuery("SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY name", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    @Test
    fun harnessFindsStagedVersions() {
        assertTrue("a staged V5 must exist for the harness to test upgrades", latest >= 5)
    }

    @Test
    fun everyPastVersionUpgradesToLatest() = runBlocking {
        for (from in 1 until latest) {
            app.deleteDatabase(name)
            // 1. Build a database at version `from` and fill it (through the DAOs where their tables are
            // unchanged since `from`; the food tables gained columns in V2, so V1 rows go in as SQL).
            open(from).use { db ->
                assertEquals(from, db.readableDatabase.version)
                db.water.add(Day(20261123), 100, 250)
                val fast = db.fasts.start(1000, 960)
                db.fasts.end(fast, 5000)
                db.goals.set(Goal(GoalKind.STEPS, 9000, Day(20260101)))
                // diary_entry gained category_id in V3: older diary rows go in as SQL.
                if (from < 3) {
                    db.writableDatabase.execSQL(
                        "INSERT INTO diary_entry (day, meal_slot_id, t_sec, name, amount_x100, serving_label, grams_x10, kcal_x10, protein_x10) " +
                            "VALUES (20261123, 1, 1795435200, 'Porridge', 100, '1 medium', 1820, 520, 3)",
                    )
                } else {
                    db.diary.add(DbTest.entry(Day(20261123), name = "Porridge"))
                }
                if (from < 2) {
                    val w = db.writableDatabase
                    w.execSQL(
                        "INSERT INTO custom_food (name, basis, kcal_x10, origin, created_sec, updated_sec) VALUES ('Crème caramel', 0, 0, 1, 1, 1)",
                    )
                    w.execSQL("INSERT INTO my_food_fts (docid, name, brand) VALUES (1099511627777, 'Crème caramel', NULL)")
                } else {
                    db.foods.insert(
                        CustomFood(
                            Food(
                                FoodRef(FoodOrigin.MY_FOOD, 0), "Crème caramel", null, 0,
                                Basis.PER_100G, Nutrients.UNKNOWN.copy(kcalX10 = 0), emptyList(), null, "", null,
                            ),
                            CustomFoodOrigin.TYPED, null, false, 1, 1, null,
                        ),
                    )
                }
                db.settings.set(SettingKeys.THEME, "light")
                // The workout table is unchanged since V1; the DAO reads workout_trim (V4), so raw SQL.
                db.writableDatabase.execSQL(
                    "INSERT INTO workout (device_id, sport, start_sec, end_sec, duration_sec, distance_m, kcal, has_track, fit_path, source) " +
                        "VALUES (99, 1, 1795435200, 1795437000, 1800, 5000, 300, 1, '/f/run.fit', 1)",
                )
            }
            // 2. Upgrade and check.
            open(latest).use { db ->
                val d = db.readableDatabase
                assertEquals(latest, d.version)
                assertEquals(250, db.water.dayTotal(Day(20261123)))
                assertEquals(9000, db.goals.goalOn(GoalKind.STEPS, Day(20261123))!!.value)
                assertEquals(listOf("Porridge"), db.diary.day(Day(20261123)).map { it.name })
                assertEquals(1, db.foods.searchMine("creme").size)
                assertEquals("light", db.settings.get(SettingKeys.THEME))
                assertEquals(1000L to 5000L, db.fasts.list(1).single().let { it.startSec to it.endSec })
                checkV2(db)
                checkV3(db)
                checkV4(db)
                checkDummyV5(db)
            }
        }
    }

    /** Schema 2: food database v2 nutrients stay unknown on old rows; new rows keep them. */
    private suspend fun checkV2(db: SelfWatchDb) {
        val old = db.diary.day(Day(20261123)).single().nutrients
        assertEquals(null, old.potassiumMg)
        assertEquals(null, old.vitdX10)
        val food = db.foods.list().single().food
        assertEquals(null, food.per.transfatX10)
        assertEquals(0, food.dietInfo.diet)
        db.diary.add(DbTest.entry(Day(20261124), name = "Banana").let { it.copy(nutrients = it.nutrients.copy(potassiumMg = 420, ironX10 = 3)) })
        val banana = db.diary.day(Day(20261124)).single().nutrients
        assertEquals(420, banana.potassiumMg)
        assertEquals(3, banana.ironX10)
        assertEquals(420, db.diary.dayTotals(Day(20261124)).potassiumMg)
    }

    /**
     * Schema 3: old diary rows have no category; new rows keep theirs (also when a meal is copied); the
     * battery history stores, reads back and goes with the device's data.
     */
    private suspend fun checkV3(db: SelfWatchDb) {
        assertEquals(null, db.diary.day(Day(20261123)).single().categoryId)
        db.diary.add(DbTest.entry(Day(20261125), name = "Apple").copy(categoryId = 101))
        assertEquals(101, db.diary.day(Day(20261125)).single().categoryId)
        db.diary.copyMeal(Day(20261125), 1, Day(20261126), 1_795_600_000L)
        assertEquals(101, db.diary.day(Day(20261126)).single().categoryId)
        val id = db.devices.upsert(
            DeviceInfo(
                0, DeviceKind.GARMIN_WATCH, "AA:BB:CC:DD:EE:01", "Watch", "vivoactive 5",
                null, null, null, SupportTier.UNVERIFIED, emptySet(), null,
            ),
        )
        db.battery.add(id, 1_000L, 80)
        db.battery.add(id, 2_000L, 140)
        assertEquals(listOf(80, 100), db.battery.readings(TimeRange(0, 3_000)).map { it.pct })
        assertEquals(100, db.battery.latest(id)?.pct)
        db.devices.deleteData(id, keepWorkouts = false)
        assertEquals(null, db.battery.latest(id))
    }

    /**
     * Schema 4: workouts from before have no trim; a trim stores the window and the originals, undo puts
     * them back, and deleting the workout removes its trim.
     */
    private suspend fun checkV4(db: SelfWatchDb) {
        val w = db.workouts.list(10).single { it.fitPath == "/f/run.fit" }
        assertEquals(null, db.workouts.trim(w.id))
        val cut = w.copy(endSec = w.startSec + 1200, durationSec = 1200, distanceM = 3300, kcal = 200)
        assertTrue(db.workouts.saveTrim(cut, w.startSec, w.startSec + 1200))
        assertEquals(3300, db.workouts.get(w.id)!!.distanceM)
        assertEquals(w, db.workouts.trim(w.id)!!.original)
        assertTrue(db.workouts.undoTrim(w.id))
        assertEquals(w, db.workouts.get(w.id))
        assertTrue(db.workouts.saveTrim(cut, w.startSec, w.startSec + 1200))
        assertTrue(db.workouts.delete(w.id))
        assertEquals(0, db.readableDatabase.rawQuery("SELECT COUNT(*) FROM workout_trim", null).use { it.moveToFirst(); it.getInt(0) })
    }

    /** Checks specific to the staged dummy V5. */
    private fun checkDummyV5(db: SelfWatchDb) {
        val d = db.writableDatabase
        assertEquals(3, d.rawQuery("SELECT source FROM water", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals("", d.rawQuery("SELECT label FROM fast", null).use { it.moveToFirst(); it.getString(0) })
        d.execSQL("INSERT INTO note (day, text) VALUES (20261123, '  hello  ')")
        assertEquals("hello", d.rawQuery("SELECT text FROM note", null).use { it.moveToFirst(); it.getString(0) })
        assertEquals("Snack", d.rawQuery("SELECT name FROM meal_slot WHERE id = 4", null).use { it.moveToFirst(); it.getString(0) })
        assertTrue(schema(db).containsKey("diary_t"))
        assertEquals("ok", d.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst(); it.getString(0) })
        assertEquals(0, d.rawQuery("PRAGMA foreign_key_check", null).use { it.count })
    }

    @Test
    fun freshInstallAtLatestMatchesUpgradedSchema() {
        // A fresh install only gets onCreate, which must replay every script up to the target version.
        open(1).use { it.readableDatabase }
        val upgraded = open(latest).use { schema(it) }
        app.deleteDatabase(name)
        val fresh = open(latest).use { db ->
            assertEquals(latest, db.readableDatabase.version)
            schema(db)
        }
        assertEquals(upgraded, fresh)
    }

    @Test
    fun failedUpgradeRollsBack() {
        open(1).use { it.writableDatabase.execSQL("INSERT INTO water (day, t_sec, ml) VALUES (1, 1, 5)") }
        val broken: (Int) -> String = { v -> if (v == 1) script(1) else "ALTER TABLE water ADD COLUMN x INTEGER; CREATE TABLE water (oops);" }
        val db = SelfWatchDb.create(app, name, 2, broken, DbChanges(app))
        try {
            db.writableDatabase
            error("upgrade should fail")
        } catch (e: android.database.sqlite.SQLiteException) {
            // expected
        } finally {
            db.close()
        }
        open(1).use { ok ->
            assertEquals(1, ok.readableDatabase.version)
            val cols = ok.readableDatabase.rawQuery("PRAGMA table_info(water)", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(1)) }
            }
            assertTrue("x" !in cols)
        }
    }
}
