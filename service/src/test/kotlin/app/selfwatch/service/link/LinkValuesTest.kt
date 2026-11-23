/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import app.selfwatch.link.client.LinkCodec
import app.selfwatch.link.client.LinkStatus
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** LinkValues ↔ CiqValue ↔ LinkCodec round trips and JSON conversions. */
class LinkValuesTest {
    private val sample: CiqValue = CiqValue.Dict(
        linkedMapOf(
            CiqValue.Str("n") to CiqValue.Null,
            CiqValue.Str("b") to CiqValue.Bool(true),
            CiqValue.Str("i") to CiqValue.Num(-42),
            CiqValue.Str("big") to CiqValue.Num(Long.MAX_VALUE),
            CiqValue.Str("d") to CiqValue.Dbl(3.25),
            CiqValue.Str("s") to CiqValue.Str("vívoactive ⛵ 5"),
            CiqValue.Str("list") to CiqValue.Arr(listOf(CiqValue.Num(1), CiqValue.Arr(listOf(CiqValue.Str("x"))))),
            CiqValue.Num(7) to CiqValue.Bool(false),
            CiqValue.Null to CiqValue.Str("null key"),
        ),
    )

    @Test
    fun ciqThroughLinkCodecAndBack() {
        val bytes = LinkCodec.encode(LinkValues.fromCiq(sample))
        assertEquals(sample, LinkValues.toCiq(LinkCodec.decode(bytes)))
        for (v in listOf(CiqValue.Null, CiqValue.Bool(false), CiqValue.Num(0), CiqValue.Dbl(-0.5), CiqValue.Str(""), CiqValue.Arr(emptyList()))) {
            assertEquals(v, LinkValues.toCiq(LinkCodec.decode(LinkCodec.encode(LinkValues.fromCiq(v)))))
        }
    }

    @Test
    fun javaTypesWidenAsTheTableSays() {
        assertEquals(CiqValue.Num(3), LinkValues.toCiq(3))
        assertEquals(CiqValue.Num(3), LinkValues.toCiq(3.toShort()))
        assertEquals(CiqValue.Num(3), LinkValues.toCiq(3.toByte()))
        assertEquals(CiqValue.Dbl(1.5), LinkValues.toCiq(1.5f))
        assertEquals(CiqValue.Str("c"), LinkValues.toCiq('c'))
        assertEquals(CiqValue.Str("sb"), LinkValues.toCiq(StringBuilder("sb")))
        try {
            LinkValues.toCiq(Any())
            throw AssertionError("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unsupported"))
        }
    }

    @Test
    fun containerKeysAreDropped() {
        val v = CiqValue.Dict(mapOf(CiqValue.Dict(emptyMap()) to CiqValue.Num(1), CiqValue.Str("ok") to CiqValue.Num(2)))
        assertEquals(linkedMapOf<Any?, Any?>("ok" to 2L), LinkValues.fromCiq(v))
    }

    @Test
    fun jsonConversions() {
        val j = (Json.parse("""{"a":1,"b":2.5,"c":"t","d":[true,null],"e":1e3,"f":{"g":-7}}""") as Outcome.Ok<JsonValue>).value
        val c = LinkValues.fromJson(j) as CiqValue.Dict
        assertEquals(CiqValue.Num(1), c.v[CiqValue.Str("a")])
        assertEquals(CiqValue.Dbl(2.5), c.v[CiqValue.Str("b")])
        assertEquals(CiqValue.Dbl(1000.0), c.v[CiqValue.Str("e")])
        assertEquals(CiqValue.Arr(listOf(CiqValue.Bool(true), CiqValue.Null)), c.v[CiqValue.Str("d")])
        assertEquals("""{"a":1,"b":2.5,"c":"t","d":[true,null],"e":1000.0,"f":{"g":-7}}""", LinkValues.toJsonText(c))
        assertEquals("""{"7":false}""", LinkValues.toJsonText(CiqValue.Dict(mapOf(CiqValue.Num(7) to CiqValue.Bool(false)))))
        assertEquals("null", LinkValues.toJsonText(CiqValue.Dbl(Double.NaN)))
    }

    @Test
    fun gadgetbridgePayloads() {
        assertEquals(CiqValue.Dict(mapOf(CiqValue.Str("cmd") to CiqValue.Str("go"))), LinkValues.gadgetbridgePayload("""{"cmd":"go"}"""))
        assertEquals(CiqValue.Arr(listOf(CiqValue.Num(1), CiqValue.Num(2))), LinkValues.gadgetbridgePayload("[1,2]"))
        assertEquals(CiqValue.Str("hello watch"), LinkValues.gadgetbridgePayload("hello watch"))
        assertEquals(CiqValue.Num(5), LinkValues.gadgetbridgePayload("5"))
        assertEquals(CiqValue.Str("{broken"), LinkValues.gadgetbridgePayload("{broken"))
    }

    @Test
    fun settingsJson() {
        val values = mapOf("theme" to CiqValue.Num(2), "dim" to CiqValue.Bool(false), "name" to CiqValue.Str("Phil"))
        val text = LinkValues.settingsToJson(values)
        assertEquals("""{"theme":2,"dim":false,"name":"Phil"}""", text)
        assertEquals(values, LinkValues.settingsFromJson(text))
        assertEquals(null, LinkValues.settingsFromJson("[1]"))
    }
}

/** F06: the send queue (one in flight, retry once on timeout, result mapping). */
class SendQueueTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app = UUID.randomUUID()

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun statusMapping() {
        assertEquals(LinkStatus.SUCCESS, SendQueue.statusOf(Outcome.Ok(Unit)))
        assertEquals(LinkStatus.NOT_CONNECTED, SendQueue.statusOf(Outcome.Fail(SwError.NotConnected(1))))
        assertEquals(LinkStatus.TOO_LARGE, SendQueue.statusOf(Outcome.Fail(SwError.BadInput("app message too large: 300 > 266 bytes"))))
        assertEquals(LinkStatus.NOT_INSTALLED, SendQueue.statusOf(Outcome.Fail(SwError.BadInput("app x is not installed"))))
        assertEquals(LinkStatus.FAILED, SendQueue.statusOf(Outcome.Fail(SwError.Timeout("ack"))))
        assertEquals(LinkStatus.FAILED, SendQueue.statusOf(Outcome.Fail(SwError.Protocol("refused"))))
    }

    @Test
    fun oneInFlightInOrder() {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val order = CopyOnWriteArrayList<Long>()
        val q = SendQueue(scope, { _, cmd ->
            val n = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            delay(5)
            order.add(((cmd as WatchCommand.AppMessage).payload as CiqValue.Num).v)
            inFlight.decrementAndGet()
            Outcome.Ok(Unit)
        })
        val results = CopyOnWriteArrayList<Int>()
        for (i in 0L until 5L) assertTrue(q.enqueue(1, app, CiqValue.Num(i)) { results.add(it) })
        awaitSize(results, 5)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), order.toList())
        assertEquals(1, maxInFlight.get())
        assertTrue(results.all { it == LinkStatus.SUCCESS })
    }

    @Test
    fun fullQueueIsRefused() {
        val started = java.util.concurrent.CountDownLatch(1)
        val q = SendQueue(scope, { _, _ ->
            started.countDown()
            delay(10_000)
            Outcome.Ok(Unit)
        }, capacity = 2)
        assertTrue(q.enqueue(1, app, CiqValue.Null) {})
        // The first one is in flight; two more may wait, the next is refused.
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(q.enqueue(1, app, CiqValue.Null) {})
        assertTrue(q.enqueue(1, app, CiqValue.Null) {})
        assertFalse(q.enqueue(1, app, CiqValue.Null) {})
        // Another watch app has its own queue.
        assertTrue(q.enqueue(1, UUID.randomUUID(), CiqValue.Null) {})
    }

    @Test
    fun retriesOnceOnTimeoutOnly() {
        val calls = AtomicInteger()
        val q = SendQueue(scope, { _, _ ->
            calls.incrementAndGet()
            Outcome.Fail(SwError.NotConnected(1))
        })
        val results = CopyOnWriteArrayList<Int>()
        q.enqueue(1, app, CiqValue.Null) { results.add(it) }
        awaitSize(results, 1)
        assertEquals(LinkStatus.NOT_CONNECTED, results[0])
        assertEquals(1, calls.get())
    }

    private fun awaitSize(list: List<*>, n: Int) {
        val end = System.currentTimeMillis() + 5_000
        while (list.size < n && System.currentTimeMillis() < end) Thread.sleep(5)
        assertEquals(n, list.size)
    }
}
