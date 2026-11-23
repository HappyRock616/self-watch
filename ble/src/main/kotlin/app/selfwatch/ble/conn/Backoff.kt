/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.conn

import app.selfwatch.ble.BACKOFF_SEC

/**
 * Reconnect delays: each [nextDelayMs] returns the next step of [stepsSec], repeating the last one
 * for ever; [reset] starts again from the first after a successful connection. Not thread-safe;
 * used only on the connection's dispatcher.
 */
internal class Backoff(private val stepsSec: IntArray = BACKOFF_SEC) {
    private var index = 0

    init {
        require(stepsSec.isNotEmpty()) { "no back-off steps" }
    }

    /** The delay before the next attempt, in milliseconds. */
    fun nextDelayMs(): Long {
        val sec = stepsSec[index.coerceAtMost(stepsSec.size - 1)]
        if (index < stepsSec.size - 1) index++
        return sec * 1000L
    }

    /** Back to the first step. */
    fun reset() {
        index = 0
    }
}

/**
 * Why a [BleConnection] reached `PortState.CLOSED`. Moved to core-model v1.1
 * ([app.selfwatch.model.ble.CloseReason]) so `garmin` can read it through `HasCloseReason`; this
 * alias keeps existing imports working.
 */
public typealias CloseReason = app.selfwatch.model.ble.CloseReason
