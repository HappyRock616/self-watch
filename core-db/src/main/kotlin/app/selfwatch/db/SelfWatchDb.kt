/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.VisibleForTesting
import app.selfwatch.db.dao.ActivityDao
import app.selfwatch.db.dao.BatteryDao
import app.selfwatch.db.dao.BodyDao
import app.selfwatch.db.dao.DeviceDao
import app.selfwatch.db.dao.DiaryDao
import app.selfwatch.db.dao.FastDao
import app.selfwatch.db.dao.FavouriteDao
import app.selfwatch.db.dao.FoodDao
import app.selfwatch.db.dao.GoalDao
import app.selfwatch.db.dao.HealthWriteDao
import app.selfwatch.db.dao.ImportDao
import app.selfwatch.db.dao.LinkDao
import app.selfwatch.db.dao.MetricDao
import app.selfwatch.db.dao.NetLogDao
import app.selfwatch.db.dao.RecipeDao
import app.selfwatch.db.dao.SleepDao
import app.selfwatch.db.dao.StatsDao
import app.selfwatch.db.dao.SummaryDao
import app.selfwatch.db.dao.SyncFileDao
import app.selfwatch.db.dao.WatchAppDao
import app.selfwatch.db.dao.WaterDao
import app.selfwatch.db.dao.WorkoutDao
import app.selfwatch.model.log.SwLog

/**
 * `selfwatch.db`: everything the user owns, in one SQLite file shared by the app's processes.
 *
 * Each process opens its own connection ([get] returns one instance per process). The database runs
 * in WAL mode with `synchronous=NORMAL` and a 5 s busy timeout on the write connection (Android's
 * extra read-only connections keep the platform default), so the `:watch` service and the screens
 * can read and write at the same time. Creation runs `assets/db/V1.sql` and then every later script
 * up to [VERSION]; upgrades run `assets/db/V{n}.sql` for every version above the old one, in order,
 * inside the helper's single upgrade transaction (a failing script rolls the whole upgrade back).
 *
 * Use the DAO properties ([devices], [diary], …); every DAO function is `suspend` and runs on
 * `Dispatchers.IO`. Writes announce the tables they changed through [changes].
 */
public class SelfWatchDb internal constructor(
    ctx: Context,
    name: String?,
    private val targetVersion: Int,
    private val scripts: (Int) -> String,
    /** Change notices for this process. */
    public val changes: DbChanges,
) : SQLiteOpenHelper(ctx.applicationContext ?: ctx, name, null, targetVersion) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        if (db.path != MEMORY_PATH) db.enableWriteAheadLogging()
        // PRAGMAs that report a value must go through rawQuery on Android.
        db.rawQuery("PRAGMA synchronous=NORMAL", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // SQLiteOpenHelper already wraps this in one transaction. A fresh install gets only onCreate,
        // so it replays every script up to the target version (V1, then each upgrade in order).
        for (v in 1..targetVersion) runScript(db, v)
        SwLog.i(TAG, "created schema V$targetVersion")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (v in oldVersion + 1..newVersion) {
            runScript(db, v)
            SwLog.i(TAG, "upgraded schema to V$v")
        }
    }

    private fun runScript(db: SQLiteDatabase, version: Int) {
        for (stmt in SqlScript.split(scripts(version))) db.execSQL(stmt)
    }

    /** Devices. */
    public val devices: DeviceDao by lazy { DeviceDao(this) }

    /** Watch sync file bookkeeping. */
    public val syncFiles: SyncFileDao by lazy { SyncFileDao(this) }

    /** Device battery history. */
    public val battery: BatteryDao by lazy { BatteryDao(this) }

    /** Minute activity data. */
    public val activity: ActivityDao by lazy { ActivityDao(this) }

    /** Time-series metrics. */
    public val metrics: MetricDao by lazy { MetricDao(this) }

    /** Sleep sessions and stages. */
    public val sleep: SleepDao by lazy { SleepDao(this) }

    /** Workouts. */
    public val workouts: WorkoutDao by lazy { WorkoutDao(this) }

    /** Atomic write of one watch sync unit (for `service.HealthIngest`). */
    public val healthWrite: HealthWriteDao by lazy { HealthWriteDao(this) }

    /** Body measurements and custom measurement kinds. */
    public val body: BodyDao by lazy { BodyDao(this) }

    /** The user's own foods and the `my_food_fts` search. */
    public val foods: FoodDao by lazy { FoodDao(this) }

    /** The food diary and meal slots. */
    public val diary: DiaryDao by lazy { DiaryDao(this) }

    /** Favourite foods. */
    public val favourites: FavouriteDao by lazy { FavouriteDao(this) }

    /** Water log. */
    public val water: WaterDao by lazy { WaterDao(this) }

    /** Fasting log. */
    public val fasts: FastDao by lazy { FastDao(this) }

    /** Recipe book. */
    public val recipes: RecipeDao by lazy { RecipeDao(this) }

    /** Goals. */
    public val goals: GoalDao by lazy { GoalDao(this) }

    /** Settings (the only settings store). */
    public val settings: SettingsStore by lazy { SettingsStore(this) }

    /** Day summaries and their week and month aggregates. */
    public val summaries: SummaryDao by lazy { SummaryDao(this) }

    /** Row counts across the health tables (Devices screen check). */
    public val stats: StatsDao by lazy { StatsDao(this) }

    /** Connect IQ apps seen on watches and their saved settings. */
    public val watchApps: WatchAppDao by lazy { WatchAppDao(this) }

    /** Link permissions for other apps. */
    public val links: LinkDao by lazy { LinkDao(this) }

    /** Network log. */
    public val netLog: NetLogDao by lazy { NetLogDao(this) }

    /** Import batches and undo. */
    public val imports: ImportDao by lazy { ImportDao(this) }

    /**
     * Runs [block] in one write transaction: every DAO call made from [block] (in this coroutine)
     * joins it, and everything commits together or, if [block] throws, nothing does. The change
     * notice for [tables] plus every table the DAO calls wrote goes out once, after the commit.
     *
     * The block runs on a background thread of its own (the IO work stays off the main thread):
     * Android binds a transaction to the thread that began it, so the block and its DAO calls stay on
     * that thread even if the block suspends. Don't switch dispatchers inside the block; a DAO call
     * from another thread fails with [IllegalStateException]. [block] may use the raw connection it
     * is given for queries the DAOs don't have. Called again inside [block], it simply joins.
     * Starting the transaction retries while the other process holds the write lock.
     */
    public suspend fun <T> inTransaction(tables: Set<Table> = emptySet(), block: suspend (SQLiteDatabase) -> T): T =
        transaction(tables, block)

    /** Constants and the per-process instance. */
    public companion object {
        /** Current schema version. */
        public const val VERSION: Int = 5

        /** File name. */
        public const val NAME: String = "selfwatch.db"

        /** SQLite busy timeout, milliseconds. */
        public const val BUSY_TIMEOUT_MS: Int = 5000

        private const val TAG: String = "SelfWatchDb"
        private const val MEMORY_PATH: String = ":memory:"

        @Volatile
        private var instance: SelfWatchDb? = null

        /** This process's database. */
        public fun get(ctx: Context): SelfWatchDb =
            instance ?: synchronized(this) {
                instance ?: create(ctx, NAME, VERSION).also { instance = it }
            }

        /** Reads `assets/db/V[version].sql`. */
        internal fun assetScript(ctx: Context, version: Int): String =
            ctx.assets.open("db/V$version.sql").bufferedReader(Charsets.UTF_8).use { it.readText() }

        /** A new helper (not the shared instance); for tests and tools. */
        internal fun create(
            ctx: Context,
            name: String?,
            version: Int = VERSION,
            scripts: (Int) -> String = { v -> assetScript(ctx, v) },
            changes: DbChanges = DbChanges.get(ctx),
        ): SelfWatchDb = SelfWatchDb(ctx, name, version, scripts, changes)

        /**
         * Test only (used by core-db's `TestDb` fixture): a new database file [name] with its own
         * [DbChanges], separate from the shared instance.
         */
        @VisibleForTesting
        public fun createForTest(ctx: Context, name: String, changes: DbChanges): SelfWatchDb = create(ctx, name, changes = changes)

        /** Test only: closes and forgets the shared instance, so the next [get] opens a fresh one. */
        @VisibleForTesting
        public fun resetSharedForTest() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
