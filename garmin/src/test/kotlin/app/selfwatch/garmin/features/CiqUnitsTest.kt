/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.TestPrg
import app.selfwatch.garmin.session.Features
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.model.device.Feature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchAppKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

/** PRG header reader, JSON ↔ Monkey C values, and the new feature gating. */
class CiqUnitsTest {
    private val uuid = UUID.fromString("5a1e0b2c-3d4e-4f60-8a7b-9c0d1e2f3a4b")

    // ---- PrgFile ----

    @Test
    fun readsUuidAndTypeOfTheFirstEntryPoint() {
        for ((type, kind) in listOf(0 to WatchAppKind.WATCH_FACE, 1 to WatchAppKind.APP, 2 to WatchAppKind.DATA_FIELD, 3 to WatchAppKind.WIDGET, 5 to WatchAppKind.OTHER)) {
            val info = (PrgFile.parse(TestPrg.build(uuid, type)) as Outcome.Ok).value
            assertEquals(uuid, info.appUuid)
            assertEquals(type, info.appType)
            assertEquals(kind, info.kind)
        }
        // Entry points before the code section, and several entries: the first wins.
        val p = TestPrg.build(uuid, 1, entries = 3, extraSectionsFirst = false)
        assertEquals(uuid, (PrgFile.parse(p) as Outcome.Ok).value.appUuid)
        assertEquals(GarminCapability.CONNECTIQ_WATCH_FACE_DOWNLOAD, (PrgFile.parse(TestPrg.build(uuid, 0)) as Outcome.Ok).value.requiredCapability)
        assertEquals(GarminCapability.CONNECTIQ_WATCH_APP_DOWNLOAD, (PrgFile.parse(TestPrg.build(uuid, 6)) as Outcome.Ok).value.requiredCapability)
    }

    private fun bad(b: ByteArray) {
        val r = PrgFile.parse(b)
        assertTrue("expected BadInput, got $r", r is Outcome.Fail && r.error is SwError.BadInput)
    }

    @Test
    fun refusesWhatIsNotAPrg() {
        bad(ByteArray(0))
        bad(byteArrayOf(0xd0.toByte(), 0, 0xd0.toByte()))
        bad("PK\u0003\u0004 a zip file".toByteArray())
        val good = TestPrg.build(uuid, 1)
        bad(good.copyOf(30)) // a section runs past the end
        bad(TestPrg.build(uuid, 1, entries = 0))
        // A file that ends before any entry point.
        val headOnly = good.copyOf(8 + 12 + 8 + 900)
        bad(headOnly)
        bad(ByteArray(PrgFile.MAX_SIZE + 1).also { it[0] = 0xd0.toByte(); it[2] = 0xd0.toByte() })
    }

    @Test
    fun prgFuzzNeverThrows() {
        val rnd = Random(11)
        val good = TestPrg.build(uuid, 1, codeBytes = 100)
        repeat(20_000) {
            val b = when (rnd.nextInt(3)) {
                0 -> ByteArray(rnd.nextInt(0, 200)) { rnd.nextInt(256).toByte() }
                1 -> good.copyOf(rnd.nextInt(good.size + 1))
                else -> good.copyOf().also { g -> repeat(rnd.nextInt(1, 6)) { g[rnd.nextInt(g.size)] = rnd.nextInt(256).toByte() } }
            }
            PrgFile.parse(b)
        }
    }

    // ---- CiqJson ----

    @Test
    fun jsonRoundTrip() {
        val v = CiqValue.Dict(
            linkedMapOf(
                CiqValue.Str("s") to CiqValue.Str("quote \" back \\ nl \n tab \t ctl \u0001 ✓"),
                CiqValue.Str("n") to CiqValue.Num(-42),
                CiqValue.Str("big") to CiqValue.Num(Long.MAX_VALUE),
                CiqValue.Str("d") to CiqValue.Dbl(2.5),
                CiqValue.Str("b") to CiqValue.Bool(false),
                CiqValue.Str("z") to CiqValue.Null,
                CiqValue.Str("a") to CiqValue.Arr(listOf(CiqValue.Num(1), CiqValue.Arr(emptyList()), CiqValue.Dict(emptyMap()))),
            ),
        )
        assertEquals(Outcome.Ok(v), CiqJson.parse(CiqJson.write(v)))
        assertEquals("""{"1":true}""", CiqJson.write(CiqValue.Dict(mapOf(CiqValue.Num(1) to CiqValue.Bool(true)))))
        assertEquals("null", CiqJson.write(CiqValue.Dbl(Double.NaN)))
    }

    @Test
    fun jsonParsing() {
        assertEquals(Outcome.Ok(CiqValue.Null), CiqJson.parse("  "))
        assertEquals(Outcome.Ok(CiqValue.Dbl(1e3)), CiqJson.parse("1e3"))
        assertEquals(Outcome.Ok(CiqValue.Dbl(1e20)), CiqJson.parse("100000000000000000000"))
        assertEquals(Outcome.Ok(CiqValue.Str("é/")), CiqJson.parse("\"\\u00e9\\/\""))
        for (bad in listOf("{", "[1,", "{\"a\" 1}", "tru", "\"open", "1 2", "-", "{\"a\":}", "\"\\x\"", "[1]]", "01.2.3")) {
            val r = CiqJson.parse(bad)
            assertTrue("$bad → $r", r is Outcome.Fail)
        }
        assertTrue(CiqJson.parse("[".repeat(CiqJson.MAX_DEPTH + 2) + "]".repeat(CiqJson.MAX_DEPTH + 2)) is Outcome.Fail)
        assertTrue(CiqJson.parse("[".repeat(10) + "]".repeat(10)) is Outcome.Ok)
    }

    @Test
    fun jsonFuzzNeverThrows() {
        val rnd = Random(12)
        val alphabet = "{}[]\",:.-+eE0123456789 truefalsnul\\u"
        repeat(30_000) {
            val s = String(CharArray(rnd.nextInt(0, 40)) { alphabet[rnd.nextInt(alphabet.length)] })
            CiqJson.parse(s)
        }
    }

    // ---- feature gating ----

    @Test
    fun newFeaturesFollowTheWatchFlags() {
        val ciq = setOf(
            GarminCapability.CONNECTIQ_WATCH_APP_DOWNLOAD, GarminCapability.CONNECTIQ_APP_MANAGEMENT, GarminCapability.CONNECTIQ_SETTINGS,
            GarminCapability.CONNECTIQ_HTTP, GarminCapability.LAUNCH_REMOTE_CIQ_APP_SUPPORT,
        )
        val f = Features.resolve(ciq, emptyList(), emptyMap(), appChannel = true)
        for (x in listOf(Feature.WATCH_APP_INSTALL, Feature.WATCH_APP_MANAGE, Feature.WATCH_APP_SETTINGS, Feature.WATCH_APP_WEB, Feature.WATCH_APP_LAUNCH, Feature.ALARMS)) {
            assertTrue("$x", x in f)
        }
        val none = Features.resolve(setOf(GarminCapability.REALTIME_SETTINGS), emptyList(), emptyMap(), appChannel = false)
        for (x in listOf(Feature.WATCH_APP_INSTALL, Feature.WATCH_APP_SETTINGS, Feature.WATCH_APP_WEB, Feature.WATCH_APP_LAUNCH, Feature.ALARMS)) {
            assertFalse("$x", x in none)
        }
        assertTrue(Feature.WATCH_APP_WEB in Features.resolve(setOf(GarminCapability.HTTP_RAW_RESOURCE_REQUEST), emptyList(), emptyMap(), false))
        assertFalse(Feature.GPS_ASSIST in Features.IMPLEMENTED)
        for (c in listOf(GarminCapability.CONNECTIQ_HTTP, GarminCapability.CONNECTIQ_SETTINGS, GarminCapability.CONNECTIQ_APP_MANAGEMENT, GarminCapability.LAUNCH_REMOTE_CIQ_APP_SUPPORT)) {
            assertTrue("$c", c in GarminCapability.SELF_WATCH)
        }
        assertFalse(GarminCapability.GPS_EPHEMERIS_DOWNLOAD in GarminCapability.SELF_WATCH)
    }
}
