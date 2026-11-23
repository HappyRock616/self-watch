# Self Watch build plan

Version 1, 2026-11-22. Owner: HappyRock. Food data: Tony (see [../FOODDB_SPEC.md](../FOODDB_SPEC.md)).

This folder turns the design ([../DESIGN.md](../DESIGN.md)), the screens ([../APP_LAYOUT.md](../APP_LAYOUT.md)) and the code plan ([../CODE_PLAN.md](../CODE_PLAN.md)) into work that developers can build in parallel. **Each developer gets three things:**
1. This README (the rules every module follows).
2. [COMPATIBILITY.md](COMPATIBILITY.md) (what every module must work on).
3. Its own work package (WP).

Then it builds to the work package's definition of done.

## Files

| File | What it covers | Lane |
|---|---|---|
| [COMPATIBILITY.md](COMPATIBILITY.md) | Android versions, permissions, phone makers, Garmin watches, screens and languages; what each module must do | all |
| [TASKS.md](TASKS.md) | Every task from now until build-and-test ready, with dependencies and lanes | all |
| [WP-01-build-ci.md](WP-01-build-ci.md) | Repo, Gradle, versions, CI, size budget, release build | G Build |
| [WP-02-core-model.md](WP-02-core-model.md) | Shared data classes and interfaces | B Data |
| [WP-03-core-db.md](WP-03-core-db.md) | `selfwatch.db`: full schema, queries, two-process rules | B Data |
| [WP-04-fit.md](WP-04-fit.md) | FIT reader and writer | A Device |
| [WP-05-ble.md](WP-05-ble.md) | Bluetooth pairing and connection | A Device |
| [WP-06-garmin.md](WP-06-garmin.md) | Garmin protocol and features | A Device |
| [WP-07-service.md](WP-07-service.md) | Always-on service: connection, sync, relays, summaries | B Data |
| [WP-08-net.md](WP-08-net.md) | HTTP, firewall, network log | F Platform |
| [WP-09-ui.md](WP-09-ui.md) | Theme, components, charts | C Screens |
| [WP-10-app-screens.md](WP-10-app-screens.md) | Every screen and its data | C Screens |
| [WP-11-food.md](WP-11-food.md) | Food sources, diary, recipe book, barcode, budget | D Food |
| [WP-12-interop.md](WP-12-interop.md) | Import, export, backup, Health Connect | E Interop |
| [WP-13-link.md](WP-13-link.md) | Watch-app messages for other apps, client kit | F Platform |
| [WP-14-qa-release.md](WP-14-qa-release.md) | Test plan, device lab, release readiness | G Build |
| [WP-15-cards.md](WP-15-cards.md) | Card stacks on Body, Activity and Food: framework, presets per goal, card catalog | C Screens |

## Lanes (who works on what in parallel)

| Lane | Modules | Can start after |
|---|---|---|
| **A Device** | fit, ble, garmin | M0 core-model interfaces |
| **B Data** | core-model, core-db, service | M0 |
| **C Screens** | ui, app | M0 theme + core-model |
| **D Food** | food | core-db food tables |
| **E Interop** | interop | core-db + fit reader |
| **F Platform** | net, link, link-client | core-model |
| **G Build** | CI, size, release, QA | first |

**Interfaces first.** M0 freezes `core-model` (WP-02) and the `selfwatch.db` schema (WP-03). Lanes then work against those contracts with fakes (`FakeWatchDriver`, `FakeBlePort`, `InMemoryDb`), so no lane waits for another's real code. Changing a frozen interface needs HappyRock's approval and a note in the task list.

## Rules for every module (developers must follow)

**Code origin**
- Write everything from scratch. **Never copy code** from Gadgetbridge, Pulse, Internet Helper, Garmin's SDK or anything else. Reading the references in `SelfWatch/references/` to learn a protocol is fine.
- Where a protocol fact came from a reference, add a comment: `// Ref: Gadgetbridge 160beab41 <path> (<what>)`.
- Never open or decompile Garmin's closed SDK or the Garmin Connect app.

**Language and style**
- Kotlin 2.x, official Kotlin style. Explicit types on public API. No `!!` in production code. No reflection. No hidden Android APIs.
- Names: data with units carry the unit in the name: `kcalX10`, `sodiumMg`, `tSec`, `durationMs`, `distanceCm`. Days are `Day` (YYYYMMDD Int). Times in storage are UTC epoch seconds.
- Packages: `app.selfwatch.<module>.<area>`.
- Every file starts with the license header: AGPL-3.0-or-later, `Copyright (C) 2026 Self Watch contributors`. In `link-client` the header is Apache-2.0.

**Concurrency**
- Kotlin coroutines. Database, Bluetooth, file and network work on `Dispatchers.IO`; parsing on `Dispatchers.Default`; never on the main thread.
- Bluetooth callbacks hop onto a single-threaded dispatcher per connection.
- Structured concurrency: every scope has an owner that cancels it. No `GlobalScope`.

**Errors**
- Expected failures return results (`sealed interface Result`-style types, see WP-02); bugs throw.
- Never swallow an exception silently: log it with `Log` (WP-02 `SwLog`).
- Corrupt input (FIT files, imports, watch messages) must never crash the app. Fuzz tests prove it.

**Logging and privacy**
- `SwLog` only (writes to the local rotating log). Never log health values, food names, notification text, message payloads or contact names at INFO level. DEBUG may, and is off by default.
- No analytics, no crash reporting, no network calls except through `net` (WP-08).

**Size**
- No native libraries. No library outside WP-01's list without HappyRock's approval.
- Resources: vector drawables, no PNG bitmaps except the launcher icon fallbacks.

**Tests (part of every task)**
- Kotlin-only modules: JUnit 4 tests run on the computer.
- Android modules: JVM unit tests for logic, Robolectric where Android classes are needed, and instrumented tests only for Bluetooth, SQLite specifics and the camera.
- Real sample files go in `testdata/` (WP-14 lists them).
- Every bug fix adds a test that fails without the fix.

**Strings and accessibility**
- All user-facing text in `strings.xml`. Plurals via `plurals`. No text in images.
- Content descriptions on every control. Touch targets at least 48 dp. Works at 200% font size.

**Definition of done (every task)**
1. Code and tests are written and pass locally and in CI.
2. Lint is clean (no new warnings).
3. It meets every COMPATIBILITY.md rule that names its module.
4. The size check passes.
5. Public API has KDoc.
6. The work package's checklist for that task is ticked.
7. A pull request (or patch) describes what changed and how it was tested.

## How the build is run with developers (proposal)

- The lead assigns tasks from TASKS.md, reviews each result against its definition of done, and runs integration checks between lanes.
- At most 4–5 developers at a time, one per lane, each in its own git worktree, merging to `main` through pull requests.
- HappyRock approves merges to `main`, as with Tony, until he decides otherwise.
- Hardware tasks (pair the watch, record sessions, test on phones) are HappyRock's. They're marked **[HappyRock]** in TASKS.md.
