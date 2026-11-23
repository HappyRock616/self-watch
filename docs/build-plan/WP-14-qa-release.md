# WP-14 Testing, device lab and release readiness

Lane G, plus HappyRock for hardware. Runs through all milestones and ends at "build and test ready" and the first release.

## Test levels

| Level | Where | What | Owner |
|---|---|---|---|
| Unit | Computer, every pull request | Every module (WP definitions of done) | Each lane |
| Replay | Computer, every pull request | Recorded Garmin sessions through `garmin` + `FakeBlePort` | Lane A |
| Golden files | Computer, every pull request | FIT, imports, recipes, foods-sample.db expected results | Lanes A, D, E |
| Robolectric | Computer | Database, ViewModels, screens open with sample and empty data | Lanes B, C |
| Instrumented | Emulators API 26–37 | SQLite features, two-process database, permissions per version, onboarding, screens at 200% font and landscape | Lane G |
| Hardware | Pixel 5 + vívoactive 5 | Pairing, sync, all watch features, soak tests | HappyRock with lane A |
| Field | HappyRock's daily use for 2 weeks before release | Everything, real life | HappyRock |

## Sample files to collect (`testdata/`, no personal details)

| Folder | What | From | Task |
|---|---|---|---|
| `fit/` | Monitoring, sleep, HRV status, metrics, activity (walk, GPS run, indoor) FIT files, with the watch's own totals written down | HappyRock's vívoactive 5 over USB | T-H02 |
| `sessions/` | Bluetooth captures (BleLog) of pairing, first sync, daily sync, a notification, a call, music, find phone, app install, settings write, app message | Pixel 5 + vívoactive 5 | T-A08 |
| `imports/` | MyFitnessPal, Lose It!, Cronometer, Garmin Connect, Gadgetbridge exports | HappyRock and volunteers; personal details removed | T-H04 |
| `recipes/` | Example files of each recipe format | Made by the team, plus public examples with open licenses | T-E12 |
| `fooddb/` | `foods-sample.db` | Tony | T-T02 |
| `barcodes/` | 20 camera frames of real barcodes | HappyRock's phone | T-H06 |

## Device lab

| Device | Android | Use |
|---|---|---|
| Pixel 5 (connected) | 17 / API 37 | Primary hardware tests |
| Emulators to create | API 26, 28, 29, 31, 33, 34, 35, 36, 37 (31, 35, 37 images already installed) | Version tests (images downloaded with HappyRock's OK, T-G05) |
| Samsung phone | One UI, recent | Background survival (HappyRock or volunteer) |
| Xiaomi or OnePlus phone | recent | Background survival (volunteer) |
| vívoactive 5 | current firmware | Tier 1 watch |
| Volunteer Garmin watches | various | Tier 2 recordings |

## Manual test checklists (written in M5, run before each release)

1. **Pairing:**
   - fresh pair
   - re-pair after unpair
   - pair with Bluetooth off at start
   - deny `BLUETOOTH_CONNECT`, then grant
2. **Sync:**
   - first sync of a full watch (days of data)
   - daily sync
   - sync interrupted by walking out of range
   - sync-only mode schedule
3. **Notifications:**
   - text, long text, emoji
   - reply
   - dismiss on the phone removes it on the watch
   - per-app deny
4. **Calls:**
   - incoming call
   - accept and reject from the watch
   - with and without number/name permissions
5. **Music:** two players; play, pause, next, volume.
6. **Find phone** (screen locked, silent mode); **find watch.**
7. **Weather:** from a weather app broadcast.
8. **Watch apps:**
   - install Just Go 5K from `.prg` + settings json
   - edit settings
   - restore a saved copy
   - delete
9. **App messages:**
   - TCS round trip
   - too large
   - denied app
   - revoked app
   - Gadgetbridge Intent compatibility
10. **Food:**
    - day logged without foods.db
    - scan (found locally, found on Open Food Facts, not found)
    - recipe from a schema.org file
    - copy yesterday
11. **Import/export:**
    - each importer's sample
    - backup → restore on the emulator
    - encrypted backup
    - GPX opens in a map app
    - Garmin body CSV accepted by Garmin Connect web (HappyRock)
12. **Privacy:**
    - every request appears in Network activity
    - firewall off stops it
    - internet permission removed (GrapheneOS-style, or `adb shell cmd appops`) → everything degrades cleanly
13. **Background:**
    - overnight soak, connected in the morning, battery under 2%
    - reboot → reconnects
    - app swiped away → service survives
14. **Accessibility:** TalkBack walk-through of each tab; 200% font; landscape.

## Release readiness ("build and test ready" = all ticked)

- [ ] M0–M6 tasks done; no open "blocker" issues.
- [ ] CI green on main for a week; reproducible build verified.
- [ ] All manual checklists passed on the Pixel 5 + vívoactive 5; background checklist passed on one Samsung phone.
- [ ] Field test: 2 weeks of HappyRock's daily use without data loss or a disconnection lasting more than an hour.
- [ ] Size under budget; no native libraries; license check clean.
- [ ] About screen credits and licenses complete (AGPL app, Apache client kit, ODbL food data with Open Food Facts attribution, references credited).
- [ ] Privacy: Network activity matches a packet capture on the phone (no unexpected hosts) for a day of use.
- [ ] Docs: README, user help pages (pairing, background per maker, import, export), CONTRIBUTING, SECURITY.
- [ ] Release signing set up by HappyRock; F-Droid metadata (with the android-app-publishing skill when it's time to publish).

## Size budget tracking

`tools/size/budget.txt` holds the current budget in bytes. The CI comment on each pull request shows the size change. Any increase over 100 KB needs a sentence in the pull request saying why.
