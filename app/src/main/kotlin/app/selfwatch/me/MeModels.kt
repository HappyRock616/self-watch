/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.Appearance
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SettingsStore
import app.selfwatch.db.dao.NetLogEntry
import app.selfwatch.model.net.NetFeature
import app.selfwatch.net.Firewall
import app.selfwatch.net.NetworkLog
import app.selfwatch.ui.SwTheme
import app.selfwatch.watch.WatchSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Requests today, for the Me card and the Privacy center. */
data class RequestsToday(val total: Int = 0, val blocked: Int = 0) {
    /** Counting. */
    companion object {
        /** Counts [entries]. */
        fun of(entries: List<NetLogEntry>): RequestsToday =
            RequestsToday(entries.size, entries.count { it.result == NetLogEntry.BLOCKED })
    }
}

/** Me view model: requests today for the privacy card and the current Appearance. */
class MeViewModel(
    /** Appearance settings. */
    val appearance: StateFlow<Appearance>,
    today: Flow<List<NetLogEntry>>,
) : ViewModel() {

    /** Requests made today. */
    val requests: StateFlow<RequestsToday> =
        today.map { RequestsToday.of(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), RequestsToday())

    /** Factory with the graph. */
    companion object {
        private const val STOP_MS = 5_000L

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { MeViewModel(graph.appearance, NetworkLog(graph.db).today()) }
        }
    }
}

/** A permission the app may hold, with whether it is granted now. */
data class HeldPermission(
    /** Which one. */
    val kind: PermissionKind,
    /** Granted now. */
    val granted: Boolean,
)

/** The permissions the Privacy center lists (only those this app declares and this Android has). */
enum class PermissionKind {
    NEARBY_DEVICES, NOTIFICATIONS, NOTIFICATION_ACCESS, CAMERA, PHONE, CALL_LOG, CONTACTS, ANSWER_CALLS, HEALTH_CONNECT,
}

/** Privacy center state. */
data class PrivacyUiState(
    val switches: Map<NetFeature, Boolean> = emptyMap(),
    val requests: RequestsToday = RequestsToday(),
    val permissions: List<HeldPermission> = emptyList(),
)

/** Privacy center (C20): a firewall switch per [NetFeature], requests today, permissions held. */
class PrivacyViewModel(
    private val firewall: Firewall,
    today: Flow<List<NetLogEntry>>,
    private val permissions: suspend () -> List<HeldPermission>,
) : ViewModel() {

    private val held = MutableStateFlow<List<HeldPermission>>(emptyList())

    /** Screen state. */
    val state: StateFlow<PrivacyUiState> = combine(firewall.observe(), today, held) { sw, t, p ->
        PrivacyUiState(sw, RequestsToday.of(t), p)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PrivacyUiState())

    init {
        refreshPermissions()
    }

    /** Allows or blocks [feature]. */
    fun setAllowed(feature: NetFeature, allowed: Boolean) {
        viewModelScope.launch { firewall.set(feature, allowed) }
    }

    /** Re-reads the permissions (call on resume: the user may have changed them in Android settings). */
    fun refreshPermissions() {
        viewModelScope.launch { held.value = permissions() }
    }

    /** Factory. */
    companion object {
        /** Builds the view model from [graph]; [permissions] reads what Android says. */
        fun factory(graph: AppGraph, permissions: suspend () -> List<HeldPermission>): ViewModelProvider.Factory = viewModelFactory {
            initializer { PrivacyViewModel(Firewall(graph.settings), NetworkLog(graph.db).today(), permissions) }
        }
    }
}

/** One day of the network log. */
data class NetDay(val date: LocalDate, val entries: List<NetLogEntry>) {
    /** Bytes up plus down. */
    val bytes: Long get() = entries.sumOf { it.bytesUp + it.bytesDown }
}

/** Network activity (C20): every request grouped by local day, newest first; clear and export. */
class NetworkLogViewModel(
    log: NetworkLog,
    private val clearAll: suspend () -> Unit,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {

    /** The log by day. */
    val days: StateFlow<List<NetDay>?> = log.recent(MAX_ROWS).map { group(it, zone()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Deletes the whole log. */
    fun clear() {
        viewModelScope.launch { clearAll() }
    }

    /** The log as CSV (what Export writes). */
    fun csv(): String = toCsv(days.value.orEmpty().flatMap { it.entries }, zone())

    /** Grouping and CSV. */
    companion object {
        /** The most rows shown and exported. */
        const val MAX_ROWS: Int = 2_000

        /** Groups [entries] (newest first) by local day in [zone]. */
        fun group(entries: List<NetLogEntry>, zone: ZoneId): List<NetDay> =
            entries.groupBy { Instant.ofEpochSecond(it.tSec).atZone(zone).toLocalDate() }
                .map { (d, e) -> NetDay(d, e) }
                .sortedByDescending { it.date }

        /** CSV with a header: time (ISO, local), feature, host, method, bytes up, bytes down, result. */
        fun toCsv(entries: List<NetLogEntry>, zone: ZoneId): String = buildString {
            append("time,feature,host,method,bytes_up,bytes_down,result\n")
            for (e in entries.sortedBy { it.tSec }) {
                append(Instant.ofEpochSecond(e.tSec).atZone(zone).toOffsetDateTime()).append(',')
                append(e.feature.name.lowercase()).append(',')
                append(e.host.replace(",", "")).append(',')
                append(e.method).append(',')
                append(e.bytesUp).append(',').append(e.bytesDown).append(',')
                append(resultText(e.result)).append('\n')
            }
        }

        /** The result column: the HTTP status, or `blocked`, `offline`, `error`. */
        fun resultText(result: Int): String = when (result) {
            NetLogEntry.BLOCKED -> "blocked"
            NetLogEntry.OFFLINE -> "offline"
            NetLogEntry.ERROR -> "error"
            else -> result.toString()
        }

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { NetworkLogViewModel(NetworkLog(graph.db), { graph.db.netLog.prune(Long.MAX_VALUE) }) }
        }
    }
}

/** Settings state. */
data class SettingsUiState(
    val loading: Boolean = true,
    val alwaysConnected: Boolean = true,
    val syncHours: Int = 4,
    val foodEnabled: Boolean = true,
    /** `phone`, `metric` or `imperial`. */
    val units: String = SettingsViewModel.UNITS_PHONE,
    val appearance: Appearance = Appearance(),
    val bleCapture: Boolean = false,
    val fakeAuth: Boolean = false,
    val debuggable: Boolean = false,
)

/**
 * Settings (C20): connection mode (always connected or sync only, with hours), food tracking,
 * units, light/dark and color theme, Bluetooth capture (debug builds) and the fake Garmin sign-in (off by
 * default; the screen shows the COMPATIBILITY R1 warning before turning it on).
 */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val debuggable: Boolean,
    private val onConnectionChanged: () -> Unit,
) : ViewModel() {

    private val mutable = MutableStateFlow(SettingsUiState(debuggable = debuggable))

    /** Screen state. */
    val state: StateFlow<SettingsUiState> = mutable.asStateFlow()

    init {
        val keys = listOf(
            SettingKeys.CONNECTION_MODE, SettingKeys.CONNECTION_SYNC_ONLY_HOURS, SettingKeys.FOOD_ENABLED, SettingKeys.UNITS,
            SettingKeys.THEME, SettingKeys.ACCENT, WatchSettings.BLE_CAPTURE, SettingKeys.GARMIN_FAKE_AUTH,
        )
        viewModelScope.launch {
            combine(keys.map { settings.observe(it) }) { it }.collect { v ->
                mutable.update {
                    it.copy(
                        loading = false,
                        alwaysConnected = v[0] != MODE_SYNC_ONLY,
                        syncHours = v[1]?.toIntOrNull() ?: DEFAULT_HOURS,
                        foodEnabled = v[2]?.toBooleanStrictOrNull() ?: true,
                        units = v[3] ?: UNITS_PHONE,
                        appearance = Appearance.parse(v[4], v[5]),
                        bleCapture = WatchSettings.bleCaptureOn(v[6], debuggable),
                        fakeAuth = v[7]?.toBooleanStrictOrNull() ?: false,
                    )
                }
            }
        }
    }

    /** Always connected (true) or sync only (false); the service re-reads the mode. */
    fun setAlwaysConnected(always: Boolean) = save {
        settings.set(SettingKeys.CONNECTION_MODE, if (always) MODE_ALWAYS else MODE_SYNC_ONLY)
        onConnectionChanged()
    }

    /** Hours between syncs in sync-only mode. */
    fun setSyncHours(hours: Int) = save {
        settings.setInt(SettingKeys.CONNECTION_SYNC_ONLY_HOURS, hours.coerceIn(1, MAX_HOURS))
        onConnectionChanged()
    }

    /** Food tracking on or off. */
    fun setFoodEnabled(on: Boolean) = save { settings.setBool(SettingKeys.FOOD_ENABLED, on) }

    /** `phone`, `metric` or `imperial`. */
    fun setUnits(units: String) = save { if (units in UNITS) settings.set(SettingKeys.UNITS, units) }

    /** Dark, light or system. */
    fun setTheme(mode: SwTheme.NightMode) = save { settings.set(SettingKeys.THEME, mode.name.lowercase()) }

    /** The color theme (stored under the `accent` key, see [Appearance]). */
    fun setColorTheme(theme: SwTheme.Theme) = save { settings.set(SettingKeys.ACCENT, Appearance.storedValue(theme)) }

    /** Bluetooth capture (debug builds only). */
    fun setBleCapture(on: Boolean) = save { if (debuggable) settings.setBool(WatchSettings.BLE_CAPTURE, on) }

    /** The fake Garmin sign-in (call only after the user accepted the warning). */
    fun setFakeAuth(on: Boolean) = save { settings.setBool(SettingKeys.GARMIN_FAKE_AUTH, on) }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    /** Values and factory. */
    companion object {
        /** Stored connection modes. */
        const val MODE_ALWAYS: String = "always"

        /** Sync only. */
        const val MODE_SYNC_ONLY: String = "sync_only"

        /** Units: follow the phone. */
        const val UNITS_PHONE: String = "phone"

        /** Units: metric. */
        const val UNITS_METRIC: String = "metric"

        /** Units: imperial. */
        const val UNITS_IMPERIAL: String = "imperial"

        /** Every units value, in display order. */
        val UNITS: List<String> = listOf(UNITS_PHONE, UNITS_METRIC, UNITS_IMPERIAL)

        /** Sync-only intervals offered. */
        val HOURS: List<Int> = listOf(1, 2, 4, 6, 12, 24)

        private const val DEFAULT_HOURS = 4
        private const val MAX_HOURS = 24

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph, debuggable: Boolean): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(graph.settings, debuggable) { graph.service.start() } }
        }
    }
}
