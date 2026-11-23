/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service

import android.Manifest
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.selfwatch.model.log.SwLog

/**
 * When the connection service may run in the foreground (COMPATIBILITY §2, API 31 and 34 rows).
 *
 * A `connectedDevice` foreground service needs, from API 34, `BLUETOOTH_CONNECT` or a companion
 * device association. The same rule is applied from API 31 (where `BLUETOOTH_CONNECT` became a
 * runtime permission), so the service never starts where it could not connect. Below 31 the
 * install-time Bluetooth permissions are enough.
 */
public object ForegroundRules {
    private const val TAG = "ForegroundRules"

    /** True when [SyncService] may call `startForeground` now. */
    public fun canStartForeground(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return hasBluetoothConnect(ctx) || hasAssociation(ctx)
    }

    /** True when `BLUETOOTH_CONNECT` is granted (always true below API 31, where it doesn't exist). */
    public fun hasBluetoothConnect(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    /** True when this app has at least one companion device association. */
    public fun hasAssociation(ctx: Context): Boolean {
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                myAssociationCount(cdm) > 0
            } else {
                legacyAssociationCount(cdm) > 0
            }
        } catch (e: RuntimeException) {
            // No companion device feature on this phone, or the service is not available.
            SwLog.w(TAG, "companion associations unavailable", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun myAssociationCount(cdm: CompanionDeviceManager): Int = cdm.myAssociations.size

    @Suppress("DEPRECATION")
    private fun legacyAssociationCount(cdm: CompanionDeviceManager): Int = cdm.associations.size
}
