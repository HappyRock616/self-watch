/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.gfdi

import app.selfwatch.model.crc.Crc16
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError

/** GFDI message ids. */
public object GfdiId {
    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/GFDIMessage.java (GarminMessage ids)
    /** Status reply to any message. */
    public const val RESPONSE: Int = 5000
    /** Start a file download. */
    public const val DOWNLOAD_REQUEST: Int = 5002
    /** Start a file upload. */
    public const val UPLOAD_REQUEST: Int = 5003
    /** File chunk. */
    public const val FILE_TRANSFER_DATA: Int = 5004
    /** Create a file before uploading. */
    public const val CREATE_FILE: Int = 5005
    /** Delete a file. */
    public const val DELETE_FILE: Int = 5006
    /** Directory filter. */
    public const val FILTER: Int = 5007
    /** Set file flags (archive). */
    public const val SET_FILE_FLAG: Int = 5008
    /** The watch has a new file. */
    public const val FILE_AVAILABLE: Int = 5009
    /** FIT definition records. */
    public const val FIT_DEFINITION: Int = 5011
    /** FIT data records. */
    public const val FIT_DATA: Int = 5012
    /** The watch asks for weather. */
    public const val WEATHER_REQUEST: Int = 5014
    /** Battery level. */
    public const val BATTERY_STATUS: Int = 5023
    /** Watch identity; starts the handshake. */
    public const val DEVICE_INFORMATION: Int = 5024
    /** Device settings. */
    public const val DEVICE_SETTINGS: Int = 5026
    /** System event. */
    public const val SYSTEM_EVENT: Int = 5030
    /** Which file types the watch has. */
    public const val SUPPORTED_FILE_TYPES_REQUEST: Int = 5031
    /** Notification relay messages (GNCS). */
    public const val NOTIFICATION_UPDATE: Int = 5033
    /** Notification control. */
    public const val NOTIFICATION_CONTROL: Int = 5034
    /** Notification data. */
    public const val NOTIFICATION_DATA: Int = 5035
    /** Notification subscription. */
    public const val NOTIFICATION_SUBSCRIPTION: Int = 5036
    /** The watch asks to sync. */
    public const val SYNCHRONIZATION: Int = 5037
    /** Find my phone. */
    public const val FIND_MY_PHONE_REQUEST: Int = 5039
    /** Stop find my phone. */
    public const val FIND_MY_PHONE_CANCEL: Int = 5040
    /** Music control. */
    public const val MUSIC_CONTROL: Int = 5041
    /** Music capabilities. */
    public const val MUSIC_CONTROL_CAPABILITIES: Int = 5042
    /** Protobuf request. */
    public const val PROTOBUF_REQUEST: Int = 5043
    /** Protobuf response. */
    public const val PROTOBUF_RESPONSE: Int = 5044
    /** Protobuf cancel. */
    public const val PROTOBUF_CANCEL: Int = 5045
    /** Music entity update. */
    public const val MUSIC_CONTROL_ENTITY_UPDATE: Int = 5049
    /** Capability exchange. */
    public const val CONFIGURATION: Int = 5050
    /** The watch asks for the time. */
    public const val CURRENT_TIME_REQUEST: Int = 5052
    /** Auth negotiation (R1). */
    public const val AUTH_NEGOTIATION: Int = 5101
    /** First secure-pairing message id (R2). */
    public const val SECURE_PAIRING_FIRST: Int = 5102
    /** Last secure-pairing message id (R2). */
    public const val SECURE_PAIRING_LAST: Int = 5112

    /** True for the secure-pairing ids 5102..5112 (LTK reconnect ... out-of-band passkey). */
    public fun isSecurePairing(id: Int): Boolean = id in SECURE_PAIRING_FIRST..SECURE_PAIRING_LAST
}

/** Status codes in a RESPONSE. */
public enum class GfdiStatus(public val code: Int) {
    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/GFDIMessage.java (Status ordinals)
    ACK(0), NAK(1), UNSUPPORTED(2), DECODE_ERROR(3), CRC_ERROR(4), LENGTH_ERROR(5), UNKNOWN(-1);

    /** Lookup. */
    public companion object {
        /** The status for [code], or [UNKNOWN]. */
        public fun of(code: Int): GfdiStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * One GFDI frame on the wire (before COBS): `length(u16) type(u16) payload crc(u16)`, little-endian,
 * where length counts the whole frame including the CRC, and the CRC covers everything before it.
 * Compact types (`type and 0x8000`) carry `id = (type and 0xFF) + 5000` and a sequence number.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/GFDIMessage.java (frame layout, compact ids)
 */
public data class GfdiFrame(val id: Int, val payload: Bytes, val compactSeq: Int? = null) {
    /** Builds and parses frames. */
    public companion object {
        /** Smallest valid frame: length, type and CRC. */
        public const val MIN_SIZE: Int = 6

        /** Builds a frame for message [id] with [payload] (full, non-compact id). */
        public fun build(id: Int, payload: ByteArray): ByteArray {
            val w = ByteWriter(payload.size + MIN_SIZE)
            w.u16(0).u16(id).bytes(payload)
            w.putU16At(0, w.size + 2)
            val body = w.toByteArray()
            return w.u16(Crc16.compute(body)).toByteArray()
        }

        /** Parses [frame], checking its length and CRC. */
        public fun parse(frame: ByteArray): Outcome<GfdiFrame> {
            if (frame.size < MIN_SIZE) return Outcome.Fail(SwError.BadInput("GFDI frame too short (${frame.size})"))
            val r = ByteReader(frame)
            val len = r.u16()
            if (len != frame.size) return Outcome.Fail(SwError.BadInput("GFDI length $len but frame has ${frame.size}"))
            val crc = (frame[len - 2].toInt() and 0xFF) or ((frame[len - 1].toInt() and 0xFF) shl 8)
            if (crc != Crc16.compute(frame, 0, len - 2)) return Outcome.Fail(SwError.BadInput("GFDI CRC mismatch"))
            val type = r.u16()
            val (id, seq) = if (type and 0x8000 != 0) ((type and 0xFF) + 5000) to ((type shr 8) and 0x7F) else type to null
            return Outcome.Ok(GfdiFrame(id, Bytes(frame.copyOfRange(4, len - 2)), seq))
        }
    }
}
