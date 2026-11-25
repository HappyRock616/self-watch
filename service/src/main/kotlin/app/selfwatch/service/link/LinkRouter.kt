/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.link.ILinkCallback
import app.selfwatch.link.client.LinkCodec
import app.selfwatch.link.client.LinkCodecException
import app.selfwatch.link.client.LinkStatus
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.log.SwLog
import app.selfwatch.service.device.DeviceKinds
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Clock
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.service.device.AppMessageHandler
import app.selfwatch.service.device.WatchLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** Sends watch messages to a phone app that is not bound right now. */
public fun interface LinkBroadcaster {
    /**
     * Sends [message] (LinkCodec bytes; [json] the same value as JSON text, for Gadgetbridge-style apps)
     * from watch app [appUuid] on watch [deviceId] to [packageName] only.
     */
    public fun broadcast(packageName: String, deviceId: Long, appUuid: UUID, message: ByteArray, json: String)
}

/**
 * The explicit, package-restricted broadcast [ACTION_MESSAGE] that carries watch messages to approved
 * apps that are not bound. Extras: [EXTRA_DEVICE_ID] (`Long`), [EXTRA_APP_UUID] (`String`),
 * [EXTRA_MESSAGE] (`byte[]`, LinkCodec) and [EXTRA_PAYLOAD] (`String`, the same value as JSON).
 */
public class AndroidLinkBroadcaster(private val ctx: Context) : LinkBroadcaster {
    override fun broadcast(packageName: String, deviceId: Long, appUuid: UUID, message: ByteArray, json: String) {
        val i = Intent(ACTION_MESSAGE)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .putExtra(EXTRA_DEVICE_ID, deviceId)
            .putExtra(EXTRA_APP_UUID, appUuid.toString())
            .putExtra(EXTRA_MESSAGE, message)
            .putExtra(EXTRA_PAYLOAD, json)
        try {
            ctx.sendBroadcast(i)
        } catch (e: RuntimeException) {
            SwLog.w(TAG, "link broadcast to $packageName failed", e)
        }
    }

    /** The broadcast's action and extras. */
    public companion object {
        private const val TAG = "LinkBroadcast"

        /** Action of the broadcast to an approved app. */
        public const val ACTION_MESSAGE: String = "app.selfwatch.link.MESSAGE"

        /** Extra: the `device` row id (`Long`). */
        public const val EXTRA_DEVICE_ID: String = "deviceId"

        /** Extra: the watch app UUID (`String`). */
        public const val EXTRA_APP_UUID: String = "appUuid"

        /** Extra: the message as LinkCodec bytes (`byte[]`). */
        public const val EXTRA_MESSAGE: String = "message"

        /** Extra: the message as JSON text (`String`), for apps written for Gadgetbridge. */
        public const val EXTRA_PAYLOAD: String = "payload"
    }
}

/**
 * Routes watch-app messages between the watches and linked phone apps (F06).
 *
 * - **Watch → phone** ([onAppMessage]): to the callback registered for the watch app; with no callback
 *   (or a dead one) the message is broadcast to every approved package ([LinkBroadcaster]) and kept in an
 *   inbox (at most [INBOX_MAX] per app, for [INBOX_TTL_SEC]) that is replayed when an app registers.
 * - **Phone → watch** ([send]): decoded (LinkCodec), checked (connected, installed, size) and queued in
 *   the [SendQueue]; the result reaches the sender's callback as `onSendResult`.
 * - Registrations die with their client ([IBinder.DeathRecipient]) and with their permission (revoked
 *   rows are noticed through the database's change notices, see [dropRevoked]).
 *
 * Permission checks happen before, in [LinkService] and the Gadgetbridge receiver.
 */
public class LinkRouter(
    private val db: SelfWatchDb,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val watches: () -> WatchAccess?,
    private val sizer: () -> MessageSizer,
    private val broadcaster: LinkBroadcaster,
    private val maxWireBytes: Int = MAX_WIRE_BYTES,
) : AppMessageHandler {

    /** One phone app listening for one watch app. */
    public class Registration internal constructor(
        /** The watch app. */
        public val appUuid: UUID,
        /** The approved package. */
        public val packageName: String,
        /** The caller's uid. */
        public val uid: Int,
        /** Where messages go. */
        public val callback: ILinkCallback,
        internal val death: IBinder.DeathRecipient,
    )

    private class Pending(val deviceId: Long, val message: ByteArray, val tSec: Long)

    private class Waiting(val uid: Int, val callback: ILinkCallback)

    private val registrations = ConcurrentHashMap<UUID, Registration>()
    private val waiting = ConcurrentHashMap<Pair<String, UUID>, Waiting>()

    /**
     * One [dropRevoked] at a time: a run that read the permission table before a change must not
     * remove a registration that a later run (which saw the change) just made.
     */
    private val revokeLock = kotlinx.coroutines.sync.Mutex()
    private val inbox = HashMap<UUID, ArrayDeque<Pending>>()
    private val queue = SendQueue(scope, { id, cmd -> watches()?.send(id, cmd) ?: Outcome.Fail(SwError.NotConnected(id)) })

    init {
        scope.launch {
            try {
                db.changes.observe(setOf(Table.LINK_PERMISSION), emitInitial = false).collect { dropRevoked() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "permission watch stopped", e)
            }
        }
    }

    /** The registration for [appUuid], or null. */
    public fun registration(appUuid: UUID): Registration? = registrations[appUuid]

    /**
     * Registers [callback] of [packageName] (uid [uid]) for [appUuid], replacing any earlier one, and
     * delivers the messages waiting in the inbox.
     */
    public fun register(appUuid: UUID, packageName: String, uid: Int, callback: ILinkCallback) {
        val binder = callback.asBinder()
        val death = IBinder.DeathRecipient { onDied(appUuid, binder) }
        try {
            binder.linkToDeath(death, 0)
        } catch (e: RemoteException) {
            SwLog.i(TAG, "client of $appUuid died while registering")
            return
        }
        val reg = Registration(appUuid, packageName, uid, callback, death)
        registrations.put(appUuid, reg)?.let { old -> if (old.callback.asBinder() !== binder) unlink(old) }
        for (p in takeInbox(appUuid)) {
            if (!deliverTo(reg, p.deviceId, p.message)) break
        }
    }

    /** Removes the registration for [appUuid] if it belongs to [uid]. */
    public fun unregister(appUuid: UUID, uid: Int) {
        val reg = registrations[appUuid] ?: return
        if (reg.uid != uid) return
        if (registrations.remove(appUuid, reg)) unlink(reg)
    }

    /**
     * Keeps [callback] of [packageName] until the user answers the approval question for [appUuid]: an
     * Allow registers it ([register]), a Deny drops it.
     */
    public fun registerWhenApproved(appUuid: UUID, packageName: String, uid: Int, callback: ILinkCallback) {
        waiting[packageName to appUuid] = Waiting(uid, callback)
    }

    /**
     * Follows the `link_permission` table: removes every registration whose row is gone or denied (a
     * revoke), and registers waiting callbacks whose app was just allowed ([registerWhenApproved]).
     */
    public suspend fun dropRevoked(): Unit = revokeLock.withLock { dropRevokedLocked() }

    private suspend fun dropRevokedLocked() {
        val rows = db.links.list()
        val allowed = rows.filter { it.allowed }.mapTo(HashSet()) { it.packageName to it.appUuid }
        for (reg in registrations.values) {
            if ((reg.packageName to reg.appUuid) !in allowed && registrations.remove(reg.appUuid, reg)) {
                SwLog.i(TAG, "${reg.packageName} lost its permission for ${reg.appUuid}; unregistered")
                unlink(reg)
            }
        }
        val decided = rows.mapTo(HashSet()) { it.packageName to it.appUuid }
        for ((key, w) in waiting.entries.toList()) {
            if (key !in decided) continue
            if (!waiting.remove(key, w)) continue
            if (key in allowed) register(key.second, key.first, w.uid, w.callback)
        }
    }

    override suspend fun onAppMessage(from: WatchLink, event: WatchEvent.AppMessageReceived) {
        deliver(from.deviceId, event.appUuid, event.payload)
    }

    /** Delivers [payload] from watch app [appUuid] on watch [deviceId] (see the class description). */
    public suspend fun deliver(deviceId: Long, appUuid: UUID, payload: CiqValue) {
        val bytes = try {
            LinkCodec.encode(LinkValues.fromCiq(payload))
        } catch (e: IllegalArgumentException) {
            SwLog.w(TAG, "watch message from $appUuid can't be passed on: ${e.message}")
            return
        }
        val reg = registrations[appUuid]
        if (reg != null && deliverTo(reg, deviceId, bytes)) return
        keep(appUuid, Pending(deviceId, bytes, clock.nowSec()))
        val json = LinkValues.toJsonText(payload)
        for (pkg in approvedPackages(appUuid)) broadcaster.broadcast(pkg, deviceId, appUuid, bytes, json)
    }

    /** The messages waiting for [appUuid] (newer than [INBOX_TTL_SEC]), removing them. */
    private fun takeInbox(appUuid: UUID): List<Pending> = synchronized(inbox) {
        val q = inbox.remove(appUuid) ?: return emptyList()
        val cutoff = clock.nowSec() - INBOX_TTL_SEC
        q.filter { it.tSec >= cutoff }
    }

    /** How many messages wait for [appUuid] (for tests and the screens). */
    public fun inboxSize(appUuid: UUID): Int = synchronized(inbox) {
        val cutoff = clock.nowSec() - INBOX_TTL_SEC
        inbox[appUuid]?.count { it.tSec >= cutoff } ?: 0
    }

    private fun keep(appUuid: UUID, p: Pending) = synchronized(inbox) {
        val q = inbox.getOrPut(appUuid) { ArrayDeque() }
        val cutoff = p.tSec - INBOX_TTL_SEC
        while (q.isNotEmpty() && q.first().tSec < cutoff) q.removeFirst()
        q.addLast(p)
        while (q.size > INBOX_MAX) q.removeFirst()
    }

    private fun deliverTo(reg: Registration, deviceId: Long, bytes: ByteArray): Boolean = try {
        reg.callback.onMessage(deviceId, reg.appUuid.toString(), bytes)
        true
    } catch (e: RemoteException) {
        SwLog.i(TAG, "callback of ${reg.packageName} is gone")
        if (registrations.remove(reg.appUuid, reg)) unlink(reg)
        false
    } catch (e: RuntimeException) {
        SwLog.w(TAG, "callback of ${reg.packageName} failed", e)
        false
    }

    /** The real packages approved for [appUuid]. */
    public suspend fun approvedPackages(appUuid: UUID): List<String> =
        db.links.list().filter { it.appUuid == appUuid && it.allowed && it.packageName != LinkPermissions.UNKNOWN_PACKAGE }.map { it.packageName }

    /**
     * Queues [message] (LinkCodec bytes) for [appUuid] on [deviceId]. Returns [LinkStatus.SUCCESS] when
     * queued (the result follows through [onResult]), or an error code at once: INVALID_MESSAGE,
     * NOT_CONNECTED, NOT_INSTALLED, TOO_LARGE or FAILED (queue full).
     */
    public suspend fun send(deviceId: Long, appUuid: UUID, message: ByteArray, onResult: (Int) -> Unit): Int {
        val value = try {
            LinkValues.toCiq(LinkCodec.decode(message))
        } catch (e: LinkCodecException) {
            SwLog.i(TAG, "bad message for $appUuid: ${e.message}")
            return LinkStatus.INVALID_MESSAGE
        } catch (e: IllegalArgumentException) {
            return LinkStatus.INVALID_MESSAGE
        }
        return sendValue(deviceId, appUuid, value, onResult)
    }

    /** As [send], for a value that is already a [CiqValue] (the Gadgetbridge Intent, CaloriesLeft). */
    public suspend fun sendValue(deviceId: Long, appUuid: UUID, value: CiqValue, onResult: (Int) -> Unit): Int {
        if (!isConnected(deviceId)) return LinkStatus.NOT_CONNECTED
        if (db.watchApps.get(deviceId, appUuid)?.installed == false) return LinkStatus.NOT_INSTALLED
        val size = sizer().wireBytes(value)
        if (size > maxWireBytes) {
            SwLog.i(TAG, "message for $appUuid is $size bytes; the watch takes $maxWireBytes")
            return LinkStatus.TOO_LARGE
        }
        return if (queue.enqueue(deviceId, appUuid, value, onResult)) LinkStatus.SUCCESS else LinkStatus.FAILED
    }

    /** Asks watch [deviceId] to open [appUuid]. Returns SUCCESS when the request goes out, else NOT_CONNECTED. */
    public fun openApp(deviceId: Long, appUuid: UUID): Int {
        if (!isConnected(deviceId)) return LinkStatus.NOT_CONNECTED
        scope.launch {
            val r = watches()?.send(deviceId, WatchCommand.LaunchApp(appUuid))
            if (r is Outcome.Fail) SwLog.i(TAG, "open $appUuid on $deviceId failed: ${r.error}")
        }
        return LinkStatus.SUCCESS
    }

    /** The paired watches as JSON strings `{"id":…,"name":"…","connected":true}`. */
    public suspend fun devicesJson(): List<String> {
        val live = watches()?.devices?.value.orEmpty().associateBy { it.info.id }
        return db.devices.active().filter { DeviceKinds.isWatch(it.kind) }.map { d ->
            Json.write(
                JsonValue.Obj(
                    linkedMapOf(
                        "id" to JsonValue.Num.of(d.id),
                        "name" to JsonValue.Str(d.name),
                        "connected" to JsonValue.Bool(live[d.id]?.let { isConnectedState(it.state) } ?: false),
                    ),
                ),
            )
        }
    }

    /** `{"installed":true,"version":12}`, `{"installed":false}` or `{"error":4}` when the watch is not connected. */
    public suspend fun appInfoJson(deviceId: Long, appUuid: UUID): String {
        if (!isConnected(deviceId)) return errorJson(LinkStatus.NOT_CONNECTED)
        val row = db.watchApps.get(deviceId, appUuid)
        val fields = LinkedHashMap<String, JsonValue>()
        fields["installed"] = JsonValue.Bool(row?.installed == true)
        if (row?.installed == true) row.version?.let { fields["version"] = JsonValue.Num.of(it.toLong()) }
        return Json.write(JsonValue.Obj(fields))
    }

    /**
     * Tells every registered app when a watch connects or disconnects (`onDeviceStatus`); called once by
     * [LinkHub] with its attached watches.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    public fun watchDeviceStatus(access: StateFlow<WatchAccess?>) {
        scope.launch {
            val known = HashMap<Long, Boolean>()
            access.flatMapLatest { it?.devices ?: emptyFlow() }.collect { list ->
                val now = list.associate { it.info.id to isConnectedState(it.state) }
                for ((id, c) in now) if (known[id] != c) notifyDevice(id, c)
                for (id in known.keys - now.keys) if (known[id] == true) notifyDevice(id, false)
                known.clear()
                known.putAll(now)
            }
        }
    }

    private fun notifyDevice(deviceId: Long, connected: Boolean) {
        for (reg in registrations.values) {
            try {
                reg.callback.onDeviceStatus(deviceId, connected)
            } catch (e: RemoteException) {
                if (registrations.remove(reg.appUuid, reg)) unlink(reg)
            }
        }
    }

    private fun isConnected(deviceId: Long): Boolean =
        watches()?.devices?.value?.any { it.info.id == deviceId && isConnectedState(it.state) } == true

    private fun onDied(appUuid: UUID, binder: IBinder) {
        val reg = registrations[appUuid] ?: return
        if (reg.callback.asBinder() === binder && registrations.remove(appUuid, reg)) {
            SwLog.i(TAG, "client of $appUuid died; unregistered")
        }
    }

    private fun unlink(reg: Registration) {
        try {
            reg.callback.asBinder().unlinkToDeath(reg.death, 0)
        } catch (e: RuntimeException) {
            SwLog.d(TAG) { "unlinkToDeath: $e" }
        }
    }

    /** Limits. */
    public companion object {
        private const val TAG = "LinkRouter"

        /** Messages kept per watch app while no app is registered. */
        public const val INBOX_MAX: Int = 20

        /** How long kept messages stay (seconds). */
        public const val INBOX_TTL_SEC: Long = 10 * 60

        /**
         * Largest encoded app message at the default packet size: 14 fragments of 19 bytes
         * (Ref: Gadgetbridge 160beab41 GarminWatchAppCommunicator.java MAX_MESSAGE_SIZE, 14 fragments).
         */
        public const val MAX_WIRE_BYTES: Int = 14 * 19

        /** `{"error":code}`. */
        public fun errorJson(code: Int): String = Json.write(JsonValue.Obj(mapOf("error" to JsonValue.Num.of(code.toLong()))))
    }
}
