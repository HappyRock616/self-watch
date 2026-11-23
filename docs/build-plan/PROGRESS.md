# Self Watch: status

Updated 2026-11-23. This is the one status page. Details of how each piece was built are in the git history (`git log --oneline dev`) and in the work packages.

## Where things stand

- **Branch `dev`:** M0–M5 built, most of M6. The full build passes: every module, about 1,000 unit tests, lint, and the license check. Not yet merged to `main`; nothing pushed (no GitHub repo yet).
- **Release APK:** 2.65 MB, no native code. Budget 3.62 MB (`tools/size/`).
- **On devices:**
  - **Pixel 5 (Android 17):** clean debug build installed; screens checked.
  - **Android 8 emulator:** app runs, and all on-device database tests pass (FTS4 included).
- **Real watch:** paired and synced on 2026-11-23 (see checklists/ble-live-test.md).

| Milestone | State |
|---|---|
| M0 Foundations | Done |
| M1 Garmin connection | **Live test passed 2026-11-23** on the vívoactive 5: pairing, handshake, full sync (22 files, 0 failed), Sync now. Range/reconnect test not run yet |
| M2 Body and Activity | Done (screens, charts, GPX/FIT/TCX export) |
| M3 Food | Done, without Tony's foods.db (D12: build 4 brief in docs/FOODDB_BUILD4.md; checker tools/fooddb/check_foods.py) |
| M4 Import and export | Done (column names of the three calorie apps are unconfirmed until real exports arrive) |
| M5 Watch features | Built. **Live test pending (A27)** |
| Cards (WP-15) | Done: Body, Activity and Food are editable card stacks; 9 main goals pick research-based defaults; 7 overlay charts (O1–O7). Later, needs owner OK: mood check-in, alcohol tags, effort rating, strength sets, sweat loss |
| M6 Privacy and polish | Done except the accessibility and landscape passes on devices (C22, C23) and the optional widget (C24) |
| Release readiness | Not started (needs the live tests and field testing) |

## HappyRock's to-do

1. **Live test:** `docs/build-plan/checklists/ble-live-test.md`. Pair the vívoactive 5, sync, and share the Bluetooth capture and logs.
2. **Look review:** open the app. The Body menu has "Load demo data" (debug builds). Screenshots are in `docs/screens/`.
3. **Merge `dev` → `main`** when happy.
4. **When convenient:**
   - Copy watch FIT files into `testdata/fit/real/` (H02).
   - Collect app exports (H04).
   - Create the GitHub repo (H03).
   - Run the USPTO search (H11).
   - Later: a Pixel 2 with an old Android version for real-hardware testing of older Bluetooth.

## Decisions on record

| Decision | Made by |
|---|---|
| Ground-up build, written from scratch; references read, never copied | HappyRock |
| App ID `app.selfwatch`; GitHub; AGPL-3.0 app, Apache-2.0 client kit | HappyRock |
| **Health Connect via Android's built-in API (Android 14+)**; client library removed | HappyRock |
| **Camera built in (Camera2), no CameraX, no saved pictures** | HappyRock |
| 4 emulators max, mixed sizes; Pixel 5 is the main real device | HappyRock |
| Nutrient totals add known values and skip unknown ones | HappyRock |
| Personal data never in git (real FIT files, captures, exports) | HappyRock |
| Competitor screenshots and Tony's Garmin-SDK findings kept out of the repo | HappyRock |
| core-model tagged v1 and v1.1 (additive) | HappyRock |
| Gradle build cache off (avoids disk growth) | HappyRock |

## Known issues

- **Garmin live-test risks:**
  - advertised capability flags;
  - reliable-link handle bits;
  - how the watch reacts to our chunk-error replies;
  - whether the vívoactive 5 needs the realtime settings method for alarms;
  - secure pairing on newer firmware.
- **GPS assistance:** off by default. It goes through a watch-app web request to Garmin's server.
- **Food:** "copy from menu board", copy day, meal names and recipe import/export screens are not built yet.
- **Size:** possible later trims are ZXing 1-D readers only (about 80 KB) and Material (up to 0.34 MB, a big job).

## Tony (food data)

- **Build 3 (2026-11-23):** 68,131 foods, 6.3 MB gzipped. File format now matches the spec, and all §13 structural checks pass. **Not accepted yet:** content problems include zero-calorie whole milk, barcodes down to 869 (from 32,205), no serving weights, broken chain names and keywords ("mcdonalds big" finds nothing), flat popularity and duplicates. The app's `FoodsDbSourceTest` spot checks also fail on its sample. Fix list in FOODDB_SPEC Part A, "Review of build 3".
- **Spec and review notes:** `docs/FOODDB_SPEC.md` (Part A lists the fixes for build 2; the biggest is FTS5 → FTS4).
- **Disk:** his `calorie-app/work/` folder holds 5.4 GB of unzipped copies that can be regenerated from `raw/`. Worth clearing when he's done with it.

## Disk use (after the 2026-11-23 clean-up)

| Item | Size |
|---|---|
| Emulator images (4) | about 13 GB |
| Emulator data | wiped after each session; snapshots off; data capped at 2 GB each |
| Android SDK otherwise | about 2 GB (NDK removed: nothing used it) |
| Gradle downloaded libraries | about 5 GB (shared with other projects) |
| Self Watch repo + docs | about 100 MB |
| Agent worktrees | removed |
