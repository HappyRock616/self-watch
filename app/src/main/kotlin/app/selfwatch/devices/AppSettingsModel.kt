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
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.service.link.LinkValues
import app.selfwatch.service.watchapps.SettingControl
import app.selfwatch.service.watchapps.SettingField
import app.selfwatch.service.watchapps.SettingValueType
import app.selfwatch.service.watchapps.SettingsForm
import app.selfwatch.service.watchapps.WatchAppOp
import app.selfwatch.service.watchapps.WatchAppRequest
import app.selfwatch.service.watchapps.WatchAppResults
import app.selfwatch.service.watchapps.WatchAppsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong

/** How the settings editor shows one field. */
enum class EditorKind {
    /** A drop-down of the field's options. */
    DROPDOWN,

    /** An on/off switch. */
    SWITCH,

    /** A slider (whole numbers with a small min..max range). */
    SLIDER,

    /** A − / + stepper with a number field (other numbers). */
    STEPPER,

    /** A text field. */
    TEXT,

    /** Shown, not editable. */
    INFO,
}

/** The pure rules of the settings editor: which control a field gets, and values in and out. */
object SettingsEditor {
    /** Largest min..max span shown as a slider. */
    const val SLIDER_MAX_SPAN: Double = 100.0

    /** The control for [f]. */
    fun kindOf(f: SettingField): EditorKind = when {
        f.readOnly -> EditorKind.INFO
        f.control == SettingControl.LIST && f.options.isNotEmpty() -> EditorKind.DROPDOWN
        f.control == SettingControl.SWITCH || f.valueType == SettingValueType.BOOLEAN -> EditorKind.SWITCH
        f.valueType.numeric || f.control == SettingControl.NUMBER || f.control == SettingControl.DATE -> {
            val min = f.min
            val max = f.max
            if (integral(f) && min != null && max != null && max > min && max - min <= SLIDER_MAX_SPAN) EditorKind.SLIDER else EditorKind.STEPPER
        }
        else -> EditorKind.TEXT
    }

    /** True for whole-number fields. */
    fun integral(f: SettingField): Boolean =
        f.valueType == SettingValueType.NUMBER || f.valueType == SettingValueType.LONG ||
            (f.valueType == SettingValueType.UNKNOWN && f.control == SettingControl.DATE)

    /** [v] as a number in hundredths, or null when it isn't a number. */
    fun toX100(v: CiqValue?): Long? = when (v) {
        is CiqValue.Num -> v.v * 100
        is CiqValue.Dbl -> (v.v * 100).roundToLong()
        else -> null
    }

    /** A number in hundredths as the value [f] stores. */
    fun fromX100(f: SettingField, x100: Long): CiqValue =
        if (integral(f)) CiqValue.Num(Math.floorDiv(x100, 100L)) else CiqValue.Dbl(x100 / 100.0)

    /** Typed [text] as the value [f] stores, or null when it doesn't parse. */
    fun parse(f: SettingField, text: String): CiqValue? = when {
        f.valueType == SettingValueType.BOOLEAN -> text.trim().lowercase(Locale.ROOT).toBooleanStrictOrNull()?.let { CiqValue.Bool(it) }
        integral(f) -> text.trim().toLongOrNull()?.let { CiqValue.Num(it) }
        f.valueType.numeric -> text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let { CiqValue.Dbl(it) }
        else -> CiqValue.Str(text)
    }

    /** [v] as plain text (for text fields and read-only rows). */
    fun text(v: CiqValue?): String = when (v) {
        null, CiqValue.Null -> ""
        is CiqValue.Str -> v.v
        is CiqValue.Num -> v.v.toString()
        is CiqValue.Dbl -> v.v.toString()
        is CiqValue.Bool -> v.v.toString()
        else -> LinkValues.toJsonText(v)
    }

    /** Lower and upper bounds of a stepper for [f], in hundredths. */
    fun boundsX100(f: SettingField): LongRange {
        val lo = f.min?.let { (it * 100).roundToLong() } ?: -99_999_999L
        val hi = f.max?.let { (it * 100).roundToLong() } ?: 99_999_999L
        return lo..maxOf(lo, hi)
    }

    /**
     * What a save writes: every editable field of [form] with its value from [edits], else [base], else
     * its default. Read-only fields and fields without any value are left out.
     */
    fun valuesToSend(form: SettingsForm, base: Map<String, CiqValue>, edits: Map<String, CiqValue>): Map<String, CiqValue> {
        val out = LinkedHashMap<String, CiqValue>()
        for (f in form.fields) {
            if (f.readOnly) continue
            val v = edits[f.key] ?: base[f.key] ?: f.defaultValue ?: continue
            out[f.key] = v
        }
        return out
    }

    /** The JSON object the service writes ([WatchAppOp.WRITE_SETTINGS]). */
    fun valuesJson(values: Map<String, CiqValue>): String = LinkValues.settingsToJson(values)
}

/** One saved settings copy (for "Restore saved copy"). */
data class SavedCopy(
    /** When it was saved, UTC epoch seconds (its id). */
    val savedSec: Long,
    /** The values. */
    val values: Map<String, CiqValue>,
)

/** One-shot messages of the settings editor. */
sealed interface AppSettingsMessage {
    /** Written to the watch. */
    data object Saved : AppSettingsMessage

    /** Read from the watch. */
    data object Read : AppSettingsMessage

    /** A request failed. */
    data class Failed(val reason: String?) : AppSettingsMessage

    /** No answer in time. */
    data object NoAnswer : AppSettingsMessage

    /** Android refused to deliver the request. */
    data object Refused : AppSettingsMessage

    /** Some values don't fit their field. */
    data class Invalid(val keys: Set<String>) : AppSettingsMessage

    /** A saved copy was loaded into the form (not written yet). */
    data object Restored : AppSettingsMessage
}

/** Settings editor state. */
data class AppSettingsUiState(
    /** True until the first load. */
    val loading: Boolean = true,
    /** The form shown (the definition, or raw keys). */
    val form: SettingsForm = SettingsForm(emptyList(), false),
    /** True when the raw-key form is shown on purpose. */
    val raw: Boolean = false,
    /** The values shown: last known values with the user's edits on top. */
    val values: Map<String, CiqValue> = emptyMap(),
    /** Keys edited since the last load. */
    val edited: Set<String> = emptySet(),
    /** Keys whose values don't fit. */
    val invalid: Set<String> = emptySet(),
    /** Saved copies, newest first. */
    val history: List<SavedCopy> = emptyList(),
    /** The op running, if any. */
    val busy: WatchAppOp? = null,
    /** A message to show once. */
    val message: AppSettingsMessage? = null,
)

/**
 * Watch app settings (C19): renders the app's [SettingsForm] with its current values (asks the watch
 * with READ_SETTINGS, then follows `watch_app_settings`), saves to the watch (WRITE_SETTINGS), restores a
 * saved copy into the form, and offers the raw-key form.
 */
class AppSettingsViewModel(
    private val db: SelfWatchDb,
    private val commands: WatchCommands,
    private val formOf: suspend (UUID) -> SettingsForm,
    private val deviceId: Long,
    private val appUuid: UUID,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
    private val answerTimeoutMs: Long = WatchAppsViewModel.ANSWER_TIMEOUT_MS,
) : ViewModel() {

    private val ui = MutableStateFlow(AppSettingsUiState())

    /** Screen state. */
    val state: StateFlow<AppSettingsUiState> = ui.asStateFlow()

    private var definitionForm = SettingsForm(emptyList(), false)
    private var base: Map<String, CiqValue> = emptyMap()
    private val edits = LinkedHashMap<String, CiqValue>()
    private var waiting: Pair<String, WatchAppOp>? = null
    private var timer: Job? = null

    init {
        viewModelScope.launch {
            db.changes.observe(setOf(Table.WATCH_APP_SETTINGS)).conflate().collect { reload() }
        }
        viewModelScope.launch {
            db.settings.observe(WatchAppResults.key(deviceId)).collect { text ->
                val r = WatchAppResults.decode(text) ?: return@collect
                val w = waiting ?: return@collect
                if (r.requestId != w.first) return@collect
                waiting = null
                timer?.cancel()
                val m = when {
                    !r.ok -> AppSettingsMessage.Failed(r.reason)
                    r.op == WatchAppOp.WRITE_SETTINGS -> {
                        edits.clear()
                        AppSettingsMessage.Saved
                    }
                    else -> AppSettingsMessage.Read
                }
                ui.update { publish(it.copy(busy = null, message = m)) }
                reload()
            }
        }
        send(WatchAppOp.READ_SETTINGS, null)
    }

    private suspend fun reload() {
        try {
            definitionForm = formOf(appUuid)
            val history = db.watchApps.settingsHistory(appUuid).mapNotNull { s ->
                LinkValues.settingsFromJson(s.valuesJson)?.let { SavedCopy(s.savedSec, it) }
            }
            base = history.firstOrNull()?.values.orEmpty()
            ui.update { publish(it.copy(loading = false, history = history)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.w(TAG, "could not load the settings", e)
            ui.update { it.copy(loading = false) }
        }
    }

    private fun publish(s: AppSettingsUiState): AppSettingsUiState {
        val values = LinkedHashMap(base).apply { putAll(edits) }
        val useRaw = s.raw || definitionForm.fields.isEmpty()
        val form = if (useRaw) SettingsForm.fromValues(values) else definitionForm
        val shown = form.withValues(values).mapNotNull { (f, v) -> v?.let { f.key to it } }.toMap()
        val invalid = form.invalid(edits.filterKeys { form.field(it) != null })
        return s.copy(form = form, values = shown, edited = edits.keys.toSet(), invalid = invalid)
    }

    /** Marks the message as shown. */
    fun messageShown() {
        ui.update { it.copy(message = null) }
    }

    /** The user changed [key] to [value]. */
    fun set(key: String, value: CiqValue) {
        edits[key] = value
        ui.update { publish(it) }
    }

    /** Shows the raw-key form ([on]) or the definition. */
    fun setRaw(on: Boolean) {
        ui.update { publish(it.copy(raw = on)) }
    }

    /** Asks the watch for the current values again. */
    fun readAgain() {
        send(WatchAppOp.READ_SETTINGS, null)
    }

    /** Loads saved copy [savedSec] into the form (the user then saves it to the watch). */
    fun restore(savedSec: Long) {
        val copy = ui.value.history.firstOrNull { it.savedSec == savedSec } ?: return
        edits.clear()
        for ((k, v) in copy.values) if (base[k] != v) edits[k] = v
        ui.update { publish(it).copy(message = AppSettingsMessage.Restored) }
    }

    /** Writes the form to the watch, unless a value doesn't fit. */
    fun save() {
        val s = ui.value
        val values = SettingsEditor.valuesToSend(s.form, base, edits)
        val bad = s.form.invalid(values)
        if (bad.isNotEmpty()) {
            ui.update { it.copy(invalid = bad, message = AppSettingsMessage.Invalid(bad)) }
            return
        }
        send(WatchAppOp.WRITE_SETTINGS, SettingsEditor.valuesJson(values))
    }

    private fun send(op: WatchAppOp, json: String?) {
        val id = newRequestId()
        if (!commands.watchApp(WatchAppRequest(op, deviceId, appUuid, id, json))) {
            ui.update { it.copy(message = AppSettingsMessage.Refused) }
            return
        }
        waiting = id to op
        ui.update { it.copy(busy = op) }
        timer?.cancel()
        timer = viewModelScope.launch {
            delay(answerTimeoutMs)
            if (waiting?.first == id) {
                waiting = null
                ui.update { it.copy(busy = null, message = AppSettingsMessage.NoAnswer) }
            }
        }
    }

    /** Factory. */
    companion object {
        private const val TAG = "AppSettingsViewModel"

        /** Builds the view model for app [appUuid] on watch [deviceId] from [graph]. */
        fun factory(graph: AppGraph, deviceId: Long, appUuid: UUID): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val store = WatchAppsStore(graph.db, SystemClock, graph.sideload, { runCatching { Locale.getDefault().isO3Language }.getOrNull() })
                AppSettingsViewModel(graph.db, graph.watchCommands, { store.form(it) }, deviceId, appUuid)
            }
        }
    }
}
