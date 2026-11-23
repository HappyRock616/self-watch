/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import app.selfwatch.model.log.SwLog
import kotlinx.coroutines.delay

/**
 * Short in-run back-off for Health Connect rate limits: a call refused with [HcRateLimitedException] is
 * tried again after each of [delaysMs]; when they are used up the exception propagates (and [HcSync]
 * backs off for longer, across runs).
 */
class HcRetry(
    private val delaysMs: List<Long> = listOf(2_000L, 8_000L),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** Runs [block], retrying on rate limits as described. */
    suspend fun <T> run(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: HcRateLimitedException) {
                if (attempt >= delaysMs.size) throw e
                SwLog.w(TAG, "rate limited, retry ${attempt + 1} in ${delaysMs[attempt]} ms")
                sleep(delaysMs[attempt])
                attempt++
            }
        }
    }

    private companion object {
        const val TAG = "HcRetry"
    }
}
