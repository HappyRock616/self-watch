/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.gfdi

// GFDI messages for the M5 features (A15–A19): notifications (GNCS), find my phone, music, weather
// over FIT records, and protobuf packets. Like the M1 messages, each one encodes and decodes in both
// directions so tests can play the watch.

/**
 * Tells the watch a notification was added, changed or removed (5033, phone → watch). The watch then
 * asks for the text with a [NotificationAttributesRequest].
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/NotificationUpdateMessage.java (type, category flags,
 * category, count, u32 id, phone flags)
 */
public data class NotificationUpdate(
    val type: Int,
    val categoryFlags: Int,
    val category: Int,
    val count: Int,
    val notificationId: Int,
    val phoneFlags: Int,
) : GfdiMessage {
    override val id: Int get() = GfdiId.NOTIFICATION_UPDATE
    override fun encode(): ByteArray = ByteWriter().u8(type).u8(categoryFlags).u8(category).u8(count)
        .i32(notificationId).u8(phoneFlags).toByteArray()

    /** Update types and flag bits. */
    public companion object {
        /** New notification. */
        public const val ADD: Int = 0
        /** Changed notification (same id). */
        public const val MODIFY: Int = 1
        /** Removed notification. */
        public const val REMOVE: Int = 2

        // Ref: Gadgetbridge 160beab41 NotificationUpdateMessage.java (NotificationFlag bit order: BACKGROUND, FOREGROUND, UNK, ACTION_ACCEPT, ACTION_DECLINE)
        /** Category flag: show in the foreground. */
        public const val FLAG_FOREGROUND: Int = 1 shl 1
        /** Category flag: decline action (the reference always sets it). */
        public const val FLAG_ACTION_DECLINE: Int = 1 shl 4

        // Ref: Gadgetbridge 160beab41 NotificationUpdateMessage.java (NotificationPhoneFlags: LEGACY_ACTIONS, NEW_ACTIONS, HAS_ATTACHMENTS)
        /** Phone flag: the notification has (new-style) actions. */
        public const val PHONE_NEW_ACTIONS: Int = 1 shl 1
        /** Phone flag: the notification has a picture. */
        public const val PHONE_HAS_ATTACHMENTS: Int = 1 shl 2
    }
}

/** One attribute the watch asks for: [attribute] id, [maxLength] (0 = no limit) and, for actions, an [extra] byte. */
public data class AttributeRequest(val attribute: Int, val maxLength: Int = 0, val extra: Int? = null)

/**
 * NOTIFICATION_CONTROL command 0 (5034, watch → phone): the watch asks for attributes of a notification.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/NotificationControlMessage.java (u32 id, then
 * attribute ids; TITLE, SUBTITLE and MESSAGE carry a u16 max length; ACTIONS carries a u16 and one more byte)
 */
public data class NotificationAttributesRequest(val notificationId: Int, val attributes: List<AttributeRequest>) : GfdiMessage {
    override val id: Int get() = GfdiId.NOTIFICATION_CONTROL
    override fun encode(): ByteArray {
        val w = ByteWriter().u8(NotificationCommand.GET_NOTIFICATION_ATTRIBUTES).i32(notificationId)
        for (a in attributes) {
            w.u8(a.attribute)
            when {
                NotificationAttribute.hasLength(a.attribute) -> w.u16(a.maxLength)
                NotificationAttribute.hasExtra(a.attribute) -> w.u16(a.maxLength).u8(a.extra ?: 0)
            }
        }
        return w.toByteArray()
    }
}

/** NOTIFICATION_CONTROL command 1 (5034, watch → phone): the watch asks for app attributes (the app's name). */
public data class NotificationAppAttributesRequest(val appId: String, val attributes: List<Int>) : GfdiMessage {
    override val id: Int get() = GfdiId.NOTIFICATION_CONTROL
    override fun encode(): ByteArray {
        val w = ByteWriter().u8(NotificationCommand.GET_APP_ATTRIBUTES).bytes(appId.toByteArray(Charsets.UTF_8)).u8(0)
        for (a in attributes) w.u8(a)
        return w.toByteArray()
    }
}

/** NOTIFICATION_CONTROL command 2 (5034): legacy accept (0) / refuse (1) action. Logged only, as in the reference. */
public data class NotificationLegacyAction(val notificationId: Int, val action: Int) : GfdiMessage {
    override val id: Int get() = GfdiId.NOTIFICATION_CONTROL
    override fun encode(): ByteArray =
        ByteWriter().u8(NotificationCommand.PERFORM_LEGACY_ACTION).i32(notificationId).u8(action).toByteArray()
}

/**
 * NOTIFICATION_CONTROL command 128 (5034, watch → phone): the user picked [action] ([NotificationActionCode])
 * on a notification, with an optional reply [text] (null-terminated on the wire, absent on newer firmware).
 */
public data class NotificationActionRequest(val notificationId: Int, val action: Int, val text: String?) : GfdiMessage {
    override val id: Int get() = GfdiId.NOTIFICATION_CONTROL
    override fun encode(): ByteArray {
        val w = ByteWriter().u8(NotificationCommand.PERFORM_ACTION).i32(notificationId).u8(action)
        if (text != null) w.bytes(text.toByteArray(Charsets.UTF_8)).u8(0)
        return w.toByteArray()
    }
}

/**
 * One chunk of a notification's attribute data (5035, phone → watch): total [messageSize], running
 * CRC-16 of the data through this chunk, [offset] and the bytes.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/NotificationDataMessage.java (u16 size, u16 crc, u16 offset, data)
 */
public data class NotificationData(val messageSize: Int, val crc: Int, val offset: Int, val chunk: Bytes) : GfdiMessage {
    override val id: Int get() = GfdiId.NOTIFICATION_DATA
    override fun encode(): ByteArray =
        ByteWriter(chunk.size + 6).u16(messageSize).u16(crc).u16(offset).bytes(chunk.toByteArray()).toByteArray()
}

/**
 * The watch turns notifications on or off (5036, watch → phone).
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/NotificationSubscriptionMessage.java (u8 enable, optional u8)
 */
public data class NotificationSubscription(val enable: Boolean, val unknown: Int = 0) : GfdiMessage {
    override val id: Int get() = GfdiId.NOTIFICATION_SUBSCRIPTION
    override fun encode(): ByteArray = byteArrayOf(if (enable) 1 else 0, unknown.toByte())
}

/**
 * Find my phone (5039, watch → phone), ringing for [durationSec].
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/FindMyPhoneRequestMessage.java (u8 duration)
 */
public data class FindMyPhoneRequest(val durationSec: Int) : GfdiMessage {
    override val id: Int get() = GfdiId.FIND_MY_PHONE_REQUEST
    override fun encode(): ByteArray = byteArrayOf(durationSec.toByte())
}

/** Stop find my phone (5040, watch → phone); no payload. */
public data object FindMyPhoneCancel : GfdiMessage {
    override val id: Int get() = GfdiId.FIND_MY_PHONE_CANCEL
    override fun encode(): ByteArray = ByteArray(0)
}

/**
 * A media button on the watch (5041, watch → phone); [command] is a [MusicCommand].
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/MusicControlMessage.java (u8 command ordinal)
 */
public data class MusicControl(val command: Int) : GfdiMessage {
    override val id: Int get() = GfdiId.MUSIC_CONTROL
    override fun encode(): ByteArray = byteArrayOf(command.toByte())
}

/**
 * The watch asks which media commands the phone supports (5042). [supported] is the watch's own byte,
 * null when the payload is empty (some watches send none).
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/MusicControlCapabilitiesMessage.java
 */
public data class MusicControlCapabilities(val supported: Int?) : GfdiMessage {
    override val id: Int get() = GfdiId.MUSIC_CONTROL_CAPABILITIES
    override fun encode(): ByteArray = if (supported == null) ByteArray(0) else byteArrayOf(supported.toByte())
}

/** One now-playing value: [entity] ([MusicEntityId]), [attribute] within it, and the text [value]. */
public data class MusicEntity(val entity: Int, val attribute: Int, val value: String)

/**
 * Now-playing update (5049, phone → watch). Each entry is `u8 length(value + 3), u8 entity, u8 attribute,
 * u8 0, value bytes`; a value longer than 252 bytes is cut.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/MusicControlEntityUpdateMessage.java (entry layout, 252-byte limit)
 */
public data class MusicEntityUpdate(val entries: List<MusicEntity>) : GfdiMessage {
    override val id: Int get() = GfdiId.MUSIC_CONTROL_ENTITY_UPDATE
    override fun encode(): ByteArray {
        val w = ByteWriter()
        for (e in entries) {
            val v = utf8Prefix(e.value, MAX_VALUE_BYTES)
            w.u8(v.size + 3).u8(e.entity).u8(e.attribute).u8(0).bytes(v)
        }
        return w.toByteArray()
    }

    /** Limits. */
    public companion object {
        /** Longest value in bytes. */
        public const val MAX_VALUE_BYTES: Int = 252
    }
}

/**
 * The watch asks for weather (5014): [format], position in semicircles, hours of forecast wanted.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/WeatherRequestMessage.java (u8, i32, i32, u8)
 */
public data class WeatherRequest(val format: Int, val latitude: Int, val longitude: Int, val hours: Int) : GfdiMessage {
    override val id: Int get() = GfdiId.WEATHER_REQUEST
    override fun encode(): ByteArray = ByteWriter().u8(format).i32(latitude).i32(longitude).u8(hours).toByteArray()
}

/**
 * FIT definition records sent outside a file (5011); [records] are standard FIT definition records.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/FitDefinitionMessage.java (payload = definition records)
 */
public data class FitDefinition(val records: Bytes) : GfdiMessage {
    override val id: Int get() = GfdiId.FIT_DEFINITION
    override fun encode(): ByteArray = records.toByteArray()
}

/**
 * FIT data records for definitions sent in a [FitDefinition] (5012).
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/FitDataMessage.java (payload = data records)
 */
public data class FitData(val records: Bytes) : GfdiMessage {
    override val id: Int get() = GfdiId.FIT_DATA
    override fun encode(): ByteArray = records.toByteArray()
}

/**
 * One chunk of a protobuf message: PROTOBUF_REQUEST (5043) or PROTOBUF_RESPONSE (5044), both directions.
 * [data] holds bytes `dataOffset until dataOffset + data.size` of a [totalLength]-byte `Smart` message.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/ProtobufMessage.java (u16 request id, u32 offset,
 * u32 total length, u32 chunk length, chunk)
 */
public data class ProtobufPacket(
    override val id: Int,
    val requestId: Int,
    val dataOffset: Int,
    val totalLength: Int,
    val data: Bytes,
) : GfdiMessage {
    init {
        require(id == GfdiId.PROTOBUF_REQUEST || id == GfdiId.PROTOBUF_RESPONSE) { "not a protobuf id: $id" }
    }

    override fun encode(): ByteArray = ByteWriter(data.size + 14).u16(requestId).i32(dataOffset).i32(totalLength)
        .i32(data.size).bytes(data.toByteArray()).toByteArray()

    /** True when this one packet holds the whole message. */
    public val isComplete: Boolean get() = dataOffset == 0 && data.size == totalLength
}

/** NOTIFICATION_CONTROL command codes. Ref: Gadgetbridge 160beab41 NotificationsHandler.java (NotificationCommand). */
public object NotificationCommand {
    /** Get notification attributes. */
    public const val GET_NOTIFICATION_ATTRIBUTES: Int = 0
    /** Get app attributes. */
    public const val GET_APP_ATTRIBUTES: Int = 1
    /** Legacy accept/refuse. */
    public const val PERFORM_LEGACY_ACTION: Int = 2
    /** New-style action. */
    public const val PERFORM_ACTION: Int = 128
}

/** Notification attribute ids. Ref: Gadgetbridge 160beab41 NotificationsHandler.java (NotificationAttribute codes). */
public object NotificationAttribute {
    /** App package. */
    public const val APP_IDENTIFIER: Int = 0
    /** Title (max length given). */
    public const val TITLE: Int = 1
    /** Subtitle (max length given). */
    public const val SUBTITLE: Int = 2
    /** Message text (max length given). */
    public const val MESSAGE: Int = 3
    /** Message length in characters, as decimal text; always answered last. */
    public const val MESSAGE_SIZE: Int = 4
    /** Date as `yyyyMMdd'T'HHmmss`. */
    public const val DATE: Int = 5
    /** Positive action label (legacy). */
    public const val POSITIVE_ACTION_LABEL: Int = 6
    /** Negative action label (legacy). */
    public const val NEGATIVE_ACTION_LABEL: Int = 7
    /** Garmin extension: the action list. */
    public const val ACTIONS: Int = 127
    /** Garmin extension: number of attachments. */
    public const val ATTACHMENTS: Int = 128

    /** App attribute: the app's display name. */
    public const val APP_NAME: Int = 0

    /** True for attributes followed by a u16 max length. */
    public fun hasLength(attribute: Int): Boolean = attribute == TITLE || attribute == SUBTITLE || attribute == MESSAGE

    /** True for attributes followed by a u16 and one more byte. */
    public fun hasExtra(attribute: Int): Boolean = attribute == ACTIONS

    /** True for ids the reference knows (others make the request malformed). */
    public fun isKnown(attribute: Int): Boolean = attribute in APP_IDENTIFIER..NEGATIVE_ACTION_LABEL && attribute != POSITIVE_ACTION_LABEL ||
        attribute == ACTIONS || attribute == ATTACHMENTS
}

/** Notification action codes. Ref: Gadgetbridge 160beab41 NotificationsHandler.java (NotificationAction codes). */
public object NotificationActionCode {
    /** First custom action (1..5). */
    public const val CUSTOM_1: Int = 1
    /** Last custom action. */
    public const val CUSTOM_5: Int = 5
    /** Reply to an incoming call with a message (also rejects it). */
    public const val REPLY_INCOMING_CALL: Int = 94
    /** Reply to a message. */
    public const val REPLY_MESSAGES: Int = 95
    /** Accept the call. */
    public const val ACCEPT_INCOMING_CALL: Int = 96
    /** Reject the call. */
    public const val REJECT_INCOMING_CALL: Int = 97
    /** Dismiss the notification. */
    public const val DISMISS_NOTIFICATION: Int = 98
    /** Mute the app. */
    public const val BLOCK_APPLICATION: Int = 99

    // Ref: Gadgetbridge 160beab41 NotificationsHandler.java (NotificationActionIconPosition BOTTOM, RIGHT, LEFT as bits)
    /** Icon position bit: bottom. */
    public const val ICON_BOTTOM: Int = 1
    /** Icon position bit: right. */
    public const val ICON_RIGHT: Int = 2
    /** Icon position bit: left. */
    public const val ICON_LEFT: Int = 4
}

/** Media commands (ordinals). Ref: Gadgetbridge 160beab41 MusicControlCapabilitiesMessage.java (GarminMusicControlCommand). */
public object MusicCommand {
    /** Toggle play/pause. */
    public const val TOGGLE_PLAY_PAUSE: Int = 0
    /** Next track. */
    public const val SKIP_TO_NEXT_ITEM: Int = 1
    /** Previous track. */
    public const val SKIP_TO_PREVIOUS_ITEM: Int = 2
    /** Volume up. */
    public const val VOLUME_UP: Int = 3
    /** Volume down. */
    public const val VOLUME_DOWN: Int = 4
    /** Play. */
    public const val PLAY: Int = 5
    /** Pause. */
    public const val PAUSE: Int = 6
    /** Skip forward within the track. */
    public const val SKIP_FORWARD: Int = 7
    /** Skip backwards within the track. */
    public const val SKIP_BACKWARDS: Int = 8
    /** Number of commands. */
    public const val COUNT: Int = 9
}

/** Now-playing entities and attributes. Ref: Gadgetbridge 160beab41 MusicControlEntityUpdateMessage.java (PLAYER 0, QUEUE 1, TRACK 2). */
public object MusicEntityId {
    /** Player entity. */
    public const val PLAYER: Int = 0
    /** Queue entity. */
    public const val QUEUE: Int = 1
    /** Track entity. */
    public const val TRACK: Int = 2

    /** PLAYER: name. */
    public const val PLAYER_NAME: Int = 0
    /** PLAYER: playback info `playing,rate,positionSec`. */
    public const val PLAYER_PLAYBACK_INFO: Int = 1
    /** PLAYER: volume 0.00..1.00. */
    public const val PLAYER_VOLUME: Int = 2

    /** TRACK: artist. */
    public const val TRACK_ARTIST: Int = 0
    /** TRACK: album. */
    public const val TRACK_ALBUM: Int = 1
    /** TRACK: title. */
    public const val TRACK_TITLE: Int = 2
    /** TRACK: duration in seconds. */
    public const val TRACK_DURATION: Int = 3
}

/**
 * Typed status bodies (after original id and status) for the feature messages, both directions.
 */
public object FeatureReplies {
    /**
     * Our answer to NOTIFICATION_SUBSCRIPTION: notification status (0 enabled, 1 disabled), the watch's
     * enable byte echoed, and one unknown byte.
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/status/NotificationSubscriptionStatusMessage.java
     */
    public data class SubscriptionStatus(val notificationStatus: Int, val enable: Boolean, val unknown: Int = 0) {
        /** Body bytes. */
        public fun encode(): ByteArray = byteArrayOf(notificationStatus.toByte(), if (enable) 1 else 0, unknown.toByte())

        /** Parser and values. */
        public companion object {
            /** Notifications on. */
            public const val ENABLED: Int = 0
            /** Notifications off. */
            public const val DISABLED: Int = 1

            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): SubscriptionStatus {
                val r = ByteReader(b)
                return SubscriptionStatus(r.u8(), r.u8() == 1, r.u8())
            }
        }
    }

    /**
     * Our answer to NOTIFICATION_CONTROL: chunk status (0 OK) and status code (0 no error, 160 unknown command).
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/status/NotificationControlStatusMessage.java
     */
    public data class ControlStatus(val chunkStatus: Int = 0, val code: Int = 0) {
        /** Body bytes. */
        public fun encode(): ByteArray = byteArrayOf(chunkStatus.toByte(), code.toByte())

        /** Parser. */
        public companion object {
            /** Unknown command. */
            public const val UNKNOWN_COMMAND: Int = 160

            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): ControlStatus {
                val r = ByteReader(b)
                return ControlStatus(r.u8(), r.u8())
            }
        }
    }

    /**
     * The watch's answer to a NOTIFICATION_DATA chunk: transfer status (0 OK, 1 resend, 2 abort, 3 CRC, 4 offset).
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/status/NotificationDataStatusMessage.java
     */
    public data class DataStatus(val transferStatus: Int) {
        /** Body bytes. */
        public fun encode(): ByteArray = byteArrayOf(transferStatus.toByte())

        /** Parser. */
        public companion object {
            /** Go on. */
            public const val OK: Int = 0

            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): DataStatus = DataStatus(ByteReader(b).u8())
        }
    }

    /**
     * The watch's answer to FIT_DEFINITION / FIT_DATA: 0 applied; definition: 1 not unique, 2 out of range,
     * 3 not ready; data: 1 no definition, 2 mismatch, 3 not ready.
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/status/FitDefinitionStatusMessage.java, FitDataStatusMessage.java
     */
    public data class FitStatus(val code: Int) {
        /** Body bytes. */
        public fun encode(): ByteArray = byteArrayOf(code.toByte())

        /** Parser. */
        public companion object {
            /** Applied. */
            public const val APPLIED: Int = 0

            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): FitStatus = FitStatus(ByteReader(b).u8())
        }
    }

    /**
     * Status of a protobuf chunk, both directions: request id, the chunk's data offset, chunk status
     * (0 kept, 1 discarded) and status code (0 no error, 100 unknown request id, 101 duplicate, 102 missing,
     * 103 exceeded total length, 200 parse error, 201 unknown).
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/status/ProtobufStatusMessage.java
     */
    public data class ProtobufStatus(val requestId: Int, val dataOffset: Int, val chunkStatus: Int, val code: Int) {
        /** Body bytes. */
        public fun encode(): ByteArray = ByteWriter().u16(requestId).i32(dataOffset).u8(chunkStatus).u8(code).toByteArray()

        /** True for "kept, no error". */
        public val isOk: Boolean get() = chunkStatus == KEPT && code == NO_ERROR

        /** Parser and values. */
        public companion object {
            /** Chunk kept. */
            public const val KEPT: Int = 0
            /** Chunk discarded. */
            public const val DISCARDED: Int = 1
            /** No error. */
            public const val NO_ERROR: Int = 0
            /** Nobody handles this request. */
            public const val UNKNOWN_REQUEST_ID: Int = 100
            /** Chunk at the wrong offset. */
            public const val MISSING_PACKET: Int = 102
            /** Chunk beyond the announced length. */
            public const val EXCEEDED_TOTAL_PROTOBUF_LENGTH: Int = 103
            /** The message did not parse. */
            public const val PROTOBUF_PARSE_ERROR: Int = 200

            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): ProtobufStatus {
                val r = ByteReader(b)
                return ProtobufStatus(r.u16(), r.i32(), r.u8(), r.u8())
            }
        }
    }

    /**
     * Our answer to MUSIC_CONTROL_CAPABILITIES: count, then each supported [MusicCommand].
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/MusicControlCapabilitiesMessage.java (generateOutgoing)
     */
    public data class MusicCapabilities(val commands: List<Int>) {
        /** Body bytes. */
        public fun encode(): ByteArray {
            val w = ByteWriter().u8(commands.size)
            for (c in commands) w.u8(c)
            return w.toByteArray()
        }

        /** Parser. */
        public companion object {
            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): MusicCapabilities {
                val r = ByteReader(b)
                return MusicCapabilities(List(r.u8()) { r.u8() })
            }
        }
    }
}

/** Decoding of the feature messages; called by [GfdiMessage.decode] for ids it does not know itself. */
internal object FeatureMessages {
    /** Decodes message [id] from [r], or null if [id] is not a feature message. Throws [MalformedException]. */
    fun decodeOrNull(id: Int, r: ByteReader): GfdiMessage? = when (id) {
        GfdiId.NOTIFICATION_UPDATE -> NotificationUpdate(r.u8(), r.u8(), r.u8(), r.u8(), r.i32(), r.u8())
        GfdiId.NOTIFICATION_CONTROL -> decodeControl(r)
        GfdiId.NOTIFICATION_DATA -> NotificationData(r.u16(), r.u16(), r.u16(), Bytes(r.rest()))
        GfdiId.NOTIFICATION_SUBSCRIPTION -> NotificationSubscription(r.u8() == 1, if (r.remaining > 0) r.u8() else 0)
        GfdiId.FIND_MY_PHONE_REQUEST -> FindMyPhoneRequest(if (r.remaining > 0) r.u8() else 0)
        GfdiId.FIND_MY_PHONE_CANCEL -> FindMyPhoneCancel
        GfdiId.MUSIC_CONTROL -> MusicControl(r.u8())
        GfdiId.MUSIC_CONTROL_CAPABILITIES -> MusicControlCapabilities(if (r.remaining > 0) r.u8() else null)
        GfdiId.MUSIC_CONTROL_ENTITY_UPDATE -> {
            val list = ArrayList<MusicEntity>()
            while (r.remaining > 0) {
                val len = r.u8()
                if (len < 3) throw MalformedException("music entity length $len")
                val entity = r.u8()
                val attribute = r.u8()
                r.u8()
                list.add(MusicEntity(entity, attribute, String(r.bytes(len - 3), Charsets.UTF_8)))
            }
            MusicEntityUpdate(list)
        }
        GfdiId.WEATHER_REQUEST -> WeatherRequest(r.u8(), r.i32(), r.i32(), r.u8())
        GfdiId.FIT_DEFINITION -> FitDefinition(Bytes(r.rest()))
        GfdiId.FIT_DATA -> FitData(Bytes(r.rest()))
        GfdiId.PROTOBUF_REQUEST, GfdiId.PROTOBUF_RESPONSE -> {
            val requestId = r.u16()
            val offset = r.i32()
            val total = r.i32()
            val len = r.i32()
            if (offset < 0 || total < 0 || len < 0) throw MalformedException("protobuf lengths $offset/$total/$len")
            ProtobufPacket(id, requestId, offset, total, Bytes(r.bytes(len)))
        }
        else -> null
    }

    private fun decodeControl(r: ByteReader): GfdiMessage = when (val cmd = r.u8()) {
        NotificationCommand.GET_NOTIFICATION_ATTRIBUTES -> {
            val nid = r.i32()
            val attrs = ArrayList<AttributeRequest>()
            while (r.remaining > 0) {
                val a = r.u8()
                if (!NotificationAttribute.isKnown(a)) throw MalformedException("notification attribute $a")
                attrs.add(
                    when {
                        NotificationAttribute.hasLength(a) -> AttributeRequest(a, r.u16())
                        NotificationAttribute.hasExtra(a) -> AttributeRequest(a, r.u16(), r.u8())
                        else -> AttributeRequest(a)
                    },
                )
            }
            NotificationAttributesRequest(nid, attrs)
        }
        NotificationCommand.GET_APP_ATTRIBUTES -> {
            val appId = cString(r)
            val attrs = ArrayList<Int>()
            while (r.remaining > 0) attrs.add(r.u8())
            NotificationAppAttributesRequest(appId, attrs)
        }
        NotificationCommand.PERFORM_LEGACY_ACTION -> NotificationLegacyAction(r.i32(), r.u8())
        NotificationCommand.PERFORM_ACTION -> {
            val nid = r.i32()
            val action = r.u8()
            NotificationActionRequest(nid, action, if (r.remaining > 0) cString(r) else null)
        }
        else -> throw MalformedException("notification command $cmd")
    }

    /** Reads up to a 0 byte (consumed) or the end. */
    private fun cString(r: ByteReader): String {
        val out = java.io.ByteArrayOutputStream()
        while (r.remaining > 0) {
            val b = r.u8()
            if (b == 0) break
            out.write(b)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

/** The longest prefix of [s] whose UTF-8 form fits in [max] bytes, never splitting a character. */
internal fun utf8Prefix(s: String, max: Int): ByteArray {
    val all = s.toByteArray(Charsets.UTF_8)
    if (all.size <= max) return all
    var end = max
    while (end > 0 && (all[end].toInt() and 0xC0) == 0x80) end--
    return all.copyOf(end)
}
