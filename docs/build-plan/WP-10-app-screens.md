# WP-10 app (screens and wiring)

Lane C. Milestones M0 (shell), M2 (Body, Activity), M3 (Food screens with WP-11), M4 (Data screens with WP-12), M5 (Devices), M6 (Me, onboarding, polish). Android app module. Depends on: every module.

## Goal

Build the screens in [../APP_LAYOUT.md](../APP_LAYOUT.md) (tabs, screens, menus, wireframes in `../layout/`). Screens read the database and ask the service to do things; they never talk to the watch directly.

## Wiring (`App.kt`)

```kotlin
class SelfWatchApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
class AppGraph(ctx: Context) {
    val db by lazy { SelfWatchDb.get(ctx) }
    val settings by lazy { SettingsStore(db) }
    val changes by lazy { DbChanges(ctx) }
    val http by lazy { NetClient(ctx, Firewall(settings), NetworkLog(db.netLogDao)) }
    val service by lazy { ServiceClient(ctx) }            // talks to SyncService via explicit intents + broadcast replies
    val food by lazy { FoodGraph(ctx, db, http) }         // created only when a food screen asks: WP-11
    val interop by lazy { InteropGraph(ctx, db) }         // created only when Data screens ask: WP-12
}
```

- **`ServiceClient`** sends commands to `SyncService` (sync now, pair result, send a test notification, install an app, write settings) as explicit intents with `setPackage`. Results come back as package-restricted broadcasts, and through the database.
- **`Application.onCreate`** in the `:watch` process must stay light. It checks the process name and skips app-only setup (log sink only).

## Screen pattern

- `Fragment` + `ViewModel` (lifecycle 2.11) + `StateFlow<UiState>`; collected with `repeatOnLifecycle(STARTED)`.
- ViewModels get the graph through a small `ViewModelProvider.Factory`.
- Lists use `RecyclerView` + `ListAdapter` with `DiffUtil`.
- **Navigation:**
  - `MainActivity` hosts a `BottomNavigationView` with 5 tabs. Each tab is a fragment kept with `show()`/`hide()` so state survives.
  - Detail screens are fragments added to the back stack in a full-screen container, or separate activities where noted.
  - Back is handled through `OnBackPressedDispatcher` only.
- **Errors:** `UiState` carries an optional `message` shown once as a snackbar.

## Screens (what each loads, shows and does)

| Screen | Class | Data (ViewModel loads) | Actions |
|---|---|---|---|
| Shell | `MainActivity`, `AddSheet` | Selected tab; food enabled? | Switch tabs; + opens `AddSheet` (scan, search, quick add, weight, measurements, water, fast) |
| Body (home) | `BodyFragment` | today's `DaySummary`; 7 days of summaries; latest weight and trend; latest measurements; last night's sleep, resting HR, HRV; device status | Card taps → details; pull to sync |
| Energy card | `EnergyCard` | `kcal_in`, `active_kcal + resting_kcal`, goal | Opens Energy detail (in vs out chart, formula explained: Goal − Food + Activity = Remaining) |
| Weight detail | `WeightDetailFragment` | `body_measure` weight series; goal; optional `kcal_in` overlay | Log, edit, delete, set goal, range |
| Measurements | `MeasurementsFragment` | latest + series per `BodyKind` and custom kinds | Log, edit, add custom kind |
| Sleep detail | `SleepDetailFragment` | night: `sleep_stage`, HR, HRV, respiration, SpO2, skin temp; week and month: summaries | Toggle chips, scrub, range (Night/Week/Month) |
| Heart detail | `HeartDetailFragment` | HR series, resting HR per day, HRV, stress | Same chart controls |
| Activity | `ActivityFragment` | today's rings (steps, active min, active kcal vs goals); trend (week/month/year/all) of distance, active min, steps, active kcal; recent workouts; streak (days meeting step goal) | Range, toggles, open workout |
| Workout detail | `WorkoutDetailFragment` | `workout` row; track points (from FIT via `WorkoutDecoder.track`) drawn as a route on a plain canvas with distance markers; HR, pace and elevation charts | Export GPX (default), FIT or TCX; delete |
| Food diary | `DiaryFragment` | the day's `diary_entry` by meal slot; yesterday's same meals; water; current fast | Add food, copy yesterday's meal, move, edit, delete (undo), day switcher |
| Add food | `AddFoodActivity` | search results (`FoodSearch`), Recent, Favorites, My foods, Recipes tabs | One-tap + (last serving), open detail, scan, quick add, copy from menu board |
| Barcode scan | `ScanActivity` | camera preview (CameraX) | Result → Food detail, or "not found" → Open Food Facts lookup (if allowed) → custom food form |
| Food detail | `FoodDetailSheet` | `Food` with servings | Choose serving and amount, add to meal, favorite, edit as custom |
| Quick add | `QuickAddSheet` | — | Calories (and optional macros) into a meal |
| Custom food editor | `FoodEditorActivity` | custom food | Save, delete |
| Recipe book | `RecipeBookFragment` | recipes and saved meals, tags | Search, filter, open, create, import, export |
| Recipe detail / editor | `RecipeDetailFragment`, `RecipeEditorActivity` | recipe with items and computed nutrition | Log a serving, scale, fix matches, edit |
| Devices | `DevicesFragment` | devices + status; feature list per device | Pair (CDM via `Pairing`), sync now, find watch, open settings, remove |
| Watch apps | `WatchAppsFragment` | `watch_app` rows | Install from file (`.prg` + optional settings json), delete, open on watch, settings |
| Watch app settings | `WatchAppSettingsFragment` | `SettingsForm` + current values | Edit (lists, ranges, switches, text), save to watch, restore saved copy |
| Linked phone apps | `LinkedAppsFragment` | `link_permission` rows | Allow, revoke |
| Notifications settings | `NotificationSettingsFragment` | listener access granted? per-app list | Grant access (opens system screen), deny per app, test notification |
| Alarms, watch settings | `AlarmsFragment`, `WatchSettingsFragment` | per device | Edit, send to watch |
| Me | `MeFragment` | privacy summary (requests today), list | — |
| Privacy center | `PrivacyFragment` | firewall switches (`Firewall.observe()`), permissions held | Toggle, open Network activity |
| Network activity | `NetworkActivityFragment` | `net_log` by day | Clear, export |
| Goals / Profile | `GoalsFragment`, `ProfileFragment` | goals and profile | Save (goal rows are dated) |
| Data | `DataFragment` | last backup, auto-backup state, Health Connect state | Import, export, back up now, restore, auto backup, auto GPX export, Health Connect |
| Import | `ImportActivity` | `ImportPlan` preview (WP-12) | Confirm, change date format and units, cancel; history with undo |
| Export | `ExportSheet` | choices | Pick format and range → `CREATE_DOCUMENT` → run |
| Food data | `FoodDataFragment` | installed version and size; manifest check result | Download (Wi-Fi), load from file, remove |
| Settings | `SettingsFragment` | connection mode, food tracking, units, theme, accent, debug options | Change |
| About | `AboutFragment` | version, licenses (AGPL, Apache client kit, ODbL food data, references credited), open-source notices | Export logs, Bluetooth capture (debug) |
| Onboarding | `OnboardingActivity` | steps 1–7 (APP_LAYOUT §2) | Each step skippable; permission requests at the moment of use |

**Permission requests** (`ActivityResultContracts.RequestPermission`) happen at the moment of use, with a one-sentence reason. Refusals lead to a gentle state, never a dead end (COMPATIBILITY §3).

## Strings and assets

`strings.xml` (English) holds every label above. Icons are vector drawables, Material Symbols style, drawn or adapted for the 5K look (Material Symbols are Apache-2.0). The launcher icon comes from `branding/icon/`: adaptive icon with the foreground, background and monochrome SVGs converted to vector drawables.

## Compatibility checklist

COMPATIBILITY §2 rows 29, 30, 33, 35, 36; §3; §6.
- [ ] Every screen tested at 200% font, landscape, dark and light, and with TalkBack on the API 26 and API 37 emulators.
- [ ] Edge-to-edge insets on 3-button and gesture navigation.

## Tests and definition of done

- [ ] ViewModel unit tests with fake graph pieces (no Android views).
- [ ] Robolectric smoke test per screen: it opens with sample data and with empty data.
- [ ] Wireframe match review by HappyRock per milestone (T-C10, T-C14, T-C17).
- [ ] `:watch` process start does no app-only work (test asserts `AppGraph` isn't built there).
