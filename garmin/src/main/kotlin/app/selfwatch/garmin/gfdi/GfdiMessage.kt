/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.gfdi

import app.selfwatch.garmin.files.DirEntry
import app.selfwatch.model.result.Outcome

/**
 * A GFDI message. Every message can be encoded to its payload and decoded back, in both directions,
 * so frames round-trip in tests. Byte layouts are little-endian; strings are u8-length-prefixed UTF-8.
 */
public sealed interface GfdiMessage {
    /** The message id. */
    public val id: Int

    /** The payload bytes (without length, type and CRC). */
    public fun encode(): ByteArray

    /** The whole frame for the wire (before COBS). */
    public fun toFrame(): ByteArray = GfdiFrame.build(id, encode())

    /** Status reply: [originalId] answered with [status], followed by message-specific [extra] bytes. */
    public data class Response(val originalId: Int, val status: GfdiStatus, val extra: Bytes = Bytes.EMPTY) : GfdiMessage {
        override val id: Int get() = GfdiId.RESPONSE
        override fun encode(): ByteArray = ByteWriter().u16(originalId).u8(status.code).bytes(extra.toByteArray()).toByteArray()
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/DownloadRequestMessage.java (layout, CONTINUE 0 / NEW 1)
    /** Asks for file [fileIndex] from [offset]; [newTransfer] selects NEW (1) rather than CONTINUE (0). */
    public data class DownloadRequest(
        val fileIndex: Int,
        val offset: Int = 0,
        val newTransfer: Boolean = true,
        val crcSeed: Int = 0,
        val size: Int = 0,
        val flags: Int = 0,
    ) : GfdiMessage {
        override val id: Int get() = GfdiId.DOWNLOAD_REQUEST
        override fun encode(): ByteArray = ByteWriter().u16(fileIndex).i32(offset).u8(if (newTransfer) 1 else 0)
            .u16(crcSeed).i32(size).u8(flags).toByteArray()
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/UploadRequestMessage.java (layout)
    /** Starts an upload of [size] bytes into file [fileIndex]. */
    public data class UploadRequest(val fileIndex: Int, val size: Int, val offset: Int = 0, val crcSeed: Int = 0) : GfdiMessage {
        override val id: Int get() = GfdiId.UPLOAD_REQUEST
        override fun encode(): ByteArray = ByteWriter().u16(fileIndex).i32(size).i32(offset).u16(crcSeed).toByteArray()
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/FileTransferDataMessage.java (flags, crc, offset, data)
    /** One file chunk; [crc] is the running CRC-16 of the file from byte 0 through this chunk. */
    public data class FileTransferData(val flags: Int, val crc: Int, val offset: Int, val data: Bytes) : GfdiMessage {
        override val id: Int get() = GfdiId.FILE_TRANSFER_DATA
        override fun encode(): ByteArray = ByteWriter(data.size + 8).u8(flags).u16(crc).i32(offset).bytes(data.toByteArray()).toByteArray()
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/CreateFileMessage.java (layout)
    /** Creates a file of [size] bytes and type [dataType]/[subType] on the watch before an upload. */
    public data class CreateFile(
        val size: Int,
        val dataType: Int,
        val subType: Int,
        val fileIndex: Int = 0,
        val subTypeMask: Int = 0,
        val numberMask: Int = 0xFFFF,
        val path: String = "",
        val fileId: Long = 0,
    ) : GfdiMessage {
        override val id: Int get() = GfdiId.CREATE_FILE
        override fun encode(): ByteArray = ByteWriter().i32(size).u8(dataType).u8(subType).u16(fileIndex).u8(0)
            .u8(subTypeMask).u16(numberMask).str16(path).i64(fileId).toByteArray()
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/FilterMessage.java (ALL 0, DEFAULT 1, 2, ONLY_NEW 3)
    /** Directory filter; [ONLY_NEW] is what the reference sends after a SYNCHRONIZATION request. */
    public data class Filter(val type: Int) : GfdiMessage {
        override val id: Int get() = GfdiId.FILTER
        override fun encode(): ByteArray = byteArrayOf(type.toByte())

        /** Filter types. */
        public companion object {
            /** Only files not yet archived. */
            public const val ONLY_NEW: Int = 3
        }
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/SetFileFlagsMessage.java (u16 index, u8 flags; ARCHIVE 0x10)
    /** Sets [flags] on file [fileIndex]. */
    public data class SetFileFlag(val fileIndex: Int, val flags: Int) : GfdiMessage {
        override val id: Int get() = GfdiId.SET_FILE_FLAG
        override fun encode(): ByteArray = ByteWriter().u16(fileIndex).u8(flags).toByteArray()

        /** Flag bits. */
        public companion object {
            /** Archive: the file is done and will not be listed as new again. */
            public const val ARCHIVE: Int = 0x10
        }
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/FileAvailableMessage.java (directory entry + trailing bytes)
    /** The watch announces a new file. */
    public data class FileAvailable(val entry: DirEntry, val extra: Bytes = Bytes.EMPTY) : GfdiMessage {
        override val id: Int get() = GfdiId.FILE_AVAILABLE
        override fun encode(): ByteArray = ByteWriter().bytes(entry.encode()).bytes(extra.toByteArray()).toByteArray()
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/BatteryStatusMessage.java (status, voltage/100, percent)
    /** Battery report: [percent] 0..100, [centivolts] voltage ×100. */
    public data class BatteryStatus(val status: Int, val centivolts: Int, val percent: Int, val extra: Bytes = Bytes.EMPTY) : GfdiMessage {
        override val id: Int get() = GfdiId.BATTERY_STATUS
        override fun encode(): ByteArray = ByteWriter().u8(status).u8(centivolts).u8(percent).bytes(extra.toByteArray()).toByteArray()
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/DeviceInformationMessage.java (incoming layout)
    /**
     * The watch's identity. [softwareVersion] is ×100 (1234 = "12.34"); [unitNumber] is the serial.
     * [extra] holds optional trailing bytes (dual-pairing flag and addresses on newer watches).
     */
    public data class DeviceInformation(
        val protocolVersion: Int,
        val productNumber: Int,
        val unitNumber: Long,
        val softwareVersion: Int,
        val maxPacketSize: Int,
        val bluetoothName: String,
        val deviceName: String,
        val deviceModel: String,
        val extra: Bytes = Bytes.EMPTY,
    ) : GfdiMessage {
        override val id: Int get() = GfdiId.DEVICE_INFORMATION
        override fun encode(): ByteArray = ByteWriter().u16(protocolVersion).u16(productNumber).i32(unitNumber.toInt())
            .u16(softwareVersion).u16(maxPacketSize).str8(bluetoothName).str8(deviceName).str8(deviceModel)
            .bytes(extra.toByteArray()).toByteArray()

        /** Firmware as "major.minor", e.g. "12.34". */
        public val firmware: String get() = "%d.%02d".format(softwareVersion / 100, softwareVersion % 100)
    }

    /** A device-setting value. */
    public sealed interface SettingValue {
        /** Boolean (wire: size 1, then 0/1). */
        public data class Bool(val v: Boolean) : SettingValue

        /** 32-bit integer (wire: size 4, then i32). */
        public data class Int32(val v: Int) : SettingValue

        /** String (wire: u8 length, then UTF-8). */
        public data class Str(val v: String) : SettingValue
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/SetDeviceSettingsMessage.java (count, id, sized value)
    /** Device settings, as (setting id, value) pairs; ids are [DeviceSettingId]. */
    public data class DeviceSettings(val settings: List<Pair<Int, SettingValue>>) : GfdiMessage {
        override val id: Int get() = GfdiId.DEVICE_SETTINGS
        override fun encode(): ByteArray {
            val w = ByteWriter().u8(settings.size)
            for ((k, v) in settings) {
                w.u8(k)
                when (v) {
                    is SettingValue.Bool -> w.u8(1).u8(if (v.v) 1 else 0)
                    is SettingValue.Int32 -> w.u8(4).i32(v.v)
                    is SettingValue.Str -> w.str8(v.v)
                }
            }
            return w.toByteArray()
        }
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/SystemEventMessage.java (u8 type, u8 or string value)
    /** A system event of [type] ([SystemEventType]); [value] is an Int (one byte) or a String. */
    public data class SystemEvent(val type: Int, val value: Any = 0) : GfdiMessage {
        init {
            require(value is Int || value is String) { "value must be Int or String" }
        }

        override val id: Int get() = GfdiId.SYSTEM_EVENT
        override fun encode(): ByteArray {
            val w = ByteWriter().u8(type)
            if (value is String) w.str8(value) else w.u8(value as Int)
            return w.toByteArray()
        }
    }

    /** Asks which file types the watch has. */
    public data object SupportedFileTypesRequest : GfdiMessage {
        override val id: Int get() = GfdiId.SUPPORTED_FILE_TYPES_REQUEST
        override fun encode(): ByteArray = ByteArray(0)
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/SynchronizationMessage.java (type, mask size 4|8, mask)
    /** The watch asks to sync: [type] (0 manual, 1 automatic), [bitmask] of file categories. */
    public data class Synchronization(val type: Int, val bitmask: Long, val maskSize: Int = 8) : GfdiMessage {
        override val id: Int get() = GfdiId.SYNCHRONIZATION
        override fun encode(): ByteArray {
            val w = ByteWriter().u8(type).u8(maskSize)
            if (maskSize == 4) w.i32(bitmask.toInt()) else w.i64(bitmask)
            return w.toByteArray()
        }

        /** True when the reference would start a sync: workouts, activities, activity summary, sleep, or manual. */
        public val shouldSync: Boolean
            get() = type == 0 || (bitmask and ((1L shl 3) or (1L shl 5) or (1L shl 21) or (1L shl 26))) != 0L
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/ConfigurationMessage.java (u8 length + capability bits)
    /** Capability bit set (bit n = capability ordinal n). */
    public data class Configuration(val capabilityBits: Bytes) : GfdiMessage {
        override val id: Int get() = GfdiId.CONFIGURATION
        override fun encode(): ByteArray = ByteWriter().u8(capabilityBits.size).bytes(capabilityBits.toByteArray()).toByteArray()
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/CurrentTimeRequestMessage.java (i32 reference id)
    /** The watch asks for the time; the reply echoes [referenceId]. */
    public data class CurrentTimeRequest(val referenceId: Int) : GfdiMessage {
        override val id: Int get() = GfdiId.CURRENT_TIME_REQUEST
        override fun encode(): ByteArray = ByteWriter().i32(referenceId).toByteArray()
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/AuthNegotiationMessage.java (u8 unknown, i32 flags)
    /** Auth negotiation from the watch (R1). */
    public data class AuthNegotiation(val unknown: Int, val flags: Int) : GfdiMessage {
        override val id: Int get() = GfdiId.AUTH_NEGOTIATION
        override fun encode(): ByteArray = ByteWriter().u8(unknown).i32(flags).toByteArray()
    }

    /** One of Garmin's secure-pairing messages (5102..5112, R2). Not implemented: detected and refused. */
    public data class SecurePairing(override val id: Int, val payload: Bytes) : GfdiMessage {
        override fun encode(): ByteArray = payload.toByteArray()
    }

    /** A message id this module does not handle (yet). */
    public data class Unknown(override val id: Int, val payload: Bytes) : GfdiMessage {
        override fun encode(): ByteArray = payload.toByteArray()
    }

    /** A known id whose payload did not match its layout. */
    public data class Malformed(override val id: Int, val payload: Bytes, val reason: String) : GfdiMessage {
        override fun encode(): ByteArray = payload.toByteArray()
    }

    /** Decoding. */
    public companion object {
        /** Parses a whole frame (length and CRC checked) into a message. */
        public fun decodeFrame(frame: ByteArray): Outcome<GfdiMessage> = when (val f = GfdiFrame.parse(frame)) {
            is Outcome.Ok -> Outcome.Ok(decode(f.value.id, f.value.payload.toByteArray()))
            is Outcome.Fail -> f
        }

        /** Decodes [payload] of message [id]; never throws. */
        public fun decode(id: Int, payload: ByteArray): GfdiMessage = try {
            decodeOrThrow(id, payload)
        } catch (e: MalformedException) {
            Malformed(id, Bytes(payload), e.message ?: "malformed")
        }

        private fun decodeOrThrow(id: Int, p: ByteArray): GfdiMessage {
            val r = ByteReader(p)
            return when (id) {
                GfdiId.RESPONSE -> Response(r.u16(), GfdiStatus.of(r.u8()), Bytes(r.rest()))
                GfdiId.DOWNLOAD_REQUEST -> DownloadRequest(r.u16(), r.i32(), r.u8() == 1, r.u16(), r.i32(), r.u8())
                GfdiId.UPLOAD_REQUEST -> UploadRequest(r.u16(), r.i32(), r.i32(), r.u16())
                GfdiId.FILE_TRANSFER_DATA -> FileTransferData(r.u8(), r.u16(), r.i32(), Bytes(r.rest()))
                GfdiId.CREATE_FILE -> {
                    val size = r.i32()
                    val type = r.u8()
                    val sub = r.u8()
                    val index = r.u16()
                    r.u8()
                    CreateFile(size, type, sub, index, r.u8(), r.u16(), r.str16(), r.i64())
                }
                GfdiId.FILTER -> Filter(r.u8())
                GfdiId.SET_FILE_FLAG -> SetFileFlag(r.u16(), r.u8())
                GfdiId.FILE_AVAILABLE -> FileAvailable(DirEntry.read(r), Bytes(r.rest()))
                GfdiId.BATTERY_STATUS -> BatteryStatus(r.u8(), r.u8(), r.u8(), Bytes(r.rest()))
                GfdiId.DEVICE_INFORMATION -> DeviceInformation(
                    r.u16(), r.u16(), r.u32(), r.u16(), r.u16(), r.str8(), r.str8(), r.str8(), Bytes(r.rest()),
                )
                GfdiId.DEVICE_SETTINGS -> {
                    val n = r.u8()
                    val list = ArrayList<Pair<Int, SettingValue>>(n)
                    repeat(n) {
                        val k = r.u8()
                        val size = r.u8()
                        val raw = r.bytes(size)
                        val v: SettingValue = when {
                            k == DeviceSettingId.DEVICE_NAME -> SettingValue.Str(String(raw, Charsets.UTF_8))
                            size == 1 -> SettingValue.Bool(raw[0].toInt() != 0)
                            size == 4 -> SettingValue.Int32(ByteReader(raw).i32())
                            else -> SettingValue.Str(String(raw, Charsets.UTF_8))
                        }
                        list.add(k to v)
                    }
                    DeviceSettings(list)
                }
                GfdiId.SYSTEM_EVENT -> {
                    val type = r.u8()
                    if (r.remaining == 1) SystemEvent(type, r.u8()) else SystemEvent(type, r.str8())
                }
                GfdiId.SUPPORTED_FILE_TYPES_REQUEST -> SupportedFileTypesRequest
                GfdiId.SYNCHRONIZATION -> {
                    val type = r.u8()
                    val size = r.u8()
                    val mask = when (size) {
                        8 -> r.i64()
                        4 -> r.u32()
                        else -> throw MalformedException("synchronization mask size $size")
                    }
                    Synchronization(type, mask, size)
                }
                GfdiId.CONFIGURATION -> Configuration(Bytes(r.bytes(r.u8())))
                GfdiId.CURRENT_TIME_REQUEST -> CurrentTimeRequest(r.i32())
                GfdiId.AUTH_NEGOTIATION -> AuthNegotiation(r.u8(), r.i32())
                in GfdiId.SECURE_PAIRING_FIRST..GfdiId.SECURE_PAIRING_LAST -> SecurePairing(id, Bytes(p))
                else -> FeatureMessages.decodeOrNull(id, r) ?: Unknown(id, Bytes(p))
            }
        }
    }
}

/** Device setting ids (ordinals of the reference's `GarminDeviceSetting`). */
public object DeviceSettingId {
    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/SetDeviceSettingsMessage.java (GarminDeviceSetting order)
    /** Device name (string). */
    public const val DEVICE_NAME: Int = 0
    /** Auto upload (bool). */
    public const val AUTO_UPLOAD_ENABLED: Int = 6
    /** Weather conditions (bool). */
    public const val WEATHER_CONDITIONS_ENABLED: Int = 7
    /** Weather alerts (bool). */
    public const val WEATHER_ALERTS_ENABLED: Int = 8
}

/** System event types (ordinals of the reference's `GarminSystemEventType`). */
public object SystemEventType {
    // Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/SystemEventMessage.java (GarminSystemEventType order)
    /** Sync finished. */
    public const val SYNC_COMPLETE: Int = 0
    /** Sync failed. */
    public const val SYNC_FAIL: Int = 1
    /** Pairing started. */
    public const val PAIR_START: Int = 3
    /** Pairing finished. */
    public const val PAIR_COMPLETE: Int = 4
    /** Phone app came to the foreground. */
    public const val HOST_DID_ENTER_FOREGROUND: Int = 6
    /** Phone app went to the background. */
    public const val HOST_DID_ENTER_BACKGROUND: Int = 7
    /** Phone ready to sync. */
    public const val SYNC_READY: Int = 8
    /** Setup wizard finished. */
    public const val SETUP_WIZARD_COMPLETE: Int = 14
    /** Phone time changed; the watch asks for the time. */
    public const val TIME_UPDATED: Int = 16
}
