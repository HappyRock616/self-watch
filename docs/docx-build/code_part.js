// Section 11: code plan. Included by build.js. Full detail in docs/CODE_PLAN.md.
module.exports = function codeSection(ctx) {
  const { c, H1, H2, P, B, N, Note, table, gap, Paragraph, TextRun, ImageRun, AlignmentType, fs, path, nextNum } = ctx;

  c.push(H1("11. Code plan"));
  c.push(P("How the code is organised, what each part does, how data moves, and the order it gets built. Full detail in docs/CODE_PLAN.md.", { keepNext: true }));
  c.push(P("**Principles:**", { keepNext: true, spacing: { after: 60 } }));
  [
    "**Written from scratch.** References are read, never copied; a one-line comment names the reference file and commit wherever a protocol detail came from.",
    "**Size first.** No native libraries; libraries for plumbing only; CI fails a build that goes over the size budget.",
    "**Kotlin-only where possible.** The Garmin protocol, the FIT reader and writer and the data model don't use Android, so they're tested on a normal computer, with no phone or watch, including in the maintainer agent's sandbox.",
    "**One direction for data:** watch → protocol → database → summaries → screens. Screens never talk to the watch.",
    "**Store plainly, format late:** whole numbers, metric units and UTC times in storage; units and local time only on screen.",
    "**Everything the user owns is in selfwatch.db** plus the workout FIT files; food data and the starter recipe book are replaceable files.",
    "**Only the watch connection runs all the time.** It lives in service, in its own small process, and can't load food, import and export or screens; everything else loads only when you open it.",
  ].forEach((t) => c.push(B(t)));

  const img = path.join(__dirname, "..", "layout", "modules.png");
  const b = fs.readFileSync(img); const w = b.readUInt32BE(16), h = b.readUInt32BE(20);
  c.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 120, after: 60 }, children: [new ImageRun({ type: "png", data: b, transformation: { width: 600, height: Math.round(600 * h / w) }, altText: { title: "Module map", description: "Self Watch modules and what each one uses", name: "modules" } })] }));
  c.push(Note("garmin is Kotlin-only, so it can't use the Android ble module directly: ble provides a byte pipe, and service plugs it into garmin. A future device family (for example smart scales) is one more module beside garmin."));

  c.push(H2("Inside the main modules"));
  c.push(table(["Module", "Main parts"], [
    ["garmin (Kotlin only)", "**link:** routes bytes for each service over Garmin's shared Bluetooth service, framing and checksums. **gfdi:** about 30 message types, acknowledgements, request-reply matching. **session:** handshake, time, battery, keep-alive. **files:** list, download, upload; which files are new. **pb:** our own message definitions for only what we use. **features:** notifications, calls, music, find, alarms, weather, settings, GPS assistance, watch-app web requests, watch apps (install, settings), app messages. **models:** the Garmin product list as one data file"],
    ["fit (Kotlin only)", "Streaming reader and writer with checksum; a small table of the messages we use; decoders for daily activity, sleep, workouts, HRV and weight; weight file writer for Garmin Connect"],
    ["ble", "Pairing through Android's Companion Device Manager (no location permission needed); connection, packet size, one-at-a-time write queue, reconnects; the byte pipe garmin uses; optional packet capture for bug reports and test recordings"],
    ["food", "Food sources (My foods, foods.db, Open Food Facts) and merged search; barcode (GTIN, CameraX + ZXing); diary (log, move, copy yesterday, quick add, favourites, recents); recipe book (ingredient parser and matcher, nutrition per serving); energy budget; foods.db and recipe book install"],
    ["interop", "Importer and exporter interfaces with an import plan (preview, detected dates and units, duplicates, undo id); backup and restore (optional passphrase encryption); CSV readers and writers; GPX, TCX and FIT; Gadgetbridge, Pulse and Garmin Connect readers; recipe formats; Health Connect"],
    ["net", "HTTP on Android's built-in connection class (no extra library); firewall checked before every request; network log of every request, allowed or blocked"],
    ["ui", "Theme (5K palette), components, the custom overlay chart (lines, bars, bands and sleep stages on one time axis, toggle chips, scrub to read values), rings, bars, formatting; charts readable as text for screen readers"],
    ["service (always on, own process)", "Watch connection (foreground while a watch is connected; woken by Android's companion-device presence on Android 12+); sync; relays for notifications, calls, music and weather; day summaries; link service (and Gadgetbridge Intent compatibility); calories left read from the day summary. Doesn't depend on food, interop or screens, so the build stops it loading them"],
    ["app (loads only when opened)", "Screens as Fragment + ViewModel, no navigation library; App.kt wiring (no Hilt) creates food parts only when a food screen asks; shares selfwatch.db with service"],
    ["core-model / core-db", "Data classes, units, time and device driver interfaces (Kotlin only) / selfwatch.db: one SQLite helper, plain SQL, tested migrations"],
    ["link-client (Apache-2.0)", "Same steps and message types as Garmin's phone toolkit, under Self Watch's own names: connect, list watches, is the app installed, send with a delivery result, receive, open the app; no dependencies"],
  ], [2000, 7360]));

  c.push(H2("selfwatch.db outline (version 1)"));
  c.push(table(["Area", "Tables"], [
    ["Devices", "device; sync_file (what was already downloaded from the watch)"],
    ["Watch data", "activity_minute (steps, active kcal, distance, intensity, keyed by device and minute); metric (heart rate, HRV, SpO2, breathing, stress, temperature in one compact table); sleep_session, sleep_stage; workout (summary, FIT file path, route preview; track points stay in the FIT file)"],
    ["Body", "body_measure (weight, body fat, waist and the rest, with source and import id); measure_kind (custom measurements)"],
    ["Food and recipe book", "custom_food, custom_serving; diary_entry (with copied nutrients); meal_slot; favourite; water; fast; recipe, recipe_item"],
    ["Goals, settings, summaries", "goal (dated, so past days keep their goal); setting (so a backup is one file); day_summary (weeks, months and years are added up from days)"],
    ["Watch apps, links, network, imports", "watch_app, watch_app_settings; link_permission; net_log; import_batch (rows carry its id for one-step undo)"],
  ], [2400, 6960]));

  c.push(H2("Key flows"));
  c.push(B("**Sync:** ble connects → garmin handshake and time → list watch files, pick new ones → download → fit decodes daily and sleep files; workout files are saved as received → written to the database in one step per file → day summaries refresh → screens update."));
  c.push(B("**Log food:** search My foods, then foods.db, then Open Food Facts (if allowed) → pick a serving → diary entry with copied nutrients → energy card updates → calories left sent to the watch app, if linked."));
  c.push(B("**Import:** pick a file → importers sniff it → preview with detected dates, units and duplicates → confirm → saved under one import id (undo deletes by that id)."));
  c.push(B("**Watch-app message:** phone app → link-client → permission check → garmin sends it to the watch app; replies come back to the app that owns that watch app."));
  c.push(B("**GPX export:** read the workout's FIT file → stream track points with heart rate, cadence and temperature to GPX; a date range becomes one zip."));

  c.push(H2("Cross-cutting"));
  c.push(B("**What runs all the time:** only service, in its own small process, while a watch is connected. It keeps notifications, calls and app messages working, as Garmin Connect and Gadgetbridge do. An idle Bluetooth connection uses little battery; syncs are batched. Android requires a small notification while it runs (silent, and the user can hide it)."));
  c.push(B("**Connection modes (Me → Settings):** always connected (default; notifications, calls and app messages work) or sync only (connects on a schedule and when the app opens, then disconnects)."));
  c.push(B("**Food only when used:** code loads only when first used, and service can't reach food code at all. foods.db opens on the first search and closes after leaving the food screens. No food background jobs. A Food tracking switch hides food entirely for people who only want the watch."));
  c.push(B("**Not used:** Google Play's download-on-demand modules: they need Google Play (F-Droid users don't have it), and the food code is small; the big part, foods.db, is already a separate optional download."));
  c.push(B("**Threads:** Kotlin coroutines; database, Bluetooth and decoding never on the screen thread."));
  c.push(B("**Permissions, only when needed:** Bluetooth connect, notification access, phone state, camera (while scanning), post notifications, connected-device foreground service, internet, Health Connect (optional). No location permission."));
  c.push(B("**Privacy:** Android cloud backup off for the app, so health data never leaves without the user choosing it; passphrase-encrypted backups; no crash reporting or analytics; a local log the user can export for bug reports."));
  c.push(B("**Time and units:** UTC in storage with each day's time-zone offset, so travel doesn't break days; units follow the phone; strings ready for translation; every chart readable as text."));

  c.push(H2("Build, libraries and tests"));
  c.push(B("Gradle with Kotlin build scripts and a version catalog; minSdk 26; R8 full mode and resource shrinking; library versions pinned and at least two weeks old when added."));
  c.push(B("**Libraries (plumbing only):** AndroidX core, AppCompat, Fragment, RecyclerView and Lifecycle; Kotlin coroutines; Material Components; protobuf-lite; CameraX and ZXing core; Health Connect client. Test-only: JUnit and Robolectric."));
  c.push(B("**Tests:** unit tests in every module; real files in testdata/ (FIT files, **recorded watch sessions** that replay through the whole Garmin stack without a watch, export files from each app, recipe files); database migration tests; a few on-device tests."));
  c.push(B("**CI on every pull request:** build, tests, lint, size budget, library license check, reproducible build check for F-Droid. The maintainer agent runs the same checks before opening a pull request."));

  c.push(H2("Build order"));
  c.push(table(["Milestone", "What gets built", "Done when"], [
    ["**M0** Foundations", "Gradle modules, CI, theme, empty tabs, selfwatch.db v1, logs, settings", "App installs with five empty tabs; CI passes; size baseline recorded"],
    ["**M1** Garmin connection (the spike)", "ble; garmin link, messages, session and files; fit decoders; writing watch data to the database", "A vívoactive 5 pairs and syncs a day of data; a recorded session replays in tests"],
    ["**M2** Body and Activity", "Summaries, overlay chart, Body home, Activity tab, sleep and heart detail, workouts, **GPX / FIT / TCX export**, weight and measurements, goals, profile", "Real data on Body and Activity; a real run's GPX opens in a map app"],
    ["**M3** Food", "Diary, custom foods, **recipe book**, quick add, barcode with Open Food Facts, energy budget; firewall and network log (first internet use)", "A full day can be logged without foods.db"],
    ["**M4** Import and export", "Backups and restore, CSV, Gadgetbridge and Garmin imports, recipe formats", "Every format passes its sample-file tests; a backup restores on another phone"],
    ["**M5** Watch features", "Notifications, calls, music, find, alarms, weather; Connect IQ install and settings editor; app messages and link-client; calories left on the watch", "All work on the vívoactive 5; Just Go 5K installs and its settings edit; TCS message round trip"],
    ["**M6** Privacy and polish", "Privacy center, Health Connect, onboarding, widgets; foods.db and starter recipe book when Tony delivers", "Privacy screens complete; offline food search works"],
    ["**M7** Release", "Signing, F-Droid metadata, store assets, docs", "Reproducible F-Droid build; first public release"],
  ], [2100, 4300, 2960]));
  c.push(gap());
  c.push(P("Watch-app messaging (M5) can move earlier if the TCS Companion needs it sooner; foods.db can plug in any time after M3."));

  c.push(H2("Device compatibility (summary)"));
  c.push(P("Full rules in docs/build-plan/COMPATIBILITY.md; every module has a compliance checklist there."));
  c.push(table(["Item", "Plan"], [
    ["Android", "Oldest 8.0 (API 26); target API 36 (Google Play rule since 31 Aug 2026); compiled against API 37 (Android 17), with a review before targeting it"],
    ["Version changes handled", "Bluetooth runtime permission (12), notification permission (13), foreground-service type connectedDevice (14), edge-to-edge (15), predictive back and large-screen rules (16), companion presence (12+), Health Connect from Android 9"],
    ["Permissions", "Asked only at the moment of use; every feature degrades gracefully. Never used: location, storage, all-packages, overlay, exact alarms, full-screen intents, accounts"],
    ["Phones", "Pixel, AOSP and GrapheneOS primary (no Google services needed); Samsung, Xiaomi and OnePlus background limits handled through the companion-device exemption plus help pages; Bluetooth quirks (error 133, slow discovery) handled in ble"],
    ["Garmin watches", "Recognised by name; features from the watch's own capability flags (about 100), plus a small model table. Tier 1 verified: vívoactive 5. Tier 2 expected: current multi-link models, checked with volunteers' recordings. Tier 3: other Garmin models, marked unverified"],
    ["Garmin risks", "R1 sign-in token expiry stops weather and GPS assistance (fake replies off by default); R2 newest secure pairing not yet understood; R3 firmware changes (caught by recorded-session tests); R4 the watch must be removed from Garmin Connect first"],
    ["Screens", "320 dp phones to tablets, portrait and landscape, dark and light, 200% font, TalkBack, right-to-left safe; units and formats follow the phone"],
  ], [2000, 7360]));
  c.push(H2("Build plan and task list"));
  c.push(P("docs/build-plan/ holds a README with the rules every developer follows, the compatibility plan, 14 work packages (one per module, with interfaces, variables, constants, libraries, tests and a definition of done) and TASKS.md: 157 tasks from now to build-and-test ready."));
  c.push(table(["Phase", "Tasks", "Ends when"], [
    ["Phase 0 (before coding)", "10", "Plan approved; repo; sample files; Tony's sample food file"],
    ["M0 Foundations", "27", "App installs with five empty tabs; CI green; core-model frozen"],
    ["M1 Garmin connection", "19", "vívoactive 5 pairs and syncs a day; replay tests in CI"],
    ["M2 Body and Activity", "16", "Real data on Body and Activity; GPX export"],
    ["M3 Food", "14", "A full day logged without foods.db; recipe book"],
    ["M4 Import and export", "17", "Every format passes its sample tests; backup restores"],
    ["M5 Watch features", "32", "All watch features live; Just Go 5K installs; TCS round trip"],
    ["M6 Privacy and polish", "13", "Privacy screens, Health Connect, onboarding, foods.db plugged in"],
    ["Build and test ready", "9", "All emulators and phones pass; 2-week field test; checklist signed"],
  ], [2600, 900, 5860]));
  c.push(P("**Lanes for parallel developers:** A Device (fit, ble, garmin), B Data (core-model, core-db, service), C Screens (ui, app), D Food, E Interop, F Platform (net, link), G Build and testing. Interfaces are frozen at the end of M0, so lanes work against the same contracts with fakes. **Critical path:** the Garmin connection (M1, then the M5 features). HappyRock's tasks: plan review, repo, sample files, live watch tests, a review per milestone, the field test and the signing key."));
};
