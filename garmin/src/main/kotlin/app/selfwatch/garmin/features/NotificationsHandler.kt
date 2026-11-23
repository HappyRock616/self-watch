/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.gfdi.ByteWriter
import app.selfwatch.garmin.gfdi.Bytes
import app.selfwatch.garmin.gfdi.FeatureReplies
import app.selfwatch.garmin.gfdi.GfdiId
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.GfdiStatus
import app.selfwatch.garmin.gfdi.MalformedException
import app.selfwatch.garmin.gfdi.NotificationActionCode as Act
import app.selfwatch.garmin.gfdi.NotificationActionRequest
import app.selfwatch.garmin.gfdi.NotificationAppAttributesRequest
import app.selfwatch.garmin.gfdi.NotificationAttribute as Attr
import app.selfwatch.garmin.gfdi.NotificationAttributesRequest
import app.selfwatch.garmin.gfdi.NotificationCommand
import app.selfwatch.garmin.gfdi.NotificationData
import app.selfwatch.garmin.gfdi.NotificationLegacyAction
import app.selfwatch.garmin.gfdi.NotificationSubscription
import app.selfwatch.garmin.gfdi.NotificationUpdate
import app.selfwatch.garmin.gfdi.utf8Prefix
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.model.crc.Crc16
import app.selfwatch.model.device.Feature
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Clock
import app.selfwatch.model.watch.NotificationCategory
import app.selfwatch.model.watch.PhoneNotification
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Notifications and calls over Garmin's notification service (GNCS, A16), only when the watch has the
 * `GNCS` capability (otherwise its messages get UNSUPPORTED and commands fail with Unsupported).
 *
 * Flow (Ref: Gadgetbridge 160beab41 service/devices/garmin/NotificationsHandler.java and GarminSupport.java):
 * 1. The watch subscribes (NOTIFICATION_SUBSCRIPTION); we answer ENABLED and echo its flag. Until then
 *    notifications are dropped, as in the reference.
 * 2. [WatchCommand.Notify] caches the notification (newest [MAX_CACHED]) and sends NOTIFICATION_UPDATE
 *    (ADD, or MODIFY for a known id).
 * 3. The watch asks for attributes (NOTIFICATION_CONTROL command 0); we answer the control status, then
 *    send the attribute data in NOTIFICATION_DATA chunks of at most [CHUNK_BYTES], each with the running
 *    CRC-16, waiting for the watch's status after each. Text is cut to the length the watch asked for.
 * 4. Actions (command 128) become [WatchEvent.CallAction] / [WatchEvent.NotificationAction].
 *
 * Calls are notifications in the CALL category whose actions are the watch's built-in reply, reject
 * and accept; [WatchCommand.CallEnded] removes the call notification.
 */
public class NotificationsHandler(
    private val clock: Clock,
    private val requestTimeoutMs: Long = 10_000,
) : GfdiHandler {
    private val lock = Any()
    private var state: State? = null

    private class State(val session: GarminSessionContext) {
        var enabled = false
        val cache = LinkedHashMap<Int, PhoneNotification>()
        var callId: Int? = null
        val uploadLock = Mutex()
    }

    private fun stateFor(session: GarminSessionContext): State = synchronized(lock) {
        state?.takeIf { it.session === session } ?: State(session).also { state = it }
    }

    private fun supported(session: GarminSessionContext) = GarminCapability.GNCS in session.capabilities

    override suspend fun command(cmd: WatchCommand, session: GarminSessionContext): Outcome<Unit>? {
        val feature = when (cmd) {
            is WatchCommand.Notify, is WatchCommand.RemoveNotification -> Feature.NOTIFICATIONS
            is WatchCommand.IncomingCall, WatchCommand.CallEnded -> Feature.CALLS
            else -> return null
        }
        if (!supported(session)) return Outcome.Fail(SwError.Unsupported(feature))
        val st = stateFor(session)
        val update = synchronized(lock) {
            when (cmd) {
                is WatchCommand.Notify -> if (st.enabled) add(st, cmd.n) else null
                is WatchCommand.RemoveNotification -> if (st.enabled) remove(st, cmd.id) else null
                is WatchCommand.IncomingCall -> if (st.enabled) {
                    val n = callNotification(cmd)
                    st.callId = n.id
                    add(st, n)
                } else {
                    null
                }
                WatchCommand.CallEnded -> {
                    val id = st.callId
                    st.callId = null
                    if (st.enabled && id != null) remove(st, id) else null
                }
            }
        }
        if (update == null) {
            if (!st.enabled) SwLog.d(TAG) { "watch has not subscribed to notifications; dropped $cmd" }
            return Outcome.Ok(Unit)
        }
        return session.channel.send(update)
    }

    // Ref: Gadgetbridge 160beab41 NotificationsHandler.onSetCallState (id = number's hash, title = name or number,
    // generic phone type, an empty action so the watch shows its built-in call actions)
    private fun callNotification(c: WatchCommand.IncomingCall): PhoneNotification {
        val caller = listOf(c.name, c.number).firstOrNull { !it.isNullOrBlank() } ?: UNKNOWN_CALLER
        val id = (c.number?.takeIf { it.isNotBlank() } ?: CALL_ID_SEED).hashCode()
        return PhoneNotification(id, "", PHONE_APP_NAME, caller, caller, clock.nowSec(), NotificationCategory.CALL, false, emptyList())
    }

    /** Caller holds [lock]. */
    private fun add(st: State, n: PhoneNotification): NotificationUpdate {
        val existed = st.cache.remove(n.id) != null
        st.cache[n.id] = n
        while (st.cache.size > MAX_CACHED) st.cache.remove(st.cache.keys.first())
        val phoneFlags = (if (actions(n).isNotEmpty()) NotificationUpdate.PHONE_NEW_ACTIONS else 0)
        return NotificationUpdate(
            if (existed) NotificationUpdate.MODIFY else NotificationUpdate.ADD,
            CATEGORY_FLAGS, categoryValue(n.category), count(st, n.category), n.id, phoneFlags,
        )
    }

    /** Caller holds [lock]. */
    private fun remove(st: State, id: Int): NotificationUpdate {
        val n = st.cache.remove(id)
        val category = n?.category ?: NotificationCategory.OTHER
        return NotificationUpdate(NotificationUpdate.REMOVE, CATEGORY_FLAGS, if (n == null) 0 else categoryValue(category), count(st, category), id, 0)
    }

    private fun count(st: State, c: NotificationCategory): Int = minOf(255, st.cache.values.count { it.category == c })

    override suspend fun handle(m: GfdiMessage, session: GarminSessionContext): Boolean {
        val isOurs = m is NotificationSubscription || m is NotificationAttributesRequest || m is NotificationAppAttributesRequest ||
            m is NotificationActionRequest || m is NotificationLegacyAction ||
            (m is GfdiMessage.Malformed && m.id == GfdiId.NOTIFICATION_CONTROL)
        if (!isOurs || !supported(session)) return false
        val st = stateFor(session)
        when (m) {
            is NotificationSubscription -> {
                synchronized(lock) { st.enabled = m.enable }
                SwLog.i(TAG, "watch ${if (m.enable) "subscribed to" else "unsubscribed from"} notifications")
                session.channel.reply(m.id, GfdiStatus.ACK, FeatureReplies.SubscriptionStatus(FeatureReplies.SubscriptionStatus.ENABLED, m.enable, 0).encode())
            }
            is NotificationAttributesRequest -> {
                session.channel.reply(m.id, GfdiStatus.ACK, FeatureReplies.ControlStatus().encode())
                val n = synchronized(lock) { st.cache[m.notificationId] }
                if (n == null) {
                    SwLog.w(TAG, "watch asked for unknown notification ${m.notificationId}")
                } else {
                    upload(session, st, attributeData(m, n))
                }
            }
            is NotificationAppAttributesRequest -> {
                session.channel.reply(m.id, GfdiStatus.ACK, FeatureReplies.ControlStatus().encode())
                val name = synchronized(lock) { st.cache.values.lastOrNull { it.appPackage == m.appId }?.appName }
                    ?.takeIf { it.isNotBlank() } ?: m.appId.ifBlank { UNKNOWN_APP }
                appAttributeData(m, name)?.let { upload(session, st, it) }
            }
            is NotificationActionRequest -> {
                session.channel.reply(m.id, GfdiStatus.ACK, FeatureReplies.ControlStatus().encode())
                val n = synchronized(lock) { st.cache[m.notificationId] }
                if (n == null) SwLog.w(TAG, "action ${m.action} on unknown notification ${m.notificationId}") else onAction(session, m, n)
            }
            is NotificationLegacyAction -> {
                SwLog.i(TAG, "legacy notification action ${m.action} on ${m.notificationId}")
                session.channel.reply(m.id, GfdiStatus.ACK, FeatureReplies.ControlStatus().encode())
            }
            else -> session.channel.reply(
                m.id, GfdiStatus.ACK,
                FeatureReplies.ControlStatus(1, FeatureReplies.ControlStatus.UNKNOWN_COMMAND).encode(),
            )
        }
        return true
    }

    // Ref: Gadgetbridge 160beab41 NotificationsHandler.performNotificationAction (action → event mapping)
    private fun onAction(session: GarminSessionContext, m: NotificationActionRequest, n: PhoneNotification) {
        when (m.action) {
            Act.ACCEPT_INCOMING_CALL -> session.emit(WatchEvent.CallAction(true))
            Act.REJECT_INCOMING_CALL -> session.emit(WatchEvent.CallAction(false))
            Act.REPLY_INCOMING_CALL -> {
                session.emit(WatchEvent.CallAction(false))
                session.emit(WatchEvent.NotificationAction(n.id, ACTION_REPLY, m.text))
            }
            Act.REPLY_MESSAGES -> session.emit(WatchEvent.NotificationAction(n.id, ACTION_REPLY, m.text))
            Act.DISMISS_NOTIFICATION -> session.emit(WatchEvent.NotificationAction(n.id, ACTION_DISMISS, null))
            Act.BLOCK_APPLICATION -> session.emit(WatchEvent.NotificationAction(n.id, ACTION_MUTE, null))
            in Act.CUSTOM_1..Act.CUSTOM_5 -> {
                val label = n.actions.getOrNull(m.action - Act.CUSTOM_1)
                if (label == null) SwLog.w(TAG, "custom action ${m.action} not on notification ${n.id}")
                else session.emit(WatchEvent.NotificationAction(n.id, label, null))
            }
            else -> SwLog.w(TAG, "unknown notification action ${m.action}")
        }
    }

    private fun upload(session: GarminSessionContext, st: State, data: ByteArray) {
        session.scope.launch {
            st.uploadLock.withLock { uploadNow(session, data) }
        }
    }

    // Ref: Gadgetbridge 160beab41 NotificationsHandler.NotificationFragment (300-byte blocks, running CRC) and
    // Upload.processUploadProgress (next block after an OK status)
    private suspend fun uploadNow(session: GarminSessionContext, data: ByteArray) {
        var offset = 0
        var crc = 0
        while (offset < data.size) {
            val end = minOf(data.size, offset + CHUNK_BYTES)
            crc = Crc16.compute(data, offset, end - offset, crc)
            val msg = NotificationData(data.size, crc, offset, Bytes(data.copyOfRange(offset, end)))
            val r = when (val rr = session.channel.request(msg, requestTimeoutMs)) {
                is Outcome.Ok -> rr.value
                is Outcome.Fail -> {
                    SwLog.w(TAG, "notification data at $offset: ${rr.error}")
                    return
                }
            }
            val st = try {
                if (r.extra.size > 0) FeatureReplies.DataStatus.parse(r.extra.toByteArray()).transferStatus else FeatureReplies.DataStatus.OK
            } catch (e: MalformedException) {
                FeatureReplies.DataStatus.OK
            }
            if (r.status != GfdiStatus.ACK || st != FeatureReplies.DataStatus.OK) {
                SwLog.w(TAG, "watch refused notification data at $offset (${r.status}, $st)")
                return
            }
            offset = end
        }
    }

    /** The attribute block for [n], in the order asked, with MESSAGE_SIZE last. */
    internal fun attributeData(req: NotificationAttributesRequest, n: PhoneNotification): ByteArray {
        val w = ByteWriter().u8(NotificationCommand.GET_NOTIFICATION_ATTRIBUTES).i32(req.notificationId)
        val ordered = req.attributes.filter { it.attribute != Attr.MESSAGE_SIZE } + req.attributes.filter { it.attribute == Attr.MESSAGE_SIZE }
        for (a in ordered) {
            val value: ByteArray = if (a.attribute == Attr.ACTIONS) {
                actionBytes(n) // never cut: a partial action list would not parse
            } else {
                cut(textValue(a.attribute, n), a.maxLength).toByteArray(Charsets.UTF_8)
            }
            w.u8(a.attribute).u16(value.size).bytes(value)
        }
        return w.toByteArray()
    }

    // Ref: Gadgetbridge 160beab41 NotificationsHandler.NotificationAttribute.getNotificationSpecAttribute
    private fun textValue(attribute: Int, n: PhoneNotification): String = when (attribute) {
        Attr.APP_IDENTIFIER -> n.appPackage
        Attr.TITLE -> n.title ?: ""
        Attr.SUBTITLE -> ""
        Attr.MESSAGE -> n.text ?: ""
        Attr.MESSAGE_SIZE -> (n.text ?: "").length.toString()
        Attr.DATE -> DATE_FORMAT.format(Instant.ofEpochSecond(if (n.tSec > 0) n.tSec else clock.nowSec()).atZone(clock.zone()))
        Attr.ATTACHMENTS -> "0"
        else -> ""
    }

    private fun appAttributeData(req: NotificationAppAttributesRequest, name: String): ByteArray? {
        val w = ByteWriter().u8(NotificationCommand.GET_APP_ATTRIBUTES).bytes(req.appId.toByteArray(Charsets.UTF_8)).u8(0)
        for (a in req.attributes) {
            if (a != Attr.APP_NAME) {
                SwLog.w(TAG, "unknown app attribute $a")
                return null
            }
            val v = name.toByteArray(Charsets.UTF_8)
            w.u8(a).u16(v.size).bytes(v)
        }
        return w.toByteArray()
    }

    /** The Garmin actions for [n]: (code, icon bits, label). */
    internal fun actions(n: PhoneNotification): List<Triple<Int, Int, String>> {
        val out = ArrayList<Triple<Int, Int, String>>()
        if (n.category == NotificationCategory.CALL) {
            // Labels are not shown for the built-in call actions (reference sends a space).
            out.add(Triple(Act.REPLY_INCOMING_CALL, Act.ICON_BOTTOM, " "))
            out.add(Triple(Act.REJECT_INCOMING_CALL, Act.ICON_LEFT, " "))
            out.add(Triple(Act.ACCEPT_INCOMING_CALL, Act.ICON_RIGHT, " "))
            return out
        }
        if (n.canReply) out.add(Triple(Act.REPLY_MESSAGES, Act.ICON_BOTTOM, LABEL_REPLY))
        for ((i, label) in n.actions.take(Act.CUSTOM_5).withIndex()) out.add(Triple(Act.CUSTOM_1 + i, 0, label))
        out.add(Triple(Act.DISMISS_NOTIFICATION, Act.ICON_LEFT, LABEL_DISMISS))
        return out
    }

    // Ref: Gadgetbridge 160beab41 NotificationAttribute.encodeNotificationActionsString / encodeNotificationAction
    // (count, then code, icon bits, u8 label length, label; no actions = four zero bytes)
    private fun actionBytes(n: PhoneNotification): ByteArray {
        val list = actions(n)
        if (list.isEmpty()) return ByteArray(4)
        val w = ByteWriter().u8(list.size)
        for ((code, icon, label) in list) {
            val l = utf8Prefix(label, MAX_ACTION_LABEL_BYTES)
            w.u8(code).u8(icon).u8(l.size).bytes(l)
        }
        return w.toByteArray()
    }

    /** Public constants. */
    public companion object {
        private const val TAG = "garmin.notify"

        /** Notifications kept for the watch's attribute requests (the reference keeps 64). */
        public const val MAX_CACHED: Int = 64

        /** Largest NOTIFICATION_DATA chunk (the reference's block size). */
        public const val CHUNK_BYTES: Int = 300

        /** Longest action label in bytes (one length byte on the wire). */
        public const val MAX_ACTION_LABEL_BYTES: Int = 255

        /** [WatchEvent.NotificationAction.action] for a reply. */
        public const val ACTION_REPLY: String = "reply"

        /** [WatchEvent.NotificationAction.action] for dismiss. */
        public const val ACTION_DISMISS: String = "dismiss"

        /** [WatchEvent.NotificationAction.action] for mute app. */
        public const val ACTION_MUTE: String = "mute"

        private const val LABEL_REPLY = "Reply"
        private const val LABEL_DISMISS = "Dismiss"
        private const val UNKNOWN_CALLER = "Unknown"
        private const val UNKNOWN_APP = "Unknown"
        private const val PHONE_APP_NAME = "Phone"
        private const val CALL_ID_SEED = "Self Watch call"

        // Ref: Gadgetbridge 160beab41 NotificationUpdateMessage.getCategoryFlags (FOREGROUND + ACTION_DECLINE)
        private const val CATEGORY_FLAGS = NotificationUpdate.FLAG_FOREGROUND or NotificationUpdate.FLAG_ACTION_DECLINE

        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        // Ref: Gadgetbridge 160beab41 NotificationUpdateMessage.NotificationCategory ordinals (OTHER 0, INCOMING_CALL 1,
        // SOCIAL 4, SCHEDULE 5, EMAIL 6, SMS 12). CALENDAR → SCHEDULE is ours (the reference sends alarms as OTHER).
        internal fun categoryValue(c: NotificationCategory): Int = when (c) {
            NotificationCategory.CALL -> 1
            NotificationCategory.SOCIAL -> 4
            NotificationCategory.CALENDAR -> 5
            NotificationCategory.EMAIL -> 6
            NotificationCategory.MESSAGE -> 12
            NotificationCategory.OTHER -> 0
        }

        /**
         * [s] cut to [max] UTF-16 characters (0 = no limit), as the reference does, but never between the
         * two halves of a surrogate pair.
         */
        internal fun cut(s: String, max: Int): String {
            if (max <= 0 || s.length <= max) return s
            var end = max
            if (Character.isHighSurrogate(s[end - 1])) end--
            return s.substring(0, end)
        }
    }
}
