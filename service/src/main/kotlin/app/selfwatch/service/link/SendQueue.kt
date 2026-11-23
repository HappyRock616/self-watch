/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import app.selfwatch.link.client.LinkStatus
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Phone → watch app messages (F06): one queue per (watch, watch app), one message in flight per queue.
 * A message that times out is sent once more; a second timeout reports [LinkStatus.FAILED]. Each
 * message gets exactly one result ([statusOf]) through its callback.
 *
 * @param send delivers one command to a watch (the DeviceManager, through [LinkHub]).
 * @param capacity messages waiting per queue; more are refused at once with [LinkStatus.FAILED].
 */
public class SendQueue(
    private val scope: CoroutineScope,
    private val send: suspend (deviceId: Long, cmd: WatchCommand) -> Outcome<Unit>,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private class Out(val deviceId: Long, val cmd: WatchCommand.AppMessage, val onResult: (Int) -> Unit)

    private val queues = HashMap<Pair<Long, UUID>, Channel<Out>>()

    /**
     * Queues [payload] for watch app [appUuid] on watch [deviceId]. [onResult] is called once with a
     * `LinkStatus` code. Returns false (and calls nothing) when the queue is full.
     */
    public fun enqueue(deviceId: Long, appUuid: UUID, payload: CiqValue, onResult: (Int) -> Unit): Boolean {
        val ch = synchronized(queues) {
            queues.getOrPut(deviceId to appUuid) {
                Channel<Out>(capacity).also { c -> scope.launch { work(c) } }
            }
        }
        return ch.trySend(Out(deviceId, WatchCommand.AppMessage(appUuid, payload), onResult)).isSuccess
    }

    private suspend fun work(ch: Channel<Out>) {
        for (o in ch) {
            var r = attempt(o)
            if (r is Outcome.Fail && r.error is SwError.Timeout) {
                SwLog.i(TAG, "app message to ${o.cmd.appUuid} timed out; retrying once")
                r = attempt(o)
            }
            val status = statusOf(r)
            if (r is Outcome.Fail) SwLog.i(TAG, "app message to ${o.cmd.appUuid} failed: ${r.error}")
            try {
                o.onResult(status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "send result callback failed", e)
            }
        }
    }

    private suspend fun attempt(o: Out): Outcome<Unit> = try {
        send(o.deviceId, o.cmd)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SwLog.e(TAG, "app message send threw", e)
        Outcome.Fail(SwError.Io("send failed"))
    }

    /** Constants and the result mapping. */
    public companion object {
        private const val TAG = "LinkSendQueue"

        /** Messages waiting per (watch, watch app). */
        public const val DEFAULT_CAPACITY: Int = 16

        /** The `LinkStatus` code for a driver result. */
        public fun statusOf(r: Outcome<Unit>): Int = when (r) {
            is Outcome.Ok -> LinkStatus.SUCCESS
            is Outcome.Fail -> when (val e = r.error) {
                is SwError.NotConnected -> LinkStatus.NOT_CONNECTED
                is SwError.BadInput -> when {
                    e.detail.contains("too large") -> LinkStatus.TOO_LARGE
                    e.detail.contains("not installed") -> LinkStatus.NOT_INSTALLED
                    else -> LinkStatus.INVALID_MESSAGE
                }
                else -> LinkStatus.FAILED
            }
        }
    }
}
