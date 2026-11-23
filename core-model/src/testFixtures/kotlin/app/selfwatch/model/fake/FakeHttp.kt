/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.fake

import app.selfwatch.model.net.Http
import app.selfwatch.model.net.HttpRequest
import app.selfwatch.model.net.HttpResponse
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import java.io.File

/**
 * A scriptable [Http]. Every request is recorded in [requests]. Answers come, in this order, from:
 * a blocked feature ([blocked] → `SwError.Blocked`), the queue filled by [enqueue], the routes added
 * with [on] (first whose URL prefix matches), and finally a 404 with an empty body.
 */
public class FakeHttp : Http {
    private val log = ArrayList<HttpRequest>()
    private val queue = ArrayDeque<Outcome<HttpResponse>>()
    private val routes = ArrayList<Pair<String, Outcome<HttpResponse>>>()

    /** Features the fake firewall blocks. */
    public val blocked: MutableSet<NetFeature> = java.util.Collections.synchronizedSet(HashSet())

    /** Queues one answer for the next request, whatever its URL. */
    @Synchronized
    public fun enqueue(answer: Outcome<HttpResponse>) {
        queue.addLast(answer)
    }

    /** Queues a successful response with [status] and a UTF-8 [body]. */
    public fun enqueue(status: Int, body: String, headers: Map<String, List<String>> = emptyMap()) {
        enqueue(Outcome.Ok(HttpResponse(status, headers, body.toByteArray(Charsets.UTF_8))))
    }

    /** Answers every request whose URL starts with [urlPrefix] with [answer] (when the queue is empty). */
    @Synchronized
    public fun on(urlPrefix: String, answer: Outcome<HttpResponse>) {
        routes.add(urlPrefix to answer)
    }

    /** Every request sent, in order. */
    @Synchronized
    public fun requests(): List<HttpRequest> = log.toList()

    @Synchronized
    private fun answer(req: HttpRequest): Outcome<HttpResponse> {
        log.add(req)
        if (req.feature in blocked) return Outcome.Fail(SwError.Blocked(req.feature))
        queue.removeFirstOrNull()?.let { return it }
        routes.firstOrNull { req.url.startsWith(it.first) }?.let { return it.second }
        return Outcome.Ok(HttpResponse(404, emptyMap(), ByteArray(0)))
    }

    override suspend fun send(req: HttpRequest): Outcome<HttpResponse> = answer(req)

    /** Writes the answer's body to [to] and reports progress once, at the end. Non-2xx statuses fail with `Io`. */
    override suspend fun download(req: HttpRequest, to: File, onProgress: (Long, Long?) -> Unit): Outcome<Long> =
        when (val a = answer(req)) {
            is Outcome.Fail -> a
            is Outcome.Ok -> {
                val r = a.value
                if (r.status !in 200..299) {
                    Outcome.Fail(SwError.Io("HTTP ${r.status}"))
                } else {
                    to.writeBytes(r.body)
                    val n = r.body.size.toLong()
                    onProgress(n, n)
                    Outcome.Ok(n)
                }
            }
        }
}
