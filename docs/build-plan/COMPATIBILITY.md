# Self Watch compatibility and support plan

Version 1, 2026-11-22. **Every module must meet every rule here that names it** (section 8 has the per-module checklist). If a rule can't be met, the developer stops and asks HappyRock; it doesn't quietly skip it.

## 1. Support policy

| Item | Policy |
|---|---|
| Oldest Android | **8.0 (API 26)** |
| Target | **API 36 (Android 16)**: Google Play has required it for new apps and updates since 31 Aug 2026; F-Droid has no rule |
| Compile against | **API 37 (Android 17)**, installed on this PC. Raising the target to 37 is its own task after a review of Android 17's behavior changes (TASKS T-G14) |
| Phones | Any phone with Bluetooth LE. No Google Play Services needed or used |
| Watches | Garmin first (section 5). Other brands later, one family at a time |
| Distribution | F-Droid (reproducible build), GitHub release APK; Google Play optional later |

**Hardware on hand:**
- Pixel 5 running Android 17 (API 37), connected by USB.
- Emulator images for API 31, 35 and 37.
- HappyRock's vívoactive 5.

**To add:** emulator images for API 26, 28, 29, 33, 34 and 36 (T-G05). HappyRock's other phones, or volunteers', for Samsung and Xiaomi testing (T-Q05).

## 2. Android version changes and who handles them

| API | Android | Change | Module | What we do |
|---|---|---|---|---|
| 26 | 8.0 | Minimum. Background limits: no implicit broadcast receivers, background services must be foreground. Notification channels required. Companion Device Manager (CDM) `associate()` | all, service, ble | Create channels at start; connection runs as a foreground service; pair through CDM |
| 26 | 8.0 | `java.time` available | core-model | Use `java.time`, no desugaring library |
| 28 | 9 | `FOREGROUND_SERVICE` permission (normal). `TelecomManager.endCall()` | service | Declare permission; reject calls via `endCall()` with `ANSWER_PHONE_CALLS` |
| 28 | 9 | Health Connect app available from here (separate Google app on 9–13) | interop | Not used: Self Watch only uses the Health Connect built into Android 14+, so Health Connect is hidden on 9–13 |
| 29 | 10 | The incoming call number needs `READ_CALL_LOG`. Background activity starts blocked | service | Ask for call log only if the user wants caller numbers on the watch. Find-my-phone uses a high-priority notification with sound, not an activity |
| 29 | 10 | System dark theme | ui | DayNight theme follows the system |
| 30 | 11 | Package visibility | service, app | `<queries>` for launcher apps, to show app names on relayed notifications |
| 30 | 11 | Permissions auto-reset for unused apps | app | Settings tip to turn off "pause app activity if unused" |
| 31 | 12 | `BLUETOOTH_CONNECT` and `BLUETOOTH_SCAN` are runtime permissions | ble, app | Ask `BLUETOOTH_CONNECT` before pairing. CDM scans for us, so no `BLUETOOTH_SCAN`. If it's ever needed, declare it with `neverForLocation` |
| 31 | 12 | `CompanionDeviceService`, `startObservingDevicePresence` | service | The service wakes when the watch appears and rests when it leaves |
| 31 | 12 | Limits on starting foreground services from the background; `PendingIntent` must say mutable or immutable; notification trampolines blocked | service | Companion exemption (`REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`); every `PendingIntent` sets `FLAG_IMMUTABLE` unless it needs a reply; notifications open activities directly |
| 33 | 13 | `POST_NOTIFICATIONS` is a runtime permission | app, service | Ask in onboarding. Without it the service still runs, but its notification can't show, so explain that |
| 33 | 13 | CDM `onAssociationPending` / `AssociationInfo`; `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` | ble, service | Use the new callbacks from 33, the old ones below |
| 33 | 13 | New `BluetoothGatt` write and notify signatures | ble | Branch on API level; old calls below 33 |
| 33 | 13 | Per-app language; predictive back opt-in | app | `locales_config.xml`; `enableOnBackInvokedCallback="true"` |
| 34 | 14 | **Foreground service type required:** `connectedDevice` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`, plus a prerequisite (Bluetooth permission or CDM association) | service | Declare the type and permission; start only when `BLUETOOTH_CONNECT` is granted or an association exists |
| 34 | 14 | Receivers registered at runtime must say exported or not | all | Always pass `RECEIVER_NOT_EXPORTED` unless another app must reach it |
| 34 | 14 | Android asks for packet size 517 itself | ble | Still request it; use whatever `onMtuChanged` reports |
| 34 | 14 | Health Connect built into Android (`android.health.connect`) | interop | Use the built-in framework API (no client library). Available when API 34+ and `HealthConnectManager` exists; otherwise hidden. Permissions are normal runtime requests (Health Connect shows its own screen) |
| 34 | 14 | Full-screen notifications restricted to calls and alarms | service | Find-my-phone uses a heads-up notification plus alarm-stream sound |
| 34 | 14 | Font scaling up to 200% | ui, app | Layouts tested at 200% |
| 35 | 15 | **Edge-to-edge enforced** when targeting 35+ | ui, app | Every screen handles window insets (status, navigation, cut-out, keyboard) |
| 35 | 15 | Boot can't start some service types (not `connectedDevice`) | service | Restart after boot via the boot broadcast into `connectedDevice` only |
| 35 | 15 | One-time codes in notifications hidden from listeners | service | Relayed notifications may show hidden codes; that's expected |
| 35 | 15 | 16 KB memory pages | build | No native libraries, so nothing to do; CI checks there are none |
| 36 | 16 | **Edge-to-edge can't be turned off; predictive back animations on** | ui, app | Back handling only through `OnBackPressedDispatcher`; no `onBackPressed` overrides |
| 36 | 16 | On large screens (600 dp+), orientation and resize limits are ignored | app | Every screen works in landscape and resized windows |
| 36 | 16 | CDM `startObservingDevicePresence(ObservingDevicePresenceRequest)`; old call deprecated | service | Branch on API level |
| 36 | 16 | Bluetooth bond-loss broadcast | ble | When keys are missing, show "re-pair your watch" instead of retrying forever |
| 36 | 16 | Health Connect history and background read permissions (arrive with a Health Connect module update, also on some 14/15 phones) | interop | Ask only when the platform defines the permission; history only when importing older than 30 days; background read only if the user turns on automatic sync |
| 37 | 17 | Compile only for now | build | T-G14 reviews the changes before raising the target |

## 3. Permissions

| Permission | Android | Kind | Asked when | Module | If refused |
|---|---|---|---|---|---|
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | 26–30 | install-time | — | ble | — |
| `BLUETOOTH_CONNECT` | 31+ | runtime | Pairing (onboarding step 4) | ble | Can't connect; Devices shows how to grant it |
| `REQUEST_COMPANION_RUN_IN_BACKGROUND`, `REQUEST_COMPANION_USE_DATA_IN_BACKGROUND` | 26+ | granted with the association | Pairing | service | — |
| `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` | 30+ | granted with the association | Pairing | service | — |
| `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` | 33+ | install-time | — | service | Falls back to staying connected |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 28+ / 34+ | install-time | — | service | — |
| `POST_NOTIFICATIONS` | 33+ | runtime | Onboarding | service, app | Service runs; user warned |
| `RECEIVE_BOOT_COMPLETED` | all | install-time | — | service | — |
| Notification access (listener) | all | special setting | When turning on notifications | service | No notification relay or music control |
| `READ_PHONE_STATE` | all | runtime | When turning on calls | service | No call alerts |
| `READ_CALL_LOG` | 29+ | runtime | Only if the user wants caller numbers | service | Calls show "Incoming call" without the number |
| `READ_CONTACTS` | all | runtime | Only if the user wants caller names | service | Number only |
| `ANSWER_PHONE_CALLS` | 26+ | runtime | With calls | service | Watch can't answer or reject |
| `CAMERA` | all | runtime | First barcode scan | food | Manual search only |
| `INTERNET`, `ACCESS_NETWORK_STATE` | all | install-time | — | net | (GrapheneOS can remove internet: everything degrades to offline) |
| Health Connect read and write | 34+ (built in) | runtime (HC screen) | When turning on sync | interop | No sync |
| **Not used** | | | | | Location, storage, `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`, exact alarms, `USE_FULL_SCREEN_INTENT`, accounts |

Rules:
- Ask at the moment of use, with one sentence saying why.
- Every feature degrades gracefully when its permission is refused.
- **No permission is ever needed just to open the app.**

## 4. Phone makers and Android versions

| Platform | Notes | Module | What we do |
|---|---|---|---|
| Pixel, AOSP, **GrapheneOS**, LineageOS | Primary platform. GrapheneOS can remove the internet permission and has no Google services | all | Nothing uses Google Play Services; offline works everywhere |
| Samsung One UI | Puts unused apps to "sleep"; slow service discovery | service, ble | The companion association exempts us. Help screen explains "Never sleeping apps". ble waits 600 ms after connecting before discovery and retries discovery once |
| Xiaomi HyperOS / MIUI | Autostart off by default; notification access can reset after updates | service | Help screen covers autostart; the service checks listener access every start and posts a "fix it" notification |
| OnePlus, Oppo, Realme (ColorOS) | Aggressive battery limits | service | Battery "unrestricted" tip; `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` allowed for companion apps |
| Motorola, Nokia, Sony | Mostly stock | — | Normal testing |
| Huawei / Honor (no Google services) | Battery manager | service | Same tips |
| Any | GATT error 133, bonding dialogs, packet size limits | ble | Retry with back-off; close and reopen the connection on 133; never assume packet size above 23 until `onMtuChanged` |

The help screens link to plain instructions per maker, written for Self Watch (dontkillmyapp.com is shown as a reference link, as text).

## 5. Garmin watches

**How a watch is recognized and supported**
1. Pairing finds watches by Bluetooth name (CDM filter) or Garmin's manufacturer id (`0x0087`) in the advertisement.
2. After connecting, the watch's device information message gives its product number and name, and its configuration message gives a list of about 100 **capability flags**.
3. Features turn on from the capability flags, not from a per-model code file.
4. A small data file, `garmin/models.json`, adds per-model facts the flags don't cover: display name, name pattern, product numbers, family, support tier, feature overrides (for example, the vívoactive 5 has no training load) and known quirks.

**Protocol generations**

| Generation | Bluetooth service | Self Watch |
|---|---|---|
| V0 (oldest) | `9b012401-bc30-ce9a-e111-0f67e491abde` | Not supported in v1 |
| V1 | `6a4e2401-667b-11e3-949a-0800200c9a66` (send `6a4e4c80-…`, receive `6a4ecd28-…`) | Supported |
| V2 multi-link | `6a4e2800-667b-11e3-949a-0800200c9a66` (5 receive/send pairs `6a4e2810…2814` / `6a4e2820…2824`) | **Primary**; the vívoactive 5 uses this. M1 confirms it |

**Support tiers**

| Tier | Meaning | Models |
|---|---|---|
| 1 Verified | Tested on real hardware before each release | vívoactive 5 (HappyRock) |
| 2 Expected | Same protocol and features; checked with recorded sessions from volunteers | Current multi-link models that Gadgetbridge supports; the list is built in T-A02 |
| 3 Should work | Recognized, no recordings yet; the app says "not yet verified" | Other Garmin models in the reference list |
| Not supported | | V0 devices, non-watch Garmin devices (scales, bike computers) until their families are added |

**Feature to capability map** (feature hidden when its flag is missing)

| Self Watch feature | Garmin capability flag(s) |
|---|---|
| Notifications | `GNCS` |
| Canned replies | `CUSTOM_CANNED_TEXT_LIST_SUPPORT`, `SMS_NOTIFICATIONS` |
| Music control | `BASIC_MUSIC_CONTROLS`, `ADVANCED_MUSIC_CONTROLS` |
| Find phone / find watch | `FIND_MY_PHONE` / `FIND_MY_WATCH` |
| Weather | `WEATHER_CONDITIONS`, `WEATHER_ALERTS` |
| Install watch apps, faces, widgets, data fields | `CONNECTIQ_WATCH_APP_DOWNLOAD`, `CONNECTIQ_WATCH_FACE_DOWNLOAD`, `CONNECTIQ_WIDGET_DOWNLOAD`, `CONNECTIQ_DATA_FIELD_DOWNLOAD` |
| Manage installed apps | `CONNECTIQ_APP_MANAGEMENT` |
| App settings | `CONNECTIQ_SETTINGS` |
| App messages (both ways) | `CONNECTIQ_WATCH_APP_DOWNLOAD` (the reference's gate; confirm in M1) |
| Watch-app web requests | `CONNECTIQ_HTTP` |
| Open an app on the watch | `LAUNCH_REMOTE_CIQ_APP_SUPPORT` |
| GPS assistance | `GPS_EPHEMERIS_DOWNLOAD` |
| Time from phone | `CURRENT_TIME_REQUEST_SUPPORT` |
| Health data | Whatever file types the watch lists: `MONITOR`, `SLEEP`, `HRV_STATUS`, `SKIN_TEMP`, `METRICS`, `ACTIVITY` |
| Later: contacts, calendar, courses, workouts | `CONTACTS_SUPPORT`, `CALENDAR`, `COURSE_DOWNLOAD`, `WORKOUT_DOWNLOAD` |

**Garmin risks**

| # | Risk | Plan |
|---|---|---|
| R1 | Some watches carry a Garmin sign-in token; when it expires, weather and GPS assistance stop. The reference can send fake sign-in replies, but using Garmin Connect afterwards may need a factory reset | Fake replies **off** by default; an advanced setting with that warning, worded plainly |
| R2 | Newest firmware may require Garmin's secure pairing (messages 5101–5112), which the reference doesn't fully implement | Detect it and tell the user plainly; the maintainer agent watches the reference for progress |
| R3 | Firmware updates change the protocol | Recorded-session tests catch breaks; the maintainer agent watches Gadgetbridge's Garmin changes |
| R4 | A watch already paired to Garmin Connect | Onboarding explains: remove the watch from Garmin Connect (and from Bluetooth settings) first, then pair with Self Watch |

## 6. Screens, accessibility and language

- **Sizes:** phones from 320 dp wide (small 360×640 dp is the design minimum); tablets and foldables must work (single column, centered, max width 640 dp) and handle resizing (API 36).
- **Orientation:** portrait-first; landscape must not break or lose state.
- **Theme:** dark (default), light and system; 5K palette; contrast at least 4.5:1 for text.
- **Text:** up to 200% font size without clipping; no fixed-height text views.
- **TalkBack:** every control labeled; charts expose values as text ("Heart rate 62 at 3:10 am"); logical focus order.
- **Touch:** 48 dp minimum targets.
- **Language:** English first; all strings in resources, ready for translation; layouts use start/end (right-to-left safe); per-app language on API 33+.
- **Units and formats:** follow the phone (US gets imperial); stored metric; dates, times and numbers formatted by locale; 12/24-hour from system.

## 7. Distribution

| Store | Requirements | Module |
|---|---|---|
| F-Droid | Reproducible build; only free libraries; no tracking; no Google Play Services; no dependency-info block in the APK | build |
| GitHub release | Signed APK, SHA-256 published | build |
| Google Play (optional, later) | Target 36+; data safety form ("no data collected or shared"); foreground-service declaration for `connectedDevice`; health-apps declaration | build, HappyRock |

## 8. Per-module compliance checklist

| Module | Must meet |
|---|---|
| core-model | Pure Kotlin (no Android imports); `java.time` only for dates |
| core-db | SQLite features of Android 8.0 (SQLite 3.18): FTS4 not FTS5, no window functions, no `STRICT`, no generated columns, no JSON functions, no `UPSERT … RETURNING`; safe across two processes |
| fit | Pure Kotlin; no crash on any input (fuzz-tested) |
| ble | §2 rows 31, 33, 34, 36; §3 Bluetooth rows; §4 Bluetooth quirks; all callbacks API-branched |
| garmin | Pure Kotlin; §5 in full; feature gating by capability flag; fake sign-in off by default |
| service | §2 rows 26, 28, 29, 31, 33, 34, 35, 36; §3 rows for its permissions; §4 maker notes; Android 8–11 path without presence |
| net | Works with no internet (GrapheneOS); never opens a socket except through the firewall |
| ui | §6 in full; insets handled by every container component |
| app | §2 rows 29, 30, 33, 35, 36; §3 "ask at the moment of use"; §6 in full |
| food | Camera permission only on first scan; works with no camera (tablets) and no internet |
| interop | Storage Access Framework only (no storage permission); Health Connect only on Android 14+ (built in), hidden below API 34 |
| link / link-client | Client kit works on API 21+ (other apps may support older phones); service checks the caller's signing certificate |
| build | §1, §7; no native libraries; 16 KB-page safe by construction |

## 9. Test matrix

| Where | What runs |
|---|---|
| Computer (CI) | Unit tests for every module; recorded Garmin session replays; FIT, import and recipe file tests; database migration tests |
| Emulators API 26, 28, 29, 31, 33, 34, 35, 36, 37 | App start, onboarding, permissions per version, screens at 200% font and in landscape, database, import and export (no Bluetooth) |
| Pixel 5, Android 17 | Full test with the vívoactive 5: pairing, sync, notifications, calls, music, find, watch apps, messages |
| Samsung and Xiaomi phones (HappyRock's or volunteers') | Background survival overnight, reconnect after Bluetooth toggle and after reboot |
| Volunteers' Garmin watches (tier 2) | Recorded sessions for the replay tests |
