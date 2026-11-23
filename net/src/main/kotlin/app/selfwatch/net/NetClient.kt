/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.net

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.selfwatch.db.dao.NetLogEntry
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.net.Http
import app.selfwatch.model.net.HttpRequest
import app.selfwatch.model.net.HttpResponse
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.URLConnection
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's only HTTP client (WP-08). Every request:
 * 1. is checked against the [Firewall] (blocked: logged as [NetLogEntry.BLOCKED], `SwError.Blocked`);
 * 2. needs a connected network, except [NetFeature.LOCAL_PHONE_APP] (offline: logged as
 *    [NetLogEntry.OFFLINE], `SwError.Offline`); [NetFeature.FOOD_DATA] and [NetFeature.RECIPE_BOOK]
 *    also wait for Wi-Fi or another unmetered network (`SwError.WaitingForWifi` when connected but metered);
 * 3. must be HTTPS, except `http://127.0.0.1` and `http://localhost`, which only
 *    [NetFeature.LOCAL_PHONE_APP] may use (and that feature may use nothing else);
 * 4. follows at most [MAX_REDIRECTS] redirects, each re-checked by the same rules;
 * 5. sends `User-Agent: SelfWatch/<versionName>` (plus a per-feature suffix, see [userAgentSuffix];
 *    Open Food Facts gets `SelfWatch/<versionName> (open source; +<project URL>)`), no cookies, no cache;
 * 6. fails when the body passes `req.maxBytes`;
 * 7. is written to the [NetworkLog] with host, method, body bytes and status, never the path or query.
 *
 * Responses with any HTTP status come back as `Outcome.Ok`; callers check [HttpResponse.status].
 * With the INTERNET permission removed (GrapheneOS) requests fail with `SwError.Offline`.
 * Cancelling the calling coroutine disconnects the socket at once.
 */
public class NetClient internal constructor(
    ctx: Context,
    private val firewall: Firewall,
    private val log: NetworkLog,
    private val connectivity: Connectivity,
    private val io: CoroutineDispatcher,
    /** Test only: lets any feature use plain http to 127.0.0.1 / localhost (the in-test server). */
    private val allowCleartextForTests: Boolean,
    private val open: (URL) -> URLConnection = { it.openConnection() },
) : Http {

    /** The client for the app: Android connectivity, `Dispatchers.IO`, production rules. */
    public constructor(
        ctx: Context,
        firewall: Firewall,
        log: NetworkLog,
        connectivity: Connectivity = AndroidConnectivity(ctx),
    ) : this(ctx, firewall, log, connectivity, Dispatchers.IO, allowCleartextForTests = false)

    /** `SelfWatch/<versionName>`: no device or user details. */
    internal val userAgent: String = "SelfWatch/" + versionName(ctx)

    /** The User-Agent sent for [feature]: [userAgent] plus that feature's [userAgentSuffix], if any. */
    internal fun userAgentFor(feature: NetFeature): String = userAgentSuffix(feature)?.let { "$userAgent $it" } ?: userAgent

    override suspend fun send(req: HttpRequest): Outcome<HttpResponse> =
        exchange(req, emptyMap()) { r -> Outcome.Ok(HttpResponse(r.status, r.headers(), r.readAll(req.maxBytes))) }

    /**
     * Downloads to [to] through `[to].tmp`, renamed only when complete (and as long as
     * `Content-Length` said, when given). If a `.tmp` from an earlier attempt exists, asks for the
     * rest with `Range`; a `206` answer appends, a `200` starts over. A partial `.tmp` is kept after a
     * failure only when the server accepts ranges. [onProgress] gets (bytes in file, total or null)
     * on the IO thread. Callers check the file's SHA-256 themselves.
     */
    override suspend fun download(req: HttpRequest, to: File, onProgress: (Long, Long?) -> Unit): Outcome<Long> {
        val tmp = File(to.path + TMP_SUFFIX)
        val have = withContext(io) { if (tmp.isFile) tmp.length() else 0L }
        val extra = buildMap {
            put("Accept-Encoding", "identity") // byte offsets must match the file
            if (have > 0) put("Range", "bytes=$have-")
        }
        val written = exchange(req, extra) { r -> r.writeTo(tmp, have, req.maxBytes, onProgress) }
        if (written !is Outcome.Ok) return written
        return withContext(io) {
            if (to.exists() && !to.delete()) {
                Outcome.Fail(SwError.Io("cannot replace ${to.name}"))
            } else if (!tmp.renameTo(to)) {
                Outcome.Fail(SwError.Io("cannot rename ${tmp.name}"))
            } else {
                written
            }
        }
    }

    /** One logged request with its redirects; [read] consumes the final response on the IO thread. */
    private suspend fun <T> exchange(
        req: HttpRequest,
        extraHeaders: Map<String, String>,
        read: (Response) -> Outcome<T>,
    ): Outcome<T> {
        val method = req.method.uppercase()
        if (method !in METHODS) return Outcome.Fail(SwError.BadInput("HTTP method $method not supported"))
        val first = when (val t = target(req.feature, req.url)) {
            is Outcome.Ok -> t.value
            is Outcome.Fail -> return t
        }
        if (!firewall.allowed(req.feature)) {
            log.record(req.feature, first.host, method, 0, 0, NetLogEntry.BLOCKED)
            return Outcome.Fail(SwError.Blocked(req.feature))
        }
        if (req.feature != NetFeature.LOCAL_PHONE_APP) {
            val state = connectivity.state()
            val waitForWifi = req.feature in WIFI_ONLY && !state.unmetered
            if (!state.connected || waitForWifi) {
                if (state.connected) SwLog.i(TAG, "${req.feature.name}: waiting for Wi-Fi")
                log.record(req.feature, first.host, method, 0, 0, NetLogEntry.OFFLINE)
                return Outcome.Fail(if (state.connected) SwError.WaitingForWifi else SwError.Offline)
            }
        }
        val hops = mutableListOf<Hop>()
        val current = AtomicReference<HttpURLConnection?>()
        try {
            return coroutineScope {
                // Cancelling the caller cancels this guard, which closes the socket under the IO thread.
                val guard = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        current.get()?.disconnect()
                    }
                }
                try {
                    withContext(io) { runHops(req, method, first, extraHeaders, hops, current, coroutineContext.job, read) }
                } finally {
                    guard.cancel()
                }
            }
        } finally {
            withContext(NonCancellable) {
                for (h in hops) log.record(req.feature, h.host, h.method, h.up, h.down, h.result)
            }
        }
    }

    private fun <T> runHops(
        req: HttpRequest,
        firstMethod: String,
        first: Target,
        extraHeaders: Map<String, String>,
        hops: MutableList<Hop>,
        current: AtomicReference<HttpURLConnection?>,
        job: Job,
        read: (Response) -> Outcome<T>,
    ): Outcome<T> {
        var target = first
        var method = firstMethod
        var body = req.body
        var redirects = 0
        while (true) {
            val hop = Hop(target.host, method)
            hops += hop
            try {
                val conn = open(target.uri.toURL()) as? HttpURLConnection
                    ?: return Outcome.Fail(SwError.BadInput("not an HTTP URL"))
                current.set(conn)
                try {
                    if (!job.isActive) throw CancellationException("request cancelled")
                    configure(conn, req, method, target, first, extraHeaders)
                    val out = body
                    if (out != null && method in BODY_METHODS) {
                        conn.doOutput = true
                        conn.setFixedLengthStreamingMode(out.size)
                        conn.outputStream.use { it.write(out) }
                        hop.up = out.size.toLong()
                    }
                    val status = conn.responseCode
                    hop.result = status
                    if (status !in REDIRECTS) return read(Response(conn, status, method, hop))
                    hop.down += drain(conn, status)
                    if (redirects == MAX_REDIRECTS) {
                        return Outcome.Fail(SwError.Protocol("more than $MAX_REDIRECTS redirects"))
                    }
                    val location = conn.getHeaderField("Location")
                        ?: return Outcome.Fail(SwError.Protocol("redirect without Location"))
                    val next = resolve(target, location)
                    target = when (val t = next?.let { target(req.feature, it) }) {
                        is Outcome.Ok -> t.value
                        else -> return Outcome.Fail(SwError.Protocol("redirect to a URL that is not allowed"))
                    }
                    if (status == HTTP_SEE_OTHER || (status in MOVED && method == "POST")) {
                        method = "GET"
                        body = null
                    }
                    redirects++
                } finally {
                    conn.disconnect()
                    current.set(null)
                }
            } catch (e: TooLarge) {
                hop.result = NetLogEntry.ERROR
                return Outcome.Fail(SwError.Protocol("response larger than ${e.limit} bytes"))
            } catch (e: SocketTimeoutException) {
                hop.result = NetLogEntry.ERROR
                return Outcome.Fail(SwError.Timeout("HTTP ${hop.host}"))
            } catch (e: UnknownHostException) {
                hop.result = NetLogEntry.OFFLINE
                return Outcome.Fail(SwError.Offline)
            } catch (e: SecurityException) {
                // No INTERNET permission (GrapheneOS can remove it).
                SwLog.w(TAG, "no internet permission")
                hop.result = NetLogEntry.OFFLINE
                return Outcome.Fail(SwError.Offline)
            } catch (e: IOException) {
                // Messages can hold the URL, so only the class name is kept.
                hop.result = NetLogEntry.ERROR
                SwLog.w(TAG, "${req.feature.name} request failed: ${e.javaClass.simpleName}")
                return Outcome.Fail(SwError.Io("network error (${e.javaClass.simpleName})"))
            }
        }
    }

    private fun configure(
        conn: HttpURLConnection,
        req: HttpRequest,
        method: String,
        target: Target,
        first: Target,
        extraHeaders: Map<String, String>,
    ) {
        conn.connectTimeout = req.timeoutMs
        conn.readTimeout = req.timeoutMs
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.requestMethod = method
        for ((name, value) in req.headers) {
            val lower = name.lowercase()
            if (lower in DROPPED_HEADERS) continue
            if (lower == "authorization" && target.host != first.host) continue
            conn.setRequestProperty(name, value)
        }
        for ((name, value) in extraHeaders) conn.setRequestProperty(name, value)
        conn.setRequestProperty("User-Agent", userAgentFor(req.feature))
    }

    /**
     * Checks [url] for [feature]: http(s) only, no user info, and the cleartext / local rules in the
     * class comment. Redirect targets pass the same check, so they stay HTTPS (or local).
     */
    private fun target(feature: NetFeature, url: String): Outcome<Target> {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return Outcome.Fail(SwError.BadInput("malformed URL"))
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (host.isNullOrEmpty() || uri.rawUserInfo != null || (scheme != "https" && scheme != "http")) {
            return Outcome.Fail(SwError.BadInput("only http(s) URLs with a host are allowed"))
        }
        val local = host in LOCAL_HOSTS
        if (feature == NetFeature.LOCAL_PHONE_APP) {
            if (!local) return Outcome.Fail(SwError.BadInput("LOCAL_PHONE_APP may only reach 127.0.0.1"))
        } else if (scheme == "http" && !(allowCleartextForTests && local)) {
            return Outcome.Fail(SwError.BadInput("HTTPS required"))
        }
        return Outcome.Ok(Target(uri, host))
    }

    private fun resolve(from: Target, location: String): String? = try {
        from.uri.resolve(location.trim()).toString()
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Reads and discards up to [DRAIN_MAX] bytes of a redirect body; returns how many. */
    private fun drain(conn: HttpURLConnection, status: Int): Long {
        val stream = (if (status >= HTTP_BAD_REQUEST) conn.errorStream else conn.inputStream) ?: return 0
        var total = 0L
        val buf = ByteArray(BUFFER)
        stream.use {
            while (total < DRAIN_MAX) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
            }
        }
        return total
    }

    /** A checked URL. */
    private class Target(val uri: URI, val host: String)

    /** One HTTP exchange for the log. */
    private class Hop(val host: String, val method: String) {
        var up: Long = 0
        var down: Long = 0
        var result: Int = NetLogEntry.ERROR
    }

    private class TooLarge(val limit: Long) : IOException("response too large")

    /** The final (non-redirect) response; counts the body bytes it reads into [hop]. */
    private class Response(val conn: HttpURLConnection, val status: Int, val method: String, val hop: Hop) {
        // The status line comes back under a null key.
        fun headers(): Map<String, List<String>> = buildMap {
            for ((k, v) in conn.headerFields) {
                val name: String? = k
                if (name != null) put(name, v.toList())
            }
        }

        fun header(name: String): String? = conn.getHeaderField(name)

        fun stream(): InputStream {
            if (method == "HEAD") return ByteArrayInputStream(ByteArray(0))
            val raw = (if (status >= HTTP_BAD_REQUEST) conn.errorStream else conn.inputStream)
                ?: return ByteArrayInputStream(ByteArray(0))
            return object : InputStream() {
                override fun read(): Int = raw.read().also { if (it >= 0) hop.down++ }

                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    raw.read(b, off, len).also { if (it > 0) hop.down += it }

                override fun close() = raw.close()
            }
        }

        fun readAll(maxBytes: Long): ByteArray {
            if (conn.contentLengthLong > maxBytes) throw TooLarge(maxBytes)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(BUFFER)
            stream().use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw TooLarge(maxBytes)
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        }

        fun writeTo(tmp: File, have: Long, maxBytes: Long, onProgress: (Long, Long?) -> Unit): Outcome<Long> {
            val append = status == HTTP_PARTIAL && have > 0 && rangeStart(header("Content-Range")) == have
            if (status == HTTP_PARTIAL && !append) {
                tmp.delete()
                return Outcome.Fail(SwError.Protocol("unexpected partial response"))
            }
            if (status != HTTP_OK && !append) return Outcome.Fail(SwError.Protocol("HTTP $status"))
            val offset = if (append) have else 0L
            val length = conn.contentLengthLong
            val total = if (length >= 0) offset + length else null
            val resumable = append || header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            if (total != null && total > maxBytes) {
                tmp.delete()
                throw TooLarge(maxBytes)
            }
            var written = offset
            try {
                FileOutputStream(tmp, append).use { out ->
                    stream().use { input ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (written + n > maxBytes) throw TooLarge(maxBytes)
                            out.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                    }
                }
            } catch (e: TooLarge) {
                tmp.delete()
                throw e
            } catch (e: IOException) {
                if (!resumable) tmp.delete()
                throw e
            }
            if (total != null && written != total) {
                if (!resumable) tmp.delete()
                return Outcome.Fail(SwError.Io("download ended early"))
            }
            return Outcome.Ok(written)
        }

        /** The first byte position of `Content-Range: bytes <start>-<end>/<size>`, or null. */
        private fun rangeStart(value: String?): Long? =
            value?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-')?.toLongOrNull()
    }

    /** Limits and rules. */
    public companion object {
        /** Redirects followed per request. */
        public const val MAX_REDIRECTS: Int = 3

        /** Suffix of the partial file [download] writes. */
        public const val TMP_SUFFIX: String = ".tmp"

        private const val TAG: String = "NetClient"
        private const val BUFFER: Int = 8192
        private const val DRAIN_MAX: Long = 64L * 1024
        private const val HTTP_OK: Int = 200
        private const val HTTP_PARTIAL: Int = 206
        private const val HTTP_SEE_OTHER: Int = 303
        private const val HTTP_BAD_REQUEST: Int = 400
        private val REDIRECTS: Set<Int> = setOf(301, 302, 303, 307, 308)
        private val MOVED: Set<Int> = setOf(301, 302)
        private val METHODS: Set<String> = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS")
        private val BODY_METHODS: Set<String> = setOf("POST", "PUT", "DELETE")
        private val LOCAL_HOSTS: Set<String> = setOf("127.0.0.1", "localhost")
        private val WIFI_ONLY: Set<NetFeature> = setOf(NetFeature.FOOD_DATA, NetFeature.RECIPE_BOOK)

        /**
         * Project link in descriptive User-Agents. The public repository is not chosen yet (repo TBD:
         * append its path here). Header values must stay ASCII.
         */
        internal const val PROJECT_URL: String = "https://github.com/"

        /**
         * The per-feature User-Agent policy: text appended after `SelfWatch/<version>`, or null for
         * none. Never device, account or user details. Open Food Facts asks API users for a
         * descriptive User-Agent that says what the app is and how to reach its makers.
         */
        internal fun userAgentSuffix(feature: NetFeature): String? = when (feature) {
            NetFeature.OPEN_FOOD_FACTS -> "(open source; +$PROJECT_URL)"
            NetFeature.FOOD_DATA, NetFeature.WATCH_APP_WEB, NetFeature.GPS_ASSIST, NetFeature.RECIPE_BOOK,
            NetFeature.LOCAL_PHONE_APP,
            -> null
        }

        // Set by this client only; cookies are never sent.
        private val DROPPED_HEADERS: Set<String> = setOf("user-agent", "cookie", "host", "content-length")

        /** The app's versionName, reduced to safe characters ("0" when unknown). */
        internal fun versionName(ctx: Context): String {
            val name = try {
                val pm = ctx.packageManager
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(ctx.packageName, 0)
                }
                info.versionName
            } catch (e: PackageManager.NameNotFoundException) {
                SwLog.w(TAG, "own package info missing", e)
                null
            }
            val clean = name.orEmpty().filter { it.isLetterOrDigit() || it in "._-+" }.take(VERSION_MAX)
            return clean.ifEmpty { "0" }
        }

        private const val VERSION_MAX: Int = 32
    }
}
