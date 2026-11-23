/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.IdRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.selfwatch.R
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.Feature
import app.selfwatch.model.log.SwLog
import app.selfwatch.service.SyncCommands
import app.selfwatch.service.device.DeviceKinds
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.service.relay.NotificationRelay
import app.selfwatch.service.watchapps.WatchAppRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The paired watch the Devices sub-screens act on.
 *
 * @property features what the watch said it can do; empty until its first connection (then nothing is hidden).
 * @property connected true while the service reports it Ready or Syncing.
 */
data class ActiveWatch(
    /** `device.id`. */
    val id: Long,
    /** Name shown. */
    val name: String,
    /** See above. */
    val features: Set<Feature>,
    /** See above. */
    val connected: Boolean,
)

/** Feature gating (C19): a row shows only when the watch has the feature, or its features aren't known yet. */
object FeatureGate {
    /** True when a row needing [feature] shows for a watch with [features]. */
    fun shows(features: Set<Feature>, feature: Feature): Boolean = features.isEmpty() || feature in features

    /** True when the feature is known to be missing (features known and [feature] not among them). */
    fun missing(features: Set<Feature>, feature: Feature): Boolean = !shows(features, feature)
}

/** The feature rows of the Devices tab, each shown only when the watch has its feature ([FeatureGate]). */
enum class DeviceRow(
    /** The row's view id. */
    @param:IdRes val viewId: Int,
    /** The feature it needs, or null when always shown. */
    val feature: Feature?,
) {
    WATCH_APPS(R.id.row_watch_apps, Feature.WATCH_APP_MANAGE),
    INSTALL(R.id.row_install, Feature.WATCH_APP_INSTALL),
    NOTIFICATIONS(R.id.row_notifications, Feature.NOTIFICATIONS),
    CALLS(R.id.row_calls, Feature.CALLS),
    ALARMS(R.id.row_alarms, Feature.ALARMS),
    WEATHER(R.id.row_weather, Feature.WEATHER),
    LINKED(R.id.row_linked, Feature.WATCH_APP_MESSAGES),
    WATCH_SETTINGS(R.id.row_watch_settings, null),
    OTHER(R.id.row_other, null),
    ;

    /** Visibility. */
    companion object {
        /** True when [row] shows for a watch with [features]. */
        fun visible(row: DeviceRow, features: Set<Feature>): Boolean = row.feature?.let { FeatureGate.shows(features, it) } ?: true
    }
}

/** Reads the [ActiveWatch] from the database. */
object ActiveWatches {
    /** The first active watch, or null; follows device and status changes. */
    fun observe(db: SelfWatchDb): Flow<ActiveWatch?> =
        db.changes.observe(setOf(Table.DEVICE, Table.SETTING)).conflate().map { load(db) }.distinctUntilChanged()

    /** The first active watch now, or null. */
    suspend fun load(db: SelfWatchDb): ActiveWatch? {
        val d = db.devices.active().firstOrNull { DeviceKinds.isWatch(it.kind) } ?: return null
        val state = WatchStatus.decode(db.settings.get(WatchStatus.stateKey(d.id)))
        return ActiveWatch(d.id, d.name, d.features, state is ConnectionState.Ready || state is ConnectionState.Syncing)
    }
}

/** Which runtime permissions and special accesses the app holds (faked in tests). */
interface PermissionChecker {
    /** The device's API level. */
    val sdkInt: Int

    /** True when runtime [permission] is granted. */
    fun granted(permission: String): Boolean

    /** True when the notification listener access is granted. */
    fun notificationAccess(): Boolean
}

/** [PermissionChecker] on Android. */
class AndroidPermissionChecker(ctx: Context) : PermissionChecker {
    private val app: Context = ctx.applicationContext ?: ctx

    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    override fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    override fun notificationAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)

    /** Intents for the system screens the settings open. */
    companion object {
        /**
         * The notification-access screen: on API 30+ the detail page of our listener, else the list.
         * Callers fall back to [Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS] if the first can't open.
         */
        fun notificationAccessIntent(ctx: Context, sdkInt: Int = Build.VERSION.SDK_INT): Intent =
            if (sdkInt >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(ctx, NotificationRelay::class.java).flattenToString(),
                )
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
    }
}

/** Commands to the service the Devices sub-screens send (faked in tests). Each returns false when Android refused. */
interface WatchCommands {
    /** A watch-app request; the result arrives as `WatchAppResults`. */
    fun watchApp(req: WatchAppRequest): Boolean

    /** Ring or stop ringing watch [deviceId]. */
    fun findWatch(deviceId: Long, ring: Boolean): Boolean

    /** Send watch [deviceId]'s stored alarms. */
    fun setAlarms(deviceId: Long): Boolean

    /** Show a test notification on the connected watches. */
    fun testNotification(): Boolean

    /** Sync every watch now. */
    fun syncNow(): Boolean
}

/** [WatchCommands] through [SyncCommands]. */
class ServiceWatchCommands(ctx: Context) : WatchCommands {
    private val app: Context = ctx.applicationContext ?: ctx

    override fun watchApp(req: WatchAppRequest): Boolean = SyncCommands.watchApp(app, req)

    override fun findWatch(deviceId: Long, ring: Boolean): Boolean = SyncCommands.findWatch(app, deviceId, ring)

    override fun setAlarms(deviceId: Long): Boolean = SyncCommands.setAlarms(app, deviceId)

    override fun testNotification(): Boolean = SyncCommands.testNotification(app)

    override fun syncNow(): Boolean = SyncCommands.start(app, SyncCommands.ACTION_SYNC_NOW)
}

/** A phone app as the settings show it. */
data class PhoneApp(
    /** Package name. */
    val packageName: String,
    /** Label shown. */
    val label: String,
)

/** Installed phone apps (faked in tests). */
interface AppCatalog {
    /** The label of [packageName], or null when it isn't visible or installed. */
    fun label(packageName: String): String?

    /** Apps with a launcher entry, sorted by label, without this app. */
    fun launcherApps(): List<PhoneApp>
}

/** [AppCatalog] on the package manager (launcher apps are visible through the manifest's `<queries>`). */
class PackageManagerCatalog(ctx: Context) : AppCatalog {
    private val app: Context = ctx.applicationContext ?: ctx

    override fun label(packageName: String): String? = try {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    override fun launcherApps(): List<PhoneApp> = try {
        val pm = app.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName }
            .filter { it != app.packageName }
            .distinct()
            .map { PhoneApp(it, label(it) ?: it) }
            .sortedBy { it.label.lowercase() }
    } catch (e: RuntimeException) {
        SwLog.w(TAG, "could not list launcher apps", e)
        emptyList()
    }

    private companion object {
        const val TAG = "PackageManagerCatalog"
    }
}
