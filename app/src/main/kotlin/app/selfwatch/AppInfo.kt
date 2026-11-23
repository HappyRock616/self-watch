/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import app.selfwatch.model.log.SwLog

/** The installed app's version (from the package manager; the build has no BuildConfig). */
object AppInfo {
    private const val TAG = "AppInfo"

    private fun info(ctx: Context): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        SwLog.w(TAG, "own package not found", e)
        null
    }

    /** Version name, for example `0.1.0` ("?" if unknown). */
    fun versionName(ctx: Context): String = info(ctx)?.versionName ?: "?"

    /** Version code (1 if unknown). */
    fun versionCode(ctx: Context): Int = info(ctx)?.let { PackageInfoCompat.getLongVersionCode(it).toInt() } ?: 1
}
