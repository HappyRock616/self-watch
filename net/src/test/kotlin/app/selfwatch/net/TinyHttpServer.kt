/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One request as the test server saw it. Header names are lower case. */
class TinyRequest(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray)

/**
 * A minimal HTTP/1.1 server on 127.0.0.1 for tests: one thread per connection, `Connection: close`
 * on every response. [handler] writes the raw response to the stream.
 */
class TinyHttpServer(private val handler: (TinyRequest, OutputStream) -> Unit) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets: MutableList<Socket> = Collections.synchronizedList(mutableListOf())

    /** Every request received, in order. */
    val requests: MutableList<TinyRequest> = CopyOnWriteArrayList()

    /** Counted down when a request arrives. */
    @Volatile
    var arrived: CountDownLatch = CountDownLatch(1)

    /** Counted down when a handler finishes or its client goes away. */
    val clientGone: CountDownLatch = CountDownLatch(1)

    val port: Int get() = server.localPort

    /** `http://127.0.0.1:<port><path>`. */
    fun url(path: String): String = "http://127.0.0.1:$port$path"

    init {
        Thread({
            while (!server.isClosed) {
                val s = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                sockets += s
                Thread({ serve(s) }, "tiny-http-conn").apply { isDaemon = true }.start()
            }
        }, "tiny-http").apply { isDaemon = true }.start()
    }

    private fun serve(s: Socket) {
        try {
            s.use {
                val input = BufferedInputStream(it.getInputStream())
                val line = readLine(input) ?: return
                val parts = line.split(' ')
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    val colon = h.indexOf(':')
                    if (colon > 0) headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val req = TinyRequest(parts[0], parts.getOrElse(1) { "/" }, headers, body)
                requests += req
                arrived.countDown()
                handler(req, it.getOutputStream())
                it.getOutputStream().flush()
            }
        } catch (e: IOException) {
            // Client went away; fine for tests.
        } finally {
            clientGone.countDown()
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            out.write(b)
        }
    }

    /** Waits up to [seconds] for the next request. */
    fun awaitRequest(seconds: Long = 5): Boolean = arrived.await(seconds, TimeUnit.SECONDS)

    override fun close() {
        server.close()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    }

    companion object {
        /** Writes a full response with `Content-Length` (unless [contentLength] overrides it). */
        fun respond(
            out: OutputStream,
            status: Int,
            body: ByteArray = ByteArray(0),
            headers: Map<String, String> = emptyMap(),
            contentLength: Long? = body.size.toLong(),
        ) {
            val sb = StringBuilder("HTTP/1.1 $status X\r\n")
            if (contentLength != null) sb.append("Content-Length: $contentLength\r\n")
            headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }
    }
}
