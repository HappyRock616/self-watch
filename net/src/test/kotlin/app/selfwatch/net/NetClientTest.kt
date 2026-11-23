/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.net

import app.selfwatch.db.dao.NetLogEntry
import app.selfwatch.model.net.HttpRequest
import app.selfwatch.model.net.HttpResponse
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.result.errorOrNull
import app.selfwatch.model.result.valueOrNull
import java.io.File
import java.net.URLConnection
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NetClientTest {
    private val logged = CopyOnWriteArrayList<NetLogEntry>()
    private val log = NetworkLog(
        insert = { logged += it },
        since = { s -> logged.filter { it.tSec >= s } },
        recentRows = { n -> logged.reversed().take(n) },
        pruneOlder = { 0 },
        changes = { emptyFlow() },
        nowSec = { 1_000L },
    )
    private val switches = NetFeature.entries.associateWith { true }.toMutableMap()
    private val firewall = Firewall(read = { switches.getValue(it) }, write = { f, on -> switches[f] = on }, watch = { flowOf(true) })
    private var net = NetState(connected = true, unmetered = true)
    private val servers = mutableListOf<TinyHttpServer>()

    private fun client(cleartext: Boolean = true, open: ((java.net.URL) -> URLConnection)? = null): NetClient =
        NetClient(
            RuntimeEnvironment.getApplication(),
            firewall,
            log,
            { net },
            Dispatchers.IO,
            allowCleartextForTests = cleartext,
            open = open ?: { it.openConnection() },
        )

    private fun server(handler: (TinyRequest, java.io.OutputStream) -> Unit): TinyHttpServer =
        TinyHttpServer(handler).also { servers += it }

    private fun ok(body: String = "hello"): (TinyRequest, java.io.OutputStream) -> Unit =
        { _, out -> TinyHttpServer.respond(out, 200, body.toByteArray()) }

    private fun get(url: String, feature: NetFeature = NetFeature.OPEN_FOOD_FACTS, timeoutMs: Int = 5_000, maxBytes: Long = 1L shl 20) =
        HttpRequest(feature, "GET", url, timeoutMs = timeoutMs, maxBytes = maxBytes)

    private fun send(req: HttpRequest, c: NetClient = client()): Outcome<HttpResponse> = runBlocking { c.send(req) }

    @After
    fun stop() {
        servers.forEach { it.close() }
    }

    @Test
    fun allowedRequestReturnsBodyAndLogs() {
        val s = server(ok())
        val r = send(get(s.url("/x"))).valueOrNull()
        assertEquals(200, r?.status)
        assertEquals("hello", r?.body?.decodeToString())
        val ua = s.requests.single().headers["user-agent"].orEmpty()
        // Open Food Facts gets the descriptive User-Agent; still no device or user details.
        assertEquals(NetClient.userAgentSuffix(NetFeature.OPEN_FOOD_FACTS), ua.substringAfter(" "))
        assertTrue(ua, ua.matches(Regex("""SelfWatch/[A-Za-z0-9._+-]+ \(open source; \+https://github\.com/[!-~]*\)""")))
        assertNull(s.requests.single().headers["cookie"])
        val e = logged.single()
        assertEquals(NetFeature.OPEN_FOOD_FACTS, e.feature)
        assertEquals("127.0.0.1", e.host)
        assertEquals("GET", e.method)
        assertEquals(0L, e.bytesUp)
        assertEquals(5L, e.bytesDown)
        assertEquals(200, e.result)
    }

    @Test
    fun postCountsBytesUpAndCallerCannotSetUserAgentOrCookie() {
        val s = server(ok("done"))
        val req = HttpRequest(
            NetFeature.OPEN_FOOD_FACTS, "POST", s.url("/p"), body = ByteArray(10) { 1 },
            headers = mapOf("User-Agent" to "Phone/123 Pixel", "Cookie" to "a=b", "X-Ok" to "1"),
        )
        assertEquals(200, send(req).valueOrNull()?.status)
        val seen = s.requests.single()
        assertEquals(10, seen.body.size)
        assertTrue(seen.headers.getValue("user-agent").startsWith("SelfWatch/"))
        assertNull(seen.headers["cookie"])
        assertEquals("1", seen.headers["x-ok"])
        assertEquals(10L, logged.single().bytesUp)
        assertEquals(4L, logged.single().bytesDown)
    }

    @Test
    fun errorStatusIsReturnedWithBody() {
        val s = server { _, out -> TinyHttpServer.respond(out, 404, "nope".toByteArray()) }
        val r = send(get(s.url("/missing"))).valueOrNull()
        assertEquals(404, r?.status)
        assertEquals("nope", r?.body?.decodeToString())
        assertEquals(404, logged.single().result)
    }

    @Test
    fun blockedByFirewallIsLoggedAndSendsNothing() {
        val s = server(ok())
        switches[NetFeature.OPEN_FOOD_FACTS] = false
        assertEquals(SwError.Blocked(NetFeature.OPEN_FOOD_FACTS), send(get(s.url("/x"))).errorOrNull())
        assertTrue(s.requests.isEmpty())
        assertEquals(NetLogEntry.BLOCKED, logged.single().result)
        assertEquals("127.0.0.1", logged.single().host)
    }

    @Test
    fun offlineIsLoggedAndSendsNothing() {
        val s = server(ok())
        net = NetState(connected = false, unmetered = false)
        assertEquals(SwError.Offline, send(get(s.url("/x"))).errorOrNull())
        assertTrue(s.requests.isEmpty())
        assertEquals(NetLogEntry.OFFLINE, logged.single().result)
    }

    @Test
    fun wifiOnlyFeaturesWaitForUnmeteredNetwork() {
        val s = server(ok())
        net = NetState(connected = true, unmetered = false)
        assertEquals(SwError.WaitingForWifi, send(get(s.url("/f"), NetFeature.FOOD_DATA)).errorOrNull())
        assertEquals(SwError.WaitingForWifi, send(get(s.url("/r"), NetFeature.RECIPE_BOOK)).errorOrNull())
        assertEquals(200, send(get(s.url("/o"), NetFeature.OPEN_FOOD_FACTS)).valueOrNull()?.status)
        net = NetState(connected = true, unmetered = true)
        assertEquals(200, send(get(s.url("/f"), NetFeature.FOOD_DATA)).valueOrNull()?.status)
        assertEquals(listOf(NetLogEntry.OFFLINE, NetLogEntry.OFFLINE, 200, 200), logged.map { it.result })
    }

    @Test
    fun productionRejectsCleartextExceptLocalPhoneApp() {
        val s = server(ok())
        val prod = client(cleartext = false)
        assertTrue(send(get(s.url("/x"), NetFeature.OPEN_FOOD_FACTS), prod).errorOrNull() is SwError.BadInput)
        assertTrue(send(get("http://example.com/x", NetFeature.WATCH_APP_WEB), prod).errorOrNull() is SwError.BadInput)
        assertTrue(send(get("ftp://example.com/x"), prod).errorOrNull() is SwError.BadInput)
        assertTrue(send(get("https://user:pw@example.com/x"), prod).errorOrNull() is SwError.BadInput)
        assertTrue(s.requests.isEmpty())
        assertTrue(logged.isEmpty())
    }

    @Test
    fun localPhoneAppReaches127001WithoutNetworkAndIsLoggedAsLocal() {
        val s = server(ok("local"))
        net = NetState(connected = false, unmetered = false) // local traffic needs no network
        val r = send(get(s.url("/watch?x=1"), NetFeature.LOCAL_PHONE_APP), client(cleartext = false))
        assertEquals("local", r.valueOrNull()?.body?.decodeToString())
        val e = logged.single()
        assertEquals(NetFeature.LOCAL_PHONE_APP, e.feature)
        assertEquals("127.0.0.1", e.host)
        assertEquals(200, e.result)
    }

    @Test
    fun localPhoneAppCannotLeaveThePhone() {
        val r = send(get("https://example.com/", NetFeature.LOCAL_PHONE_APP), client(cleartext = false))
        assertTrue(r.errorOrNull() is SwError.BadInput)
        val s = server { _, out -> TinyHttpServer.respond(out, 302, headers = mapOf("Location" to "https://example.com/")) }
        val redirected = send(get(s.url("/"), NetFeature.LOCAL_PHONE_APP), client(cleartext = false))
        assertTrue(redirected.errorOrNull() is SwError.Protocol)
        assertEquals(1, s.requests.size)
    }

    @Test
    fun redirectsAreFollowedAndEachHopLogged() {
        val s = server { req, out ->
            when (req.target) {
                "/a" -> TinyHttpServer.respond(out, 301, headers = mapOf("Location" to "/b"))
                "/b" -> TinyHttpServer.respond(out, 307, headers = mapOf("Location" to "/c"))
                else -> TinyHttpServer.respond(out, 200, "end".toByteArray())
            }
        }
        assertEquals("end", send(get(s.url("/a"))).valueOrNull()?.body?.decodeToString())
        assertEquals(listOf("/a", "/b", "/c"), s.requests.map { it.target })
        assertEquals(listOf(301, 307, 200), logged.map { it.result })
    }

    @Test
    fun seeOtherTurnsPostIntoGet() {
        val s = server { req, out ->
            if (req.target == "/post") {
                TinyHttpServer.respond(out, 303, headers = mapOf("Location" to "/result"))
            } else {
                TinyHttpServer.respond(out, 200, req.method.toByteArray())
            }
        }
        val r = send(HttpRequest(NetFeature.OPEN_FOOD_FACTS, "POST", s.url("/post"), body = "q".toByteArray()))
        assertEquals("GET", r.valueOrNull()?.body?.decodeToString())
        assertEquals(0, s.requests[1].body.size)
    }

    @Test
    fun redirectLimitIsThree() {
        val s = server { req, out ->
            TinyHttpServer.respond(out, 302, headers = mapOf("Location" to req.target + "x"))
        }
        val r = send(get(s.url("/r")))
        assertTrue(r.errorOrNull() is SwError.Protocol)
        assertEquals(NetClient.MAX_REDIRECTS + 1, s.requests.size)
        assertEquals(4, logged.size)
    }

    @Test
    fun redirectToPlainHttpIsRefused() {
        val s = server { _, out ->
            TinyHttpServer.respond(out, 302, headers = mapOf("Location" to "http://example.com/next"))
        }
        val r = send(get(s.url("/r")))
        assertTrue(r.errorOrNull() is SwError.Protocol)
        assertEquals(1, s.requests.size)
        assertEquals(listOf("127.0.0.1"), logged.map { it.host })
    }

    @Test
    fun bodyOverLimitFails() {
        val big = ByteArray(5_000) { 7 }
        val s = server { req, out ->
            if (req.target == "/len") {
                TinyHttpServer.respond(out, 200, big)
            } else {
                TinyHttpServer.respond(out, 200, big, contentLength = null) // ends at close, no length
            }
        }
        assertTrue(send(get(s.url("/len"), maxBytes = 1_000)).errorOrNull() is SwError.Protocol)
        assertTrue(send(get(s.url("/stream"), maxBytes = 1_000)).errorOrNull() is SwError.Protocol)
        assertEquals(5_000, send(get(s.url("/len"), maxBytes = 5_000)).valueOrNull()?.body?.size)
        assertEquals(listOf(NetLogEntry.ERROR, NetLogEntry.ERROR, 200), logged.map { it.result })
    }

    @Test
    fun silentServerTimesOut() {
        val s = server { _, _ -> Thread.sleep(5_000) }
        val start = System.nanoTime()
        val r = send(get(s.url("/slow"), timeoutMs = 300))
        assertTrue(r.errorOrNull() is SwError.Timeout)
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(4))
        assertEquals(NetLogEntry.ERROR, logged.single().result)
    }

    @Test
    fun cancellationDisconnectsAtOnce() {
        val s = server { _, _ -> Thread.sleep(20_000) }
        val c = client()
        runBlocking {
            val job = async(Dispatchers.Default) { c.send(get(s.url("/hang"), timeoutMs = 30_000)) }
            assertTrue(s.awaitRequest())
            val start = System.nanoTime()
            job.cancel()
            withTimeout(3_000) { job.join() }
            assertTrue(job.isCancelled)
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3))
        }
        assertEquals(NetLogEntry.ERROR, logged.single().result)
    }

    @Test
    fun missingInternetPermissionIsOffline() {
        val c = client(open = { throw SecurityException("Permission denied (missing INTERNET permission?)") })
        assertEquals(SwError.Offline, send(get("http://127.0.0.1:9/x"), c).errorOrNull())
        assertEquals(NetLogEntry.OFFLINE, logged.single().result)
    }

    @Test
    fun logNeverStoresPathOrQuery() {
        val s = server(ok())
        send(get(s.url("/search/secret-food?q=chocolate+cake&user=me#frag")))
        switches[NetFeature.OPEN_FOOD_FACTS] = false
        send(get(s.url("/search?q=chocolate")))
        assertEquals(2, logged.size)
        for (e in logged) {
            val text = e.toString()
            for (word in listOf("search", "secret", "chocolate", "cake", "user", "frag", "?", "/")) {
                assertFalse("$word in $text", text.contains(word))
            }
            assertEquals("127.0.0.1", e.host)
        }
        assertEquals("example.com", NetworkLog.hostOnly("https://me:pw@Example.com:8443/a/b?q=x#y"))
        assertEquals("[::1]", NetworkLog.hostOnly("http://[::1]:80/p"))
        assertEquals("example.com", NetworkLog.hostOnly("example.com/path?q=1"))
    }

    @Test
    fun downloadResumesWithRange() {
        val data = ByteArray(1_000) { (it % 251).toByte() }
        var first = true
        val s = server { req, out ->
            val range = req.headers["range"]
            if (first) {
                first = false
                // Promise everything, send 400 bytes, then drop the connection.
                TinyHttpServer.respond(out, 200, data.copyOf(400), mapOf("Accept-Ranges" to "bytes"), contentLength = 1_000)
            } else if (range != null) {
                val from = range.removePrefix("bytes=").substringBefore('-').toInt()
                TinyHttpServer.respond(
                    out, 206, data.copyOfRange(from, data.size),
                    mapOf("Content-Range" to "bytes $from-999/1000", "Accept-Ranges" to "bytes"),
                )
            } else {
                TinyHttpServer.respond(out, 200, data)
            }
        }
        val dir = Files.createTempDirectory("dl").toFile()
        val to = File(dir, "foods.db.gz")
        val tmp = File(dir, "foods.db.gz.tmp")
        val c = client()
        val progress = mutableListOf<Pair<Long, Long?>>()

        val r1 = runBlocking { c.download(get(s.url("/foods.db.gz"), NetFeature.FOOD_DATA), to) { a, b -> progress += a to b } }
        assertTrue(r1 is Outcome.Fail)
        assertFalse(to.exists())
        assertEquals(400L, tmp.length())

        val r2 = runBlocking { c.download(get(s.url("/foods.db.gz"), NetFeature.FOOD_DATA), to) { a, b -> progress += a to b } }
        assertEquals(1_000L, r2.valueOrNull())
        assertEquals("bytes=400-", s.requests[1].headers["range"])
        assertArrayEquals(data, to.readBytes())
        assertFalse(tmp.exists())
        assertEquals(1_000L to 1_000L, progress.last())
        assertEquals(600L, logged.last().bytesDown)
        assertEquals(206, logged.last().result)
        dir.deleteRecursively()
    }

    @Test
    fun downloadStartsOverWhenServerIgnoresRange() {
        val data = ByteArray(300) { 3 }
        val s = server { _, out -> TinyHttpServer.respond(out, 200, data) }
        val dir = Files.createTempDirectory("dl").toFile()
        val to = File(dir, "book.bin")
        File(dir, "book.bin.tmp").writeBytes(ByteArray(50) { 9 })
        to.writeBytes(ByteArray(1) { 1 }) // an older file is replaced
        val r = runBlocking { client().download(get(s.url("/book")), to) { _, _ -> } }
        assertEquals(300L, r.valueOrNull())
        assertArrayEquals(data, to.readBytes())
        assertFalse(File(dir, "book.bin.tmp").exists())
        dir.deleteRecursively()
    }

    @Test
    fun downloadOverLimitLeavesNoFile() {
        val s = server { _, out -> TinyHttpServer.respond(out, 200, ByteArray(2_000), mapOf("Accept-Ranges" to "bytes")) }
        val dir = Files.createTempDirectory("dl").toFile()
        val to = File(dir, "x.bin")
        val r = runBlocking { client().download(get(s.url("/x"), maxBytes = 1_000), to) { _, _ -> } }
        assertTrue(r.errorOrNull() is SwError.Protocol)
        assertFalse(to.exists())
        assertFalse(File(dir, "x.bin.tmp").exists())
        dir.deleteRecursively()
    }

    @Test
    fun userAgentHasNoDeviceDetails() {
        val ua = client().userAgent
        assertTrue(ua, ua.matches(Regex("SelfWatch/[A-Za-z0-9._+-]+")))
    }

    @Test
    fun userAgentPolicyIsPerFeature() {
        val c = client()
        // Only Open Food Facts gets a suffix; every User-Agent is plain ASCII (a header value).
        for (f in NetFeature.entries) {
            val ua = c.userAgentFor(f)
            assertTrue(ua, ua.all { it.code in 0x20..0x7E })
            if (f == NetFeature.OPEN_FOOD_FACTS) assertTrue(ua, ua.startsWith(c.userAgent + " (open source; +")) else assertEquals(c.userAgent, ua)
        }
        val s = server(ok())
        send(get(s.url("/f"), NetFeature.FOOD_DATA))
        assertEquals(c.userAgent, s.requests.single().headers["user-agent"])
    }
}
