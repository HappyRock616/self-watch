/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch

import android.content.Context
import app.selfwatch.db.DbChanges
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingsStore
import app.selfwatch.devices.AndroidPermissionChecker
import app.selfwatch.devices.AppCatalog
import app.selfwatch.devices.AppDeviceActions
import app.selfwatch.devices.PackageManagerCatalog
import app.selfwatch.devices.PermissionChecker
import app.selfwatch.devices.ServiceWatchCommands
import app.selfwatch.devices.WatchCommands
import app.selfwatch.service.watchapps.SideloadStore
import app.selfwatch.devices.DbDeviceList
import app.selfwatch.devices.DbDevicesSource
import app.selfwatch.devices.DeviceList
import app.selfwatch.devices.DeviceActions
import app.selfwatch.devices.DevicesSource
import app.selfwatch.garmin.models.GarminModels
import app.selfwatch.model.result.valueOrNull
import app.selfwatch.watch.WatchSettings
import app.selfwatch.model.net.Http
import app.selfwatch.net.Firewall
import app.selfwatch.net.NetClient
import app.selfwatch.net.NetworkLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's shared objects, each built on first use (WP-10 "Wiring"). Lives as long as the main
 * process; never built in the `:watch` process.
 */
class AppGraph(ctx: Context) {
    private val app: Context = ctx.applicationContext

    private val dbLazy = lazy { SelfWatchDb.get(app) }
    private val foodLazy = lazy { FoodGraph(app, db, http) }
    private val interopLazy = lazy { InteropGraph(app, db) }

    /** The database (this process's instance). */
    val db: SelfWatchDb by dbLazy

    /** Settings. */
    val settings: SettingsStore by lazy { db.settings }

    /** Change notices from every process. */
    val changes: DbChanges by lazy { db.changes }

    /** The only HTTP client: firewall and network log built in. */
    val http: Http by lazy { NetClient(app, Firewall(settings), NetworkLog(db)) }

    /** Commands to the always-on service. */
    val service: ServiceClient by lazy { ServiceClient(app) }

    /** The Garmin model table (`models.json`), loaded on first use. */
    val garminModels: GarminModels? by lazy { GarminModels.load().valueOrNull() }

    /** What the Devices screen reads: the paired watch, its published state, data counts. */
    val devicesSource: DevicesSource by lazy { DbDevicesSource(db, { garminModels }, WatchSettings.isDebuggable(app)) }

    /** What the Devices screen does: save a pairing, sync, forget. */
    val deviceActions: DeviceActions by lazy { AppDeviceActions(app, db, service, { garminModels }) }

    /** Every paired device in order (Devices tab, Device order, Other devices). */
    val deviceList: DeviceList by lazy { DbDeviceList(app, db, service) }

    /** Which permissions and accesses the app holds (Devices settings, C19). */
    val permissions: PermissionChecker by lazy { AndroidPermissionChecker(app) }

    /** Watch-app requests, find watch, alarms and the test notification (C19). */
    val watchCommands: WatchCommands by lazy { ServiceWatchCommands(app) }

    /** Installed phone apps (notification deny list, linked apps). */
    val appCatalog: AppCatalog by lazy { PackageManagerCatalog(app) }

    /** Sideloaded watch-app files (`files/watchapps/`). */
    val sideload: SideloadStore by lazy { SideloadStore.forContext(app) }

    /** Food sources, diary and recipe book: created only when a food screen asks (WP-11). */
    val food: FoodGraph by foodLazy

    /** Import, export, backup and Health Connect: created only when a Data screen asks (WP-12). */
    val interop: InteropGraph by interopLazy

    private val appearanceFlow = MutableStateFlow(Appearance())

    /** The Appearance settings as last read (dark and orange until the database answers). */
    val appearance: StateFlow<Appearance> = appearanceFlow.asStateFlow()

    /** Called by [SelfWatchApp] when the stored Appearance settings are read or change. */
    internal fun setAppearance(value: Appearance) {
        appearanceFlow.value = value
    }

    /** True once [db] has been opened. */
    val isDbBuilt: Boolean get() = dbLazy.isInitialized()

    /** True once [food] has been built. */
    val isFoodBuilt: Boolean get() = foodLazy.isInitialized()

    /** True once [interop] has been built. */
    val isInteropBuilt: Boolean get() = interopLazy.isInitialized()
}
