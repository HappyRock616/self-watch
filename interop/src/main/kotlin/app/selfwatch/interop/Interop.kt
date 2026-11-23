/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop

import android.content.Context
import app.selfwatch.interop.api.ExportSource
import app.selfwatch.interop.api.Exporter
import app.selfwatch.interop.api.ImportRegistry
import app.selfwatch.interop.api.ImportSink
import app.selfwatch.interop.api.Importer
import app.selfwatch.interop.api.TrackFormat
import app.selfwatch.interop.backup.BackupExporter
import app.selfwatch.interop.backup.BackupRestore
import app.selfwatch.interop.backup.BackupWriter
import app.selfwatch.interop.backup.SelfWatchBackupImporter
import app.selfwatch.interop.backup.ServicePauser
import app.selfwatch.interop.db.DbExportSource
import app.selfwatch.interop.db.DbImportSink
import app.selfwatch.interop.device.GadgetbridgeImporter
import app.selfwatch.interop.device.GarminExportImporter
import app.selfwatch.interop.diary.CronometerExporter
import app.selfwatch.interop.diary.CronometerImporter
import app.selfwatch.interop.diary.FitWeightExporter
import app.selfwatch.interop.diary.GarminBodyCsvExporter
import app.selfwatch.interop.diary.JsonExporter
import app.selfwatch.interop.diary.LoseItImporter
import app.selfwatch.interop.diary.MfpImporter
import app.selfwatch.interop.food.FoodListExporter
import app.selfwatch.interop.food.FoodListImporter
import app.selfwatch.interop.recipe.NextcloudRecipeExporter
import app.selfwatch.interop.recipe.RecipeImporters
import app.selfwatch.interop.workout.WorkoutExporter
import app.selfwatch.interop.workout.WorkoutFileImporter
import java.io.File
import java.time.ZoneId

/**
 * Wiring for every importer and exporter in this module. The [Context] overloads connect them to
 * `selfwatch.db` ([DbImportSink], [DbExportSource], backup and restore); the others take the sink or
 * source explicitly (tests, previews).
 */
object Interop {
    /**
     * Every importer, writing through [sink]; local dates are read in [zone]. [cacheDir] holds temp
     * copies (the Gadgetbridge database); [restorer] restores Self Watch backups (null: not connected).
     */
    fun importers(
        sink: ImportSink = ImportSink.NONE,
        zone: ZoneId = ZoneId.systemDefault(),
        cacheDir: File = File(System.getProperty("java.io.tmpdir") ?: "."),
        restorer: BackupRestore? = null,
    ): List<Importer> = listOf(
        MfpImporter(sink, zone),
        LoseItImporter(sink, zone),
        CronometerImporter(sink, zone),
        WorkoutFileImporter(TrackFormat.FIT, sink),
        WorkoutFileImporter(TrackFormat.GPX, sink),
        WorkoutFileImporter(TrackFormat.TCX, sink),
        FoodListImporter(sink),
        GadgetbridgeImporter(sink, cacheDir),
        GarminExportImporter(sink, zone),
        SelfWatchBackupImporter(restorer),
    ) + RecipeImporters.all(sink)

    /** Every importer connected to this process's database ([DbImportSink]); [pauser] pauses the service for restores. */
    fun importers(ctx: Context, pauser: ServicePauser, zone: ZoneId = ZoneId.systemDefault()): List<Importer> =
        importers(DbImportSink(ctx, zone), zone, ctx.cacheDir, BackupRestore(ctx, pauser))

    /** A registry over [importers]. */
    fun registry(sink: ImportSink = ImportSink.NONE, zone: ZoneId = ZoneId.systemDefault()): ImportRegistry =
        ImportRegistry(importers(sink, zone))

    /** A registry over the database-connected [importers]. */
    fun registry(ctx: Context, pauser: ServicePauser, zone: ZoneId = ZoneId.systemDefault()): ImportRegistry =
        ImportRegistry(importers(ctx, pauser, zone))

    /** Every exporter reading from this process's database, plus "backup" (encrypted when [passphrase] gives one). */
    fun exporters(ctx: Context, zone: ZoneId = ZoneId.systemDefault(), passphrase: () -> CharArray? = { null }): List<Exporter> =
        exporters(DbExportSource(ctx, zone)) + BackupExporter(BackupWriter(ctx), passphrase)

    /** Every exporter, reading from [source]. */
    fun exporters(source: ExportSource): List<Exporter> = listOf(
        WorkoutExporter(TrackFormat.GPX, source),
        WorkoutExporter(TrackFormat.TCX, source),
        WorkoutExporter(TrackFormat.FIT, source),
        CronometerExporter(source),
        JsonExporter(source),
        GarminBodyCsvExporter(source),
        FitWeightExporter(source),
        NextcloudRecipeExporter(source),
        FoodListExporter(source),
    )
}
