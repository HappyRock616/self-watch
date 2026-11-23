/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service

import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.SyncReason
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.FileStore
import app.selfwatch.model.watch.HealthSink
import app.selfwatch.model.watch.SyncReport
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchDriver
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.service.device.DriverFactory
import app.selfwatch.service.device.EventHandlers
import app.selfwatch.service.device.PortFactory
import app.selfwatch.service.health.WatchRestingKcal
import app.selfwatch.service.link.MessageSizer
import app.selfwatch.service.watchapps.WatchAppControl
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What the app plugs into the service. The app's `Application` class implements [WatchServiceHost]
 * (it runs in the `:watch` process too), so the service gets the real Bluetooth and Garmin code
 * without depending on those modules.
 */
public data class WatchServiceDeps(
    /** Makes Bluetooth links (the app: `ble.BleConnection`). */
    val portFactory: PortFactory,
    /** Makes drivers (the app: `garmin.GarminDriver`). */
    val driverFactory: DriverFactory,
    /** Where watch events go (relays, link router, web router, watch-app store). */
    val handlers: EventHandlers = EventHandlers(),
    /** The watch's resting metabolic rate, when the FIT data provides it. */
    val restingKcal: WatchRestingKcal = WatchRestingKcal.NONE,
    /** Watch-app list refresh and settings reads (the app: each driver's `GarminFeatures`). */
    val appControl: WatchAppControl = WatchAppControl.NONE,
    /** App message size on the watch link, for the link's size check (the app: garmin's encoding). */
    val messageSizer: MessageSizer = MessageSizer.ESTIMATE,
) {
    /** Defaults. */
    public companion object {
        /**
         * Used when the application is not a [WatchServiceHost]: drivers that never connect, so the
         * service stays harmless.
         */
        public val UNWIRED: WatchServiceDeps = WatchServiceDeps(UnwiredPorts, DriverFactory { UnwiredDriver(it) })
    }
}

/** Implemented by the app's `Application` to wire [SyncService]. */
public interface WatchServiceHost {
    /** The service's dependencies (called once per service start, in the `:watch` process). */
    public fun watchServiceDeps(): WatchServiceDeps
}

private object UnwiredPorts : PortFactory {
    override fun create(address: String, autoReconnect: Boolean): BlePort =
        throw IllegalStateException("PortFactory not wired: the Application must implement WatchServiceHost")
}

private class UnwiredDriver(info: DeviceInfo) : WatchDriver {
    override val info: StateFlow<DeviceInfo?> = MutableStateFlow(info)
    override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
    override val events: Flow<WatchEvent> = emptyFlow()

    override suspend fun run(port: BlePort, sink: HealthSink, files: FileStore) {
        awaitCancellation()
    }

    override suspend fun sync(reason: SyncReason): Outcome<SyncReport> =
        Outcome.Fail(SwError.NotConnected(info.value?.id ?: 0))

    override suspend fun send(cmd: WatchCommand): Outcome<Unit> = Outcome.Fail(SwError.NotConnected(info.value?.id ?: 0))
}
