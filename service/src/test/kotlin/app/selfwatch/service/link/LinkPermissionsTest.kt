/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import app.selfwatch.db.dao.LinkPermission
import app.selfwatch.link.client.LinkStatus
import app.selfwatch.service.ServiceDbTest
import app.selfwatch.service.notify.ServiceNotifications
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/** F05: the permission flow (allowed, pending, denied, certificate mismatch, revoked) and the approval notification. */
class LinkPermissionsTest : ServiceDbTest() {
    private val app1 = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val cert = byteArrayOf(1, 2, 3, 4)
    private val otherCert = byteArrayOf(9, 9, 9)
    private val prompt = FakePrompt()
    private lateinit var perms: LinkPermissions

    @Before
    fun setUp() {
        installSigned(app, "com.example.tcs", 10_123, cert)
        perms = LinkPermissions(app.packageManager, db.links, clock, prompt)
    }

    @Test
    fun certificateDigestIsSha256Hex() {
        assertEquals(digestOf(cert), PackageSignerReader(app.packageManager).certSha256("com.example.tcs"))
        assertEquals(64, digestOf(cert).length)
        assertNull(PackageSignerReader(app.packageManager).certSha256("not.installed"))
    }

    @Test
    fun unknownPairIsPendingAndAsksOnce() = runBlocking {
        assertEquals(LinkDecision.Pending, perms.checkUid(10_123, app1))
        assertEquals(LinkStatus.PENDING_APPROVAL, perms.checkUid(10_123, app1).status)
        assertEquals(1, prompt.asked.size)
        assertEquals(ApprovalRequest("com.example.tcs", app1, digestOf(cert)), prompt.asked[0])
        // After ten minutes without an answer the question is shown again.
        clock.advance(LinkPermissions.ASK_AGAIN_SEC)
        perms.checkUid(10_123, app1)
        assertEquals(2, prompt.asked.size)
    }

    @Test
    fun allowedAfterTheUserAllows() = runBlocking {
        perms.checkUid(10_123, app1)
        perms.decide("com.example.tcs", app1, digestOf(cert), true)
        assertEquals(listOf<Pair<String?, UUID>>("com.example.tcs" to app1), prompt.cancelled.toList())
        clock.advance(60)
        assertEquals(LinkDecision.Allowed("com.example.tcs"), perms.checkUid(10_123, app1))
        assertEquals(clock.nowSec(), db.links.get("com.example.tcs", app1)?.lastUsedSec)
        assertEquals(listOf("com.example.tcs"), perms.approvedPackages(app1))
    }

    @Test
    fun deniedStaysDenied() = runBlocking {
        perms.decide("com.example.tcs", app1, digestOf(cert), false)
        val d = perms.checkUid(10_123, app1)
        assertTrue(d is LinkDecision.Denied)
        assertEquals(LinkStatus.DENIED, d.status)
        assertTrue(prompt.asked.isEmpty())
        assertTrue(perms.approvedPackages(app1).isEmpty())
    }

    @Test
    fun certificateMismatchIsAlwaysDenied() = runBlocking {
        // Approved for the real app's certificate; a look-alike with the same package name is signed differently.
        db.links.set(LinkPermission("com.example.tcs", app1, digestOf(otherCert), true, clock.nowSec(), null))
        val d = perms.checkUid(10_123, app1)
        assertEquals(LinkDecision.Denied("certificate mismatch"), d)
        assertTrue(prompt.asked.isEmpty())
        assertNull(db.links.get("com.example.tcs", app1)?.lastUsedSec)
    }

    @Test
    fun revokedAppIsAskedAgain() = runBlocking {
        perms.decide("com.example.tcs", app1, digestOf(cert), true)
        assertTrue(perms.checkUid(10_123, app1) is LinkDecision.Allowed)
        assertTrue(perms.revoke("com.example.tcs", app1))
        assertEquals(LinkDecision.Pending, perms.checkUid(10_123, app1))
        assertEquals(1, prompt.asked.size)
    }

    @Test
    fun unknownUidAndUnsignedAreDenied() = runBlocking {
        assertTrue(perms.checkUid(55_555, app1) is LinkDecision.Denied)
        shadowOf(app.packageManager).setPackagesForUid(10_200, "com.example.ghost")
        assertTrue(perms.checkUid(10_200, app1) is LinkDecision.Denied)
        assertTrue(prompt.asked.isEmpty())
    }

    @Test
    fun unknownSenderUsesThePseudoPackage() = runBlocking {
        assertEquals(LinkDecision.Pending, perms.checkUnknownSender(app1))
        assertEquals(ApprovalRequest(null, app1, ""), prompt.asked.single())
        perms.decide(LinkPermissions.UNKNOWN_PACKAGE, app1, "", true)
        assertEquals(LinkDecision.Allowed(LinkPermissions.UNKNOWN_PACKAGE), perms.checkUnknownSender(app1))
        // The pseudo package never receives broadcasts.
        assertTrue(perms.approvedPackages(app1).isEmpty())
    }

    @Test
    fun approvalNotificationAllowsThroughItsReceiver() = runBlocking {
        ServiceNotifications.createChannels(app)
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        NotificationApprovalPrompt(app).ask(ApprovalRequest("com.example.tcs", app1, digestOf(cert)))
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = NotificationApprovalPrompt.notificationId("com.example.tcs", app1)
        val n = shadowOf(nm).getNotification(id)
        assertNotNull(n)
        assertEquals(2, n.actions.size)
        // The Allow action is an immutable broadcast to the (not exported) receiver.
        val allow = shadowOf(n.actions[0].actionIntent)
        assertTrue(allow.isBroadcast)
        assertTrue(allow.isImmutable)
        val intent: Intent = allow.savedIntent
        assertEquals(LinkApprovalReceiver.ACTION_ALLOW, intent.action)
        LinkApprovalReceiver().onReceive(app, intent)
        val row = waitFor { db.links.get("com.example.tcs", app1) }
        assertEquals(true, row.allowed)
        assertEquals(digestOf(cert), row.certSha256)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun <T : Any> waitFor(read: suspend () -> T?): T = runBlocking {
        val end = System.currentTimeMillis() + 5_000
        var v: T? = read()
        while (v == null && System.currentTimeMillis() < end) {
            Thread.sleep(20)
            v = read()
        }
        requireNotNull(v) { "timed out" }
    }
}

/** API 26: certificates come from GET_SIGNATURES. */
@Config(sdk = [26])
class LinkPermissionsOldApiTest : ServiceDbTest() {
    @Test
    fun readsSignaturesBelowApi28() = runBlocking {
        val cert = byteArrayOf(7, 7, 7)
        installSigned(app, "com.example.old", 10_300, cert)
        val reader = PackageSignerReader(app.packageManager)
        assertEquals(digestOf(cert), reader.certSha256("com.example.old"))
        val prompt = FakePrompt()
        val perms = LinkPermissions(app.packageManager, db.links, clock, prompt)
        val uuid = UUID.randomUUID()
        assertEquals(LinkDecision.Pending, perms.checkUid(10_300, uuid))
        perms.decide("com.example.old", uuid, digestOf(cert), true)
        assertEquals(LinkDecision.Allowed("com.example.old"), perms.checkUid(10_300, uuid))
    }
}
