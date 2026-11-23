/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.telephony.TelephonyManager
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.service.ServiceDbTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

/** B20: call states per API branch (PhoneStateListener 26–30, TelephonyCallback 31+), optional number, accept/reject. */
@Config(sdk = [26, 28, 31, 35])
class CallRelayTest : ServiceDbTest() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sent: MutableList<WatchCommand> = Collections.synchronizedList(ArrayList())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun relay() = CallRelay(app, db.settings, scope, { sent.add(it) }, numberWaitMs = 300)

    private fun idleUntil(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 3_000
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun telephony() = shadowOf(app.getSystemService(TelephonyManager::class.java))

    @Test
    fun trackerMapsStates() {
        val t = CallStateTracker()
        assertNull(t.onState(TelephonyManager.CALL_STATE_IDLE))
        assertEquals(CallChange.RINGING, t.onState(TelephonyManager.CALL_STATE_RINGING))
        assertNull("repeats are ignored", t.onState(TelephonyManager.CALL_STATE_RINGING))
        assertEquals(CallChange.ENDED, t.onState(TelephonyManager.CALL_STATE_OFFHOOK))
        assertNull(t.onState(TelephonyManager.CALL_STATE_IDLE))
        assertEquals(CallChange.RINGING, t.onState(TelephonyManager.CALL_STATE_RINGING))
        assertEquals(CallChange.ENDED, t.onState(TelephonyManager.CALL_STATE_IDLE))
    }

    @Test
    fun withoutPhoneStatePermissionNothingIsRegistered() = runBlocking {
        val r = relay()
        r.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(r.isListening)
    }

    @Test
    fun ringingAndEndedWithoutOptionalPermissions() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val r = relay()
        runBlocking { r.refresh() }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(r.isListening)
        telephony().setCallState(TelephonyManager.CALL_STATE_RINGING, "+15550100")
        idleUntil { sent.isNotEmpty() }
        assertEquals("number and name are off by default", WatchCommand.IncomingCall(null, null), sent[0])
        telephony().setCallState(TelephonyManager.CALL_STATE_OFFHOOK, null)
        idleUntil { sent.size >= 2 }
        assertEquals(WatchCommand.CallEnded, sent[1])
        r.unregister()
        assertFalse(r.isListening)
    }

    @Test
    fun numberNeedsTheSettingAndCallLogFromApi29() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        runBlocking { db.settings.setBool(SettingKeys.CALLS_SHOW_NUMBER, true) }
        val r = relay()
        runBlocking { r.refresh() }
        shadowOf(Looper.getMainLooper()).idle()
        ring("+15550100")
        idleUntil { sent.isNotEmpty() }
        val expected = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) "+15550100" else null
        assertEquals(WatchCommand.IncomingCall(expected, null), sent[0])
        telephony().setCallState(TelephonyManager.CALL_STATE_IDLE, null)
        idleUntil { sent.size >= 2 }

        shadowOf(app).grantPermissions(Manifest.permission.READ_CALL_LOG)
        ring("+15550199")
        idleUntil { sent.size >= 3 }
        assertEquals(WatchCommand.IncomingCall("+15550199", null), sent[2])
        r.unregister()
    }

    /** Rings the way the API level does it: the number in the listener (26–28) or the PHONE_STATE broadcast. */
    private fun ring(number: String) {
        telephony().setCallState(TelephonyManager.CALL_STATE_RINGING, number)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            app.sendBroadcast(
                Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                    .putExtra(TelephonyManager.EXTRA_STATE, TelephonyManager.EXTRA_STATE_RINGING)
                    .putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER, number),
            )
        }
    }

    @Test
    fun acceptAndRejectNeedAnswerPhoneCalls() {
        val c = CallControl(app)
        assertEquals(CallControlResult.NO_PERMISSION, c.accept())
        shadowOf(app).grantPermissions(Manifest.permission.ANSWER_PHONE_CALLS)
        assertEquals(CallControlResult.DONE, c.accept())
        val rejected = c.reject()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            assertEquals("endCall needs API 28", CallControlResult.UNSUPPORTED, rejected)
        } else {
            // endCall() reports false when no call is ringing (as in the test); it must not throw.
            assertTrue(rejected == CallControlResult.DONE || rejected == CallControlResult.FAILED)
        }
    }
}
