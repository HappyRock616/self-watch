/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.SystemClock
import app.selfwatch.service.device.DeviceKinds
import app.selfwatch.service.link.LinkSettings
import app.selfwatch.service.watchapps.WatchAppsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** One phone app's permission for one watch app. */
data class LinkedAppRow(
    /** Phone app package. */
    val packageName: String,
    /** Its label (the package name when unknown). */
    val label: String,
    /** The watch app it may message. */
    val appUuid: UUID,
    /** The watch app's name, when known. */
    val watchAppName: String?,
    /** Allowed (true) or refused. */
    val allowed: Boolean,
    /** Last message, UTC epoch seconds, or null. */
    val lastUsedSec: Long?,
)

/** Linked phone apps state. */
data class LinkedAppsUiState(
    /** True until the database has answered. */
    val loading: Boolean = true,
    /** The master switch `link.enabled`. */
    val enabled: Boolean = true,
    /** Every decision, by label. */
    val rows: List<LinkedAppRow> = emptyList(),
    /** The watch app that gets the calories left, or null when off. */
    val caloriesApp: UUID? = null,
    /** Watch apps to choose from for the calories (installed on the watch). */
    val watchApps: List<WatchAppItem> = emptyList(),
)

/**
 * Linked phone apps (C19): the `link_permission` rows with app labels, the watch app each may message,
 * last use, Allow / Revoke; the master switch `link.enabled`; the "Calories left on watch" app.
 */
class LinkedAppsViewModel(
    private val db: SelfWatchDb,
    private val catalog: AppCatalog,
    private val clock: Clock = SystemClock,
) : ViewModel() {

    /** Screen state. */
    val state: StateFlow<LinkedAppsUiState> =
        db.changes.observe(setOf(Table.LINK_PERMISSION, Table.SETTING, Table.WATCH_APP, Table.DEVICE))
            .conflate()
            .map { load() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LinkedAppsUiState())

    /** Loads everything once (also used by tests). */
    suspend fun load(): LinkedAppsUiState {
        val watch = db.devices.active().firstOrNull { DeviceKinds.isWatch(it.kind) }
        val known = watch?.let { db.watchApps.forDevice(it.id, includeRemoved = true) }.orEmpty()
        val names = known.associate { it.appUuid to it.name }
        val installed = known.filter { it.installed }
            .map { WatchAppItem(it.appUuid, it.name, it.version, WatchAppsStore.kindOf(it.kind)) }
            .sortedBy { (it.name ?: it.uuid.toString()).lowercase() }
        val perms = db.links.list()
        val labels = withContext(Dispatchers.IO) { perms.map { it.packageName }.distinct().associateWith { catalog.label(it) } }
        val rows = perms.map { p ->
            LinkedAppRow(p.packageName, labels[p.packageName] ?: p.packageName, p.appUuid, names[p.appUuid], p.allowed, p.lastUsedSec)
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.appUuid.toString() }))
        val calories = db.settings.get(LinkSettings.CALORIES_APP)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return LinkedAppsUiState(
            loading = false,
            enabled = db.settings.getBool(LinkSettings.ENABLED, true),
            rows = rows,
            caloriesApp = calories,
            watchApps = installed,
        )
    }

    /** The master switch. */
    fun setEnabled(on: Boolean) {
        viewModelScope.launch { db.settings.setBool(LinkSettings.ENABLED, on) }
    }

    /** Allows [row] again. */
    fun allow(row: LinkedAppRow) {
        viewModelScope.launch { decide(row, true) }
    }

    /** Revokes [row] (later messages are refused until allowed again). */
    fun revoke(row: LinkedAppRow) {
        viewModelScope.launch { decide(row, false) }
    }

    /** Stores the decision for [row]. */
    suspend fun decide(row: LinkedAppRow, allowed: Boolean) {
        val p = db.links.get(row.packageName, row.appUuid) ?: return
        db.links.set(p.copy(allowed = allowed, decidedSec = clock.nowSec()))
    }

    /** Chooses the watch app that gets the calories left; null turns it off. */
    fun setCaloriesApp(app: UUID?) {
        viewModelScope.launch { db.settings.set(LinkSettings.CALORIES_APP, app?.toString()) }
    }

    /** Factory. */
    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { LinkedAppsViewModel(graph.db, graph.appCatalog) }
        }
    }
}
