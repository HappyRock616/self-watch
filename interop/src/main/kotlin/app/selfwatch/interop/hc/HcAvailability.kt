/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import android.content.Context
import android.content.Intent
import android.health.connect.HealthConnectManager
import android.os.Build
import app.selfwatch.model.log.SwLog

/** Whether Health Connect can be used on this phone. */
enum class HcStatus {
    /** Android older than 14, or Health Connect missing (work profile, stripped build): hide every Health Connect control. */
    UNSUPPORTED,

    /** Ready to use. */
    AVAILABLE,
}

/**
 * Health Connect availability (COMPATIBILITY rows 28 and 34). Self Watch uses only the Health Connect
 * built into Android 14+ (`android.health.connect`); on Android 9 to 13 (the separate Google app)
 * Health Connect is not offered.
 */
object HcAvailability {
    /** First Android version with Health Connect built in (Android 14). */
    const val MIN_API: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private const val TAG = "HcAvailability"

    /** The status on this phone. Never throws. */
    fun status(ctx: Context): HcStatus = classify(Build.VERSION.SDK_INT, Build.VERSION.SDK_INT >= MIN_API && manager(ctx) != null)

    /** True when Health Connect can be used now. */
    fun isAvailable(ctx: Context): Boolean = status(ctx) == HcStatus.AVAILABLE

    /** Maps an SDK level and whether the system service exists to a [HcStatus]. Pure, for tests. */
    fun classify(sdkInt: Int, hasService: Boolean): HcStatus =
        if (sdkInt >= MIN_API && hasService) HcStatus.AVAILABLE else HcStatus.UNSUPPORTED

    /**
     * An intent that opens Health Connect's permission and data screen for this app (where the user
     * manages what Self Watch may read and write), or null below Android 14.
     */
    fun settingsIntent(ctx: Context): Intent? {
        if (Build.VERSION.SDK_INT < MIN_API) return null
        return Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS).putExtra(Intent.EXTRA_PACKAGE_NAME, ctx.packageName)
    }

    /** The real [HcApi] for this phone, or null when Health Connect is not [HcStatus.AVAILABLE]. */
    fun api(ctx: Context): HcApi? {
        if (Build.VERSION.SDK_INT < MIN_API) return null
        val m = manager(ctx) ?: return null
        return HcClientApi(ctx.applicationContext ?: ctx, m)
    }

    private fun manager(ctx: Context): HealthConnectManager? {
        if (Build.VERSION.SDK_INT < MIN_API) return null
        return try {
            ctx.getSystemService(HealthConnectManager::class.java)
        } catch (e: RuntimeException) {
            SwLog.w(TAG, "health connect service unavailable", e)
            null
        }
    }
}
