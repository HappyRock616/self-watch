/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import android.annotation.SuppressLint
import android.content.Context
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.service.device.DeviceStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The watches as the link sees them. [app.selfwatch.service.SyncService] implements it on its DeviceManager. */
public interface WatchAccess {
    /** Every managed watch and its state. */
    public val devices: StateFlow<List<DeviceStatus>>

    /** Sends [cmd] to watch [deviceId]. */
    public suspend fun send(deviceId: Long, cmd: WatchCommand): Outcome<Unit>
}

/** True when [s] means the watch can take commands. */
public fun isConnectedState(s: ConnectionState): Boolean = s is ConnectionState.Ready || s is ConnectionState.Syncing

/**
 * How many bytes an app message takes on the watch link, for the size check before sending. The app
 * wires the garmin encoding (`AppMessages.pad(CiqCodec.encode(v))`); [ESTIMATE] is a conservative guess.
 */
public fun interface MessageSizer {
    /** The encoded (padded) size of [v] in bytes. */
    public fun wireBytes(v: CiqValue): Int

    /** Defaults. */
    public companion object {
        /**
         * Monkey C serialization estimate: a 14-byte header, 5 bytes per value, strings once in the string
         * table with a 2-byte length and a terminator; padded to a multiple of 4.
         */
        public val ESTIMATE: MessageSizer = MessageSizer { v ->
            val n = 14 + estimate(v) + 1
            (n + 3) / 4 * 4
        }

        private fun estimate(v: CiqValue): Int = when (v) {
            CiqValue.Null, is CiqValue.Bool -> 5
            is CiqValue.Num -> if (v.v in Int.MIN_VALUE..Int.MAX_VALUE) 5 else 9
            is CiqValue.Dbl -> 9
            is CiqValue.Str -> 5 + 3 + v.v.toByteArray(Charsets.UTF_8).size
            is CiqValue.Arr -> 5 + v.v.sumOf { estimate(it) }
            is CiqValue.Dict -> 5 + v.v.entries.sumOf { (k, x) -> estimate(k) + estimate(x) }
        }
    }
}

/**
 * The in-process meeting point of the link inside process `:watch` (like `relay.RelayHub`).
 *
 * Other apps bind [LinkService] whenever they like, while the watches live in
 * [app.selfwatch.service.SyncService]. SyncService calls [attach] with its [WatchAccess] in `onCreate`
 * and [detach] in `onDestroy`, and sets [sizer] from the app's wiring. [router] is the process's one
 * [LinkRouter]; the app's `EventHandlers.appMessage` must be that router so watch messages reach it.
 *
 * The hub owns a process-lifetime scope for the router's send queues (the process is its owner).
 */
@SuppressLint("StaticFieldLeak") // holds only the application context
public object LinkHub {
    private const val TAG = "LinkHub"

    private val access = MutableStateFlow<WatchAccess?>(null)

    @Volatile
    private var routerInstance: LinkRouter? = null

    /** Owner: the process. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> SwLog.e(TAG, "link failure", e) },
    )

    /** The attached watches, or null while no [app.selfwatch.service.SyncService] runs. */
    public val watches: StateFlow<WatchAccess?> = access.asStateFlow()

    /** How app messages are sized before sending (set by the service from the app's wiring). */
    @Volatile
    public var sizer: MessageSizer = MessageSizer.ESTIMATE

    /** Registers the service's watches (replacing any earlier ones). */
    public fun attach(w: WatchAccess) {
        access.value = w
    }

    /** Removes [w] if it is the attached one. */
    public fun detach(w: WatchAccess) {
        access.compareAndSet(w, null)
    }

    /** Sends [cmd] through the attached watches; [SwError.NotConnected] when none are attached. */
    public suspend fun send(deviceId: Long, cmd: WatchCommand): Outcome<Unit> =
        access.value?.send(deviceId, cmd) ?: Outcome.Fail(SwError.NotConnected(deviceId))

    /** The process's [LinkRouter] (created on first use). */
    public fun router(ctx: Context): LinkRouter = routerInstance ?: synchronized(this) {
        routerInstance ?: run {
            val app = ctx.applicationContext ?: ctx
            val db = SelfWatchDb.get(app)
            LinkRouter(
                db = db,
                clock = SystemClock,
                scope = scope,
                watches = { access.value },
                sizer = { sizer },
                broadcaster = AndroidLinkBroadcaster(app),
            ).also { r ->
                r.watchDeviceStatus(access)
                routerInstance = r
            }
        }
    }

    /** The process's [LinkPermissions] (created on first use; it remembers which questions are showing). */
    public fun permissions(ctx: Context): LinkPermissions = permissionsInstance ?: synchronized(this) {
        permissionsInstance ?: run {
            val app = ctx.applicationContext ?: ctx
            LinkPermissions(app.packageManager, SelfWatchDb.get(app).links, SystemClock, NotificationApprovalPrompt(app))
                .also { permissionsInstance = it }
        }
    }

    @Volatile
    private var permissionsInstance: LinkPermissions? = null
}
