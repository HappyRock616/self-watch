# Self Watch task list: from now to "build and test ready"

Version 1, 2026-11-22. Each task names its lane, milestone, dependencies, rough size and how we know it's done. **Size:** S = about a day, M = 2–4 days, L = 1–2 weeks of work. "Done when" is in addition to the README's definition of done. **[HappyRock]** and **[Tony]** tasks need them; everything else is for developers, reviewed by the lead.

**Lanes:**

| Prefix | Lane |
|---|---|
| P | Plan and setup |
| H | HappyRock hands-on |
| T | Tony |
| G | Build |
| B | Data |
| A | Device |
| C | Screens |
| D | Food |
| E | Interop |
| F | Platform |
| Q | Testing |

## Phase 0: before coding starts

| ID | Task | Owner | Depends | Size | Done when |
|---|---|---|---|---|---|
| P01 | Review this build plan; mark changes | **[HappyRock]** | — | S | HappyRock approves or lists changes |
| P02 | Decide developer setup: max 4–5 at once, one git worktree each, HappyRock approves merges | **[HappyRock]** | P01 | S | Written in README |
| H03 | Create the GitHub repo (private until release) under the HappyRock616 account; decide when the first push happens | **[HappyRock]** | P01 | S | Repo URL recorded; push only on HappyRock's OK |
| H05 ✅ | OK given for 4 emulators, mixed phone/tablet/fold | **[HappyRock]** | — | S | Yes or no recorded |
| H02 | Copy FIT files from the vívoactive 5 over USB (`GARMIN/Activity`, `Monitor`, `Sleep`, `HRVStatus`, `Metrics`), and write down the watch's totals for those days (steps, sleep stages, a workout's distance and time) | **[HappyRock]** | — | S | Files + totals in `testdata/fit/` |
| H04 | Request own data exports from any of MyFitnessPal, Lose It!, Cronometer, Garmin Connect, Gadgetbridge you have; volunteers too | **[HappyRock]** | — | S (waiting time) | Files in `testdata/imports/`, personal details removed |
| ~~H06~~ | ~~Photograph barcodes~~ dropped (HappyRock, 2026-11-23): tests use generated barcode images; the scanner never saves pictures | — | — | — | — |
| H11 | USPTO search for "Self Watch" | **[HappyRock]** | — | S | Result noted (before release) |
| T01 | Work through FOODDB_SPEC Part A items 1–12 | **[Tony]** | — | L | Build produces `foods.db` in the spec layout |
| T02 | Deliver `foods-sample.db` (about 5,000 rows, final format) | **[Tony]** | T01 | S | Passes FOODDB_SPEC §13 checks |

## M0: Foundations

| ID | Task | Lane | Depends | Size | Done when |
|---|---|---|---|---|---|
| G01 | `git init`, `.gitignore` (references, calorie-app), LICENSE (AGPL), `link-client/LICENSE` (Apache), `.editorconfig` | G | P01 | S | Repo commits clean; ignored folders not tracked |
| G02 | Gradle skeleton: settings, wrapper (checksum pinned), `build-logic` convention plugins, 12 empty modules | G | G01 | M | `./gradlew build` passes on this PC |
| G03 | Version catalog: confirm each WP-01 library version is current and at least 2 weeks old | G | G02 | S | `libs.versions.toml` committed with a note of dates |
| G04 | CI workflow (build, test, lint), actions pinned by SHA | G | G02, H03 | M | Green run on GitHub (after HappyRock's push OK) or locally with `act` |
| G05 ✅ | Emulators (HappyRock: 4 max, mixed sizes): API 26 small phone, 30 large phone, 33 tablet, 36 fold; Pixel 5 covers 37 | G | H05 | S | `emulator -list-avds` shows all |
| G06 | Size script + baseline budget; native-library check | G | G02 | S | Budget file committed; CI fails on a test APK with `lib/` |
| G07 | License check script with allow-list | G | G03 | S | CI fails on a fake GPL-only dependency |
| G08 | Reproducible-build job | G | G04 | M | Two clean builds give the same unsigned APK hash |
| G09 | Network-API ban check (only `net` may use `java.net` sockets/HTTP) | G | G02 | S | CI fails on a test file in another module |
| G10 | Gradle dependency verification metadata | G | G03 | S | Tampered checksum fails the build |
| G12 | `strings.xml` skeleton, `locales_config.xml`, launcher `<queries>` | G | G02 | S | Lint clean |
| G13 | Adaptive launcher icon from `branding/icon/` (vector foreground, background, monochrome) | G | G02 | S | Icon shows on the Pixel 5 and API 26 emulator |
| G15 | README, CONTRIBUTING (no-copy rule, Ref comments), SECURITY | G | G01 | S | Reviewed |
| B01 | core-model: all types in WP-02 | B | G02 | M | Compiles as Kotlin-only; KDoc complete |
| B02 | core-model JSON mini reader and writer | B | G02 | S | Conformance tests pass |
| B03 | Test fixtures: FakeClock, FakeBlePort, FakeWatchDriver, RecordingHealthSink, FakeHttp | B | B01 | M | Used by a sample test in each lane |
| B04 | **Freeze core-model v1** (tag `core-model-v1`) | B | B01–B03 | S | Tag set; changes now need approval |
| B05 | core-db: `SelfWatchDb`, `V1.sql`, seed data, code-table sync test | B | B01 | M | Robolectric creates the database; enums match |
| B06 | `DbChanges` (broadcast + flow) and `SettingsStore` | B | B05 | S | Change in one process seen in the other (instrumented) |
| B08 | Two-process database test (`:watch` writer + main reader) | B | B06, G05 | S | No busy failures in 10,000 mixed operations |
| C01 | Theme tokens, dark and light, accent overlays, type scale | C | G02 | S | Gallery shows both themes |
| C02 | Components: InsetsPadding, SwCard, StatTile, RingView, BarsView, Sparkline, ToggleChipGroup, ListRow, SourceTag, EmptyState, SwSheet, NumberStepper | C | C01 | M | Each in the gallery at 3 font sizes |
| C03 | Debug-only component gallery activity | C | C02 | S | Opens on the Pixel 5 |
| C04 | **Look review** of the gallery | **[HappyRock]** | C03 | S | HappyRock approves or lists changes |
| C05 | Shell: `SelfWatchApp`, `AppGraph` (lazy), `MainActivity` with 5 tabs (placeholders), AddSheet stub, `ServiceClient` stub, edge-to-edge, predictive back | C | C02, B06 | M | App installs with 5 empty tabs (M0 exit) |
| F01 | `net`: NetClient, Firewall, NetworkLog (+ tiny test server) | F | B05 | M | WP-08 tests pass |
| F02 | `network_security_config.xml` (HTTPS only, 127.0.0.1 exception) | F | F01 | S | Cleartext to other hosts fails in test |

**M0 exit:**
- App installs on the Pixel 5 and API 26 with five empty tabs.
- CI green.
- Size baseline recorded.
- core-model frozen.
- Database created.

## M1: Garmin connection (the spike)

| ID | Task | Lane | Depends | Size | Done when |
|---|---|---|---|---|---|
| A01 | FIT reader: header, records, definitions, developer fields, compressed timestamps, CRC; fuzz harness | A | B04 | M | Reads every `testdata/fit` file; 10,000-mutation fuzz clean |
| A02 | `models.json` from the reference's Garmin model list (names, patterns, families, tiers); product ids where known | A | B04 | M | vívoactive 5 entry correct; tier 2 list reviewed |
| A03 | Monitoring decoder (activity minutes, HR, stress, respiration, SpO2) | A | A01, H02 | M | Day totals match the watch's (H02 notes) |
| A04 | Sleep and HRV decoders | A | A01, H02 | M | Stage minutes and HRV match the watch |
| A05 | Verify every FIT field number and rule in WP-04 against the reference and samples (cycles→steps, skin temp, resting HR) | A | A03, A04 | S | WP-04 table updated; Ref comments added |
| A06 | `Pairing` (CDM, API branches, bonding) | A | B04 | M | Pairs the vívoactive 5 without the location permission |
| A07 | `BleConnection`: operation queue, MTU, notifications, back-off, API branches; live check | A | A06 | L | Opens multi-link pipes on the vívoactive 5 and receives notifications |
| A08 | `BleLog` capture + record sessions (pairing, first sync, daily sync) | A + **[HappyRock]** | A07 | S | Sessions in `testdata/sessions/` |
| A09 | garmin `link/` (V1, V2 multi-link registration, MLR), COBS, CRC, `gfdi/` framing, `session/` handshake, capabilities, time | A | A07, A08 | L | Replay of the pairing session reaches READY; live READY on the watch; constants confirmed in the code |
| A10 | garmin `files/`: directory, download, archive flags, `SyncPlan` | A | A09 | M | Replay downloads the same files; live sync of a day |
| A11 | Find which channel carries app messages on the vívoactive 5; `CiqCodec` and `Xxtea` | A | A09 | M | Channel documented; codec vectors from a recorded message pass |
| B07 | DAOs for M1 tables (device, sync_file, activity, metric, sleep, workout) + performance measurement | B | B05 | M | WP-03 targets met on the Pixel 5; year-of-data size recorded |
| B09 | `SyncService` skeleton: foreground type, channels, notification, start/stop rules | B | B06 | M | Starts and stops correctly on API 26, 31, 34, 37 |
| B11 | `DeviceManager` with fakes: back-off, modes, event routing | B | B09, B03 | M | Unit tests pass |
| B12 | `HealthIngest` + `FileStore` | B | B07 | S | Ingest is atomic; sync_file marked only after commit |
| C06 | Debug Devices screen: pair button, connection state, "sync now", last sync | C | C05, A06 | S | HappyRock can pair and sync from the app |
| A12 | **M1 live test:** pair, sync a day into `selfwatch.db`, reconnect after walking away | A + **[HappyRock]** | A10, B12, C06 | S | Data in the database matches the watch |
| B10 | Process isolation check (`:watch` heap holds no food, interop or ui classes) | B | B09 | S | Heap dump review recorded |
| D09 | FTS4 + accent-stripping query on API 26 and 37 emulators with `foods-sample.db` | D | T02, G05 | S | Big Mac and crème brûlée found on both |

**M1 exit:**
- The vívoactive 5 pairs and syncs a day.
- Replay tests in CI.
- Performance measured.
- FTS4 proven on Android 8.

## M2: Body and Activity

| ID | Task | Lane | Depends | Size | Done when |
|---|---|---|---|---|---|
| B13 | `SummaryBuilder` (day rows; rules in WP-07) | B | B12 | M | DST and device-priority tests pass |
| B14 | Presence service (API 31+) | B | B09 | M | Service wakes when the watch comes in range (Pixel 5) |
| B15 | BootReceiver + sync-only schedule (JobScheduler) | B | B11 | S | Reboot reconnects; sync-only runs on schedule |
| B17a | DAOs: body, goals, settings helpers, summaries (week/month SQL) | B | B05 | M | Robolectric tests pass |
| A13 | Workout decoder: summary, track, route preview | A | A01 | M | A GPS run decodes: distance and time match the watch |
| C07 | `OverlayChartView` (layers, down-sampling, scrub, zoom, accessibility) | C | C02 | L | Night chart with 4 layers renders smoothly |
| C08 | Chart benchmark | C | C07 | S | < 4 ms per frame on the Pixel 5 |
| C09 | TrendChartView, WeightTrendView | C | C07 | M | Gallery shows both |
| C10 | Body home (energy card, weight, measurements, recovery) + **review** | C + **[HappyRock]** | B13, C09 | M | Matches the wireframe; HappyRock approves |
| C11 | Sleep detail + Heart detail | C | C07, B13 | M | Toggle chips, Night/Week/Month work |
| C12 | Activity tab + workouts list + workout detail (route canvas, charts) | C | A13, C09 | L | Real workouts shown |
| C13 | Weight, Measurements, Goals, Profile screens | C | B17a | M | Log, edit, delete with undo |
| E01 | interop `api/` + registry + `io/` (CSV, date and unit detectors) | E | B05 | M | Unit tests pass |
| E02 | GPX (with HR, cadence, temperature), TCX and original FIT exporters; date-range zip | E | A13, E01 | M | GPX validates against 1.1 schema |
| Q09a | GPX opens in a map app; Strava/Komoot accept it | **[HappyRock]** | E02 | S | Confirmed |
| C14 | **M2 review** | **[HappyRock]** | C10–C13 | S | Approved |

**M2 exit:** real data on Body and Activity; GPX export works.

## M3: Food

| ID | Task | Lane | Depends | Size | Done when |
|---|---|---|---|---|---|
| B16 | DAOs: custom food, servings, `my_food_fts`, diary, favorites, water, fast, recipes | B | B05 | M | Robolectric tests pass |
| D01 | `Gtin` + nutrient maths | D | B04 | S | Vector and property tests pass |
| D02 | `MyFoodsSource` + `FoodSearch` merge | D | B16 | M | Results stream in source order |
| D03 | `DiaryService`, `WaterService`, `FastService` | D | B16 | M | Copy-yesterday and undo work |
| D04 | `FoodsDbSource` + `FoodsDbHandle` (idle close) | D | T02, D09 | M | §13 spot checks pass on the sample |
| D05 | `OffSource` (barcode lookup → custom food) | D | F01, D01 | S | Live lookup works; offline and blocked handled |
| D06 | `BarcodeAnalyzer` + `ScanActivity` | D | D01 | M | Reads all 20 test frames; works on the Pixel 5 |
| D07 | `IngredientParser` + 150-line corpus | D | B04 | M | Corpus passes |
| D08 | `IngredientMatcher` + `RecipeService` + `RecipeNutrition` | D | D02, D07 | M | Stated vs computed vs partial correct |
| D10 | `Bmr` + `EnergyBudget` | D | B13 | S | Formula and floors tested |
| D11 | `FoodDbInstaller` + `RecipeBookInstaller` | D | F01, D04 | M | Bad file cases keep the old file |
| C15 | Food diary, Add food (tabs, one-tap +), Food detail, Quick add, Scan, Custom food editor | C | D02, D03, D06 | L | A full day logs in under 2 minutes |
| C16 | Recipe book, recipe detail, recipe editor | C | D08 | M | Log a serving; fix a match |
| C17 | **M3 review** | **[HappyRock]** | C15, C16 | S | Approved |

**M3 exit:** a full day logged without foods.db; recipe book works; Open Food Facts lookup works.

## M4: Import and export

| ID | Task | Lane | Depends | Size | Done when |
|---|---|---|---|---|---|
| B17b | DAOs: import batch + undo, net log, link, watch apps | B | B05 | S | Undo test passes |
| B18 | Migration test harness (ready for V2) | B | B05 | S | Harness runs in CI |
| E03 | Backup (VACUUM INTO, zip, manifest) + encryption | E | E01 | M | Encrypted and plain backups made |
| E04 | Restore (pause service, swap, migrate, restart) | E | E03, B09 | M | Round trip: identical database |
| E05 | Cronometer-layout CSV, JSON, Garmin body CSV, FIT weight exporters | E | E01, A14 | M | Golden outputs match |
| A14 | Weight decoder + `WeightFileWriter` | A | A01 | S | Round trip passes |
| E06 | MyFitnessPal, Lose It!, Cronometer importers | E | E01, H04 | L | Golden counts on sample files |
| E07 | Gadgetbridge/Pulse backup importer (table mapping from the reference's DAO generator, facts only) | E | E01, B17a | L | A real Gadgetbridge backup imports |
| E08 | Garmin "Export Your Data" importer | E | E01, A03, A13 | M | Sample export imports |
| E09 | FIT, GPX, TCX importers | E | E01, A13 | M | Round trip with E02 |
| E10 | Food-list CSV in and out | E | E01, B16 | S | Round trip |
| E11 | Recipe importers (schema.org, Paprika, Mealie, Tandoor, Meal-Master, Cooklang) + Nextcloud exporter | E | E01, D08 | L | Each sample imports; export opens in Tandoor (manual) |
| E12 | Recipe sample files (made or openly licensed) | E | — | S | One per format in `testdata/recipes/` |
| E13 | Auto backup and auto GPX export jobs | E | E03, E02 | S | Jobs run on schedule (emulator) |
| E15 | Importer fuzz + undo for every importer | E | E06–E11 | M | No crash; undo removes all |
| C18 | Data, Import (preview, confirm, undo history), Export sheet, Food data screens | C | E01–E05 | M | Every format reachable from the UI |
| Q09b | Garmin Connect web accepts the body CSV and FIT weight file | **[HappyRock]** | E05 | S | Confirmed |

**M4 exit:** every format passes its sample tests; a backup restores on another device.

## M5: Watch features and messaging

| ID | Task | Lane | Depends | Size | Done when |
|---|---|---|---|---|---|
| A15 | Our protobuf `.proto` subset + `ProtobufService` (chunking) | A | A09 | M | Round trips; replay of a protobuf exchange passes |
| A16 | Notifications (GNCS) + calls, text limits from the reference | A | A15 | L | Live: notification with reply; call accept/reject |
| A17 | Music control | A | A09 | M | Live: control two players |
| A18 | Weather (FIT weather records) | A | A09, A01 | M | Live: weather shows on the watch |
| A19 | Find phone / find watch | A | A15 | S | Live both ways |
| A20 | Alarms + device settings (method confirmed from the reference) | A | A15 | M | Live: alarm set from phone rings on watch |
| A21 | Watch apps: list, install `.prg` (upload), delete, launch | A | A10, A15 | L | Live: Just Go 5K installs and opens |
| A22 | App settings read and write | A | A15 | M | Live: Just Go 5K setting changes on the watch |
| A23 | Watch-app web requests | A | A15 | M | Live: TCS `127.0.0.1` request answered by the phone app |
| A24 | GPS assistance upload | A | A10 | S | Live: upload accepted (when enabled) |
| A25 | Auth replies (R1) and secure-pairing detection (R2) | A | A09 | S | Fake-auth off by default; R2 message shown on a simulated request |
| A26 | Replay test suite covers every feature above | A | A16–A25 | M | All replays in CI |
| B19 | NotificationRelay (filters, mapping, actions, replies) | B | B11 | M | Unit + Pixel manual checks |
| B20 | CallRelay (API branches, optional number and name) | B | B11 | M | Works with and without optional permissions |
| B21 | MusicRelay | B | B19 | S | Metadata and controls round trip |
| B22 | WeatherRelay (weather-app broadcast) | B | B11 | S | Breezy Weather broadcast reaches the watch |
| B23 | FindPhoneAlert | B | B11 | S | Rings in silent mode with screen locked |
| B24 | TimeZoneWatcher | B | B11 | S | Time-zone change updates the watch |
| B25 | WebRequestRouter (local phone app vs internet) | B | F01, A23 | S | Local requests logged, not counted as internet |
| B26 | WatchAppsStore + SettingsDefinitionParser + settings backup | B | A21, A22 | M | Just Go 5K's settings form shows real labels |
| F03 | Link AIDL + `LinkCodec` | F | B04 | S | Codec round trips |
| F04 | `link-client` library (API 21+, no dependencies, Apache-2.0) | F | F03 | M | Under 30 KB; README example works |
| F05 | `LinkService` + `LinkPermissions` (certificate check, approval notification) | F | F03, B09 | M | Denied, pending, allowed, revoked paths tested |
| F06 | `LinkRouter` + `SendQueue` + inbox | F | F05, A11 | M | Too large and not connected handled |
| F07 | Gadgetbridge Intent receiver (+ replies to the approved package) | F | F06 | S | A Gadgetbridge-style test app works unchanged |
| F08 | CaloriesLeft | F | F06, D10 | S | Budget arrives at the chosen watch app |
| F09 | Sample companion app (Self Watch side + marked stub for Garmin's toolkit) | F | F04 | S | Builds and runs on the Pixel 5 |
| F12 | **Live TCS round trip** | F + **[HappyRock]** | F06, A11 | S | Message there and back on the vívoactive 5 |
| C19 | Devices (full): watch apps, settings editor, linked apps, notification settings, alarms, watch settings | C | B26, F05 | L | All actions work live |
| Q02 | Write the manual test checklists (WP-14) | Q | — | S | Checklists in `docs/build-plan/checklists/` |
| Q07 | Relays manual checks on the Pixel 5 | Q + **[HappyRock]** | B19–B24 | S | Checklists 3–7 pass |
| A27 | **M5 live feature test** (WP-06 live list) | A + **[HappyRock]** | A16–A25, F12 | S | All pass on the vívoactive 5 |

**M5 exit:** all watch features work live on the vívoactive 5; Just Go 5K installs and its settings edit; TCS round trip works.

## M6: Privacy, Health Connect, onboarding, polish

| ID | Task | Lane | Depends | Size | Done when |
|---|---|---|---|---|---|
| E14 | Health Connect read and write (changes token, client record ids) | E | E01, B13 | L | Weight and nutrition appear in Health Connect; no duplicates |
| C20 | Me, Privacy center, Network activity, Settings, About, licenses | C | F01 | M | Every switch works; licenses complete |
| C21 | Onboarding (7 steps, permissions at the moment of use, import step, food data optional) | C | C19, C18 | M | Fresh install to synced watch in under 5 minutes |
| C22 | Accessibility pass (TalkBack, labels, chart text, focus order) | C | C10–C21 | M | Checklist 14 passes |
| C23 | Landscape and large-screen pass (API 36 rules) | C | C10–C21 | M | No broken screens on the tablet emulator |
| C24 | Home-screen widget: today's steps and calories left (optional) | C | B13 | M | Widget updates after sync |
| D12 | Plug in Tony's first `foods.db.gz` release + manifest (and recipe book if ready) | D | T04, D11 | S | Download, verify, search offline |
| T03 | Trimming rules proposal + build report | **[Tony]** | T01 | M | HappyRock approves rules |
| T04 | First `foods.db.gz` release (+ sha256, manifest) | **[Tony]** | T03 | M | Passes §13; under 40 MB |
| T05 | Build scripts moved to `tools/fooddb/`, base folder as argument | **[Tony]** | G01 | S | Rebuild from one command |
| T06 | Starter recipe book (optional): source check per recipe, `recipes.db.gz` | **[Tony]** | T01 | L | Only confirmed public-domain recipes |
| G11 | Release workflow (signing from secrets HappyRock adds) | G | G04 | S | Signed APK from a tag |
| G14 | Android 17 behavior review; decide on targetSdk 37 | G | G02 | S | Decision recorded; COMPATIBILITY updated |

**M6 exit:** privacy screens complete; Health Connect works; onboarding done; foods.db plugs in.

## Build and test ready (release candidate)

| ID | Task | Lane | Depends | Size | Done when |
|---|---|---|---|---|---|
| Q01 | Instrumented test run on all emulators API 26–37 | Q | G05, C21 | M | All pass |
| Q03 | Permission behavior per Android version | Q | C21 | M | Matches COMPATIBILITY §3 on each API |
| Q04 | 200% font, landscape and TalkBack run on API 26 and 37 | Q | C22, C23 | S | Pass |
| Q05 | Background survival on a Samsung phone (and Xiaomi or OnePlus if available) | Q + **[HappyRock]** | B14, B15 | S | Overnight connected; reboot reconnects |
| Q06 | Overnight soak on the Pixel 5 (battery under 2%) | Q + **[HappyRock]** | B14 | S | Pass |
| Q08 | Privacy capture: a day of use, all traffic matches Network activity | Q | C20 | S | No unexpected hosts |
| Q10 | **Field test:** 2 weeks of HappyRock's daily use | **[HappyRock]** | M6 | — | No data loss; no disconnection over an hour |
| Q11 | Release readiness checklist (WP-14) signed off | **[HappyRock]** | all | S | Every box ticked |
| H08 | Release keystore created and kept safe | **[HappyRock]** | — | S | Secrets added to GitHub |

## Order and parallel waves (for developers)

| Wave | Runs in parallel | Gate to the next wave |
|---|---|---|
| 0 | P01–P02, H-tasks, T01–T02, G01–G03 | Plan approved, repo ready |
| 1 | G04–G15 · B01–B03 · C01–C03 | B04 core-model frozen |
| 2 | A01, A02, A06 · B05–B08 · C04–C05 · F01–F02 | **M0 exit** |
| 3 | A03–A11 · B07, B09–B12 · C06 · D09 | **M1 exit** (live sync) |
| 4 | A13 · B13–B15, B17a · C07–C14 · E01–E02 | **M2 exit** |
| 5 | B16 · D01–D11 · C15–C17 | **M3 exit** |
| 6 | B17b, B18 · E03–E15 · A14 · C18 | **M4 exit** |
| 7 | A15–A27 · B19–B26 · F03–F12 · C19 · Q02, Q07 | **M5 exit** |
| 8 | E14 · C20–C24 · D12 · T03–T06 · G11, G14 | **M6 exit** |
| 9 | Q01–Q11, H08 | **Build and test ready** |

**Critical path:** G02 → B01 → B04 → A06 → A07 → A09 → A10 → A12 (M1). Then A15 → A16/A21 → A27 (M5), then the field test (Q10). The Garmin connection is the longest and riskiest chain, so it gets a developer from wave 2 on, with HappyRock available for live tests.

**Can move earlier:** messaging (A11, F03–F06, F12) if the TCS Companion needs it before M5; foods.db plug-in (D12) any time after T04 and D11.

**Task count:** about 150 tasks: 9 HappyRock tasks plus his reviews and live tests, 6 Tony tasks, the rest for developers.
