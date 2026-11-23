/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.gatt

import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** A GATT callback reduced to a value, delivered in arrival order on the connection's dispatcher. */
internal sealed interface GattEvent {
    data class ConnectionState(val status: Int, val newState: Int) : GattEvent
    data class ServicesDiscovered(val status: Int) : GattEvent
    data class MtuChanged(val mtu: Int, val status: Int) : GattEvent
    data class DescriptorWritten(val service: UUID, val characteristic: UUID, val descriptor: UUID, val status: Int) : GattEvent
    data class CharacteristicWritten(val service: UUID, val characteristic: UUID, val status: Int) : GattEvent
    class CharacteristicRead(val service: UUID, val characteristic: UUID, val value: ByteArray, val status: Int) : GattEvent
    class CharacteristicChanged(val service: UUID, val characteristic: UUID, val value: ByteArray) : GattEvent
}

/**
 * One GATT operation.
 *
 * @property name short label for logs and timeout errors.
 * @property start starts the operation and returns [GattApi.STATUS_OK] if it began. Runs on the
 *   queue's dispatcher and may throw [SecurityException].
 * @property immediate for an operation without a callback: its result once [start] succeeded.
 * @property complete maps a callback event to this operation's result, or null if the event is not
 *   its callback. Null [complete] means the operation has no callback and is done once started
 *   ([immediate] must then be set).
 * @property fatalOnTimeout whether a timeout should tear down the link (the stack is likely stuck).
 */
internal class GattOp<T>(
    val name: String,
    val timeoutMs: Long,
    val start: () -> Int,
    val immediate: (() -> T)? = null,
    val fatalOnTimeout: Boolean = true,
    val complete: ((GattEvent) -> Outcome<T>?)? = null,
)

/**
 * Runs GATT operations strictly one at a time, in submission order. Android silently drops an
 * operation started while another is in flight, so nothing reaches the stack except through here.
 *
 * One queue lives for one GATT session. [run] processing and [onEvent] must both execute on the
 * same single-threaded dispatcher (the [scope]'s); [submit] may be called from anywhere.
 */
internal class GattQueue(
    private val scope: CoroutineScope,
    private val onFatalTimeout: (String) -> Unit,
) {
    private class Pending<T>(val op: GattOp<T>) {
        /** What the caller sees. A cancelled caller completes it early. */
        val result = CompletableDeferred<Outcome<T>>()

        /** Completed only by the callback (or [fail]); the queue waits on this, not on [result]. */
        val settled = CompletableDeferred<Unit>()

        fun fail(error: SwError) {
            result.complete(Outcome.Fail(error))
            settled.complete(Unit)
        }

        fun offer(event: GattEvent): Boolean {
            val mapped = op.complete?.invoke(event) ?: return false
            result.complete(mapped)
            settled.complete(Unit)
            return true
        }
    }

    private val ops = Channel<Pending<*>>(Channel.UNLIMITED)

    /** The operation in flight. Touched only on the queue's dispatcher. */
    private var current: Pending<*>? = null

    @Volatile
    private var closedWith: SwError? = null

    /** Starts processing; the returned job ends when the queue is closed or the scope cancelled. */
    fun start(): Job = scope.launch {
        for (p in ops) process(p)
    }

    private suspend fun <T> process(p: Pending<T>) {
        if (p.result.isCompleted) return // caller gave up while queued
        closedWith?.let { p.result.complete(Outcome.Fail(it)); return }
        current = p
        try {
            val status = try {
                p.op.start()
            } catch (e: SecurityException) {
                SwLog.w(TAG, "${p.op.name}: permission missing", e)
                p.result.complete(Outcome.Fail(SwError.PermissionMissing(BLUETOOTH_CONNECT)))
                return
            }
            if (status != GattApi.STATUS_OK) {
                p.result.complete(Outcome.Fail(SwError.Io("${p.op.name} not started (status $status)")))
                return
            }
            if (p.op.complete == null) {
                val immediate = checkNotNull(p.op.immediate) { "${p.op.name}: no callback and no immediate result" }
                p.result.complete(Outcome.Ok(immediate()))
                return
            }
            val done = withTimeoutOrNull(p.op.timeoutMs) { p.settled.await() }
            if (done == null) {
                SwLog.w(TAG, "${p.op.name} timed out after ${p.op.timeoutMs} ms")
                p.fail(SwError.Timeout(p.op.name))
                if (p.op.fatalOnTimeout) onFatalTimeout(p.op.name)
            }
        } finally {
            current = null
        }
    }

    /**
     * Queues [op] and waits for its result. Fails at once if the queue is closed. If the caller is
     * cancelled while the operation is queued it is skipped; if it is already in flight the queue
     * still waits for its callback so nothing overlaps.
     */
    suspend fun <T> submit(op: GattOp<T>): Outcome<T> {
        closedWith?.let { return Outcome.Fail(it) }
        val p = Pending(op)
        if (ops.trySend(p).isFailure) return Outcome.Fail(closedWith ?: SwError.Io("queue closed"))
        try {
            return p.result.await()
        } catch (e: CancellationException) {
            p.result.complete(Outcome.Fail(SwError.Io("cancelled")))
            throw e
        }
    }

    /** Offers a callback to the operation in flight. Returns true if it was that operation's callback. */
    fun onEvent(event: GattEvent): Boolean = current?.offer(event) ?: false

    /** Fails the operation in flight and everything queued with [error]; later submits fail too. */
    fun close(error: SwError) {
        if (closedWith != null) return
        closedWith = error
        ops.close()
        current?.fail(error)
        while (true) {
            val p = ops.tryReceive().getOrNull() ?: break
            p.fail(error)
        }
    }

    private companion object {
        const val TAG = "GattQueue"
        const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    }
}
