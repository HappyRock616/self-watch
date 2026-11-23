/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.data

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.food.FoodViews
import app.selfwatch.graph
import app.selfwatch.interop.backup.BackupCrypto
import app.selfwatch.interop.backup.BackupManifest
import app.selfwatch.interop.hc.HcAvailability
import app.selfwatch.interop.hc.HcPermissions
import app.selfwatch.interop.hc.HcStatus
import app.selfwatch.me.MeScreenActivity
import app.selfwatch.me.MeViews
import app.selfwatch.me.SwitchRow
import app.selfwatch.model.log.SwLog
import app.selfwatch.ui.format.Fmt
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Data (C18): Import, Export (sheet), Back up now and Restore (with a confirmation), automatic
 * backup to a picked folder and automatic GPX export (both through SAF tree permissions), and
 * Health Connect. Passphrases are asked for each manual backup and never stored unless the user
 * keeps one for automatic backups (Keystore-encrypted, see [KeystorePassphraseStore]).
 */
class DataActivity : MeScreenActivity() {

    private val vm: DataViewModel by viewModels { DataViewModel.factory(graph) }

    private var restoreDialog: AlertDialog? = null

    private val createBackup = registerForActivityResult(CreateFile()) { uri ->
        if (uri == null) vm.cancelBackup() else vm.backupTo(Saf.output(this, uri))
    }

    private val createExport = registerForActivityResult(CreateFile()) { uri ->
        if (uri == null) vm.cancelExport() else vm.runExport(Saf.output(this, uri))
    }

    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val head = withContext(Dispatchers.IO) { Saf.head(this@DataActivity, uri, HEAD) }
            val src = Saf.input(this@DataActivity, uri)
            if (BackupCrypto.isEncrypted(head)) {
                Saf.askPassphrase(this@DataActivity, getString(R.string.data_restore), getString(R.string.data_restore_passphrase), allowEmpty = false) {
                    vm.prepareRestore(src, it)
                }
            } else {
                vm.prepareRestore(src, null)
            }
        }
    }

    private val pickBackupFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && Saf.keepFolder(this, uri)) vm.setBackupFolder(uri.toString())
    }

    private val pickExportFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && Saf.keepFolder(this, uri)) vm.setExportFolder(uri.toString())
    }

    private val hcPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        vm.onHcPermissions(r.filterValues { it }.keys)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen(R.string.me_data)
        val progress = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            contentDescription = getString(R.string.data_working)
        }
        MeViews.add(column, progress, gap = false)

        // Import and export
        val io = MeViews.card(column, getString(R.string.data_move_title))
        MeViews.row(io.content, getString(R.string.data_import), getString(R.string.data_import_sub)) {
            startActivity(Intent(this, ImportActivity::class.java))
        }
        MeViews.row(io.content, getString(R.string.data_export), getString(R.string.data_export_sub)) {
            if (supportFragmentManager.findFragmentByTag(ExportSheet.TAG) == null) ExportSheet().show(supportFragmentManager, ExportSheet.TAG)
        }

        // Backup and restore
        val backup = MeViews.card(column, getString(R.string.data_backup_title))
        val last = MeViews.body(this, null)
        MeViews.add(backup.content, last, gap = false)
        MeViews.add(backup.content, FoodViews.filledButton(this, getString(R.string.data_backup_now)) { askBackup() })
        MeViews.add(backup.content, FoodViews.outlinedButton(this, getString(R.string.data_restore)) { openBackup.launch(arrayOf("*/*")) })
        MeViews.add(backup.content, MeViews.caption(this, getString(R.string.data_backup_help)))

        // Automatic
        val auto = MeViews.card(column, getString(R.string.data_auto_title))
        val autoBackup = SwitchRow(this, getString(R.string.data_auto_backup), getString(R.string.data_auto_backup_help))
        autoBackup.onChange = { on ->
            vm.setAutoBackup(on)
            if (on && vm.state.value.backupFolder == null) pickBackupFolder.launch(null)
        }
        MeViews.add(auto.content, autoBackup, gap = false)
        val backupFolder = MeViews.row(auto.content, getString(R.string.data_folder)) { pickBackupFolder.launch(null) }
        val every = MeViews.row(auto.content, getString(R.string.data_every)) {
            val labels = EVERY.map { resources.getQuantityString(R.plurals.data_days, it, it) }
            MeViews.choose(this, getString(R.string.data_every), labels, EVERY.indexOf(vm.state.value.everyDays)) { vm.setEveryDays(EVERY[it]) }
        }
        val encrypt = SwitchRow(this, getString(R.string.data_encrypt), getString(R.string.data_encrypt_help))
        encrypt.onChange = { vm.setEncrypt(it) }
        MeViews.add(auto.content, encrypt)
        val passRow = MeViews.row(auto.content, getString(R.string.data_stored_passphrase)) { passphraseOptions() }
        val autoGpx = SwitchRow(this, getString(R.string.data_auto_gpx), getString(R.string.data_auto_gpx_help))
        autoGpx.onChange = { on ->
            vm.setAutoGpx(on)
            if (on && vm.state.value.exportFolder == null) pickExportFolder.launch(null)
        }
        MeViews.add(auto.content, autoGpx)
        val exportFolder = MeViews.row(auto.content, getString(R.string.data_folder)) { pickExportFolder.launch(null) }

        // Health Connect
        val hc = MeViews.card(column, getString(R.string.me_health_connect))
        val hcText = MeViews.body(this, null)
        MeViews.add(hc.content, hcText, gap = false)
        val hcSwitch = SwitchRow(this, getString(R.string.data_hc_sync), getString(R.string.data_hc_sync_help))
        hcSwitch.onChange = { on ->
            if (on) {
                hcSwitch.checked = false
                requestHc()
            } else {
                vm.disableHc()
            }
        }
        MeViews.add(hc.content, hcSwitch)
        val hcSyncNow = FoodViews.outlinedButton(this, getString(R.string.data_hc_sync_now)) { vm.syncHc() }
        val hcSettings = FoodViews.textButton(this, getString(R.string.data_hc_open)) {
            HcAvailability.settingsIntent(this)?.let { launchSafely(it) }
        }
        for (b in listOf(hcSyncNow, hcSettings)) MeViews.add(hc.content, b)

        collect(vm.state) { s ->
            progress.visibility = if (s.busy) View.VISIBLE else View.GONE
            last.text = s.lastBackupSec?.let { getString(R.string.data_last_backup, whenText(it)) } ?: getString(R.string.data_no_backup)
            autoBackup.checked = s.autoBackup
            backupFolder.trailing = Saf.folderLabel(s.backupFolder) ?: getString(R.string.data_folder_none)
            every.trailing = resources.getQuantityString(R.plurals.data_days, s.everyDays, s.everyDays)
            encrypt.checked = s.encrypt
            passRow.trailing = getString(if (s.passphraseStored) R.string.data_passphrase_kept else R.string.data_passphrase_none)
            passRow.subtitle = if (s.autoBackupNeedsPassphrase) getString(R.string.data_passphrase_needed) else null
            for (v in listOf(backupFolder, every, encrypt, passRow)) v.visibility = if (s.autoBackup) View.VISIBLE else View.GONE
            passRow.visibility = if (s.autoBackup && s.encrypt) View.VISIBLE else View.GONE
            autoGpx.checked = s.autoGpx
            exportFolder.trailing = Saf.folderLabel(s.exportFolder) ?: getString(R.string.data_folder_none)
            exportFolder.visibility = if (s.autoGpx) View.VISIBLE else View.GONE
            renderHc(s, hcText, hcSwitch, hcSyncNow, hcSettings)
            showRestoreConfirm(s.pendingRestore)
            s.message?.let {
                toast(messageText(it))
                vm.messageShown()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshHc()
    }

    private fun renderHc(s: DataUiState, text: android.widget.TextView, sw: SwitchRow, syncNow: View, settings: View) {
        val available = s.hcStatus == HcStatus.AVAILABLE
        text.text = getString(
            when (s.hcStatus) {
                HcStatus.AVAILABLE -> if (s.hcEnabled) R.string.data_hc_on else R.string.data_hc_off
                HcStatus.UNSUPPORTED -> R.string.data_hc_unavailable
            },
        )
        sw.visibility = if (available) View.VISIBLE else View.GONE
        sw.checked = s.hcEnabled && s.hcGranted > 0
        syncNow.visibility = if (available && s.hcEnabled) View.VISIBLE else View.GONE
        settings.visibility = if (available) View.VISIBLE else View.GONE
    }

    private fun requestHc() {
        try {
            hcPermissions.launch(HcPermissions.build(write = true, read = true).toTypedArray())
        } catch (e: ActivityNotFoundException) {
            SwLog.w(TAG, "Health Connect permission screen missing", e)
            toast(getString(R.string.data_hc_unavailable))
        }
    }

    private fun askBackup() {
        Saf.askPassphrase(this, getString(R.string.data_backup_now), getString(R.string.data_backup_passphrase), allowEmpty = true) { pass ->
            val name = vm.startBackup(pass)
            createBackup.launch(CreateFile.Request(name, if (pass.isEmpty()) "application/zip" else "application/octet-stream"))
        }
    }

    /** Called by the [ExportSheet] with the user's choice. */
    fun startExport(exporterId: String, range: ExportRange, weightUnit: String) {
        val p = vm.chooseExport(exporterId, range, weightUnit) ?: return
        createExport.launch(CreateFile.Request(p.fileName, p.mimeType))
    }

    private fun passphraseOptions() {
        if (vm.state.value.passphraseStored) {
            MeViews.confirm(this, getString(R.string.data_stored_passphrase), getString(R.string.data_forget_passphrase_message), getString(R.string.data_forget_passphrase)) {
                vm.forgetPassphrase()
            }
        } else {
            Saf.askPassphrase(this, getString(R.string.data_stored_passphrase), getString(R.string.data_keep_passphrase_message), allowEmpty = false) {
                vm.storePassphrase(it)
            }
        }
    }

    private fun showRestoreConfirm(p: PendingRestore?) {
        if (p == null) {
            restoreDialog?.dismiss()
            restoreDialog = null
            return
        }
        if (restoreDialog != null) return
        restoreDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.data_restore_confirm_title)
            .setMessage(restoreMessage(p.manifest))
            .setPositiveButton(R.string.data_restore_confirm) { _, _ -> vm.confirmRestore() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> vm.cancelRestore() }
            .setOnCancelListener { vm.cancelRestore() }
            .setOnDismissListener { restoreDialog = null }
            .show()
    }

    private fun restoreMessage(m: BackupManifest): String {
        val rows = m.counts.values.sum()
        return getString(R.string.data_restore_confirm_message, whenText(m.createdSec), Fmt.number(rows), m.appVersion)
    }

    private fun messageText(m: DataMessage): String = when (m) {
        is DataMessage.BackedUp -> getString(if (m.encrypted) R.string.data_backed_up_encrypted else R.string.data_backed_up)
        is DataMessage.Restored -> getString(R.string.data_restored, whenText(m.manifest.createdSec))
        is DataMessage.Exported -> resources.getQuantityString(R.plurals.data_exported, m.items, m.items)
        DataMessage.NeedsPassphrase -> getString(R.string.data_restore_passphrase)
        is DataMessage.Failed -> getString(R.string.data_failed, m.detail)
        is DataMessage.HcSynced -> resources.getQuantityString(R.plurals.data_hc_synced, m.items, m.items)
        DataMessage.PassphraseNotStored -> getString(R.string.data_passphrase_not_kept)
    }

    private fun whenText(tSec: Long): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochSecond(tSec).atZone(ZoneId.systemDefault()))

    private fun launchSafely(i: Intent) {
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            SwLog.w(TAG, "no activity for ${i.action}", e)
            toast(getString(R.string.data_hc_unavailable))
        }
    }

    private companion object {
        const val TAG = "DataActivity"
        const val HEAD = 8
        val EVERY = listOf(1, 3, 7, 14, 30)
    }
}
