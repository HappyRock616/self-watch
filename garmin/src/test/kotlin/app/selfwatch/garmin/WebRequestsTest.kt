/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.garmin

import app.selfwatch.garmin.FeatureRig.Companion.settle
import app.selfwatch.garmin.FeatureRig.Companion.withRig
import app.selfwatch.garmin.appmsg.CiqCodec
import app.selfwatch.garmin.features.WebRequestHandler
import app.selfwatch.garmin.gfdi.FeatureReplies
import app.selfwatch.garmin.gfdi.GfdiId
import app.selfwatch.garmin.pb.proto.DataTransferProto
import app.selfwatch.garmin.pb.proto.HttpProto
import app.selfwatch.garmin.pb.proto.SmartProto.Smart
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import com.google.protobuf.ByteString
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/** Scripted FakeWatch flows for watch-app web requests and the data transfer service (A23). */
class WebRequestsTest {
    private val web = setOf(GarminCapability.CONNECTIQ_HTTP)

    private fun ciq(v: CiqValue) = ByteString.copyFrom(CiqCodec.encode(v))

    private fun webRequest(
        url: String = "http://127.0.0.1:8080/tcs/status",
        method: HttpProto.HttpMethod = HttpProto.HttpMethod.POST,
        headers: CiqValue? = CiqValue.Dict(mapOf(CiqValue.Str("Content-Type") to CiqValue.Num(1))),
        body: CiqValue? = CiqValue.Dict(mapOf(CiqValue.Str("boat") to CiqValue.Str("Pixie"), CiqValue.Str("knots") to CiqValue.Dbl(6.5))),
        maxLen: Int? = 2_000,
        type: HttpProto.ResponseType? = null,
        includeHeaders: Boolean = false,
    ): Smart {
        val r = HttpProto.WebRequest.newBuilder().setUrl(url).setHttpMethod(method).setIncludeHttpHeaderFieldsInResponse(includeHeaders)
        headers?.let { r.setHttpHeaderFields(ciq(it)) }
        body?.let { r.setHttpBody(ciq(it)) }
        maxLen?.let { r.setMaxResponseLength(it) }
        type?.let { r.setResponseType(it) }
        return Smart.newBuilder().setConnectIqHttpService(HttpProto.HttpService.newBuilder().setConnectIqHttpRequest(r)).build()
    }

    private fun rawRequest(url: String, xfer: Boolean = false, headers: Map<String, String> = emptyMap(), maxSize: Int? = null): Smart {
        val r = HttpProto.RawResourceRequest.newBuilder().setUrl(url).setMethod(HttpProto.HttpMethod.GET).setUseDataXfer(xfer)
        for ((k, v) in headers) r.addHeaders(HttpProto.HttpHeader.newBuilder().setKey(k).setValue(v))
        maxSize?.let { r.setMaxSize(it) }
        return Smart.newBuilder().setConnectIqHttpService(HttpProto.HttpService.newBuilder().setRawResourceRequest(r)).build()
    }

    private suspend fun TestScope.ask(r: FeatureRig, smart: Smart, pbId: Int): WatchEvent.WebRequest {
        r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, pbId, smart.toByteArray())
        settle()
        return r.events.filterIsInstance<WatchEvent.WebRequest>().last()
    }

    private fun answerTo(r: FeatureRig, pbId: Int): HttpProto.HttpService = r.pbResponses().last { it.first == pbId }.second.connectIqHttpService

    @Test
    fun localWebRequestRoundTrip() = runTest {
        withRig(web) { r ->
            val req = ask(r, webRequest(includeHeaders = true), 300)
            assertEquals("http://127.0.0.1:8080/tcs/status", req.url)
            assertEquals("POST", req.method)
            assertNull(req.appUuid)
            assertEquals(mapOf("Content-Type" to "application/json"), req.headers)
            assertEquals("""{"boat":"Pixie","knots":6.5}""", String(req.body!!, Charsets.UTF_8))
            // The chunk was accepted ("kept"); the answer follows later.
            assertTrue(r.pbStatuses().last().isOk)
            assertTrue(r.pbResponses().isEmpty())
            assertEquals(1, r.features.web.pendingCount)

            val body = """{"ok":true,"marks":[1,2.5,"C"],"wind":{"dir":270}}"""
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.WebResponse(req.requestId, 200, body.toByteArray(), "application/json; charset=utf-8")))
            settle()
            val resp = answerTo(r, 300).connectIqHttpResponse
            assertEquals(HttpProto.WebResponse.Status.OK, resp.status)
            assertEquals(200, resp.httpStatusCode)
            assertEquals(0, resp.inflatedSize)
            val expected = CiqValue.Dict(
                linkedMapOf(
                    CiqValue.Str("ok") to CiqValue.Bool(true),
                    CiqValue.Str("marks") to CiqValue.Arr(listOf(CiqValue.Num(1), CiqValue.Dbl(2.5), CiqValue.Str("C"))),
                    CiqValue.Str("wind") to CiqValue.Dict(mapOf(CiqValue.Str("dir") to CiqValue.Num(270))),
                ),
            )
            assertEquals(Outcome.Ok(expected), CiqCodec.decode(resp.httpBody.toByteArray()))
            assertEquals(
                Outcome.Ok(CiqValue.Dict(mapOf(CiqValue.Str("Content-Type") to CiqValue.Str("application/json; charset=utf-8")))),
                CiqCodec.decode(resp.httpHeaderFields.toByteArray()),
            )
            assertEquals(0, r.features.web.pendingCount)
            // A second answer to the same request is refused.
            val again = r.driver.send(WatchCommand.WebResponse(req.requestId, 200, ByteArray(0), null))
            assertTrue((again as Outcome.Fail).error is SwError.BadInput)
        }
    }

    @Test
    fun formEncodedBodyAndDefaultMethod() = runTest {
        withRig(web) { r ->
            val req = ask(
                r,
                webRequest(
                    method = HttpProto.HttpMethod.UNKNOWN,
                    headers = CiqValue.Dict(mapOf(CiqValue.Str("Content-Type") to CiqValue.Str("0"), CiqValue.Str("X-N") to CiqValue.Num(5))),
                    body = CiqValue.Dict(linkedMapOf(CiqValue.Str("q") to CiqValue.Str("a b&c"), CiqValue.Str("n") to CiqValue.Num(2))),
                ),
                301,
            )
            assertEquals("GET", req.method)
            assertEquals(mapOf("Content-Type" to "application/x-www-form-urlencoded", "X-N" to "5"), req.headers)
            assertEquals("q=a+b%26c&n=2", String(req.body!!, Charsets.UTF_8))
        }
    }

    @Test
    fun errorStatusesOversizeAndBadBodies() = runTest {
        withRig(web) { r ->
            suspend fun TestScope.statusFor(pbId: Int, smart: Smart, resp: (Int) -> WatchCommand.WebResponse): HttpProto.WebResponse {
                val req = ask(r, smart, pbId)
                assertEquals(Outcome.Ok(Unit), r.driver.send(resp(req.requestId)))
                settle()
                return answerTo(r, pbId).connectIqHttpResponse
            }
            val tooBig = statusFor(310, webRequest(maxLen = 100)) { WatchCommand.WebResponse(it, 200, ByteArray(101) { 'a'.code.toByte() }, "text/plain") }
            assertEquals(HttpProto.WebResponse.Status.NETWORK_RESPONSE_TOO_LARGE, tooBig.status)
            assertEquals(0, tooBig.httpStatusCode)
            // No max_response_length: Self Watch's default limit applies.
            val defaultLimit = statusFor(311, webRequest(maxLen = null, type = HttpProto.ResponseType.PLAIN_TEXT)) {
                WatchCommand.WebResponse(it, 200, ByteArray(WebRequestHandler.DEFAULT_MAX_RESPONSE + 1), "text/plain")
            }
            assertEquals(HttpProto.WebResponse.Status.NETWORK_RESPONSE_TOO_LARGE, defaultLimit.status)
            val offline = statusFor(312, webRequest()) { WatchCommand.WebResponse(it, WebRequestHandler.STATUS_NETWORK_ERROR, ByteArray(0), null) }
            assertEquals(HttpProto.WebResponse.Status.NETWORK_REQUEST_TIMED_OUT, offline.status)
            val blocked = statusFor(313, webRequest()) { WatchCommand.WebResponse(it, WebRequestHandler.STATUS_FAILED, ByteArray(0), null) }
            assertEquals(HttpProto.WebResponse.Status.UNKNOWN, blocked.status)
            // The service's specific Connect IQ errors (-n) reach the watch as status n.
            val insecure = statusFor(317, webRequest()) { WatchCommand.WebResponse(it, -1001, ByteArray(0), null) }
            assertEquals(HttpProto.WebResponse.Status.INSECURE_REQUEST, insecure.status)
            val notJson = statusFor(314, webRequest()) { WatchCommand.WebResponse(it, 404, "Not found".toByteArray(), "text/html") }
            assertEquals(HttpProto.WebResponse.Status.INVALID_HTTP_BODY_IN_NETWORK_RESPONSE, notJson.status)
            assertEquals(404, notJson.httpStatusCode)
            val badJson = statusFor(315, webRequest()) { WatchCommand.WebResponse(it, 200, "{oops".toByteArray(), "application/json") }
            assertEquals(HttpProto.WebResponse.Status.INVALID_HTTP_BODY_IN_NETWORK_RESPONSE, badJson.status)
            assertEquals(0, badJson.httpStatusCode)
            val text = statusFor(316, webRequest(type = HttpProto.ResponseType.PLAIN_TEXT)) { WatchCommand.WebResponse(it, 500, "busy".toByteArray(), "text/plain") }
            assertEquals(HttpProto.WebResponse.Status.OK, text.status)
            assertEquals(500, text.httpStatusCode)
            assertEquals(Outcome.Ok(CiqValue.Str("busy")), CiqCodec.decode(text.httpBody.toByteArray()))
            assertTrue(!text.hasHttpHeaderFields())
        }
    }

    @Test
    fun unansweredRequestTimesOut() = runTest {
        withRig(web, webTimeoutMs = 20_000) { r ->
            val req = ask(r, webRequest(), 320)
            settle(25_000)
            assertEquals(HttpProto.WebResponse.Status.NETWORK_REQUEST_TIMED_OUT, answerTo(r, 320).connectIqHttpResponse.status)
            assertEquals(0, r.features.web.pendingCount)
            val late = r.driver.send(WatchCommand.WebResponse(req.requestId, 200, ByteArray(0), null))
            assertTrue((late as Outcome.Fail).error is SwError.BadInput)
        }
    }

    @Test
    fun largeAnswerIsSentInProtobufChunks() = runTest {
        withRig(web, maxPacket = 90) { r ->
            val req = ask(r, webRequest(type = HttpProto.ResponseType.PLAIN_TEXT), 330)
            val text = "x".repeat(1_500)
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.WebResponse(req.requestId, 200, text.toByteArray(), "text/plain")))
            settle()
            assertEquals(Outcome.Ok(CiqValue.Str(text)), CiqCodec.decode(answerTo(r, 330).connectIqHttpResponse.httpBody.toByteArray()))
            val chunks = r.sent().filterIsInstance<app.selfwatch.garmin.gfdi.ProtobufPacket>().filter { it.id == GfdiId.PROTOBUF_RESPONSE && it.requestId == 330 }
            assertTrue(chunks.size > 10)
        }
    }

    @Test
    fun rawRequestWithDataTransferEphemerisMarkAndGzip() = runTest {
        withRig(setOf(GarminCapability.HTTP_RAW_RESOURCE_REQUEST)) { r ->
            val url = "https://api.gcs.garmin.com/ephemeris/cpe/sony/lle?coverage=WEEKS_1"
            val req = ask(r, rawRequest(url, xfer = true, headers = mapOf("Accept" to "application/octet-stream")), 340)
            assertEquals(url, req.url)
            assertEquals("1", req.headers[WebRequestHandler.EPHEMERIS_HEADER])
            assertEquals("application/octet-stream", req.headers["accept"])
            val file = ByteArray(5_000) { (it * 31).toByte() }
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.WebResponse(req.requestId, 200, file, "application/octet-stream")))
            settle()
            val raw = answerTo(r, 340).rawResourceResponse
            assertEquals(HttpProto.RawResourceResponse.Status.OK, raw.status)
            assertEquals(200, raw.httpStatusCode)
            assertEquals(5_000, raw.xferData.size)
            assertTrue(raw.resourceData.isEmpty)
            // The watch pulls the data in pieces.
            val got = ByteArrayOutputStream()
            var pb = 400
            while (got.size() < file.size) {
                val dl = Smart.newBuilder().setDataTransferService(
                    DataTransferProto.DataTransferService.newBuilder().setDataDownloadRequest(
                        DataTransferProto.DataDownloadRequest.newBuilder().setId(raw.xferData.id).setOffset(got.size()).setMaxChunkSize(1_200),
                    ),
                ).build()
                r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, pb, dl.toByteArray())
                settle()
                val d = r.pbResponses().last { it.first == pb }.second.dataTransferService.dataDownloadResponse
                assertEquals(DataTransferProto.DataDownloadResponse.Status.SUCCESS, d.status)
                assertEquals(got.size(), d.offset)
                assertTrue(d.payload.size() <= 1_200)
                got.write(d.payload.toByteArray())
                pb++
            }
            assertArrayEquals(file, got.toByteArray())
            suspend fun download(id: Int, offset: Int): DataTransferProto.DataDownloadResponse.Status {
                val dl = Smart.newBuilder().setDataTransferService(
                    DataTransferProto.DataTransferService.newBuilder().setDataDownloadRequest(
                        DataTransferProto.DataDownloadRequest.newBuilder().setId(id).setOffset(offset),
                    ),
                ).build()
                r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, pb, dl.toByteArray())
                settle()
                return r.pbResponses().last { it.first == pb++ }.second.dataTransferService.dataDownloadResponse.status
            }
            assertEquals(DataTransferProto.DataDownloadResponse.Status.INVALID_ID, download(raw.xferData.id + 50, 0))
            assertEquals(DataTransferProto.DataDownloadResponse.Status.INVALID_OFFSET, download(raw.xferData.id, 5_000))

            // Inline raw answer, gzip when the watch accepts it.
            val req2 = ask(r, rawRequest("https://example.org/a.json", headers = mapOf("Accept-Encoding" to "gzip")), 350)
            assertTrue(req2.headers[WebRequestHandler.EPHEMERIS_HEADER] == null)
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.WebResponse(req2.requestId, 200, "hello hello hello".toByteArray(), "text/plain")))
            settle()
            val raw2 = answerTo(r, 350).rawResourceResponse
            assertEquals("gzip", raw2.headersList.single { it.key == "Content-Encoding" }.value)
            assertEquals("hello hello hello", String(GZIPInputStream(raw2.resourceData.newInput()).readAllBytes()))

            // Over max_size.
            val req3 = ask(r, rawRequest("https://example.org/big", maxSize = 10), 360)
            assertEquals(Outcome.Ok(Unit), r.driver.send(WatchCommand.WebResponse(req3.requestId, 200, ByteArray(11), null)))
            settle()
            assertEquals(HttpProto.RawResourceResponse.Status.FILE_TOO_LARGE, answerTo(r, 360).rawResourceResponse.status)
        }
    }

    @Test
    fun unsupportedRawFormsAreAnsweredAtOnce() = runTest {
        withRig(web) { r ->
            val form = Smart.newBuilder().setConnectIqHttpService(
                HttpProto.HttpService.newBuilder().setRawResourceRequest(
                    HttpProto.RawResourceRequest.newBuilder().setUrl("https://example.org/up").addForms(HttpProto.MultipartForm.newBuilder().setName("f")),
                ),
            ).build()
            r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, 370, form.toByteArray())
            settle()
            assertTrue(r.events.none { it is WatchEvent.WebRequest })
            assertEquals(HttpProto.RawResourceResponse.Status.UNKNOWN, answerTo(r, 370).rawResourceResponse.status)
        }
    }

    @Test
    fun webRequestsWithoutTheFlagAreDiscarded() = runTest {
        withRig(setOf(GarminCapability.GNCS)) { r ->
            r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, 380, webRequest().toByteArray())
            settle()
            assertTrue(r.events.none { it is WatchEvent.WebRequest })
            val st = r.pbStatuses().last()
            assertEquals(FeatureReplies.ProtobufStatus.DISCARDED, st.chunkStatus)
            assertEquals(FeatureReplies.ProtobufStatus.UNKNOWN_REQUEST_ID, st.code)
        }
    }

    @Test
    fun tooManyWaitingRequestsAreRefused() = runTest {
        withRig(web) { r ->
            repeat(16) { ask(r, webRequest(), 600 + it) }
            assertEquals(16, r.features.web.pendingCount)
            r.watch.sendProtobuf(GfdiId.PROTOBUF_REQUEST, 699, webRequest().toByteArray())
            settle()
            assertEquals(16, r.events.filterIsInstance<WatchEvent.WebRequest>().size)
            assertEquals(HttpProto.WebResponse.Status.UNKNOWN, answerTo(r, 699).connectIqHttpResponse.status)
        }
    }
}

/** The status convention shared with the service's `WebRequestRouter` (0 = no answer, -n = Connect IQ error n). */
class WebErrorStatusTest {
    @Test
    fun webStatuses() {
        assertEquals(HttpProto.WebResponse.Status.NETWORK_REQUEST_TIMED_OUT, WebRequestHandler.webErrorStatus(0))
        assertEquals(HttpProto.WebResponse.Status.UNKNOWN, WebRequestHandler.webErrorStatus(WebRequestHandler.STATUS_FAILED))
        assertEquals(HttpProto.WebResponse.Status.INVALID_HTTP_HEADER_FIELDS_IN_REQUEST, WebRequestHandler.webErrorStatus(-200))
        assertEquals(HttpProto.WebResponse.Status.INVALID_HTTP_BODY_IN_REQUEST, WebRequestHandler.webErrorStatus(-201))
        assertEquals(HttpProto.WebResponse.Status.INVALID_HTTP_METHOD_IN_REQUEST, WebRequestHandler.webErrorStatus(-202))
        assertEquals(HttpProto.WebResponse.Status.NETWORK_REQUEST_TIMED_OUT, WebRequestHandler.webErrorStatus(-300))
        assertEquals(HttpProto.WebResponse.Status.INVALID_HTTP_BODY_IN_NETWORK_RESPONSE, WebRequestHandler.webErrorStatus(-400))
        assertEquals(HttpProto.WebResponse.Status.NETWORK_RESPONSE_TOO_LARGE, WebRequestHandler.webErrorStatus(-402))
        assertEquals(HttpProto.WebResponse.Status.INSECURE_REQUEST, WebRequestHandler.webErrorStatus(-1001))
        // -100 would be OK, which is never an error; unknown codes are UNKNOWN.
        assertEquals(HttpProto.WebResponse.Status.UNKNOWN, WebRequestHandler.webErrorStatus(-100))
        assertEquals(HttpProto.WebResponse.Status.UNKNOWN, WebRequestHandler.webErrorStatus(-7))
        assertEquals(HttpProto.WebResponse.Status.UNKNOWN, WebRequestHandler.webErrorStatus(Int.MIN_VALUE))
    }

    @Test
    fun rawStatuses() {
        assertEquals(HttpProto.RawResourceResponse.Status.NETWORK_REQUEST_TIMED_OUT, WebRequestHandler.rawErrorStatus(0))
        assertEquals(HttpProto.RawResourceResponse.Status.NETWORK_REQUEST_TIMED_OUT, WebRequestHandler.rawErrorStatus(-300))
        assertEquals(HttpProto.RawResourceResponse.Status.FILE_TOO_LARGE, WebRequestHandler.rawErrorStatus(-402))
        assertEquals(HttpProto.RawResourceResponse.Status.UNKNOWN, WebRequestHandler.rawErrorStatus(-1))
        assertEquals(HttpProto.RawResourceResponse.Status.UNKNOWN, WebRequestHandler.rawErrorStatus(-1001))
    }
}
