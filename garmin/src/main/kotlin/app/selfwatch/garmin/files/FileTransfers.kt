/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.files

import app.selfwatch.garmin.gfdi.Bytes
import app.selfwatch.garmin.gfdi.GfdiChannel
import app.selfwatch.garmin.gfdi.GfdiId
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.GfdiReplies
import app.selfwatch.garmin.gfdi.GfdiStatus
import app.selfwatch.garmin.gfdi.MalformedException
import app.selfwatch.garmin.gfdi.SystemEventType
import app.selfwatch.model.crc.Crc16
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * File downloads, archive flags and uploads over a [GfdiChannel]. One transfer at a time.
 *
 * Download (Ref: Gadgetbridge 160beab41 service/devices/garmin/FileTransferHandler.java, FileFragment.append):
 * DOWNLOAD_REQUEST(index, offset 0, NEW) → RESPONSE with download status and size → FILE_TRANSFER_DATA
 * chunks, each carrying the running CRC-16 of the whole file so far → each chunk answered with a
 * RESPONSE(ACK, transfer status OK, next offset).
 *
 * The session must hand every incoming FILE_TRANSFER_DATA to [onChunk].
 */
public class FileTransfers(
    private val ch: GfdiChannel,
    private val chunkTimeoutMs: Long = 15_000,
    private val requestTimeoutMs: Long = 10_000,
) {
    private val chunks = Channel<GfdiMessage.FileTransferData>(CHUNK_QUEUE)
    private val transferLock = Mutex()

    @Volatile
    private var downloading = false

    /** Largest GFDI frame the watch accepts (from DEVICE_INFORMATION); sizes upload chunks. */
    @Volatile
    public var maxPacketSize: Int = 375

    /** Delivers an incoming chunk; false if no download is running (the caller then refuses it). */
    public suspend fun onChunk(m: GfdiMessage.FileTransferData): Boolean {
        if (!downloading) return false
        return try {
            chunks.send(m)
            true
        } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
            false
        }
    }

    /** Ends any running download (the link closed). */
    public fun close() {
        chunks.close()
    }

    /** Downloads file [index] (0 = the directory). Fails on refusal, timeout, CRC error or size over [maxBytes]. */
    public suspend fun download(index: Int, maxBytes: Long, onProgress: (Int, Int) -> Unit = { _, _ -> }): Outcome<ByteArray> =
        transferLock.withLock {
            do { val more = chunks.tryReceive().isSuccess } while (more) // drop stale chunks
            downloading = true
            try {
                downloadLocked(index, maxBytes, onProgress)
            } finally {
                downloading = false
            }
        }

    private suspend fun downloadLocked(index: Int, maxBytes: Long, onProgress: (Int, Int) -> Unit): Outcome<ByteArray> {
        val resp = when (val r = ch.request(GfdiMessage.DownloadRequest(index), requestTimeoutMs)) {
            is Outcome.Ok -> r.value
            is Outcome.Fail -> return r
        }
        if (resp.status != GfdiStatus.ACK) return Outcome.Fail(SwError.Protocol("download of $index refused: ${resp.status}"))
        val st = try {
            GfdiReplies.DownloadStatus.parse(resp.extra.toByteArray())
        } catch (e: MalformedException) {
            return Outcome.Fail(SwError.Protocol("download status: ${e.message}"))
        }
        if (st.status != GfdiReplies.DownloadStatus.OK) return Outcome.Fail(SwError.Protocol("download of $index refused: status ${st.status}"))
        val size = st.maxFileSize
        if (size < 0 || size > maxBytes) return Outcome.Fail(SwError.BadInput("file $index size $size out of range"))
        val buf = ByteArray(size)
        var received = 0
        var crc = 0
        var offsetErrors = 0
        while (received < size) {
            val got = withTimeoutOrNull(chunkTimeoutMs) { chunks.receiveCatching() }
                ?: return Outcome.Fail(SwError.Timeout("chunk at $received of file $index"))
            val m = got.getOrNull() ?: return Outcome.Fail(SwError.Io("link closed during download of $index"))
            val data = m.data.toByteArray()
            if (m.offset != received) {
                SwLog.w(TAG, "chunk offset ${m.offset}, expected $received")
                if (++offsetErrors > MAX_OFFSET_ERRORS) {
                    replyChunk(GfdiReplies.TransferStatus.ABORT, received)
                    return Outcome.Fail(SwError.Protocol("too many out-of-order chunks in file $index"))
                }
                replyChunk(GfdiReplies.TransferStatus.OFFSET_MISMATCH, received)
                continue
            }
            if (received + data.size > size) {
                replyChunk(GfdiReplies.TransferStatus.ABORT, received)
                return Outcome.Fail(SwError.Protocol("file $index longer than announced"))
            }
            val next = Crc16.compute(data, initial = crc)
            if (next != m.crc) {
                replyChunk(GfdiReplies.TransferStatus.ABORT, received)
                return Outcome.Fail(SwError.Protocol("CRC mismatch in file $index at $received"))
            }
            crc = next
            data.copyInto(buf, received)
            received += data.size
            replyChunk(GfdiReplies.TransferStatus.OK, received)
            onProgress(received, size)
        }
        return Outcome.Ok(buf)
    }

    private suspend fun replyChunk(status: Int, nextOffset: Int) {
        ch.reply(GfdiId.FILE_TRANSFER_DATA, GfdiStatus.ACK, GfdiReplies.TransferStatus(status, nextOffset).encode())
    }

    /** Sets the archive flag on file [index] so the watch stops offering it as new. */
    public suspend fun archive(index: Int): Outcome<Unit> {
        val resp = when (val r = ch.request(GfdiMessage.SetFileFlag(index, GfdiMessage.SetFileFlag.ARCHIVE), requestTimeoutMs)) {
            is Outcome.Ok -> r.value
            is Outcome.Fail -> return r
        }
        if (resp.status != GfdiStatus.ACK) return Outcome.Fail(SwError.Protocol("archive of $index refused: ${resp.status}"))
        val st = try {
            GfdiReplies.FlagsStatus.parse(resp.extra.toByteArray())
        } catch (e: MalformedException) {
            null
        }
        if (st != null && st.status != GfdiReplies.FlagsStatus.APPLIED) {
            return Outcome.Fail(SwError.Protocol("archive of $index not applied: ${st.status}"))
        }
        return Outcome.Ok(Unit)
    }

    /**
     * Uploads [bytes] as a new file of [dataType]/[subType] (apps 255/17, settings, GPS assistance).
     * Used by app install (A21) and alarms (A20); follows the reference flow, not yet tried on a watch (T-A27).
     *
     * Ref: Gadgetbridge 160beab41 service/devices/garmin/FileTransferHandler.java (Upload: CREATE_FILE →
     * UPLOAD_REQUEST → FILE_TRANSFER_DATA chunks of maxPacket - 13 bytes → SYSTEM_EVENT SYNC_COMPLETE) and
     * messages/CreateFileMessage.java (random 64-bit file id)
     */
    public suspend fun upload(bytes: ByteArray, dataType: Int, subType: Int, onProgress: (Int, Int) -> Unit = { _, _ -> }): Outcome<Int> =
        transferLock.withLock {
            val created = when (val r = ch.request(GfdiMessage.CreateFile(bytes.size, dataType, subType, fileId = kotlin.random.Random.nextLong()), requestTimeoutMs)) {
                is Outcome.Ok -> r.value
                is Outcome.Fail -> return@withLock r
            }
            val cs = parseOrNull { GfdiReplies.CreateStatus.parse(created.extra.toByteArray()) }
            if (created.status != GfdiStatus.ACK || cs == null || cs.status != GfdiReplies.CreateStatus.OK) {
                return@withLock Outcome.Fail(SwError.Protocol("create file refused (${created.status}, ${cs?.status})"))
            }
            val up = when (val r = ch.request(GfdiMessage.UploadRequest(cs.fileIndex, bytes.size), requestTimeoutMs)) {
                is Outcome.Ok -> r.value
                is Outcome.Fail -> return@withLock r
            }
            val us = parseOrNull { GfdiReplies.UploadStatus.parse(up.extra.toByteArray()) }
            if (up.status != GfdiStatus.ACK || us == null || us.status != GfdiReplies.UploadStatus.OK) {
                return@withLock Outcome.Fail(SwError.Protocol("upload refused (${up.status}, ${us?.status})"))
            }
            var offset = us.offset
            if (offset < 0 || offset > bytes.size) return@withLock Outcome.Fail(SwError.Protocol("upload offset $offset"))
            var crc = if (offset == 0) 0 else Crc16.compute(bytes, 0, offset)
            val chunk = maxOf(1, maxPacketSize - UPLOAD_OVERHEAD)
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + chunk)
                crc = Crc16.compute(bytes, offset, end - offset, crc)
                val msg = GfdiMessage.FileTransferData(0, crc, offset, Bytes(bytes.copyOfRange(offset, end)))
                val r = when (val rr = ch.request(msg, requestTimeoutMs)) {
                    is Outcome.Ok -> rr.value
                    is Outcome.Fail -> return@withLock rr
                }
                val ts = parseOrNull { GfdiReplies.TransferStatus.parse(r.extra.toByteArray()) }
                if (r.status != GfdiStatus.ACK || ts == null || ts.status != GfdiReplies.TransferStatus.OK || ts.nextOffset != end) {
                    return@withLock Outcome.Fail(SwError.Protocol("upload chunk at $offset refused (${r.status}, ${ts?.status})"))
                }
                offset = end
                onProgress(offset, bytes.size)
            }
            ch.send(GfdiMessage.SystemEvent(SystemEventType.SYNC_COMPLETE, 0))
            Outcome.Ok(cs.fileIndex)
        }

    private inline fun <T> parseOrNull(block: () -> T): T? = try {
        block()
    } catch (e: MalformedException) {
        null
    }

    private companion object {
        const val TAG = "garmin.files"
        const val CHUNK_QUEUE = 16
        const val MAX_OFFSET_ERRORS = 8

        /** Frame bytes around an upload chunk: length, type, flags, CRC, offset, frame CRC (and margin). */
        const val UPLOAD_OVERHEAD = 13
    }
}
