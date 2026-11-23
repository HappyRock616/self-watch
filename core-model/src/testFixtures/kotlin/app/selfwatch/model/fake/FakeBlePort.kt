/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.fake

import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.ble.BytePipe
import app.selfwatch.model.ble.PortState
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID

/**
 * A scriptable [BlePort]. Pipes are created on demand by [openPipe] (or ahead of time with [pipe])
 * and are [FakeBytePipe]s: queue notifications with [FakeBytePipe.queueIncoming], read what the code
 * under test wrote from [FakeBytePipe.writes], and answer writes with [FakeBytePipe.onWrite].
 */
public class FakeBlePort(
    override val address: String = "AA:BB:CC:DD:EE:FF",
    initialState: PortState = PortState.CONNECTED,
    /** Service UUIDs returned by [serviceUuids]. */
    public var services: Set<UUID> = emptySet(),
    /** `maxWrite` for pipes created from now on. */
    public var maxWrite: Int = 20,
) : BlePort {
    private val pipes = LinkedHashMap<Triple<UUID, UUID, UUID>, FakeBytePipe>()

    /** Mutable link state; tests set it to simulate connects and drops. */
    public val mutableState: MutableStateFlow<PortState> = MutableStateFlow(initialState)
    override val state: StateFlow<PortState> get() = mutableState

    /** When non-null, [openPipe] fails with this error. */
    @Volatile
    public var openPipeError: SwError? = null

    /** Every value passed to [setHighSpeed], in order. */
    public val highSpeedCalls: MutableList<Boolean> = java.util.Collections.synchronizedList(ArrayList())

    override suspend fun serviceUuids(): Set<UUID> = services

    override suspend fun openPipe(service: UUID, rx: UUID, tx: UUID): Outcome<BytePipe> {
        openPipeError?.let { return Outcome.Fail(it) }
        return Outcome.Ok(pipe(service, rx, tx))
    }

    /** The pipe for this (service, rx, tx), created if needed; lets a test script it before the code opens it. */
    @Synchronized
    public fun pipe(service: UUID, rx: UUID, tx: UUID): FakeBytePipe =
        pipes.getOrPut(Triple(service, rx, tx)) { FakeBytePipe(maxWrite) }

    /** Every pipe created so far, in creation order. */
    @Synchronized
    public fun allPipes(): List<FakeBytePipe> = pipes.values.toList()

    override suspend fun setHighSpeed(on: Boolean) {
        highSpeedCalls.add(on)
    }
}

/**
 * A scriptable [BytePipe]. Notifications are buffered without limit and delivered in order to the
 * collector of [incoming] (one collector at a time).
 */
public class FakeBytePipe(override val maxWrite: Int = 20) : BytePipe {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val written = ArrayList<ByteArray>()

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    /** Called after each successful write with the written bytes, e.g. to queue a scripted reply. */
    @Volatile
    public var onWrite: (FakeBytePipe, ByteArray) -> Unit = { _, _ -> }

    /** When non-null, [write] fails with this error (and records nothing). */
    @Volatile
    public var writeError: SwError? = null

    /** True after [close]. */
    @Volatile
    public var closed: Boolean = false
        private set

    /** Queues one notification from the device. */
    public fun queueIncoming(bytes: ByteArray) {
        inbox.trySend(bytes.copyOf())
    }

    /** Ends [incoming] as a dropped link would. */
    public fun endIncoming() {
        inbox.close()
    }

    /** Copies of everything written so far, in order. */
    @Synchronized
    public fun writes(): List<ByteArray> = written.map { it.copyOf() }

    /** Clears the record of writes. */
    @Synchronized
    public fun clearWrites() {
        written.clear()
    }

    override suspend fun write(bytes: ByteArray): Outcome<Unit> {
        if (closed) return Outcome.Fail(SwError.Io("pipe closed"))
        writeError?.let { return Outcome.Fail(it) }
        if (bytes.size > maxWrite) return Outcome.Fail(SwError.Protocol("write of ${bytes.size} > maxWrite $maxWrite"))
        val copy = bytes.copyOf()
        synchronized(this) { written.add(copy) }
        onWrite(this, copy)
        return Outcome.Ok(Unit)
    }

    override suspend fun close() {
        closed = true
        inbox.close()
    }
}
