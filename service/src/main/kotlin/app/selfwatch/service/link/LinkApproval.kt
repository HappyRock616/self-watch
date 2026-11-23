/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.log.SwLog
import app.selfwatch.service.device.DeviceKinds
import app.selfwatch.service.R
import app.selfwatch.service.notify.ServiceNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The approval question as a notification on channel `alerts`: "Allow Third Coast Sailing to message its
 * watch app on your vívoactive 5?" with Allow and Deny actions. Each action is an immutable broadcast
 * [PendingIntent] to [LinkApprovalReceiver] (not exported), carrying the package, watch app and the
 * certificate seen when asking, so a look-alike installed later can't inherit the answer.
 */
public class NotificationApprovalPrompt(ctx: Context) : ApprovalPrompt {
    private val app: Context = ctx.applicationContext ?: ctx

    override suspend fun ask(request: ApprovalRequest) {
        if (!ServiceNotifications.canPost(app)) {
            SwLog.i(TAG, "can't show the link approval (notifications are off)")
            return
        }
        val id = notificationId(request.packageName, request.appUuid)
        val who = request.packageName?.let { label(it) }
        val names = describe(request.appUuid)
        val title = if (who != null) {
            app.getString(R.string.svc_link_ask_title, who)
        } else {
            app.getString(R.string.svc_link_ask_title_unknown)
        }
        val watchApp = names.first ?: request.appUuid.toString()
        val watch = names.second ?: app.getString(R.string.svc_link_ask_your_watch)
        val text = app.getString(R.string.svc_link_ask_text, watchApp, watch)
        val n = NotificationCompat.Builder(app, ServiceNotifications.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.svc_ic_watch)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(false)
            .addAction(0, app.getString(R.string.svc_link_allow), action(request, true, id))
            .addAction(0, app.getString(R.string.svc_link_deny), action(request, false, id))
            .build()
        try {
            NotificationManagerCompat.from(app).notify(id, n)
        } catch (e: SecurityException) {
            SwLog.w(TAG, "approval notification refused", e)
        }
    }

    override fun cancel(packageName: String?, appUuid: UUID) {
        NotificationManagerCompat.from(app).cancel(notificationId(packageName, appUuid))
    }

    private fun action(r: ApprovalRequest, allow: Boolean, id: Int): PendingIntent {
        val pkg = r.packageName ?: LinkPermissions.UNKNOWN_PACKAGE
        val i = Intent(app, LinkApprovalReceiver::class.java)
            .setAction(if (allow) LinkApprovalReceiver.ACTION_ALLOW else LinkApprovalReceiver.ACTION_DENY)
            // Distinct data per question, so the PendingIntents of two questions never merge.
            .setData(Uri.Builder().scheme("selfwatch-link").authority("approve").appendPath(pkg).appendPath(r.appUuid.toString()).build())
            .putExtra(LinkApprovalReceiver.EXTRA_PACKAGE, pkg)
            .putExtra(LinkApprovalReceiver.EXTRA_APP_UUID, r.appUuid.toString())
            .putExtra(LinkApprovalReceiver.EXTRA_CERT, r.certSha256)
        return PendingIntent.getBroadcast(app, id * 2 + if (allow) 0 else 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun label(pkg: String): String = try {
        val pm = app.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
        pm.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    /** The watch app's name and the first watch's name, when known. */
    private suspend fun describe(appUuid: UUID): Pair<String?, String?> = try {

        withContext(Dispatchers.IO) {
            val db = SelfWatchDb.get(app)
            val devices = db.devices.active().filter { DeviceKinds.isWatch(it.kind) }
            val appName = devices.firstNotNullOfOrNull { d -> db.watchApps.get(d.id, appUuid)?.name }
            appName to devices.firstOrNull()?.name
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SwLog.w(TAG, "could not read the watch names", e)
        null to null
    }

    /** Ids. */
    public companion object {
        private const val TAG = "LinkApproval"
        private const val ID_BASE = 0x4C00_0000

        /** The notification id of the question for [packageName] (null = unknown app) and [appUuid]. */
        public fun notificationId(packageName: String?, appUuid: UUID): Int =
            ID_BASE + (((packageName ?: LinkPermissions.UNKNOWN_PACKAGE) + "/" + appUuid).hashCode() and 0xFFFFF)
    }
}

/**
 * Receives Allow / Deny from the approval notification (in `:watch`, not exported: only the
 * notification's immutable PendingIntents reach it) and stores the answer through [LinkPermissions].
 */
public class LinkApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val allow = when (intent.action) {
            ACTION_ALLOW -> true
            ACTION_DENY -> false
            else -> return
        }
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val uuid = intent.getStringExtra(EXTRA_APP_UUID)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val cert = intent.getStringExtra(EXTRA_CERT) ?: return
        val result: PendingResult? = goAsync()
        scope.launch {
            try {
                LinkHub.permissions(context).decide(pkg, uuid, cert, allow)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.e(TAG, "could not store the link decision", e)
            } finally {
                result?.finish()
            }
        }
    }

    /** Actions and extras. */
    public companion object {
        private const val TAG = "LinkApproval"

        /** The receiver's own scope (each job ends with its broadcast). */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Allow. */
        public const val ACTION_ALLOW: String = "app.selfwatch.service.link.ALLOW"

        /** Deny. */
        public const val ACTION_DENY: String = "app.selfwatch.service.link.DENY"

        /** Extra: package name (`String`), [LinkPermissions.UNKNOWN_PACKAGE] for an unknown app. */
        public const val EXTRA_PACKAGE: String = "app.selfwatch.service.link.extra.PACKAGE"

        /** Extra: watch app UUID (`String`). */
        public const val EXTRA_APP_UUID: String = "app.selfwatch.service.link.extra.APP_UUID"

        /** Extra: the certificate digest seen when asking (`String`). */
        public const val EXTRA_CERT: String = "app.selfwatch.service.link.extra.CERT"
    }
}
