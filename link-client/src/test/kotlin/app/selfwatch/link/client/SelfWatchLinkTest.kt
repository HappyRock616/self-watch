/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.link.client

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelfWatchLinkTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val shadowApp: ShadowApplication = shadowOf(app)
    private val fake = FakeLinkService()
    private val component = ComponentName(SelfWatchLink.SELF_WATCH_PACKAGE, "app.selfwatch.service.link.LinkService")
    private val direct = Executor { it.run() }
    private val appUuid: UUID = UUID.fromString("6A1E2C3D-0000-4000-8000-00000000ABCD")
    private val device = LinkDevice(42, "vívoactive 5", true)

    private val events = mutableListOf<String>()
    private val listener = object : SelfWatchLink.Listener {
        override fun onReady() {
            events += "ready"
        }

        override fun onUnavailable(reason: SelfWatchLink.Reason) {
            events += "unavailable $reason"
        }
    }

    @Before
    fun setUp() {
        shadowApp.setComponentNameAndServiceForBindServiceForIntent(bindIntent(), component, fake)
    }

    private fun bindIntent(): Intent = Intent(SelfWatchLink.ACTION_BIND).setPackage(SelfWatchLink.SELF_WATCH_PACKAGE)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun connect(): SelfWatchLink {
        val link = SelfWatchLink.connect(app, listener, direct, null)
        idle()
        return link
    }

    private fun connection(): ServiceConnection = shadowApp.boundServiceConnections.last()

    @Test
    fun bindsWithTheDocumentedIntentAndBecomesReady() {
        // The fake binder is only handed out for the exact action + package, so "ready" proves the intent.
        val link = connect()
        assertEquals(listOf("ready"), events)
        assertTrue(link.isReady)
        assertEquals("apiVersion", fake.calls.first())
    }

    @Test
    fun notInstalledWhenBindFails() {
        shadowApp.declareActionUnbindable(SelfWatchLink.ACTION_BIND)
        val link = connect()
        assertEquals(listOf("unavailable NOT_INSTALLED"), events)
        assertFalse(link.isReady)
        val statuses = mutableListOf<SendStatus>()
        link.send(device, appUuid, "hi") { statuses += it }
        idle()
        assertEquals(listOf(SendStatus.UNAVAILABLE), statuses)
    }

    @Test
    fun isInstalledFollowsThePackageManager() {
        assertFalse(SelfWatchLink.isInstalled(app))
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = SelfWatchLink.SELF_WATCH_PACKAGE })
        assertTrue(SelfWatchLink.isInstalled(app))
    }

    @Test
    fun unsupportedServiceVersionIsReportedAsNotInstalled() {
        fake.version = 0
        connect()
        assertEquals(listOf("unavailable NOT_INSTALLED"), events)
    }

    @Test
    fun nullBindingMeansDenied() {
        val link = SelfWatchLink.connect(app, listener, direct, null)
        connection().onNullBinding(component)
        idle()
        assertTrue(events.contains("unavailable DENIED"))
        assertFalse(link.isReady)
    }

    @Test
    fun handlersRegisterOnConnectAndReceiveDecodedMessages() {
        val link = SelfWatchLink.connect(app, listener, direct, null)
        val got = mutableListOf<Pair<LinkDevice, Any?>>()
        link.onMessage(appUuid) { d, m -> got += d to m }
        idle()
        val key = appUuid.toString()
        assertTrue(fake.calls.contains("register $key"))

        link.devices { _, _ -> }
        idle()
        // The service may report the UUID in upper case; it still reaches the handler.
        fake.callbacks.getValue(key).onMessage(42, key.uppercase(), LinkCodec.encode(mapOf("speed" to 6.5)))
        fake.callbacks.getValue(key).onMessage(42, key, byteArrayOf(9, 9)) // malformed: dropped
        idle()
        assertEquals(listOf(device to mapOf<Any?, Any?>("speed" to 6.5)), got)
    }

    @Test
    fun handlerAddedWhileReadyRegistersAtOnceAndNullRemoves() {
        val link = connect()
        val key = appUuid.toString()
        link.onMessage(appUuid) { _, _ -> }
        link.onMessage(appUuid) { _, _ -> } // replacing does not register twice
        assertEquals(1, fake.calls.count { it == "register $key" })
        link.onMessage(appUuid, null)
        assertTrue(fake.calls.contains("unregister $key"))
    }

    @Test
    fun sendReportsDeliveryFromTheCallback() {
        val link = connect()
        link.onMessage(appUuid) { _, _ -> }
        val statuses = mutableListOf<SendStatus>()
        link.send(device, appUuid, listOf(1, "two", 3.0)) { statuses += it }
        idle()
        assertTrue(statuses.isEmpty()) // queued, not yet delivered
        val (devId, uuid, bytes) = fake.sent.single()
        assertEquals(42L, devId)
        assertEquals(appUuid.toString(), uuid)
        assertEquals(listOf(1L, "two", 3.0), LinkCodec.decode(bytes))

        fake.callbacks.getValue(uuid).onSendResult(fake.requestIds.single(), LinkStatus.SUCCESS)
        fake.callbacks.getValue(uuid).onSendResult(fake.requestIds.single(), LinkStatus.FAILED) // duplicate ignored
        idle()
        assertEquals(listOf(SendStatus.SUCCESS), statuses)
    }

    @Test
    fun immediateErrorsFromSend() {
        val link = connect()
        val statuses = mutableListOf<SendStatus>()
        fake.sendCode = LinkStatus.TOO_LARGE
        link.send(device, appUuid, "x") { statuses += it }
        fake.sendCode = LinkStatus.PENDING_APPROVAL
        link.send(device, appUuid, "x") { statuses += it }
        fake.sendCode = 99
        link.send(device, appUuid, "x") { statuses += it }
        fake.throwOnSend = SecurityException("not approved")
        link.send(device, appUuid, "x") { statuses += it }
        link.send(device, appUuid, Any()) { statuses += it }
        idle()
        assertEquals(
            listOf(SendStatus.TOO_LARGE, SendStatus.PENDING_APPROVAL, SendStatus.FAILED, SendStatus.DENIED,
                SendStatus.INVALID_MESSAGE),
            statuses,
        )
    }

    @Test
    fun devicesAndAppInfoAreParsed() {
        val link = connect()
        fake.deviceJson = listOf("""{"id":42,"name":"vívoactive 5","connected":true}""", "not json", """{"name":"x"}""")
        var devices: List<LinkDevice>? = null
        link.devices { status, list ->
            assertEquals(SendStatus.SUCCESS, status)
            devices = list
        }
        val infos = mutableListOf<Pair<SendStatus, AppInfo?>>()
        link.appInfo(device, appUuid) { s, i -> infos += s to i }
        fake.appInfoJson = """{"error":3}"""
        link.appInfo(device, appUuid) { s, i -> infos += s to i }
        fake.appInfoJson = "garbage"
        link.appInfo(device, appUuid) { s, i -> infos += s to i }
        idle()
        assertEquals(listOf(device), devices)
        assertEquals(
            listOf(SendStatus.SUCCESS to AppInfo(true, 12), SendStatus.NOT_INSTALLED to null, SendStatus.FAILED to null),
            infos,
        )
    }

    @Test
    fun openAppPassesTheResultThrough() {
        val link = connect()
        val statuses = mutableListOf<SendStatus>()
        link.openApp(device, appUuid) { statuses += it }
        fake.openCode = LinkStatus.NOT_CONNECTED
        link.openApp(device, appUuid) { statuses += it }
        idle()
        assertEquals(listOf(SendStatus.SUCCESS, SendStatus.NOT_CONNECTED), statuses)
    }

    @Test
    fun deviceStatusUpdatesReachTheListener() {
        val link = connect()
        link.onMessage(appUuid) { _, _ -> }
        val seen = mutableListOf<Pair<LinkDevice, Boolean>>()
        link.setDeviceStatusListener { d, c -> seen += d to c }
        link.devices { _, _ -> }
        idle()
        fake.callbacks.getValue(appUuid.toString()).onDeviceStatus(42, false)
        idle()
        assertEquals(listOf(LinkDevice(42, "vívoactive 5", false) to false), seen)
    }

    @Test
    fun serviceRestartFailsPendingSendsThenReconnectsAndReRegisters() {
        val link = connect()
        link.onMessage(appUuid) { _, _ -> }
        val statuses = mutableListOf<SendStatus>()
        link.send(device, appUuid, "pending") { statuses += it }
        idle()

        connection().onServiceDisconnected(component)
        idle()
        assertEquals(listOf("ready", "unavailable DISCONNECTED"), events)
        assertEquals(listOf(SendStatus.UNAVAILABLE), statuses)
        assertFalse(link.isReady)
        link.send(device, appUuid, "while down") { statuses += it }
        idle()
        assertEquals(listOf(SendStatus.UNAVAILABLE, SendStatus.UNAVAILABLE), statuses)

        // The platform reconnects the same binding when the :watch process is back.
        fake.calls.clear()
        connection().onServiceConnected(component, fake)
        idle()
        assertEquals(listOf("ready", "unavailable DISCONNECTED", "ready"), events)
        assertEquals(listOf("apiVersion", "register $appUuid"), fake.calls)
    }

    @Test
    fun bindingDiedUnbindsAndRebindsAfterABackoff() {
        val link = connect()
        val conn = connection()
        conn.onBindingDied(component)
        idle()
        assertTrue(shadowApp.unboundServiceConnections.contains(conn))
        assertEquals(listOf("ready", "unavailable DISCONNECTED"), events)
        val boundBefore = shadowApp.boundServiceConnections.size

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SelfWatchLink.FIRST_RETRY_MS - 1))
        assertEquals(boundBefore, shadowApp.boundServiceConnections.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2))
        idle()
        assertEquals(boundBefore + 1, shadowApp.boundServiceConnections.size)
        assertEquals(listOf("ready", "unavailable DISCONNECTED", "ready"), events)
        assertTrue(link.isReady)
    }

    @Test
    fun closeUnregistersUnbindsAndSilencesCallbacks() {
        val link = connect()
        link.onMessage(appUuid) { _, _ -> events += "message" }
        val statuses = mutableListOf<SendStatus>()
        link.send(device, appUuid, "pending") { statuses += it }
        idle()
        val cb = fake.callbacks.getValue(appUuid.toString())
        val conn = connection()

        link.close()
        idle()
        assertTrue(fake.calls.contains("unregister $appUuid"))
        assertTrue(shadowApp.unboundServiceConnections.contains(conn))
        assertEquals(listOf(SendStatus.UNAVAILABLE), statuses)
        assertFalse(link.isReady)

        cb.onMessage(42, appUuid.toString(), LinkCodec.encode("late"))
        conn.onServiceDisconnected(component)
        idle()
        assertEquals(listOf("ready"), events)
        link.close() // twice is fine
    }
}
