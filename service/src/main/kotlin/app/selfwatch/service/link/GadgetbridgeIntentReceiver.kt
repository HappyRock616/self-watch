/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.link.client.LinkStatus
import app.selfwatch.model.log.SwLog
import app.selfwatch.service.device.DeviceKinds
import app.selfwatch.model.watch.CiqValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** One Gadgetbridge `SEND_WATCH_APP_MESSAGE` Intent. */
public data class GadgetbridgeMessage(
    /** The watch's Bluetooth address, or null for "the connected watch". */
    val deviceAddress: String?,
    /** The watch app. */
    val appUuid: UUID,
    /** The message (JSON parsed into a value, other text as a string). */
    val payload: CiqValue,
)

/** The Gadgetbridge Intent's names and parser. */
public object GadgetbridgeIntent {
    /** The action apps written for Gadgetbridge send. */
    public const val ACTION: String = "nodomain.freeyourgadget.gadgetbridge.command.SEND_WATCH_APP_MESSAGE"

    /** Extra: the watch's Bluetooth address (`String`, optional). */
    public const val EXTRA_DEVICE: String = "device"

    /** Extra: the watch app UUID (`String`). */
    public const val EXTRA_APP_ID: String = "appId"

    /** Extra: the message, JSON or plain text (`String`). */
    public const val EXTRA_PAYLOAD: String = "payload"

    /** The message in [intent], or null when the action, app id or payload is missing or malformed. */
    public fun parse(intent: Intent): GadgetbridgeMessage? {
        if (intent.action != ACTION) return null
        val uuid = try {
            intent.getStringExtra(EXTRA_APP_ID)?.trim()?.let { UUID.fromString(it) }
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        val payload = intent.getStringExtra(EXTRA_PAYLOAD) ?: return null
        val device = intent.getStringExtra(EXTRA_DEVICE)?.trim()?.takeIf { it.isNotEmpty() }
        return GadgetbridgeMessage(device, uuid, LinkValues.gadgetbridgePayload(payload))
    }
}

/**
 * Sends Gadgetbridge-Intent messages (F07) after the same permission check as the bound service: by
 * the sender's package when Android names it (API 34+ `getSentFromPackage`, if the sender shares its
 * identity), otherwise by watch app with the "unknown app" approval. Watch replies go to the approved
 * package's `app.selfwatch.link.MESSAGE` broadcast ([LinkRouter]), which Gadgetbridge never did.
 */
public class GadgetbridgeHandler(
    private val db: SelfWatchDb,
    private val permissions: LinkPermissions,
    private val router: LinkRouter,
) {
    /**
     * Handles [msg] from [senderPackage] (null = unknown). Returns the `LinkStatus` (SUCCESS = queued;
     * the delivery result is only logged, as the Intent has no way back).
     */
    public suspend fun handle(msg: GadgetbridgeMessage, senderPackage: String?): Int {
        if (!db.settings.getBool(LinkSettings.ENABLED, true)) return LinkStatus.DENIED
        val decision = if (senderPackage != null) {
            permissions.checkPackage(senderPackage, msg.appUuid)
        } else {
            permissions.checkUnknownSender(msg.appUuid)
        }
        if (decision !is LinkDecision.Allowed) {
            SwLog.i(TAG, "Gadgetbridge message for ${msg.appUuid} not sent: $decision")
            return decision.status
        }
        val deviceId = deviceFor(msg.deviceAddress) ?: return LinkStatus.NOT_CONNECTED
        val status = router.sendValue(deviceId, msg.appUuid, msg.payload) { s ->
            SwLog.i(TAG, "Gadgetbridge message for ${msg.appUuid}: result $s")
        }
        if (status != LinkStatus.SUCCESS) SwLog.i(TAG, "Gadgetbridge message for ${msg.appUuid} refused: $status")
        return status
    }

    private suspend fun deviceFor(address: String?): Long? {
        if (address != null) return db.devices.byAddress(address)?.id ?: db.devices.byAddress(address.uppercase())?.id
        val connected = LinkHub.watches.value?.devices?.value.orEmpty()
            .firstOrNull { DeviceKinds.isWatch(it.info.kind) && isConnectedState(it.state) }
        return connected?.info?.id ?: db.devices.active().firstOrNull { DeviceKinds.isWatch(it.kind) }?.id
    }

    private companion object {
        const val TAG = "GadgetbridgeIntent"
    }
}

/**
 * Receives Gadgetbridge's `SEND_WATCH_APP_MESSAGE` (exported, process `:watch`) and hands it to
 * [GadgetbridgeHandler].
 */
public class GadgetbridgeIntentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val msg = GadgetbridgeIntent.parse(intent) ?: run {
            SwLog.i(TAG, "ignored a malformed Gadgetbridge Intent")
            return
        }
        val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromPackage else null
        val result: PendingResult? = goAsync()
        val app = context.applicationContext ?: context
        scope.launch {
            try {
                GadgetbridgeHandler(SelfWatchDb.get(app), LinkHub.permissions(app), LinkHub.router(app)).handle(msg, sender)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.e(TAG, "Gadgetbridge message failed", e)
            } finally {
                result?.finish()
            }
        }
    }

    private companion object {
        const val TAG = "GadgetbridgeIntent"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
