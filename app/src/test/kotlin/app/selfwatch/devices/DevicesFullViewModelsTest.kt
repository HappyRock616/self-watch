/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.devices

import android.Manifest
import android.app.Application
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.dao.LinkPermission
import app.selfwatch.db.dao.WatchAppRow
import app.selfwatch.db.dao.WatchAppSettings
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.Alarm
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchAppKind
import app.selfwatch.service.device.AlarmSettings
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.service.link.LinkSettings
import app.selfwatch.service.link.LinkValues
import app.selfwatch.service.watchapps.SettingsDefinitionParser
import app.selfwatch.service.watchapps.SettingsForm
import app.selfwatch.service.watchapps.SideloadStore
import app.selfwatch.service.watchapps.WatchAppOp
import app.selfwatch.service.watchapps.WatchAppRequest
import app.selfwatch.service.watchapps.WatchAppResult
import app.selfwatch.service.watchapps.WatchAppResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.UUID

/** C19 view models: install flow, settings editor, linked apps, permission-gated toggles, feature hiding. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DevicesFullViewModelsTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private lateinit var db: SelfWatchDb

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeCommands : WatchCommands {
        val requests = ArrayList<WatchAppRequest>()
        val rings = ArrayList<Pair<Long, Boolean>>()
        val alarms = ArrayList<Long>()
        var tests = 0
        var accept = true
        override fun watchApp(req: WatchAppRequest): Boolean {
            requests += req
            return accept
        }
        override fun findWatch(deviceId: Long, ring: Boolean): Boolean {
            rings += deviceId to ring
            return accept
        }
        override fun setAlarms(deviceId: Long): Boolean {
            alarms += deviceId
            return accept
        }
        override fun testNotification(): Boolean {
            tests++
            return accept
        }
        override fun syncNow(): Boolean = accept
    }

    private class FakePerms(override var sdkInt: Int = 34) : PermissionChecker {
        val held = HashSet<String>()
        var access = false
        override fun granted(permission: String): Boolean = permission in held
        override fun notificationAccess(): Boolean = access
    }

    private class FakeCatalog : AppCatalog {
        override fun label(packageName: String): String? = if (packageName == "com.example.run") "Run app" else null
        override fun launcherApps(): List<PhoneApp> = listOf(PhoneApp("com.example.chat", "Chat"), PhoneApp("com.example.run", "Run app"))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = SelfWatchDb.get(ctx)
        val raw = db.writableDatabase
        for (t in listOf("setting", "device", "watch_app", "watch_app_settings", "link_permission")) raw.execSQL("DELETE FROM $t")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun addWatch(features: Set<Feature> = emptySet(), connected: Boolean = true): Long {
        val id = db.devices.upsert(
            DeviceInfo(0, DeviceKind.GARMIN_WATCH, "AA:BB:CC:DD:EE:01", "vívoactive 5", "vívoactive 5", null, null, null, SupportTier.VERIFIED, features, null),
        )
        if (connected) db.settings.set(WatchStatus.stateKey(id), "ready")
        return id
    }

    private fun <T> CoroutineScope.watch(flow: kotlinx.coroutines.flow.Flow<T>) = launch { flow.collect {} }

    // ---- Install flow -----------------------------------------------------------------------------

    @Test
    fun installFromFileParsesThePrgStoresFilesAndSendsInstall() = runBlocking {
        val id = addWatch()
        val app = UUID.fromString("6f2c1a9e-0d4b-4c8a-9e3f-1b2c3d4e5f60")
        val files = SideloadStore(tmp.newFolder("watchapps"))
        val cmds = FakeCommands()
        var n = 0
        val vm = WatchAppsViewModel(db, cmds, files, newRequestId = { "r${++n}" })
        val job = watch(vm.state)
        withTimeout(5_000) { vm.state.first { it.watch?.id == id } }

        vm.pickPrg("demo.prg", byteArrayOf(1, 2, 3))
        assertTrue(vm.state.value.message is WatchAppsMessage.BadPrg)
        vm.messageShown()

        val prg = syntheticPrg(app, type = 2)
        vm.pickPrg("demo.prg", prg)
        val p = vm.state.value.pending
        assertEquals(app, p?.info?.appUuid)
        assertEquals(WatchAppKind.DATA_FIELD, p?.info?.kind)

        vm.addSettings("bad.json", "[]")
        assertTrue(vm.state.value.message is WatchAppsMessage.BadSettings)
        vm.messageShown()
        vm.addSettings("demo-settings.json", SETTINGS_JSON)
        vm.addStrings("strings.xml", "<strings><string id=\"ModeTitle\">Mode</string></strings>")
        assertEquals("demo-settings.json", vm.state.value.pending?.settingsName)
        assertEquals("strings.xml", vm.state.value.pending?.stringsName)

        vm.install()
        assertNull(vm.state.value.pending)
        assertArrayEquals(prg, files.prg(app))
        assertEquals(SETTINGS_JSON, files.settingsJson(app))
        assertTrue(files.stringsXml(app)?.contains("ModeTitle") == true)
        assertEquals(WatchAppRequest(WatchAppOp.INSTALL, id, app, "r1"), cmds.requests.single())
        assertEquals(WatchAppOp.INSTALL, vm.state.value.progress?.op)

        // The service publishes the result; the list is then read again, quietly.
        db.settings.set(WatchAppResults.key(id), WatchAppResults.encode(WatchAppResult("r1", WatchAppOp.INSTALL, app, true, null, 1)))
        withTimeout(5_000) { vm.state.first { it.message != null } }
        assertEquals(WatchAppsMessage.Done(WatchAppOp.INSTALL, "demo.prg"), vm.state.value.message)
        assertEquals(WatchAppOp.REFRESH, cmds.requests.last().op)
        vm.messageShown()

        db.watchApps.upsert(WatchAppRow(id, app, "Demo field", 3, 4, true, 2))
        val listed = withTimeout(5_000) { vm.state.first { it.apps.isNotEmpty() } }.apps.single()
        assertEquals(WatchAppItem(app, "Demo field", 3, WatchAppKind.DATA_FIELD), listed)

        // A failed delete shows the reason.
        vm.delete(listed)
        val del = cmds.requests.last()
        assertEquals(WatchAppOp.DELETE, del.op)
        db.settings.set(WatchAppResults.key(id), WatchAppResults.encode(WatchAppResult(del.requestId, WatchAppOp.DELETE, app, false, "not connected", 3)))
        withTimeout(5_000) { vm.state.first { it.message != null } }
        assertEquals(WatchAppsMessage.Failed(WatchAppOp.DELETE, "not connected"), vm.state.value.message)

        cmds.accept = false
        vm.launch(listed)
        assertEquals(WatchAppsMessage.Refused, vm.state.value.message)
        job.cancel()
    }

    @Test
    fun noAnswerClearsTheProgress() = runBlocking {
        // The answer timer needs real time (the test dispatcher's virtual clock never moves here).
        Dispatchers.setMain(Dispatchers.Unconfined)
        addWatch()
        val vm = WatchAppsViewModel(db, FakeCommands(), SideloadStore(tmp.newFolder("w")), answerTimeoutMs = 50)
        val job = watch(vm.state)
        withTimeout(5_000) { vm.state.first { it.watch != null } }
        vm.refresh()
        withTimeout(5_000) { vm.state.first { it.message is WatchAppsMessage.NoAnswer } }
        assertNull(vm.state.value.progress)
        job.cancel()
    }

    // ---- Settings editor --------------------------------------------------------------------------

    @Test
    fun editorPicksAControlPerField() {
        val form = (SettingsDefinitionParser.parse(SETTINGS_JSON) as Outcome.Ok).value
        val kinds = form.fields.associate { it.key to SettingsEditor.kindOf(it) }
        assertEquals(
            mapOf(
                "mode" to EditorKind.DROPDOWN, "count" to EditorKind.SLIDER, "ratio" to EditorKind.STEPPER,
                "on" to EditorKind.SWITCH, "name" to EditorKind.TEXT, "ver" to EditorKind.INFO,
            ),
            kinds,
        )
        val count = form.field("count")!!
        assertEquals(CiqValue.Num(7), SettingsEditor.fromX100(count, 700))
        assertEquals(CiqValue.Dbl(2.25), SettingsEditor.fromX100(form.field("ratio")!!, 225))
        assertEquals(CiqValue.Num(4), SettingsEditor.parse(count, " 4 "))
        assertNull(SettingsEditor.parse(count, "four"))
        assertEquals(CiqValue.Str("abc"), SettingsEditor.parse(form.field("name")!!, "abc"))
        val values = SettingsEditor.valuesToSend(form, mapOf("mode" to CiqValue.Num(0)), mapOf("count" to CiqValue.Num(9)))
        assertEquals(
            mapOf("mode" to CiqValue.Num(0), "count" to CiqValue.Num(9), "ratio" to CiqValue.Dbl(2.5), "on" to CiqValue.Bool(true), "name" to CiqValue.Str("x")),
            values,
        )
        assertEquals("""{"mode":0,"count":9,"ratio":2.5,"on":true,"name":"x"}""", SettingsEditor.valuesJson(values))
    }

    @Test
    fun settingsScreenReadsSavesAndRestores() = runBlocking {
        val id = addWatch()
        val app = UUID.randomUUID()
        val form = (SettingsDefinitionParser.parse(SETTINGS_JSON) as Outcome.Ok).value
        val cmds = FakeCommands()
        var n = 0
        val vm = AppSettingsViewModel(db, cmds, { form }, id, app, newRequestId = { "s${++n}" })
        assertEquals(WatchAppOp.READ_SETTINGS, cmds.requests.single().op)
        assertEquals(WatchAppOp.READ_SETTINGS, vm.state.value.busy)

        // What the watch reported lands in watch_app_settings (by the service).
        db.watchApps.saveSettings(WatchAppSettings(app, 100, """{"mode":0,"count":3}""", null))
        db.settings.set(WatchAppResults.key(id), WatchAppResults.encode(WatchAppResult("s1", WatchAppOp.READ_SETTINGS, app, true, null, 100)))
        val s = withTimeout(5_000) { vm.state.first { it.message == AppSettingsMessage.Read && it.values["count"] == CiqValue.Num(3) } }
        assertNull(s.busy)
        assertEquals(CiqValue.Num(0), s.values["mode"])
        assertEquals(CiqValue.Bool(true), s.values["on"])
        assertTrue(s.form.fromDefinition)
        vm.messageShown()

        vm.set("count", CiqValue.Num(11))
        assertEquals(setOf("count"), vm.state.value.invalid)
        vm.save()
        assertEquals(AppSettingsMessage.Invalid(setOf("count")), vm.state.value.message)
        assertEquals(1, cmds.requests.size)

        vm.set("count", CiqValue.Num(7))
        vm.save()
        val write = cmds.requests.last()
        assertEquals(WatchAppOp.WRITE_SETTINGS, write.op)
        assertEquals(
            mapOf("mode" to CiqValue.Num(0), "count" to CiqValue.Num(7), "ratio" to CiqValue.Dbl(2.5), "on" to CiqValue.Bool(true), "name" to CiqValue.Str("x")),
            LinkValues.settingsFromJson(write.valuesJson ?: ""),
        )
        // The service keeps a copy of what it wrote, then publishes the result.
        db.watchApps.saveSettings(WatchAppSettings(app, 200, write.valuesJson ?: "", null))
        db.settings.set(WatchAppResults.key(id), WatchAppResults.encode(WatchAppResult(write.requestId, WatchAppOp.WRITE_SETTINGS, app, true, null, 200)))
        val saved = withTimeout(5_000) { vm.state.first { it.message == AppSettingsMessage.Saved && it.history.size == 2 } }
        assertTrue(saved.edited.isEmpty())
        assertEquals(CiqValue.Num(7), saved.values["count"])

        vm.restore(100)
        assertEquals(AppSettingsMessage.Restored, vm.state.value.message)
        assertEquals(CiqValue.Num(3), vm.state.value.values["count"])

        vm.setRaw(true)
        assertFalse(vm.state.value.form.fromDefinition)
        assertTrue(vm.state.value.form.fields.any { it.key == "count" })
    }

    @Test
    fun rawKeysWhenThereIsNoDefinition() = runBlocking {
        val id = addWatch()
        val app = UUID.randomUUID()
        db.watchApps.saveSettings(WatchAppSettings(app, 100, """{"speed":2,"label":"go"}""", null))
        val vm = AppSettingsViewModel(db, FakeCommands(), { SettingsForm.fromValues(emptyMap()) }, id, app)
        val s = withTimeout(5_000) { vm.state.first { it.form.fields.size == 2 } }
        assertFalse(s.form.fromDefinition)
        assertEquals(EditorKind.STEPPER, SettingsEditor.kindOf(s.form.field("speed")!!))
        assertEquals(EditorKind.TEXT, SettingsEditor.kindOf(s.form.field("label")!!))
    }

    // ---- Linked apps ------------------------------------------------------------------------------

    @Test
    fun linkedAppsShowLabelsAndRevoke() = runBlocking {
        val id = addWatch()
        val app = UUID.randomUUID()
        db.watchApps.upsert(WatchAppRow(id, app, "Run field", 1, 4, true, 1))
        db.links.set(LinkPermission("com.example.run", app, "cert", true, 10, 20))
        db.links.set(LinkPermission("com.example.gone", app, "cert", false, 10, null))
        val vm = LinkedAppsViewModel(db, FakeCatalog())
        val s = vm.load()
        assertTrue(s.enabled)
        assertEquals(listOf("com.example.gone", "Run app"), s.rows.map { it.label })
        val run = s.rows.first { it.packageName == "com.example.run" }
        assertEquals("Run field", run.watchAppName)
        assertEquals(20L, run.lastUsedSec)
        assertEquals(listOf("Run field"), s.watchApps.map { it.name })

        vm.decide(run, false)
        assertFalse(db.links.get("com.example.run", app)?.allowed ?: true)
        vm.decide(run, true)
        assertTrue(db.links.get("com.example.run", app)?.allowed ?: false)

        db.settings.set(LinkSettings.CALORIES_APP, app.toString())
        db.settings.setBool(LinkSettings.ENABLED, false)
        val after = vm.load()
        assertEquals(app, after.caloriesApp)
        assertFalse(after.enabled)
    }

    // ---- Permission-gated toggles -----------------------------------------------------------------

    @Test
    fun callsAskForPermissionsWhenTurnedOn() = runBlocking {
        val perms = FakePerms()
        val vm = CallSettingsViewModel(db, perms)
        assertFalse("stored on by default, but no permission", vm.load().calls)

        val missing = vm.prepare(CallOption.CALLS, true)
        assertEquals(listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS), missing)
        assertFalse(db.settings.getBool(SettingKeys.CALLS_ENABLED, true))

        perms.held += Manifest.permission.READ_PHONE_STATE
        assertTrue(vm.granted(CallOption.CALLS, mapOf(Manifest.permission.READ_PHONE_STATE to true, Manifest.permission.ANSWER_PHONE_CALLS to false)))
        assertTrue(db.settings.getBool(SettingKeys.CALLS_ENABLED))
        val s = vm.load()
        assertTrue(s.calls)
        assertFalse(s.canAnswer)

        assertEquals(listOf(Manifest.permission.READ_CALL_LOG), vm.prepare(CallOption.NUMBER, true))
        perms.sdkInt = 28
        assertEquals(emptyList<String>(), vm.prepare(CallOption.NUMBER, true))
        assertTrue(vm.load().number)

        assertEquals(listOf(Manifest.permission.READ_CONTACTS), vm.prepare(CallOption.NAME, true))
        assertFalse(vm.granted(CallOption.NAME, mapOf(Manifest.permission.READ_CONTACTS to false)))
        assertFalse(db.settings.getBool(SettingKeys.CALLS_SHOW_NAME))

        assertEquals(emptyList<String>(), vm.prepare(CallOption.CALLS, false))
        assertFalse(vm.load().calls)
    }

    @Test
    fun notificationsNeedAccessAndKeepADenyList() = runBlocking {
        val perms = FakePerms()
        val cmds = FakeCommands()
        val vm = NotificationSettingsViewModel(db, perms, FakeCatalog(), cmds)
        assertTrue("needs access", vm.setEnabled(true))
        val s = withTimeout(5_000) { vm.state.first { !it.loading } }
        assertTrue(s.wanted)
        assertFalse(s.on)
        assertEquals(listOf("Chat", "Run app"), s.apps.map { it.label })
        perms.access = true
        assertFalse(vm.setEnabled(true))
        vm.recheck()
        assertTrue(withTimeout(5_000) { vm.state.first { it.access } }.on)

        vm.setAllowed("com.example.chat", false)
        assertEquals(listOf("com.example.chat"), db.settings.getList(SettingKeys.NOTIFY_APPS_DENIED))
        vm.setAllowed("com.example.chat", true)
        assertEquals(emptyList<String>(), db.settings.getList(SettingKeys.NOTIFY_APPS_DENIED))

        assertEquals(SendOutcome.NOT_CONNECTED, vm.sendTest())
        addWatch(connected = true)
        assertEquals(SendOutcome.SENT, vm.sendTest())
        assertEquals(1, cmds.tests)
    }

    // ---- Feature hiding, alarms, watch settings ---------------------------------------------------

    @Test
    fun rowsHideWhenTheWatchLacksTheFeature() {
        val none = emptySet<Feature>()
        assertTrue("unknown features hide nothing", DeviceRow.entries.all { DeviceRow.visible(it, none) })
        val some = setOf(Feature.NOTIFICATIONS, Feature.WATCH_APP_MANAGE)
        assertTrue(DeviceRow.visible(DeviceRow.NOTIFICATIONS, some))
        assertTrue(DeviceRow.visible(DeviceRow.WATCH_APPS, some))
        assertFalse(DeviceRow.visible(DeviceRow.ALARMS, some))
        assertFalse(DeviceRow.visible(DeviceRow.CALLS, some))
        assertFalse(DeviceRow.visible(DeviceRow.INSTALL, some))
        assertTrue(DeviceRow.visible(DeviceRow.WATCH_SETTINGS, some))
        val w = ActiveWatch(1, "w", some, true)
        assertFalse(WatchSettingsUiState(watch = w).canFind)
        assertTrue(WatchSettingsUiState(watch = w.copy(features = some + Feature.FIND_WATCH)).canFind)
    }

    @Test
    fun alarmsAreKeptPerWatchAndSent() = runBlocking {
        val cmds = FakeCommands()
        val vm = AlarmsViewModel(db, cmds)
        assertFalse(vm.save(Alarm(0, 7, 0, emptySet(), true, null)))
        val id = addWatch(features = setOf(Feature.NOTIFICATIONS), connected = false)
        val unsupported = vm.load()
        assertFalse(unsupported.supported)
        assertFalse(unsupported.canAdd)

        db.devices.upsert(db.devices.get(id)!!.copy(features = setOf(Feature.ALARMS)))
        assertTrue(vm.load().supported)
        for (h in 0 until AlarmSettings.MAX) assertTrue(vm.save(Alarm(0, 23 - h, 30, emptySet(), true, "a$h")))
        assertFalse(vm.save(Alarm(0, 5, 0, emptySet(), true, null)))
        val s = vm.load()
        assertEquals(AlarmSettings.MAX, s.alarms.size)
        assertEquals(14, s.alarms.first().hour)
        assertFalse(s.canAdd)
        val first = s.alarms.first()
        vm.save(first.copy(minute = 0, label = "Early"))
        assertEquals("Early", vm.load().alarms.first().label)
        vm.delete(first.id)
        assertEquals(AlarmSettings.MAX - 1, vm.load().alarms.size)

        assertEquals(SendOutcome.NOT_CONNECTED, vm.send())
        db.settings.set(WatchStatus.stateKey(id), "ready")
        assertEquals(SendOutcome.SENT, vm.send())
        assertEquals(listOf(id), cmds.alarms)
        db.settings.set(AlarmSettings.resultKey(id), AlarmSettings.encodeResult(true, 50, null))
        assertEquals(50L, vm.load().lastSend?.tSec)
    }

    @Test
    fun findWatchNeedsAConnectedWatch() = runBlocking {
        val cmds = FakeCommands()
        val vm = WatchSettingsViewModel(db, cmds, debuggable = true)
        assertEquals(SendOutcome.NOT_CONNECTED, vm.ring(true))
        val id = addWatch(setOf(Feature.FIND_WATCH))
        assertEquals(SendOutcome.SENT, vm.ring(true))
        assertEquals(SendOutcome.SENT, vm.ring(false))
        assertEquals(listOf(id to true, id to false), cmds.rings)
        assertTrue(withTimeout(5_000) { vm.state.first { it.watch != null } }.canFind)
    }

    companion object {
        /** A settings definition with one field of each editor kind. */
        val SETTINGS_JSON: String = """
            {"settings":[
              {"key":"mode","valueType":"number","configTitle":"ModeTitle","configType":"list","defaultValue":1,
               "configOptions":[{"display":"A","value":0},{"display":"B","value":1}]},
              {"key":"count","valueType":"number","configType":"numeric","configMin":1,"configMax":10,"defaultValue":5},
              {"key":"ratio","valueType":"float","configType":"numeric","configMin":0,"configMax":1000,"defaultValue":2.5},
              {"key":"on","valueType":"boolean","defaultValue":true},
              {"key":"name","valueType":"string","defaultValue":"x","configMaxLength":5},
              {"key":"ver","valueType":"string","configReadonly":true,"defaultValue":"1.0"}
            ]}
        """.trimIndent()

        /** A minimal Connect IQ `.prg`: head section, entry points (one entry of [type]), end. */
        fun syntheticPrg(app: UUID, type: Int): ByteArray {
            val entry = 2 + 16 + 5 * 4
            val b = ByteBuffer.allocate(8 + 4 + 8 + entry + 8)
            b.putInt(0xd000d000.toInt()).putInt(4).putInt(0x01020304)
            b.putInt(0x6060c0de).putInt(entry).putShort(1)
            b.putLong(app.mostSignificantBits).putLong(app.leastSignificantBits)
            b.putInt(1).putInt(2).putInt(3).putInt(4).putInt(type)
            b.putInt(0).putInt(0)
            return b.array()
        }
    }
}
