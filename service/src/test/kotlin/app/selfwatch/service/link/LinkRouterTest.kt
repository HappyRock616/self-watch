/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import app.selfwatch.db.dao.LinkPermission
import app.selfwatch.db.dao.WatchAppRow
import app.selfwatch.link.client.LinkCodec
import app.selfwatch.link.client.LinkStatus
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.service.ServiceDbTest
import app.selfwatch.service.device.WatchLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.shadows.ShadowBinder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** F06: delivery to callbacks vs broadcast + inbox, the send checks and queue, revokes, devices and app info. */
class LinkRouterTest : ServiceDbTest() {
    private val uuid = UUID.fromString("0d2e1b9a-1c3d-4e5f-8a9b-0c1d2e3f4a5b")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watches = FakeWatches()
    private val broadcaster = RecordingBroadcaster()
    private var deviceId = 0L
    private lateinit var router: LinkRouter

    @Before
    fun setUp() {
        deviceId = addDevice()
        router = LinkRouter(db, clock, scope, { watches }, { MessageSizer.ESTIMATE }, broadcaster)
        runBlocking { db.links.set(LinkPermission("com.example.tcs", uuid, "c", true, clock.nowSec(), null)) }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun connect() = watches.set(device("AA:BB:CC:DD:EE:01").copy(id = deviceId), ConnectionState.Ready)

    private val link = object : WatchLink {
        override val deviceId: Long get() = this@LinkRouterTest.deviceId

        override suspend fun send(cmd: WatchCommand): Outcome<Unit> = Outcome.Ok(Unit)
    }

    private val msg = CiqValue.Dict(mapOf(CiqValue.Str("speed") to CiqValue.Dbl(6.5), CiqValue.Str("tack") to CiqValue.Num(2)))

    @Test
    fun watchMessageGoesToTheRegisteredCallback() = runBlocking {
        val cb = RecordingCallback()
        router.register(uuid, "com.example.tcs", 10_123, cb)
        router.onAppMessage(link, WatchEvent.AppMessageReceived(uuid, msg))
        val (dev, app, bytes) = cb.messages.single()
        assertEquals(deviceId, dev)
        assertEquals(uuid.toString(), app)
        assertEquals(msg, LinkValues.toCiq(LinkCodec.decode(bytes)))
        assertTrue(broadcaster.sent.isEmpty())
        assertEquals(0, router.inboxSize(uuid))
    }

    @Test
    fun withoutCallbackItIsBroadcastAndKeptForLater() = runBlocking {
        router.onAppMessage(link, WatchEvent.AppMessageReceived(uuid, msg))
        val b = broadcaster.sent.single()
        assertEquals("com.example.tcs", b.pkg)
        assertEquals(deviceId, b.deviceId)
        assertEquals(msg, LinkValues.toCiq(LinkCodec.decode(b.message)))
        assertEquals("""{"speed":6.5,"tack":2}""", b.json)
        assertEquals(1, router.inboxSize(uuid))
        // The app binds later: the kept message is delivered once.
        val cb = RecordingCallback()
        router.register(uuid, "com.example.tcs", 10_123, cb)
        assertEquals(1, cb.messages.size)
        assertEquals(0, router.inboxSize(uuid))
    }

    @Test
    fun inboxKeepsTwentyForTenMinutes() = runBlocking {
        repeat(25) { router.deliver(deviceId, uuid, CiqValue.Num(it.toLong())) }
        assertEquals(LinkRouter.INBOX_MAX, router.inboxSize(uuid))
        clock.advance(LinkRouter.INBOX_TTL_SEC + 1)
        assertEquals(0, router.inboxSize(uuid))
        router.deliver(deviceId, uuid, CiqValue.Num(99))
        val cb = RecordingCallback()
        router.register(uuid, "com.example.tcs", 10_123, cb)
        assertEquals(listOf<Any?>(99L), cb.messages.map { LinkCodec.decode(it.third) })
    }

    @Test
    fun deniedOrUnknownAppsGetNoBroadcast() = runBlocking {
        val other = UUID.randomUUID()
        db.links.set(LinkPermission("com.example.bad", other, "c", false, clock.nowSec(), null))
        db.links.set(LinkPermission(LinkPermissions.UNKNOWN_PACKAGE, other, "", true, clock.nowSec(), null))
        router.deliver(deviceId, other, CiqValue.Str("x"))
        assertTrue(broadcaster.sent.isEmpty())
        assertEquals(1, router.inboxSize(other))
    }

    @Test
    fun deadCallbackFallsBackToBroadcast() = runBlocking {
        val dead = object : app.selfwatch.link.ILinkCallback.Stub() {
            override fun onMessage(deviceId: Long, appUuid: String?, message: ByteArray?) = throw android.os.RemoteException("gone")
            override fun onSendResult(requestId: Int, status: Int) {}
            override fun onDeviceStatus(deviceId: Long, connected: Boolean) {}
        }
        router.register(uuid, "com.example.tcs", 10_123, dead)
        router.deliver(deviceId, uuid, CiqValue.Bool(true))
        assertNull(router.registration(uuid))
        assertEquals(1, broadcaster.sent.size)
    }

    @Test
    fun registrationFollowsDeathAndUnregister() {
        val cb = RecordingCallback()
        router.register(uuid, "com.example.tcs", 10_123, cb)
        // Only the owner may unregister.
        router.unregister(uuid, 999)
        assertEquals(cb, router.registration(uuid)?.callback)
        router.unregister(uuid, 10_123)
        assertNull(router.registration(uuid))
        router.register(uuid, "com.example.tcs", 10_123, cb)
        val recipients = org.robolectric.shadow.api.Shadow.extract<ShadowBinder>(cb).deathRecipients
        assertEquals(1, recipients.size)
        recipients[0].binderDied()
        assertNull(router.registration(uuid))
    }

    @Test
    fun revokeUnregisters() = runBlocking {
        router.register(uuid, "com.example.tcs", 10_123, RecordingCallback())
        db.links.delete("com.example.tcs", uuid)
        router.dropRevoked()
        assertNull(router.registration(uuid))
    }

    @Test
    fun waitingRegistrationStartsWhenAllowed() = runBlocking {
        val app2 = UUID.randomUUID()
        val cb = RecordingCallback()
        router.registerWhenApproved(app2, "com.example.new", 10_400, cb)
        router.dropRevoked()
        assertNull(router.registration(app2))
        db.links.set(LinkPermission("com.example.new", app2, "c", true, clock.nowSec(), null))
        router.dropRevoked()
        assertEquals(cb, router.registration(app2)?.callback)
    }

    @Test
    fun sendChecksAndQueues() = runBlocking {
        val results = CopyOnWriteArrayList<Int>()
        val ok = LinkCodec.encode(mapOf("go" to true))
        assertEquals(LinkStatus.INVALID_MESSAGE, router.send(deviceId, uuid, byteArrayOf(1, 0x55)) { results.add(it) })
        assertEquals(LinkStatus.NOT_CONNECTED, router.send(deviceId, uuid, ok) { results.add(it) })
        connect()
        assertEquals(LinkStatus.TOO_LARGE, router.send(deviceId, uuid, LinkCodec.encode("x".repeat(300))) { results.add(it) })
        assertEquals(LinkStatus.SUCCESS, router.send(deviceId, uuid, ok) { results.add(it) })
        awaitSize(results, 1)
        assertEquals(listOf(LinkStatus.SUCCESS), results.toList())
        assertEquals(WatchCommand.AppMessage(uuid, CiqValue.Dict(mapOf(CiqValue.Str("go") to CiqValue.Bool(true)))), watches.sent.single().second)
        // A watch app the watch listed as removed.
        db.watchApps.upsert(WatchAppRow(deviceId, uuid, "TCS", 3, 1, false, clock.nowSec()))
        assertEquals(LinkStatus.NOT_INSTALLED, router.send(deviceId, uuid, ok) { results.add(it) })
    }

    @Test
    fun timeoutIsRetriedOnceThenFails() = runBlocking {
        connect()
        val results = CopyOnWriteArrayList<Int>()
        val ok = LinkCodec.encode(1L)
        synchronized(watches.answers) {
            watches.answers.addLast(Outcome.Fail(SwError.Timeout("ack")))
            watches.answers.addLast(Outcome.Ok(Unit))
        }
        router.send(deviceId, uuid, ok) { results.add(it) }
        awaitSize(results, 1)
        assertEquals(LinkStatus.SUCCESS, results[0])
        assertEquals(2, watches.sent.size)
        synchronized(watches.answers) {
            watches.answers.addLast(Outcome.Fail(SwError.Timeout("ack")))
            watches.answers.addLast(Outcome.Fail(SwError.Timeout("ack")))
        }
        router.send(deviceId, uuid, ok) { results.add(it) }
        awaitSize(results, 2)
        assertEquals(LinkStatus.FAILED, results[1])
        assertEquals(4, watches.sent.size)
    }

    @Test
    fun openAppSendsLaunch() = runBlocking {
        assertEquals(LinkStatus.NOT_CONNECTED, router.openApp(deviceId, uuid))
        connect()
        assertEquals(LinkStatus.SUCCESS, router.openApp(deviceId, uuid))
        val end = System.currentTimeMillis() + 5_000
        while (watches.sent.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(WatchCommand.LaunchApp(uuid), watches.sent.single().second)
    }

    @Test
    fun devicesAndAppInfoJson() = runBlocking {
        val d = Json.parse(router.devicesJson().single()) as Outcome.Ok<JsonValue>
        assertEquals(deviceId, d.value["id"]?.asLong)
        assertEquals("vivoactive 5", d.value["name"]?.asString)
        assertEquals(false, d.value["connected"]?.asBoolean)
        assertEquals("""{"error":4}""", router.appInfoJson(deviceId, uuid))
        connect()
        assertEquals(true, (Json.parse(router.devicesJson().single()) as Outcome.Ok<JsonValue>).value["connected"]?.asBoolean)
        assertEquals("""{"installed":false}""", router.appInfoJson(deviceId, uuid))
        db.watchApps.upsert(WatchAppRow(deviceId, uuid, "TCS", 12, 1, true, clock.nowSec()))
        assertEquals("""{"installed":true,"version":12}""", router.appInfoJson(deviceId, uuid))
    }

    @Test
    fun deviceStatusReachesCallbacks() {
        val cb = RecordingCallback()
        router.register(uuid, "com.example.tcs", 10_123, cb)
        val access = MutableStateFlow<WatchAccess?>(watches)
        router.watchDeviceStatus(access)
        connect()
        val end = System.currentTimeMillis() + 5_000
        while (cb.statuses.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(deviceId to true, cb.statuses.first())
        watches.set(device("AA:BB:CC:DD:EE:01").copy(id = deviceId), ConnectionState.Disconnected)
        while (cb.statuses.size < 2 && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(deviceId to false, cb.statuses[1])
    }

    @Test
    fun containerKeysAreDroppedForPhoneApps() = runBlocking {
        val cb = RecordingCallback()
        router.register(uuid, "com.example.tcs", 10_123, cb)
        val v = CiqValue.Dict(mapOf(CiqValue.Arr(emptyList()) to CiqValue.Num(1), CiqValue.Str("k") to CiqValue.Num(2)))
        router.deliver(deviceId, uuid, v)
        assertArrayEquals(LinkCodec.encode(mapOf("k" to 2L)), cb.messages.single().third)
    }

    private fun awaitSize(list: List<*>, n: Int) {
        val end = System.currentTimeMillis() + 5_000
        while (list.size < n && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(n, list.size)
    }
}
