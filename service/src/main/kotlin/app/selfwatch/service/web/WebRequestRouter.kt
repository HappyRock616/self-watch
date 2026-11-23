/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.web

import app.selfwatch.model.log.SwLog
import app.selfwatch.model.net.Http
import app.selfwatch.model.net.HttpRequest
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.service.device.WatchLink
import app.selfwatch.service.device.WebRequestHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Status values of [WatchCommand.WebResponse], the convention shared with the garmin driver
 * (`garmin.features.WebRequestHandler.webErrorStatus`):
 * - 100–599: the real HTTP status.
 * - [NETWORK_ERROR] (0): no answer from the network (offline, connection failed); the watch app hears
 *   "network request timed out".
 * - `-n`: the Connect IQ `Communications` error `n` itself (for example [TIMED_OUT] = -300 → 300); the
 *   driver passes it to the watch as the protocol's `ResponseStatus n`.
 * - [FAILED] (-1, any other negative value): failed or blocked; the watch app hears UNKNOWN.
 *
 * Ref: Gadgetbridge 160beab41 GBProtobufGenerator/src/main/proto/garmin/gdi_http.proto
 * (ConnectIQHTTPResponse.ResponseStatus values)
 */
public object WebStatus {
    /** No answer from the network (offline, I/O error); the watch hears "timed out". */
    public const val NETWORK_ERROR: Int = 0

    /** Failed for another reason (too many requests at once); the watch hears UNKNOWN. */
    public const val FAILED: Int = -1

    /** Blocked by the firewall or by the Garmin-server block; the watch hears UNKNOWN. */
    public const val BLOCKED: Int = FAILED

    /** The request's headers (or URL) were not acceptable. */
    public const val BAD_REQUEST_HEADERS: Int = -200

    /** The request's body was not acceptable (too large). */
    public const val BAD_REQUEST_BODY: Int = -201

    /** The method is not supported. */
    public const val BAD_METHOD: Int = -202

    /** No answer in time. */
    public const val TIMED_OUT: Int = -300

    /** The response could not be read. */
    public const val BAD_RESPONSE: Int = -400

    /** The response is larger than the watch accepts. */
    public const val TOO_LARGE: Int = -402

    /** Plain `http://` to anything but this phone. */
    public const val INSECURE: Int = -1001
}

/** Where a watch-app request goes. */
public sealed interface WebRoute {
    /** To an app on this phone (`127.0.0.1` / `localhost`), feature [NetFeature.LOCAL_PHONE_APP]. */
    public data object LocalPhoneApp : WebRoute

    /** To the internet, feature [NetFeature.WATCH_APP_WEB] (firewall off by default). */
    public data object Internet : WebRoute

    /** The watch's GPS ephemeris download, feature [NetFeature.GPS_ASSIST] (firewall off by default). */
    public data object GpsAssist : WebRoute

    /** Not sent; answered at once with [status]. */
    public data class Refused(val status: Int) : WebRoute
}

/**
 * Web requests from Connect IQ apps (B25). Requests for `127.0.0.1` or `localhost` go to the app
 * on this phone through `net` with [NetFeature.LOCAL_PHONE_APP] (logged, never counted as internet);
 * everything else uses [NetFeature.WATCH_APP_WEB]. Each request runs in [scope] (so the watch's event
 * stream is not held up), at most [maxConcurrent] at a time, and is answered with
 * [WatchCommand.WebResponse]: response bodies over [maxResponseBytes] or slower than [timeoutMs] get
 * an error status ([WebStatus]).
 *
 * **Garmin servers are never contacted** (`*.garmin.com`, `*.garmin.cn`, `*.dciwx.com`): such requests
 * are answered [WebStatus.BLOCKED] and logged. The one exception is the watch's own GPS ephemeris
 * download, which the garmin driver marks with the header [EPHEMERIS_HEADER]: it goes out with
 * [NetFeature.GPS_ASSIST] (firewall off by default) and the marker header is removed first.
 */
public class WebRequestRouter(
    private val http: Http,
    private val scope: CoroutineScope,
    private val maxResponseBytes: Int = MAX_RESPONSE_BYTES,
    private val maxRequestBytes: Int = MAX_REQUEST_BYTES,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val maxConcurrent: Int = MAX_CONCURRENT,
) : WebRequestHandler {
    private val pending = HashMap<Long, Job>()

    override suspend fun onWebRequest(from: WatchLink, event: WatchEvent.WebRequest) {
        val key = (from.deviceId shl 32) or (event.requestId.toLong() and 0xFFFFFFFFL)
        val busy = synchronized(pending) {
            pending.values.removeAll { !it.isActive }
            pending.size >= maxConcurrent
        }
        if (busy) {
            SwLog.i(TAG, "too many web requests at once")
            from.send(error(event.requestId, WebStatus.FAILED))
            return
        }
        val job = scope.launch {
            try {
                from.send(answer(event))
            } finally {
                synchronized(pending) { pending.remove(key) }
            }
        }
        synchronized(pending) { pending.put(key, job)?.cancel() }
    }

    /** The response to [req] (does the request). */
    public suspend fun answer(req: WatchEvent.WebRequest): WatchCommand.WebResponse {
        val method = req.method.uppercase(Locale.ROOT)
        if (method !in METHODS) return error(req.requestId, WebStatus.BAD_METHOD)
        if ((req.body?.size ?: 0) > maxRequestBytes) return error(req.requestId, WebStatus.BAD_REQUEST_BODY)
        if (req.headers.any { (k, v) -> k.isBlank() || k.any { it == '\r' || it == '\n' } || v.any { it == '\r' || it == '\n' } }) {
            return error(req.requestId, WebStatus.BAD_REQUEST_HEADERS)
        }
        val ephemeris = req.headers.any { (k, v) -> k.equals(EPHEMERIS_HEADER, ignoreCase = true) && v == "1" }
        val feature = when (val r = route(req.url, ephemeris)) {
            is WebRoute.Refused -> {
                if (r.status == WebStatus.BLOCKED) SwLog.i(TAG, "blocked a watch-app request to a Garmin server: ${safeHost(req.url)}")
                return error(req.requestId, r.status)
            }
            WebRoute.LocalPhoneApp -> NetFeature.LOCAL_PHONE_APP
            WebRoute.Internet -> NetFeature.WATCH_APP_WEB
            WebRoute.GpsAssist -> NetFeature.GPS_ASSIST
        }
        val httpReq = HttpRequest(
            feature = feature,
            method = method,
            url = req.url,
            headers = req.headers.filterKeys { !it.equals(EPHEMERIS_HEADER, ignoreCase = true) },
            body = req.body,
            timeoutMs = timeoutMs,
            maxBytes = maxResponseBytes.toLong(),
        )
        val result = withTimeoutOrNull(timeoutMs.toLong() + TIMEOUT_SLACK_MS) { http.send(httpReq) }
            ?: return error(req.requestId, WebStatus.TIMED_OUT)
        return when (result) {
            is Outcome.Fail -> {
                SwLog.i(TAG, "web request failed (${feature.name}): ${result.error}")
                error(req.requestId, statusFor(result.error))
            }
            is Outcome.Ok -> {
                val r = result.value
                if (r.body.size > maxResponseBytes) {
                    error(req.requestId, WebStatus.TOO_LARGE)
                } else {
                    val type = r.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
                    WatchCommand.WebResponse(req.requestId, r.status, r.body, type)
                }
            }
        }
    }

    private fun error(id: Int, status: Int) = WatchCommand.WebResponse(id, status, ByteArray(0), null)

    /** Limits and the routing rule. */
    public companion object {
        /** Largest response body passed to the watch (bytes). */
        public const val MAX_RESPONSE_BYTES: Int = 64 * 1024

        /** Largest request body accepted from the watch (bytes). */
        public const val MAX_REQUEST_BYTES: Int = 64 * 1024

        /** Time limit per request (ms). */
        public const val TIMEOUT_MS: Int = 30_000

        /** Requests running at once. */
        public const val MAX_CONCURRENT: Int = 4

        /** Header the garmin driver adds to the watch's GPS ephemeris requests (value "1"); never sent out. */
        public const val EPHEMERIS_HEADER: String = "X-SelfWatch-Ephemeris"

        /** Garmin server domains: these hosts and every host below them are blocked. */
        public val BLOCKED_DOMAINS: List<String> = listOf("garmin.com", "garmin.cn", "dciwx.com")

        private const val TIMEOUT_SLACK_MS = 2_000L
        private const val TAG = "WebRequestRouter"
        private val METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD")
        private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost")
        private val EPHEMERIS_HOSTS = setOf("api.gcs.garmin.com", "api.gcs.garmin.cn")
        private const val EPHEMERIS_PATH = "/ephemeris/"

        /**
         * Where [url] goes: this phone for `127.0.0.1`/`localhost` over http(s), the internet over
         * https, Garmin servers nowhere ([WebStatus.BLOCKED]) unless it is a marked GPS ephemeris
         * download ([ephemeris] and an ephemeris URL), which goes to [WebRoute.GpsAssist].
         */
        public fun route(url: String, ephemeris: Boolean = false): WebRoute {
            val uri = try {
                URI(url)
            } catch (e: URISyntaxException) {
                SwLog.d(TAG) { "bad URL: $e" }
                return WebRoute.Refused(WebStatus.BAD_REQUEST_HEADERS)
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".")
            if ((scheme != "http" && scheme != "https") || host.isNullOrEmpty()) return WebRoute.Refused(WebStatus.BAD_REQUEST_HEADERS)
            if (host in LOCAL_HOSTS) return WebRoute.LocalPhoneApp
            if (isGarminHost(host)) {
                val path = uri.rawPath ?: ""
                return if (ephemeris && scheme == "https" && host in EPHEMERIS_HOSTS && path.startsWith(EPHEMERIS_PATH)) {
                    WebRoute.GpsAssist
                } else {
                    WebRoute.Refused(WebStatus.BLOCKED)
                }
            }
            return if (scheme == "https") WebRoute.Internet else WebRoute.Refused(WebStatus.INSECURE)
        }

        /** True for a Garmin server host ([BLOCKED_DOMAINS] or below); [host] is lower case. */
        public fun isGarminHost(host: String): Boolean {
            val h = host.lowercase(Locale.ROOT).removeSuffix(".")
            return BLOCKED_DOMAINS.any { h == it || h.endsWith(".$it") }
        }

        /** The [WebStatus] for a failed request. */
        public fun statusFor(e: SwError): Int = when (e) {
            is SwError.Timeout -> WebStatus.TIMED_OUT
            is SwError.Protocol -> if (e.detail.startsWith("response larger")) WebStatus.TOO_LARGE else WebStatus.BAD_RESPONSE
            is SwError.BadInput -> WebStatus.BAD_REQUEST_HEADERS
            is SwError.Blocked -> WebStatus.BLOCKED
            SwError.Offline, SwError.WaitingForWifi, is SwError.Io, is SwError.NotConnected -> WebStatus.NETWORK_ERROR
            is SwError.RateLimited -> WebStatus.FAILED
            is SwError.Unsupported, is SwError.PermissionMissing -> WebStatus.FAILED
        }

        private fun safeHost(url: String): String = try {
            URI(url).host ?: "?"
        } catch (e: URISyntaxException) {
            "?"
        }
    }
}
