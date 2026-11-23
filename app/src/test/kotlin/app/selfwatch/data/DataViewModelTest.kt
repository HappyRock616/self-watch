/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.data

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.SelfWatchApp
import app.selfwatch.db.SettingKeys
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.ExportResult
import app.selfwatch.interop.api.Exporter
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.MemorySink
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.interop.backup.BackupManifest
import app.selfwatch.interop.hc.HcStatus
import app.selfwatch.interop.hc.HcSyncReport
import app.selfwatch.interop.jobs.AutoJobs
import app.selfwatch.me.MeTestSupport
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

/** Data screen: restore needs confirmation, backups keep their passphrase, export and automatic settings. */
@RunWith(RobolectricTestRunner::class)
class DataViewModelTest {

    private lateinit var app: SelfWatchApp
    private val store = ViewModelStore()
    private val backups = FakeBackups()
    private val passphrases = FakePassphrases()
    private val exporter = FakeExporter()

    @Before
    fun setUp() {
        app = MeTestSupport.freshApp()
    }

    @After
    fun tearDown() {
        store.clear()
        MeTestSupport.resetDb()
    }

    private fun vm(newest: Long? = 42L): DataViewModel {
        val g = app.graph
        val f = viewModelFactory {
            initializer {
                DataViewModel(g.settings, backups, listOf(exporter), passphrases, FakeHc(), { newest }, { NOW }, { ZoneOffset.UTC })
            }
        }
        val vm = ViewModelProvider(store, f)[DataViewModel::class.java]
        MeTestSupport.waitFor("loaded") { !vm.state.value.loading }
        return vm
    }

    @Test
    fun restoreRunsOnlyAfterConfirmation() {
        val vm = vm()
        val src = InputSource.ofBytes("b.zip", ByteArray(4))
        vm.prepareRestore(src, null)
        MeTestSupport.waitFor("pending") { vm.state.value.pendingRestore != null }
        assertEquals(1, backups.inspected)
        assertEquals("nothing replaced before the user confirms", 0, backups.restored)

        vm.cancelRestore()
        assertNull(vm.state.value.pendingRestore)
        vm.confirmRestore() // nothing pending: ignored
        MeTestSupport.waitFor("idle") { !vm.state.value.busy }
        assertEquals(0, backups.restored)

        vm.prepareRestore(src, "secret".toCharArray())
        MeTestSupport.waitFor("pending again") { vm.state.value.pendingRestore != null }
        vm.confirmRestore()
        MeTestSupport.waitFor("restored") { vm.state.value.message is DataMessage.Restored }
        assertEquals(1, backups.restored)
        assertEquals("secret", backups.lastPassphrase)
        assertNull(vm.state.value.pendingRestore)
    }

    @Test
    fun aBadBackupNeverAsksForConfirmation() {
        backups.inspectResult = Outcome.Fail(SwError.BadInput("wrong passphrase"))
        val vm = vm()
        vm.prepareRestore(InputSource.ofBytes("b.swbak", ByteArray(4)), "nope".toCharArray())
        MeTestSupport.waitFor("failed") { vm.state.value.message is DataMessage.Failed }
        assertNull(vm.state.value.pendingRestore)
        assertEquals(0, backups.restored)
    }

    @Test
    fun backupUsesThePassphraseGivenAtStartAndRecordsTheTime() {
        val vm = vm()
        val name = vm.startBackup("pw".toCharArray())
        assertTrue(name.endsWith(".swbak"))
        vm.backupTo(MemorySink())
        MeTestSupport.waitFor("backed up") { vm.state.value.message is DataMessage.BackedUp }
        assertEquals("pw", backups.lastWritePassphrase)
        assertEquals(true, (vm.state.value.message as DataMessage.BackedUp).encrypted)
        assertEquals(NOW.toString(), runBlocking { app.graph.settings.get(DataViewModel.LAST_BACKUP_SEC) })
        MeTestSupport.waitFor("last backup shown") { vm.state.value.lastBackupSec == NOW }
    }

    @Test
    fun backupWithoutStartIsRefused() {
        val vm = vm()
        vm.backupTo(MemorySink())
        MeTestSupport.waitFor("message") { vm.state.value.message != null }
        assertEquals(0, backups.writes)
        assertTrue(vm.startBackup(CharArray(0)).endsWith(".zip"))
    }

    @Test
    fun exportUsesTheChosenRange() {
        val vm = vm()
        assertEquals(listOf("gpx"), vm.exportIds)
        assertNotNull(vm.chooseExport("gpx", ExportRange.LAST_30_DAYS))
        val p = vm.state.value.pendingExport
        assertEquals("export.gpx", p?.fileName)
        vm.runExport(MemorySink())
        MeTestSupport.waitFor("exported") { vm.state.value.message is DataMessage.Exported }
        val today = LocalDate.ofEpochDay(NOW / 86_400)
        assertEquals(ExportRange.LAST_30_DAYS.days(today), exporter.lastRequest?.days)
        assertEquals(30, exporter.lastRequest?.days?.days()?.size)
    }

    @Test
    fun automaticSettingsAreStored() {
        val vm = vm()
        vm.setAutoBackup(true)
        vm.setBackupFolder("content://tree/primary%3ABackups")
        vm.setEveryDays(400)
        vm.setAutoGpx(true)
        MeTestSupport.waitFor("stored", { vm.state.value.toString() }) {
            val s = vm.state.value
            s.autoBackup && s.backupFolder != null && s.everyDays == DataViewModel.MAX_DAYS && s.autoGpx
        }
        val settings = app.graph.settings
        assertTrue(runBlocking { settings.getBool(SettingKeys.BACKUP_AUTO) })
        assertEquals("only workouts from now on are exported", 42L, runBlocking { settings.getLong(AutoJobs.LAST_GPX_ID) })
        assertTrue("encrypted by default but no passphrase kept", vm.state.value.autoBackupNeedsPassphrase)

        vm.storePassphrase("kept".toCharArray())
        MeTestSupport.waitFor("kept") { vm.state.value.passphraseStored }
        assertEquals("kept", passphrases.value)
        assertFalse(vm.state.value.autoBackupNeedsPassphrase)
        vm.forgetPassphrase()
        MeTestSupport.waitFor("forgotten") { !vm.state.value.passphraseStored }
    }

    private class FakeBackups : BackupOps {
        var inspected = 0
        var restored = 0
        var writes = 0
        var lastPassphrase: String? = null
        var lastWritePassphrase: String? = null
        var inspectResult: Outcome<BackupManifest> = Outcome.Ok(MANIFEST)

        override suspend fun write(out: OutputSink, passphrase: CharArray?): Outcome<BackupManifest> {
            writes++
            lastWritePassphrase = passphrase?.let { String(it) }
            return Outcome.Ok(MANIFEST)
        }

        override suspend fun inspect(src: InputSource, passphrase: CharArray?): Outcome<BackupManifest> {
            inspected++
            return inspectResult
        }

        override suspend fun restore(src: InputSource, passphrase: CharArray?): Outcome<BackupManifest> {
            restored++
            lastPassphrase = passphrase?.let { String(it) }
            return Outcome.Ok(MANIFEST)
        }
    }

    private class FakePassphrases : PassphraseStore {
        var value: String? = null
        override fun isStored(): Boolean = value != null
        override suspend fun load(): CharArray? = value?.toCharArray()
        override suspend fun save(passphrase: CharArray): Boolean {
            value = String(passphrase)
            return true
        }
        override suspend fun clear() {
            value = null
        }
    }

    private class FakeExporter : Exporter {
        var lastRequest: ExportRequest? = null
        override val id: String = "gpx"
        override fun mimeType(req: ExportRequest): String = "application/gpx+xml"
        override fun suggestedName(req: ExportRequest): String = "export.gpx"
        override suspend fun export(req: ExportRequest, out: OutputSink): Outcome<ExportResult> {
            lastRequest = req
            return Outcome.Ok(ExportResult(mapOf("workout" to 2)))
        }
    }

    private class FakeHc : HcOps {
        override fun status(): HcStatus = HcStatus.UNSUPPORTED
        override suspend fun granted(): Set<String> = emptySet()
        override suspend fun sync(): Outcome<HcSyncReport> = Outcome.Ok(HcSyncReport(emptyMap(), emptyMap(), null))
    }

    private companion object {
        const val NOW = 1_780_000_000L
        val MANIFEST = BackupManifest(1, "0.1.0", 1, NOW - 86_400, mapOf("diary_entry" to 3L), 0)
    }
}
