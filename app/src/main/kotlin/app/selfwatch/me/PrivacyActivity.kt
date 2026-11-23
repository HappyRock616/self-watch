/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.selfwatch.R
import app.selfwatch.data.AndroidHcOps
import app.selfwatch.food.FoodViews
import app.selfwatch.graph
import app.selfwatch.interop.hc.HcStatus
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.net.NetFeature
import app.selfwatch.ui.components.ListRow

/**
 * Privacy center (C20, DESIGN §11): requests today with a link to Network activity, the promise, a
 * firewall switch for each internet feature, and the permissions the app holds (each asked only at
 * the moment of use; changed in Android's own settings).
 */
class PrivacyActivity : MeScreenActivity() {

    private val vm: PrivacyViewModel by viewModels {
        val ctx = applicationContext
        PrivacyViewModel.factory(graph) { readPermissions(ctx) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen(R.string.me_privacy_center)

        val today = MeViews.card(column, getString(R.string.privacy_today_title))
        val requests = MeViews.body(this, null)
        MeViews.add(today.content, requests, gap = false)
        MeViews.row(today.content, getString(R.string.me_network)) { startActivity(Intent(this, NetworkLogActivity::class.java)) }

        val promise = MeViews.card(column, getString(R.string.privacy_promise_title))
        for (line in resources.getStringArray(R.array.privacy_promises)) MeViews.add(promise.content, MeViews.body(this, "• $line"))

        val net = MeViews.card(column, getString(R.string.privacy_switches_title))
        MeViews.add(net.content, MeViews.caption(this, getString(R.string.privacy_switches_help)), gap = false)
        val switches = NetFeature.entries.associateWith { f ->
            SwitchRow(this, getString(featureLabel(f)), getString(featureHelp(f))).also { row ->
                row.onChange = { on -> vm.setAllowed(f, on) }
                MeViews.add(net.content, row)
            }
        }

        val perms = MeViews.card(column, getString(R.string.privacy_permissions_title))
        MeViews.add(perms.content, MeViews.caption(this, getString(R.string.privacy_permissions_help)), gap = false)
        val permRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        MeViews.add(perms.content, permRows)
        MeViews.add(perms.content, FoodViews.outlinedButton(this, getString(R.string.privacy_manage)) { openAppSettings() })

        collect(vm.state) { s ->
            requests.text = requestsText(this, s.requests)
            for ((f, row) in switches) row.checked = s.switches[f] ?: f.defaultOn
            permRows.removeAllViews()
            for (p in s.permissions) {
                permRows.addView(
                    ListRow(this).apply {
                        title = getString(permissionLabel(p.kind))
                        trailing = getString(if (p.granted) R.string.privacy_allowed else R.string.privacy_not_allowed)
                    },
                    FoodViews.matchWrap(),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
    }

    private fun openAppSettings() {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            SwLog.w(TAG, "no app settings screen", e)
        }
    }

    /** Labels and permission reading. */
    companion object {
        private const val TAG = "PrivacyActivity"

        /** "12 requests today · 2 blocked". */
        fun requestsText(ctx: Context, r: RequestsToday): String {
            val base = ctx.resources.getQuantityString(R.plurals.me_requests_today, r.total, r.total)
            return if (r.blocked > 0) ctx.resources.getQuantityString(R.plurals.me_requests_blocked, r.blocked, base, r.blocked) else base
        }

        /** The switch label for [f]. */
        @StringRes
        fun featureLabel(f: NetFeature): Int = when (f) {
            NetFeature.FOOD_DATA -> R.string.net_food_data
            NetFeature.OPEN_FOOD_FACTS -> R.string.net_open_food_facts
            NetFeature.WATCH_APP_WEB -> R.string.net_watch_app_web
            NetFeature.GPS_ASSIST -> R.string.net_gps_assist
            NetFeature.RECIPE_BOOK -> R.string.net_recipe_book
            NetFeature.LOCAL_PHONE_APP -> R.string.net_local_phone_app
        }

        /** One sentence saying what [f] sends where. */
        @StringRes
        fun featureHelp(f: NetFeature): Int = when (f) {
            NetFeature.FOOD_DATA -> R.string.net_food_data_help
            NetFeature.OPEN_FOOD_FACTS -> R.string.net_open_food_facts_help
            NetFeature.WATCH_APP_WEB -> R.string.net_watch_app_web_help
            NetFeature.GPS_ASSIST -> R.string.net_gps_assist_help
            NetFeature.RECIPE_BOOK -> R.string.net_recipe_book_help
            NetFeature.LOCAL_PHONE_APP -> R.string.net_local_phone_app_help
        }

        /** The label for [k]. */
        @StringRes
        fun permissionLabel(k: PermissionKind): Int = when (k) {
            PermissionKind.NEARBY_DEVICES -> R.string.perm_nearby
            PermissionKind.NOTIFICATIONS -> R.string.perm_notifications
            PermissionKind.NOTIFICATION_ACCESS -> R.string.perm_notification_access
            PermissionKind.CAMERA -> R.string.perm_camera
            PermissionKind.PHONE -> R.string.perm_phone
            PermissionKind.CALL_LOG -> R.string.perm_call_log
            PermissionKind.CONTACTS -> R.string.perm_contacts
            PermissionKind.ANSWER_CALLS -> R.string.perm_answer_calls
            PermissionKind.HEALTH_CONNECT -> R.string.perm_health_connect
        }

        /**
         * The permissions this app declares (and this Android version has), each with whether it is
         * granted now. Health Connect counts as allowed when any of its permissions is granted.
         */
        suspend fun readPermissions(ctx: Context): List<HeldPermission> {
            val declared = declaredPermissions(ctx)
            val out = ArrayList<HeldPermission>()
            fun runtime(kind: PermissionKind, permission: String) {
                if (permission in declared) {
                    out += HeldPermission(kind, ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runtime(PermissionKind.NEARBY_DEVICES, Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) runtime(PermissionKind.NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS)
            if (declaresListener(ctx)) {
                out += HeldPermission(PermissionKind.NOTIFICATION_ACCESS, ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx))
            }
            runtime(PermissionKind.CAMERA, Manifest.permission.CAMERA)
            runtime(PermissionKind.PHONE, Manifest.permission.READ_PHONE_STATE)
            runtime(PermissionKind.CALL_LOG, Manifest.permission.READ_CALL_LOG)
            runtime(PermissionKind.CONTACTS, Manifest.permission.READ_CONTACTS)
            runtime(PermissionKind.ANSWER_CALLS, Manifest.permission.ANSWER_PHONE_CALLS)
            val hc = AndroidHcOps(ctx)
            if (hc.status() == HcStatus.AVAILABLE) out += HeldPermission(PermissionKind.HEALTH_CONNECT, hc.granted().isNotEmpty())
            return out
        }

        private fun declaredPermissions(ctx: Context): Set<String> = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            }
            info.requestedPermissions?.toSet().orEmpty()
        } catch (e: PackageManager.NameNotFoundException) {
            SwLog.w(TAG, "own package not found", e)
            emptySet()
        }

        private fun declaresListener(ctx: Context): Boolean {
            val i = Intent("android.service.notification.NotificationListenerService").setPackage(ctx.packageName)
            return try {
                @Suppress("DEPRECATION")
                ctx.packageManager.queryIntentServices(i, 0).isNotEmpty()
            } catch (e: RuntimeException) {
                SwLog.w(TAG, "listener query failed", e)
                false
            }
        }
    }
}
