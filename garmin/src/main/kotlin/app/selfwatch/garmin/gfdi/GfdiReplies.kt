/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.gfdi

/**
 * Typed bodies of RESPONSE messages (the bytes after original id and status). Each has `encode()` and
 * a `parse()` so tests can play either side.
 */
public object GfdiReplies {
    /**
     * Our answer to DEVICE_INFORMATION.
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/DeviceInformationMessage.java (reply layout:
     * protocol 150, product 0xFFFF (-1), unit -1, software 7791, max packet 0xFFFF (-1), BT name, manufacturer,
     * device, then a protocol-flags byte = 1 when the watch's protocol version is 1xx).
     */
    public data class DeviceInfoReply(
        val protocolVersion: Int = 150,
        val productNumber: Int = 0xFFFF,
        val unitNumber: Int = -1,
        val softwareVersion: Int = 7791,
        val maxPacketSize: Int = 0xFFFF,
        val bluetoothName: String,
        val manufacturer: String,
        val model: String,
        val protocolFlags: Int,
    ) {
        /** Body bytes. */
        public fun encode(): ByteArray = ByteWriter().u16(protocolVersion).u16(productNumber).i32(unitNumber)
            .u16(softwareVersion).u16(maxPacketSize).str8(bluetoothName).str8(manufacturer).str8(model)
            .u8(protocolFlags).toByteArray()

        /** Parser. */
        public companion object {
            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): DeviceInfoReply {
                val r = ByteReader(b)
                return DeviceInfoReply(r.u16(), r.u16(), r.i32(), r.u16(), r.u16(), r.str8(), r.str8(), r.str8(), r.u8())
            }
        }
    }

    /**
     * Answer to CURRENT_TIME_REQUEST. Times are Garmin-epoch seconds; [tzOffsetSec] is the whole UTC
     * offset (DST included). The reference writes the transition after next first, then the next one.
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/CurrentTimeRequestMessage.java (reply layout)
     */
    public data class TimeReply(
        val referenceId: Int,
        val garminTime: Int,
        val tzOffsetSec: Int,
        val transitionAfterNext: Int,
        val nextTransition: Int,
    ) {
        /** Body bytes. */
        public fun encode(): ByteArray = ByteWriter().i32(referenceId).i32(garminTime).i32(tzOffsetSec)
            .i32(transitionAfterNext).i32(nextTransition).toByteArray()

        /** Parser. */
        public companion object {
            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): TimeReply {
                val r = ByteReader(b)
                return TimeReply(r.i32(), r.i32(), r.i32(), r.i32(), r.i32())
            }
        }
    }

    /**
     * Answer to AUTH_NEGOTIATION: auth status (0 GUESS_OK), the watch's unknown byte, and its flags echoed.
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/status/AuthNegotiationStatusMessage.java
     */
    public data class AuthReply(val authStatus: Int, val unknown: Int, val flags: Int) {
        /** Body bytes. */
        public fun encode(): ByteArray = ByteWriter().u8(authStatus).u8(unknown).i32(flags).toByteArray()

        /** Parser. */
        public companion object {
            /** Auth accepted ("guess OK"). */
            public const val GUESS_OK: Int = 0

            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): AuthReply {
                val r = ByteReader(b)
                return AuthReply(r.u8(), r.u8(), r.i32())
            }
        }
    }

    /**
     * Status of a FILE_TRANSFER_DATA chunk: transfer status and the next offset expected.
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/status/FileTransferDataStatusMessage.java
     * (OK 0, RESEND 1, ABORT 2, CRC_MISMATCH 3, OFFSET_MISMATCH 4, SYNC_PAUSED 5)
     */
    public data class TransferStatus(val status: Int, val nextOffset: Int) {
        /** Body bytes. */
        public fun encode(): ByteArray = ByteWriter().u8(status).i32(nextOffset).toByteArray()

        /** Codes and parser. */
        public companion object {
            /** Chunk accepted. */
            public const val OK: Int = 0
            /** Send again. */
            public const val RESEND: Int = 1
            /** Stop the transfer. */
            public const val ABORT: Int = 2
            /** CRC wrong. */
            public const val CRC_MISMATCH: Int = 3
            /** Offset wrong. */
            public const val OFFSET_MISMATCH: Int = 4

            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): TransferStatus {
                val r = ByteReader(b)
                return TransferStatus(r.u8(), r.i32())
            }
        }
    }

    /**
     * Status of a DOWNLOAD_REQUEST: download status (0 OK) and the file size.
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/status/DownloadRequestStatusMessage.java
     * (OK, INDEX_NOT_FOUND, NOT_READABLE, NOT_READY, INVALID_REQUEST, CRC_MISMATCH, RANGE_EXCEEDS_FILE)
     */
    public data class DownloadStatus(val status: Int, val maxFileSize: Int) {
        /** Body bytes. */
        public fun encode(): ByteArray = ByteWriter().u8(status).i32(maxFileSize).toByteArray()

        /** Parser. */
        public companion object {
            /** Download may start. */
            public const val OK: Int = 0

            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): DownloadStatus {
                val r = ByteReader(b)
                return DownloadStatus(r.u8(), r.i32())
            }
        }
    }

    /**
     * Status of SET_FILE_FLAG: flags status (0 APPLIED), file index, flags.
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/status/SetFileFlagsStatusMessage.java
     */
    public data class FlagsStatus(val status: Int, val fileIndex: Int, val flags: Int) {
        /** Body bytes. */
        public fun encode(): ByteArray = ByteWriter().u8(status).u16(fileIndex).u8(flags).toByteArray()

        /** Parser. */
        public companion object {
            /** Flags applied. */
            public const val APPLIED: Int = 0

            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): FlagsStatus {
                val r = ByteReader(b)
                return FlagsStatus(r.u8(), r.u16(), r.u8())
            }
        }
    }

    /** One supported file type. */
    public data class FileTypeInfo(val dataType: Int, val subType: Int, val name: String)

    /**
     * Answer to SUPPORTED_FILE_TYPES_REQUEST: count, then (type, subtype, name) entries.
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/status/SupportedFileTypesStatusMessage.java
     */
    public data class SupportedFileTypes(val types: List<FileTypeInfo>) {
        /** Body bytes. */
        public fun encode(): ByteArray {
            val w = ByteWriter().u8(types.size)
            for (t in types) w.u8(t.dataType).u8(t.subType).str8(t.name)
            return w.toByteArray()
        }

        /** Parser. */
        public companion object {
            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): SupportedFileTypes {
                val r = ByteReader(b)
                val n = r.u8()
                return SupportedFileTypes(List(n) { FileTypeInfo(r.u8(), r.u8(), r.str8()) })
            }
        }
    }

    /**
     * Status of CREATE_FILE: create status (0 OK), assigned file index, type, subtype, file number.
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/status/CreateFileStatusMessage.java
     */
    public data class CreateStatus(val status: Int, val fileIndex: Int, val dataType: Int, val subType: Int, val number: Int) {
        /** Body bytes. */
        public fun encode(): ByteArray = ByteWriter().u8(status).u16(fileIndex).u8(dataType).u8(subType).u16(number).toByteArray()

        /** Parser. */
        public companion object {
            /** File created. */
            public const val OK: Int = 0

            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): CreateStatus {
                val r = ByteReader(b)
                return CreateStatus(r.u8(), r.u16(), r.u8(), r.u8(), r.u16())
            }
        }
    }

    /**
     * Status of UPLOAD_REQUEST: upload status (0 OK), data offset, max file size, CRC seed.
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/status/UploadRequestStatusMessage.java
     */
    public data class UploadStatus(val status: Int, val offset: Int, val maxFileSize: Int, val crcSeed: Int) {
        /** Body bytes. */
        public fun encode(): ByteArray = ByteWriter().u8(status).i32(offset).i32(maxFileSize).u16(crcSeed).toByteArray()

        /** Parser. */
        public companion object {
            /** Upload may start. */
            public const val OK: Int = 0

            /** Parses a body; throws [MalformedException] if short. */
            public fun parse(b: ByteArray): UploadStatus {
                val r = ByteReader(b)
                return UploadStatus(r.u8(), r.i32(), r.i32(), r.u16())
            }
        }
    }
}
