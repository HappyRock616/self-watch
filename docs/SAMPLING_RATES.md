# Sampling and sync rates

Every rate, interval, timer and Bluetooth connection setting that Self Watch sets or relies on for the
watch and sensors, checked against Gadgetbridge's Garmin code (`references/Gadgetbridge`, paths below are
relative to `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/`, "GB") and Garmin's published
vívoactive 5 manual. Audit of 2026-11-25 (restart plan, workstream C).

Rule: watch battery comes first. A rate is changed only when it is wasteful or too coarse. Nothing here
polls the watch.

## Watch logging (on the watch itself)

Self Watch sends no logging settings to the watch, and neither does Gadgetbridge: both send only the three
GFDI device settings below. Heart rate, stress, Pulse Ox, respiration, HRV, Body Battery and skin
temperature all run at the watch's own settings. The user changes them on the watch.

| Rate | Value | Reason | Source |
|---|---|---|---|
| Wrist heart rate | Watch default: "Auto", continuous | Not sent; the watch decides | vívoactive 5 manual (wrist heart rate settings) |
| Pulse Ox (SpO2) | Watch setting: All day, During sleep or On demand | Not sent. All day costs the watch noticeably more battery, so Self Watch leaves the choice to the user | Manual lists the three modes but no default; "On demand" as the default is an inference from Garmin's comparison page |
| Stress, Body Battery, respiration | All day (watch default) | Not sent | Inference from Garmin's general docs; not stated for this model |
| HRV status | Overnight only (watch default) | Not sent | Inference from Garmin's general docs |
| Skin temperature | Watch default | Not sent; Self Watch asks for the file type (73/3) when the watch offers it | `garmin/.../files/Directory.kt:98` |
| GFDI device settings | AUTO_UPLOAD on, WEATHER_CONDITIONS on, WEATHER_ALERTS off | Same three as Gadgetbridge | `garmin/.../GarminDriver.kt:418-424`; GB `service/devices/garmin/GarminSupport.java:1378-1384` |

Note: sleep breathing variations and overnight SpO2 only exist when "During sleep" or "All day" Pulse Ox is
turned on on the watch. Self Watch does not turn it on by itself (battery).

## Sync triggers

| Rate | Value | Reason | Source |
|---|---|---|---|
| Sync when the watch asks (SYNCHRONIZATION, FileAvailable) | On request, one sync at a time | The watch knows when it has new files | `garmin/.../GarminDriver.kt:345-358, 471-475`; GB `service/devices/garmin/FileTransferHandler.java:131-137` |
| Sync on connect | Only if the last sync is 30 min old or more (or first connect) | A full sync on every reconnect costs the watch battery on a flaky link | `app/.../watch/WatchWiring.kt:223-243`; GB does not sync on connect (sends SYNC_READY and waits) |
| Sync-only mode schedule | Every 4 h by default (1 to 24), 30 min flex, JobScheduler | Only in "Sync only" mode | `service/.../device/DeviceManager.kt` (`DEFAULT_SYNC_HOURS`), `service/.../schedule/SyncJob.kt:28-52`; GB has no schedule |
| Sync-only connect wait | 120 s | Gives up on a watch that does not answer | `DeviceManager.CONNECT_TIMEOUT_MS` |
| Time sent to the watch | On connect, on watch request, on time-zone change | No periodic resend | `GarminDriver.kt:427`; GB sets time on connect |
| Weather | When new weather arrives, and on connect if under 6 h old | No timed re-push | `app/.../WatchWiring.kt:202` (`weatherRepushMs` null), `service/.../relay/WeatherRelay.kt:193` |

## Bluetooth link

| Rate | Value | Reason | Source |
|---|---|---|---|
| Connection priority | BALANCED once per connection at READY, again when the watch leaves range; never HIGH | Short intervals cost the watch battery | `GarminDriver.kt:434-435`, `service/.../SyncService.kt` (device disappeared); GB requests BALANCED (LOW_POWER if the user opts in) `service/btle/AbstractBTLEMultiDeviceSupport.java:464-469` |
| MTU | 517 requested; 23 if refused | Fewer packets per message | `ble/.../BleConstants.kt:19`; GB asks 515 |
| First connect | Direct, 30 s timeout | Quick when the watch is near | `BleConstants.CONNECT_TIMEOUT_MS` |
| Later connects | Android autoConnect (low power, no timeout) after a direct try failed | Waits for the watch without retries | `WatchWiring.kt` (`BlePortFactory`); GB uses direct connects with a 5 s timeout |
| Reconnect back-off (DeviceManager) | 5, 10, 30, 60, 120 s, then every 5 min; resets after a link stays up 5 min | Each try wakes the watch radio | `service/.../device/DeviceTypes.kt` (`Backoff`); GB starts at 2 s and doubles to 64 s (`devices/AbstractDeviceCoordinator.java:1543-1547`) |
| Link-level reconnect (BleConnection) | **Changed:** was 1, 2, 5, 10, 30, 60, 300 s; now the same steps as DeviceManager (5 s first) for the watch and sensor links | See "Changes" | `app/.../watch/WatchWiring.kt` (`LINK_RECONNECT_SEC`), `ble/.../BleConstants.kt:46` (default kept for other users) |
| Contested watch (another phone or app keeps taking it) | 3 links that drop within 2 min of READY inside 15 min: check once an hour; "Sync now" takes it back | Fighting over the watch costs its battery | `service/.../device/ContestDetector.kt`; GB does not detect this |
| Exclusive watch (new, off by default) | When on, no hourly pause: the normal back-off above (ends at one try per 5 min) | The user chose to keep the watch on this phone | `DeviceManager.supervise`, `SettingKeys.deviceExclusive` |
| GATT operation / discovery / bond timeouts | 10 s / 15 s with one retry (600 ms before) / 60 s | Timeouts, not repeats | `BleConstants.kt:31-43` |
| Garmin handshake / GFDI request / file chunk | 30 s / 10 s / 15 s | Timeouts | `garmin/.../session/Session.kt:44-46` |
| MLR (reliable link) | Window 32, ack after 5 packets or 20 ms, retransmit 1 s doubling to 20 s, gives up after 12 timeouts | Same values as GB except the give-up | `garmin/.../link/Mlr.kt:280-293`; GB `service/devices/garmin/communicator/v2/MlrCommunicator.java:28-33` |
| Keepalive / heartbeat | None | Not needed; neither app sends one | Not found in either |
| Presence (CompanionDeviceManager) | Event driven, no timers | The system reports the watch appearing and leaving | `service/.../presence/WatchPresenceService.kt` |

## Live readings and sensors

| Rate | Value | Reason | Source |
|---|---|---|---|
| Watch live heart rate and steps | On only while a card shows them; lease 90 s, screen renews every 30 s (renewals send nothing to a watch already on) | Live data costs watch battery | `service/.../live/LiveRelay.kt`; GB keeps realtime on until the user stops it |
| Live values to the screens | On change, unchanged values at most every 5 s | Phone side only | `LiveRelay.REPEAT_SEC` |
| Strap heart rate (sensor priority, new) | Every value the strap sends (about 1/s, its own rate); replaces the watch's live value for 10 s after each one; shown only while a screen asks | A connected strap always wins; no extra radio traffic because straps stream anyway | `ble/.../profile/HealthSensorDriver.kt`, `LiveRelay.SENSOR_FRESH_SEC` |
| Strap samples to the database | Buffered, written every 10 s | One transaction per 10 s instead of per beat | `HealthSensorDriver.HR_FLUSH_MS` |
| Stored heart rate, sensor priority (new) | A watch sample within 30 s of a strap sample is replaced by the strap's; the source is the row's `device_id` | A connected strap always overrides the watch | `core-db/.../dao/MetricDao.kt` (`SENSOR_HR_WINDOW_SEC`, `sensorPriorityIn`) |

## Phone-side timers (no watch traffic)

| Rate | Value | Reason | Source |
|---|---|---|---|
| Day summary refresh | Every 15 min and 5 s after midnight | Keeps today's numbers current | `service/.../health/SummaryBuilder.kt:113-120, 209-212` |
| Screen ticks | 15 s (live cards), 30 s (food) | Keeps "minutes ago" text true | `app/.../watch/LiveVitals.kt`, `FoodFragment.kt` |
| Music updates | At most 1/s, only on change | | `service/.../relay/MusicRelay.kt:99` |
| Calories-left message | At most every 5 min, only on change | | `service/.../link/CaloriesLeft.kt:140` |
| Find my phone | Stops after 120 s | | `service/.../relay/FindPhoneAlert.kt:208` |
| Web requests for watch apps | 30 s (+2 s slack) | Timeout | `service/.../web/WebRequestRouter.kt` |
| GPS help (GPS_ASSIST, on by default) | Only when the watch asks; never polled, no timer. A file under 6 h old is answered from the phone with no network; an older one is revalidated with `If-None-Match` / `If-Modified-Since` and a 304 answers from the phone. Any network allowed (no Wi-Fi-only setting exists; the file is under 64 KB) | Data and battery: the watch asks only when its own copy is old, and repeats cost nothing | `service/.../web/WebRequestRouter.kt` (`EPHEMERIS_FRESH_SEC`), `service/.../web/EphemerisCache.kt` (cache in the app's cache folder); GB serves a user-set AGPS file the same way (`http/interceptors/AgpsInterceptor.java`) |
| Garmin sign-in answer (per watch, off by default) | Only when the watch asks (about every 90 days, when its Garmin sign-in runs out); one made-up reply over Bluetooth, no network, no timer. When off, the request is left unanswered and Watch settings shows a hint | Weather and GPS help on the watch need it; nothing to poll | `garmin/.../pb/CorePbHandler.kt`, `service/.../device/DeviceManager.kt` (`noteSignInRequest`); GB "Send fake OAuth responses" (`devices/garmin/GarminPrefs.java`, `service/devices/garmin/ProtocolBufferHandler.java`) |

## Changes in this audit

1. **Link-level reconnect delays now start at 5 s, not 1 s.** `BleConnection` has its own reconnect loop
   (1, 2, 5, 10, 30, 60, 300 s). DeviceManager closes each link when its driver returns, so this loop
   should never fire, but between a drop and the close it could retry after 1 or 2 s, and each try wakes
   the watch's radio (the 1 s reconnect loop is what drained the vívoactive 5 on 2026-11-24). The watch and
   sensor links now use DeviceManager's steps (5, 10, 30, 60, 120, 300 s), so no layer retries sooner
   than the manager would. Gadgetbridge starts at 2 s.
2. **Exclusive watch switch (off by default).** With it off nothing changes. With it on, the hourly pause
   for a contested watch is skipped and the normal back-off applies (still one try per 5 minutes at most).
3. **Sensor priority.** No new rate on the watch: the strap's own notifications are reused.

Nothing else was wasteful or too coarse: HIGH priority is never requested, nothing polls the watch, weather
has no timer, live readings expire on their own, sync on connect is gated to 30 min, and back-off ends at
5 min. The hourly check for a contested watch can leave it unsynced for up to an hour; that is deliberate
(battery), "Sync now" overrides it, and the new Exclusive switch removes it for users who want that.

## What Exclusive cannot do

A Garmin watch talks to one phone at a time, and nothing in the protocol lets a phone stop another phone or
app from taking the watch (there is no "lock" message; Gadgetbridge has none either). So Exclusive means
"never step back": Self Watch keeps reconnecting on its normal back-off. To make the watch truly exclusive,
forget the watch in the other phone's Bluetooth settings and close Garmin Connect or Gadgetbridge on this
phone.
