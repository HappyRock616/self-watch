/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.interop.api.BaseImporter
import app.selfwatch.interop.api.DateFormatGuess
import app.selfwatch.interop.api.ImportChoices
import app.selfwatch.interop.api.ImportData
import app.selfwatch.interop.api.ImportHandle
import app.selfwatch.interop.api.ImportPlan
import app.selfwatch.interop.api.ImportResult
import app.selfwatch.interop.api.ImportSink
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.UnitGuess
import app.selfwatch.interop.db.AppFiles
import app.selfwatch.interop.io.Texts
import app.selfwatch.interop.io.TooLargeException
import app.selfwatch.interop.io.Zips
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pauses the always-on `:watch` service around a restore. The app wires the real one (a bound call
 * or broadcast that makes the service stop syncing and close its `SelfWatchDb` connection).
 */
interface ServicePauser {
    /** Asks the service to stop writing and close the database; true once it has (false aborts the restore). */
    suspend fun pause(): Boolean

    /** Lets the service reopen the database and carry on. Called after every [pause] that returned true. */
    suspend fun resume()

    /** Constants. */
    companion object {
        /** No service to pause (tests, or a process without the service). */
        val NONE: ServicePauser = object : ServicePauser {
            override suspend fun pause(): Boolean = true
            override suspend fun resume() = Unit
        }
    }
}

/**
 * Restores a Self Watch backup (plain zip or SWBK-encrypted).
 *
 * 1. Unpacks into a staging folder in [cacheDir] (decrypting first when the file starts with SWBK);
 *    entry names with `..` or absolute paths are refused.
 * 2. Validates: a manifest of ours, a database that passes `PRAGMA integrity_check` and whose
 *    `user_version` is between 1 and [SelfWatchDb.VERSION] (an older schema is fine: migrations run on
 *    open), with the core tables present.
 * 3. Swaps: [ServicePauser.pause], closes [db] in this process, moves the old database (with its WAL)
 *    aside, puts the new file in place and deletes stale `-wal`/`-shm`, reopens (migrations run) and only
 *    then replaces `workouts/` and `watchapps/`. Any failure puts the old database back.
 * 4. Notifies every table through `DbChanges`, deletes the old copies and resumes the service.
 */
class BackupRestore(
    private val db: SelfWatchDb,
    private val files: AppFiles,
    private val cacheDir: File,
    private val pauser: ServicePauser = ServicePauser.NONE,
) {
    /** A restorer for this process's database, files and cache. */
    constructor(ctx: Context, pauser: ServicePauser) : this(SelfWatchDb.get(ctx), AppFiles(ctx.filesDir), ctx.cacheDir, pauser)

    /** Reads and validates the backup in [src] without changing anything; returns its manifest. */
    suspend fun inspect(src: InputSource, passphrase: CharArray?): Outcome<BackupManifest> = withContext(Dispatchers.IO) {
        staged(src, passphrase) { s -> Outcome.Ok(s.manifest) }
    }

    /** Replaces the database and files with the backup in [src]. Returns the backup's manifest. */
    suspend fun restore(src: InputSource, passphrase: CharArray?): Outcome<BackupManifest> = withContext(Dispatchers.IO) {
        staged(src, passphrase) { s ->
            if (!pauser.pause()) return@staged Outcome.Fail(SwError.Io("the watch service did not pause"))
            try {
                swap(s)
                Outcome.Ok(s.manifest)
            } finally {
                pauser.resume()
            }
        }
    }

    private class Staged(val dir: File, val manifest: BackupManifest, val dbFile: File, val filesDir: File)

    private suspend fun staged(src: InputSource, passphrase: CharArray?, use: suspend (Staged) -> Outcome<BackupManifest>): Outcome<BackupManifest> {
        val dir = File(cacheDir, "restore-${System.nanoTime()}")
        return try {
            if (!dir.mkdirs()) return Outcome.Fail(SwError.Io("cannot create a temp folder"))
            when (val s = unpack(src, passphrase, dir)) {
                is Outcome.Fail -> s
                is Outcome.Ok -> use(s.value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SwLog.w(TAG, "restore failed", e)
            Outcome.Fail(SwError.BadInput("cannot read backup: ${e.javaClass.simpleName}"))
        } catch (e: Exception) {
            SwLog.e(TAG, "restore failed", e)
            Outcome.Fail(SwError.Io("restore failed: ${e.javaClass.simpleName}"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun unpack(src: InputSource, passphrase: CharArray?, dir: File): Outcome<Staged> {
        val head = src.open().use { input -> ByteArray(BackupCrypto.HEADER_BYTES).let { b -> b.copyOf(readUpTo(input, b)) } }
        val zipFile: File? = if (BackupCrypto.isEncrypted(head)) {
            if (passphrase == null || passphrase.isEmpty()) return Outcome.Fail(SwError.BadInput("this backup is encrypted: a passphrase is needed"))
            val f = File(dir, "backup.zip")
            val r = src.open().use { input -> FileOutputStream(f).use { out -> BackupCrypto.decrypt(input, out, passphrase) } }
            if (r is Outcome.Fail) return r
            f
        } else {
            if (!Zips.isZip(head)) return Outcome.Fail(SwError.BadInput("not a Self Watch backup"))
            null
        }
        val staging = File(dir, "files")
        val dbFile = File(dir, BackupFormat.DATABASE)
        var manifestText: String? = null
        var count = 0
        var total = 0L
        val open: () -> InputStream = { if (zipFile != null) FileInputStream(zipFile) else src.open() }
        ZipInputStream(BufferedInputStream(open())).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (++count > Zips.MAX_ENTRIES) throw TooLargeException("too many entries")
                val name = e.name.replace('\\', '/')
                if (name.startsWith("/") || name.split('/').any { it == ".." } || name.contains(':')) {
                    return Outcome.Fail(SwError.BadInput("backup contains an unsafe path"))
                }
                when {
                    name == BackupFormat.MANIFEST -> manifestText = Texts.readBounded(z, MAX_MANIFEST).toString(Charsets.UTF_8)
                    name == BackupFormat.DATABASE -> total += copyBounded(z, dbFile, MAX_TOTAL - total)
                    BackupFormat.FOLDERS.any { name.startsWith("$it/") } -> {
                        val out = File(staging, name)
                        out.parentFile?.mkdirs()
                        total += copyBounded(z, out, MAX_TOTAL - total)
                    }
                    else -> SwLog.w(TAG, "ignored backup entry")
                }
            }
        }
        val manifest = manifestText?.let { BackupManifest.parse(it) } ?: return Outcome.Fail(SwError.BadInput("backup has no Self Watch manifest"))
        if (manifest.format > BackupFormat.VERSION) return Outcome.Fail(SwError.BadInput("backup format ${manifest.format} is newer than this app"))
        if (!dbFile.isFile) return Outcome.Fail(SwError.BadInput("backup has no database"))
        validate(dbFile)?.let { return Outcome.Fail(SwError.BadInput(it)) }
        return Outcome.Ok(Staged(dir, manifest, dbFile, staging))
    }

    /**
     * Null when [f] is a usable `selfwatch.db`, else the reason. Opened read-write because SQLite's
     * FTS4 part of `integrity_check` needs a writable handle; [f] is the staging copy, never the live file.
     */
    private fun validate(f: File): String? = try {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
            val check = d.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            val version = d.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val tables = HashSet<String>()
            d.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
            when {
                check != "ok" -> {
                    SwLog.w(TAG, "integrity_check failed")
                    "the backup's database is damaged"
                }
                version < 1 -> "the backup's database has no schema version"
                version > SelfWatchDb.VERSION -> "the backup is from a newer version of Self Watch (schema $version)"
                !tables.containsAll(REQUIRED_TABLES) -> "the backup's database is not a Self Watch database"
                else -> null
            }
        }
    } catch (e: android.database.SQLException) {
        SwLog.w(TAG, "backup database unreadable", e)
        "the backup's database is unreadable"
    }

    private fun swap(s: Staged) {
        val live = File(db.readableDatabase.path)
        val sidecars = listOf("-wal", "-shm", "-journal").map { File(live.path + it) }
        val aside = File(live.path + ASIDE)
        val asideWal = File(live.path + ASIDE + "-wal")
        db.close()
        aside.delete()
        asideWal.delete()
        if (!live.renameTo(aside)) throw IOException("cannot move the old database aside")
        sidecars[0].takeIf { it.exists() }?.renameTo(asideWal)
        sidecars.forEach { it.delete() }
        try {
            s.dbFile.copyTo(live, overwrite = true)
            db.writableDatabase // reopens and runs migrations
        } catch (e: Exception) {
            SwLog.e(TAG, "new database failed to open; putting the old one back", e)
            db.close()
            live.delete()
            sidecars.forEach { it.delete() }
            aside.renameTo(live)
            asideWal.takeIf { it.exists() }?.renameTo(sidecars[0])
            db.writableDatabase
            throw e
        }
        aside.delete()
        asideWal.delete()
        for (folder in BackupFormat.FOLDERS) replaceFolder(File(s.filesDir, folder), File(files.filesDir, folder))
        db.changes.notify(Table.entries.toSet())
        SwLog.i(TAG, "restored backup: schema ${s.manifest.schemaVersion}, ${s.manifest.files} files")
    }

    private fun replaceFolder(from: File, to: File) {
        val old = File(to.path + ASIDE)
        old.deleteRecursively()
        if (to.exists() && !to.renameTo(old)) to.deleteRecursively()
        to.parentFile?.mkdirs()
        if (from.isDirectory && !from.renameTo(to)) {
            from.copyRecursively(to, overwrite = true)
        }
        old.deleteRecursively()
    }

    private companion object {
        const val TAG = "BackupRestore"
        const val ASIDE = ".pre-restore"
        const val MAX_MANIFEST = 1 shl 20
        const val MAX_TOTAL = 8L shl 30
        val REQUIRED_TABLES = setOf("setting", "import_batch", "diary_entry", "workout")

        fun readUpTo(input: InputStream, b: ByteArray): Int {
            var n = 0
            while (n < b.size) {
                val r = input.read(b, n, b.size - n)
                if (r < 0) break
                n += r
            }
            return n
        }

        fun copyBounded(input: InputStream, out: File, max: Long): Long {
            var total = 0L
            val buf = ByteArray(1 shl 16)
            FileOutputStream(out).use { os ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > max) throw TooLargeException("backup too large")
                    os.write(buf, 0, n)
                }
            }
            return total
        }
    }
}

/**
 * The "selfwatch-backup" importer: detects Self Watch backups (plain or encrypted) so the import
 * screen can route them, previews the manifest, and restores plain backups on commit. Encrypted
 * backups plan with a warning and need [BackupRestore.restore] with the passphrase.
 */
class SelfWatchBackupImporter(private val restorer: BackupRestore?) : BaseImporter(ImportSink.NONE) {
    override val id: String = "selfwatch-backup"

    override fun sniffSafe(name: String, head: ByteArray): Int {
        if (BackupCrypto.isEncrypted(head)) return 100
        if (!Zips.isZip(head)) return 0
        val names = Zips.namesInHead(head)
        return if (BackupFormat.MANIFEST in names && BackupFormat.DATABASE in names) 95 else 0
    }

    private class Handle(val src: InputSource) : ImportHandle {
        override fun data(choices: ImportChoices): Outcome<ImportData> = Outcome.Ok(ImportData())
    }

    override fun parse(src: InputSource): Outcome<ImportPlan> {
        val head = src.open().use { input -> ByteArray(64).let { b -> b.copyOf(input.read(b).coerceAtLeast(0)) } }
        val warnings = ArrayList<String>()
        var counts: Map<String, Int> = emptyMap()
        val preview = ArrayList<List<String>>()
        if (BackupCrypto.isEncrypted(head)) {
            warnings.add("encrypted backup: enter the passphrase to restore")
        } else {
            val items = Zips.readAll(src.open())
            val manifest = items.firstOrNull { it.name == BackupFormat.MANIFEST }?.let { BackupManifest.parse(it.bytes.toString(Charsets.UTF_8)) }
                ?: return Outcome.Fail(SwError.BadInput("not a Self Watch backup"))
            if (items.none { it.name == BackupFormat.DATABASE }) return Outcome.Fail(SwError.BadInput("backup has no database"))
            counts = manifest.counts.filterValues { it > 0 }.mapValues { it.value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            preview.add(listOf("table", "rows"))
            manifest.counts.forEach { (t, n) -> preview.add(listOf(t, n.toString())) }
            warnings.add("restoring replaces everything in Self Watch")
        }
        return Outcome.Ok(
            ImportPlan(
                importerId = id, fileName = src.name, counts = counts, preview = preview, columns = emptyList(),
                dateFormat = DateFormatGuess.NONE, units = UnitGuess.NONE, duplicates = 0, warnings = warnings, handle = Handle(src),
            ),
        )
    }

    override suspend fun commit(plan: ImportPlan, choices: ImportChoices): Outcome<ImportResult> {
        val h = plan.handle as? Handle ?: return Outcome.Fail(SwError.BadInput("not a backup plan"))
        val r = restorer ?: return Outcome.Fail(SwError.Io("no database connected"))
        return when (val m = r.restore(h.src, null)) {
            is Outcome.Ok -> Outcome.Ok(ImportResult(0, plan.counts))
            is Outcome.Fail -> m
        }
    }
}
