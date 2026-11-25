/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.SyncReason
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.service.device.DeviceKinds
import app.selfwatch.service.device.DeviceManager
import app.selfwatch.service.device.DeviceStatus
import app.selfwatch.service.device.RelayTargets
import app.selfwatch.service.device.StartResult
import app.selfwatch.service.device.WatchStatusPublisher
import app.selfwatch.service.device.AlarmSettings
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.model.watch.NotificationCategory
import app.selfwatch.model.watch.PhoneNotification
import app.selfwatch.service.health.AppFileStore
import app.selfwatch.service.health.DayRefreshReceiver
import app.selfwatch.service.health.HealthIngest
import app.selfwatch.service.health.SummaryBuilder
import app.selfwatch.service.notify.ServiceNotifications
import app.selfwatch.service.notify.ServiceStatus
import app.selfwatch.service.presence.PresenceObserver
import app.selfwatch.service.relay.CallRelay
import app.selfwatch.service.relay.ListenerHealth
import app.selfwatch.service.relay.RelayHub
import app.selfwatch.service.relay.TimeZoneWatcher
import app.selfwatch.service.relay.WatchBroadcaster
import app.selfwatch.service.relay.WeatherStore
import app.selfwatch.service.schedule.JobSyncScheduler
import app.selfwatch.service.link.CaloriesLeft
import app.selfwatch.service.link.LinkHub
import app.selfwatch.service.live.LiveReading
import app.selfwatch.service.live.LiveRelay
import app.selfwatch.service.link.WatchAccess
import app.selfwatch.service.watchapps.SideloadStore
import app.selfwatch.service.watchapps.WatchAppsCommander
import app.selfwatch.service.watchapps.WatchAppsStore
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * The always-on part (WP-07), in process `:watch`: a `connectedDevice` foreground service that owns
 * one [DeviceManager] and one [SummaryBuilder].
 *
 * Started by the app, [BootReceiver], the presence service (API 31+) and the sync-only job, always
 * through [SyncCommands]. It goes foreground only when [ForegroundRules] allow it (API 34
 * prerequisite) and otherwise stops itself cleanly. It stops when no device is paired, when a
 * sync-only cycle has finished, and on [SyncCommands.ACTION_STOP].
 *
 * Each watch's state and battery are published for the screens (another process) as settings
 * through [app.selfwatch.service.device.WatchStatusPublisher]; see [app.selfwatch.service.device.WatchStatus].
 *
 * Relays (WP-07): the service attaches a [WatchBroadcaster] to [RelayHub] so the notification
 * listener, the weather receiver and the relays below reach every connected watch. While it runs it
 * also owns the [CallRelay], the [TimeZoneWatcher], the food module's `REFRESH_DAY` receiver
 * ([DayRefreshReceiver]) and the notification-access "fix it" check ([ListenerHealth]), and sends the
 * stored weather ([WeatherStore]) to each watch as it connects.
 */
public class SyncService : LifecycleService() {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Mutex()
    private lateinit var db: SelfWatchDb
    private lateinit var summaries: SummaryBuilder
    private lateinit var devices: DeviceManager
    private lateinit var status: WatchStatusPublisher
    private var foreground = false
    private var ticks: Job? = null
    private var presenceRegistered = false
    private lateinit var calls: CallRelay
    private lateinit var timeWatcher: TimeZoneWatcher
    private lateinit var dayRefresh: DayRefreshReceiver
    private val broadcaster = WatchBroadcaster { cmd -> sendToConnected(cmd) }
    private var watchAccess: WatchAccess? = null
    private lateinit var commander: WatchAppsCommander
    private lateinit var live: LiveRelay

    override fun onCreate() {
        super.onCreate()
        ServiceNotifications.createChannels(this)
        val deps = (application as? WatchServiceHost)?.watchServiceDeps() ?: WatchServiceDeps.UNWIRED
        db = SelfWatchDb.get(this)
        summaries = SummaryBuilder(db, SystemClock, deps.restingKcal)
        devices = DeviceManager(
            db = db,
            clock = SystemClock,
            portFactory = deps.portFactory,
            driverFactory = deps.driverFactory,
            sink = HealthIngest(db, SystemClock, summaries) { id -> SyncCommands.workoutAdded(this, id) },
            files = AppFileStore(filesDir),
            scope = work,
            handlers = deps.handlers,
            scheduler = JobSyncScheduler(this),
        )
        status = WatchStatusPublisher(db.settings) { id, pct -> db.battery.add(id, SystemClock.nowSec(), pct) }
        lifecycleScope.launch {
            devices.devices.collect { list -> if (foreground) ServiceNotifications.update(this@SyncService, notification(list)) }
        }
        // The screens run in another process: they read the state from settings (see WatchStatus).
        work.launch { devices.devices.collect { status.publish(it) } }
        startRelays()
        startLink(deps)
        startLive()
    }

    /**
     * Live heart rate and steps: on only while a screen asks ([SyncCommands.ACTION_LIVE]); readings go to the
     * screens as a broadcast to this package ([LiveReading.BROADCAST]).
     */
    private fun startLive() {
        live = LiveRelay(
            scope = work,
            connected = { devices.devices.value.filter { isConnected(it.state) && DeviceKinds.isWatch(it.info.kind) }.map { it.info.id } },
            send = { id, cmd -> devices.send(id, cmd) },
            publish = { r -> sendBroadcast(LiveReading.toIntent(this, r)) },
            nowSec = { SystemClock.nowSec() },
            // Sensor priority: a connected strap's heart rate replaces the watch's on screen.
            isSensor = { id -> devices.devices.value.any { it.info.id == id && !DeviceKinds.isWatch(it.info.kind) } },
        )
        work.launch { devices.live.collect { (id, e) -> live.onEvent(id, e) } }
        work.launch {
            devices.devices.collect { list ->
                live.onConnected(list.filter { isConnected(it.state) && DeviceKinds.isWatch(it.info.kind) }.map { it.info.id })
            }
        }
    }

    /**
     * Link and watch apps (F05-F08, B26): the watches are offered to the link through [LinkHub], watch-app
     * requests from the screens run through the [WatchAppsCommander], and [CaloriesLeft] follows the day.
     */
    private fun startLink(deps: WatchServiceDeps) {
        val access = object : WatchAccess {
            override val devices: StateFlow<List<DeviceStatus>> get() = this@SyncService.devices.devices

            override suspend fun send(deviceId: Long, cmd: WatchCommand): Outcome<Unit> = this@SyncService.devices.send(deviceId, cmd)
        }
        watchAccess = access
        LinkHub.sizer = deps.messageSizer
        LinkHub.attach(access)
        LinkHub.router(this)
        val files = SideloadStore.forContext(this)
        commander = WatchAppsCommander(
            send = { id, cmd -> devices.send(id, cmd) },
            control = deps.appControl,
            store = WatchAppsStore(db, SystemClock, files),
            files = files,
            settings = db.settings,
            clock = SystemClock,
        )
        val calories = CaloriesLeft(db, SystemClock, send = { app, msg -> sendAppMessage(app, msg) })
        calories.start(work)
        // A watch that connects gets the current numbers.
        work.launch {
            val connected = HashSet<Long>()
            devices.devices.collect { list ->
                var fresh = false
                for (d in list) if (isConnected(d.state)) fresh = connected.add(d.info.id) || fresh else connected.remove(d.info.id)
                if (fresh) calories.poke()
            }
        }
    }

    /** Sends an app message to every connected watch; true when one took it. */
    private suspend fun sendAppMessage(app: UUID, msg: CiqValue): Boolean {
        var any = false
        for (d in devices.devices.value) {
            if (!isConnected(d.state) || !DeviceKinds.isWatch(d.info.kind)) continue
            if (devices.send(d.info.id, WatchCommand.AppMessage(app, msg)) is Outcome.Ok) any = true
        }
        return any
    }

    private fun startRelays() {
        RelayHub.attach(broadcaster)
        calls = CallRelay(this, db.settings, work)
        timeWatcher = TimeZoneWatcher(this, work).also { it.register() }
        dayRefresh = DayRefreshReceiver(this, work) { days -> summaries.refresh(days) }.also { it.register() }
        // Re-check the call listener when the setting changes (the permission is re-checked on every start).
        work.launch { db.settings.observe(SettingKeys.CALLS_ENABLED).collect { calls.refresh() } }
        work.launch { ListenerHealth(this@SyncService, db.settings).check() }
        work.launch { pushWeatherOnConnect() }
    }

    /**
     * Sends [cmd] to the watches [RelayTargets] picks (the [RelayHub] broadcaster): notifications,
     * calls and music to the primary connected watch, time and weather to every connected watch.
     */
    private suspend fun sendToConnected(cmd: WatchCommand) {
        for (id in RelayTargets.of(devices.devices.value, cmd)) {
            val r = devices.send(id, cmd)
            if (r is Outcome.Fail) SwLog.i(TAG, "relay to watch $id failed: ${r.error}")
        }
    }

    /** A watch that becomes connected gets the stored forecast (when weather is on and it is fresh). */
    private suspend fun pushWeatherOnConnect() {
        val store = WeatherStore(db.settings)
        val connected = HashSet<Long>()
        devices.devices.collect { list ->
            for (d in list) {
                if (!isConnected(d.state) || !DeviceKinds.isWatch(d.info.kind)) {
                    connected.remove(d.info.id)
                    continue
                }
                if (!connected.add(d.info.id)) continue
                if (!db.settings.getBool(SettingKeys.WEATHER_ENABLED)) continue
                val w = store.fresh(SystemClock.nowSec()) ?: continue
                work.launch { devices.send(d.info.id, w) }
            }
        }
    }

    private fun isConnected(s: ConnectionState): Boolean = s is ConnectionState.Ready || s is ConnectionState.Syncing

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // A null intent means Android restarted the sticky service after the process died.
        val action = intent?.action ?: SyncCommands.ACTION_START
        when (action) {
            SyncCommands.ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            SyncCommands.ACTION_REFRESH_DAY -> {
                val day = intent?.getIntExtra(SyncCommands.EXTRA_DAY, 0) ?: 0
                lifecycleScope.launch {
                    refreshDay(day)
                    if (!foreground) stopSelf(startId)
                }
                return if (foreground) START_STICKY else START_NOT_STICKY
            }
            SyncCommands.ACTION_DEVICE_DISAPPEARED -> {
                val address = intent?.getStringExtra(SyncCommands.EXTRA_ADDRESS)
                if (!foreground) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                // Keep the auto reconnect armed, but release the high-speed connection priority.
                if (address != null) work.launch { devices.setHighSpeed(address, false) }
                return START_STICKY
            }
            SyncCommands.ACTION_WATCH_APP -> {
                val req = intent?.let { SyncCommands.parseWatchApp(it) }
                if (req == null) {
                    SwLog.w(TAG, "malformed watch-app request")
                } else if (!foreground) {
                    // Not running: nothing is connected. Say so, then stop again.
                    work.launch { commander.refuse(req, SwError.NotConnected(req.deviceId)) }
                } else {
                    work.launch { commander.run(req) }
                }
                if (!foreground) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            SyncCommands.ACTION_LIVE -> {
                if (!foreground) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val enable = intent?.getBooleanExtra(SyncCommands.EXTRA_ENABLE, false) ?: false
                work.launch { live.request(enable) }
                return START_STICKY
            }
            SyncCommands.ACTION_FIND_WATCH, SyncCommands.ACTION_SET_ALARMS, SyncCommands.ACTION_TEST_NOTIFICATION -> {
                if (!foreground) {
                    SwLog.i(TAG, "watch command ignored: nothing connected")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val i = intent ?: return START_STICKY
                work.launch { watchCommand(i) }
                return START_STICKY
            }
        }
        if (!goForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        lifecycleScope.launch { handle(action) }
        return START_STICKY
    }

    override fun onDestroy() {
        foreground = false
        RelayHub.detach(broadcaster)
        work.launch { live.stop() }
        watchAccess?.let { LinkHub.detach(it) }
        calls.unregister()
        timeWatcher.unregister()
        dayRefresh.unregister()
        // Disconnect and mark every watch "off" for the screens, then cancel the rest (at most 2 s).
        val last = work.launch {
            withTimeoutOrNull(FINAL_STATUS_MS) {
                devices.stop()
                status.markAllOff()
            }
        }
        last.invokeOnCompletion { work.cancel() }
        super.onDestroy()
    }

    private suspend fun handle(action: String) {
        commands.withLock {
            val result = try {
                devices.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.e(TAG, "could not start the device manager", e)
                StartResult.NO_DEVICES
            }
            when (result) {
                StartResult.NO_DEVICES -> {
                    SwLog.i(TAG, "no paired watch; stopping")
                    shutdown()
                }
                StartResult.ALWAYS -> {
                    if (ticks == null) ticks = work.launch { summaries.runTicks() }
                    registerPresence()
                    if (action == SyncCommands.ACTION_SYNC_NOW) work.launch { devices.syncAll(SyncReason.USER) }
                }
                StartResult.SYNC_ONLY -> {
                    if (action != SyncCommands.ACTION_DEVICE_APPEARED) {
                        val reason = if (action == SyncCommands.ACTION_SCHEDULED_SYNC) SyncReason.SCHEDULE else SyncReason.USER
                        try {
                            devices.syncCycle(reason)
                            summaries.refreshToday()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            SwLog.e(TAG, "sync-only cycle failed", e)
                        }
                    }
                    shutdown()
                }
            }
        }
    }

    /** Find watch, alarms and the test notification (C19). */
    private suspend fun watchCommand(i: Intent) {
        val deviceId = i.getLongExtra(SyncCommands.EXTRA_DEVICE_ID, -1L)
        try {
            when (i.action) {
                SyncCommands.ACTION_FIND_WATCH -> {
                    val r = devices.send(deviceId, WatchCommand.FindWatch(i.getBooleanExtra(SyncCommands.EXTRA_RING, true)))
                    if (r is Outcome.Fail) SwLog.i(TAG, "find watch failed: ${r.error}")
                }
                SyncCommands.ACTION_SET_ALARMS -> {
                    val alarms = AlarmSettings.decode(db.settings.get(AlarmSettings.key(deviceId)))
                    val r = devices.send(deviceId, WatchCommand.SetAlarms(alarms))
                    val reason = (r as? Outcome.Fail)?.let { WatchStatus.reason(it.error) }
                    db.settings.set(AlarmSettings.resultKey(deviceId), AlarmSettings.encodeResult(r is Outcome.Ok, SystemClock.nowSec(), reason))
                    SwLog.i(TAG, "alarms to watch $deviceId: ${if (r is Outcome.Ok) "sent ${alarms.size}" else reason}")
                }
                SyncCommands.ACTION_TEST_NOTIFICATION -> sendToConnected(
                    WatchCommand.Notify(
                        PhoneNotification(
                            id = TEST_NOTIFICATION_ID,
                            appPackage = packageName,
                            appName = getString(R.string.svc_test_notify_app),
                            title = getString(R.string.svc_test_notify_title),
                            text = getString(R.string.svc_test_notify_text),
                            tSec = SystemClock.nowSec(),
                            category = NotificationCategory.OTHER,
                            canReply = false,
                            actions = emptyList(),
                        ),
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.e(TAG, "watch command ${i.action} failed", e)
        }
    }

    private suspend fun refreshDay(yyyymmdd: Int) {
        val day = Day(yyyymmdd)
        try {
            day.toLocalDate()
            summaries.refresh(setOf(day))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.w(TAG, "refresh of day $yyyymmdd failed", e)
        }
    }

    private fun registerPresence() {
        if (presenceRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        presenceRegistered = true
        work.launch {
            for (d in db.devices.active()) PresenceObserver.start(this@SyncService, d.address, db.devices.associationId(d.id))
        }
    }

    /** Goes foreground once; false when the rules forbid it or Android refuses. */
    private fun goForeground(): Boolean {
        if (foreground) return true
        if (!ForegroundRules.canStartForeground(this)) {
            SwLog.i(TAG, "no BLUETOOTH_CONNECT and no companion association; not starting")
            return false
        }
        val n = notification(devices.devices.value)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ServiceNotifications.ID_CONNECTION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ServiceNotifications.ID_CONNECTION, n)
            }
            foreground = true
            true
        } catch (e: SecurityException) {
            SwLog.w(TAG, "foreground start refused", e)
            false
        } catch (e: IllegalStateException) {
            // API 31+: ForegroundServiceStartNotAllowedException.
            SwLog.w(TAG, "foreground start refused", e)
            false
        }
    }

    private fun shutdown() {
        ticks?.cancel()
        ticks = null
        work.launch { devices.stop() }
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun notification(list: List<DeviceStatus>) =
        ServiceNotifications.connection(this, ServiceStatus.of(list), SystemClock.nowSec())

    private companion object {
        const val TAG = "SyncService"
        const val FINAL_STATUS_MS = 2_000L
        const val TEST_NOTIFICATION_ID = 0x5E1F
    }
}
