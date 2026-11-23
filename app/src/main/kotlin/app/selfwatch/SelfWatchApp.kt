/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch

import android.app.Application
import android.content.Context
import app.selfwatch.data.KeystorePassphraseStore
import app.selfwatch.db.SettingKeys
import app.selfwatch.interop.jobs.AutoJobs
import app.selfwatch.log.FileLogSink
import app.selfwatch.log.LogSinks
import app.selfwatch.model.log.SwLog
import app.selfwatch.service.WatchServiceDeps
import app.selfwatch.service.WatchServiceHost
import app.selfwatch.ui.SwTheme
import app.selfwatch.watch.WatchWiring
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application entry point. Every process gets the log sink; only the main process applies the theme
 * and may build the [AppGraph] (the `:watch` service process stays light, WP-10). In the `:watch`
 * process it wires the service ([WatchServiceHost]) with real Bluetooth and Garmin code.
 */
class SelfWatchApp : Application(), WatchServiceHost {

    private val graphLazy = lazy { AppGraph(this) }
    private val watchLazy = lazy { WatchWiring(this) }

    /** True once the `:watch` wiring exists (only ever in the `:watch` process). */
    val isWatchWiringBuilt: Boolean get() = watchLazy.isInitialized()

    /** Called by `SyncService` in the `:watch` process. Never builds [graph]. */
    override fun watchServiceDeps(): WatchServiceDeps = watchLazy.value.deps()

    /** Shared objects, built lazily. Never touch from the `:watch` process. */
    val graph: AppGraph by graphLazy

    /** True once [graph] exists. */
    val isGraphBuilt: Boolean get() = graphLazy.isInitialized()

    /** This process's role, decided in [onCreate]. */
    var role: ProcessRole = ProcessRole.MAIN
        private set

    /** This process's log file. */
    lateinit var logFile: FileLogSink
        private set

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> SwLog.e(TAG, "app scope failure", e) },
    )

    override fun onCreate() {
        super.onCreate()
        role = ProcessRole.of(ProcessRole.currentProcessName(), packageName)
        logFile = AppStartup.start(this, role, scope) { graph }
    }

    private companion object {
        const val TAG = "SelfWatchApp"
    }
}

/** What each process does at start. Kept apart from [SelfWatchApp] so tests can run the `:watch` path. */
object AppStartup {
    private const val TAG = "AppStartup"

    /**
     * Installs the log sink for [role]; in the main process only, applies the theme (dark at once, then
     * the saved Appearance setting) using the graph from [graph]. Other processes never call [graph].
     * Returns the process's log file.
     */
    fun start(ctx: Context, role: ProcessRole, scope: CoroutineScope, graph: () -> AppGraph): FileLogSink {
        val file = LogSinks.create(ctx, role.logName)
        SwLog.sink = LogSinks.withLogcatIfDebuggable(ctx, file)
        SwLog.i(TAG, "start: ${role.name}")
        if (role.doesAppSetup) {
            SwTheme.applyNightMode(SwTheme.DEFAULT_NIGHT_MODE)
            val g = graph()
            watchAppearance(g, scope)
            wireAutoJobs(ctx.applicationContext, g, scope)
        }
        return file
    }

    /**
     * Automatic backup and GPX export (WP-12): gives [AutoJobs] the Keystore-held passphrase (read only
     * when an encrypted automatic backup runs) and re-syncs the backup job now and whenever its
     * settings change, from any screen or a restore.
     */
    private fun wireAutoJobs(ctx: Context, g: AppGraph, scope: CoroutineScope) {
        val store = KeystorePassphraseStore(ctx)
        AutoJobs.passphrase = { store.load() }
        scope.launch {
            combine(
                g.settings.observe(SettingKeys.BACKUP_AUTO),
                g.settings.observe(SettingKeys.BACKUP_FOLDER_URI),
                g.settings.observe(SettingKeys.BACKUP_EVERY_DAYS),
            ) { a, b, c -> listOf(a, b, c) }
                .flowOn(Dispatchers.IO)
                .distinctUntilChanged()
                .collect { withContext(Dispatchers.IO) { AutoJobs.sync(ctx) } }
        }
    }

    private fun watchAppearance(g: AppGraph, scope: CoroutineScope) {
        scope.launch {
            // The old accent picker's values become the nearest color theme (once).
            withContext(Dispatchers.IO) {
                Appearance.migratedValue(g.settings.get(SettingKeys.ACCENT))?.let { g.settings.set(SettingKeys.ACCENT, it) }
            }
            combine(
                g.settings.observe(SettingKeys.THEME),
                g.settings.observe(SettingKeys.ACCENT),
            ) { theme, accent -> Appearance.parse(theme, accent) }
                .flowOn(Dispatchers.IO)
                .distinctUntilChanged()
                .collect { a ->
                    g.setAppearance(a)
                    SwTheme.applyNightMode(a.night)
                    SwTheme.setCurrent(a.theme) // open screens recreate themselves
                }
        }
    }
}

/** The app graph from any context in the main process. */
val Context.graph: AppGraph get() = (applicationContext as SelfWatchApp).graph
