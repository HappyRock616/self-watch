/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.link.ILinkCallback
import app.selfwatch.link.ILinkService
import app.selfwatch.link.client.LinkStatus
import app.selfwatch.model.log.SwLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Setting keys of the link (generic `setting` rows). */
public object LinkSettings {
    /** Other apps may use the link (bool, default true). Off: every call answers DENIED. */
    public const val ENABLED: String = SettingKeys.LINK_ENABLED

    /** The watch app that gets the calories left ([CaloriesLeft]), a UUID; absent = off. */
    public const val CALORIES_APP: String = SettingKeys.LINK_CALORIES_APP
}

/**
 * The `ILinkService` implementation, separate from the Android service so tests can call it with a
 * chosen calling uid. Every call is checked by [permissions] for the calling uid and watch app first.
 * Calls arrive on binder threads, so blocking briefly for the database is fine.
 */
public class LinkBinder(
    private val db: SelfWatchDb,
    private val permissions: LinkPermissions,
    private val router: LinkRouter,
    private val callingUid: () -> Int = { Binder.getCallingUid() },
) : ILinkService.Stub() {

    override fun apiVersion(): Int = API_VERSION

    override fun devices(): List<String> = guarded(emptyList()) {
        if (!enabled()) emptyList() else router.devicesJson()
    }

    override fun appInfo(deviceId: Long, appUuid: String?): String {
        val uid = callingUid()
        val uuid = parse(appUuid) ?: return LinkRouter.errorJson(LinkStatus.INVALID_MESSAGE)
        return guarded(LinkRouter.errorJson(LinkStatus.FAILED)) {
            when (val d = decide(uid, uuid)) {
                is LinkDecision.Allowed -> router.appInfoJson(deviceId, uuid)
                else -> LinkRouter.errorJson(d.status)
            }
        }
    }

    override fun register(appUuid: String?, cb: ILinkCallback?) {
        val uid = callingUid()
        val uuid = parse(appUuid) ?: return
        if (cb == null) return
        guarded(Unit) {
            when (val d = decide(uid, uuid)) {
                is LinkDecision.Allowed -> router.register(uuid, d.packageName, uid, cb)
                LinkDecision.Pending -> permissions.packagesForUid(uid).firstOrNull()?.let { router.registerWhenApproved(uuid, it, uid, cb) }
                is LinkDecision.Denied -> SwLog.i(TAG, "register for $uuid denied: ${d.reason}")
            }
        }
    }

    override fun unregister(appUuid: String?) {
        val uid = callingUid()
        val uuid = parse(appUuid) ?: return
        router.unregister(uuid, uid)
    }

    override fun send(deviceId: Long, appUuid: String?, message: ByteArray?, requestId: Int): Int {
        val uid = callingUid()
        val uuid = parse(appUuid) ?: return LinkStatus.INVALID_MESSAGE
        if (message == null) return LinkStatus.INVALID_MESSAGE
        return guarded(LinkStatus.FAILED) {
            when (val d = decide(uid, uuid)) {
                is LinkDecision.Allowed -> {
                    val cb = router.registration(uuid)?.takeIf { it.uid == uid }?.callback
                    router.send(deviceId, uuid, message) { status -> report(cb, requestId, status) }
                }
                else -> d.status
            }
        }
    }

    override fun openApp(deviceId: Long, appUuid: String?): Int {
        val uid = callingUid()
        val uuid = parse(appUuid) ?: return LinkStatus.INVALID_MESSAGE
        return guarded(LinkStatus.FAILED) {
            when (val d = decide(uid, uuid)) {
                is LinkDecision.Allowed -> router.openApp(deviceId, uuid)
                else -> d.status
            }
        }
    }

    private suspend fun decide(uid: Int, uuid: UUID): LinkDecision =
        if (!enabled()) LinkDecision.Denied("link switched off") else permissions.checkUid(uid, uuid)

    private suspend fun enabled(): Boolean = db.settings.getBool(LinkSettings.ENABLED, true)

    private fun report(cb: ILinkCallback?, requestId: Int, status: Int) {
        try {
            cb?.onSendResult(requestId, status)
        } catch (e: RemoteException) {
            SwLog.i(TAG, "send result for $requestId not delivered (client gone)")
        }
    }

    private fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        runBlocking(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SwLog.e(TAG, "link call failed", e)
        fallback
    }

    private fun parse(s: String?): UUID? = try {
        s?.let { UUID.fromString(it) }
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Constants. */
    public companion object {
        private const val TAG = "LinkService"

        /** The interface version answered by `apiVersion()`. */
        public const val API_VERSION: Int = 1
    }
}

/**
 * The bound service other apps reach with `Intent("app.selfwatch.link.BIND").setPackage("app.selfwatch")`
 * (WP-13; exported, process `:watch`). It returns the [LinkBinder] for that action and nothing otherwise.
 */
public class LinkService : Service() {
    private val binder: LinkBinder by lazy {
        LinkBinder(SelfWatchDb.get(this), LinkHub.permissions(this), LinkHub.router(this))
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == ACTION_BIND) binder else null

    /** Constants. */
    public companion object {
        /** The bind action. */
        public const val ACTION_BIND: String = "app.selfwatch.link.BIND"
    }
}
