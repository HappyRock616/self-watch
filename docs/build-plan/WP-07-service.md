# WP-07 service (the always-on part)

Lane B. Milestones M1 (connection, sync, ingest), M2 (summaries), M5 (relays, watch apps, link). Android library; its components run in process `:watch`. Depends on: core-model, core-db, ble, garmin, net, lifecycle-service, coroutines. **Must not depend on food, interop, ui or app.**

## Goal

Keep the watch connected (or sync on a schedule), store everything the watch sends, keep day summaries current, relay phone events to the watch, and serve other apps' watch-app messages. It does all this with as little battery and memory as possible, and survives every Android version's background rules (COMPATIBILITY §2).

## Manifest entries (in `service/src/main/AndroidManifest.xml`, merged into the app)

```xml
<service android:name=".SyncService" android:process=":watch" android:exported="false"
         android:foregroundServiceType="connectedDevice" />
<service android:name=".presence.WatchPresenceService" android:process=":watch" android:exported="true"
         android:permission="android.permission.BIND_COMPANION_DEVICE_SERVICE">           <!-- API 31+ -->
  <intent-filter><action android:name="android.companion.CompanionDeviceService" /></intent-filter></service>
<service android:name=".relay.NotificationRelay" android:process=":watch" android:exported="true"
         android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
  <intent-filter><action android:name="android.service.notification.NotificationListenerService" /></intent-filter></service>
<service android:name=".link.LinkService" android:process=":watch" android:exported="true" />  <!-- WP-13 -->
<receiver android:name=".BootReceiver" android:process=":watch" android:exported="true">
  <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" />
                 <action android:name="android.intent.action.MY_PACKAGE_REPLACED" /></intent-filter></receiver>
<receiver android:name=".link.GadgetbridgeIntentReceiver" android:process=":watch" android:exported="true">
  <intent-filter><action android:name="nodomain.freeyourgadget.gadgetbridge.command.SEND_WATCH_APP_MESSAGE" /></intent-filter></receiver>
```

Permissions are declared here too (COMPATIBILITY §3).

## Components

### `SyncService` (foreground service)

```kotlin
class SyncService : LifecycleService() {
    // started by: app (user pairs, opens app), BootReceiver, WatchPresenceService.onDeviceAppeared, schedule (sync-only)
    // stopped when: no paired devices; or sync-only mode finished its sync; or user turns the watch off in Devices
}
```

- **Foreground start:** `startForeground(ID, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` (the 3-argument form on API 29+). It's started only when `BLUETOOTH_CONNECT` is granted (31+) or an association exists (34 prerequisite).
- **Notification:** channel `connection` with low importance, so it's silent with no badge. Text is "Connected · synced 2 min ago", or "Syncing 3 of 7", or "Watch out of range". Its tap opens the app (`PendingIntent.getActivity`, immutable). A "Sync now" action is a broadcast `PendingIntent` to the service.
- **Owns:** one `DeviceManager`, one `SummaryBuilder`, the relays and the `LinkRouter`.

### `DeviceManager`

```kotlin
class DeviceManager(ctx: Context, db: SelfWatchDb, http: Http, scope: CoroutineScope) {
    val devices: StateFlow<List<DeviceStatus>>                  // for the screens (via db + broadcast)
    fun start(); suspend fun stop()
    suspend fun syncNow(deviceId: Long): Outcome<SyncReport>
    suspend fun send(deviceId: Long, cmd: WatchCommand): Outcome<Unit>
}
data class DeviceStatus(val info: DeviceInfo, val state: ConnectionState, val lastSyncSec: Long?)
```

- For each active device in `device`, create `BleConnection` (WP-05) plus the driver for its kind (`GarminDriver`), then run `driver.run(port, healthIngest, fileStore)` in a supervised child coroutine with the back-off from WP-05.
- **Connection mode:**
  - `always`: connect and stay connected; `auto = true` reconnects.
  - `sync_only`: connect at app open, on "Sync now", and every `connection.sync_only_hours` via `JobScheduler` (job id 1001, requires Bluetooth on, runs about every N hours, persisted). Sync, then disconnect and stop the service.
- **Events from the driver** (`driver.events`):

  | Event | Goes to |
  |---|---|
  | FindPhone | `FindPhoneAlert` (heads-up notification + alarm sound + vibration until stopped) |
  | MusicControl | `MusicRelay` |
  | CallAction | `CallRelay` |
  | NotificationAction | `NotificationRelay` (performs the notification's action or reply `RemoteInput`) |
  | AppMessageReceived | `LinkRouter` |
  | WebRequest | `WebRequestRouter` |
  | InstalledApps / AppSettings | `WatchAppsStore` (db) |
  | Battery | db `device` + notification text |

### `presence/WatchPresenceService` (API 31+)

- Extends `CompanionDeviceService`.
- `onDeviceAppeared(AssociationInfo)` (33+) or `onDeviceAppeared(address)` (31–32): start `SyncService` (allowed from the background for companion apps).
- `onDeviceDisappeared`: in `always` mode, keep the GATT `auto` reconnect armed but release the high-speed connection priority.
- Register with `startObservingDevicePresence(…)`: API 36+ through `ObservingDevicePresenceRequest`, API 31–35 by address. Unregister on unpair.
- **API 26–30:** no presence. The service stays foreground with an `auto` reconnect.

### `HealthIngest` (implements `HealthSink`)

- `ingest(batch)`, in one database transaction:
  1. Insert activity minutes, metrics, sleep stages and sessions, workout and body rows.
  2. Mark `sync_file` done.
  3. Compute the set of affected days (local time zone at each sample).
  4. Commit.
- After the commit: `DbChanges.notify(...)`, then `SummaryBuilder.refresh(days)`.
- `alreadyHave(key)` reads `sync_file`.
- `FileStore.saveWorkoutFit` writes `files/workouts/<deviceId>/<startSec>.fit` (app-private storage).

### `health/SummaryBuilder`

```kotlin
class SummaryBuilder(db: SelfWatchDb, clock: Clock) { suspend fun refresh(days: Set<Day>); suspend fun refreshToday() }
```

For each day it fills a `day_summary` row:

| Column | Computed as |
|---|---|
| `steps`, `distance_m`, `active_kcal` | Sums of activity minutes in the local day (device priority: the watch with `priority` max; others ignored for the same minute) |
| `active_min` | Minutes with intensity ≥ 2 (moderate+). Vigorous counts double, as in common "intensity minutes" practice. The rule is shown in the UI |
| `resting_kcal` | Resting metabolic rate from the watch (monitoring_info), else the Mifflin-St Jeor formula from the profile, prorated for today |
| `resting_hr` | `RESTING_HR` metric if present, else the lowest 30-minute average heart rate during the night |
| `hrv_avg_ms` | Last-night average HRV |
| `sleep_min` | Sleep minutes of the session ending that morning |
| `kcal_in`, `protein_g`, `fat_g`, `carb_g`, `water_ml` | Sums of the diary and water for that day (the app writes those rows and calls `refresh` through a broadcast) |
| `weight_kg_x100` | Latest weight that day |
| `tz_offset_min` | The phone's offset on that day |

Weeks, months and years are summed in SQL from day rows (WP-03 `SummaryDao`).

### `relay/`

| Class | How | Notes |
|---|---|---|
| `NotificationRelay` | `NotificationListenerService.onNotificationPosted/Removed`. Filters: skip ongoing, group summaries, our own package, apps in `notify.apps_denied`, and duplicates (same key + text within 2 s). Maps to `PhoneNotification`. App name via `PackageManager` (visible thanks to the launcher `<queries>`). Category from `Notification.category`. Reply actions from `RemoteInput` | Also used for music: `MediaSessionManager.getActiveSessions(ComponentName(this, NotificationRelay::class.java))` |
| `CallRelay` | `TelephonyManager` listener: `TelephonyCallback.CallStateListener` (31+) or `PhoneStateListener` (26–30). Number from the `EXTRA_INCOMING_NUMBER` broadcast (needs `READ_CALL_LOG` 29+, optional). Name via `ContactsContract.PhoneLookup` (optional). Accept: `TelecomManager.acceptRingingCall()`. Reject: `TelecomManager.endCall()` (28+; on 26–27 not offered) | Both need `ANSWER_PHONE_CALLS` |
| `MusicRelay` | The active `MediaController`: metadata, playback state and volume → `WatchCommand.Music`. Watch actions → `transportControls` or `AudioManager.adjustStreamVolume` | Updates throttled to 1 per second |
| `WeatherRelay` | Listens for weather broadcasts from weather apps that offer them (Breezy Weather's Gadgetbridge-format broadcast `nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER`, JSON extra), mapped to `WatchCommand.Weather`. No weather network calls of our own in v1 | Off by default |
| `FindPhoneAlert` | Heads-up notification on channel `find_phone` (high importance) with a stop action; plays the default alarm with `AudioAttributes.USAGE_ALARM`; vibrates | No full-screen intent (API 34 limits) |
| `TimeZoneWatcher` | `ACTION_TIMEZONE_CHANGED`, `ACTION_TIME_CHANGED` → `WatchCommand.SyncTime` | |

### `watchapps/`

- **`WatchAppsStore`:** keeps `watch_app` in step with `WatchEvent.InstalledApps`.
- **`SettingsDefinitionParser`:** reads the Connect IQ `<app>-settings.json` that sits beside a `.prg`. The fields are key, valueType, defaultValue, configTitle (string id), configType, configOptions, min, max. It resolves titles from the app's language resources when they're available, and produces a `SettingsForm` for the UI.
- **Sideloaded apps:** the user picks the `.prg` and, optionally, the settings json. Both are kept in `files/watchapps/<uuid>/` so the form works later.
- **Settings backup:** on every successful write, a row in `watch_app_settings`.

### `web/WebRequestRouter`

`WatchEvent.WebRequest` is routed by URL:
- **To this phone:** host `127.0.0.1` or `localhost` goes to the local app at that port. It's sent through `net` with feature `LOCAL_PHONE_APP` (logged, never counted as internet).
- **Anything else:** feature `WATCH_APP_WEB` (firewall off by default; a per-app allow list may come later).

Response limits follow the watch's limits; oversize bodies return an error status.

### `link/` (WP-13 has the details)

`LinkService` (bound AIDL service for `link-client`), `LinkPermissions`, `GadgetbridgeIntentReceiver`, `LinkRouter` (maps a watch app UUID to the approved phone app package and its callback), and `CaloriesLeft`.

`CaloriesLeft` reads today's `day_summary` and the goal, and sends `{"kcalLeft": n, "kcalIn": a, "kcalOut": b}` to the user-chosen watch app on changes, at most every 5 minutes.

## Variables (service-level)

`devices: Map<Long, ManagedDevice>` (connection, driver, job, back-off index), `mode: ConnectionMode`, `notificationCache`, `activeMediaController`, `pendingWebRequests: Map<Int, Job>`, `syncJobId = 1001`, notification ids (`CONNECTION = 1`, `FIND_PHONE = 2`, `FIX_IT = 3`), channels (`connection`, `find_phone`, `alerts`).

## Compatibility checklist

COMPATIBILITY §2 rows 26, 28, 29, 31, 33, 34, 35, 36; §3 rows for Bluetooth, notifications, calls, boot; §4 maker notes.
- [ ] No food, interop or ui imports (enforced by Gradle dependencies).
- [ ] Each Android path is tested on an emulator at API 26, 31, 34 and 37 (permissions, presence, foreground type).

## Tests and definition of done

- [ ] `HealthIngest` + `SummaryBuilder` unit tests with Robolectric DB: totals per day across a DST change; device priority; resting HR rule.
- [ ] `DeviceManager` with `FakeWatchDriver` + `FakeBlePort`: back-off, sync-only cycle, event routing.
- [ ] Relays: unit tests for the filters and mappings; a manual test checklist on the Pixel 5 (T-Q07).
- [ ] Process isolation check: a heap dump of `:watch` after a day shows no classes from `app.selfwatch.food`, `interop` or `ui` (T-B10).
- [ ] Overnight soak test on the Pixel 5: still connected in the morning; battery use of Self Watch < 2% overnight, as reported by Android's battery screen (T-Q06).
