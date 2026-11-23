/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.backup

import android.database.sqlite.SQLiteDatabase
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.interop.DbTestSupport
import app.selfwatch.interop.TestSupport
import app.selfwatch.interop.api.DiaryRow
import app.selfwatch.interop.api.ImportData
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.MemorySink
import app.selfwatch.interop.api.TrackFormat
import app.selfwatch.interop.api.WaterRow
import app.selfwatch.interop.db.AppFiles
import app.selfwatch.interop.workout.WorkoutFiles
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Day
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRestoreTest {
    private lateinit var files: AppFiles
    private val cache: File get() = RuntimeEnvironment.getApplication().cacheDir

    @Before
    fun setUp() {
        files = DbTestSupport.fresh()
    }

    private fun populate() = runBlocking {
        val fit = TestSupport.bytes("fit/synthetic/activity_run_gps.fit")
        val run = (WorkoutFiles.parse(TrackFormat.FIT, "run.fit", fit) as Outcome.Ok).value
        val day = Day(20260301)
        val diary = (1..30).map { DiaryRow(day.plusDays(it % 5), "Lunch", null, "Food $it", null, 100, "g", null, Nutrients(it * 10, null, null, null, null, null, null, null, null)) }
        DbTestSupport.sink(files).write("test", "seed", ImportData(diary = diary, workouts = run, water = listOf(WaterRow(day, null, 300))), true)
        DbTestSupport.db.settings.set("units", "metric")
        File(files.filesDir, "watchapps/abc/settings.json").apply { parentFile?.mkdirs() }.writeText("{\"a\":1}")
    }

    private fun writer(method: SnapshotMethod) = BackupWriter(DbTestSupport.db, files, cache, "1.2.3", method = method)

    private fun restorer(pauser: ServicePauser = ServicePauser.NONE) = BackupRestore(DbTestSupport.db, files, cache, pauser)

    private fun fileTree(): Map<String, String> =
        BackupFormat.FOLDERS.flatMap { d -> BackupWriter.listFiles(File(files.filesDir, d)).map { "$d/${it.first}" to it.second.readBytes().contentHashCode().toString() } }.toMap()

    /** Backup, wipe everything (a fresh install), restore: identical tables and files. */
    private fun roundTrip(method: SnapshotMethod, passphrase: CharArray?) = runBlocking {
        populate()
        val sums = DbTestSupport.checksums()
        val counts = DbTestSupport.counts()
        val tree = fileTree()
        val out = MemorySink("b")
        val manifest = (writer(method).write(out, passphrase) as Outcome.Ok).value
        assertEquals(SelfWatchDb.VERSION, manifest.schemaVersion)
        assertEquals(counts, manifest.counts)
        assertEquals(tree.size, manifest.files)
        assertEquals(passphrase != null, BackupCrypto.isEncrypted(out.bytes()))

        files.filesDir.deleteRecursively()
        DbTestSupport.fresh()
        assertTrue(DbTestSupport.checksums() != sums)

        var paused = 0
        var resumed = 0
        val pauser = object : ServicePauser {
            override suspend fun pause(): Boolean {
                paused++
                return true
            }

            override suspend fun resume() {
                resumed++
            }
        }
        val r = restorer(pauser).restore(InputSource.ofBytes("b", out.bytes()), passphrase)
        assertTrue("$r", r is Outcome.Ok)
        assertEquals(1, paused)
        assertEquals(1, resumed)
        assertEquals(sums, DbTestSupport.checksums())
        assertEquals(tree, fileTree())
        // The database is usable again after the swap.
        assertEquals("metric", DbTestSupport.db.settings.get("units"))
        val w = DbTestSupport.db.workouts.list(5).first { it.fitPath != null }
        assertTrue(files.resolve(w.fitPath!!).isFile)
    }

    @Test
    fun plainRoundTripWithVacuumInto() = roundTrip(SnapshotMethod.VACUUM_INTO, null)

    @Test
    fun encryptedRoundTripWithLockedCopy() = roundTrip(SnapshotMethod.LOCKED_COPY, "correct horse".toCharArray())

    @Test
    fun methodPerApiLevel() {
        assertEquals(SnapshotMethod.LOCKED_COPY, SnapshotMethod.forSdk(26))
        assertEquals(SnapshotMethod.LOCKED_COPY, SnapshotMethod.forSdk(29))
        assertEquals(SnapshotMethod.VACUUM_INTO, SnapshotMethod.forSdk(30))
    }

    @Test
    fun lockedCopyIncludesUncheckpointedWal() = runBlocking<Unit> {
        populate()
        // Writes after the last checkpoint live only in the -wal file until then.
        DbTestSupport.db.settings.set("theme", "light")
        val snap = File(cache, "snap-${System.nanoTime()}.db")
        BackupWriter.snapshot(DbTestSupport.db, snap, SnapshotMethod.LOCKED_COPY)
        assertFalse(File(snap.path + "-wal").exists())
        SQLiteDatabase.openDatabase(snap.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT value FROM setting WHERE key = 'theme'", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("light", c.getString(0))
            }
            d.rawQuery("PRAGMA journal_mode", null).use { c ->
                c.moveToFirst()
                assertEquals("delete", c.getString(0).lowercase())
            }
        }
        assertEquals(DbTestSupport.counts(), BackupWriter.tableCounts(snap))
        snap.delete()
    }

    @Test
    fun wrongPassphraseAndTamperingFailCleanly() = runBlocking {
        populate()
        val out = MemorySink()
        writer(SnapshotMethod.VACUUM_INTO).write(out, "secret".toCharArray())
        val before = DbTestSupport.checksums()
        val wrong = restorer().restore(InputSource.ofBytes("b", out.bytes()), "nope".toCharArray())
        assertEquals(SwError.BadInput("wrong passphrase or damaged file"), (wrong as Outcome.Fail).error)
        val tampered = out.bytes().copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertTrue(restorer().restore(InputSource.ofBytes("b", tampered), "secret".toCharArray()) is Outcome.Fail)
        assertTrue(restorer().restore(InputSource.ofBytes("b", out.bytes()), null) is Outcome.Fail)
        assertEquals(before, DbTestSupport.checksums())
    }

    @Test
    fun inspectReadsTheManifestWithoutChangingAnything() = runBlocking {
        populate()
        val out = MemorySink()
        writer(SnapshotMethod.VACUUM_INTO).write(out)
        val before = DbTestSupport.checksums()
        val m = (restorer().inspect(InputSource.ofBytes("b", out.bytes()), null) as Outcome.Ok).value
        assertEquals("1.2.3", m.appVersion)
        assertEquals(30L, m.counts["diary_entry"])
        assertEquals(before, DbTestSupport.checksums())
    }

    private fun zipWith(manifest: BackupManifest, db: ByteArray, vararg extra: Pair<String, ByteArray>): ByteArray =
        TestSupport.zipOf(BackupFormat.MANIFEST to manifest.toJson().toByteArray(), BackupFormat.DATABASE to db, *extra)

    private fun snapshotBytes(edit: (SQLiteDatabase) -> Unit = {}): ByteArray {
        val f = File(cache, "s-${System.nanoTime()}.db")
        BackupWriter.snapshot(DbTestSupport.db, f, SnapshotMethod.VACUUM_INTO)
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use(edit)
        return f.readBytes().also { f.delete() }
    }

    @Test
    fun newerSchemaDamagedDatabaseAndUnsafePathsAreRefused() = runBlocking {
        populate()
        val before = DbTestSupport.checksums()
        val manifest = BackupManifest(1, "x", 1, 0, emptyMap(), 0)
        val newer = snapshotBytes { it.execSQL("PRAGMA user_version = ${SelfWatchDb.VERSION + 1}") }
        val r1 = restorer().restore(InputSource.ofBytes("b", zipWith(manifest, newer)), null)
        assertTrue("$r1", (r1 as Outcome.Fail).error.toString().contains("newer"))
        val garbage = ByteArray(5000) { (it * 7).toByte() }
        assertTrue(restorer().restore(InputSource.ofBytes("b", zipWith(manifest, garbage)), null) is Outcome.Fail)
        val ok = snapshotBytes()
        val slip = restorer().restore(InputSource.ofBytes("b", zipWith(manifest, ok, "workouts/../../evil.txt" to byteArrayOf(1))), null)
        assertTrue((slip as Outcome.Fail).error.toString().contains("unsafe"))
        val noManifest = TestSupport.zipOf(BackupFormat.DATABASE to ok)
        assertTrue(restorer().restore(InputSource.ofBytes("b", noManifest), null) is Outcome.Fail)
        val refused = object : ServicePauser {
            override suspend fun pause(): Boolean = false
            override suspend fun resume() = Unit
        }
        assertTrue(restorer(refused).restore(InputSource.ofBytes("b", zipWith(manifest, ok)), null) is Outcome.Fail)
        assertEquals(before, DbTestSupport.checksums())
    }

    @Test
    fun backupImporterDetectsAndRestores() = runBlocking {
        populate()
        val plain = MemorySink()
        writer(SnapshotMethod.VACUUM_INTO).write(plain)
        val enc = MemorySink()
        writer(SnapshotMethod.VACUUM_INTO).write(enc, "pw".toCharArray())
        val importer = SelfWatchBackupImporter(restorer())
        assertEquals(95, importer.sniff("selfwatch-backup-2026-11-23.zip", plain.bytes().copyOf(65536)))
        assertEquals(100, importer.sniff("x.swbak", enc.bytes().copyOf(64)))
        val sums = DbTestSupport.checksums()
        val plan = (importer.plan(InputSource.ofBytes("b.zip", plain.bytes())) as Outcome.Ok).value
        assertEquals(30, plan.counts["diary_entry"])
        DbTestSupport.fresh()
        assertTrue(importer.commit(plan, plan.defaultChoices()) is Outcome.Ok)
        assertEquals(sums, DbTestSupport.checksums())
        val encPlan = (importer.plan(InputSource.ofBytes("b.swbak", enc.bytes())) as Outcome.Ok).value
        assertTrue(encPlan.warnings.single().contains("encrypted"))
    }
}
