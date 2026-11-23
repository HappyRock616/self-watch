/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import android.content.Intent
import app.selfwatch.link.client.LinkStatus
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.service.ServiceDbTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** F07: Gadgetbridge Intent parsing, the permission paths and replies to the approved package. */
class GadgetbridgeIntentTest : ServiceDbTest() {
    private val uuid = UUID.fromString("a3421fee-d289-106a-538c-b9547ab12095")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watches = FakeWatches()
    private val prompt = FakePrompt()
    private val broadcaster = RecordingBroadcaster()
    private var deviceId = 0L
    private lateinit var router: LinkRouter
    private lateinit var perms: LinkPermissions
    private lateinit var handler: GadgetbridgeHandler

    @Before
    fun setUp() {
        deviceId = addDevice("AA:BB:CC:DD:EE:01")
        router = LinkRouter(db, clock, scope, { watches }, { MessageSizer.ESTIMATE }, broadcaster)
        perms = LinkPermissions(app.packageManager, db.links, clock, prompt)
        handler = GadgetbridgeHandler(db, perms, router)
        watches.set(device("AA:BB:CC:DD:EE:01").copy(id = deviceId), ConnectionState.Ready)
        installSigned(app, "com.example.gbapp", 10_600, byteArrayOf(4, 4))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun intent(payload: String?, appId: String? = uuid.toString(), device: String? = "AA:BB:CC:DD:EE:01"): Intent {
        val i = Intent(GadgetbridgeIntent.ACTION)
        appId?.let { i.putExtra(GadgetbridgeIntent.EXTRA_APP_ID, it) }
        payload?.let { i.putExtra(GadgetbridgeIntent.EXTRA_PAYLOAD, it) }
        device?.let { i.putExtra(GadgetbridgeIntent.EXTRA_DEVICE, it) }
        return i
    }

    @Test
    fun parsesJsonTextAndRejectsBadIntents() {
        val json = GadgetbridgeIntent.parse(intent("""{"wind":12,"dir":"NE"}""")) ?: throw AssertionError()
        assertEquals("AA:BB:CC:DD:EE:01", json.deviceAddress)
        assertEquals(uuid, json.appUuid)
        assertEquals(CiqValue.Dict(mapOf(CiqValue.Str("wind") to CiqValue.Num(12), CiqValue.Str("dir") to CiqValue.Str("NE"))), json.payload)
        assertEquals(CiqValue.Str("start race"), GadgetbridgeIntent.parse(intent("start race"))?.payload)
        assertNull(GadgetbridgeIntent.parse(intent("x", device = null))?.deviceAddress)
        assertNull(GadgetbridgeIntent.parse(intent(null)))
        assertNull(GadgetbridgeIntent.parse(intent("x", appId = "nope")))
        assertNull(GadgetbridgeIntent.parse(intent("x", appId = null)))
        assertNull(GadgetbridgeIntent.parse(Intent("other.action").putExtra(GadgetbridgeIntent.EXTRA_APP_ID, uuid.toString())))
    }

    @Test
    fun unknownSenderNeedsTheUnknownAppApproval() = runBlocking {
        val msg = GadgetbridgeIntent.parse(intent("""[1,2]""")) ?: throw AssertionError()
        assertEquals(LinkStatus.PENDING_APPROVAL, handler.handle(msg, null))
        assertEquals(ApprovalRequest(null, uuid, ""), prompt.asked.single())
        perms.decide(LinkPermissions.UNKNOWN_PACKAGE, uuid, "", true)
        assertEquals(LinkStatus.SUCCESS, handler.handle(msg, null))
        awaitSent(1)
        assertEquals(deviceId to WatchCommand.AppMessage(uuid, CiqValue.Arr(listOf(CiqValue.Num(1), CiqValue.Num(2)))), watches.sent.single())
    }

    @Test
    fun namedSenderIsCheckedByPackageAndGetsReplies() = runBlocking {
        val msg = GadgetbridgeIntent.parse(intent("ping", device = null)) ?: throw AssertionError()
        assertEquals(LinkStatus.PENDING_APPROVAL, handler.handle(msg, "com.example.gbapp"))
        perms.decide("com.example.gbapp", uuid, digestOf(byteArrayOf(4, 4)), true)
        assertEquals(LinkStatus.SUCCESS, handler.handle(msg, "com.example.gbapp"))
        awaitSent(1)
        // The watch app answers; Gadgetbridge dropped this, Self Watch broadcasts it to the approved package.
        router.deliver(deviceId, uuid, CiqValue.Str("pong"))
        val b = broadcaster.sent.single()
        assertEquals("com.example.gbapp", b.pkg)
        assertEquals("\"pong\"", b.json)
    }

    @Test
    fun deniedAndUnknownWatch() = runBlocking {
        perms.decide(LinkPermissions.UNKNOWN_PACKAGE, uuid, "", false)
        val msg = GadgetbridgeIntent.parse(intent("x")) ?: throw AssertionError()
        assertEquals(LinkStatus.DENIED, handler.handle(msg, null))
        perms.decide(LinkPermissions.UNKNOWN_PACKAGE, uuid, "", true)
        val elsewhere = GadgetbridgeIntent.parse(intent("x", device = "11:22:33:44:55:66")) ?: throw AssertionError()
        assertEquals(LinkStatus.NOT_CONNECTED, handler.handle(elsewhere, null))
    }

    private fun awaitSent(n: Int) {
        val end = System.currentTimeMillis() + 5_000
        while (watches.sent.size < n && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(n, watches.sent.size)
    }
}
