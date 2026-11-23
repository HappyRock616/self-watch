/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.ble.gatt

import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GattQueueTest {
    private val service = UUID(1, 1)

    /** An op completed by a CharacteristicWritten event for characteristic UUID(0, id). */
    private fun op(id: Int, started: MutableList<Int>, timeoutMs: Long = 10_000, fatal: Boolean = true, onStart: () -> Unit = {}) =
        GattOp(
            name = "op$id",
            timeoutMs = timeoutMs,
            fatalOnTimeout = fatal,
            start = { started += id; onStart(); GattApi.STATUS_OK },
            complete = { e ->
                (e as? GattEvent.CharacteristicWritten)?.takeIf { it.characteristic == UUID(0, id.toLong()) }
                    ?.let { if (it.status == 0) Outcome.Ok(id) else Outcome.Fail(SwError.Protocol("status ${it.status}")) }
            },
        )

    private fun written(id: Int, status: Int = 0) = GattEvent.CharacteristicWritten(service, UUID(0, id.toLong()), status)

    @Test
    fun operationsStartInSubmissionOrderOneAtATime() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val queue = GattQueue(scope) { }
        queue.start()
        val started = Collections.synchronizedList(ArrayList<Int>())
        val results = (0 until 50).map { i ->
            scope.async(start = CoroutineStart.UNDISPATCHED) { queue.submit(op(i, started)) }
        }
        for (i in 0 until 50) {
            runCurrent()
            // exactly one more op started, and it is the next one
            assertEquals((0..i).toList(), started.toList())
            // a callback for a later op is not taken by the current one
            assertEquals(false, queue.onEvent(written(i + 1)))
            assertTrue(queue.onEvent(written(i)))
        }
        runCurrent()
        assertEquals((0 until 50).map { Outcome.Ok(it) }, results.awaitAll())
        scope.cancel()
    }

    @Test
    fun timeoutFailsTheOpReportsItAndMovesOn() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val timeouts = mutableListOf<String>()
        val queue = GattQueue(scope) { timeouts += it }
        queue.start()
        val started = mutableListOf<Int>()
        val first = scope.async { queue.submit(op(1, started)) }
        val second = scope.async { queue.submit(op(2, started, fatal = false, timeoutMs = 500)) }
        runCurrent()
        assertEquals(listOf(1), started)
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(false, first.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(Outcome.Fail(SwError.Timeout("op1")), first.await())
        assertEquals(listOf("op1"), timeouts)
        assertEquals(listOf(1, 2), started)
        // a late callback for op1 is ignored
        assertEquals(false, queue.onEvent(written(1)))
        advanceTimeBy(501)
        runCurrent()
        assertEquals(Outcome.Fail(SwError.Timeout("op2")), second.await())
        assertEquals(listOf("op1"), timeouts) // op2 was not fatal
        scope.cancel()
    }

    @Test
    fun startFailureAndSecurityExceptionFailWithoutWaiting() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val queue = GattQueue(scope) { }
        queue.start()
        val notStarted = GattOp<Unit>("w", 10_000, start = { GattApi.STATUS_NOT_STARTED }, complete = { null })
        val denied = GattOp<Unit>("d", 10_000, start = { throw SecurityException("x") }, complete = { null })
        val a = scope.async { queue.submit(notStarted) }
        val b = scope.async { queue.submit(denied) }
        runCurrent()
        assertTrue((a.await() as Outcome.Fail).error is SwError.Io)
        assertEquals(Outcome.Fail(SwError.PermissionMissing("android.permission.BLUETOOTH_CONNECT")), b.await())
        assertEquals(0L, testScheduler.currentTime)
        scope.cancel()
    }

    @Test
    fun closeFailsInFlightAndQueuedAndLaterSubmits() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val queue = GattQueue(scope) { }
        queue.start()
        val started = mutableListOf<Int>()
        val a = scope.async { queue.submit(op(1, started)) }
        val b = scope.async { queue.submit(op(2, started)) }
        runCurrent()
        queue.close(SwError.Io("link lost"))
        runCurrent()
        assertEquals(Outcome.Fail(SwError.Io("link lost")), a.await())
        assertEquals(Outcome.Fail(SwError.Io("link lost")), b.await())
        assertEquals(Outcome.Fail(SwError.Io("link lost")), queue.submit(op(3, started)))
        assertEquals(listOf(1), started)
        scope.cancel()
    }

    @Test
    fun cancelledCallerInFlightStillBlocksTheNextOpUntilItsCallback() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val queue = GattQueue(scope) { }
        queue.start()
        val started = mutableListOf<Int>()
        val a = scope.launch { queue.submit(op(1, started)) }
        val b = scope.async { queue.submit(op(2, started)) }
        runCurrent()
        a.cancel()
        runCurrent()
        assertEquals(listOf(1), started) // op2 must not overlap op1
        assertTrue(queue.onEvent(written(1)))
        runCurrent()
        assertEquals(listOf(1, 2), started)
        queue.onEvent(written(2))
        runCurrent()
        assertEquals(Outcome.Ok(2), b.await())
        scope.cancel()
    }

    @Test
    fun callbacksFromOtherThreadsKeepOneInFlight() = runBlocking {
        val single = Dispatchers.IO.limitedParallelism(1)
        val scope = CoroutineScope(SupervisorJob() + single)
        val queue = GattQueue(scope) { }
        queue.start()
        val binder = Executors.newFixedThreadPool(4)
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val started = Collections.synchronizedList(ArrayList<Int>())
        val n = 500
        val results = (0 until n).map { i ->
            async(Dispatchers.Default) {
                queue.submit(
                    op(i, started) {
                        maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { x, y -> maxOf(x, y) }
                        binder.execute {
                            Thread.sleep(0, (i % 7) * 1000)
                            inFlight.decrementAndGet()
                            // the callback arrives on a Binder-like thread and hops onto the queue's dispatcher
                            scope.launch { queue.onEvent(written(i)) }
                        }
                    },
                )
            }
        }.awaitAll()
        assertEquals(1, maxInFlight.get())
        assertEquals((0 until n).map { Outcome.Ok(it) }.toSet(), results.toSet())
        assertEquals(n, started.toSet().size)
        withContext(Dispatchers.Default) { binder.shutdown(); binder.awaitTermination(5, TimeUnit.SECONDS) }
        scope.cancel()
    }
}
