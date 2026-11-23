/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import android.content.Intent
import app.selfwatch.link.client.LinkCodec
import app.selfwatch.link.client.LinkStatus
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.service.ServiceDbTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowBinder
import java.util.UUID

/** F05: the binder end to end, with the calling uid from Robolectric's Binder shadow. */
class LinkBinderTest : ServiceDbTest() {
    private val uuid = UUID.fromString("6b2f0c1e-0000-4000-8000-00000000abcd")
    private val cert = byteArrayOf(5, 4, 3)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watches = FakeWatches()
    private val prompt = FakePrompt()
    private var deviceId = 0L
    private lateinit var router: LinkRouter
    private lateinit var perms: LinkPermissions
    private lateinit var binder: LinkBinder

    @Before
    fun setUp() {
        deviceId = addDevice()
        installSigned(app, "com.example.tcs", 10_500, cert)
        router = LinkRouter(db, clock, scope, { watches }, { MessageSizer.ESTIMATE }, RecordingBroadcaster())
        perms = LinkPermissions(app.packageManager, db.links, clock, prompt)
        binder = LinkBinder(db, perms, router)
        ShadowBinder.setCallingUid(10_500)
        watches.set(device("AA:BB:CC:DD:EE:01").copy(id = deviceId), ConnectionState.Ready)
    }

    @After
    fun tearDown() {
        ShadowBinder.reset()
        scope.cancel()
    }

    @Test
    fun pendingThenAllowedThenRevoked() = runBlocking {
        val msg = LinkCodec.encode(listOf(1L, "two"))
        val cb = RecordingCallback()
        assertEquals(1, binder.apiVersion())
        binder.register(uuid.toString(), cb)
        assertEquals(LinkStatus.PENDING_APPROVAL, binder.send(deviceId, uuid.toString(), msg, 1))
        assertEquals("""{"error":6}""", binder.appInfo(deviceId, uuid.toString()))
        assertEquals(1, prompt.asked.size)
        assertNull(router.registration(uuid))

        // The user taps Allow: the waiting callback is registered.
        perms.decide("com.example.tcs", uuid, digestOf(cert), true)
        // The router follows link_permission changes by itself (no explicit call needed).
        val until = System.currentTimeMillis() + 5_000
        while (router.registration(uuid) == null && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(cb, router.registration(uuid)?.callback)
        assertEquals(LinkStatus.SUCCESS, binder.send(deviceId, uuid.toString(), msg, 2))
        val end = System.currentTimeMillis() + 5_000
        while (cb.results.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(2 to LinkStatus.SUCCESS, cb.results.single())
        assertEquals("""{"installed":false}""", binder.appInfo(deviceId, uuid.toString()))
        assertEquals(LinkStatus.SUCCESS, binder.openApp(deviceId, uuid.toString()))

        // Revoked from the Linked phone apps screen.
        db.links.delete("com.example.tcs", uuid)
        router.dropRevoked()
        assertNull(router.registration(uuid))
        assertEquals(LinkStatus.PENDING_APPROVAL, binder.send(deviceId, uuid.toString(), msg, 3))
    }

    @Test
    fun deniedAndMismatchAndOtherUid() = runBlocking {
        perms.decide("com.example.tcs", uuid, digestOf(cert), false)
        assertEquals(LinkStatus.DENIED, binder.send(deviceId, uuid.toString(), LinkCodec.encode(1L), 1))
        assertEquals(LinkStatus.DENIED, binder.openApp(deviceId, uuid.toString()))
        // A look-alike with the same package name but another key.
        perms.decide("com.example.tcs", uuid, digestOf(byteArrayOf(0)), true)
        assertEquals(LinkStatus.DENIED, binder.send(deviceId, uuid.toString(), LinkCodec.encode(1L), 2))
        // An uid without packages.
        ShadowBinder.setCallingUid(42)
        assertEquals(LinkStatus.DENIED, binder.send(deviceId, uuid.toString(), LinkCodec.encode(1L), 3))
    }

    @Test
    fun badInputAndSwitchedOff() = runBlocking {
        assertEquals(LinkStatus.INVALID_MESSAGE, binder.send(deviceId, "not-a-uuid", LinkCodec.encode(1L), 1))
        perms.decide("com.example.tcs", uuid, digestOf(cert), true)
        assertEquals(LinkStatus.INVALID_MESSAGE, binder.send(deviceId, uuid.toString(), byteArrayOf(9), 1))
        db.settings.setBool(LinkSettings.ENABLED, false)
        assertEquals(LinkStatus.DENIED, binder.send(deviceId, uuid.toString(), LinkCodec.encode(1L), 2))
        assertTrue(binder.devices().isEmpty())
        db.settings.setBool(LinkSettings.ENABLED, true)
        assertEquals(1, binder.devices().size)
    }

    @Test
    fun serviceBindsOnlyWithTheLinkAction() {
        val svc = Robolectric.setupService(LinkService::class.java)
        assertNotNull(svc.onBind(Intent(LinkService.ACTION_BIND)))
        assertNull(svc.onBind(Intent("something.else")))
    }
}
