# WP-12 interop (import, export, backup, Health Connect)

Lane E. Milestones M2 (GPX/FIT/TCX export), M4 (everything else), M6 (Health Connect). Android library. Depends on: core-model, core-db, fit, coroutines (Health Connect is the framework API built into Android 14+, no library). Formats are defined in [../DESIGN.md](../DESIGN.md) §7.

## Goal

Free, complete import and export from the phone, in the formats people already have, with a preview before anything is saved and a one-step undo.

## `api/`

```kotlin
interface Importer {
    val id: String                                    // "mfp", "loseit", "cronometer", "gadgetbridge", "garmin-export", "fit", "gpx", "tcx",
                                                      // "recipe-jsonld", "paprika", "mealie", "tandoor", "mealmaster", "cooklang", "foodlist-csv", "selfwatch-backup"
    fun sniff(name: String, head: ByteArray): Int     // 0..100 confidence from file name and first 64 KB
    suspend fun plan(src: InputSource): Outcome<ImportPlan>
    suspend fun commit(plan: ImportPlan, choices: ImportChoices): Outcome<ImportResult>
}
interface Exporter { val id: String; suspend fun export(req: ExportRequest, out: OutputSink): Outcome<ExportResult> }
data class ImportPlan(val importerId: String, val fileName: String, val counts: Map<String, Int>,      // "diary": 1234, "weight": 56
                      val preview: List<List<String>>, val columns: List<ColumnMapping>, val dateFormat: DateFormatGuess,
                      val units: UnitGuess, val duplicates: Int, val warnings: List<String>, val handle: Any /* parsed data */)
data class ImportChoices(val dateFormat: String, val weightUnit: String, val skipDuplicates: Boolean = true)
data class ImportResult(val importId: Long, val counts: Map<String, Int>)
class ImportRegistry(importers: List<Importer>) { fun detect(name: String, head: ByteArray): List<Pair<Importer, Int>> }
```

- `InputSource` and `OutputSink` wrap Storage Access Framework URIs (`ContentResolver.openInputStream/openOutputStream`); zip entries are streamed with `ZipInputStream`.
- Large files are streamed: plan keeps a compact parsed form (on disk in `cache/import/` if over 20 MB).
- `commit`:
  1. Creates an `import_batch` row.
  2. Writes everything in one transaction tagged with its id.
  3. Notifies `DbChanges`.
  4. Asks the service to refresh summaries for the affected days.

## `io/`

- **`CsvReader`:** RFC 4180 (quotes, embedded newlines, BOM, comma or semicolon detection).
- **`CsvWriter`:** UTF-8 with BOM for spreadsheet friendliness; option without.
- **`ColumnMatcher`:** maps header names to fields through a table of aliases per importer (case-insensitive, trims units in brackets).
- **`DateFormatDetector`:** tries `yyyy-MM-dd`, `MM/dd/yyyy`, `dd/MM/yyyy`, `dd.MM.yyyy`, with or without time, on the first 200 rows; ambiguous ones are listed in the preview for the user to choose.
- **`UnitDetector`:** kg vs lb by value range and header text; the user confirms.

## Importers

| Importer | Reads | Notes |
|---|---|---|
| `mfp` | Export zip: nutrition, exercise, progress CSVs | One diary entry per meal row (name = "MyFitnessPal <meal>", nutrients as given). Weight from progress. Headers matched by alias (unconfirmed, so the preview matters) |
| `loseit` | Account export zip: food log, weights, exercise | Food log `Date,Name,Icon,Type,Quantity,Units,Calories,…`; `Type` or `Meal` = meal slot; US dates |
| `cronometer` | `servings.csv`, `dailySummary.csv`, `exercises.csv`, `biometrics.csv`, `notes.csv` | Servings → diary entries with full nutrients; biometrics → body measures (`Metric` names mapped: Weight, Body Fat, Waist…) |
| `gadgetbridge` | Gadgetbridge or Pulse backup zip | Opens the database file from the zip with Android SQLite (read-only temp copy). Reads Garmin tables, written to selfwatch.db: activity samples, sleep stages, HRV, SpO2, stress, respiration, weight samples, workout summaries plus their FIT files from the zip's files folder. Table and column names are mapped in T-E07 from the reference's DAO generator (facts only) |
| `garmin-export` | Garmin "Export Your Data" zip | Walks `DI_CONNECT/…` folders: FIT files (nested zips) → fit decoders; hydration JSON → water; summaries JSON where useful. Folder names vary, so match by pattern |
| `fit`, `gpx`, `tcx` | One or many files (or a zip) | Workouts. FIT goes through `WorkoutDecoder`; GPX and TCX are parsed with `XmlPullParser` (built into Android). The original file is kept in `files/workouts/`: FIT in `fit_path`, GPX or TCX in `track_path` |
| `foodlist-csv` | CSV of foods | Columns: name, brand, serving, grams, kcal, protein, fat, carbs, … → custom foods |
| Recipe importers | `recipe-jsonld` (schema.org Recipe JSON, single or array, inside HTML or `.json`), `paprika` (`.paprikarecipes` = zip of gzipped JSON), `mealie` (backup zip), `tandoor` (export zip), `mealmaster` (`.mmf/.txt`, one-column format), `cooklang` (`.cook`) | → `Recipe` with items (lines kept verbatim), stated nutrition if present (schema.org `nutrition.calories` etc. per serving), tags from categories/keywords; then the user can run matching |
| `selfwatch-backup` | Our backup zip (below) | Restore |

## Exporters

| Exporter | Output | Details |
|---|---|---|
| `backup` | `selfwatch-backup-<date>.zip`, or `.swbak` when encrypted | Contains: `selfwatch.db` (made with `VACUUM INTO` a temp file, consistent while the app runs), `workouts/…fit/gpx`, `watchapps/…`, `manifest.json` (app version, schema version, created, counts). **Encryption** (setting `backup.encrypt`): PBKDF2-HMAC-SHA256 (310,000 rounds, 16-byte salt) → AES-256-GCM over the zip, with a 12-byte nonce, all from `javax.crypto` (built in). Header: magic `SWBK`, version, salt, nonce |
| `restore` | — | Check the manifest, decrypt, then replace the database: close the db in both processes (service told to pause), swap the file, run migrations, restart the service |
| `gpx` | GPX 1.1 per workout, or a zip for a range | `<trk><trkseg><trkpt lat lon><ele><time>` plus `<extensions><gpxtpx:TrackPointExtension>` with `hr`, `cad`, `atemp` (namespace `http://www.garmin.com/xmlschemas/TrackPointExtension/v1`); written with `XmlSerializer` |
| `tcx` | TCX per workout | Activity, Lap, Track, Trackpoint with HR and cadence |
| `fit` | The original FIT file, copied | |
| `cronometer-csv` | `servings.csv`, `dailySummary.csv`, `exercises.csv`, `biometrics.csv`, `notes.csv` | ISO dates, units in headers (`Energy (kcal)`), same column order as Cronometer's where known |
| `json` | One JSON file per area (diary, body, workouts summary, recipes, foods) | Plain arrays of objects, field names as in core-model |
| `garmin-body-csv` | `Body` line, then header `Date,Time,Weight,BMI,Fat` | Dates `yyyy-MM-dd`, weight in the user's chosen unit (the Garmin import asks which) |
| `fit-weight` | FIT weight file | `WeightFileWriter` (WP-04) |
| `recipes-nextcloud` | Zip: `Recipes/<name>/recipe.json` (schema.org) | Readable by Tandoor, Mealie, Nextcloud Cookbook |
| `foodlist-csv` | Custom foods CSV | |

**Automatic jobs** (`JobScheduler` in the app process, user-enabled):
- Auto backup every N days to a picked folder (`ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`).
- Auto GPX export of each new workout: the service broadcasts "workout added", and a job writes the file.

## `hc/` (Health Connect, API 34+, built into Android)

- **Platform API only:** `android.health.connect` (`HealthConnectManager`, records in `android.health.connect.datatypes`). No androidx client library (it pulled in Guava, a shaded protobuf and coroutines-guava, +1.2 MB). Every framework call sits behind `@RequiresApi(34)` / an SDK check; the app still runs on API 26.
- **Availability:** API 34+ and `getSystemService(HealthConnectManager::class.java)` non-null, else hidden. No install or update offers (Android 9–13 Health Connect app not supported).
- **Permissions:** the platform `android.permission.health.*` strings, requested with the standard `RequestMultiplePermissions` contract (Health Connect shows its own screen) and checked with `checkSelfPermission`. Rationale: `VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS` activity-alias.
  - Write: weight, body fat, nutrition, hydration, steps, distance, active calories, exercise sessions, sleep sessions, heart rate, HRV RMSSD, oxygen saturation, respiratory rate.
  - Read: weight, body fat, nutrition, steps, exercise, sleep.
  - History: older than 30 days only if the user asks; history and background read are asked for only where the platform defines those permissions.
- **Sync:**
  - Changes token based (`getChangeLogToken` / `getChangeLogs`; an expired token comes back as `ERROR_INVALID_ARGUMENT` and triggers a 30-day re-read). Callback calls (`Executor` + `OutcomeReceiver`) are wrapped in `suspendCancellableCoroutine`; `ERROR_RATE_LIMIT_EXCEEDED` → back-off, `ERROR_SECURITY` → permission missing.
  - Our records carry `clientRecordId = "sw:<table>:<id>"`, so re-writes update instead of duplicating.
  - Records from our own package are skipped on read.
  - Runs on app open and after each sync if enabled (background reads only with the background permission).

## Compatibility checklist

- [ ] SAF only, no storage permissions.
- [ ] Health Connect hidden below API 34 or when the system service is missing.
- [ ] Imports never crash on bad files (fuzz on every importer).
- [ ] Restore works across schema versions (old backup → newer app).

## Tests and definition of done

- [ ] Real sample files in `testdata/imports/` (T-H04: from HappyRock and volunteers, personal details removed) for each importer; golden counts.
- [ ] Round trips: Self Watch backup → restore → identical database (row counts and checksums per table); GPX export → GPX import → same points; Cronometer CSV export → our Cronometer import → same diary.
- [ ] Encrypted backup: wrong passphrase fails cleanly; tampered file fails the GCM check.
- [ ] `ImportDao.undo` after each importer removes everything it added.
- [ ] GPX output validates against the GPX 1.1 schema (test resource XSD) and opens in a map app (T-Q09).
