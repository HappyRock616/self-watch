# Self Watch: Design Document

**The primary design document is `Garmin Apps/research/Gadgetbridge-Fork-Design-and-Research.docx`.** This file is a working copy for Tony; keep the two in step.

Draft v0.9, 2026-11-22. Code plan: [CODE_PLAN.md](CODE_PLAN.md). Owner: HappyRock. Food database build: Tony.

**v0.4 change (HappyRock, 2026-11-22): Self Watch is a ground-up build, not a fork.** It combines the best parts of every reference app into one new app. It works fully without a food database, and Tony's `foods.db` plugs in later. 

**v0.5 (HappyRock, 2026-11-22): written from scratch.** Gadgetbridge, Pulse and Internet Helper are **references, not code sources**: we read them to learn how things work (especially the Garmin protocol), then write our own code. Libraries are fine for standard plumbing; everything that makes Self Watch what it is gets written for Self Watch. It will be a large codebase, and that's accepted.

**Top rule: size and efficiency of the final product come first.** When two options are close, pick the smaller and faster one, even if it is more work to build.

## Slogans (kept by HappyRock, 2026-11-22)

Core idea: **my body, my data.**

- Your body. Your data. Your business.
- Know thyself. Tell no one.
- Measured by you. Seen by no one.
- Your pulse is nobody's business.
- Tracked by you, for you.

**Name: Self Watch** (chosen by HappyRock 2026-11-22; web-search screen only, weak-mark caveat; run a USPTO search before publishing). Icon: concept v3-1 "Classic" (shield, squared watch, fork and spoon, pulse). Final files in `branding/icon/`; concepts in `branding/icon-concepts/`.

---

## 1. What Self Watch is

A new Android app, built from the ground up for GrapheneOS and F-Droid users. No Google services, no account, no ads, no AI features. One app for:

1. **Body:** weight, body measurements (waist, hips, body fat), and calories in vs out.
2. **Activity:** steps, active minutes, workouts and long trends, read straight from the watch. Physical activity comes first.
3. **Food:** a diary with search, barcode scanning, custom foods and saved meals. It works on day one without a food database (section 5).
4. **Watch:** pairing, sync, notifications, Connect IQ app install and settings, and two-way messaging between phone apps and watch apps (section 4).
5. **Import and export** in the formats the reference apps use, so people can move in and out freely (section 7).

**How the reference apps are used.** All Self Watch code is written for Self Watch. Gadgetbridge, Pulse and Internet Helper are open source, so we can read exactly how they talk to a Garmin watch, read FIT files and so on, then write our own version. Closed apps (Garmin Connect, MyFitnessPal, Lose It!, Cronometer) are inspiration for features only: no names, artwork, screen designs or wording.

| Reference | Folder | Version | What we learn from it |
|---|---|---|---|
| Gadgetbridge | `Gadgetbridge/` | master `160beab41` (2026-11-21), ~900k lines | How the Garmin protocol works (~24k lines of their code); which Garmin models exist and what each supports; Bluetooth connection handling (~11k); the FIT file format; GPX export; the Garmin Connect export layout; Health Connect sync (~6k, newer than Pulse's) |
| Pulse | `Pulse/` | `4ef13c310` (2026-10-24) | Ideas for the Today screen, streaks, weekly review and widgets |
| Internet Helper | `Internethelper/` | master `b9be971` (2026-11-21), ~1.6k lines | What a network helper must offer: HTTP now; Wi-Fi, FTP and UDP when a device needs them |

**Scale:** Gadgetbridge's Garmin and Bluetooth code alone is about 50,000 lines. Self Watch will be a large codebase written from scratch, and that's accepted (HappyRock, 2026-11-22).

**Devices:** **Garmin first** (Gadgetbridge supports 193 Garmin models). Smart scales and other brands are added one family at a time, as people need them, with Gadgetbridge as the reference. This is the main trade-off of a ground-up build: Gadgetbridge's 670 models don't all arrive on day one.

---

## 2. Best of each reference app

| From | What Self Watch takes |
|---|---|
| Garmin Connect | Watch-app install and settings with proper labels; two-way messaging between phone apps and watch apps; one daily view of body, activity and sleep; long-range trends |
| Gadgetbridge | Local-only data and no account; the Garmin protocol, FIT handling, exports and imports; an Intent interface for other apps; a firewall for each feature |
| Pulse | A Today-style home screen with a goal ring, streaks, a weekly review, widgets, and themes with accent colors |
| MyFitnessPal | Large searchable food list; one-line food rows with name, serving and calories; weekly macro chart |
| Lose It! | Simple day log with a budget ring; "add yesterday's meal"; streaks and badges; an encouraging weight-goal view |
| Cronometer | Source tag on every food; nutrient targets as percentage bars; free barcode scanning; free, complete export |
| Internet Helper | The network engines, behind a firewall and a request log the user can read |
| **Self Watch's own** | Overlay charts with toggle chips (for example sleep stages with heart rate, HRV and breathing); calories left shown on the watch; works fully offline; nothing paywalled; free import and export from the phone |

Details of the calorie apps are in [PRIOR_ART.md](PRIOR_ART.md).

---

## 3. Decisions

| Topic | Decision | Why |
|---|---|---|
| Language | **Kotlin** for all code. | Android's Bluetooth, notifications, services and Health Connect are only available to Java and Kotlin. A Rust app would still need the Java runtime plus a Rust library for each processor type, so it would be larger, not smaller. |
| Rust | **None**, unless profiling shows a real slow spot. | SQLite already does the heavy work. Battery use comes from the radio and wakeups, not computing. |
| Screens | **Android Views. No Jetpack Compose.** | Compose adds a few MB. |
| Oldest Android | **Android 8.0 (minSdk 26)**; target the newest. | Modern date and time code is built in from 8.0, so no extra compatibility library. Covers nearly every phone still in use. |
| Structure | One app module plus a few library modules ([CODE_PLAN.md](CODE_PLAN.md)). Plain constructor wiring: **no Hilt, no database mapping library, no chart library.** | Each library adds size and build time. A custom chart view also gives the overlay charts their own look. |
| Libraries | Standard plumbing only: AndroidX core, AppCompat, Fragment, RecyclerView and Lifecycle; Kotlin coroutines; Material Components (tabs, chips, sheets); protobuf-lite (the Garmin protocol needs it); CameraX and ZXing (barcodes); Health Connect client (optional sync). Anything more needs a reason: size, or a job we shouldn't write ourselves. | All open source, no Google Play Services, fine for F-Droid. |
| Build size | R8 code and resource shrinking. **No native libraries, so one small download works on every phone.** | |
| Background | **Only the watch connection runs all the time,** in its own small process. Food, import and export, and screens load only when opened; food has no background jobs. Connection mode: always connected (default) or sync only. A Food tracking switch can hide food entirely. | Battery and memory. Garmin Connect and Gadgetbridge also keep an always-on connection; Self Watch keeps that part small. |
| Data on the phone | One writable `selfwatch.db` holding everything the user owns, plus a read-only `foods.db` that can be replaced. Both use Android's built-in SQLite. | A food data update never touches the user's records. |
| Food data | **Optional and pluggable** (section 5). Downloaded compressed after install, or loaded from a file. FTS4 search. | The app works without it, and food updates don't need an app release. |
| Food data build | On the PC, by Tony: **DuckDB**, with **Parquet** files between steps. DuckDB writes the final SQLite file. | Fast with very large CSV and JSON files. |
| Name | Self Watch, with its own app ID. Installs alongside Gadgetbridge. | |
| One app | Internet access is built in. No separate helper app (section 11). | HappyRock's decision: one install. |
| License | **AGPL-3.0** (recommended). | Our code is our own, but we will study AGPL code closely. The same license removes any doubt if parts end up similar, and fits F-Droid and the privacy promise. The client kit for other apps is **Apache-2.0**, so any app can use it (section 4). |

---

## 4. Watch messaging

Self Watch handles Garmin watch-app messages **in both directions**, written from scratch with Gadgetbridge's `GarminWatchAppCommunicator` and `watchapps/` as the reference:

- Phone app to watch app, and watch app to phone app.
- Phone apps connect through a small client library (`selfwatch-link-client`) or plain Intents. Self Watch also accepts Gadgetbridge's `SEND_WATCH_APP_MESSAGE` Intent, so apps already written for Gadgetbridge work unchanged.
- A permission for each phone app; delivery confirmation; an "is the app installed on the watch?" check; a way to ask the watch to open the app.
- **Limit:** a message can be at most about 14 fragments (Gadgetbridge's `MAX_MESSAGE_SIZE`), so payloads must stay compact.
- **First users:** Third Coast Sailing (as a fallback where its direct Bluetooth link isn't available), and the food tracker's "calories left" on the watch.

### How other phone apps connect (compared, 2026-11-22)

| | Garmin's phone toolkit (with Garmin Connect) | Gadgetbridge Intent | Gadgetbridge PebbleKit support | Internet Helper | **Self Watch link** |
|---|---|---|---|---|---|
| What it is | A closed library inside the phone app that talks only to the Garmin Connect app | A broadcast Intent, `SEND_WATCH_APP_MESSAGE` | Gadgetbridge answers the old Pebble app's broadcast Intents | A separate app offering bound services (AIDL) | A bound service inside Self Watch, plus the client kit |
| Direction | Both | Phone to watch only; watch replies are dropped | Both, with acknowledgements | Request and reply (not watch messaging) | Both |
| Who may use it | Any app with the toolkit, once Garmin Connect is installed | Any app, once one switch is on for that watch | Any app | Apps the user grants a "dangerous" permission | Apps the user approves one by one |
| Message format | Monkey C values as Java lists, maps, strings and numbers | JSON or text, turned into Monkey C values; no delivery result | Pebble dictionaries | — | The same as Garmin's: lists, maps, strings, numbers |
| Delivery result, installed check, open app | Yes | No | Partly | — | Yes |
| Existing apps work unchanged | — | Apps written for this Intent | Yes: Pebble's toolkit used open broadcasts, and Pebble's own app was gone | — | Gadgetbridge-Intent apps, yes; Garmin-toolkit apps need a small change |
| License | Garmin's own: may only be used to build Connect IQ apps | AGPL | AGPL | AGPL | Self Watch AGPL; client kit Apache-2.0 (recommended) |

**Verdict on posing as Garmin Connect: no.**
- Garmin's toolkit is closed and is built to find Garmin Connect's own app. Its errors include "Garmin Connect not installed" and "Garmin Connect needs an upgrade".
- For unchanged apps to work, Self Watch would have to install under Garmin's app name. That would be impersonation, impossible alongside the real Garmin Connect, and against store rules and our own rules.
- Working out the toolkit's private interface would mean studying Garmin's closed code, which its license doesn't allow. The toolkit's code was not opened for this reason.
- Gadgetbridge's PebbleKit support worked only because Pebble's toolkit used open broadcasts that any app could answer, and Pebble's own app was gone.

**What Self Watch does instead:**
1. **The client kit mirrors the shape of Garmin's toolkit**, under Self Watch's own names: the same steps (connect, list watches, check the watch app is installed, send with a delivery result, receive, open the app) and the same message types. A companion app can support both Garmin Connect and Self Watch with a small switch, and porting is mostly renaming. No Garmin code, names or package names are used.
2. **The client kit is licensed Apache-2.0**, not AGPL, so any phone app, open or closed, can use it without changing its own license. HappyRock can choose this because the kit is written from scratch.
3. **Gadgetbridge's Intent keeps working**, so apps written for Gadgetbridge need no change, and Self Watch adds what Gadgetbridge drops: watch replies go back to the phone app that owns the watch app.
4. **Security model from Internet Helper:** a bound service, plus a per-app approval screen. That is stronger than Gadgetbridge's one switch per watch.
5. A short sample shows one companion app talking to both Garmin Connect and Self Watch.

### Watch app install and settings (Connect IQ app parity)

Gadgetbridge shows how, behind an experimental switch: list the installed Connect IQ apps, delete apps, install `.prg` files (`GarminPrgInstallHandler`), and read and write each app's settings (`AppConfigHandler`). Self Watch writes its own version of each.

**What Self Watch adds:**
1. App management on by default: an installed-apps list with icons, install from a file, delete, and open on the watch.
2. **A proper settings editor.** The Connect IQ compiler writes a settings definition file (`<app>-settings.json`) next to each `.prg`. Self Watch reads it to show real labels, dropdowns, number ranges and on/off switches, and falls back to raw keys when there's no definition. Confirmed with Just Go 5K: the file holds the type, list options, min and max, and defaults. Titles are string IDs (for example `ThemeTitle`), so they must be looked up in the app's language files. Test first with Just Go 5K and the TCS Companion.
3. A saved copy of each app's settings on the phone, so they can be restored after a reinstall.

**Not possible:** browsing or installing from the Connect IQ Store. The Store needs a Garmin account and Garmin's servers, which conflicts with the no-account promise. Users can still use Garmin's app for Store installs. A curated list of open-source watch apps could come later.

---

## 5. Body and food

**Body:** weight (typed in, or from the watch or a scale), goal and trend line, body measurements (waist, hips, chest, arms, thighs, body fat, plus custom ones), BMI, and calories in vs out.

**Food:**
- Search by name or brand, with results as you type.
- Scan a barcode. If it isn't found locally, look it up online in Open Food Facts (free, no account key, fine for F-Droid), then offer a custom-food form.
- Recent foods, favorites, custom foods, the recipe book (5.2) and quick-add calories.
- A "copy from the menu board" form. US chains with 20 or more locations must post calories, so users can type in what they see.
- A daily budget: calories eaten against calories burned, from watch data.
- Remaining calories shown on the watch, using the messaging from section 4.

### 5.1 How the food database plugs in later

- **The app works without `foods.db`.** On day one, food comes from My foods (custom foods, saved meals, history) and from Open Food Facts online lookups, if the user allows them.
- All food lookups go through one interface (`FoodSource`) with three providers, searched in this order: **My foods**, then **`foods.db`** (when installed), then **Open Food Facts online** (when allowed).
- When `foods.db` is installed, search and barcode lookups work offline, instantly.
- The app reads `meta.schema_version` and only opens files it understands. Anything else gets a clear message, never a crash.
- Two ways to install it: download from Self Watch's release page (Wi-Fi only, checksum checked), or **Load from file** for people who want no network use at all.
- Diary entries copy the calories and nutrients they used, so replacing or removing `foods.db` never changes past days.
- **The layout in 6.3 is the contract between Tony and the app.**

### 5.2 Recipe book

One place for **recipes** (a dish with ingredients, servings and steps) and **saved meals** (foods logged together, like "my usual breakfast").

- **Log a serving** of any recipe in one tap. The diary copies its calories and nutrients, like any other food.
- **Nutrition per serving:**
  - If the recipe states its own nutrition (most recipe files and recipe websites do), Self Watch uses it.
  - Otherwise each ingredient line ("2 cups flour") is matched to a food from My foods, `foods.db` or Open Food Facts. The user confirms the matches, and the totals are worked out from them.
  - Lines that can't be matched are flagged, never guessed silently.
- Scale servings, edit, tag (breakfast, high protein…) and search.
- **Import** from the common recipe formats and food lists (7.1); **export** in formats other recipe apps read (7.2).
- **Starter recipe book (optional, Tony):** USDA's MyPlate Kitchen had 1,072 recipes, each with a USDA nutrition analysis. USDA retired the site on 2026-01-07, but the original pages survive in web archives. Most recipes are US government works (public domain); some came from partner programs and may still be under copyright, so check each recipe's stated source before including it. Use the original USDA pages, not third-party copies, whose added data has its own terms. Delivered like `foods.db`: optional, downloaded or loaded from a file.

---

## 6. Food database: Tony's part

### 6.1 Sources

| Source | Gives | Barcodes | How current | License |
|---|---|---|---|---|
| Open Food Facts | Packaged groceries; a few restaurant items | Yes | Live | **ODbL** |
| USDA FoodData Central, Branded Foods | US packaged groceries, reported by manufacturers | Yes (`gtin_upc`) | About twice a year (latest October 2026) | Public domain |
| USDA FoodData Central, Foundation and SR Legacy (generic foods) and FNDDS | Generic foods (apple, rice); SR Legacy "Fast Foods" group has big-chain items (e.g. 45 McDonald's, 31 Burger King) | No | SR Legacy frozen since 2018 | Public domain |
| NYC MenuStat (Historical), NYC Open Data `qgc5-ecnb` | 96 large US chains, about 65,000 menu items | No | 2017–2018 only | Free from NYC Open Data, no explicit license |

Restaurant data is from 2018 at the newest. The app labels it with its year and lets users correct it.

### 6.2 Merge rules

1. **Barcodes:** remove everything but digits, check the check digit, left-pad to 14 digits (GTIN-14), and store as a whole number. Throw away any code with a bad check digit.
2. **Groceries (Open Food Facts against USDA Branded):** match on the normalized barcode. When both have it, use USDA's nutrition numbers and Open Food Facts for names it lacks.
3. **Restaurant items (MenuStat against SR Legacy and FNDDS):** match on chain plus a fuzzy item-name match. When both have it, keep MenuStat (newer). Fill `data_year` from the source.
4. **Generic foods:** prefer Foundation, then SR Legacy, then FNDDS.
5. **Remove** items with no calories, impossible values (for example more than 900 kcal per 100 g, or nutrients adding up to more than 100 g per 100 g), and exact duplicates.
6. **Trimming fringe and outdated items:** Tony proposes the rules and the size effect of each, and HappyRock approves. Ideas: keep US products only; drop Open Food Facts items with no recent scans; drop discontinued menu items; keep the newest version of each item.

### 6.3 `foods.db` layout

**The full, authoritative contract is [FOODDB_SPEC.md](FOODDB_SPEC.md):** file settings, every column rule, text style, permanent ids, barcodes, the update manifest, the starter recipe book format, release checks and size targets. The summary below must match it.

Store whole numbers, not decimals or text, wherever possible. Units are in the column names. A nutrient that is unknown is stored as `NULL`, not 0.

```sql
-- Facts about this build of the file
CREATE TABLE meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
) WITHOUT ROWID;
-- keys: schema_version (1), data_version (YYYYMMDD), built_at,
--       sources, license ('ODbL-1.0'), attribution

CREATE TABLE chain (
  id   INTEGER PRIMARY KEY,
  name TEXT NOT NULL              -- "McDonald's"
);

CREATE TABLE food (
  id          INTEGER PRIMARY KEY,
  name        TEXT NOT NULL,
  brand       TEXT,               -- brand, or the chain name for restaurant items
  keywords    TEXT,               -- optional extra search words, e.g. "mcdonalds" (no apostrophe)
  kind        INTEGER NOT NULL,   -- 0 generic, 1 packaged, 2 restaurant
  chain_id    INTEGER,            -- restaurant items only
  source      INTEGER NOT NULL,   -- 1 OFF, 2 USDA Branded, 3 USDA Foundation, 4 SR Legacy, 5 FNDDS, 6 MenuStat
  data_year   INTEGER,            -- year the nutrition data is from
  popularity  INTEGER NOT NULL DEFAULT 0,  -- for ordering search results
  basis       INTEGER NOT NULL,   -- nutrients are per: 0 = 100 g, 1 = 100 ml, 2 = 1 item (weight unknown)
  kcal_x10    INTEGER,            -- 219 kcal = 2190
  protein_x10 INTEGER,            -- grams x 10
  fat_x10     INTEGER,
  satfat_x10  INTEGER,
  carb_x10    INTEGER,
  sugar_x10   INTEGER,
  fibre_x10   INTEGER,
  sodium_mg   INTEGER,
  cholesterol_mg INTEGER
);

-- "1 sandwich = 219 g"
CREATE TABLE serving (
  food_id   INTEGER NOT NULL,
  seq       INTEGER NOT NULL,     -- 0 = default serving
  label     TEXT NOT NULL,
  grams_x10 INTEGER,              -- NULL if unknown
  PRIMARY KEY (food_id, seq)
) WITHOUT ROWID;

CREATE TABLE barcode (
  gtin    INTEGER PRIMARY KEY,    -- normalized GTIN-14 as a number
  food_id INTEGER NOT NULL
) WITHOUT ROWID;

-- Search index that reads text from `food` instead of keeping a second copy
CREATE VIRTUAL TABLE food_fts USING fts4(
  content="food", name, brand, keywords,
  prefix="2,3",
  tokenize=unicode61 "remove_diacritics=1"
);
INSERT INTO food_fts(food_fts) VALUES('rebuild');
```

**Finishing steps, in order:** `INSERT INTO food_fts(food_fts) VALUES('optimize');`, then `ANALYZE;`, then `VACUUM;`. Keep the page size at 4096.

**Size targets:** Tony reports the file size, then the compressed size, and then the effect of each trimming rule before anything is cut. There is no fixed limit yet.

### 6.4 What Tony delivers each build

- `foods.db.gz` (gzip-compressed `foods.db`; gzip is built into Android, while zstd would need a ~1 MB native library) and its SHA-256 checksum.
- A short report: rows per source, barcode overlap between Open Food Facts and USDA, restaurant matches, items removed by each rule, and file sizes before and after compression.
- The build script, so the build can be repeated.

---

## 7. Import and export

**Everything is free and done from the phone.** By contrast, MyFitnessPal's export is paid, and Lose It! and Cronometer export only from their websites. **None of the three can import a CSV file**, and Garmin Connect doesn't read from Health Connect. So Self Watch reads everyone's export files, and writes files each destination can actually use.

**Export is a must (HappyRock):** every piece of data can leave the phone, free, in an open format.

### 7.1 Import (bring your history in)

| From | File | What comes in |
|---|---|---|
| Gadgetbridge or Pulse | Their backup zip (database, settings and the watch's FIT files) | Activity, sleep, heart rate, HRV, SpO2, stress, weight and workouts |
| Garmin Connect | The account's "Export Your Data" zip (Gadgetbridge's `GarminVendorDataImporter` shows the layout) | Workouts and other FIT files, daily summaries, hydration |
| Any sports app | FIT, GPX or TCX files | Workouts and GPS tracks |
| MyFitnessPal | Its export zip (Premium): nutrition, exercise and progress CSVs | Diary by meal (calories, macros), exercise, weight |
| Lose It! | Its account export (free, from the website): food log, weights, exercise CSVs | Diary by food, weight, exercise |
| Cronometer | Its CSV exports (website): servings, daily summary, exercises, biometrics | Diary by food with nutrients, weight and other body measurements, exercise |
| Health Connect | Read, with the user's permission | Weight, body fat, nutrition, steps, sleep and exercise written by other apps |
| Self Watch | Its own backup zip | Everything |
| Recipe websites and apps using the web standard (Nextcloud Cookbook, RecipeSage, Mealie, most recipe sites) | schema.org Recipe JSON (JSON-LD) | Recipes with ingredients, steps and stated nutrition |
| Paprika | `.paprikarecipes` export (a zip of compressed JSON files) | Recipes |
| Mealie, Tandoor | Their backup or export zip | Recipes |
| Meal-Master | `.mmf` text files (a classic format with large free archives online) | Recipes, usually without nutrition |
| Cooklang | `.cook` plain-text files (an open format) | Recipes |
| Food lists | CSV, one food per row (name, brand, serving, grams, calories, protein, fat, carbs…) | Custom foods |

The recipe formats are the most common ones that the open-source recipe manager Tandoor imports; its list of 20+ importers is a good map of what people actually use.

**How importing works:**
- Columns are matched by name, with known variants (for example Lose It! calls the meal column `Type` in some exports and `Meal` in others).
- A preview screen shows the first rows with the detected date format and units, and the user confirms them before anything is saved. Garmin Connect's own import works the same way.
- Rows already in Self Watch are skipped, so importing the same file twice is safe.
- Every import is tagged, so it can be undone in one step.
- Imported diary entries keep the calories and nutrients as recorded; they don't need `foods.db`.

### 7.2 Export (take your data anywhere)

| To | Format | Contents |
|---|---|---|
| A new phone | **Self Watch backup zip:** `selfwatch.db`, settings and workout FIT files. Optional automatic backup to a folder on a schedule (as Gadgetbridge does). | Everything |
| Strava, Komoot, Garmin Connect, map apps, any sports app | **GPX for every activity with GPS** (the default): the track plus heart rate, cadence and temperature, using Garmin's GPX extension that most apps read. Also **original FIT** (exactly as the watch recorded it) and **TCX**, which suit indoor activities with no track. One activity, or every activity in a date range as one zip. Optional automatic GPX export of each new activity to a chosen folder. | Activities and GPS tracks |
| Garmin Connect (weight) | **Garmin body CSV:** first line `Body`, then the header `Date,Time,Weight,BMI,Fat`. A **FIT weight file** carries muscle, bone and water mass too (written with our own FIT writer, no Garmin SDK). | Weight, BMI, body fat |
| Spreadsheets and tools | **CSV in Cronometer's layout,** the most complete of the three: `servings.csv` (one row per food, with nutrients), `dailySummary.csv`, `exercises.csv`, `biometrics.csv`, `notes.csv`. ISO dates (YYYY-MM-DD), units in column names (for example `Energy (kcal)`). The same data is also offered as JSON. | Diary, daily totals, exercise, body measurements, notes |
| Other recipe apps | **Recipe book** as schema.org Recipe JSON in Nextcloud Cookbook's folder layout (one folder per recipe), which Tandoor, Mealie and Nextcloud can import. Custom foods as a CSV food list. | Recipes, saved meals, custom foods |
| MyFitnessPal, Lose It!, Cronometer, other health apps | **Health Connect** (write, with permission). This is the live bridge to the calorie apps, which read weight, steps and exercise from it. | Weight, body fat, nutrition, water, steps, sleep, heart rate, HRV, SpO2, exercise |

**Before writing the importers:** collect one real export from each app (HappyRock's own or a volunteer's, with personal details removed) to use as test files. Most column headers above come from secondary sources, because the apps' help pages don't publish them. The sure parts: Garmin's body CSV layout, Lose It!'s food log (`Date,Name,Icon,Type,Quantity,Units,Calories,...`, US-style dates), and the file names of each export.

---

## 8. `selfwatch.db` (in the app)

One writable database for everything the user owns: devices, watch data, workouts, body measurements, the food diary, custom foods, the recipe book (recipes and saved meals), favorites, goals, water, fasting, watch-app settings copies, linked-app permissions and the network log.

- Whole numbers wherever possible, like `foods.db`. Minute-by-minute watch data goes in compact `WITHOUT ROWID` tables keyed by device and time.
- Small daily summaries; weeks, months and years are added up from them, so year-long charts open fast.
- Workout FIT files are kept as received, so "export original" returns exactly what the watch recorded. Daily monitoring files are read into the tables and then deleted.
- Diary entries copy the calories and nutrients they used, so replacing `foods.db` never changes past days.

---

## 9. Food data updates

1. The app reads `data_version` from its `foods.db`, if there is one.
2. It checks Self Watch's release location for a newer `foods.db.gz` and its checksum.
3. It downloads over Wi-Fi only, checks the checksum, decompresses next to the old file, and swaps the files in one step.
4. `selfwatch.db` is never touched.

---

## 10. Licenses

- App code: **AGPL-3.0** (recommended). All code is written for Self Watch, but we will study AGPL code closely, and the same license removes any doubt if parts end up similar. Thank Gadgetbridge, Pulse and Internet Helper as references in the About screen and README.
- Client kit (`link-client`): **Apache-2.0** (recommended), so any phone app, open or closed, can use it without changing its own license.
- No code, artwork or fonts are copied from the references. Don't use the Pulse or Gadgetbridge names or branding.
- `foods.db` contains Open Food Facts data, so it is published under **ODbL 1.0**. Credit Open Food Facts in the app and in the `meta` table. USDA data is public domain; NYC data is free open data.
- Don't scrape chain websites without a proper legal check: nutrition facts generally aren't protected by copyright in the US, but copying them automatically can break those sites' terms of use.

---

## 11. Internet (one app, no helper)

### 11.1 Why Gadgetbridge keeps them separate

Gadgetbridge deliberately has **no internet permission**. Anything that needs the internet goes through the separate Internet Helper app, which the user must install and allow. That gives users a guarantee they can check: the app holding their health data can't reach the internet by itself.

Self Watch is one app, so it gives up that guarantee and must earn the trust another way:

1. **Everything is off by default,** except what the app needs out of the box: the food data download, watch app web requests, apps on this phone (127.0.0.1, never leaves the phone) and GPS help for the watch. Each can be turned off. Open Food Facts barcode lookups start off.
2. A firewall switch for each feature, and for each watch app's web requests.
3. A **Network activity** screen listing every request: when, which host, which feature, and how many bytes.
4. No analytics, no crash reporting to servers, no ads, no third-party code that phones home.
5. "Load from file" for the food database, so a user can keep the network off completely.

### 11.2 What Internet Helper does (upstream `b9be971`)

| Service | What it can do | In Self Watch |
|---|---|---|
| HTTP | Send a request and get the response back; optionally over a specific Wi-Fi network | **Built now:** food data download, Open Food Facts lookups, Garmin watch-app web requests, GPS assistance data for the watch |
| FTP | Connect, log in, list folders, upload, download, delete, create folders | Built with the first device that needs it |
| UDP | Open a UDP socket to a host and port | Built with the first device that needs it |
| Wi-Fi (Android 10+) | Read the current network name, check whether Wi-Fi is on, join a named network (e.g. a watch's or camera's hotspot), disconnect | Built with the first device that needs it |

Self Watch has its own network code inside the app, so it doesn't need Internet Helper's separate-app interfaces (AIDL).

---

## 12. Reference: what Gadgetbridge does

Based on Gadgetbridge's features page and code (master `160beab41`). What works depends on the device. **Self Watch v1 covers these for Garmin watches**; the rest arrive as device families are added.

| Area | Capabilities |
|---|---|
| Phone to watch | Notifications with replies, incoming calls (accept, reject, mute), contacts, music control, weather (from weather provider apps), alarms, find phone, find device, navigation directions from navigation apps, loyalty cards and passes |
| Health data | Steps, activity and sleep, heart rate, HRV, SpO2, stress, temperature, blood pressure, ECG (some devices), VO2 max, training load, hydration, weight and body composition from scales |
| Workouts | Workouts with GPS tracks, GPX route install, exports, links to sports tracking apps |
| Watch management | Install firmware and watch faces, manage watch apps (Garmin Connect IQ, Pebble, Bangle.js and others), settings for each device, home screen widgets |
| Data | Local database, automatic export, ZIP and database export, importing from Garmin Connect, Health Connect sync, Sleep as Android |
| Automation | An Intent interface for other apps (trigger sync, exports, set alarms, change settings, send messages to watch apps), PebbleKit compatibility |
| Privacy | No account, no vendor servers, no internet permission (internet only through Internet Helper, with a firewall for each device) |

**Devices:** about **670 models** in about **120 families** (full list at https://gadgetbridge.org/gadgets/). Largest groups: Garmin 193, Amazfit / Zepp about 65, Huawei / Honor about 46, Mi Band / Xiaomi about 29, Soundcore 28, Sony 22, Colmi 17, Nothing / CMF 11+, iGPSPORT 11. Smaller groups include Pebble, Bangle.js, PineTime, Fossil Hybrid, Casio, Polar, Coros, Withings, Galaxy Buds, AsteroidOS and Wasp-OS. Beyond watches: earbuds, smart scales, heart-rate straps, bike computers and sensors, e-scooters, thermometers, a rowing machine, standing desks, power stations, DJI cameras, Flipper Zero, thermal printers, smart glasses and game controllers.

**Order after Garmin (proposal):** smart scales (weight is central to Self Watch), then heart-rate straps, then whichever watch family users ask for most.

---

## 13. Reference: Pulse

Source in `GadgetbridgeFork/Pulse/` (github.com/zoop-dev/pulse, HEAD `4ef13c310`, 2026-10-24). On F-Droid as `cc.zachy.pulse`.

- **What it is:** a Garmin-only fork of Gadgetbridge by one developer ("zoop"), with a complete visual redesign: a Today screen with a steps ring, Health, Sleep and Fitness tabs, streaks, achievements, a weekly review, widgets, themes with accent colors, and Open-Meteo weather.
- **Garmin connection code:** unchanged from Gadgetbridge as of 2026-08-28. It hasn't merged upstream since, so Gadgetbridge is the better reference for the Garmin and Health Connect code.
- **No food tracking and no watch-app messaging.**
- **License:** code AGPL-3.0; fonts (Plus Jakarta Sans, Space Grotesk) SIL OFL 1.1; some artwork CC BY-SA 4.0. Don't use the "Pulse" name or branding.
- **Use in Self Watch:** UI ideas only; nothing is copied. Any contact with the developer is HappyRock's call.

---

## 14. Maintenance by agent

Tony runs every night: it collects new issues, triages them, and proposes fixes for the top ones. HappyRock approves or rejects every change by hand.

Safety rules, because issue text is written by strangers and can try to steer the agent (prompt injection):

- The agent runs in a sandbox.
- It holds no signing keys or other secrets.
- It can only open pull requests. It never pushes to the main branch.
- Only HappyRock merges.
- It treats issue text as information, never as instructions.

Self Watch has no upstream to merge, but the agent should also watch Gadgetbridge for Garmin protocol fixes and propose matching changes in Self Watch's own code.

---

## 15. Open questions

- [ ] App ID (placeholder `app.selfwatch`), and a USPTO search for "Self Watch".
- [ ] Where the code lives (Codeberg or GitHub) and where `foods.db.gz` is published.
- [ ] Check that FTS4 with `unicode61` and `remove_diacritics` works on Android 8.0's built-in SQLite.
- [ ] Real export files from MyFitnessPal, Lose It!, Cronometer and Garmin Connect, to use as importer test files.
- [ ] Tony's trimming rules and the resulting size.
- [ ] Starter recipe book: check each MyPlate Kitchen recipe's source (US government work or partner) before including it.
- [ ] Is US-only chain coverage enough? All restaurant sources are US.
- [ ] Which device family to add after Garmin (proposal: smart scales).
- [ ] Confirm the build order in CODE_PLAN.md §8 (Garmin connection first, then Body and Activity, then food).
