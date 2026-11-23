# Self Watch: app layout, code structure, screens and menus

Draft v0.7, 2026-11-22 (written from scratch; import and export; recipe book; GPX export; code plan moved to CODE_PLAN.md). Companion to [DESIGN.md](DESIGN.md); the Word document is the primary copy. Wireframes: `layout/wireframes.html` and `layout/wireframes.png`.

## 1. Navigation

There are five bottom tabs. **Body is the home screen. Physical activity comes first.** A **+** button on Body and Food opens the Add sheet.

| Tab | Purpose |
|---|---|
| **Body** (home) | Calories in vs out (today and 7 days), weight trend against goal, body measurements (waist, hips, body fat), recovery (sleep, resting HR, HRV), watch status |
| **Activity** | Today: steps, active minutes and active calories rings; long trends (weeks, months, a year) of walking distance, steps and active minutes with toggle chips; recent activities and workouts; activity streak |
| **Food** | Food diary by day: meals, water, fasting |
| **Devices** | Watches and other gadgets: sync, watch apps and their settings, notifications, alarms, linked phone apps |
| **Me** | Goals, profile, privacy center, data (import, export, backup), settings, about |

**Add sheet (+):** Scan barcode · Search food · Quick add calories · Log weight · Log measurements (waist, hips, chest, body fat…) · Log water · Start or stop fast.

## 2. Screens

| Screen | Reached from | Shows | Main actions |
|---|---|---|---|
| Body (home) | Tab, app launch | Energy card (in vs out today, 7-day in/out bars, net), weight with trend and goal line, measurements grid, recovery row (sleep, resting HR, HRV) | Tap any card for its detail; + |
| Activity | Tab | Rings (steps, active minutes, active kcal), long-trend chart (Week · Month · Year · All) with toggles for distance, active minutes, steps and active kcal, recent activities, streak | Open an activity, change range, toggle lines |
| Food diary | Tab | Day switcher, meal sections with items and calories, inline "Add yesterday's [meal]", water, fasting timer | Add food, copy yesterday, edit or delete item, move item to another meal |
| Add food | + or "Add food" | Search with a Scan button; tabs Recent · Favorites · My foods · Recipes; rows with source tag and a one-tap + | One-tap re-log, open food detail, quick add, copy from menu board |
| Barcode scan | Add food, + | Camera view (CameraX + ZXing) | Found: food detail. Not found: Open Food Facts lookup (if allowed), then a custom-food form |
| Food detail | Any food row | Serving picker (named servings plus grams), amount, nutrient preview, source and data year | Add to meal, favorite, edit as custom food |
| Custom food / meal editor | Add food, Food detail | Name, brand, servings, nutrients; a meal holds a list of foods | Save, delete |
| Recipe book | Food diary menu, Add food (Recipes tab) | Recipes and saved meals: name, tags, calories per serving; search and tag filter | Open, log a serving, create, import, export |
| Recipe detail / editor | Recipe book | Servings (scalable), ingredients with their matched foods, nutrition per serving, steps, source | Log a serving, fix an ingredient match, edit, share |
| Measurements | Body, + | Waist, hips, chest, arms, thighs, body fat, each with history and trend; custom measurements allowed | Log, edit, add a custom measurement |
| Sleep detail | Body | Hypnogram with overlays (HR, HRV, breathing, SpO2, movement, temperature) switched by toggle chips; Night · Week · Month; stage totals | Toggle lines, scrub the chart, change range |
| Heart detail | Body | HR, resting HR, HRV, stress overlaid; zones | Same chart controls |
| Activity detail | Activity | Steps, intensity minutes, active calories, distance overlaid; calories burned vs eaten | Same chart controls |
| Weight | Body, + | Weight with trend line and goal; body fat, BMI, measurements; calories eaten as an optional overlay | Log weight, edit goal |
| Workouts | Activity | Workout list; detail with charts and the GPS route drawn from its points (no map tiles, so no network) | Open, export GPX (default), FIT or TCX |
| Devices | Tab | Watch card (battery, sync, find), then lists | Pair new device |
| Watch apps | Devices | Installed Connect IQ apps with icons | Open on watch, settings, delete, install from file |
| Watch app settings | Watch apps | Editor built from the app's settings definition (lists, ranges, switches); raw keys as fallback | Save to watch, restore saved copy |
| Linked phone apps | Devices | Phone apps allowed to message watch apps, with last activity | Allow, revoke |
| Notifications, alarms, watch settings | Devices | Garmin phone-to-watch features (Gadgetbridge as reference), with new screens | — |
| Me | Tab | Privacy summary card, then lists | — |
| Privacy center | Me | Requests today, firewall switches for each feature, permissions | Open Network activity, toggle features |
| Network activity | Privacy center | Every request: time, host, feature, bytes | Clear log, export |
| Goals | Me, onboarding | Calories (auto from BMR and watch activity, or manual), macros in grams, steps, weight, water, fasting window | Save |
| Profile | Me, onboarding | Height, birth year, sex for BMR, units (follow phone by default) | Save |
| Data | Me | Import, Export, Backup and restore, automatic backup and workout export, Health Connect | Open each |
| Import | Data, onboarding | Pick a file (Gadgetbridge or Pulse backup, Garmin Connect export, FIT / GPX / TCX, MyFitnessPal, Lose It!, Cronometer, recipe files, food lists, Self Watch backup); preview of the first rows with detected dates and units | Confirm, change units or date format, undo a past import |
| Export | Data | Self Watch backup zip; CSV (Cronometer layout) or JSON; Garmin body CSV or FIT weight file; activities as GPX, FIT or TCX (one, or all in a date range); recipe book; date range | Export to a file or share |
| Food data | Me | Food database: installed or not, version and size | Download (Wi-Fi only), load from file, remove |
| Appearance | Me | Dark / light / system; accent (orange default) | — |
| Settings | Me | Connection mode (always connected, or sync only), food tracking on or off, units, notifications | — |
| Onboarding | First launch | 1 Welcome and promise · 2 Profile · 3 Goals · 4 Pair watch · 5 Bring your history (import) · 6 Permissions (only what's needed) · 7 Food data (optional) | Skip any step |

## 3. Menus (top-bar overflow)

| Screen | Menu items |
|---|---|
| Body | Edit cards · Sync now |
| Activity | Range · Activity goals · Export activities (GPX, FIT, TCX) |
| Food diary | Go to date · Copy day · Recipe book · Meal names · Export day |
| Add food | Create custom food · Create recipe · Create saved meal · Scan settings |
| Recipe book | Import recipes · Export recipe book · Tags |
| Body detail screens | Range · Compare with previous period · Export data · Chart settings |
| Devices | Pair new device · Device order · Bluetooth help |
| Watch apps | Install from file · Refresh list |
| Me | Help · About |

## 4. Code structure

**The full code plan is in [CODE_PLAN.md](CODE_PLAN.md):** principles, module map (diagram in `layout/modules.png`), what is inside each module, the `selfwatch.db` outline, key flows, build and tests, and the build order (M0–M7).

In short:
- Written from scratch; references are read, never copied.
- Modules: `app`, `service` (always on, own process), `ui`, `food`, `interop`, `garmin`, `fit`, `ble`, `core-model`, `core-db`, `net`, `link-client`, plus `tools/fooddb` and `testdata/`.
- `garmin`, `fit` and `core-model` are Kotlin-only (no Android), so they are tested on a normal computer without a phone or watch.
- Screens use Fragment + ViewModel; no Hilt, no navigation library, no chart library.
- Build order: M0 foundations → M1 Garmin connection (the spike) → M2 Body and Activity → M3 Food → M4 Import and export → M5 Watch features → M6 Privacy and polish → M7 Release.
