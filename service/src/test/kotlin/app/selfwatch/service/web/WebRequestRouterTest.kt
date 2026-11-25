/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.web

import app.selfwatch.model.fake.FakeHttp
import app.selfwatch.model.net.HttpResponse
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.service.device.WatchLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B25: routing, limits and error statuses. */
class WebRequestRouterTest {
    private val http = FakeHttp()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val router = WebRequestRouter(http, scope, maxResponseBytes = 16)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun req(url: String, method: String = "GET", body: ByteArray? = null, headers: Map<String, String> = emptyMap()) =
        WatchEvent.WebRequest(9, null, url, method, headers, body)

    @Test
    fun routes() {
        assertEquals(WebRoute.LocalPhoneApp, WebRequestRouter.route("http://127.0.0.1:8080/x"))
        assertEquals(WebRoute.LocalPhoneApp, WebRequestRouter.route("http://LOCALHOST:22222/api?q=1"))
        assertEquals(WebRoute.Internet, WebRequestRouter.route("https://api.example.org/v1"))
        assertEquals(WebRoute.Refused(WebStatus.INSECURE), WebRequestRouter.route("http://api.example.org/v1"))
        assertEquals(WebRoute.Refused(WebStatus.BAD_REQUEST_HEADERS), WebRequestRouter.route("ftp://127.0.0.1/"))
        assertEquals(WebRoute.Refused(WebStatus.BAD_REQUEST_HEADERS), WebRequestRouter.route("not a url"))
        // A look-alike host is the internet, not this phone.
        assertEquals(WebRoute.Refused(WebStatus.INSECURE), WebRequestRouter.route("http://127.0.0.1.evil.example/"))
    }

    @Test
    fun localGoesThroughLocalPhoneAppAndInternetThroughWatchAppWeb() = runBlocking {
        http.on("http://127.0.0.1", Outcome.Ok(HttpResponse(200, mapOf("content-type" to listOf("application/json")), "{}".toByteArray())))
        val local = router.answer(req("http://127.0.0.1:8080/x"))
        assertEquals(WatchCommand.WebResponse(9, 200, "{}".toByteArray(), "application/json"), local)
        http.enqueue(201, "ok")
        val net = router.answer(req("https://api.example.org/v1", "post", "a".toByteArray(), mapOf("X-A" to "1")))
        assertEquals(201, net.status)
        val sent = http.requests()
        assertEquals(NetFeature.LOCAL_PHONE_APP, sent[0].feature)
        assertEquals(NetFeature.WATCH_APP_WEB, sent[1].feature)
        assertEquals("POST", sent[1].method)
        assertEquals(mapOf("X-A" to "1"), sent[1].headers)
        assertEquals(16L, sent[1].maxBytes)
    }

    @Test
    fun errorsBecomeStatuses() = runBlocking {
        http.enqueue(200, "this body is longer than sixteen bytes")
        assertEquals(WebStatus.TOO_LARGE, router.answer(req("https://a.example/")).status)
        http.blocked.add(NetFeature.WATCH_APP_WEB)
        assertEquals(WebStatus.BLOCKED, router.answer(req("https://a.example/")).status)
        http.blocked.clear()
        http.enqueue(Outcome.Fail(SwError.Timeout("HTTP a.example")))
        assertEquals(WebStatus.TIMED_OUT, router.answer(req("https://a.example/")).status)
        http.enqueue(Outcome.Fail(SwError.Protocol("response larger than 16 bytes")))
        assertEquals(WebStatus.TOO_LARGE, router.answer(req("https://a.example/")).status)
        http.enqueue(Outcome.Fail(SwError.Offline))
        assertEquals(WebStatus.NETWORK_ERROR, router.answer(req("https://a.example/")).status)
        assertEquals(WebStatus.BAD_METHOD, router.answer(req("https://a.example/", "TRACE")).status)
        assertEquals(WebStatus.INSECURE, router.answer(req("http://a.example/")).status)
        assertEquals(WebStatus.BAD_REQUEST_HEADERS, router.answer(req("https://a.example/", headers = mapOf("A" to "b\r\nc"))).status)
        val big = WebRequestRouter(http, scope, maxRequestBytes = 4)
        assertEquals(WebStatus.BAD_REQUEST_BODY, big.answer(req("https://a.example/", "POST", ByteArray(5))).status)
        // Refused requests never reach the network.
        assertEquals(5, http.requests().size)
    }

    @Test
    fun garminServersAreBlocked() = runBlocking {
        for (u in listOf(
            "https://connectapi.garmin.com/x", "https://garmin.com/", "https://API.GARMIN.CN/a", "https://dciwx.com/w",
            "https://a.b.dciwx.com/w", "http://services.garmin.com./", "https://api.gcs.garmin.com/ephemeris/x",
        )) {
            assertEquals(u, WebRoute.Refused(WebStatus.BLOCKED), WebRequestRouter.route(u))
            assertEquals(u, WebStatus.BLOCKED, router.answer(req(u)).status)
        }
        // Look-alikes are ordinary hosts.
        assertEquals(WebRoute.Internet, WebRequestRouter.route("https://notgarmin.com/"))
        assertEquals(WebRoute.Internet, WebRequestRouter.route("https://garmin.com.example.org/"))
        assertEquals(0, http.requests().size)
    }

    @Test
    fun markedEphemerisFollowsGpsAssist() = runBlocking {
        val marker = mapOf(WebRequestRouter.EPHEMERIS_HEADER to "1", "Accept" to "*/*")
        val url = "https://api.gcs.garmin.com/ephemeris/cpe/sony/lle?coverage=WEEKS_1"
        assertEquals(WebRoute.GpsAssist, WebRequestRouter.route(url, ephemeris = true))
        // The marker only opens the ephemeris path, nothing else on Garmin's servers.
        assertEquals(WebRoute.Refused(WebStatus.BLOCKED), WebRequestRouter.route("https://connect.garmin.com/ephemeris/x", ephemeris = true))
        assertEquals(WebRoute.Refused(WebStatus.BLOCKED), WebRequestRouter.route("https://api.gcs.garmin.com/other", ephemeris = true))
        // GPS_ASSIST turned off: the firewall blocks it and the watch hears "timed out", as with
        // Gadgetbridge when no AGPS file is set (an outright failure may keep the watch asking).
        http.blocked.add(NetFeature.GPS_ASSIST)
        assertEquals(WebStatus.NETWORK_ERROR, router.answer(req(url, headers = marker)).status)
        http.blocked.clear()
        http.enqueue(200, "eph")
        assertEquals(200, router.answer(req(url, headers = marker)).status)
        val sent = http.requests().last()
        assertEquals(NetFeature.GPS_ASSIST, sent.feature)
        assertEquals(mapOf("Accept" to "*/*"), sent.headers)
    }

    @Test
    fun gpsAssistIsCachedAndRevalidatedNeverRefetchedWhileFresh() = runBlocking {
        var now = 1_000_000L
        val store = MemoryEphemerisStore()
        val r = WebRequestRouter(http, scope, ephemerisStore = store, nowSec = { now })
        val marker = mapOf(WebRequestRouter.EPHEMERIS_HEADER to "1")
        val url = "https://api.gcs.garmin.com/ephemeris/cpe/sony/lle?coverage=WEEKS_1"
        http.enqueue(200, "eph1", mapOf("ETag" to listOf("\"v1\""), "Last-Modified" to listOf("Mon, 23 Nov 2026 00:00:00 GMT")))
        assertEquals("eph1", String(r.answer(req(url, headers = marker)).body))
        assertEquals(1, http.requests().size)
        assertTrue("first fetch is unconditional", http.requests()[0].headers.keys.none { it.startsWith("If-") })

        // Fresh: answered from the phone, no request at all.
        now += 3_600
        assertEquals("eph1", String(r.answer(req(url, headers = marker)).body))
        assertEquals(1, http.requests().size)

        // Stale: a conditional request; 304 answers with the cached file.
        now += WebRequestRouter.EPHEMERIS_FRESH_SEC
        http.enqueue(304, "")
        val revalidated = r.answer(req(url, headers = marker))
        assertEquals(200, revalidated.status)
        assertEquals("eph1", String(revalidated.body))
        val cond = http.requests().last().headers
        assertEquals("\"v1\"", cond["If-None-Match"])
        assertEquals("Mon, 23 Nov 2026 00:00:00 GMT", cond["If-Modified-Since"])
        assertEquals(2, http.requests().size)

        // The 304 renewed the freshness: the next ask costs nothing.
        now += 60
        r.answer(req(url, headers = marker))
        assertEquals(2, http.requests().size)

        // Changed on the server: the new file replaces the old one.
        now += WebRequestRouter.EPHEMERIS_FRESH_SEC
        http.enqueue(200, "eph2", mapOf("ETag" to listOf("\"v2\"")))
        assertEquals("eph2", String(r.answer(req(url, headers = marker)).body))
        assertEquals("\"v2\"", store.load(url)?.etag)

        // Other watch-app requests are never cached.
        http.enqueue(200, "a")
        http.enqueue(200, "b")
        assertEquals("a", String(r.answer(req("https://api.example.org/x")).body))
        assertEquals("b", String(r.answer(req("https://api.example.org/x")).body))
    }

    @Test
    fun fileEphemerisStoreRoundTrips() {
        val dir = kotlin.io.path.createTempDirectory("eph").toFile()
        try {
            val s = FileEphemerisStore(dir)
            val e = CachedEphemeris("x".toByteArray(), "application/octet-stream", "\"e\"", null, 42L)
            assertEquals(null, s.load("https://a/1"))
            s.save("https://a/1", e)
            assertEquals(e, FileEphemerisStore(dir).load("https://a/1"))
            assertEquals(null, s.load("https://a/2"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun statusConventionMatchesTheGarminDriver() {
        // garmin.features.WebRequestHandler: 0 = no answer ("timed out"), -n = Connect IQ error n, -1 = UNKNOWN.
        assertEquals(0, WebStatus.NETWORK_ERROR)
        assertEquals(-1, WebStatus.FAILED)
        assertEquals(WebStatus.FAILED, WebStatus.BLOCKED)
        assertEquals(-300, WebStatus.TIMED_OUT)
        assertEquals(-402, WebStatus.TOO_LARGE)
        assertEquals(-1001, WebStatus.INSECURE)
        assertEquals(WebStatus.NETWORK_ERROR, WebRequestRouter.statusFor(SwError.Offline))
        assertEquals(WebStatus.NETWORK_ERROR, WebRequestRouter.statusFor(SwError.WaitingForWifi))
        assertEquals(WebStatus.FAILED, WebRequestRouter.statusFor(SwError.RateLimited(30)))
        assertEquals(WebStatus.NETWORK_ERROR, WebRequestRouter.statusFor(SwError.Io("refused")))
        assertEquals(WebStatus.BLOCKED, WebRequestRouter.statusFor(SwError.Blocked(NetFeature.WATCH_APP_WEB)))
        assertEquals(WebStatus.TIMED_OUT, WebRequestRouter.statusFor(SwError.Timeout("x")))
        for (s in listOf(WebStatus.BAD_REQUEST_HEADERS, WebStatus.BAD_REQUEST_BODY, WebStatus.BAD_METHOD, WebStatus.TIMED_OUT,
            WebStatus.BAD_RESPONSE, WebStatus.TOO_LARGE, WebStatus.INSECURE, WebStatus.FAILED)) {
            assertTrue(s < 0)
        }
    }

    @Test
    fun handlerAnswersThroughTheLink() = runBlocking {
        val answered = CompletableDeferred<WatchCommand>()
        val link = object : WatchLink {
            override val deviceId: Long = 3
            override suspend fun send(cmd: WatchCommand): Outcome<Unit> {
                answered.complete(cmd)
                return Outcome.Ok(Unit)
            }
        }
        http.enqueue(200, "hi")
        router.onWebRequest(link, req("https://a.example/"))
        val r = withTimeout(5_000) { answered.await() } as WatchCommand.WebResponse
        assertEquals(9, r.requestId)
        assertTrue(r.body.contentEquals("hi".toByteArray()))
    }
}
