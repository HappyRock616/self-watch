/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import android.content.Intent
import android.database.sqlite.SQLiteDatabaseLockedException
import android.os.Looper
import app.selfwatch.model.food.Profile
import app.selfwatch.model.food.Sex
import app.selfwatch.model.food.UnitSystem
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.time.Day
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.robolectric.Shadows.shadowOf

class SettingsAndChangesTest : DbTest() {

    @Test
    fun defaultsComeFromCode() = test {
        val s = db.settings
        assertEquals("phone", s.get(SettingKeys.UNITS))
        assertEquals("dark", s.get(SettingKeys.THEME))
        assertEquals("#FF8A1F", s.get(SettingKeys.ACCENT))
        assertEquals("always", s.get(SettingKeys.CONNECTION_MODE))
        assertEquals(4, s.getInt(SettingKeys.CONNECTION_SYNC_ONLY_HOURS))
        assertTrue(s.getBool(SettingKeys.FOOD_ENABLED))
        assertTrue(s.getBool(SettingKeys.NOTIFY_ENABLED))
        assertEquals(emptyList<String>(), s.getList(SettingKeys.NOTIFY_APPS_DENIED))
        assertTrue(s.getBool(SettingKeys.CALLS_ENABLED))
        assertFalse(s.getBool(SettingKeys.CALLS_SHOW_NUMBER, fallback = true))
        assertFalse(s.getBool(SettingKeys.CALLS_SHOW_NAME, fallback = true))
        assertTrue("weather forwarding is on by default", s.getBool(SettingKeys.WEATHER_ENABLED))
        assertEquals("broadcast", s.get(SettingKeys.WEATHER_SOURCE))
        assertFalse(s.getBool(SettingKeys.GARMIN_FAKE_AUTH, fallback = true))
        assertFalse("per-watch Garmin sign-in answer is off by default", s.getBool(SettingKeys.deviceFakeAuth(1)))
        assertEquals("devices.1.fake_auth", SettingKeys.deviceFakeAuth(1))
        assertEquals("devices.1.sign_in_asked", SettingKeys.deviceSignInAsked(1))
        assertFalse(s.getBool(SettingKeys.BACKUP_AUTO, fallback = true))
        assertEquals(7, s.getInt(SettingKeys.BACKUP_EVERY_DAYS))
        assertTrue(s.getBool(SettingKeys.BACKUP_ENCRYPT))
        assertFalse(s.getBool(SettingKeys.EXPORT_AUTO_GPX, fallback = true))
        assertFalse(s.getBool(SettingKeys.ONBOARDING_DONE, fallback = true))
        assertNull(s.get(SettingKeys.PROFILE_HEIGHT_CM))
        assertNull(s.get(SettingKeys.BACKUP_FOLDER_URI))
        assertNull(s.getInt(SettingKeys.FOOD_DB_INSTALLED_VERSION))
        for (f in NetFeature.entries) assertEquals(f.defaultOn, s.netAllowed(f))
        assertEquals(emptyMap<String, String>(), s.all())
    }

    @Test
    fun setGetAndReset() = test {
        val s = db.settings
        s.setInt(SettingKeys.BACKUP_EVERY_DAYS, 3)
        s.setBool(SettingKeys.ONBOARDING_DONE, true)
        s.setLong(SettingKeys.FOOD_DB_INSTALLED_VERSION, 20261101L)
        s.setList(SettingKeys.NOTIFY_APPS_DENIED, listOf("a.b", " ", "c.d "))
        s.setNetAllowed(NetFeature.GPS_ASSIST, false)
        assertEquals(3, s.getInt(SettingKeys.BACKUP_EVERY_DAYS))
        assertTrue(s.getBool(SettingKeys.ONBOARDING_DONE))
        assertEquals(20261101L, s.getLong(SettingKeys.FOOD_DB_INSTALLED_VERSION))
        assertEquals(listOf("a.b", "c.d"), s.getList(SettingKeys.NOTIFY_APPS_DENIED))
        assertFalse(s.netAllowed(NetFeature.GPS_ASSIST))
        assertEquals("false", s.all()["net.gps_assist"])
        s.set(SettingKeys.BACKUP_EVERY_DAYS, null)
        assertEquals(7, s.getInt(SettingKeys.BACKUP_EVERY_DAYS))
        s.setAll(mapOf(SettingKeys.THEME to "light", SettingKeys.ACCENT to null))
        assertEquals("light", s.get(SettingKeys.THEME))
        assertEquals(5, s.all().size)
    }

    @Test
    fun profileRoundTrip() = test {
        val s = db.settings
        assertEquals(Profile(null, null, Sex.UNSPECIFIED, UnitSystem.FOLLOW_PHONE), s.profile())
        val p = Profile(180, 1980, Sex.FEMALE, UnitSystem.IMPERIAL)
        s.setProfile(p)
        assertEquals(p, s.profile())
        s.setProfile(Profile(null, 1990, Sex.UNSPECIFIED, UnitSystem.METRIC))
        assertEquals(Profile(null, 1990, Sex.UNSPECIFIED, UnitSystem.METRIC), s.profile())
    }

    @Test
    fun observeEmitsNowAndOnChange() = test {
        val s = db.settings
        val got = kotlinx.coroutines.coroutineScope {
            val job = async(Dispatchers.IO) { withTimeout(5000) { s.observe(SettingKeys.THEME).take(3).toList() } }
            delay(300)
            s.set(SettingKeys.ACCENT, "#000000") // other key: same value, dropped
            s.set(SettingKeys.THEME, "light")
            delay(200)
            s.set(SettingKeys.THEME, null)
            job.await()
        }
        assertEquals(listOf("dark", "light", "dark"), got)
    }

    @Test
    fun changesFlowFiltersTables() = test {
        val got = kotlinx.coroutines.coroutineScope {
            val job = async(Dispatchers.IO) { withTimeout(5000) { changes.observe(setOf(Table.DIARY), emitInitial = false).first() } }
            delay(200)
            changes.notify(setOf(Table.WATER))
            changes.notify(setOf(Table.DIARY, Table.WATER))
            job.await()
        }
        assertEquals(Unit, got)
    }

    @Test
    fun broadcastFromOtherProcessReachesFlow() = test {
        val received = kotlinx.coroutines.coroutineScope {
            val job = async(Dispatchers.IO) { withTimeout(5000) { changes.changes.first() } }
            delay(200)
            val intent = Intent(DbChanges.ACTION).setPackage(app.packageName)
                .putExtra(DbChanges.EXTRA_TABLES, arrayOf("water", "nonsense"))
                .putExtra(DbChanges.EXTRA_PID, -42)
            app.sendBroadcast(intent, DbChanges.permissionFor(app.packageName))
            shadowOf(Looper.getMainLooper()).idle()
            job.await()
        }
        assertEquals(setOf(Table.WATER), received)
    }

    @Test
    fun ownBroadcastIsIgnored() = test {
        val all = kotlinx.coroutines.coroutineScope {
            val job = async(Dispatchers.IO) { withTimeout(3000) { changes.changes.take(2).toList() } }
            delay(200)
            changes.notify(setOf(Table.FAST)) // flow once, broadcast ignored by this process
            shadowOf(Looper.getMainLooper()).idle()
            delay(200)
            changes.notify(setOf(Table.GOAL))
            job.await()
        }
        assertEquals(listOf(setOf(Table.FAST), setOf(Table.GOAL)), all)
    }

    @Test
    fun daoWritesNotify() = test {
        val got = kotlinx.coroutines.coroutineScope {
            val job = async(Dispatchers.IO) { withTimeout(5000) { changes.changes.first() } }
            delay(200)
            db.water.add(Day(20261123), 1L, 250)
            job.await()
        }
        assertEquals(setOf(Table.WATER), got)
    }

    @Test
    fun busyRetryGivesUpAfterThreeRetries() = test {
        var calls = 0
        try {
            busyRetry<Unit> {
                calls++
                throw SQLiteDatabaseLockedException("busy")
            }
            fail("expected DbBusyException")
        } catch (e: DbBusyException) {
            assertEquals("database busy", e.error.detail)
        }
        assertEquals(1 + BUSY_RETRIES, calls)
    }

    @Test
    fun busyRetryRecovers() = test {
        var calls = 0
        val v = busyRetry {
            calls++
            if (calls < 3) throw SQLiteDatabaseLockedException("busy")
            7
        }
        assertEquals(7, v)
        assertEquals(3, calls)
    }
}
