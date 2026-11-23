/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch

import android.content.Context
import app.selfwatch.data.AndroidHcOps
import app.selfwatch.data.BackupOps
import app.selfwatch.data.HcOps
import app.selfwatch.data.KeystorePassphraseStore
import app.selfwatch.data.PassphraseStore
import app.selfwatch.data.WatchServicePauser
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.interop.Interop
import app.selfwatch.interop.api.Exporter
import app.selfwatch.interop.api.Importer
import app.selfwatch.interop.backup.BackupRestore
import app.selfwatch.interop.backup.BackupWriter
import app.selfwatch.interop.backup.ServicePauser
import app.selfwatch.interop.db.AppFiles
import app.selfwatch.interop.db.DbExportSource
import app.selfwatch.interop.db.DbImportSink
import java.time.ZoneId

/**
 * Import, export, backup and Health Connect (WP-12), built only when a Data screen first asks for
 * [AppGraph.interop].
 */
class InteropGraph internal constructor(
    /** Application context. */
    val ctx: Context,
    /** Database. */
    val db: SelfWatchDb,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Pauses the `:watch` service around a restore (stops it and ends its process). */
    val pauser: ServicePauser by lazy { WatchServicePauser(ctx) }

    /** Where imports are written; also undoes them. */
    val sink: DbImportSink by lazy { DbImportSink(db, AppFiles(ctx.filesDir), zone) }

    /**
     * Every importer, connected to the database. Self Watch backups are only detected here (no
     * restorer): restoring goes through the Data screen, which asks for the passphrase and confirms.
     */
    val importers: List<Importer> by lazy { Interop.importers(sink, zone, ctx.cacheDir, restorer = null) }

    /** Every exporter except the backup, reading from the database. */
    val exporters: List<Exporter> by lazy { Interop.exporters(DbExportSource(ctx, zone)) }

    /** Backup and restore. */
    val backups: BackupOps by lazy { BackupOps.of(BackupWriter(ctx), BackupRestore(ctx, pauser)) }

    /** The stored passphrase for automatic encrypted backups (Android Keystore). */
    val passphrases: PassphraseStore by lazy { KeystorePassphraseStore(ctx) }

    /** Health Connect. */
    val hc: HcOps by lazy { AndroidHcOps(ctx) }
}
