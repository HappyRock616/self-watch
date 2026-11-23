/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.presence

import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import app.selfwatch.model.log.SwLog
import app.selfwatch.service.SyncCommands

/**
 * Woken by Android when the associated watch comes into or leaves Bluetooth range (API 31+;
 * COMPATIBILITY §2 rows 31, 33 and 36). Appearing starts [app.selfwatch.service.SyncService]
 * (companion apps may start foreground services from the background); leaving tells it to release
 * the high-speed connection priority while the `auto` reconnect stays armed.
 *
 * Callbacks by API level: 31–32 `onDeviceAppeared(address)`, 33–35 `onDeviceAppeared(AssociationInfo)`,
 * 36+ `onDevicePresenceEvent`. Each older callback acts only on its own API levels, so nothing is
 * handled twice when the platform forwards one callback to another.
 */
@RequiresApi(Build.VERSION_CODES.S)
public class WatchPresenceService : CompanionDeviceService() {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) appeared(this, address)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) disappeared(this, address)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.TIRAMISU until Build.VERSION_CODES.BAKLAVA) {
            appeared(this, associationInfo.deviceMacAddress?.toString())
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.TIRAMISU until Build.VERSION_CODES.BAKLAVA) {
            disappeared(this, associationInfo.deviceMacAddress?.toString())
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED, DevicePresenceEvent.EVENT_BT_CONNECTED ->
                appeared(this, PresenceObserver.addressOf(this, event.associationId))
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED, DevicePresenceEvent.EVENT_BT_DISCONNECTED ->
                disappeared(this, PresenceObserver.addressOf(this, event.associationId))
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "WatchPresence"

        fun appeared(ctx: Context, address: String?) {
            SwLog.i(TAG, "watch in range")
            SyncCommands.start(ctx, SyncCommands.ACTION_DEVICE_APPEARED, address)
        }

        fun disappeared(ctx: Context, address: String?) {
            SwLog.i(TAG, "watch out of range")
            val i = SyncCommands.intent(ctx, SyncCommands.ACTION_DEVICE_DISAPPEARED)
            if (address != null) i.putExtra(SyncCommands.EXTRA_ADDRESS, address)
            SyncCommands.sendPlain(ctx, i)
        }
    }
}

/**
 * Registers and unregisters presence observation for a paired watch (API 31+; does nothing below).
 * API 36+ uses `ObservingDevicePresenceRequest` with the association id; 31–35 use the address.
 * The app calls [stop] when the user unpairs.
 */
public object PresenceObserver {
    private const val TAG = "PresenceObserver"

    /** Starts observing the watch at [address] (association [associationId], when known). */
    public fun start(ctx: Context, address: String, associationId: Long?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && associationId != null) {
                cdm.startObservingDevicePresence(request(associationId))
            } else {
                legacyStart(cdm, address)
            }
        } catch (e: Exception) {
            // DeviceNotAssociatedException, or no companion device feature.
            SwLog.w(TAG, "could not observe presence", e)
        }
    }

    /** Stops observing the watch at [address] (association [associationId], when known). */
    public fun stop(ctx: Context, address: String, associationId: Long?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && associationId != null) {
                cdm.stopObservingDevicePresence(request(associationId))
            } else {
                legacyStop(cdm, address)
            }
        } catch (e: Exception) {
            SwLog.w(TAG, "could not stop observing presence", e)
        }
    }

    /** The Bluetooth address of association [associationId], or null (API 33+). */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    internal fun addressOf(ctx: Context, associationId: Int): String? {
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return null
        return try {
            cdm.myAssociations.firstOrNull { it.id == associationId }?.deviceMacAddress?.toString()
        } catch (e: RuntimeException) {
            SwLog.w(TAG, "associations unavailable", e)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun request(associationId: Long): ObservingDevicePresenceRequest =
        ObservingDevicePresenceRequest.Builder().setAssociationId(associationId.toInt()).build()

    @RequiresApi(Build.VERSION_CODES.S)
    @Suppress("DEPRECATION")
    private fun legacyStart(cdm: CompanionDeviceManager, address: String) {
        cdm.startObservingDevicePresence(address)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Suppress("DEPRECATION")
    private fun legacyStop(cdm: CompanionDeviceManager, address: String) {
        cdm.stopObservingDevicePresence(address)
    }
}
