/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin

import app.selfwatch.garmin.files.DirEntry
import app.selfwatch.garmin.gfdi.ByteReader
import app.selfwatch.garmin.gfdi.Bytes
import app.selfwatch.garmin.gfdi.FeatureReplies
import app.selfwatch.garmin.gfdi.FitData
import app.selfwatch.garmin.gfdi.FitDefinition
import app.selfwatch.garmin.gfdi.MusicEntityUpdate
import app.selfwatch.garmin.gfdi.NotificationData
import app.selfwatch.garmin.gfdi.NotificationUpdate
import app.selfwatch.garmin.gfdi.ProtobufPacket
import app.selfwatch.garmin.gfdi.GfdiId
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.GfdiReplies
import app.selfwatch.garmin.gfdi.GfdiStatus
import app.selfwatch.garmin.link.Cobs
import app.selfwatch.garmin.link.CobsDecoder
import app.selfwatch.garmin.link.GarminUuids
import app.selfwatch.garmin.link.MlControl
import app.selfwatch.garmin.link.Mlr
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminTime
import app.selfwatch.model.crc.Crc16
import app.selfwatch.model.fake.FakeBlePort
import app.selfwatch.model.fake.FakeBytePipe
import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.UUID

/** One file the fake watch serves. */
class FakeFile(val index: Int, val subType: Int, val garminTime: Long, val bytes: ByteArray, val dataType: Int = 128, val flags: Int = 0) {
    fun entry(): DirEntry = DirEntry(index, dataType, subType, index, 0, flags, bytes.size.toLong(), garminTime)
}

/**
 * Plays the watch side of a Garmin connection over a [FakeBlePort]: answers multi-link control
 * messages (or runs V1), optionally runs the reliable layer, sends DEVICE_INFORMATION and
 * CONFIGURATION, answers supported file types, serves the directory and files in chunks with correct
 * running CRCs, and records archive flags and everything the phone sent.
 */
class FakeWatch(
    private val scope: CoroutineScope,
    val port: FakeBlePort,
    val mode: Mode = Mode.V2_MLR,
    val files: List<FakeFile> = emptyList(),
    val capabilities: Set<GarminCapability> = setOf(
        GarminCapability.SYNC, GarminCapability.GNCS, GarminCapability.CONNECTIQ_WATCH_APP_DOWNLOAD,
        GarminCapability.CURRENT_TIME_REQUEST_SUPPORT, GarminCapability.FIND_MY_PHONE,
    ),
    val chunkSize: Int = 100,
    val productNumber: Int = 4426,
    val btName: String = "vívoactive 5",
    val withWatchAppService: Boolean = false,
    /** Corrupt the CRC of the chunk at this offset of this file index (once). */
    var corruptChunk: Pair<Int, Int>? = null,
    /** False: stop after DEVICE_INFORMATION (handshake timeout tests). */
    val sendConfiguration: Boolean = true,
    /** Max packet size announced in DEVICE_INFORMATION. */
    val maxPacket: Int = 375,
) {
    /** Complete notification attribute blocks received (NOTIFICATION_DATA reassembled, CRC checked). */
    val notificationBlocks: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
    private val notificationBuf = java.io.ByteArrayOutputStream()
    private var notificationCrc = 0

    /** FIT_DEFINITION / FIT_DATA payloads received. */
    val fitDefinitions: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
    val fitData: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())

    /** Whole protobuf messages from the phone: (message id, request id, bytes). */
    val protobufFromPhone: MutableList<Triple<Int, Int, ByteArray>> = Collections.synchronizedList(ArrayList())
    private val protobufPartial = java.io.ByteArrayOutputStream()

    /** Answers a whole PROTOBUF_REQUEST from the phone with these response bytes (null = no answer). */
    var protobufResponder: ((ByteArray) -> ByteArray?)? = null

    enum class Mode { V2_MLR, V2_PLAIN, V1 }

    val fromPhone: MutableList<GfdiMessage> = Collections.synchronizedList(ArrayList())
    val archived: MutableList<Int> = Collections.synchronizedList(ArrayList())
    val timeReplies: MutableList<GfdiReplies.TimeReply> = Collections.synchronizedList(ArrayList())
    val controlRequests: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
    val uploaded = java.io.ByteArrayOutputStream()
    private var uploadCrc = 0

    /** One upload chunk as received: offset, running CRC sent by the phone, chunk length. */
    data class UploadChunk(val offset: Int, val crc: Int, val size: Int)

    /** A finished upload: file type, subtype and bytes. */
    class UploadedFile(val dataType: Int, val subType: Int, val bytes: ByteArray)

    /** Chunks of the current upload, in order. */
    val uploadChunks: MutableList<UploadChunk> = Collections.synchronizedList(ArrayList())

    /** Every upload that arrived whole (CRC and offsets checked). */
    val uploads: MutableList<UploadedFile> = Collections.synchronizedList(ArrayList())

    /** CREATE_FILE answer status (0 = OK; e.g. 2 = no space). */
    var createStatus: Int = 0
    private var creating: Pair<Int, Int> = 0 to 0
    private var uploadSize = 0

    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val decoder = CobsDecoder()
    private val sendLock = Mutex()
    private lateinit var pipe: FakeBytePipe
    private var watchMlr: Mlr? = null
    private val handle = 3
    private var clientId = MlControl.DEFAULT_CLIENT_ID
    private var serving: FakeFile? = null
    private var servingBytes: ByteArray = ByteArray(0)
    private var sentConfiguration = false

    fun start() {
        val services = mutableSetOf<UUID>()
        when (mode) {
            Mode.V1 -> {
                services.add(GarminUuids.V1_SERVICE)
                pipe = port.pipe(GarminUuids.V1_SERVICE, GarminUuids.V1_RX, GarminUuids.V1_TX)
            }
            else -> {
                services.add(GarminUuids.ML_SERVICE)
                pipe = port.pipe(GarminUuids.ML_SERVICE, GarminUuids.ML_RX[0], GarminUuids.ML_TX[0])
            }
        }
        if (withWatchAppService) services.add(GarminUuids.WATCH_APP_SERVICE)
        port.services = services
        pipe.onWrite = { _, bytes -> inbound.trySend(bytes) }
        scope.launch {
            if (mode == Mode.V1) sendGfdi(deviceInformation())
            for (b in inbound) onPacket(b)
        }
    }

    fun deviceInformation(): GfdiMessage.DeviceInformation =
        GfdiMessage.DeviceInformation(150, productNumber, 3_456_789_012L, 1234, maxPacket, btName, "vívoactive 5", "006-B4426-00")

    private suspend fun onPacket(b: ByteArray) {
        when (mode) {
            Mode.V1 -> feed(b)
            Mode.V2_MLR, Mode.V2_PLAIN -> {
                val b0 = b[0].toInt() and 0xFF
                val m = watchMlr
                when {
                    b0 and 0x80 != 0 && m != null -> m.onPacket(b)
                    b0 == 0 -> onControl(b)
                    b0 == handle -> feed(b.copyOfRange(1, b.size))
                    else -> error("unexpected packet ${Bytes.hex(b)}")
                }
            }
        }
    }

    private suspend fun onControl(b: ByteArray) {
        controlRequests.add(b)
        val r = ByteReader(b)
        r.u8()
        val type = r.u8()
        clientId = r.i64()
        val service = r.u16()
        val extra = r.u8()
        val head = { t: Int -> app.selfwatch.garmin.gfdi.ByteWriter().u8(0).u8(t).i64(clientId) }
        when (type) {
            MlControl.CLOSE_ALL_REQ -> pipe.queueIncoming(head(MlControl.CLOSE_ALL_RESP).u16(0).toByteArray())
            MlControl.REGISTER_REQ -> {
                val reliable = mode == Mode.V2_MLR && extra != 0
                pipe.queueIncoming(head(MlControl.REGISTER_RESP).u16(service).u8(0).u8(handle).u8(if (reliable) 1 else 0).toByteArray())
                if (reliable) {
                    watchMlr = Mlr(handle, pipe.maxWrite, scope, sendPacket = { pipe.queueIncoming(it) }, onData = { feed(it) })
                }
                sendGfdi(deviceInformation())
            }
        }
    }

    private suspend fun feed(bytes: ByteArray) {
        for (frame in decoder.feed(bytes)) {
            val m = (GfdiMessage.decodeFrame(frame) as Outcome.Ok).value
            fromPhone.add(m)
            onGfdi(m)
        }
    }

    /** Sends a GFDI message from the watch to the phone. */
    suspend fun sendGfdi(m: GfdiMessage) = sendFrame(m.toFrame())

    /** Sends raw GFDI frame bytes (for fuzzing). */
    suspend fun sendFrame(frame: ByteArray) = sendLock.withLock {
        val enc = Cobs.encode(frame)
        val m = watchMlr
        when {
            mode == Mode.V1 -> enc.toList().chunked(pipe.maxWrite).forEach { pipe.queueIncoming(it.toByteArray()) }
            m != null -> m.send(enc)
            else -> enc.toList().chunked(pipe.maxWrite - 1).forEach { pipe.queueIncoming(byteArrayOf(handle.toByte()) + it.toByteArray()) }
        }
    }

    private suspend fun respond(id: Int, status: GfdiStatus = GfdiStatus.ACK, extra: ByteArray = ByteArray(0)) =
        sendGfdi(GfdiMessage.Response(id, status, Bytes(extra)))

    private suspend fun onGfdi(m: GfdiMessage) {
        when (m) {
            is GfdiMessage.Response -> when (m.originalId) {
                GfdiId.DEVICE_INFORMATION -> if (m.extra.size > 0 && !sentConfiguration && sendConfiguration) {
                    sentConfiguration = true
                    sendGfdi(GfdiMessage.Configuration(Bytes(GarminCapability.toBits(capabilities))))
                }
                GfdiId.FILE_TRANSFER_DATA -> {
                    val st = GfdiReplies.TransferStatus.parse(m.extra.toByteArray())
                    if (st.status == GfdiReplies.TransferStatus.OK && st.nextOffset < servingBytes.size) sendChunk(st.nextOffset)
                }
                GfdiId.CURRENT_TIME_REQUEST -> timeReplies.add(GfdiReplies.TimeReply.parse(m.extra.toByteArray()))
                else -> Unit
            }
            is GfdiMessage.SupportedFileTypesRequest -> respond(
                m.id,
                extra = GfdiReplies.SupportedFileTypes(
                    listOf(
                        GfdiReplies.FileTypeInfo(128, 4, "FIT_TYPE_4"),
                        GfdiReplies.FileTypeInfo(128, 32, "FIT_TYPE_32"),
                        GfdiReplies.FileTypeInfo(128, 49, "FIT_TYPE_49"),
                    ),
                ).encode(),
            )
            is GfdiMessage.DeviceSettings, is GfdiMessage.SystemEvent, is GfdiMessage.Configuration -> respond(m.id)
            is GfdiMessage.Filter -> respond(m.id, extra = byteArrayOf(0))
            is GfdiMessage.DownloadRequest -> {
                val bytes = if (m.fileIndex == 0) {
                    files.fold(ByteArray(0)) { acc, f -> acc + f.entry().encode() }
                } else {
                    files.firstOrNull { it.index == m.fileIndex }?.bytes
                }
                if (bytes == null) {
                    respond(m.id, extra = GfdiReplies.DownloadStatus(1, 0).encode())
                    return
                }
                serving = files.firstOrNull { it.index == m.fileIndex }
                servingBytes = bytes
                respond(m.id, extra = GfdiReplies.DownloadStatus(GfdiReplies.DownloadStatus.OK, bytes.size).encode())
                if (bytes.isNotEmpty()) sendChunk(0)
            }
            is GfdiMessage.CreateFile -> {
                creating = m.dataType to m.subType
                respond(m.id, extra = GfdiReplies.CreateStatus(createStatus, 20, m.dataType, m.subType, 1).encode())
            }
            is GfdiMessage.UploadRequest -> {
                uploaded.reset()
                uploadChunks.clear()
                uploadCrc = 0
                uploadSize = m.size
                respond(m.id, extra = GfdiReplies.UploadStatus(0, 0, m.size, 0).encode())
            }
            is GfdiMessage.FileTransferData -> {
                val d = m.data.toByteArray()
                uploadCrc = Crc16.compute(d, initial = uploadCrc)
                val ok = m.offset == uploaded.size() && m.crc == uploadCrc
                if (ok) uploaded.write(d)
                uploadChunks.add(UploadChunk(m.offset, m.crc, d.size))
                if (ok && uploaded.size() == uploadSize) uploads.add(UploadedFile(creating.first, creating.second, uploaded.toByteArray()))
                val st = if (ok) GfdiReplies.TransferStatus.OK else GfdiReplies.TransferStatus.CRC_MISMATCH
                respond(m.id, extra = GfdiReplies.TransferStatus(st, uploaded.size()).encode())
            }
            is GfdiMessage.SetFileFlag -> {
                archived.add(m.fileIndex)
                respond(m.id, extra = GfdiReplies.FlagsStatus(GfdiReplies.FlagsStatus.APPLIED, m.fileIndex, m.flags).encode())
            }
            is NotificationData -> {
                val d = m.chunk.toByteArray()
                notificationCrc = Crc16.compute(d, initial = if (m.offset == 0) 0 else notificationCrc)
                if (m.offset == 0) notificationBuf.reset()
                val ok = m.offset == notificationBuf.size() && m.crc == notificationCrc
                if (ok) notificationBuf.write(d)
                if (ok && notificationBuf.size() == m.messageSize) notificationBlocks.add(notificationBuf.toByteArray())
                respond(m.id, extra = FeatureReplies.DataStatus(if (ok) 0 else 3).encode())
            }
            is FitDefinition -> {
                fitDefinitions.add(m.records.toByteArray())
                respond(m.id, extra = FeatureReplies.FitStatus(FeatureReplies.FitStatus.APPLIED).encode())
            }
            is FitData -> {
                fitData.add(m.records.toByteArray())
                respond(m.id, extra = FeatureReplies.FitStatus(FeatureReplies.FitStatus.APPLIED).encode())
            }
            is ProtobufPacket -> onProtobuf(m)
            is NotificationUpdate, is MusicEntityUpdate -> respond(m.id)
            else -> Unit
        }
    }

    private suspend fun onProtobuf(m: ProtobufPacket) {
        val whole: ByteArray? = if (m.isComplete) {
            respond(m.id)
            m.data.toByteArray()
        } else {
            if (m.dataOffset == 0) protobufPartial.reset()
            protobufPartial.write(m.data.toByteArray())
            respond(m.id, extra = FeatureReplies.ProtobufStatus(m.requestId, m.dataOffset, 0, 0).encode())
            if (protobufPartial.size() == m.totalLength) protobufPartial.toByteArray() else null
        }
        if (whole == null) return
        protobufFromPhone.add(Triple(m.id, m.requestId, whole))
        if (m.id == GfdiId.PROTOBUF_REQUEST) {
            protobufResponder?.invoke(whole)?.let { sendProtobuf(GfdiId.PROTOBUF_RESPONSE, m.requestId, it) }
        }
    }

    /** Sends a protobuf message from the watch, in chunks of [chunk] bytes (back to back). */
    suspend fun sendProtobuf(msgId: Int, requestId: Int, bytes: ByteArray, chunk: Int = maxPacket - 20) {
        if (bytes.size <= chunk) {
            sendGfdi(ProtobufPacket(msgId, requestId, 0, bytes.size, Bytes(bytes)))
            return
        }
        var off = 0
        while (off < bytes.size) {
            val end = minOf(bytes.size, off + chunk)
            sendGfdi(ProtobufPacket(msgId, requestId, off, bytes.size, Bytes(bytes.copyOfRange(off, end))))
            off = end
        }
    }

    private suspend fun sendChunk(offset: Int) {
        val end = minOf(servingBytes.size, offset + chunkSize)
        var crc = Crc16.compute(servingBytes, 0, end)
        val c = corruptChunk
        if (c != null && serving?.index == c.first && offset == c.second) {
            crc = crc xor 0x5555
            corruptChunk = null
        }
        sendGfdi(GfdiMessage.FileTransferData(0, crc, offset, Bytes(servingBytes.copyOfRange(offset, end))))
    }

    /** Asks the phone for the time. */
    suspend fun requestTime(ref: Int) = sendGfdi(GfdiMessage.CurrentTimeRequest(ref))

    companion object {
        /** Garmin time for a Unix time. */
        fun gt(unix: Long): Long = GarminTime.fromUnix(unix).toLong() and 0xFFFF_FFFFL
    }
}
