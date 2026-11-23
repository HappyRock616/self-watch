/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(ExperimentalCoroutinesApi::class)

package app.selfwatch.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.garmin.features.PrgFile
import app.selfwatch.garmin.features.PrgInfo
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.WatchAppKind
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.service.watchapps.SettingsDefinitionParser
import app.selfwatch.service.watchapps.SideloadStore
import app.selfwatch.service.watchapps.WatchAppOp
import app.selfwatch.service.watchapps.WatchAppRequest
import app.selfwatch.service.watchapps.WatchAppResult
import app.selfwatch.service.watchapps.WatchAppResults
import app.selfwatch.service.watchapps.WatchAppsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** One app on the watch as the list shows it. */
data class WatchAppItem(
    /** App UUID. */
    val uuid: UUID,
    /** Name, or null when the watch didn't give one. */
    val name: String?,
    /** Version, or null. */
    val version: Int?,
    /** Kind. */
    val kind: WatchAppKind,
)

/** A `.prg` picked and read, waiting for optional extras and the Install tap. */
data class PendingInstall(
    /** The file's name. */
    val fileName: String,
    /** UUID and type from the file. */
    val info: PrgInfo,
    /** Size in bytes. */
    val sizeBytes: Int,
    /** The settings definition's file name, when one was added. */
    val settingsName: String? = null,
    /** The strings file's name, when one was added. */
    val stringsName: String? = null,
)

/** What is running right now (shown as progress). */
data class WatchAppProgress(
    /** The request. */
    val op: WatchAppOp,
    /** The app's name or file name, if any. */
    val label: String?,
)

/** One-shot messages of the Watch apps screen (the fragment turns them into text). */
sealed interface WatchAppsMessage {
    /** The `.prg` couldn't be read. */
    data class BadPrg(val reason: String) : WatchAppsMessage

    /** The settings file couldn't be read. */
    data class BadSettings(val reason: String) : WatchAppsMessage

    /** The strings file has no strings. */
    data object BadStrings : WatchAppsMessage

    /** A request finished well. */
    data class Done(val op: WatchAppOp, val label: String?) : WatchAppsMessage

    /** A request failed. */
    data class Failed(val op: WatchAppOp, val reason: String?) : WatchAppsMessage

    /** No answer from the service in time. */
    data class NoAnswer(val op: WatchAppOp) : WatchAppsMessage

    /** Android refused to deliver the request. */
    data object Refused : WatchAppsMessage

    /** No watch is paired. */
    data object NoWatch : WatchAppsMessage
}

/** Watch apps screen state. */
data class WatchAppsUiState(
    /** True until the database has answered. */
    val loading: Boolean = true,
    /** The watch, or null when none is paired. */
    val watch: ActiveWatch? = null,
    /** Installed apps, by name. */
    val apps: List<WatchAppItem> = emptyList(),
    /** The install waiting for its Install tap, if any. */
    val pending: PendingInstall? = null,
    /** What is running, if anything. */
    val progress: WatchAppProgress? = null,
    /** A message to show once. */
    val message: WatchAppsMessage? = null,
)

/**
 * Watch apps (C19): the list from `watch_app`, refresh, install from file (the `.prg` plus optional
 * settings definition and strings), open on watch and delete. Requests go to the service through
 * [commands]; results come back as the setting [WatchAppResults.key].
 */
class WatchAppsViewModel(
    private val db: SelfWatchDb,
    private val commands: WatchCommands,
    private val files: SideloadStore,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
    private val answerTimeoutMs: Long = ANSWER_TIMEOUT_MS,
) : ViewModel() {

    private class Bytes(val prg: ByteArray, var settings: String? = null, var strings: String? = null)

    private val pending = MutableStateFlow<PendingInstall?>(null)
    private var pendingBytes: Bytes? = null
    private val progress = MutableStateFlow<WatchAppProgress?>(null)
    private val message = MutableStateFlow<WatchAppsMessage?>(null)
    private class Waiting(val progress: WatchAppProgress, val silent: Boolean, val timer: Job)

    private val requests = LinkedHashMap<String, Waiting>()

    private val watch = ActiveWatches.observe(db)

    private val apps = watch.flatMapLatest { w ->
        if (w == null) {
            flowOf(emptyList())
        } else {
            db.changes.observe(setOf(Table.WATCH_APP)).conflate().map { load(w.id) }
        }
    }

    /** Screen state. */
    val state: StateFlow<WatchAppsUiState> =
        combine(watch, apps, pending, progress, message) { w, list, p, pr, m ->
            WatchAppsUiState(loading = false, watch = w, apps = if (w == null) emptyList() else list, pending = p, progress = pr, message = m)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WatchAppsUiState())

    init {
        viewModelScope.launch {
            watch.flatMapLatest { w ->
                if (w == null) emptyFlow() else db.settings.observe(WatchAppResults.key(w.id)).map { WatchAppResults.decode(it) }
            }.collect { r -> if (r != null) onResult(r) }
        }
    }

    private suspend fun load(deviceId: Long): List<WatchAppItem> =
        db.watchApps.forDevice(deviceId)
            .map { WatchAppItem(it.appUuid, it.name, it.version, WatchAppsStore.kindOf(it.kind)) }
            .sortedWith(compareBy({ (it.name ?: "￿").lowercase() }, { it.uuid.toString() }))

    /** Marks the message as shown. */
    fun messageShown() {
        message.value = null
    }

    // ---- Install from file ----------------------------------------------------------------------

    /** The user picked [fileName] with [bytes] as the `.prg`. */
    suspend fun pickPrg(fileName: String, bytes: ByteArray) {
        when (val r = withContext(Dispatchers.Default) { PrgFile.parse(bytes) }) {
            is Outcome.Ok -> {
                pendingBytes = Bytes(bytes)
                pending.value = PendingInstall(fileName, r.value, bytes.size)
            }
            is Outcome.Fail -> message.value = WatchAppsMessage.BadPrg(WatchStatus.reason(r.error))
        }
    }

    /** The user added [fileName] as the settings definition ([text]). */
    suspend fun addSettings(fileName: String, text: String) {
        val p = pending.value ?: return
        val b = pendingBytes ?: return
        when (val r = withContext(Dispatchers.Default) { SettingsDefinitionParser.parse(text) }) {
            is Outcome.Ok -> {
                b.settings = text
                pending.value = p.copy(settingsName = fileName)
            }
            is Outcome.Fail -> message.value = WatchAppsMessage.BadSettings(WatchStatus.reason(r.error))
        }
    }

    /** The user added [fileName] as the app's strings resource ([text]). */
    suspend fun addStrings(fileName: String, text: String) {
        val p = pending.value ?: return
        val b = pendingBytes ?: return
        val strings = withContext(Dispatchers.Default) { SettingsDefinitionParser.parseStrings(text) }
        if (strings.isEmpty()) {
            message.value = WatchAppsMessage.BadStrings
            return
        }
        b.strings = text
        pending.value = p.copy(stringsName = fileName)
    }

    /** Drops the waiting install. */
    fun cancelInstall() {
        pending.value = null
        pendingBytes = null
    }

    /** Stores the waiting install's files and asks the service to install it. */
    suspend fun install() {
        val p = pending.value ?: return
        val b = pendingBytes ?: return
        val w = state.value.watch ?: ActiveWatches.load(db)
        if (w == null) {
            message.value = WatchAppsMessage.NoWatch
            return
        }
        when (val saved = files.save(p.info.appUuid, b.prg, b.settings, b.strings)) {
            is Outcome.Fail -> {
                message.value = WatchAppsMessage.Failed(WatchAppOp.INSTALL, WatchStatus.reason(saved.error))
                return
            }
            is Outcome.Ok -> Unit
        }
        cancelInstall()
        send(w.id, WatchAppOp.INSTALL, p.info.appUuid, p.fileName)
    }

    // ---- Per-app actions ------------------------------------------------------------------------

    /** Re-reads the app list from the watch. */
    fun refresh() {
        withWatch { send(it.id, WatchAppOp.REFRESH, null, null) }
    }

    /** Opens [app] on the watch. */
    fun launch(app: WatchAppItem) {
        withWatch { send(it.id, WatchAppOp.LAUNCH, app.uuid, app.name) }
    }

    /** Deletes [app] from the watch (after the screen confirmed). */
    fun delete(app: WatchAppItem) {
        withWatch { send(it.id, WatchAppOp.DELETE, app.uuid, app.name) }
    }

    private fun withWatch(block: (ActiveWatch) -> Unit) {
        val w = state.value.watch
        if (w == null) message.value = WatchAppsMessage.NoWatch else block(w)
    }

    private fun send(deviceId: Long, op: WatchAppOp, app: UUID?, label: String?, silent: Boolean = false) {
        val id = newRequestId()
        val pr = WatchAppProgress(op, label)
        if (!commands.watchApp(WatchAppRequest(op, deviceId, app, id))) {
            if (!silent) message.value = WatchAppsMessage.Refused
            return
        }
        val wait = if (op == WatchAppOp.INSTALL) answerTimeoutMs * INSTALL_TIMEOUT_FACTOR else answerTimeoutMs
        val timer = viewModelScope.launch {
            delay(wait)
            if (requests.remove(id) != null) {
                if (!silent) message.value = WatchAppsMessage.NoAnswer(op)
                progress.value = requests.values.lastOrNull()?.progress
            }
        }
        requests[id] = Waiting(pr, silent, timer)
        progress.value = pr
    }

    private fun onResult(r: WatchAppResult) {
        val w = requests.remove(r.requestId) ?: return
        w.timer.cancel()
        progress.value = requests.values.lastOrNull()?.progress
        if (!w.silent) {
            message.value = if (r.ok) WatchAppsMessage.Done(r.op, w.progress.label) else WatchAppsMessage.Failed(r.op, r.reason)
        }
        // After an install or delete the list changed on the watch: read it again (quietly).
        if (r.ok && (r.op == WatchAppOp.INSTALL || r.op == WatchAppOp.DELETE)) {
            state.value.watch?.let { s -> send(s.id, WatchAppOp.REFRESH, null, null, silent = true) }
        }
    }

    /** Factory and limits. */
    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** How long to wait for the service's answer (installs wait [INSTALL_TIMEOUT_FACTOR] times longer). */
        const val ANSWER_TIMEOUT_MS: Long = 45_000L

        /** Installs move the whole app over Bluetooth. */
        const val INSTALL_TIMEOUT_FACTOR: Int = 4

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { WatchAppsViewModel(graph.db, graph.watchCommands, graph.sideload) }
        }
    }
}
