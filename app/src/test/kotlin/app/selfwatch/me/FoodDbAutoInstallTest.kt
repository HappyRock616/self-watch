/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import app.selfwatch.food.install.DataManifest
import app.selfwatch.food.install.UpdateCheck
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/** The automatic food database download: when it starts, Wi-Fi queueing, progress, errors shown once. */
class FoodDbAutoInstallTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manifest = DataManifest(2, 20261122, 1, "foods.db.gz", "https://example.org/foods.db.gz", "00", 1_000L, 4_000L, 10L, null)

    @After
    fun tearDown() = scope.cancel()

    private class Ops(var installed: Int? = null) : FoodDataOps {
        var checks = 0
        var installs = 0
        val checkAnswers = ArrayDeque<Outcome<UpdateCheck>>()
        var installAnswer: Outcome<Int> = Outcome.Ok(20261122)
        val progress = ArrayList<Pair<Long, Long?>>()
        override suspend fun installedVersion(): Int? = installed
        override suspend fun sizeBytes(): Long = 0
        override suspend fun check(): Outcome<UpdateCheck> {
            checks++
            return checkAnswers.removeFirstOrNull() ?: error("no answer queued")
        }
        override suspend fun install(m: DataManifest, onProgress: (Long, Long?) -> Unit): Outcome<Int> {
            installs++
            onProgress(500, 1_000)
            progress += 500L to 1_000L
            return installAnswer.also { if (it is Outcome.Ok) installed = it.value }
        }
        override suspend fun installFrom(input: InputStream): Outcome<Int> = error("unused")
        override suspend fun remove() = Unit
    }

    private class Wifi(var on: Boolean) : WifiWaiter {
        val arrived = CompletableDeferred<Unit>()
        var waits = 0
        override fun onWifi(): Boolean = on
        override suspend fun awaitWifi() {
            waits++
            arrived.await()
            on = true
        }
    }

    private fun auto(ops: Ops, wifi: Wifi, allowed: Boolean = true) =
        FoodDbAutoInstall(ops, { allowed }, wifi, scope, retryDelayMs = 1)

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }

    @Test
    fun downloadsOnFirstUseOnWifiAndSaysSoOnce() {
        val ops = Ops().apply { checkAnswers += Outcome.Ok(UpdateCheck.Available(manifest)) }
        val a = auto(ops, Wifi(on = true))
        await { a.ensure()?.join() }
        assertEquals(AutoFoodDbState.Installed(20261122), a.state.value)
        assertEquals(1, ops.installs)
        a.acknowledge()
        assertEquals(AutoFoodDbState.Idle, a.state.value)
        // Opening food entry again does nothing more.
        await { a.ensure()?.join() }
        assertEquals(1, ops.checks)
        assertEquals(AutoFoodDbState.Idle, a.state.value)
    }

    @Test
    fun nothingWhenInstalledOrTurnedOff() {
        val installed = Ops(installed = 1)
        await { auto(installed, Wifi(true)).ensure()?.join() }
        assertEquals(0, installed.checks)
        val blocked = Ops()
        val a = auto(blocked, Wifi(true), allowed = false)
        await { a.ensure()?.join() }
        assertEquals(0, blocked.checks)
        assertEquals("no nagging when food data is off", AutoFoodDbState.Idle, a.state.value)
        assertTrue(NetFeature.FOOD_DATA.defaultOn)
    }

    @Test
    fun offWifiItWaitsThenDownloads() {
        val ops = Ops().apply { checkAnswers += Outcome.Ok(UpdateCheck.Available(manifest)) }
        val wifi = Wifi(on = false)
        val a = auto(ops, wifi)
        val job = a.ensure()!!
        await { while (a.state.value != AutoFoodDbState.WaitingForWifi) kotlinx.coroutines.delay(5) }
        assertEquals(0, ops.checks)
        // A second open while queued starts nothing new.
        assertTrue(a.ensure() === job)
        wifi.arrived.complete(Unit)
        await { job.join() }
        assertEquals(AutoFoodDbState.Installed(20261122), a.state.value)
        assertEquals(1, wifi.waits)
    }

    @Test
    fun lostWifiRequeuesAndErrorsAreShownOnceWithoutRetry() {
        val ops = Ops().apply {
            checkAnswers += Outcome.Fail(SwError.WaitingForWifi)
            checkAnswers += Outcome.Ok(UpdateCheck.Available(manifest))
            installAnswer = Outcome.Fail(SwError.Io("HTTP 500"))
        }
        val a = auto(ops, Wifi(on = true))
        await { a.ensure()?.join() }
        assertEquals(2, ops.checks)
        val s = a.state.value
        assertTrue(s.toString(), s is AutoFoodDbState.Failed)
        a.acknowledge()
        await { a.ensure()?.join() }
        assertEquals("a failure is not retried by itself in the same run", 2, ops.checks)
    }

    @Test
    fun newerAppNeededIsReported() {
        val ops = Ops().apply { checkAnswers += Outcome.Ok(UpdateCheck.NeedsNewerApp(3, 99)) }
        val a = auto(ops, Wifi(true))
        await { a.ensure()?.join() }
        assertEquals(AutoFoodDbState.NeedsNewerApp, a.state.value)
        assertEquals(0, ops.installs)
    }
}
