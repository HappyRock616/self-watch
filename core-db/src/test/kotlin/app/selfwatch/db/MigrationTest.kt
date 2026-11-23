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
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Goal
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.time.Day
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
 * staged in test resources `db/V{n}.sql` (today: a dummy V2, never shipped) so the harness is proven
 * before a real V2 exists. When a real V2 lands, move it to assets, bump VERSION and add its checks.
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
        assertTrue("a staged V2 must exist for the harness to test upgrades", latest >= 2)
    }

    @Test
    fun everyPastVersionUpgradesToLatest() = runBlocking {
        for (from in 1 until latest) {
            app.deleteDatabase(name)
            // 1. Build a database at version `from` and fill it through the DAOs (V1 DAOs work on V1).
            open(from).use { db ->
                assertEquals(from, db.readableDatabase.version)
                db.water.add(Day(20261123), 100, 250)
                val fast = db.fasts.start(1000, 960)
                db.fasts.end(fast, 5000)
                db.goals.set(Goal(GoalKind.STEPS, 9000, Day(20260101)))
                db.diary.add(DbTest.entry(Day(20261123), name = "Porridge"))
                db.foods.insert(
                    CustomFood(
                        Food(
                            FoodRef(FoodOrigin.MY_FOOD, 0), "Crème caramel", null, 0,
                            Basis.PER_100G, Nutrients.ZERO, emptyList(), null, "", null,
                        ),
                        CustomFoodOrigin.TYPED, null, false, 1, 1, null,
                    ),
                )
                db.settings.set(SettingKeys.THEME, "light")
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
                checkDummyV2(db)
            }
        }
    }

    /** Checks specific to the staged dummy V2. */
    private fun checkDummyV2(db: SelfWatchDb) {
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
