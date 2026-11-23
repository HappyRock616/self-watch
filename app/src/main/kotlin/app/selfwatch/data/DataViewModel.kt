/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SettingsStore
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.Exporter
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.interop.api.WeightUnit
import app.selfwatch.interop.backup.BackupManifest
import app.selfwatch.interop.backup.BackupWriter
import app.selfwatch.interop.hc.HcKeys
import app.selfwatch.interop.hc.HcStatus
import app.selfwatch.interop.jobs.AutoJobs
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.DayRange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Date ranges offered by the export sheet. */
enum class ExportRange {
    /** Everything. */
    ALL,

    /** The last 30 days, today included. */
    LAST_30_DAYS,

    /** The last 365 days, today included. */
    LAST_YEAR,

    /** 1 January to today. */
    THIS_YEAR,
    ;

    /** The days this range covers when today is [today]; null for everything. */
    fun days(today: LocalDate): DayRange? = when (this) {
        ALL -> null
        LAST_30_DAYS -> DayRange(Day.of(today.minusDays(29)), Day.of(today))
        LAST_YEAR -> DayRange(Day.of(today.minusDays(364)), Day.of(today))
        THIS_YEAR -> DayRange(Day.of(today.withDayOfYear(1)), Day.of(today))
    }
}

/** An export chosen in the sheet, waiting for the user to pick where to save it. */
data class PendingExport(val exporterId: String, val request: ExportRequest, val fileName: String, val mimeType: String)

/** A backup that was read and checked, waiting for the user to confirm the restore. */
class PendingRestore internal constructor(
    /** The file. */
    val source: InputSource,
    internal val passphrase: CharArray?,
    /** What it contains. */
    val manifest: BackupManifest,
)

/** One-shot messages for the Data screen. */
sealed interface DataMessage {
    /** A backup was written. */
    data class BackedUp(val encrypted: Boolean) : DataMessage

    /** The restore finished. */
    data class Restored(val manifest: BackupManifest) : DataMessage

    /** An export was written; [items] is the number of rows or files. */
    data class Exported(val items: Int) : DataMessage

    /** The backup file is encrypted and needs its passphrase. */
    data object NeedsPassphrase : DataMessage

    /** Something failed; [detail] is short and technical. */
    data class Failed(val detail: String) : DataMessage

    /** Health Connect sync ran. */
    data class HcSynced(val items: Int) : DataMessage

    /** The passphrase could not be stored. */
    data object PassphraseNotStored : DataMessage
}

/** Everything the Data screen shows. */
data class DataUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val lastBackupSec: Long? = null,
    val autoBackup: Boolean = false,
    val backupFolder: String? = null,
    val everyDays: Int = DEFAULT_EVERY_DAYS,
    val encrypt: Boolean = true,
    val passphraseStored: Boolean = false,
    val autoGpx: Boolean = false,
    val exportFolder: String? = null,
    val hcStatus: HcStatus = HcStatus.UNSUPPORTED,
    val hcEnabled: Boolean = false,
    val hcGranted: Int = 0,
    val pendingRestore: PendingRestore? = null,
    val pendingExport: PendingExport? = null,
    val message: DataMessage? = null,
) {
    /** True when encrypted automatic backups would be skipped for lack of a stored passphrase. */
    val autoBackupNeedsPassphrase: Boolean get() = autoBackup && encrypt && !passphraseStored

    /** Constants. */
    companion object {
        /** Default days between automatic backups. */
        const val DEFAULT_EVERY_DAYS: Int = 7
    }
}

/**
 * Data screen (C18): back up now and restore (with a confirmation step: [prepareRestore] only reads
 * and checks the file; nothing is replaced until [confirmRestore]), the export sheet's choice and
 * run, automatic backup and GPX export settings (the app re-syncs [AutoJobs] whenever they change),
 * and Health Connect.
 */
class DataViewModel(
    private val settings: SettingsStore,
    private val backups: BackupOps,
    private val exporters: List<Exporter>,
    private val passphrases: PassphraseStore,
    private val hc: HcOps,
    private val newestWorkoutId: suspend () -> Long?,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {

    private val mutable = MutableStateFlow(DataUiState())

    /** Screen state. */
    val state: StateFlow<DataUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                listOf(
                    settings.observe(LAST_BACKUP_SEC), settings.observe(SettingKeys.BACKUP_AUTO),
                    settings.observe(SettingKeys.BACKUP_FOLDER_URI), settings.observe(SettingKeys.BACKUP_EVERY_DAYS),
                    settings.observe(SettingKeys.BACKUP_ENCRYPT), settings.observe(SettingKeys.EXPORT_AUTO_GPX),
                    settings.observe(SettingKeys.EXPORT_FOLDER_URI), settings.observe(HcKeys.ENABLED),
                ),
            ) { v -> v }.collect { v ->
                mutable.update {
                    it.copy(
                        loading = false,
                        lastBackupSec = v[0]?.toLongOrNull(),
                        autoBackup = v[1].toBool(false),
                        backupFolder = v[2]?.takeIf { s -> s.isNotEmpty() },
                        everyDays = v[3]?.toIntOrNull() ?: DataUiState.DEFAULT_EVERY_DAYS,
                        encrypt = v[4].toBool(true),
                        passphraseStored = passphrases.isStored(),
                        autoGpx = v[5].toBool(false),
                        exportFolder = v[6]?.takeIf { s -> s.isNotEmpty() },
                        hcEnabled = v[7].toBool(false),
                    )
                }
            }
        }
        refreshHc()
    }

    /** Re-reads Health Connect availability and permissions (call on resume). */
    fun refreshHc() {
        viewModelScope.launch {
            val status = hc.status()
            val granted = if (status == HcStatus.AVAILABLE) hc.granted().size else 0
            mutable.update { it.copy(hcStatus = status, hcGranted = granted, passphraseStored = passphrases.isStored()) }
        }
    }

    /** Clears the shown message. */
    fun messageShown() = mutable.update { it.copy(message = null) }

    // ---- Backup and restore -----------------------------------------------------------------------

    private var awaitingBackup = false
    private var heldPassphrase: CharArray? = null

    /**
     * Step 1 of "Back up now": keeps [passphrase] (empty or null: not encrypted) while the user picks
     * where to save, and returns the file name to offer.
     */
    fun startBackup(passphrase: CharArray?): String {
        heldPassphrase?.fill(' ')
        heldPassphrase = passphrase?.takeIf { it.isNotEmpty() }
        awaitingBackup = true
        return BackupWriter.fileName(nowSec(), zone(), heldPassphrase != null)
    }

    /**
     * Step 2: writes the backup to the picked [out]. Ignored unless [startBackup] ran in this view
     * model (so a backup the user asked to encrypt is never written in the clear after the process
     * was recreated).
     */
    fun backupTo(out: OutputSink) {
        if (!awaitingBackup) {
            mutable.update { it.copy(message = DataMessage.Failed("start the backup again")) }
            return
        }
        awaitingBackup = false
        val pass = heldPassphrase
        heldPassphrase = null
        backupNow(out, pass)
    }

    /** The user closed the picker. */
    fun cancelBackup() {
        awaitingBackup = false
        heldPassphrase?.fill(' ')
        heldPassphrase = null
    }

    override fun onCleared() {
        cancelBackup()
        mutable.value.pendingRestore?.passphrase?.fill(' ')
    }

    /** Writes a backup to [out]; encrypted when [passphrase] is not empty. The array is wiped. */
    private fun backupNow(out: OutputSink, passphrase: CharArray?) = work {
        val pass = passphrase?.takeIf { it.isNotEmpty() }
        val r = try {
            backups.write(out, pass)
        } finally {
            passphrase?.fill(' ')
        }
        when (r) {
            is Outcome.Ok -> {
                settings.setLong(LAST_BACKUP_SEC, nowSec())
                DataMessage.BackedUp(pass != null)
            }
            is Outcome.Fail -> DataMessage.Failed(r.error.shortText())
        }
    }

    /**
     * Step 1 of a restore: reads and checks the backup in [src] (with [passphrase] when it is
     * encrypted). On success the screen asks for confirmation ([DataUiState.pendingRestore]); nothing
     * changes until [confirmRestore].
     */
    fun prepareRestore(src: InputSource, passphrase: CharArray?) = work {
        when (val r = backups.inspect(src, passphrase)) {
            is Outcome.Ok -> {
                mutable.update { it.copy(pendingRestore = PendingRestore(src, passphrase, r.value)) }
                null
            }
            is Outcome.Fail -> {
                passphrase?.fill(' ')
                DataMessage.Failed(r.error.shortText())
            }
        }
    }

    /** Step 2: replaces everything with the confirmed backup. Does nothing without a pending restore. */
    fun confirmRestore() {
        val p = mutable.value.pendingRestore ?: return
        mutable.update { it.copy(pendingRestore = null) }
        work {
            val r = try {
                backups.restore(p.source, p.passphrase)
            } finally {
                p.passphrase?.fill(' ')
            }
            when (r) {
                is Outcome.Ok -> DataMessage.Restored(r.value)
                is Outcome.Fail -> DataMessage.Failed(r.error.shortText())
            }
        }
    }

    /** Drops the pending restore. */
    fun cancelRestore() {
        mutable.value.pendingRestore?.passphrase?.fill(' ')
        mutable.update { it.copy(pendingRestore = null) }
    }

    // ---- Export -----------------------------------------------------------------------------------

    /** The exporters the sheet offers, in its order. */
    val exportIds: List<String> get() = EXPORT_ORDER.filter { id -> exporters.any { it.id == id } }

    /** Step 1 of an export: remembers the choice and returns it (name and type for the file picker). */
    fun chooseExport(exporterId: String, range: ExportRange, weightUnit: String = WeightUnit.KG): PendingExport? {
        val e = exporters.firstOrNull { it.id == exporterId } ?: return null
        val now = nowSec()
        val z = zone()
        val today = java.time.Instant.ofEpochSecond(now).atZone(z).toLocalDate()
        val req = ExportRequest(days = range.days(today), weightUnit = weightUnit, zone = z, createdSec = now)
        val p = PendingExport(e.id, req, e.suggestedName(req), e.mimeType(req))
        mutable.update { it.copy(pendingExport = p) }
        return p
    }

    /** Step 2: writes the chosen export to [out]. */
    fun runExport(out: OutputSink) {
        val p = mutable.value.pendingExport ?: return
        mutable.update { it.copy(pendingExport = null) }
        val e = exporters.firstOrNull { it.id == p.exporterId } ?: return
        work {
            when (val r = e.export(p.request, out)) {
                is Outcome.Ok -> DataMessage.Exported(r.value.counts.values.sum())
                is Outcome.Fail -> DataMessage.Failed(r.error.shortText())
            }
        }
    }

    /** The user closed the file picker without choosing. */
    fun cancelExport() = mutable.update { it.copy(pendingExport = null) }

    // ---- Automatic jobs ---------------------------------------------------------------------------

    /** Turns automatic backup on or off. */
    fun setAutoBackup(on: Boolean) = save { settings.setBool(SettingKeys.BACKUP_AUTO, on) }

    /** Stores the picked backup folder (a tree URI the app holds a persisted permission for). */
    fun setBackupFolder(uri: String) = save { settings.set(SettingKeys.BACKUP_FOLDER_URI, uri) }

    /** Days between automatic backups. */
    fun setEveryDays(days: Int) = save { settings.setInt(SettingKeys.BACKUP_EVERY_DAYS, days.coerceIn(1, MAX_DAYS)) }

    /** Encrypt automatic backups (needs a stored passphrase). */
    fun setEncrypt(on: Boolean) = save { settings.setBool(SettingKeys.BACKUP_ENCRYPT, on) }

    /** Stores [passphrase] for automatic backups (Keystore-encrypted). The array is wiped. */
    fun storePassphrase(passphrase: CharArray) = work {
        val ok = try {
            passphrase.isNotEmpty() && passphrases.save(passphrase)
        } finally {
            passphrase.fill(' ')
        }
        mutable.update { it.copy(passphraseStored = passphrases.isStored()) }
        if (ok) null else DataMessage.PassphraseNotStored
    }

    /** Forgets the stored passphrase. */
    fun forgetPassphrase() = work {
        passphrases.clear()
        mutable.update { it.copy(passphraseStored = false) }
        null
    }

    /**
     * Turns automatic GPX export on or off. Turning it on starts from the newest workout already
     * stored, so only workouts recorded from now on are exported.
     */
    fun setAutoGpx(on: Boolean) = save {
        if (on && settings.getLong(AutoJobs.LAST_GPX_ID) == null) newestWorkoutId()?.let { settings.setLong(AutoJobs.LAST_GPX_ID, it) }
        settings.setBool(SettingKeys.EXPORT_AUTO_GPX, on)
    }

    /** Stores the picked export folder. */
    fun setExportFolder(uri: String) = save { settings.set(SettingKeys.EXPORT_FOLDER_URI, uri) }

    // ---- Health Connect ---------------------------------------------------------------------------

    /** Turns Health Connect sync off (turning it on goes through the permission screen first). */
    fun disableHc() = save { settings.setBool(HcKeys.ENABLED, false) }

    /** The permission screen returned [granted]; sync is turned on when anything was granted. */
    fun onHcPermissions(granted: Set<String>) {
        if (granted.isEmpty()) {
            refreshHc()
            return
        }
        work {
            settings.setBool(HcKeys.ENABLED, true)
            mutable.update { it.copy(hcGranted = hc.granted().size) }
            syncResult()
        }
    }

    /** Runs one Health Connect sync now. */
    fun syncHc() = work { syncResult() }

    private suspend fun syncResult(): DataMessage = when (val r = hc.sync()) {
        is Outcome.Ok -> DataMessage.HcSynced(r.value.written.values.sum() + r.value.read.values.sum())
        is Outcome.Fail -> DataMessage.Failed(r.error.shortText())
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun work(block: suspend () -> DataMessage?) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            val msg = try {
                block()
            } finally {
                mutable.update { it.copy(busy = false) }
            }
            if (msg != null) mutable.update { it.copy(message = msg) }
        }
    }

    private fun String?.toBool(fallback: Boolean): Boolean = this?.lowercase(Locale.ROOT)?.toBooleanStrictOrNull() ?: fallback

    /** Keys, order and factory. */
    companion object {
        /** Setting: when the last backup (manual or automatic) was written, UTC epoch seconds. */
        const val LAST_BACKUP_SEC: String = "backup.last_sec"

        /** The most days between automatic backups. */
        const val MAX_DAYS: Int = 90

        /** The export sheet's order. */
        val EXPORT_ORDER: List<String> = listOf(
            "gpx", "fit", "tcx", "cronometer-csv", "json", "garmin-body-csv", "fit-weight", "recipes-nextcloud", "foodlist-csv",
        )

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val i = graph.interop
                DataViewModel(
                    settings = graph.settings,
                    backups = i.backups,
                    exporters = i.exporters,
                    passphrases = i.passphrases,
                    hc = i.hc,
                    newestWorkoutId = { graph.db.workouts.list(1).firstOrNull()?.id },
                )
            }
        }
    }
}
