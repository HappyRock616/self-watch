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
import app.selfwatch.model.device.Feature
import app.selfwatch.model.watch.Alarm
import app.selfwatch.service.device.AlarmSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Alarms screen state. */
data class AlarmsUiState(
    /** True until the first load. */
    val loading: Boolean = true,
    /** The watch, or null when none is paired. */
    val watch: ActiveWatch? = null,
    /** The stored alarms, by time. */
    val alarms: List<Alarm> = emptyList(),
    /** How the last send went, or null. */
    val lastSend: AlarmSettings.SendResult? = null,
) {
    /** False when the watch is known not to support alarms. */
    val supported: Boolean get() = watch == null || FeatureGate.shows(watch.features, Feature.ALARMS)

    /** True when another alarm can be added. */
    val canAdd: Boolean get() = watch != null && supported && alarms.size < AlarmSettings.MAX
}

/** Alarms (C19): up to [AlarmSettings.MAX] alarms per watch kept in settings, sent with "Send to watch". */
class AlarmsViewModel(private val db: SelfWatchDb, private val commands: WatchCommands) : ViewModel() {

    /** Screen state. */
    val state: StateFlow<AlarmsUiState> =
        db.changes.observe(setOf(Table.SETTING, Table.DEVICE)).conflate()
            .map { load() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AlarmsUiState())

    /** Loads the state now. */
    suspend fun load(): AlarmsUiState {
        val w = ActiveWatches.load(db) ?: return AlarmsUiState(loading = false)
        return AlarmsUiState(
            loading = false,
            watch = w,
            alarms = sorted(AlarmSettings.decode(db.settings.get(AlarmSettings.key(w.id)))),
            lastSend = AlarmSettings.decodeResult(db.settings.get(AlarmSettings.resultKey(w.id))),
        )
    }

    /** Adds [alarm] (id 0) or replaces the alarm with its id. Returns false when the list is full or no watch. */
    suspend fun save(alarm: Alarm): Boolean {
        val w = ActiveWatches.load(db) ?: return false
        val list = AlarmSettings.decode(db.settings.get(AlarmSettings.key(w.id))).toMutableList()
        val i = list.indexOfFirst { it.id == alarm.id }
        if (alarm.id != 0 && i >= 0) {
            list[i] = alarm
        } else {
            if (list.size >= AlarmSettings.MAX) return false
            list += alarm.copy(id = (list.maxOfOrNull { it.id } ?: 0) + 1)
        }
        db.settings.set(AlarmSettings.key(w.id), AlarmSettings.encode(sorted(list)))
        return true
    }

    /** Turns alarm [id] on or off. */
    suspend fun setEnabled(id: Int, on: Boolean) {
        val a = state.value.alarms.firstOrNull { it.id == id } ?: load().alarms.firstOrNull { it.id == id } ?: return
        save(a.copy(enabled = on))
    }

    /** Deletes alarm [id]. */
    suspend fun delete(id: Int) {
        val w = ActiveWatches.load(db) ?: return
        val list = AlarmSettings.decode(db.settings.get(AlarmSettings.key(w.id))).filter { it.id != id }
        db.settings.set(AlarmSettings.key(w.id), AlarmSettings.encode(list))
    }

    /** "Send to watch". */
    suspend fun send(): SendOutcome {
        val w = ActiveWatches.load(db)
        if (w == null || !w.connected) return SendOutcome.NOT_CONNECTED
        return if (commands.setAlarms(w.id)) SendOutcome.SENT else SendOutcome.REFUSED
    }

    /** Factory and limits. */
    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** [alarms] by time of day. */
        fun sorted(alarms: List<Alarm>): List<Alarm> = alarms.sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { AlarmsViewModel(graph.db, graph.watchCommands) }
        }
    }
}
