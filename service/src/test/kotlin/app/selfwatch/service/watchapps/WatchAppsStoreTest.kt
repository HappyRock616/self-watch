/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.watchapps

import android.content.Intent
import app.selfwatch.model.device.Feature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchApp
import app.selfwatch.model.watch.WatchAppKind
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.service.ServiceDbTest
import app.selfwatch.service.SyncCommands
import app.selfwatch.service.link.LinkValues
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** B26: watch_app sync, settings backups, sideloaded files, the settings form and the watch-app commands. */
class WatchAppsStoreTest : ServiceDbTest() {
    private val jg5k = UUID.fromString("9b0a09cf-1d2a-4f55-9e2b-71a4b3c00001")
    private val tcs = UUID.fromString("9b0a09cf-1d2a-4f55-9e2b-71a4b3c00002")
    private var deviceId = 0L
    private lateinit var files: SideloadStore
    private lateinit var store: WatchAppsStore

    private val definition = """{"settings":[{"key":"theme","valueType":"number","defaultValue":2,"configTitle":"ThemeTitle",
        |"configType":"list","configOptions":[{"display":"Dark","value":0},{"display":"Light","value":1},{"display":"Orange","value":2}]},
        |{"key":"dim","valueType":"boolean","defaultValue":false,"configTitle":"DimTitle","configType":"boolean"}],
        |"languages":{"eng":{"ThemeTitle":"Colour theme","Dark":"Dark","Light":"Light","Orange":"Orange"}}}""".trimMargin()

    @Before
    fun setUp() {
        deviceId = addDevice()
        val root = File(app.filesDir, "watchapps-test")
        root.deleteRecursively()
        files = SideloadStore(root)
        store = WatchAppsStore(db, clock, files)
    }

    @Test
    fun installedAppsKeepTheTableInStep() = runBlocking {
        store.sync(deviceId, WatchEvent.InstalledApps(listOf(WatchApp(jg5k, "Just Go 5K", 3, WatchAppKind.APP), WatchApp(tcs, "TCS", null, WatchAppKind.WIDGET))))
        val rows = db.watchApps.forDevice(deviceId)
        assertEquals(listOf("Just Go 5K", "TCS"), rows.map { it.name })
        assertEquals(1, rows[0].kind)
        assertEquals(2, rows[1].kind)
        assertEquals(WatchAppKind.WIDGET, WatchAppsStore.kindOf(rows[1].kind))
        // TCS was deleted on the watch: kept as not installed.
        clock.advance(60)
        store.sync(deviceId, WatchEvent.InstalledApps(listOf(WatchApp(jg5k, "Just Go 5K", 4, WatchAppKind.APP))))
        assertEquals(listOf(jg5k), db.watchApps.forDevice(deviceId).map { it.appUuid })
        val gone = db.watchApps.get(deviceId, tcs)!!
        assertFalse(gone.installed)
        assertEquals(clock.nowSec(), gone.seenSec)
        assertEquals(4, db.watchApps.get(deviceId, jg5k)?.version)
    }

    @Test
    fun settingsReadsAndWritesAreBackedUp() = runBlocking {
        files.save(jg5k, byteArrayOf(1, 2, 3), definition)
        val link = object : app.selfwatch.service.device.WatchLink {
            override val deviceId: Long get() = this@WatchAppsStoreTest.deviceId
            override suspend fun send(cmd: WatchCommand): Outcome<Unit> = Outcome.Ok(Unit)
        }
        store.onAppSettings(link, WatchEvent.AppSettings(jg5k, mapOf("theme" to CiqValue.Num(1), "dim" to CiqValue.Bool(true))))
        val saved = db.watchApps.latestSettings(jg5k)!!
        assertEquals("""{"theme":1,"dim":true}""", saved.valuesJson)
        assertEquals(definition, saved.definitionJson)
        clock.advance(10)
        store.backupWrite(jg5k, mapOf("theme" to CiqValue.Num(0)))
        assertEquals(mapOf("theme" to CiqValue.Num(0)), store.latestValues(jg5k))
        assertEquals(2, db.watchApps.settingsHistory(jg5k).size)
        // Copies are pruned.
        val few = WatchAppsStore(db, clock, files, keepCopies = 1)
        clock.advance(10)
        few.backupWrite(jg5k, mapOf("theme" to CiqValue.Num(2)))
        assertEquals(1, db.watchApps.settingsHistory(jg5k).size)
    }

    @Test
    fun formUsesTheSideloadedDefinitionElseValues() = runBlocking {
        files.save(jg5k, byteArrayOf(1), definition)
        val f = store.form(jg5k)
        assertTrue(f.fromDefinition)
        assertEquals("Colour theme", f.field("theme")!!.title)
        assertEquals("dim", f.field("dim")!!.title)
        // An app with only saved values: raw keys.
        store.backupWrite(tcs, mapOf("wind" to CiqValue.Num(3)))
        val raw = store.form(tcs)
        assertFalse(raw.fromDefinition)
        assertEquals(listOf("wind"), raw.fields.map { it.title })
        assertTrue(store.form(UUID.randomUUID()).fields.isEmpty())
    }

    @Test
    fun sideloadedFilesLiveInTheirFolder() = runBlocking {
        assertEquals(Outcome.Ok(Unit), files.save(jg5k, byteArrayOf(9, 8), definition, "<strings/>"))
        assertTrue(File(files.dir(jg5k), SideloadStore.PRG_FILE).isFile)
        assertEquals(jg5k.toString(), files.dir(jg5k).name)
        assertArrayEquals(byteArrayOf(9, 8), files.prg(jg5k))
        assertEquals("<strings/>", files.stringsXml(jg5k))
        files.save(jg5k, byteArrayOf(7), null)
        assertNull(files.settingsJson(jg5k))
        assertNull(files.stringsXml(jg5k))
        assertTrue(files.delete(jg5k))
        assertNull(files.prg(jg5k))
    }

    @Test
    fun commanderRunsRequestsAndPublishesResults() = runBlocking {
        val sent = ArrayList<WatchCommand>()
        var answer: Outcome<Unit> = Outcome.Ok(Unit)
        var refreshed = 0
        val control = object : WatchAppControl {
            override suspend fun refreshApps(deviceId: Long): Outcome<List<WatchApp>> {
                refreshed++
                return Outcome.Ok(emptyList())
            }

            override suspend fun requestSettings(deviceId: Long, appUuid: UUID): Outcome<Unit> =
                Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_SETTINGS))
        }
        val cmd = WatchAppsCommander({ _, c -> sent.add(c); answer }, control, store, files, db.settings, clock)

        assertEquals(Outcome.Ok(Unit), cmd.run(WatchAppRequest(WatchAppOp.REFRESH, deviceId, null, "r1")))
        assertEquals(1, refreshed)
        assertEquals(WatchAppResult("r1", WatchAppOp.REFRESH, null, true, null, clock.nowSec()), result())

        // Install without a stored file fails; with one it sends the bytes and the settings definition.
        assertTrue(cmd.run(WatchAppRequest(WatchAppOp.INSTALL, deviceId, jg5k, "r2")) is Outcome.Fail)
        assertEquals(false, result()?.ok)
        files.save(jg5k, byteArrayOf(5, 5), definition)
        cmd.run(WatchAppRequest(WatchAppOp.INSTALL, deviceId, jg5k, "r3"))
        assertEquals(WatchCommand.InstallApp(byteArrayOf(5, 5), definition), sent.last())

        cmd.run(WatchAppRequest(WatchAppOp.LAUNCH, deviceId, jg5k, "r4"))
        assertEquals(WatchCommand.LaunchApp(jg5k), sent.last())
        cmd.run(WatchAppRequest(WatchAppOp.DELETE, deviceId, jg5k, "r5"))
        assertEquals(WatchCommand.DeleteApp(jg5k), sent.last())

        val read = cmd.run(WatchAppRequest(WatchAppOp.READ_SETTINGS, deviceId, jg5k, "r6"))
        assertTrue(read is Outcome.Fail)
        assertEquals("unsupported: watch_app_settings", result()?.reason)

        // A successful write is backed up; a failed one is not.
        cmd.run(WatchAppRequest(WatchAppOp.WRITE_SETTINGS, deviceId, jg5k, "r7", """{"theme":0,"dim":true}"""))
        assertEquals(WatchCommand.WriteAppSettings(jg5k, mapOf("theme" to CiqValue.Num(0), "dim" to CiqValue.Bool(true))), sent.last())
        assertEquals(mapOf("theme" to CiqValue.Num(0), "dim" to CiqValue.Bool(true)), store.latestValues(jg5k))
        assertEquals(WatchAppResult("r7", WatchAppOp.WRITE_SETTINGS, jg5k, true, null, clock.nowSec()), result())
        clock.advance(5)
        answer = Outcome.Fail(SwError.NotConnected(deviceId))
        cmd.run(WatchAppRequest(WatchAppOp.WRITE_SETTINGS, deviceId, jg5k, "r8", """{"theme":1}"""))
        assertEquals(CiqValue.Num(0), store.latestValues(jg5k)?.get("theme"))
        assertEquals(WatchAppResult("r8", WatchAppOp.WRITE_SETTINGS, jg5k, false, "not connected", clock.nowSec()), result())
        assertTrue(cmd.run(WatchAppRequest(WatchAppOp.WRITE_SETTINGS, deviceId, jg5k, "r9", "[1]")) is Outcome.Fail)
    }

    @Test
    fun requestIntentsAndResultTextRoundTrip() {
        val req = WatchAppRequest(WatchAppOp.WRITE_SETTINGS, 7, jg5k, "abc", LinkValues.settingsToJson(mapOf("a" to CiqValue.Num(1))))
        val i: Intent = SyncCommands.watchAppIntent(app, req)
        assertEquals(SyncCommands.ACTION_WATCH_APP, i.action)
        assertEquals(req, SyncCommands.parseWatchApp(i))
        assertNull(SyncCommands.parseWatchApp(Intent(SyncCommands.ACTION_WATCH_APP)))
        val r = WatchAppResult("id|1", WatchAppOp.INSTALL, null, false, "too\nlarge | file", 123)
        assertEquals(WatchAppResult("id/1", WatchAppOp.INSTALL, null, false, "too large / file", 123), WatchAppResults.decode(WatchAppResults.encode(r)))
        assertNull(WatchAppResults.decode("garbage"))
        assertNull(WatchAppResults.decode(null))
    }

    private suspend fun result(): WatchAppResult? = WatchAppResults.decode(db.settings.get(WatchAppResults.key(deviceId)))
}
