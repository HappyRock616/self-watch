/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.backup

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.interop.api.ExportRequest
import app.selfwatch.interop.api.ExportResult
import app.selfwatch.interop.api.Exporter
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.interop.db.AppFiles
import app.selfwatch.interop.io.JsonBuild
import app.selfwatch.interop.io.Zips
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.result.valueOrNull
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

/**
 * What `manifest.json` in a backup says.
 *
 * @property format backup layout version ([BackupFormat.VERSION]).
 * @property appVersion version name of the app that wrote it.
 * @property schemaVersion `PRAGMA user_version` of the database inside.
 * @property createdSec when it was made, UTC epoch seconds.
 * @property counts rows per table of the database inside.
 * @property files number of files under `workouts/` and `watchapps/`.
 */
data class BackupManifest(
    val format: Int,
    val appVersion: String,
    val schemaVersion: Int,
    val createdSec: Long,
    val counts: Map<String, Long>,
    val files: Int,
) {
    /** The manifest as JSON. */
    fun toJson(): String = Json.write(
        JsonBuild.obj(
            "app" to BackupFormat.APP, "format" to format, "appVersion" to appVersion, "schemaVersion" to schemaVersion,
            "createdSec" to createdSec, "created" to Instant.ofEpochSecond(createdSec).toString(),
            "counts" to JsonValue.Obj(counts.mapValues { JsonValue.Num.of(it.value) }), "files" to files,
        ),
    )

    /** Parsing. */
    companion object {
        /** Reads a manifest, or null if [text] isn't one of ours. */
        fun parse(text: String): BackupManifest? {
            val v = Json.parse(text).valueOrNull() ?: return null
            if (v["app"]?.asString != BackupFormat.APP) return null
            val counts = LinkedHashMap<String, Long>()
            v["counts"]?.asMap?.forEach { (k, n) -> n.asLong?.let { counts[k] = it } }
            return BackupManifest(
                format = v["format"]?.asLong?.toInt() ?: return null,
                appVersion = v["appVersion"]?.asString ?: "",
                schemaVersion = v["schemaVersion"]?.asLong?.toInt() ?: return null,
                createdSec = v["createdSec"]?.asLong ?: 0,
                counts = counts,
                files = v["files"]?.asLong?.toInt() ?: 0,
            )
        }
    }
}

/** Names inside a backup. */
object BackupFormat {
    /** Value of the manifest's `app` field. */
    const val APP: String = "selfwatch"

    /** Layout version written by this code. */
    const val VERSION: Int = 1

    /** The manifest entry (written first). */
    const val MANIFEST: String = "manifest.json"

    /** The database entry. */
    const val DATABASE: String = "selfwatch.db"

    /** Folders (under `filesDir`) copied into the backup with their paths. */
    val FOLDERS: List<String> = listOf(AppFiles.WORKOUTS, AppFiles.WATCHAPPS)
}

/**
 * How a consistent copy of the live database is taken while both processes may use it.
 *
 * - [VACUUM_INTO] (API 30+, SQLite 3.28+): `VACUUM INTO` a temp file. It reads inside one read
 *   transaction, so it sees one consistent state and never blocks writers; the copy is compact and in
 *   rollback-journal mode.
 * - [LOCKED_COPY] (API 26 to 29: SQLite 3.18 to 3.22 has no `VACUUM INTO`, and Android exposes no
 *   `sqlite3_backup`): take the write lock with `BEGIN IMMEDIATE` (no other connection in either process
 *   can commit, so the WAL can't grow or be reset and no auto-checkpoint runs), copy the main file and
 *   its `-wal` file, release the lock, then open the copy, `wal_checkpoint(TRUNCATE)` it and switch it to
 *   `journal_mode=DELETE`. A passive checkpoint that another connection might run during the copy only
 *   writes pages into the main file that are also in the copied WAL, which wins on recovery, so the pair
 *   is consistent. Writers in the other process wait (busy timeout and retries) while the files copy.
 */
enum class SnapshotMethod {
    /** `VACUUM INTO` (API 30+). */
    VACUUM_INTO,

    /** Locked file copy of the database and its WAL (any API). */
    LOCKED_COPY,
    ;

    /** Choice per API level. */
    companion object {
        /** The method for [sdkInt]. */
        fun forSdk(sdkInt: Int): SnapshotMethod = if (sdkInt >= Build.VERSION_CODES.R) VACUUM_INTO else LOCKED_COPY
    }
}

/**
 * Writes Self Watch backups: a zip of `manifest.json`, `selfwatch.db` (a consistent snapshot, see
 * [SnapshotMethod]), `workouts/…` and `watchapps/…`, optionally encrypted with [BackupCrypto] (SWBK).
 */
class BackupWriter(
    private val db: SelfWatchDb,
    private val files: AppFiles,
    private val cacheDir: File,
    private val appVersion: String,
    private val clock: Clock = SystemClock,
    private val method: SnapshotMethod = SnapshotMethod.forSdk(Build.VERSION.SDK_INT),
) {
    /** A writer for this process's database, files and cache. */
    constructor(ctx: Context) : this(SelfWatchDb.get(ctx), AppFiles(ctx.filesDir), ctx.cacheDir, appVersionOf(ctx))

    /** Writes a backup to [out]; encrypted when [passphrase] is given. Returns the manifest written. */
    suspend fun write(out: OutputSink, passphrase: CharArray? = null): Outcome<BackupManifest> = withContext(Dispatchers.IO) {
        val work = File(cacheDir, "backup-${System.nanoTime()}")
        try {
            if (!work.mkdirs()) return@withContext Outcome.Fail(SwError.Io("cannot create a temp folder"))
            val snap = File(work, BackupFormat.DATABASE)
            snapshot(db, snap, method)
            val tree = BackupFormat.FOLDERS.flatMap { dir -> listFiles(File(files.filesDir, dir)).map { "$dir/${it.first}" to it.second } }
            val manifest = BackupManifest(
                format = BackupFormat.VERSION, appVersion = appVersion, schemaVersion = userVersion(snap),
                createdSec = clock.nowSec(), counts = tableCounts(snap), files = tree.size,
            )
            if (passphrase == null) {
                out.open().use { os -> writeZip(os, manifest, snap, tree) }
            } else {
                val zip = File(work, "backup.zip")
                FileOutputStream(zip).use { os -> writeZip(os, manifest, snap, tree) }
                val r = FileInputStream(zip).use { input -> out.open().use { os -> BackupCrypto.encrypt(input, os, passphrase) } }
                if (r is Outcome.Fail) return@withContext r
            }
            SwLog.i(TAG, "backup written: ${tree.size} files, schema ${manifest.schemaVersion}, method $method")
            Outcome.Ok(manifest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.e(TAG, "backup failed", e)
            Outcome.Fail(SwError.Io("backup failed: ${e.javaClass.simpleName}"))
        } finally {
            work.deleteRecursively()
        }
    }

    private fun writeZip(os: java.io.OutputStream, manifest: BackupManifest, snap: File, tree: List<Pair<String, File>>) {
        Zips.write(os) {
            entry(BackupFormat.MANIFEST) { it.write(manifest.toJson().toByteArray(Charsets.UTF_8)) }
            entry(BackupFormat.DATABASE) { z -> FileInputStream(snap).use { it.copyTo(z) } }
            for ((name, f) in tree) entry(name) { z -> FileInputStream(f).use { it.copyTo(z) } }
        }
    }

    /** Helpers shared with restore and tests. */
    companion object {
        private const val TAG = "BackupWriter"

        /** Tables left out of counts: SQLite and Android internals and FTS shadow tables. */
        private fun countable(name: String): Boolean =
            !name.startsWith("sqlite_") && name != "android_metadata" && !name.startsWith("my_food_fts_")

        /** Writes a consistent single-file copy of [db] to [target] (which must not exist) with [method]. */
        fun snapshot(db: SelfWatchDb, target: File, method: SnapshotMethod) {
            if (target.exists() && !target.delete()) throw IOException("snapshot target exists")
            val live = db.writableDatabase
            when (method) {
                SnapshotMethod.VACUUM_INTO -> live.execSQL("VACUUM INTO '" + target.absolutePath.replace("'", "''") + "'")
                SnapshotMethod.LOCKED_COPY -> {
                    val path = File(live.path)
                    val wal = File(path.path + "-wal")
                    val walCopy = File(target.path + "-wal")
                    live.beginTransactionNonExclusive()
                    try {
                        live.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
                        path.copyTo(target, overwrite = true)
                        if (wal.isFile) wal.copyTo(walCopy, overwrite = true)
                    } finally {
                        live.endTransaction()
                    }
                    SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { c ->
                        c.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                        c.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
                    }
                    walCopy.delete()
                    File(target.path + "-shm").delete()
                    File(target.path + "-journal").delete()
                }
            }
        }

        /** `PRAGMA user_version` of the database file [f]. */
        fun userVersion(f: File): Int = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }

        /** Rows per user table of the database file [f]. */
        fun tableCounts(f: File): Map<String, Long> = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { tableCounts(it) }

        /** Rows per user table of [d], by name. */
        fun tableCounts(d: SQLiteDatabase): Map<String, Long> {
            val names = ArrayList<String>()
            d.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", null).use { c ->
                while (c.moveToNext()) c.getString(0).takeIf(::countable)?.let { names.add(it) }
            }
            val out = LinkedHashMap<String, Long>()
            for (n in names) d.rawQuery("SELECT count(*) FROM \"$n\"", null).use { c -> if (c.moveToFirst()) out[n] = c.getLong(0) }
            return out
        }

        /** Files under [dir], recursively, as (path relative to [dir] with `/`, file), sorted. */
        fun listFiles(dir: File): List<Pair<String, File>> {
            if (!dir.isDirectory) return emptyList()
            val base = dir.absoluteFile.toPath()
            return dir.walkTopDown().filter { it.isFile }.map { base.relativize(it.absoluteFile.toPath()).joinToString("/") to it }
                .sortedBy { it.first }.toList()
        }

        /** The app's version name, or "" if unknown. */
        fun appVersionOf(ctx: Context): String = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            }
            info.versionName ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            SwLog.w(TAG, "own package not found", e)
            ""
        }

        /** `selfwatch-backup-<date>.zip`, or `.swbak` when [encrypted]. */
        fun fileName(createdSec: Long, zone: ZoneId, encrypted: Boolean): String {
            val date = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochSecond(createdSec).atZone(zone))
            return "selfwatch-backup-$date." + if (encrypted) "swbak" else "zip"
        }
    }
}

/**
 * The "backup" exporter: [BackupWriter] behind the [Exporter] interface (the request's days are
 * ignored; a backup is always everything). Encrypted when [passphrase] gives a passphrase.
 */
class BackupExporter(private val writer: BackupWriter, private val passphrase: () -> CharArray? = { null }) : Exporter {
    override val id: String = "backup"

    override fun mimeType(req: ExportRequest): String = if (passphrase() != null) "application/octet-stream" else "application/zip"

    override fun suggestedName(req: ExportRequest): String = BackupWriter.fileName(req.createdSec, req.zone, passphrase() != null)

    override suspend fun export(req: ExportRequest, out: OutputSink): Outcome<ExportResult> =
        when (val r = writer.write(out, passphrase())) {
            is Outcome.Ok -> Outcome.Ok(ExportResult(mapOf("table" to r.value.counts.size, "file" to r.value.files)))
            is Outcome.Fail -> r
        }
}
