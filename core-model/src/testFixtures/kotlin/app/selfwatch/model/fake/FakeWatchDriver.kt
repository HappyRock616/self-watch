/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.fake

import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.SyncReason
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.FileStore
import app.selfwatch.model.watch.HealthSink
import app.selfwatch.model.watch.SyncReport
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchDriver
import app.selfwatch.model.watch.WatchEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections

/**
 * A scriptable [WatchDriver]. [run] marks the driver [ConnectionState.Ready], records its arguments
 * and suspends until [disconnect] or cancellation, then goes [ConnectionState.Disconnected].
 * [sync] and [send] record their arguments and return [syncResult] / [sendResult].
 */
public class FakeWatchDriver(initialInfo: DeviceInfo? = null) : WatchDriver {
    /** Settable device info. */
    public val mutableInfo: MutableStateFlow<DeviceInfo?> = MutableStateFlow(initialInfo)

    /** Settable connection state. */
    public val mutableState: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)

    private val eventFlow = MutableSharedFlow<WatchEvent>(extraBufferCapacity = 64)

    override val info: StateFlow<DeviceInfo?> get() = mutableInfo
    override val state: StateFlow<ConnectionState> get() = mutableState
    override val events: Flow<WatchEvent> get() = eventFlow

    /** What [sync] returns. */
    @Volatile
    public var syncResult: Outcome<SyncReport> = Outcome.Ok(SyncReport(filesNew = 0, filesFailed = 0, durationMs = 0))

    /** What [send] returns. */
    @Volatile
    public var sendResult: Outcome<Unit> = Outcome.Ok(Unit)

    /** Every reason passed to [sync], in order. */
    public val syncCalls: MutableList<SyncReason> = Collections.synchronizedList(ArrayList())

    /** Every command passed to [send], in order. */
    public val sent: MutableList<WatchCommand> = Collections.synchronizedList(ArrayList())

    /** The port passed to the current or last [run]. */
    @Volatile
    public var lastPort: BlePort? = null
        private set

    /** The sink passed to the current or last [run]. */
    @Volatile
    public var lastSink: HealthSink? = null
        private set

    /** The file store passed to the current or last [run]. */
    @Volatile
    public var lastFiles: FileStore? = null
        private set

    /** How many times [run] was called. */
    @Volatile
    public var runCount: Int = 0
        private set

    @Volatile
    private var stop = CompletableDeferred<Unit>()

    override suspend fun run(port: BlePort, sink: HealthSink, files: FileStore) {
        lastPort = port
        lastSink = sink
        lastFiles = files
        runCount++
        val gate = CompletableDeferred<Unit>().also { stop = it }
        mutableState.value = ConnectionState.Ready
        try {
            gate.await()
        } finally {
            mutableState.value = ConnectionState.Disconnected
        }
    }

    /** Makes the current [run] return, as if the watch disconnected. */
    public fun disconnect() {
        stop.complete(Unit)
    }

    override suspend fun sync(reason: SyncReason): Outcome<SyncReport> {
        syncCalls.add(reason)
        return syncResult
    }

    override suspend fun send(cmd: WatchCommand): Outcome<Unit> {
        sent.add(cmd)
        return sendResult
    }

    /** Emits [event] to collectors of [events]. Returns false if the buffer was full. */
    public fun emit(event: WatchEvent): Boolean = eventFlow.tryEmit(event)
}
