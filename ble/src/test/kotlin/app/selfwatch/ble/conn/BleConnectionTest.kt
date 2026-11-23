/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.ble.conn

import android.bluetooth.BluetoothGattCharacteristic
import app.selfwatch.ble.gatt.GattCodes
import app.selfwatch.ble.log.BleLog
import app.selfwatch.ble.log.BleLogFormat
import app.selfwatch.model.ble.BytePipe
import app.selfwatch.model.ble.PortState
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.result.valueOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class BleConnectionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val address = "AA:BB:CC:DD:EE:01"

    private fun TestScope.newConnection(
        gatt: FakeGatt,
        env: FakeEnv = FakeEnv(),
        log: BleLog? = null,
        timing: BleTiming = BleTiming(),
    ): BleConnection = BleConnection(address, log, gatt, env, StandardTestDispatcher(testScheduler), timing)

    private fun TestScope.fake(): FakeGatt = FakeGatt(now = { testScheduler.currentTime })

    private suspend fun BleConnection.pipe(): BytePipe =
        (openPipe(Ids.SERVICE, Ids.RX, Ids.TX) as Outcome.Ok).value

    @Test
    fun connectWaitsThenDiscoversThenNegotiatesMtu() = runTest {
        val gatt = fake()
        val conn = newConnection(gatt)
        assertEquals(Outcome.Ok(Unit), conn.connect(auto = false))
        assertEquals(PortState.CONNECTED, conn.state.value)
        assertEquals(600L, testScheduler.currentTime) // the post-connect pause, nothing else waited
        assertEquals(listOf("discover", "mtu 517"), gatt.ops.toList())
        assertEquals(listOf(false), gatt.autoFlags.toList())
        assertEquals(517, conn.mtu.value)
        assertEquals(setOf(Ids.SERVICE), conn.serviceUuids())
        assertEquals(512, conn.pipe().maxWrite) // MTU − 3 = 514, capped at the 512-byte ATT value limit
        conn.close()
        assertEquals(PortState.CLOSED, conn.state.value)
        assertEquals(CloseReason.CLOSED_BY_APP, conn.closeReason.value)
        assertTrue(gatt.current.closed)
    }

    @Test
    fun mtuRefusedFallsBackTo20() = runTest {
        val gatt = fake().apply { mtuReply = Reply.NOT_STARTED }
        val conn = newConnection(gatt)
        assertEquals(Outcome.Ok(Unit), conn.connect(false))
        assertEquals(20, conn.pipe().maxWrite)
        assertEquals(23, conn.mtu.value)
        conn.close()
    }

    @Test
    fun mtuNeverAnsweredFallsBackTo20AndKeepsTheLink() = runTest {
        val gatt = fake().apply { mtuReply = Reply.SILENT }
        val conn = newConnection(gatt)
        assertEquals(Outcome.Ok(Unit), conn.connect(false))
        assertEquals(600L + 10_000L, testScheduler.currentTime)
        assertEquals(PortState.CONNECTED, conn.state.value)
        assertEquals(20, conn.pipe().maxWrite)
        assertEquals(1, gatt.sessions.size)
        conn.close()
    }

    @Test
    fun discoveryTimesOutOnceThenRetries() = runTest {
        val gatt = fake().apply { discoverReplies = ArrayDeque(listOf(Reply.SILENT, Reply.OK)) }
        val conn = newConnection(gatt)
        assertEquals(Outcome.Ok(Unit), conn.connect(false))
        assertEquals(600L + 15_000L, testScheduler.currentTime)
        assertEquals(listOf("discover", "discover", "mtu 517"), gatt.ops.toList())
        assertEquals(1, gatt.sessions.size)
        conn.close()
    }

    @Test
    fun discoveryFailingTwiceClosesTheGattAndRetriesLater() = runTest {
        val gatt = fake().apply { discoverReplies = ArrayDeque(listOf(Reply.SILENT, Reply.SILENT)) }
        val conn = newConnection(gatt)
        assertEquals(Outcome.Fail(SwError.Timeout("discoverServices")), conn.connect(false))
        assertTrue(gatt.sessions[0].closed)
        assertEquals(PortState.DISCONNECTED, conn.state.value)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, gatt.attempts.get())
        conn.close()
    }

    @Test
    fun backoffFollowsTheSequenceAndRepeatsFiveMinutes() = runTest {
        val gatt = fake().apply { connectScript = { ConnectScript.FAIL_133 } }
        val conn = newConnection(gatt)
        assertTrue(conn.connect(false) is Outcome.Fail)
        advanceTimeBy(1_000_000)
        runCurrent()
        val expected = listOf(0L, 1_000, 3_000, 8_000, 18_000, 48_000, 108_000, 408_000, 708_000)
        assertEquals(expected, gatt.attemptTimes.take(expected.size))
        // every attempt's GATT was closed, none leaked
        assertTrue(gatt.sessions.all { it.closed })
        // reconnects use autoConnect
        assertEquals(listOf(false, true, true), gatt.autoFlags.take(3))
        conn.close()
    }

    @Test
    fun backoffResetsAfterASuccessfulConnection() = runTest {
        val gatt = fake().apply { connectScript = { n -> if (n < 3) ConnectScript.FAIL_133 else ConnectScript.SUCCEED } }
        val conn = newConnection(gatt)
        conn.connect(false)
        advanceTimeBy(8_000 + 600 + 1)
        runCurrent()
        assertEquals(4, gatt.attempts.get())
        assertEquals(PortState.CONNECTED, conn.state.value)
        val dropAt = testScheduler.currentTime
        gatt.current.drop(GattCodes.CONN_TIMEOUT)
        runCurrent()
        assertEquals(PortState.DISCONNECTED, conn.state.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(5, gatt.attempts.get())
        assertEquals(dropAt + 1_000, gatt.attemptTimes[4])
        conn.close()
    }

    @Test
    fun connectDuringBackoffRetriesAtOnce() = runTest {
        val gatt = fake().apply { connectScript = { n -> if (n < 5) ConnectScript.FAIL_133 else ConnectScript.SUCCEED } }
        val conn = newConnection(gatt)
        conn.connect(false)
        advanceTimeBy(18_000 + 1) // attempts at 0, 1, 3, 8, 18 s; next pause is 30 s
        runCurrent()
        assertEquals(5, gatt.attempts.get())
        val before = testScheduler.currentTime
        assertEquals(Outcome.Ok(Unit), conn.connect(false))
        assertEquals(before + 600, testScheduler.currentTime)
        conn.close()
    }

    @Test
    fun status133MidSessionClosesEndsPipesAndReconnects() = runTest {
        val gatt = fake()
        val conn = newConnection(gatt)
        conn.connect(false)
        val pipe = conn.pipe()
        val collected = async { pipe.incoming.toList() }
        gatt.current.notify(Ids.RX, byteArrayOf(1))
        runCurrent()
        gatt.current.drop(GattCodes.GATT_ERROR)
        runCurrent()
        assertEquals(listOf(listOf<Byte>(1)), collected.await().map { it.toList() })
        assertTrue(gatt.sessions[0].closed)
        assertEquals(PortState.DISCONNECTED, conn.state.value)
        assertTrue(pipe.write(byteArrayOf(1)) is Outcome.Fail)
        advanceTimeBy(1_000 + 600)
        runCurrent()
        assertEquals(2, gatt.sessions.size)
        assertEquals(PortState.CONNECTED, conn.state.value)
        // a late notification from the old GATT never reaches a pipe on the new one
        val newPipe = conn.pipe()
        val got = async { newPipe.incoming.take(1).toList() }
        gatt.sessions[0].notify(Ids.RX, byteArrayOf(9))
        gatt.current.notify(Ids.RX, byteArrayOf(2))
        runCurrent()
        assertEquals(listOf<Byte>(2), got.await().single().toList())
        conn.close()
    }

    @Test
    fun bluetoothOffWaitsForOnWithoutBackoff() = runTest {
        val gatt = fake()
        val env = FakeEnv()
        val conn = newConnection(gatt, env)
        conn.connect(false)
        env.adapterOn.value = false
        runCurrent()
        assertEquals(PortState.DISCONNECTED, conn.state.value)
        assertTrue(gatt.sessions[0].closed)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, gatt.attempts.get())
        env.adapterOn.value = true
        runCurrent()
        assertEquals(2, gatt.attempts.get())
        advanceTimeBy(601)
        runCurrent()
        assertEquals(PortState.CONNECTED, conn.state.value)
        conn.close()
        assertEquals(1, env.stopped)
    }

    @Test
    fun bondLostClosesWithRepairNeededAndStopsRetrying() = runTest {
        val gatt = fake()
        val env = FakeEnv()
        val conn = newConnection(gatt, env)
        conn.connect(false)
        env.bondLost.emit(Unit)
        runCurrent()
        assertEquals(PortState.CLOSED, conn.state.value)
        assertEquals(CloseReason.REPAIR_NEEDED, conn.closeReason.value)
        // garmin reads the reason through core-model's HasCloseReason, without knowing BleConnection.
        val port: app.selfwatch.model.ble.BlePort = conn
        assertEquals(CloseReason.REPAIR_NEEDED, (port as? app.selfwatch.model.ble.HasCloseReason)?.closeReason?.value)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, gatt.attempts.get())
        assertTrue(conn.connect(false) is Outcome.Fail)
    }

    @Test
    fun securityExceptionMeansPermissionMissing() = runTest {
        val gatt = fake().apply { connectScript = { ConnectScript.THROW_SECURITY } }
        val conn = newConnection(gatt)
        assertEquals(Outcome.Fail(SwError.PermissionMissing("android.permission.BLUETOOTH_CONNECT")), conn.connect(false))
        assertEquals(PortState.CLOSED, conn.state.value)
        assertEquals(CloseReason.PERMISSION_MISSING, conn.closeReason.value)
    }

    @Test
    fun directConnectTimesOutAfter30Seconds() = runTest {
        val gatt = fake().apply { connectScript = { n -> if (n == 0) ConnectScript.SILENT else ConnectScript.SUCCEED } }
        val conn = newConnection(gatt)
        assertEquals(Outcome.Fail(SwError.Timeout("connect")), conn.connect(false))
        assertEquals(30_000L, testScheduler.currentTime)
        assertTrue(gatt.sessions[0].closed)
        advanceTimeBy(1_000 + 600)
        runCurrent()
        assertEquals(PortState.CONNECTED, conn.state.value)
        conn.close()
    }

    @Test
    fun openPipeEnablesNotificationsThroughTheCccd() = runTest {
        val gatt = fake()
        val conn = newConnection(gatt)
        conn.connect(false)
        gatt.ops.clear()
        conn.pipe()
        assertEquals(listOf("notify ${Ids.RX} true", "desc ${Ids.RX} 0100"), gatt.ops.toList())
        gatt.rxProperties = BluetoothGattCharacteristic.PROPERTY_INDICATE
        gatt.ops.clear()
        assertTrue(conn.openPipe(Ids.SERVICE, Ids.RX2, Ids.TX2) is Outcome.Ok)
        assertEquals("desc ${Ids.RX2} 0200", gatt.ops.last())
        // opening the same rx twice is refused; unknown characteristics are refused
        assertTrue(conn.openPipe(Ids.SERVICE, Ids.RX, Ids.TX) is Outcome.Fail)
        assertTrue(conn.openPipe(Ids.SERVICE, Ids.RX, java.util.UUID(5, 5)) is Outcome.Fail)
        conn.close()
    }

    @Test
    fun closingAPipeDisablesNotifications() = runTest {
        val gatt = fake()
        val conn = newConnection(gatt)
        conn.connect(false)
        val pipe = conn.pipe()
        gatt.ops.clear()
        pipe.close()
        assertEquals(listOf("notify ${Ids.RX} false", "desc ${Ids.RX} 0000"), gatt.ops.toList())
        assertTrue(pipe.write(byteArrayOf(1)) is Outcome.Fail)
        assertTrue(conn.openPipe(Ids.SERVICE, Ids.RX, Ids.TX) is Outcome.Ok) // can be opened again
        conn.close()
    }

    @Test
    fun writeTypeFollowsThePipeOption() = runTest {
        val gatt = fake()
        val conn = newConnection(gatt)
        conn.connect(false)
        val withResponse = conn.pipe()
        val noResponse = (conn.openPipe(Ids.SERVICE, Ids.RX2, Ids.TX2, writeNoResponse = true) as Outcome.Ok).value
        gatt.ops.clear()
        assertEquals(Outcome.Ok(Unit), withResponse.write(byteArrayOf(0x0a)))
        assertEquals(Outcome.Ok(Unit), noResponse.write(byteArrayOf(0x0b)))
        assertEquals(listOf("write ${Ids.TX} 0a", "write ${Ids.TX2} 0b noResp"), gatt.ops.toList())
        conn.close()
    }

    @Test
    fun writesLongerThanMaxWriteAreRefused() = runTest {
        val gatt = fake().apply { mtuValue = 23 }
        val conn = newConnection(gatt)
        conn.connect(false)
        val pipe = conn.pipe()
        assertEquals(20, pipe.maxWrite)
        assertTrue((pipe.write(ByteArray(21)) as Outcome.Fail).error is SwError.Protocol)
        assertEquals(Outcome.Ok(Unit), pipe.write(ByteArray(20)))
        conn.close()
    }

    @Test
    fun writeTimeoutDropsTheLinkAndReconnects() = runTest {
        val gatt = fake()
        val conn = newConnection(gatt)
        conn.connect(false)
        val pipe = conn.pipe()
        gatt.writeReply = Reply.SILENT
        assertEquals(Outcome.Fail(SwError.Timeout("writeCharacteristic")), pipe.write(byteArrayOf(1)))
        runCurrent()
        assertTrue(gatt.sessions[0].closed)
        assertEquals(PortState.DISCONNECTED, conn.state.value)
        gatt.writeReply = Reply.OK
        advanceTimeBy(1_000 + 600)
        runCurrent()
        assertEquals(PortState.CONNECTED, conn.state.value)
        conn.close()
    }

    @Test
    fun authErrorOnUnbondedWatchBondsAndRetries() = runTest {
        val gatt = fake().apply { writeStatus = { n -> if (n == 0) GattCodes.GATT_INSUFFICIENT_AUTHENTICATION else 0 } }
        val env = FakeEnv().apply { bonded = false }
        val conn = newConnection(gatt, env)
        conn.connect(false)
        val pipe = conn.pipe()
        assertEquals(Outcome.Ok(Unit), pipe.write(byteArrayOf(7)))
        assertEquals(1, env.bondCalls.get())
        assertEquals(2, gatt.ops.count { it.startsWith("write") })
        conn.close()
    }

    @Test
    fun repeatedAuthErrorsOnABondedWatchMeanRepair() = runTest {
        val gatt = fake().apply { writeStatus = { GattCodes.GATT_INSUFFICIENT_ENCRYPTION } }
        val env = FakeEnv().apply { bonded = true }
        val conn = newConnection(gatt, env)
        conn.connect(false)
        val pipe = conn.pipe()
        repeat(3) { assertTrue(pipe.write(byteArrayOf(1)) is Outcome.Fail) }
        assertEquals(0, env.bondCalls.get())
        assertEquals(PortState.CLOSED, conn.state.value)
        assertEquals(CloseReason.REPAIR_NEEDED, conn.closeReason.value)
    }

    @Test
    fun setHighSpeedRequestsConnectionPriority() = runTest {
        val gatt = fake()
        val conn = newConnection(gatt)
        conn.setHighSpeed(true) // not connected: ignored
        conn.connect(false)
        gatt.ops.clear()
        conn.setHighSpeed(true)
        conn.setHighSpeed(false)
        assertEquals(listOf("priority high", "priority balanced"), gatt.ops.toList())
        conn.close()
    }

    @Test
    fun captureRecordsWritesAndNotificationsWithSessionPipeIndexes() = runTest {
        val dir = tmp.newFolder("blelog")
        val log = BleLog(dir, clockMs = { 1_764_000_000_000L + testScheduler.currentTime }).apply { enabled = true }
        val gatt = fake()
        val conn = newConnection(gatt, log = log)
        conn.connect(false)
        val gfdi = conn.pipe() // first pipe opened: index 0
        // a failed open does not use up an index
        assertTrue(conn.openPipe(Ids.SERVICE, Ids.RX2, java.util.UUID(5, 5)) is Outcome.Fail)
        val app = (conn.openPipe(Ids.SERVICE, Ids.RX2, Ids.TX2) as Outcome.Ok).value // index 1
        gfdi.write(byteArrayOf(0x10, 0x11))
        gatt.current.notify(Ids.RX, byteArrayOf(0x20))
        app.write(byteArrayOf(0x30))
        gatt.current.notify(Ids.RX2, byteArrayOf(0x40))
        runCurrent()
        // after a reconnect, indexes start again at 0
        gatt.current.drop(GattCodes.CONN_TIMEOUT)
        advanceTimeBy(1_000 + 600)
        runCurrent()
        conn.pipe().write(byteArrayOf(0x50))
        conn.close()
        log.close()
        val records = BleLogFormat.decode(log.files().single().readBytes()).records
        assertEquals(
            listOf(
                Triple(BleLogFormat.DIR_OUT, 0, 0x10), Triple(BleLogFormat.DIR_IN, 0, 0x20),
                Triple(BleLogFormat.DIR_OUT, 1, 0x30), Triple(BleLogFormat.DIR_IN, 1, 0x40),
                Triple(BleLogFormat.DIR_OUT, 0, 0x50),
            ),
            records.map { Triple(it.direction, it.pipe, it.bytes[0].toInt()) },
        )
        assertArrayEquals(byteArrayOf(0x10, 0x11), records[0].bytes)
    }

    /** 10 000 notifications fired from another thread arrive complete and in order (real threads). */
    @Test
    fun notificationsUnderLoadArriveInOrderWithoutLoss() = runBlocking {
        val gatt = FakeGatt()
        val dir = tmp.newFolder("load")
        val log = BleLog(dir).apply { enabled = true }
        val conn = BleConnection(address, log, gatt, FakeEnv(), Dispatchers.IO.limitedParallelism(1), BleTiming(discoveryDelayMs = 0))
        assertEquals(Outcome.Ok(Unit), conn.connect(false))
        val pipe = conn.openPipe(Ids.SERVICE, Ids.RX, Ids.TX).valueOrNull()!!
        val n = 10_000
        val received = async(Dispatchers.Default) { pipe.incoming.take(n).toList() }
        val producer = Thread {
            for (i in 0 until n) gatt.current.notify(Ids.RX, ByteBuffer.allocate(4).putInt(i).array())
        }
        producer.start()
        val got = withTimeout(30_000) { received.await() }
        producer.join()
        assertEquals((0 until n).toList(), got.map { ByteBuffer.wrap(it).int })
        conn.close()
        log.close()
        val ins = BleLogFormat.decode(log.files().single().readBytes()).records.filter { it.direction == BleLogFormat.DIR_IN }
        assertEquals((0 until n).toList(), ins.map { ByteBuffer.wrap(it.bytes).int })
    }

    /** Writes from many coroutines with callbacks on other threads: never two GATT operations at once. */
    @Test
    fun concurrentWritesKeepOneOperationInFlight() = runBlocking {
        val binder = java.util.concurrent.Executors.newFixedThreadPool(3)
        val gatt = FakeGatt(deliver = { cb -> binder.execute { Thread.sleep(0, 200_000); cb() } })
        val conn = BleConnection(address, null, gatt, FakeEnv(), Dispatchers.IO.limitedParallelism(1), BleTiming(discoveryDelayMs = 0))
        assertEquals(Outcome.Ok(Unit), conn.connect(false))
        val pipe = conn.openPipe(Ids.SERVICE, Ids.RX, Ids.TX).valueOrNull()!!
        val results = (0 until 300).map { i -> async(Dispatchers.Default) { pipe.write(byteArrayOf(i.toByte())) } }
        results.forEach { assertEquals(Outcome.Ok(Unit), it.await()) }
        assertEquals(1, gatt.maxInFlight.get())
        assertEquals(300, gatt.ops.count { it.startsWith("write") })
        conn.close()
        binder.shutdown()
    }

    @Test
    fun closedConnectionRefusesEverything() = runTest {
        val conn = newConnection(fake())
        conn.close()
        conn.close() // idempotent
        assertTrue(conn.connect(false) is Outcome.Fail)
        assertTrue(conn.openPipe(Ids.SERVICE, Ids.RX, Ids.TX) is Outcome.Fail)
        assertFalse(conn.state.value == PortState.CONNECTED)
    }
}
