/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.install

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SettingsStore
import app.selfwatch.food.source.FoodDbFormat
import app.selfwatch.food.source.FoodsDbHandle
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.net.Http
import app.selfwatch.model.net.HttpRequest
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * What one installer installs: file name, `application_id`, the table that must not be empty, the
 * network feature and where the installed `data_version` is remembered.
 */
data class InstallSpec(
    val fileName: String,
    val applicationId: Int,
    val rowTable: String,
    val feature: NetFeature,
    val versionStore: VersionStore,
)

/** Remembers the installed `data_version` (null: nothing installed). */
interface VersionStore {
    /** The installed data version, or null. */
    suspend fun get(): Int?

    /** Stores [version] (null clears it). */
    suspend fun set(version: Int?)
}

/** A [VersionStore] in the settings table under [key]. */
class SettingsVersionStore(private val settings: SettingsStore, private val key: String) : VersionStore {
    override suspend fun get(): Int? = settings.getInt(key)

    override suspend fun set(version: Int?): Unit = settings.setInt(key, version)
}

/** The update manifest (FOODDB_SPEC §9), as published next to the file. */
data class DataManifest(
    val schemaVersion: Int,
    val dataVersion: Int,
    val minAppVersion: Int,
    val file: String,
    val url: String,
    val sha256: String,
    val sizeGz: Long?,
    val sizeDb: Long?,
    val rows: Long?,
    val builtAt: String?,
) {
    /** Parser. */
    companion object {
        /** Parses manifest JSON; fails with `BadInput` when a required field is missing or malformed. */
        fun parse(json: String): Outcome<DataManifest> {
            val root = when (val r = Json.parse(json)) {
                is Outcome.Fail -> return r
                is Outcome.Ok -> r.value
            }
            if (root !is JsonValue.Obj) return bad("manifest is not an object")
            fun int(k: String): Int? = root[k]?.asLong?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            val schema = int("schema_version") ?: return bad("schema_version")
            val data = int("data_version") ?: return bad("data_version")
            val url = root["url"]?.asString ?: return bad("url")
            val sha = root["sha256"]?.asString?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return bad("sha256")
            return Outcome.Ok(
                DataManifest(
                    schemaVersion = schema,
                    dataVersion = data,
                    minAppVersion = int("min_app_version") ?: 1,
                    file = root["file"]?.asString ?: url.substringAfterLast('/'),
                    url = url,
                    sha256 = sha,
                    sizeGz = root["size_gz"]?.asLong,
                    sizeDb = root["size_db"]?.asLong,
                    rows = root["rows"]?.asLong,
                    builtAt = root["built_at"]?.asString,
                ),
            )
        }

        private fun bad(what: String): Outcome<DataManifest> = Outcome.Fail(SwError.BadInput("manifest: $what"))
    }
}

/** Result of an update check. */
sealed interface UpdateCheck {
    /** [manifest] is newer than what is installed and this app can use it. */
    data class Available(val manifest: DataManifest) : UpdateCheck

    /** The installed file is current. */
    data object UpToDate : UpdateCheck

    /** The published file needs a newer app ([minAppVersion]) or schema ([schemaVersion]). */
    data class NeedsNewerApp(val schemaVersion: Int, val minAppVersion: Int) : UpdateCheck
}

/**
 * Downloads, checks and installs a read-only data file (foods.db or recipes.db), FOODDB_SPEC §9:
 * 1. fetch the manifest through `net` (Wi-Fi only for FOOD_DATA and RECIPE_BOOK; `net` enforces it);
 * 2. compare `schema_version` (supported: 1), `min_app_version` and `data_version`;
 * 3. download the `.gz` to `<dir>/tmp/`;
 * 4. check its SHA-256;
 * 5. gunzip to `<file>.new`;
 * 6. open it and check `application_id`, `user_version`, `PRAGMA quick_check` and row count > 0;
 * 7. close the old handle ([beforeSwap]), rename atomically over the old file, store the version.
 *
 * **Every failure keeps the old file**: temporary files are deleted and nothing is renamed.
 * "Load from file" ([installFrom]) runs steps 4–7 on a picked `.gz` or `.db` (checksum optional).
 *
 * @param dir the data folder (`files/fooddata`).
 * @param appVersion this app's version code, compared with `min_app_version`.
 * @param beforeSwap closes any open connection to the file (for foods.db: `FoodsDbHandle.close`).
 */
open class DataInstaller(
    private val spec: InstallSpec,
    private val http: Http,
    private val dir: File,
    private val manifestUrl: String,
    private val appVersion: Int,
    private val beforeSwap: suspend () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The installed file. */
    val file: File get() = File(dir, spec.fileName)

    private val newFile: File get() = File(dir, spec.fileName + ".new")
    private val tmpDir: File get() = File(dir, "tmp")

    /** True when the file is installed. */
    val isInstalled: Boolean get() = file.isFile

    /** The installed data version, or null. */
    suspend fun installedVersion(): Int? = if (isInstalled) spec.versionStore.get() else null

    /** Fetches the manifest and says whether there is something newer to install. */
    suspend fun checkForUpdate(): Outcome<UpdateCheck> {
        val resp = when (val r = http.send(HttpRequest(spec.feature, "GET", manifestUrl, maxBytes = MANIFEST_MAX_BYTES))) {
            is Outcome.Fail -> return r
            is Outcome.Ok -> r.value
        }
        if (resp.status !in 200..299) return Outcome.Fail(SwError.Io("HTTP ${resp.status}"))
        val m = when (val p = DataManifest.parse(resp.body.toString(Charsets.UTF_8))) {
            is Outcome.Fail -> return p
            is Outcome.Ok -> p.value
        }
        if (m.schemaVersion != FoodDbFormat.SCHEMA_VERSION || m.minAppVersion > appVersion) {
            return Outcome.Ok(UpdateCheck.NeedsNewerApp(m.schemaVersion, m.minAppVersion))
        }
        val current = installedVersion()
        return Outcome.Ok(if (current != null && m.dataVersion <= current) UpdateCheck.UpToDate else UpdateCheck.Available(m))
    }

    /**
     * Downloads and installs [m] (from [checkForUpdate]). [onProgress] gets (bytes, total or null)
     * during the download. Returns the installed data version.
     */
    suspend fun install(m: DataManifest, onProgress: (Long, Long?) -> Unit = { _, _ -> }): Outcome<Int> {
        if (m.schemaVersion != FoodDbFormat.SCHEMA_VERSION) return Outcome.Fail(SwError.BadInput("unsupported schema ${m.schemaVersion}"))
        if (m.minAppVersion > appVersion) return Outcome.Fail(SwError.BadInput("needs app version ${m.minAppVersion}"))
        val gz = File(tmpDir, spec.fileName + ".gz")
        try {
            withContext(io) {
                tmpDir.mkdirs()
                gz.delete()
            }
            val maxBytes = (m.sizeGz ?: 0L).coerceAtLeast(0L) + DOWNLOAD_SLACK_BYTES
            val req = HttpRequest(spec.feature, "GET", m.url, timeoutMs = DOWNLOAD_TIMEOUT_MS, maxBytes = maxBytes)
            when (val d = http.download(req, gz, onProgress)) {
                is Outcome.Fail -> return d
                is Outcome.Ok -> Unit
            }
            return installLocal(gz, m.sha256, m.dataVersion)
        } finally {
            withContext(NonCancellable + io) { gz.delete() }
        }
    }

    /**
     * "Load from file": installs a picked `.gz` or `.db` from [input] (gzip is detected by its magic
     * bytes). [expectedSha256], when given, is checked against the bytes as read. The data version
     * comes from the file's `meta` table. Returns the installed data version.
     */
    suspend fun installFrom(input: InputStream, expectedSha256: String? = null): Outcome<Int> {
        val staged = File(tmpDir, spec.fileName + ".pick")
        try {
            withContext(io) {
                tmpDir.mkdirs()
                staged.outputStream().use { out -> input.copyTo(out, BUFFER) }
            }
            return installLocal(staged, expectedSha256?.lowercase(), null)
        } catch (e: IOException) {
            SwLog.w(TAG, "reading the picked file failed", e)
            return Outcome.Fail(SwError.Io("read failed"))
        } finally {
            withContext(NonCancellable + io) { staged.delete() }
        }
    }

    /** Deletes the installed file and forgets its version. */
    suspend fun remove() {
        beforeSwap()
        withContext(io) {
            file.delete()
            newFile.delete()
            File(dir, spec.fileName + "-journal").delete()
            tmpDir.deleteRecursively()
        }
        spec.versionStore.set(null)
        SwLog.i(TAG, "${spec.fileName} removed")
    }

    /** Steps 4–7 on a local [source] (gzip or plain). [expectedVersion] must match `meta.data_version` when given. */
    private suspend fun installLocal(source: File, expectedSha256: String?, expectedVersion: Int?): Outcome<Int> {
        val staged = newFile
        try {
            val checked = withContext(io) {
                if (expectedSha256 != null) {
                    val actual = sha256(source)
                    if (actual != expectedSha256) return@withContext fail("checksum mismatch")
                }
                ensureActive()
                staged.delete()
                try {
                    openMaybeGzip(source).use { inp -> staged.outputStream().use { out -> inp.copyTo(out, BUFFER) } }
                } catch (e: IOException) {
                    SwLog.w(TAG, "unpacking ${spec.fileName} failed", e)
                    return@withContext fail("unpack failed")
                }
                ensureActive()
                verify(staged)
            }
            val version = when (checked) {
                is Outcome.Fail -> return checked
                is Outcome.Ok -> checked.value
            }
            if (expectedVersion != null && version != expectedVersion) {
                return fail("data_version $version, manifest says $expectedVersion")
            }
            beforeSwap()
            val moved = withContext(io) { moveOver(staged, file) }
            if (!moved) return Outcome.Fail(SwError.Io("rename failed"))
            spec.versionStore.set(version)
            SwLog.i(TAG, "${spec.fileName} installed, data version $version")
            return Outcome.Ok(version)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SwLog.w(TAG, "installing ${spec.fileName} failed", e)
            return Outcome.Fail(SwError.Io("install failed"))
        } finally {
            withContext(NonCancellable + io) { staged.delete() }
        }
    }

    /**
     * Opens the staged copy [f] (read-write, rollback journal; see `FoodDbFormat.openForChecks`) and
     * runs the quick checks; returns `meta.data_version` (0 when absent).
     */
    private fun verify(f: File): Outcome<Int> {
        val db: SQLiteDatabase = try {
            FoodDbFormat.openForChecks(f)
        } catch (e: SQLiteException) {
            SwLog.w(TAG, "${spec.fileName}: not a database", e)
            return fail("not a database")
        }
        try {
            val appId = FoodDbFormat.applicationId(db)
            if (appId != spec.applicationId) return fail("application_id $appId")
            if (db.version != FoodDbFormat.SCHEMA_VERSION) return fail("user_version ${db.version}")
            val quick = db.rawQuery("PRAGMA quick_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (quick != "ok") return fail("quick_check failed")
            val rows = db.rawQuery("SELECT COUNT(*) FROM ${spec.rowTable}", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            if (rows <= 0) return fail("no rows")
            val schema = metaValue(db, "schema_version")
            if (schema != null && schema != FoodDbFormat.SCHEMA_VERSION.toString()) return fail("meta schema_version $schema")
            return Outcome.Ok(metaValue(db, "data_version")?.toIntOrNull() ?: 0)
        } catch (e: SQLiteException) {
            SwLog.w(TAG, "${spec.fileName}: checks failed", e)
            return fail("checks failed")
        } finally {
            db.close()
        }
    }

    private fun metaValue(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun fail(why: String): Outcome<Int> {
        SwLog.w(TAG, "${spec.fileName} rejected: $why")
        return Outcome.Fail(SwError.BadInput(why))
    }

    /** Constants and file helpers. */
    companion object {
        private const val TAG = "DataInstaller"
        private const val BUFFER = 64 * 1024
        private const val MANIFEST_MAX_BYTES = 64L * 1024
        private const val DOWNLOAD_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_SLACK_BYTES = 64L shl 20

        /** Lowercase hex SHA-256 of [f]. */
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { inp ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun openMaybeGzip(f: File): InputStream {
            val inp = BufferedInputStream(f.inputStream(), BUFFER)
            inp.mark(2)
            val b0 = inp.read()
            val b1 = inp.read()
            inp.reset()
            return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(inp, BUFFER) else inp
        }

        /** Renames [from] over [to] atomically when the file system can, else with a plain replace. */
        private fun moveOver(from: File, to: File): Boolean = try {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: IOException) {
            SwLog.w(TAG, "rename failed", e)
            false
        }
    }
}

/**
 * Installs foods.db (FOODDB_SPEC Part B) and closes the shared [handle] before swapping it. The
 * installed version goes to [versionStore] (in the app: `food.db.installed_version`).
 */
class FoodDbInstaller(
    http: Http,
    versionStore: VersionStore,
    dir: File,
    manifestUrl: String,
    appVersion: Int,
    handle: FoodsDbHandle?,
    io: CoroutineDispatcher = Dispatchers.IO,
) : DataInstaller(
    InstallSpec(FoodDbFormat.FOODS_FILE, FoodDbFormat.FOODS_APPLICATION_ID, "food", NetFeature.FOOD_DATA, versionStore),
    http, dir, manifestUrl, appVersion, { handle?.close() }, io,
) {
    /** The app's installer: version in setting `food.db.installed_version`. */
    constructor(http: Http, settings: SettingsStore, dir: File, manifestUrl: String, appVersion: Int, handle: FoodsDbHandle?) :
        this(http, SettingsVersionStore(settings, SettingKeys.FOOD_DB_INSTALLED_VERSION), dir, manifestUrl, appVersion, handle)
}

/** Installs the optional starter recipe book, recipes.db (FOODDB_SPEC §10). */
class RecipeBookInstaller(
    http: Http,
    versionStore: VersionStore,
    dir: File,
    manifestUrl: String,
    appVersion: Int,
    beforeSwap: suspend () -> Unit = {},
    io: CoroutineDispatcher = Dispatchers.IO,
) : DataInstaller(
    InstallSpec(FoodDbFormat.RECIPES_FILE, FoodDbFormat.RECIPES_APPLICATION_ID, "recipe", NetFeature.RECIPE_BOOK, versionStore),
    http, dir, manifestUrl, appVersion, beforeSwap, io,
) {
    /** The app's installer: version in setting [KEY_INSTALLED_VERSION]. */
    constructor(http: Http, settings: SettingsStore, dir: File, manifestUrl: String, appVersion: Int) :
        this(http, SettingsVersionStore(settings, KEY_INSTALLED_VERSION), dir, manifestUrl, appVersion)

    /** Keys. */
    companion object {
        /** Setting: installed recipes.db data version (int). */
        const val KEY_INSTALLED_VERSION: String = SettingKeys.RECIPES_DB_INSTALLED_VERSION
    }
}
