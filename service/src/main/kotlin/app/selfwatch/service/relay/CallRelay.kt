/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SettingsStore
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.service.device.CallHandler
import app.selfwatch.service.device.WatchLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What a call-state change means for the watch. */
public enum class CallChange {
    /** A call started ringing. */
    RINGING,

    /** The ringing call was answered, rejected or missed. */
    ENDED,
}

/**
 * Turns `TelephonyManager.CALL_STATE_*` values into [CallChange]s: ringing starts a call alert,
 * and off-hook or idle after ringing ends it. Repeats are ignored.
 */
public class CallStateTracker {
    private var ringing = false

    /** The change for [state], or null when the watch need not hear about it. */
    public fun onState(state: Int): CallChange? = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> if (ringing) null else CallChange.RINGING.also { ringing = true }
        TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_IDLE ->
            if (ringing) CallChange.ENDED.also { ringing = false } else null
        else -> null
    }
}

/** The optional permissions and settings that decide what a call alert shows. */
public object CallPermissions {
    /** Call alerts at all (`READ_PHONE_STATE`). */
    public fun canWatch(ctx: Context): Boolean = granted(ctx, Manifest.permission.READ_PHONE_STATE)

    /** The incoming number: `READ_CALL_LOG` on API 29+, `READ_PHONE_STATE` before. */
    public fun canReadNumber(ctx: Context): Boolean =
        canWatch(ctx) && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(ctx, Manifest.permission.READ_CALL_LOG))

    /** Contact names (`READ_CONTACTS`). */
    public fun canReadContacts(ctx: Context): Boolean = granted(ctx, Manifest.permission.READ_CONTACTS)

    /** Accept and reject from the watch (`ANSWER_PHONE_CALLS`). */
    public fun canAnswer(ctx: Context): Boolean = granted(ctx, Manifest.permission.ANSWER_PHONE_CALLS)

    private fun granted(ctx: Context, p: String): Boolean = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
}

/**
 * Incoming calls to the watch (B20). Owned by [app.selfwatch.service.SyncService] while it runs.
 *
 * The call state comes from `TelephonyCallback.CallStateListener` on API 31+ and
 * `PhoneStateListener` on 26–30. The number comes from the legacy listener (26–28) or the
 * `PHONE_STATE` broadcast, which carries it only with `READ_CALL_LOG` (29+); the name from
 * `ContactsContract.PhoneLookup` only with `READ_CONTACTS`. Settings `calls.enabled`,
 * `calls.show_number`, `calls.show_name`. Without `READ_PHONE_STATE` nothing is registered.
 * The permissions are requested by the app's settings screen when the user turns the option on.
 */
public class CallRelay(
    private val ctx: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val send: suspend (WatchCommand) -> Unit = { RelayHub.sendToAll(it) },
    private val numberWaitMs: Long = NUMBER_WAIT_MS,
) {
    private val main = Handler(Looper.getMainLooper())
    private val tracker = CallStateTracker()
    private val number = MutableStateFlow<String?>(null)
    private var registered: Any? = null
    private var receiver: BroadcastReceiver? = null
    private var alert: Job? = null

    /** True while a listener is registered. */
    public val isListening: Boolean get() = registered != null

    /**
     * Registers the listeners when `calls.enabled` is on and `READ_PHONE_STATE` is granted, else
     * unregisters. The change itself is posted to the main thread (the legacy listener needs a looper).
     */
    public suspend fun refresh() {
        val want = settings.getBool(SettingKeys.CALLS_ENABLED, true) && CallPermissions.canWatch(ctx)
        main.post { if (want) register() else unregister() }
    }

    /** Unregisters everything. Call on the main thread. */
    public fun unregister() {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val r = registered
        registered = null
        if (r != null && tm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) unregister31(tm, r) else unregisterLegacy(tm, r)
        }
        receiver?.let {
            try {
                ctx.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                SwLog.w(TAG, "phone state receiver was not registered", e)
            }
        }
        receiver = null
    }

    private fun register() {
        if (registered != null) return
        val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return
        try {
            registered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) register31(tm) else registerLegacy(tm)
        } catch (e: SecurityException) {
            SwLog.w(TAG, "call state listener refused", e)
            return
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
                incomingNumber(intent)?.let { number.value = it }
            }
        }
        SystemReceivers.register(ctx, r, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        receiver = r
        SwLog.i(TAG, "listening for calls")
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun register31(tm: TelephonyManager): Any {
        val cb = Callback31()
        tm.registerTelephonyCallback(ctx.mainExecutor, cb)
        return cb
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregister31(tm: TelephonyManager, r: Any) {
        (r as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
    }

    @Suppress("DEPRECATION") // PhoneStateListener is the only call-state API on 26–30.
    private fun registerLegacy(tm: TelephonyManager): Any {
        val l = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state, phoneNumber)
        }
        tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        return l
    }

    @Suppress("DEPRECATION") // see registerLegacy
    private fun unregisterLegacy(tm: TelephonyManager, r: Any) {
        (r as? PhoneStateListener)?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class Callback31 : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = onState(state, null)
    }

    /** One call-state change ([legacyNumber] from the 26–30 listener, often empty). */
    internal fun onState(state: Int, legacyNumber: String?) {
        if (!legacyNumber.isNullOrBlank()) number.value = legacyNumber
        when (tracker.onState(state)) {
            CallChange.RINGING -> alert = scope.launch { ring() }
            CallChange.ENDED -> {
                alert?.cancel()
                alert = null
                number.value = null
                scope.launch { send(WatchCommand.CallEnded) }
            }
            null -> if (state == TelephonyManager.CALL_STATE_IDLE) number.value = null
        }
    }

    private suspend fun ring() {
        val showNumber = settings.getBool(SettingKeys.CALLS_SHOW_NUMBER) && CallPermissions.canReadNumber(ctx)
        val showName = settings.getBool(SettingKeys.CALLS_SHOW_NAME) && CallPermissions.canReadContacts(ctx)
        // The number may arrive a moment after the state (PHONE_STATE broadcast).
        val n = if (showNumber || showName) withTimeoutOrNull(numberWaitMs) { number.filterNotNull().first() } else null
        val name = if (showName && n != null) lookupName(n) else null
        send(WatchCommand.IncomingCall(if (showNumber) n else null, name))
    }

    private suspend fun lookupName(phone: String): String? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (e: SecurityException) {
            SwLog.w(TAG, "contacts not readable", e)
            null
        } catch (e: IllegalArgumentException) {
            SwLog.w(TAG, "contact lookup failed", e)
            null
        }
    }

    private companion object {
        const val TAG = "CallRelay"
        const val NUMBER_WAIT_MS = 800L

        @Suppress("DEPRECATION") // The extra is deprecated but still the only source of the number for others' calls.
        fun incomingNumber(intent: Intent): String? =
            intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }
    }
}

/** What [CallControl] did (for tests and logs). */
public enum class CallControlResult { DONE, NO_PERMISSION, UNSUPPORTED, FAILED }

/**
 * Accept or reject from the watch (B20): `TelecomManager.acceptRingingCall()` and `endCall()`
 * (API 28+; not offered on 26–27). Both need `ANSWER_PHONE_CALLS`; without it, or when Android
 * refuses, nothing happens and the phone keeps ringing.
 */
public class CallControl(private val ctx: Context) : CallHandler {
    override suspend fun onCallAction(from: WatchLink, event: WatchEvent.CallAction) {
        val r = if (event.accept) accept() else reject()
        if (r != CallControlResult.DONE) SwLog.i(TAG, "call ${if (event.accept) "accept" else "reject"}: $r")
    }

    /** Answers the ringing call. */
    @Suppress("DEPRECATION") // Deprecated in API 29 for apps that are not the default dialer, still works for companions.
    public fun accept(): CallControlResult {
        if (!CallPermissions.canAnswer(ctx)) return CallControlResult.NO_PERMISSION
        val tm = ctx.getSystemService(TelecomManager::class.java) ?: return CallControlResult.FAILED
        return try {
            tm.acceptRingingCall()
            CallControlResult.DONE
        } catch (e: SecurityException) {
            SwLog.w(TAG, "accept refused", e)
            CallControlResult.FAILED
        }
    }

    /** Rejects (ends) the ringing call. */
    @Suppress("DEPRECATION") // see accept
    public fun reject(): CallControlResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return CallControlResult.UNSUPPORTED
        if (!CallPermissions.canAnswer(ctx)) return CallControlResult.NO_PERMISSION
        val tm = ctx.getSystemService(TelecomManager::class.java) ?: return CallControlResult.FAILED
        return try {
            if (tm.endCall()) CallControlResult.DONE else CallControlResult.FAILED
        } catch (e: SecurityException) {
            SwLog.w(TAG, "reject refused", e)
            CallControlResult.FAILED
        }
    }

    private companion object {
        const val TAG = "CallControl"
    }
}
