# WP-03 core-db

Lane B. Milestone M0 (schema frozen) then M1–M4 (DAOs grow). Android library. Depends on: core-model, coroutines.

## Goal

One SQLite database, `selfwatch.db`, holding everything the user owns. It's safe to use from two processes (the always-on `service` process and the screens' `app` process), fast for a year or more of minute data, and upgradeable without losing anything.

## Database setup

```kotlin
class SelfWatchDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "selfwatch.db", null, VERSION) {
    companion object { const val VERSION = 1; fun get(ctx: Context): SelfWatchDb /* one per process */ }
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()                 // WAL: readers don't block the writer
        db.execSQL("PRAGMA synchronous=NORMAL")      // safe with WAL, much faster
        db.execSQL("PRAGMA busy_timeout=5000")       // the other process may hold the write lock briefly
    }
    override fun onCreate(db: SQLiteDatabase)        // runs assets/db/V1.sql in one transaction, seeds meal slots
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int)  // runs V{n}.sql files in order, one transaction each
}
```

- **Two processes:** each process opens its own connection to the same file. Writes are short transactions. A write that gets `SQLITE_BUSY` after the busy timeout retries 3 times with 200 ms gaps, then fails with `SwError.Io`.
- **Change notices:** after a commit, the writer calls `DbChanges.notify(setOf(Table.DIARY, …))`. This sends an explicit broadcast `app.selfwatch.DB_CHANGED` to its own package (`setPackage`), guarded by a signature-level permission `app.selfwatch.permission.INTERNAL`. Inside a process it also emits on a `SharedFlow<Set<Table>>`. Screens observe with `DbChanges.observe(tables): Flow<Unit>` and re-query.
- **Thread:** every DAO function is `suspend` and runs on `Dispatchers.IO`. Bulk inserts use one `SQLiteStatement` compiled per batch, `bindLong` and `executeInsert`, inside `beginTransactionNonExclusive()`.
- **SQLite level:** only Android 8.0 features (COMPATIBILITY §8): no UPSERT (use `INSERT OR REPLACE` or `INSERT OR IGNORE` plus `UPDATE`), no window functions, no JSON functions, FTS4.

## Schema V1 (`assets/db/V1.sql`)

```sql
-- devices and sync bookkeeping
CREATE TABLE device (
  id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, address TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
  model TEXT NOT NULL, product_id INTEGER, firmware TEXT, serial INTEGER, features TEXT NOT NULL DEFAULT '',
  association_id INTEGER, added_sec INTEGER NOT NULL, last_sync_sec INTEGER, priority INTEGER NOT NULL DEFAULT 0,
  removed INTEGER NOT NULL DEFAULT 0);
CREATE TABLE sync_file (
  device_id INTEGER NOT NULL REFERENCES device(id), file_index INTEGER NOT NULL, file_type INTEGER NOT NULL,
  file_subtype INTEGER NOT NULL, t_sec INTEGER NOT NULL, size INTEGER NOT NULL, state INTEGER NOT NULL, -- 1 done, 2 failed, 3 skipped
  done_sec INTEGER, PRIMARY KEY (device_id, file_type, file_subtype, t_sec, size)) WITHOUT ROWID;

-- watch data
CREATE TABLE activity_minute (
  device_id INTEGER NOT NULL, t_sec INTEGER NOT NULL, steps INTEGER NOT NULL DEFAULT 0,
  active_kcal_x10 INTEGER NOT NULL DEFAULT 0, distance_cm INTEGER NOT NULL DEFAULT 0,
  intensity INTEGER NOT NULL DEFAULT 0, activity_type INTEGER, import_id INTEGER,
  PRIMARY KEY (device_id, t_sec)) WITHOUT ROWID;
CREATE TABLE metric (
  kind INTEGER NOT NULL, device_id INTEGER NOT NULL, t_sec INTEGER NOT NULL, value INTEGER NOT NULL, import_id INTEGER,
  PRIMARY KEY (kind, device_id, t_sec)) WITHOUT ROWID;
CREATE TABLE sleep_session (
  id INTEGER PRIMARY KEY, device_id INTEGER NOT NULL, start_sec INTEGER NOT NULL, end_sec INTEGER NOT NULL,
  score INTEGER, import_id INTEGER, UNIQUE (device_id, start_sec));
CREATE TABLE sleep_stage (
  device_id INTEGER NOT NULL, t_sec INTEGER NOT NULL, stage INTEGER NOT NULL, import_id INTEGER,
  PRIMARY KEY (device_id, t_sec)) WITHOUT ROWID;
CREATE TABLE workout (
  id INTEGER PRIMARY KEY, device_id INTEGER, sport INTEGER NOT NULL, sub_sport INTEGER,
  start_sec INTEGER NOT NULL, end_sec INTEGER NOT NULL, duration_sec INTEGER NOT NULL, distance_m INTEGER,
  kcal INTEGER, avg_hr INTEGER, max_hr INTEGER, ascent_m INTEGER, descent_m INTEGER,
  has_track INTEGER NOT NULL DEFAULT 0, fit_path TEXT, track_path TEXT /* GPX/TCX original for imports */,
  route_preview BLOB, source INTEGER NOT NULL,
  import_id INTEGER, UNIQUE (device_id, start_sec));
CREATE INDEX workout_start ON workout(start_sec);

-- body
CREATE TABLE measure_kind (id INTEGER PRIMARY KEY, name TEXT NOT NULL, unit TEXT NOT NULL, hidden INTEGER NOT NULL DEFAULT 0);
CREATE TABLE body_measure (
  id INTEGER PRIMARY KEY, t_sec INTEGER NOT NULL, kind INTEGER NOT NULL,
  custom_kind_id INTEGER REFERENCES measure_kind(id), value_x100 INTEGER NOT NULL,
  source INTEGER NOT NULL, device_id INTEGER, import_id INTEGER);
CREATE INDEX body_kind_time ON body_measure(kind, t_sec);

-- food (user side; the read-only foods.db is a separate file)
CREATE TABLE custom_food (
  id INTEGER PRIMARY KEY, name TEXT NOT NULL, brand TEXT, basis INTEGER NOT NULL,
  kcal_x10 INTEGER, protein_x10 INTEGER, fat_x10 INTEGER, satfat_x10 INTEGER, carb_x10 INTEGER,
  sugar_x10 INTEGER, fibre_x10 INTEGER, sodium_mg INTEGER, cholesterol_mg INTEGER,
  barcode INTEGER, origin INTEGER NOT NULL,            -- 1 typed, 2 Open Food Facts lookup, 3 import, 4 menu board
  origin_ref TEXT, created_sec INTEGER NOT NULL, updated_sec INTEGER NOT NULL,
  hidden INTEGER NOT NULL DEFAULT 0, import_id INTEGER);
CREATE INDEX custom_food_barcode ON custom_food(barcode);
CREATE TABLE custom_serving (
  food_id INTEGER NOT NULL REFERENCES custom_food(id) ON DELETE CASCADE, seq INTEGER NOT NULL,
  label TEXT NOT NULL, grams_x10 INTEGER, PRIMARY KEY (food_id, seq)) WITHOUT ROWID;
CREATE TABLE meal_slot (id INTEGER PRIMARY KEY, name TEXT NOT NULL, ord INTEGER NOT NULL, hidden INTEGER NOT NULL DEFAULT 0);
CREATE TABLE diary_entry (
  id INTEGER PRIMARY KEY, day INTEGER NOT NULL, meal_slot_id INTEGER NOT NULL REFERENCES meal_slot(id),
  t_sec INTEGER NOT NULL, food_origin INTEGER, food_id INTEGER, name TEXT NOT NULL, brand TEXT,
  amount_x100 INTEGER NOT NULL, serving_label TEXT NOT NULL, grams_x10 INTEGER,
  kcal_x10 INTEGER NOT NULL, protein_x10 INTEGER, fat_x10 INTEGER, satfat_x10 INTEGER, carb_x10 INTEGER,
  sugar_x10 INTEGER, fibre_x10 INTEGER, sodium_mg INTEGER, cholesterol_mg INTEGER, import_id INTEGER);
CREATE INDEX diary_day ON diary_entry(day, meal_slot_id);
CREATE INDEX diary_food ON diary_entry(food_origin, food_id, t_sec);
CREATE TABLE favorite (food_origin INTEGER NOT NULL, food_id INTEGER NOT NULL, added_sec INTEGER NOT NULL,
  PRIMARY KEY (food_origin, food_id)) WITHOUT ROWID;
CREATE TABLE water (id INTEGER PRIMARY KEY, day INTEGER NOT NULL, t_sec INTEGER NOT NULL, ml INTEGER NOT NULL, import_id INTEGER);
CREATE INDEX water_day ON water(day);
CREATE TABLE fast (id INTEGER PRIMARY KEY, start_sec INTEGER NOT NULL, end_sec INTEGER, goal_min INTEGER NOT NULL);

-- recipe book
CREATE TABLE recipe (
  id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, name TEXT NOT NULL, servings_x10 INTEGER NOT NULL,
  serving_label TEXT, prep_min INTEGER, cook_min INTEGER, steps TEXT, notes TEXT, source_name TEXT, source_url TEXT,
  kcal_x10 INTEGER, protein_x10 INTEGER, fat_x10 INTEGER, satfat_x10 INTEGER, carb_x10 INTEGER, sugar_x10 INTEGER,
  fibre_x10 INTEGER, sodium_mg INTEGER, cholesterol_mg INTEGER,      -- stated per serving, may be NULL
  origin INTEGER NOT NULL, origin_ref TEXT, created_sec INTEGER NOT NULL, updated_sec INTEGER NOT NULL, import_id INTEGER);
CREATE TABLE recipe_item (
  recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE, seq INTEGER NOT NULL, line TEXT NOT NULL,
  food_origin INTEGER, food_id INTEGER, amount_x100 INTEGER, unit TEXT, grams_x10 INTEGER, match_state INTEGER NOT NULL,
  PRIMARY KEY (recipe_id, seq)) WITHOUT ROWID;
CREATE TABLE recipe_tag (recipe_id INTEGER NOT NULL REFERENCES recipe(id) ON DELETE CASCADE, tag TEXT NOT NULL,
  PRIMARY KEY (recipe_id, tag)) WITHOUT ROWID;

-- search over the user's own foods and recipes (docid = origin*2^40 + id)
CREATE VIRTUAL TABLE my_food_fts USING fts4(name, brand, prefix="2,3", tokenize=unicode61 "remove_diacritics=1");

-- goals, settings, summaries
CREATE TABLE goal (kind INTEGER NOT NULL, from_day INTEGER NOT NULL, value INTEGER NOT NULL, PRIMARY KEY (kind, from_day)) WITHOUT ROWID;
CREATE TABLE setting (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
CREATE TABLE day_summary (
  day INTEGER PRIMARY KEY, tz_offset_min INTEGER NOT NULL, steps INTEGER, active_kcal INTEGER, resting_kcal INTEGER,
  distance_m INTEGER, active_min INTEGER, resting_hr INTEGER, hrv_avg_ms INTEGER, sleep_min INTEGER,
  kcal_in INTEGER, protein_g INTEGER, fat_g INTEGER, carb_g INTEGER, weight_kg_x100 INTEGER, water_ml INTEGER,
  updated_sec INTEGER NOT NULL);

-- watch apps, links, network, imports
CREATE TABLE watch_app (device_id INTEGER NOT NULL, app_uuid TEXT NOT NULL, name TEXT, version INTEGER, kind INTEGER,
  installed INTEGER NOT NULL, seen_sec INTEGER NOT NULL, PRIMARY KEY (device_id, app_uuid)) WITHOUT ROWID;
CREATE TABLE watch_app_settings (app_uuid TEXT NOT NULL, saved_sec INTEGER NOT NULL, values_json TEXT NOT NULL,
  definition_json TEXT, PRIMARY KEY (app_uuid, saved_sec)) WITHOUT ROWID;
CREATE TABLE link_permission (package TEXT NOT NULL, app_uuid TEXT NOT NULL, cert_sha256 TEXT NOT NULL,
  allowed INTEGER NOT NULL, decided_sec INTEGER NOT NULL, last_used_sec INTEGER,
  PRIMARY KEY (package, app_uuid)) WITHOUT ROWID;
CREATE TABLE net_log (id INTEGER PRIMARY KEY, t_sec INTEGER NOT NULL, feature INTEGER NOT NULL, host TEXT NOT NULL,
  method TEXT NOT NULL, bytes_up INTEGER NOT NULL, bytes_down INTEGER NOT NULL, result INTEGER NOT NULL);  -- result: HTTP status, or -1 blocked, -2 offline, -3 error
CREATE INDEX net_log_time ON net_log(t_sec);
CREATE TABLE import_batch (id INTEGER PRIMARY KEY, t_sec INTEGER NOT NULL, importer TEXT NOT NULL, file_name TEXT,
  counts_json TEXT NOT NULL, undone INTEGER NOT NULL DEFAULT 0);
```

**Seed data:**
- Meal slots: Breakfast, Lunch, Dinner, Snacks (`ord` 1–4).
- Settings defaults: section "Setting keys".

**Code tables** (stored as integers; kept in sync with core-model enums by a unit test):
- `body_measure.kind` = `BodyKind.code`
- `metric.kind` = `MetricKind.code`
- `*_origin` = `FoodOrigin.code`
- `workout.source` and `body_measure.source` = `DataSource.code`

## Setting keys (table `setting`)

| Key | Type | Default | Meaning |
|---|---|---|---|
| `profile.height_cm`, `profile.birth_year`, `profile.sex` | int, int, text | — | For resting-energy maths |
| `units` | `metric` / `imperial` / `phone` | `phone` | Display units |
| `theme`, `accent` | `dark` / `light` / `system`, color | `dark`, `#FF8A1F` | Look |
| `connection.mode` | `always` / `sync_only` | `always` | COMPATIBILITY / WP-07 |
| `connection.sync_only_hours` | int | 4 | Schedule for sync-only mode |
| `food.enabled` | bool | true | Food tracking switch |
| `food.db.installed_version` | int | — | foods.db data version |
| `food.goal.mode`, `food.goal.rate_g_per_week`, `food.goal.effective_kcal` | text, int, int | auto, 0, — | Food calorie goal; the effective goal is published for the service |
| `recipes.db.installed_version` | int | — | recipes.db data version |
| `hc.enabled`, `hc.background_read` | bool | false, false | Health Connect sync |
| `link.enabled`, `link.calories_app` | bool, UUID | true, — | Link for other apps; watch app for calories left |
| `notify.listener_seen`, `weather.last` | bool, JSON | —, — | Service bookkeeping |
| `goal.active_kcal`, `debug.ble_capture` | int, bool | —, debug builds on | Active-calories goal; Bluetooth capture |
| `notify.enabled`, `notify.apps_denied` | bool, comma list | true, empty | Notification relay and per-app blocklist |
| `calls.enabled`, `calls.show_number`, `calls.show_name` | bool | true, false, false | Call relay options (permissions) |
| `weather.enabled`, `weather.source` | bool, text | false, `broadcast` | Weather to watch |
| `net.<feature>` | bool | `NetFeature.defaultOn` | Firewall switch per feature |
| `garmin.fake_auth` | bool | **false** | COMPATIBILITY R1 |
| `backup.auto`, `backup.folder_uri`, `backup.every_days`, `backup.encrypt` | bool, uri, int, bool | false, —, 7, true | Automatic backup |
| `export.auto_gpx`, `export.folder_uri` | bool, uri | false, — | Auto GPX export |
| `onboarding.done` | bool | false | |

`SettingsStore` wraps the table: typed getters and setters plus `observe(key): Flow<String?>`. It's the only settings store, so a backup is one file.

## Data access objects (all `suspend`, all in `app.selfwatch.db.dao`)

| DAO | Main functions |
|---|---|
| `DeviceDao` | `upsert(DeviceInfo): Long`, `active(): List<DeviceInfo>`, `byAddress(a)`, `markRemoved(id)`, `setLastSync(id, tSec)` |
| `SyncFileDao` | `isDone(key)`, `mark(key, state)` |
| `ActivityDao` | `insert(list)` (INSERT OR REPLACE), `minutes(range, deviceId?)`, `totalsByDay(dayRange, zone)` |
| `MetricDao` | `insert(list)`, `series(kind, range)`, `dailyStats(kind, dayRange)` (min, max, average) |
| `SleepDao` | `insertSession`, `insertStages(list)`, `night(day)`, `sessions(dayRange)` |
| `WorkoutDao` | `insert(Workout): Long`, `list(limit, before)`, `get(id)`, `delete(id)`, `forExport(dayRange)` |
| `BodyDao` | `insert`, `update`, `delete`, `latest(kind)`, `series(kind, dayRange)`, `customKinds()` |
| `FoodDao` | custom foods: `insert`, `update`, `get`, `byBarcode`, `servings(id)`; `searchMine(query, limit)` via `my_food_fts` |
| `DiaryDao` | `add(entry)`, `update`, `delete`, `day(day)`, `copyMeal(fromDay, slot, toDay)`, `recents(limit)`, `frequent(limit)`, `dayTotals(day)` |
| `RecipeDao` | `insert(Recipe)` (with items and tags), `update`, `get`, `list(tag?, query?)`, `delete` |
| `GoalDao` | `set(goal)`, `goalOn(kind, day)` (latest `from_day ≤ day`) |
| `SummaryDao` | `upsertDay(DaySummary)`, `days(dayRange)`, `weeks(dayRange)` (grouped in SQL by week start), `months(...)` |
| `WatchAppDao`, `LinkDao`, `NetLogDao`, `ImportDao` | Straightforward insert, list and update. `NetLogDao.prune(olderThanSec)`. `ImportDao.undo(id)` deletes rows with that `import_id` in every table, in one transaction |

## Schema and API additions (core-model v1.1 follow-ups; V1 is unreleased, so V1.sql was edited in place)

**Schema**
- `import_id INTEGER` on `activity_minute`, `metric`, `sleep_session`, `sleep_stage` (NULL for watch syncs, so `HealthWriteDao` / `IngestBatch` writes are unchanged), with partial indexes `… WHERE import_id IS NOT NULL` (`activity_import`, `metric_import`, `sleep_session_import`, `sleep_stage_import`) so undo needs no full scan and watch rows pay nothing.
- `metric.kind` code 8 = `RESTING_KCAL` (kcal per day, one sample per local day stamped at the day's start).
- `ImportDao.undo(id)` now also deletes those four tables' rows by `import_id`. interop's `DbImportSink` still files imported watch data under a per-import device row (`import:<id>:<source>`, marked removed) and its `undo` deletes that row.

**API**
- `SelfWatchDb.inTransaction(tables = emptySet()) { db -> … }` (suspend): one write transaction that every DAO call in the block joins; one change notice (given tables plus every table the DAO calls wrote) after the commit; rollback if the block throws. The block runs on a dedicated background thread so it stays on the thread that began the transaction even across suspensions; a DAO call from another thread inside it fails with `IllegalStateException`. Nested calls join.
- `ActivityDao.insert(list, importId = null)`, `MetricDao.insert(list, importId = null)`, `SleepDao.insertSession(s, importId = null)`, `SleepDao.insertStages(list, importId = null)`.
- Range reads for exports: `DiaryDao.range(DayRange?)` (by day, meal-slot order, time), `WaterDao.range(DayRange?)`, `BodyDao.all(TimeRange? = null)`, `WorkoutDao.all()` (oldest first). Null means everything.
- `DiaryDao.lastEntryFor(FoodRef): DiaryEntry?` (newest entry for that food; uses the `diary_food` index).
- `SettingKeys` gains the keys other modules used as literals: `FOOD_GOAL_MODE`, `FOOD_GOAL_RATE_G_PER_WEEK`, `FOOD_GOAL_EFFECTIVE_KCAL` (`food.goal.effective_kcal`, written by food's `EnergyBudget`, read by the service's calories-left message), `RECIPES_DB_INSTALLED_VERSION`, `HC_ENABLED`, `HC_BACKGROUND_READ`, `DEBUG_BLE_CAPTURE`, `LINK_ENABLED`, `LINK_CALORIES_APP`, `NOTIFY_LISTENER_SEEN`, `WEATHER_LAST`, `GOAL_ACTIVE_KCAL` (no new defaults).
- Test fixture (`src/testFixtures`, `testImplementation(testFixtures(project(":core-db")))`): `TestDb.fresh(context, name = random)` (an isolated database with its own `DbChanges`; also resets the shared `DbChanges`), `TestDb.resetShared(context)` and `TestDb.freshShared(context)` for code that calls `SelfWatchDb.get`. They use the `@VisibleForTesting` `SelfWatchDb.createForTest` / `resetSharedForTest` and `DbChanges.createForTest` / `resetSharedForTest`; no reflection.

**Retention:**
- `net_log` older than 90 days is pruned daily.
- Health data is never pruned: it's the user's.

## Performance targets (checked in M1 spike task T-B07)

| Measure | Target | Measured on Pixel 5, Android 17 (B07, 2026-11-23) |
|---|---|---|
| Insert a day of minute data (1,440 activity + about 2,000 metric rows) | < 150 ms on the Pixel 5 | 38 ms median in one ingest transaction (41 ms as two transactions) |
| A year of data | about 25–35 MB on disk (measured, not guessed, in T-B07) | 22.5 MB: 525,540 activity, 729,917 metric, 23,021 sleep-stage rows, 183 workouts, 2,920 diary lines, 365 summaries (after WAL checkpoint) |
| Activity tab, 365 day-summary rows | < 30 ms | 5.4 ms median |
| Sleep detail, one night of stages + heart rate + HRV | < 50 ms | 6.2 ms median |

Also measured (same run, year database): week + month aggregation over a year 9.5 ms; `ActivityDao.totalsByDay` over 30 days 34 ms. Two-process test (B08): 5,000 writes in `:watch` and 5,000 mixed operations in the main process, 0 failures, 1,667 change notices received by broadcast. Device SQLite 3.50.6 (API 37); FTS4 with `unicode61 remove_diacritics` works. Tests: `core-db/src/androidTest` (`PerformanceTest`, `TwoProcessTest`); numbers are in logcat tag `SelfWatchPerf`.

## Tests and definition of done

- [ ] Robolectric tests for every DAO function (Robolectric uses a real SQLite).
- [ ] An instrumented test on API 26 and API 37 emulators runs V1.sql and the FTS4 query (proves the SQLite features exist).
- [ ] Two-process test: an instrumented test with a second process (`:watch`) writing while the main process reads; no `SQLITE_BUSY` failures surface.
- [ ] Migration test harness: copies of every past schema version are upgraded and checked (from V2 on).
- [ ] Code-table sync test: enum codes match the SQL comments and seeded values.
- [ ] `ImportDao.undo` removes exactly that import's rows (test with two imports).
