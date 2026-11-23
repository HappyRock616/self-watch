/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.appmsg.CiqCodec
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.pb.PbHandler
import app.selfwatch.garmin.pb.PbResult
import app.selfwatch.garmin.pb.ProtobufService
import app.selfwatch.garmin.pb.proto.DataTransferProto
import app.selfwatch.garmin.pb.proto.HttpProto
import app.selfwatch.garmin.pb.proto.HttpProto.HttpService
import app.selfwatch.garmin.pb.proto.SmartProto.Smart
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import com.google.protobuf.ByteString
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.zip.GZIPOutputStream

/**
 * Watch-app web requests (A23) over the protobuf HTTP service.
 *
 * A Connect IQ `makeWebRequest` (needs `CONNECTIQ_HTTP`) or raw resource request (needs `CONNECTIQ_HTTP` or
 * `HTTP_RAW_RESOURCE_REQUEST`) becomes a [WatchEvent.WebRequest] with a fresh `requestId`; the chunk is
 * answered "kept" at once and the answer follows as a PROTOBUF_RESPONSE with the watch's request id when the
 * app sends [WatchCommand.WebResponse] (as the reference answers asynchronously). Without an answer in
 * [responseTimeoutMs] the watch gets "network request timed out". At most [maxPending] requests wait at once.
 *
 * Requests the watch makes for GPS ephemeris ([GpsAssist.isEphemerisRequest]) carry the extra header
 * [EPHEMERIS_HEADER] = "1" so the app can answer them from its cached file instead of the network.
 *
 * **Requests:** method (UNKNOWN → GET); headers from the Monkey C dictionary, with Connect IQ's content-type
 * codes "0" → `application/x-www-form-urlencoded`, "1" → `application/json`; the body is the Monkey C value
 * as JSON text, or form-encoded when the content type says so (raw bytes if it does not decode). Raw requests
 * pass their header list (keys lower-cased) and body through. Raw requests with multipart forms or an
 * encrypted body are answered UNKNOWN at once (the reference does not support them either).
 *
 * **Responses** ([WatchCommand.WebResponse]): `status` is the HTTP status (100–599); [STATUS_NETWORK_ERROR] (0)
 * means no answer from the network (→ "timed out"); a negative status `-n` is the Connect IQ error `n` itself
 * when the protocol has it (-200 bad headers, -300 timed out, -402 too large, -1001 insecure...; see
 * [webErrorStatus] and [rawErrorStatus]); any other negative value, such as [STATUS_FAILED], means it failed or
 * was blocked (→ UNKNOWN). For `makeWebRequest`: a body longer than the watch's `max_response_length` (or
 * [defaultMaxResponse] when it sends none) → NETWORK_RESPONSE_TOO_LARGE; a JSON content type is parsed into a
 * Monkey C value (unparsable → INVALID_HTTP_BODY_IN_NETWORK_RESPONSE); a non-JSON answer to a request that
 * expects JSON → INVALID_HTTP_BODY_IN_NETWORK_RESPONSE; other text goes as a string. The content type is
 * returned as a header dictionary when the watch asks for headers. For raw requests: over `max_size` →
 * FILE_TOO_LARGE; with `use_data_xfer` the body is stored and the watch pulls it through the data transfer
 * service (DataDownloadRequest by id and offset); otherwise it is sent inline, gzip-compressed when the
 * request's `accept-encoding` is gzip.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/http/HttpHandler.java (handleWebRequest, handleRawRequest,
 * createWebResponse, createRawResponse, createErrorResponse), GarminHttpRequest.java (header and body
 * conversion, content-type codes), DataTransferHandler.java (registerData, DataDownloadRequest with
 * max_chunk_size, INVALID_ID / INVALID_OFFSET), interceptors/FirewallInterceptor.java (async answer as a
 * ProtobufResponseEvent with the original request id), interceptors/AgpsInterceptor.java (ephemeris URLs)
 */
public class WebRequestHandler(
    private val protobuf: ProtobufService,
    private val responseTimeoutMs: Long = 30_000,
    private val maxPending: Int = 16,
    private val defaultMaxResponse: Int = DEFAULT_MAX_RESPONSE,
) : GfdiHandler {
    private class Pending(
        val session: GarminSessionContext,
        val pbRequestId: Int,
        val web: HttpProto.WebRequest?,
        val raw: HttpProto.RawResourceRequest?,
        val acceptGzip: Boolean,
    ) {
        var timer: Job? = null
    }

    private val lock = Any()
    private var current: GarminSessionContext? = null
    private var nextId = 1
    private val pending = LinkedHashMap<Int, Pending>()
    private val store = DataStore()

    /** Takes HTTP and data transfer messages from the watch. */
    public val pb: PbHandler = object : PbHandler {
        override suspend fun onMessage(smart: Smart, isResponse: Boolean, session: GarminSessionContext): PbResult? = null

        override suspend fun onMessage(smart: Smart, isResponse: Boolean, requestId: Int, session: GarminSessionContext): PbResult? {
            sessionChanged(session)
            return when {
                smart.hasConnectIqHttpService() && !isResponse -> onHttp(smart.connectIqHttpService, requestId, session)
                smart.hasDataTransferService() -> onDataTransfer(smart.dataTransferService)
                else -> null
            }
        }
    }

    override suspend fun handle(m: GfdiMessage, session: GarminSessionContext): Boolean {
        sessionChanged(session)
        return false
    }

    override suspend fun command(cmd: WatchCommand, session: GarminSessionContext): Outcome<Unit>? {
        if (cmd !is WatchCommand.WebResponse) return null
        sessionChanged(session)
        val p = synchronized(lock) { pending.remove(cmd.requestId) }
            ?: return Outcome.Fail(SwError.BadInput("no waiting web request ${cmd.requestId}"))
        p.timer?.cancel()
        if (p.session !== session) return Outcome.Fail(SwError.Io("web request ${cmd.requestId} came from an earlier connection"))
        return protobuf.respond(session, p.pbRequestId, Smart.newBuilder().setConnectIqHttpService(answer(p, cmd)).build())
    }

    /** Number of requests waiting for a [WatchCommand.WebResponse] (for tests and diagnostics). */
    public val pendingCount: Int get() = synchronized(lock) { pending.size }

    private fun sessionChanged(session: GarminSessionContext) = synchronized(lock) {
        if (current !== session) {
            current = session
            pending.clear()
            store.clear()
        }
    }

    private fun onHttp(http: HttpService, pbRequestId: Int, session: GarminSessionContext): PbResult? {
        val caps = session.capabilities
        return when {
            http.hasConnectIqHttpRequest() -> {
                if (GarminCapability.CONNECTIQ_HTTP !in caps) return null
                val r = http.connectIqHttpRequest
                val headers = webHeaders(r)
                val body = if (r.hasHttpBody() && !r.httpBody.isEmpty) webBody(r.httpBody.toByteArray(), headers) else null
                enqueue(Pending(session, pbRequestId, r, null, false), r.url, methodName(r.httpMethod), headers, body)
            }
            http.hasRawResourceRequest() -> {
                if (GarminCapability.CONNECTIQ_HTTP !in caps && GarminCapability.HTTP_RAW_RESOURCE_REQUEST !in caps) return null
                val r = http.rawResourceRequest
                if (r.formsCount > 0 || r.hasEncryptedRequest()) {
                    SwLog.w(TAG, "raw request to ${safeUrl(r.url)} needs forms or encryption; not supported")
                    return PbResult.Reply(Smart.newBuilder().setConnectIqHttpService(rawError(HttpProto.RawResourceResponse.Status.UNKNOWN)).build())
                }
                val headers = LinkedHashMap<String, String>()
                for (h in r.headersList) headers[h.key.lowercase()] = h.value
                val body = when {
                    r.hasBody() -> r.body.toByteArray(Charsets.UTF_8)
                    !r.rawBody.isEmpty -> r.rawBody.toByteArray()
                    else -> null
                }
                val gzip = headers["accept-encoding"]?.contains("gzip") == true
                enqueue(Pending(session, pbRequestId, null, r, gzip), r.url, methodName(r.method), headers, body)
            }
            else -> null
        }
    }

    private fun enqueue(p: Pending, url: String, method: String, headers: MutableMap<String, String>, body: ByteArray?): PbResult {
        val raw = p.raw != null
        val id = synchronized(lock) {
            if (pending.size >= maxPending) {
                null
            } else {
                val id = nextId
                nextId = if (nextId == Int.MAX_VALUE) 1 else nextId + 1
                pending[id] = p
                id
            }
        }
        if (id == null) {
            SwLog.w(TAG, "too many web requests waiting; refusing ${safeUrl(url)}")
            return PbResult.Reply(Smart.newBuilder().setConnectIqHttpService(if (raw) rawError(HttpProto.RawResourceResponse.Status.UNKNOWN) else webError(HttpProto.WebResponse.Status.UNKNOWN)).build())
        }
        if (GpsAssist.isEphemerisRequest(url)) headers[EPHEMERIS_HEADER] = "1"
        p.timer = p.session.scope.launch {
            delay(responseTimeoutMs)
            val still = synchronized(lock) { pending.remove(id) }
            if (still != null) {
                SwLog.w(TAG, "web request $id got no answer in time")
                val e = if (raw) rawError(HttpProto.RawResourceResponse.Status.NETWORK_REQUEST_TIMED_OUT) else webError(HttpProto.WebResponse.Status.NETWORK_REQUEST_TIMED_OUT)
                protobuf.respond(p.session, p.pbRequestId, Smart.newBuilder().setConnectIqHttpService(e).build())
            }
        }
        SwLog.d(TAG) { "web request $id: $method ${safeUrl(url)}" }
        p.session.emit(WatchEvent.WebRequest(id, null, url, method, headers, body))
        return PbResult.Done
    }

    private fun onDataTransfer(dt: DataTransferProto.DataTransferService): PbResult? {
        if (!dt.hasDataDownloadRequest()) return null
        val req = dt.dataDownloadRequest
        val max = if (req.hasMaxChunkSize() && req.maxChunkSize > 0) minOf(req.maxChunkSize, MAX_DATA_CHUNK) else MAX_DATA_CHUNK
        val resp = DataTransferProto.DataDownloadResponse.newBuilder().setId(req.id).setOffset(req.offset)
        val data = synchronized(lock) { store.get(req.id) }
        when {
            data == null -> resp.status = DataTransferProto.DataDownloadResponse.Status.INVALID_ID
            req.offset < 0 || req.offset >= data.size -> resp.status = DataTransferProto.DataDownloadResponse.Status.INVALID_OFFSET
            else -> {
                resp.status = DataTransferProto.DataDownloadResponse.Status.SUCCESS
                resp.payload = ByteString.copyFrom(data, req.offset, minOf(max, data.size - req.offset))
            }
        }
        return PbResult.Reply(Smart.newBuilder().setDataTransferService(DataTransferProto.DataTransferService.newBuilder().setDataDownloadResponse(resp)).build())
    }

    private fun answer(p: Pending, r: WatchCommand.WebResponse): HttpService {
        if (p.raw != null) return rawAnswer(p, p.raw, r)
        val web = requireNotNull(p.web)
        val out = HttpProto.WebResponse.newBuilder()
        if (r.status <= 0) {
            return HttpService.newBuilder().setConnectIqHttpResponse(out.setStatus(webErrorStatus(r.status)).setHttpStatusCode(0)).build()
        }
        val limit = if (web.hasMaxResponseLength() && web.maxResponseLength > 0) web.maxResponseLength else defaultMaxResponse
        if (r.body.size > limit) {
            SwLog.w(TAG, "web response ${r.requestId} has ${r.body.size} bytes; the watch takes $limit")
            return HttpService.newBuilder().setConnectIqHttpResponse(
                out.setStatus(HttpProto.WebResponse.Status.NETWORK_RESPONSE_TOO_LARGE).setHttpStatusCode(0),
            ).build()
        }
        val ct = r.contentType?.lowercase()
        val wantsJson = web.responseType == HttpProto.ResponseType.JSON
        val text = String(r.body, Charsets.UTF_8)
        val value: CiqValue = when {
            ct?.contains("json") == true || (ct == null && wantsJson) -> when (val j = CiqJson.parse(text)) {
                is Outcome.Ok -> j.value
                is Outcome.Fail -> return HttpService.newBuilder().setConnectIqHttpResponse(
                    out.setStatus(HttpProto.WebResponse.Status.INVALID_HTTP_BODY_IN_NETWORK_RESPONSE).setHttpStatusCode(0),
                ).build()
            }
            wantsJson -> return HttpService.newBuilder().setConnectIqHttpResponse(
                out.setStatus(HttpProto.WebResponse.Status.INVALID_HTTP_BODY_IN_NETWORK_RESPONSE).setHttpStatusCode(r.status),
            ).build()
            else -> CiqValue.Str(text)
        }
        out.setStatus(HttpProto.WebResponse.Status.OK).setHttpStatusCode(r.status)
            .setHttpBody(ByteString.copyFrom(CiqCodec.encode(value))).setInflatedSize(0)
        if (web.includeHttpHeaderFieldsInResponse) {
            val h = LinkedHashMap<CiqValue, CiqValue>()
            r.contentType?.let { h[CiqValue.Str("Content-Type")] = CiqValue.Str(it) }
            out.setHttpHeaderFields(ByteString.copyFrom(CiqCodec.encode(CiqValue.Dict(h))))
        }
        return HttpService.newBuilder().setConnectIqHttpResponse(out).build()
    }

    private fun rawAnswer(p: Pending, raw: HttpProto.RawResourceRequest, r: WatchCommand.WebResponse): HttpService {
        val out = HttpProto.RawResourceResponse.newBuilder()
        if (r.status <= 0) {
            out.status = rawErrorStatus(r.status)
            return HttpService.newBuilder().setRawResourceResponse(out).build()
        }
        if (raw.hasMaxSize() && raw.maxSize > 0 && r.body.size > raw.maxSize) {
            out.status = HttpProto.RawResourceResponse.Status.FILE_TOO_LARGE
            out.httpStatusCode = r.status
            return HttpService.newBuilder().setRawResourceResponse(out).build()
        }
        out.status = HttpProto.RawResourceResponse.Status.OK
        out.httpStatusCode = r.status
        r.contentType?.let { out.addHeaders(HttpProto.HttpHeader.newBuilder().setKey("Content-Type").setValue(it)) }
        when {
            raw.useDataXfer -> {
                val id = synchronized(lock) { store.put(r.body) }
                out.setXferData(HttpProto.DataTransferItem.newBuilder().setId(id).setSize(r.body.size))
            }
            p.acceptGzip -> {
                out.addHeaders(HttpProto.HttpHeader.newBuilder().setKey("Content-Encoding").setValue("gzip"))
                out.resourceData = ByteString.copyFrom(gzip(r.body))
            }
            else -> out.resourceData = ByteString.copyFrom(r.body)
        }
        return HttpService.newBuilder().setRawResourceResponse(out).build()
    }

    private fun rawError(status: HttpProto.RawResourceResponse.Status): HttpService =
        HttpService.newBuilder().setRawResourceResponse(HttpProto.RawResourceResponse.newBuilder().setStatus(status)).build()

    private fun webError(status: HttpProto.WebResponse.Status): HttpService =
        HttpService.newBuilder().setConnectIqHttpResponse(HttpProto.WebResponse.newBuilder().setStatus(status).setHttpStatusCode(0)).build()

    private fun webHeaders(r: HttpProto.WebRequest): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (!r.hasHttpHeaderFields() || r.httpHeaderFields.isEmpty) return out
        val dict = (CiqCodec.decode(r.httpHeaderFields.toByteArray()) as? Outcome.Ok)?.value as? CiqValue.Dict ?: return out
        for ((k, v) in dict.v) {
            val key = (k as? CiqValue.Str)?.v ?: continue
            var value = when (v) {
                is CiqValue.Str -> v.v
                is CiqValue.Num -> v.v.toString()
                else -> CiqJson.write(v)
            }
            if (key.equals("content-type", ignoreCase = true)) {
                value = when (value) {
                    CIQ_CONTENT_TYPE_URL_ENCODED -> MIME_URL_ENCODED
                    CIQ_CONTENT_TYPE_JSON -> MIME_JSON
                    else -> value
                }
            }
            out[key] = value
        }
        return out
    }

    private fun webBody(bytes: ByteArray, headers: Map<String, String>): ByteArray {
        val v = (CiqCodec.decode(bytes) as? Outcome.Ok)?.value ?: return bytes
        val ct = headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
        if (ct != null && ct.startsWith(MIME_URL_ENCODED) && v is CiqValue.Dict) {
            return v.v.entries.joinToString("&") { (k, e) ->
                val key = (k as? CiqValue.Str)?.v ?: CiqJson.write(k)
                val text = when (e) {
                    is CiqValue.Str -> e.v
                    CiqValue.Null -> ""
                    else -> CiqJson.write(e)
                }
                URLEncoder.encode(key, Charsets.UTF_8) + "=" + URLEncoder.encode(text, Charsets.UTF_8)
            }.toByteArray(Charsets.UTF_8)
        }
        return CiqJson.write(v).toByteArray(Charsets.UTF_8)
    }

    private fun methodName(m: HttpProto.HttpMethod): String = if (m == HttpProto.HttpMethod.UNKNOWN) "GET" else m.name

    /** Large raw answers the watch pulls by id; the oldest are dropped beyond [MAX_STORED] items or [MAX_STORED_BYTES]. */
    private class DataStore {
        private val items = LinkedHashMap<Int, ByteArray>()
        private var next = 1
        private var bytes = 0L

        fun put(b: ByteArray): Int {
            val id = next
            next = if (next == Int.MAX_VALUE) 1 else next + 1
            items[id] = b
            bytes += b.size
            while (items.size > 1 && (items.size > MAX_STORED || bytes > MAX_STORED_BYTES)) {
                val first = items.keys.first()
                bytes -= items.remove(first)?.size ?: 0
            }
            return id
        }

        fun get(id: Int): ByteArray? = items[id]

        fun clear() {
            items.clear()
            bytes = 0
        }
    }

    /** Constants. */
    public companion object {
        private const val TAG = "garmin.web"

        /** Header added to requests for GPS ephemeris ([GpsAssist.isEphemerisRequest]); value "1". */
        public const val EPHEMERIS_HEADER: String = "X-SelfWatch-Ephemeris"

        /** [WatchCommand.WebResponse.status] for "no answer from the network" (the watch hears "timed out"). */
        public const val STATUS_NETWORK_ERROR: Int = 0

        /** [WatchCommand.WebResponse.status] for "failed or blocked" (the watch hears UNKNOWN). */
        public const val STATUS_FAILED: Int = -1

        /**
         * The `makeWebRequest` status for an error [status] (0 or negative): 0 → NETWORK_REQUEST_TIMED_OUT; `-n` →
         * the protocol's status `n` when it is an error status the protocol has; anything else → UNKNOWN.
         */
        public fun webErrorStatus(status: Int): HttpProto.WebResponse.Status {
            if (status == STATUS_NETWORK_ERROR) return HttpProto.WebResponse.Status.NETWORK_REQUEST_TIMED_OUT
            val s = if (status < 0 && status != Int.MIN_VALUE) HttpProto.WebResponse.Status.forNumber(-status) else null
            return if (s == null || s == HttpProto.WebResponse.Status.OK) HttpProto.WebResponse.Status.UNKNOWN else s
        }

        /**
         * The raw-resource status for an error [status] (0 or negative): 0 and -300 (timed out) →
         * NETWORK_REQUEST_TIMED_OUT, -402 (too large) → FILE_TOO_LARGE, anything else → UNKNOWN.
         */
        public fun rawErrorStatus(status: Int): HttpProto.RawResourceResponse.Status = when (status) {
            STATUS_NETWORK_ERROR, -HttpProto.WebResponse.Status.NETWORK_REQUEST_TIMED_OUT_VALUE ->
                HttpProto.RawResourceResponse.Status.NETWORK_REQUEST_TIMED_OUT
            -HttpProto.WebResponse.Status.NETWORK_RESPONSE_TOO_LARGE_VALUE -> HttpProto.RawResourceResponse.Status.FILE_TOO_LARGE
            else -> HttpProto.RawResourceResponse.Status.UNKNOWN
        }

        /** Response limit when the watch sends no `max_response_length` (Self Watch's choice). */
        public const val DEFAULT_MAX_RESPONSE: Int = 64 * 1024

        /** Largest data transfer chunk served, even if the watch asks for more. */
        public const val MAX_DATA_CHUNK: Int = 64 * 1024

        private const val MAX_STORED = 8
        private const val MAX_STORED_BYTES = 8L * 1024 * 1024
        private const val CIQ_CONTENT_TYPE_URL_ENCODED = "0"
        private const val CIQ_CONTENT_TYPE_JSON = "1"
        private const val MIME_URL_ENCODED = "application/x-www-form-urlencoded"
        private const val MIME_JSON = "application/json"

        private fun gzip(b: ByteArray): ByteArray {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(b) }
            return bos.toByteArray()
        }

        /** The URL without query and fragment, for logs (the network log never stores queries). */
        private fun safeUrl(url: String): String = url.substringBefore('?').substringBefore('#')
    }
}
