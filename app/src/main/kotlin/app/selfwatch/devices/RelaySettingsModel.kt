/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.Manifest
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.Table
import app.selfwatch.service.relay.RelaySettings
import app.selfwatch.service.relay.WeatherStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STOP_TIMEOUT_MS = 5_000L

/** Result of a "send to watch" tap. */
enum class SendOutcome {
    /** Handed to the service. */
    SENT,

    /** No watch connected right now. */
    NOT_CONNECTED,

    /** Android refused to deliver it. */
    REFUSED,
}

// ---- Notifications --------------------------------------------------------------------------------

/** Notifications settings state. */
data class NotificationSettingsUiState(
    /** True until the first load. */
    val loading: Boolean = true,
    /** The stored switch `notify.enabled`. */
    val wanted: Boolean = false,
    /** Notification access granted. */
    val access: Boolean = false,
    /** Launcher apps for the deny list. */
    val apps: List<PhoneApp> = emptyList(),
    /** Packages not relayed (`notify.apps_denied`). */
    val denied: Set<String> = emptySet(),
    /** The connected watch, if any. */
    val watch: ActiveWatch? = null,
) {
    /** What the master switch shows: on only when wanted and access is granted. */
    val on: Boolean get() = wanted && access
}

/**
 * Notifications settings (C19): the master switch (needs notification access, granted on the system
 * screen), the per-app deny list and "Send test notification".
 */
class NotificationSettingsViewModel(
    private val db: SelfWatchDb,
    private val perms: PermissionChecker,
    private val catalog: AppCatalog,
    private val commands: WatchCommands,
) : ViewModel() {
    private val tick = MutableStateFlow(0)
    private val apps = MutableStateFlow<List<PhoneApp>?>(null)

    /** Screen state. */
    val state: StateFlow<NotificationSettingsUiState> =
        combine(db.changes.observe(setOf(Table.SETTING, Table.DEVICE)).conflate(), tick, apps) { _, _, a -> a }
            .map { a ->
                NotificationSettingsUiState(
                    loading = a == null,
                    wanted = db.settings.getBool(SettingKeys.NOTIFY_ENABLED, true),
                    access = perms.notificationAccess(),
                    apps = a.orEmpty(),
                    denied = db.settings.getList(SettingKeys.NOTIFY_APPS_DENIED).toSet(),
                    watch = ActiveWatches.load(db),
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NotificationSettingsUiState())

    init {
        viewModelScope.launch { apps.value = withContext(Dispatchers.IO) { catalog.launcherApps() } }
    }

    /** Re-checks the access (after returning from the system screen). */
    fun recheck() {
        tick.value++
    }

    /**
     * The master switch. Turning it on stores the choice and returns true when notification access is
     * still needed (the screen explains and opens the system screen).
     */
    suspend fun setEnabled(on: Boolean): Boolean {
        db.settings.setBool(SettingKeys.NOTIFY_ENABLED, on)
        return on && !perms.notificationAccess()
    }

    /** Relays [pkg]'s notifications ([allowed]) or not. */
    suspend fun setAllowed(pkg: String, allowed: Boolean) {
        val denied = db.settings.getList(SettingKeys.NOTIFY_APPS_DENIED).toMutableSet()
        if (allowed) denied.remove(pkg) else denied.add(pkg)
        db.settings.setList(SettingKeys.NOTIFY_APPS_DENIED, denied.sorted())
    }

    /** "Send test notification". */
    suspend fun sendTest(): SendOutcome {
        val w = ActiveWatches.load(db)
        if (w == null || !w.connected) return SendOutcome.NOT_CONNECTED
        return if (commands.testNotification()) SendOutcome.SENT else SendOutcome.REFUSED
    }

    /** Factory. */
    companion object {
        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { NotificationSettingsViewModel(graph.db, graph.permissions, graph.appCatalog, graph.watchCommands) }
        }
    }
}

// ---- Calls ----------------------------------------------------------------------------------------

/** The call options, each with its own permissions. */
enum class CallOption {
    /** Call alerts on the watch (`calls.enabled`). */
    CALLS,

    /** Show the caller's number (`calls.show_number`). */
    NUMBER,

    /** Show the caller's name (`calls.show_name`). */
    NAME,
}

/** Calls settings state; each switch is on only when stored on and its permission is held. */
data class CallSettingsUiState(
    /** True until the first load. */
    val loading: Boolean = true,
    /** Call alerts. */
    val calls: Boolean = false,
    /** Accept / reject from the watch works. */
    val canAnswer: Boolean = false,
    /** Caller number. */
    val number: Boolean = false,
    /** Caller name. */
    val name: Boolean = false,
)

/**
 * Calls settings (C19). Permissions are asked when an option is turned on ([prepare] returns what to
 * ask for); after a grant [granted] stores the option, so the service (which follows `calls.enabled`)
 * re-checks the permission.
 */
class CallSettingsViewModel(
    private val db: SelfWatchDb,
    private val perms: PermissionChecker,
) : ViewModel() {
    private val tick = MutableStateFlow(0)

    /** Screen state. */
    val state: StateFlow<CallSettingsUiState> =
        combine(db.changes.observe(setOf(Table.SETTING)).conflate(), tick) { _, _ -> }
            .map { load() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CallSettingsUiState())

    /** Loads the state now. */
    suspend fun load(): CallSettingsUiState {
        val s = db.settings
        val watch = perms.granted(Manifest.permission.READ_PHONE_STATE)
        return CallSettingsUiState(
            loading = false,
            calls = s.getBool(SettingKeys.CALLS_ENABLED, true) && watch,
            canAnswer = perms.granted(Manifest.permission.ANSWER_PHONE_CALLS),
            number = s.getBool(SettingKeys.CALLS_SHOW_NUMBER) && missing(CallOption.NUMBER).isEmpty(),
            name = s.getBool(SettingKeys.CALLS_SHOW_NAME) && missing(CallOption.NAME).isEmpty(),
        )
    }

    /** Re-checks the permissions (after returning to the screen). */
    fun recheck() {
        tick.value++
    }

    /** The runtime permissions [option] uses. */
    fun required(option: CallOption): List<String> = when (option) {
        CallOption.CALLS -> listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
        CallOption.NUMBER -> if (perms.sdkInt >= Build.VERSION_CODES.Q) listOf(Manifest.permission.READ_CALL_LOG) else emptyList()
        CallOption.NAME -> listOf(Manifest.permission.READ_CONTACTS)
    }

    /** The permissions of [option] not yet granted. */
    fun missing(option: CallOption): List<String> = required(option).filterNot { perms.granted(it) }

    /**
     * The user flipped [option] to [on]. Off is stored at once. On is stored at once when the permissions
     * are held; otherwise the option is stored off and the missing permissions are returned to ask for.
     */
    suspend fun prepare(option: CallOption, on: Boolean): List<String> {
        if (!on) {
            store(option, false)
            return emptyList()
        }
        val missing = missing(option)
        store(option, missing.isEmpty())
        return missing
    }

    /** The permission answers for [option] came back. Returns true when the option is now on. */
    suspend fun granted(option: CallOption, results: Map<String, Boolean>): Boolean {
        val essential = when (option) {
            CallOption.CALLS -> Manifest.permission.READ_PHONE_STATE
            CallOption.NUMBER -> Manifest.permission.READ_CALL_LOG
            CallOption.NAME -> Manifest.permission.READ_CONTACTS
        }
        val ok = results[essential] ?: perms.granted(essential)
        // Stored off in prepare(), so this is an off → on change the service notices.
        store(option, ok)
        recheck()
        return ok
    }

    private suspend fun store(option: CallOption, on: Boolean) {
        val key = when (option) {
            CallOption.CALLS -> SettingKeys.CALLS_ENABLED
            CallOption.NUMBER -> SettingKeys.CALLS_SHOW_NUMBER
            CallOption.NAME -> SettingKeys.CALLS_SHOW_NAME
        }
        db.settings.setBool(key, on)
    }

    /** Factory. */
    companion object {
        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { CallSettingsViewModel(graph.db, graph.permissions) }
        }
    }
}

// ---- Weather --------------------------------------------------------------------------------------

/** Weather settings state. */
data class WeatherUiState(
    /** True until the first load. */
    val loading: Boolean = true,
    /** `weather.enabled`. */
    val enabled: Boolean = false,
    /** Time of the last forecast a weather app sent, UTC epoch seconds, or null. */
    val lastForecastSec: Long? = null,
)

/** Weather on watch (C19): the switch and the last forecast time. */
class WeatherViewModel(private val db: SelfWatchDb) : ViewModel() {

    /** Screen state. */
    val state: StateFlow<WeatherUiState> =
        db.changes.observe(setOf(Table.SETTING)).conflate()
            .map {
                WeatherUiState(
                    loading = false,
                    enabled = db.settings.getBool(SettingKeys.WEATHER_ENABLED),
                    lastForecastSec = db.settings.get(RelaySettings.WEATHER_LAST)?.let { WeatherStore.decode(it) }?.now?.tSec,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WeatherUiState())

    /** The switch. */
    fun setEnabled(on: Boolean) {
        viewModelScope.launch { db.settings.setBool(SettingKeys.WEATHER_ENABLED, on) }
    }

    /** Factory. */
    companion object {
        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { WeatherViewModel(graph.db) }
        }
    }
}
