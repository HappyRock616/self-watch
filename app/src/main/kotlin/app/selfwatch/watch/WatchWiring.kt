/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.watch

import android.content.Context
import android.content.pm.ApplicationInfo
import app.selfwatch.ble.conn.BleConnection
import app.selfwatch.ble.log.BleLog
import app.selfwatch.ble.profile.HealthSensorDriver
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SettingsStore
import app.selfwatch.garmin.GarminDriver
import app.selfwatch.garmin.GarminFeatures
import app.selfwatch.garmin.appmsg.AppMessages
import app.selfwatch.garmin.appmsg.CiqCodec
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.watch.WatchApp
import app.selfwatch.service.link.LinkHub
import app.selfwatch.service.link.MessageSizer
import app.selfwatch.service.watchapps.SideloadStore
import app.selfwatch.service.watchapps.WatchAppControl
import app.selfwatch.service.watchapps.WatchAppsStore
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import app.selfwatch.garmin.files.FitModuleDecoders
import app.selfwatch.garmin.models.GarminModels
import app.selfwatch.garmin.session.GarminConfig
import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.ble.PortState
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.net.Http
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.valueOrNull
import app.selfwatch.model.watch.WatchDriver
import app.selfwatch.net.Firewall
import app.selfwatch.net.NetClient
import app.selfwatch.net.NetworkLog
import app.selfwatch.service.WatchServiceDeps
import app.selfwatch.service.device.DriverFactory
import app.selfwatch.service.device.EventHandlers
import app.selfwatch.service.device.PortFactory
import app.selfwatch.service.relay.CallControl
import app.selfwatch.service.relay.FindPhoneAlert
import app.selfwatch.service.relay.MusicControlRouter
import app.selfwatch.service.relay.NotificationActionRouter
import app.selfwatch.service.web.WebRequestRouter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** Setting keys owned by the app layer for the watch connection (generic `setting` rows). */
object WatchSettings {
    /** Bluetooth capture to `files/blelog/` (bool). Default: on in debug builds, off in release. */
    const val BLE_CAPTURE: String = SettingKeys.DEBUG_BLE_CAPTURE

    /** True while the next connection to the watch at [address] is the first one after pairing (bool). */
    fun firstConnectKey(address: String): String = "garmin.first_connect.${address.uppercase()}"

    /** True when the app is a debuggable (debug) build. */
    fun isDebuggable(ctx: Context): Boolean = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Whether Bluetooth capture is on, given the stored [value] (null = never set). */
    fun bleCaptureOn(value: String?, debuggable: Boolean): Boolean = value?.toBooleanStrictOrNull() ?: debuggable
}

/** One Bluetooth link as the [BlePortFactory] drives it. */
interface LinkHandle {
    /** The port handed to the driver. */
    val port: BlePort

    /** Brings the link up ([auto] = Android's low-power background connect). */
    suspend fun connect(auto: Boolean): Outcome<Unit>

    /** Closes the link for good. */
    suspend fun close()
}

/**
 * [PortFactory] on the `ble` module. Each link is connected as soon as it is made: the first try is a
 * direct connect (quick when the watch is near); after a direct try that never connected, the next
 * link uses Android's background auto-connect, which waits in low power until the watch is in range.
 * The driver returns when a link fails, so [app.selfwatch.service.device.DeviceManager]'s back-off
 * decides when the next link is made.
 */
class BlePortFactory(
    private val scope: CoroutineScope,
    private val open: (address: String) -> LinkHandle,
) : PortFactory {
    private class Live(val handle: LinkHandle, val job: Job, val reached: AtomicBoolean)

    private val lock = Any()
    private val live = HashMap<BlePort, Live>()
    private val directFailed = HashSet<String>()

    override fun create(address: String, autoReconnect: Boolean): BlePort {
        val key = address.uppercase()
        val handle = open(address)
        val auto = autoReconnect && synchronized(lock) { key in directFailed }
        val reached = AtomicBoolean(false)
        val job = scope.launch {
            launch {
                handle.port.state.first { it == PortState.CONNECTED }
                reached.set(true)
                synchronized(lock) { directFailed.remove(key) }
            }
            when (val r = handle.connect(auto)) {
                is Outcome.Ok -> SwLog.i(TAG, "link up (auto=$auto)")
                is Outcome.Fail -> SwLog.i(TAG, "link attempt failed (auto=$auto): ${r.error}")
            }
        }
        synchronized(lock) { live[handle.port] = Live(handle, job, reached) }
        return handle.port
    }

    override suspend fun release(port: BlePort) {
        val l = synchronized(lock) { live.remove(port) } ?: return
        l.job.cancel()
        if (!l.reached.get()) synchronized(lock) { directFailed.add(port.address.uppercase()) }
        try {
            l.handle.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.w(TAG, "closing the link failed", e)
        }
    }

    /** True when the next link to [address] will use auto-connect (for tests and logs). */
    fun usesAutoConnect(address: String): Boolean = synchronized(lock) { address.uppercase() in directFailed }

    private companion object {
        const val TAG = "BlePortFactory"
    }
}

/**
 * [DriverFactory] for Garmin watches, and for scales, straps and cuffs that follow the standard
 * Bluetooth health profiles (`ble.profile.HealthSensorDriver`). Every Garmin connection reads its
 * settings fresh: `firstConnect` is true while [WatchSettings.firstConnectKey] is set (cleared once the
 * watch reaches READY), and fake sign-in follows `garmin.fake_auth`. FIT files are decoded in the
 * phone's time zone.
 */
class GarminDriverFactory(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val models: () -> GarminModels?,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : DriverFactory {

    override fun create(info: DeviceInfo): WatchDriver = when (info.kind) {
        DeviceKind.GARMIN_WATCH -> garmin(info)
        // Scales, straps and cuffs that follow the open Bluetooth SIG health profiles.
        DeviceKind.WEIGHT_SCALE, DeviceKind.HEART_RATE_SENSOR, DeviceKind.BLOOD_PRESSURE_MONITOR ->
            HealthSensorDriver(info, SystemClock)
    }

    private val features = ConcurrentHashMap<Long, GarminFeatures>()

    /** The feature handlers of watch [deviceId]'s current driver, or null before one was made. */
    fun featuresOf(deviceId: Long): GarminFeatures? = features[deviceId]

    /** Watch-app list refresh and settings reads on each driver's [GarminFeatures]. */
    val appControl: WatchAppControl = object : WatchAppControl {
        override suspend fun refreshApps(deviceId: Long): Outcome<List<WatchApp>> =
            features[deviceId]?.apps?.refresh() ?: Outcome.Fail(SwError.NotConnected(deviceId))

        override suspend fun requestSettings(deviceId: Long, appUuid: UUID): Outcome<Unit> =
            features[deviceId]?.appSettings?.request(appUuid) ?: Outcome.Fail(SwError.NotConnected(deviceId))
    }

    private fun garmin(info: DeviceInfo): GarminDriver {
        val firstKey = WatchSettings.firstConnectKey(info.address)
        // One GarminFeatures per driver, kept so the service can reach apps.refresh / appSettings.request.
        val f = GarminFeatures(SystemClock)
        features[info.id] = f
        val driver = GarminDriver(
            deviceId = info.id,
            config = GarminConfig(),
            models = models(),
            decoders = FitModuleDecoders(zone()),
            handlers = f.handlers,
            connectConfig = { configFor(firstKey) },
        )
        scope.launch {
            driver.state.first { it is ConnectionState.Ready }
            if (settings.getBool(firstKey)) {
                settings.set(firstKey, null)
                SwLog.i(TAG, "first connection after pairing done")
            }
        }
        return driver
    }

    /** The settings for the next connection of the watch whose first-connect flag is [firstKey]. */
    suspend fun configFor(firstKey: String): GarminConfig = GarminConfig(
        firstConnect = settings.getBool(firstKey),
        fakeAuth = settings.getBool(SettingKeys.GARMIN_FAKE_AUTH),
    )

    private companion object {
        const val TAG = "GarminDriverFactory"
    }
}

/**
 * The `:watch` process's wiring of [app.selfwatch.service.SyncService]: real Bluetooth links, Garmin
 * drivers and the Bluetooth capture. Built only in the `:watch` process (by
 * [app.selfwatch.SelfWatchApp.watchServiceDeps]); it never touches the app graph, food, interop or ui.
 */
class WatchWiring(ctx: Context) {
    private val app: Context = ctx.applicationContext ?: ctx

    /** Owner: the `:watch` process (lives as long as it does). */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> SwLog.e(TAG, "watch wiring failure", e) },
    )

    private val db: SelfWatchDb by lazy { SelfWatchDb.get(app) }

    private val models: GarminModels? by lazy { GarminModels.load().valueOrNull() }

    /** The shared Bluetooth capture (one writer per process); follows [WatchSettings.BLE_CAPTURE]. */
    val bleLog: BleLog by lazy {
        val log = BleLog.forContext(app)
        val debuggable = WatchSettings.isDebuggable(app)
        scope.launch {
            db.settings.observe(WatchSettings.BLE_CAPTURE).collect { v ->
                val on = WatchSettings.bleCaptureOn(v, debuggable)
                if (log.enabled != on) {
                    log.enabled = on
                    SwLog.i(TAG, "Bluetooth capture ${if (on) "on" else "off"}")
                }
            }
        }
        log
    }

    /** Port factory on `ble.BleConnection`. */
    val portFactory: BlePortFactory by lazy {
        BlePortFactory(scope) { address -> BleLinkHandle(BleConnection(app, address, bleLog)) }
    }

    /** Driver factory on `garmin.GarminDriver`. */
    val driverFactory: GarminDriverFactory by lazy { GarminDriverFactory(db.settings, scope, { models }) }

    /** The `:watch` process's own HTTP client (firewall and network log shared through the database). */
    val http: Http by lazy { NetClient(app, Firewall(db.settings), NetworkLog(db)) }

    /**
     * Where watch events go: find my phone, music buttons and notification actions (through the bound
     * notification listener, see `service.relay.RelayHub`), call accept/reject and web requests
     * (B19–B25); app messages to the link router (F06) and app lists and settings to the watch-app
     * store (B26).
     */
    val handlers: EventHandlers by lazy {
        EventHandlers(
            findPhone = FindPhoneAlert(app),
            music = MusicControlRouter(),
            call = CallControl(app),
            notificationAction = NotificationActionRouter(),
            appMessage = LinkHub.router(app),
            webRequest = WebRequestRouter(http, scope),
            watchApps = WatchAppsStore(db, SystemClock, SideloadStore.forContext(app), { runCatching { Locale.getDefault().isO3Language }.getOrNull() }),
        )
    }

    /** What [app.selfwatch.service.SyncService] needs. */
    fun deps(): WatchServiceDeps = WatchServiceDeps(
        portFactory = portFactory,
        driverFactory = driverFactory,
        handlers = handlers,
        appControl = driverFactory.appControl,
        messageSizer = GARMIN_SIZER,
    )

    private companion object {
        const val TAG = "WatchWiring"

        /** App message size as the garmin driver sends it (padded Monkey C serialization, before XXTEA). */
        val GARMIN_SIZER: MessageSizer = MessageSizer { v -> AppMessages.pad(CiqCodec.encode(v)).size }
    }
}

private class BleLinkHandle(private val conn: BleConnection) : LinkHandle {
    override val port: BlePort get() = conn

    override suspend fun connect(auto: Boolean): Outcome<Unit> = conn.connect(auto)

    override suspend fun close() {
        conn.close()
    }
}
