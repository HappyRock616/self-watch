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
import app.selfwatch.SelfWatchApp
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.WeightUnit
import app.selfwatch.me.MeTestSupport
import app.selfwatch.model.time.Day
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Import preview → overrides → commit → history → undo, against a real (Robolectric) database. */
@RunWith(RobolectricTestRunner::class)
class ImportViewModelTest {

    private lateinit var app: SelfWatchApp
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        app = MeTestSupport.freshApp()
    }

    @After
    fun tearDown() {
        store.clear()
        MeTestSupport.resetDb()
    }

    private fun vm(): ImportViewModel = ViewModelProvider(store, ImportViewModel.factory(app.graph))[ImportViewModel::class.java]

    private fun loseIt(): InputSource = InputSource.ofBytes("loseit-export.zip", MeTestSupport.zipOfDir("imports-synthetic/loseit"))

    @Test
    fun previewShowsDetectedFormatAndUnitsThenOverridesCommitAndUndo() {
        val vm = vm()
        vm.open(loseIt())
        MeTestSupport.waitFor("preview", { vm.state.value.toString() }) { vm.state.value.stage == ImportStage.PREVIEW }
        val s = vm.state.value
        val plan = requireNotNull(s.plan)
        assertEquals("loseit", s.importerId)
        assertEquals(mapOf("diary" to 5, "body" to 3, "exercise" to 2), plan.counts)
        assertTrue(plan.dateFormat.ambiguous)
        assertEquals("MM/dd/yyyy", s.choices?.dateFormat)
        assertEquals(WeightUnit.LB, s.choices?.weightUnit)
        assertTrue("dd/MM/yyyy" in plan.dateFormat.candidates)

        // The user says the dates are day/month and the weights kilograms.
        vm.setDateFormat("dd/MM/yyyy")
        vm.setWeightUnit(WeightUnit.KG)
        assertEquals("dd/MM/yyyy", vm.state.value.choices?.dateFormat)
        vm.confirm()
        MeTestSupport.waitFor("done", { vm.state.value.toString() }) { vm.state.value.stage == ImportStage.DONE }
        val result = requireNotNull(vm.state.value.result)
        assertTrue(result.importId > 0)

        val db = app.graph.db
        val april = runBlocking { db.diary.day(Day(20260403)) }
        assertTrue("diary written on 3 April (day/month)", april.isNotEmpty())
        assertTrue(runBlocking { db.diary.day(Day(20260304)) }.isEmpty())

        MeTestSupport.waitFor("history", { vm.state.value.history.toString() }) { vm.state.value.history.size == 1 }
        assertEquals("loseit", vm.state.value.history.single().importer)

        vm.undo(result.importId)
        MeTestSupport.waitFor("undo", { vm.state.value.toString() }) { vm.state.value.undoneRows != null }
        assertTrue(requireNotNull(vm.state.value.undoneRows) > 0)
        MeTestSupport.waitFor("history undone") { vm.state.value.history.singleOrNull()?.undone == true }
        assertTrue(runBlocking { db.diary.day(Day(20260403)) }.isEmpty())
    }

    @Test
    fun cancelSavesNothing() {
        val vm = vm()
        vm.open(loseIt())
        MeTestSupport.waitFor("preview") { vm.state.value.stage == ImportStage.PREVIEW }
        vm.cancel()
        assertEquals(ImportStage.IDLE, vm.state.value.stage)
        vm.confirm() // no plan any more
        MeTestSupport.waitFor("idle") { vm.state.value.stage == ImportStage.IDLE }
        assertTrue(runBlocking { app.graph.db.imports.list() }.isEmpty())
    }

    @Test
    fun unknownFilesFailCleanly() {
        val vm = vm()
        vm.open(InputSource.ofBytes("random.bin", byteArrayOf(1, 2, 3, 4, 5)))
        MeTestSupport.waitFor("failed") { vm.state.value.stage == ImportStage.FAILED }
    }

    @Test
    fun selfWatchBackupsAreSentToRestore() {
        val vm = vm()
        vm.open(InputSource.ofBytes("backup.swbak", "SWBK".toByteArray() + ByteArray(64)))
        MeTestSupport.waitFor("backup") { vm.state.value.stage == ImportStage.BACKUP_FILE }
    }
}
