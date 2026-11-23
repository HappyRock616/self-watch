# WP-02 core-model

Lane B. Milestone M0, **frozen at the end of M0** (changes after that need plan-lead approval). Kotlin-only (JVM) module. Depends on: `kotlinx-coroutines-core` only.

## Goal

The shared vocabulary of the whole app: data classes, units, time, results, logging, and the interfaces between modules. It can't do anything on its own; it lets the other lanes build in parallel against the same contracts.

## Conventions

- All classes immutable (`data class`, `val`). Collections are `List`/`Set`/`Map` (read-only).
- **Units are in names:** `X10` = value ×10 as Int, `X100` = ×100, `Mg`, `Ml`, `Cm`, `M` (meters), `Sec`, `Ms`, `Bpm`, `E7` (degrees ×10⁷).
- **Time:** `tSec: Long` = UTC epoch seconds. `Day` = local calendar day.
- `null` means unknown; 0 means zero.

## Packages and types

### `app.selfwatch.model.time`

```kotlin
@JvmInline value class Day(val yyyymmdd: Int) : Comparable<Day> {
    fun toLocalDate(): java.time.LocalDate
    fun plusDays(n: Int): Day
    companion object { fun of(date: java.time.LocalDate): Day; fun of(tSec: Long, zone: java.time.ZoneId): Day }
}
data class TimeRange(val startSec: Long, val endSec: Long)          // end exclusive
data class DayRange(val first: Day, val last: Day)                   // inclusive
interface Clock { fun nowSec(): Long; fun zone(): java.time.ZoneId }  // FakeClock in testFixtures
```

### `app.selfwatch.model.result`

```kotlin
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Fail(val error: SwError) : Outcome<Nothing>
}
sealed interface SwError {
    data class NotConnected(val deviceId: Long) : SwError
    data class Unsupported(val feature: Feature) : SwError
    data class Timeout(val what: String) : SwError
    data class Protocol(val detail: String) : SwError
    data class Blocked(val feature: NetFeature) : SwError          // firewall
    data object Offline : SwError
    data class Io(val detail: String) : SwError
    data class BadInput(val detail: String) : SwError
    data class PermissionMissing(val permission: String) : SwError
}
```

### `app.selfwatch.model.log`

```kotlin
object SwLog {                       // backend set at app start (Android: rotating file + logcat in debug)
    fun d(tag: String, msg: () -> String); fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null); fun e(tag: String, msg: String, t: Throwable? = null)
    var sink: LogSink
}
interface LogSink { fun write(level: Int, tag: String, msg: String, t: Throwable?) }
```

### `app.selfwatch.model.device`

```kotlin
enum class DeviceKind { GARMIN_WATCH /* later: SCALE, HR_STRAP */ }
enum class Feature {                  // what the UI can offer; drivers report which they support
    NOTIFICATIONS, NOTIFICATION_REPLY, CALLS, MUSIC, WEATHER, FIND_PHONE, FIND_WATCH, ALARMS, TIME_SYNC,
    WATCH_APP_INSTALL, WATCH_APP_MANAGE, WATCH_APP_SETTINGS, WATCH_APP_MESSAGES, WATCH_APP_WEB, WATCH_APP_LAUNCH,
    GPS_ASSIST, HEALTH_ACTIVITY, HEALTH_SLEEP, HEALTH_HR, HEALTH_HRV, HEALTH_SPO2, HEALTH_STRESS,
    HEALTH_RESPIRATION, HEALTH_SKIN_TEMP, WORKOUTS, REALTIME_HR, BATTERY
}
enum class SupportTier { VERIFIED, EXPECTED, UNVERIFIED }
data class DeviceInfo(
    val id: Long,                       // selfwatch.db device.id (0 before first save)
    val kind: DeviceKind,
    val address: String,                // Bluetooth MAC
    val name: String,                   // advertised name, e.g. "vívoactive 5"
    val model: String,                  // display model name from models.json
    val productId: Int?,                // Garmin product number
    val firmware: String?,              // "12.34"
    val serial: Long?,                  // unit id
    val tier: SupportTier,
    val features: Set<Feature>,
    val batteryPct: Int?
)
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Handshaking : ConnectionState
    data object Ready : ConnectionState
    data class Syncing(val done: Int, val total: Int, val label: String) : ConnectionState
    data class Failed(val error: SwError, val retryInSec: Int?) : ConnectionState
}
enum class SyncReason { CONNECT, USER, SCHEDULE, WATCH_REQUEST }
```

### `app.selfwatch.model.ble` (the Bluetooth contract; implemented by `ble`, used by `garmin`)

```kotlin
interface BlePort {                                   // one connected device
    val address: String
    val state: kotlinx.coroutines.flow.StateFlow<PortState>
    suspend fun serviceUuids(): Set<java.util.UUID>
    suspend fun openPipe(service: java.util.UUID, rx: java.util.UUID, tx: java.util.UUID): Outcome<BytePipe>
    suspend fun setHighSpeed(on: Boolean)             // connection priority during sync
}
enum class PortState { DISCONNECTED, CONNECTING, CONNECTED, CLOSED }
interface BytePipe {
    val maxWrite: Int                                  // bytes per write after MTU negotiation (MTU − 3)
    val incoming: kotlinx.coroutines.flow.Flow<ByteArray>   // notifications, in order, never dropped
    suspend fun write(bytes: ByteArray): Outcome<Unit>  // one GATT write; queued, in order
    suspend fun close()
}
```

### `app.selfwatch.model.health`

```kotlin
data class ActivityMinute(val deviceId: Long, val tSec: Long, val steps: Int, val activeKcalX10: Int,
                          val distanceCm: Int, val intensity: Int /*0 sedentary,1 light,2 moderate,3 vigorous*/,
                          val activityType: Int? /* FIT activity_type */)
enum class MetricKind(val code: Int) {      // value scaling noted per kind
    HEART_RATE(1),        // bpm
    HRV_RMSSD(2),         // ms
    SPO2(3),              // percent
    RESPIRATION(4),       // breaths/min ×10
    STRESS(5),            // 0..100
    SKIN_TEMP_DELTA(6),   // °C ×100 relative to baseline
    RESTING_HR(7)         // bpm, one per day
}
data class MetricSample(val deviceId: Long, val kind: MetricKind, val tSec: Long, val value: Int)
enum class SleepStage(val code: Int) { AWAKE(0), LIGHT(1), DEEP(2), REM(3), UNMEASURABLE(4) }
data class SleepStageSample(val deviceId: Long, val tSec: Long, val stage: SleepStage)
data class SleepSession(val deviceId: Long, val startSec: Long, val endSec: Long, val score: Int?)
data class Workout(val id: Long, val deviceId: Long?, val sport: Int /*FIT sport*/, val subSport: Int?,
                   val startSec: Long, val endSec: Long, val durationSec: Int, val distanceM: Int?,
                   val kcal: Int?, val avgHr: Int?, val maxHr: Int?, val ascentM: Int?, val descentM: Int?,
                   val hasTrack: Boolean, val fitPath: String?, val trackPath: String?, val routePreview: ByteArray?,
                   val source: DataSource)
data class TrackPoint(val tSec: Long, val latE7: Int?, val lonE7: Int?, val altCm: Int?, val hrBpm: Int?,
                      val cadence: Int?, val tempC10: Int?, val speedMms: Int?)
enum class BodyKind(val code: Int, val unit: String) {
    WEIGHT(1,"kg"), BODY_FAT(2,"%"), BMI(3,""), WAIST(4,"cm"), HIPS(5,"cm"), CHEST(6,"cm"), ARM(7,"cm"),
    THIGH(8,"cm"), NECK(9,"cm"), MUSCLE_MASS(10,"kg"), BONE_MASS(11,"kg"), BODY_WATER(12,"%"), CUSTOM(99,"")
}
data class BodyMeasure(val id: Long, val tSec: Long, val kind: BodyKind, val customKindId: Long?,
                       val valueX100: Int, val source: DataSource, val deviceId: Long?, val importId: Long?)
enum class DataSource(val code: Int) { WATCH(1), SCALE(2), MANUAL(3), IMPORT(4), HEALTH_CONNECT(5) }
data class DaySummary(val day: Day, val tzOffsetMin: Int, val steps: Int?, val activeKcal: Int?, val restingKcal: Int?,
                      val distanceM: Int?, val activeMin: Int?, val restingHr: Int?, val hrvAvgMs: Int?,
                      val sleepMin: Int?, val kcalIn: Int?, val proteinG: Int?, val fatG: Int?, val carbG: Int?,
                      val weightKgX100: Int?, val waterMl: Int?)
```

### `app.selfwatch.model.food`

```kotlin
enum class FoodOrigin(val code: Int) { MY_FOOD(1), FOODS_DB(2), OPEN_FOOD_FACTS(3), RECIPE(4) }
data class FoodRef(val origin: FoodOrigin, val id: Long)
data class Nutrients(val kcalX10: Int?, val proteinX10: Int?, val fatX10: Int?, val satfatX10: Int?,
                     val carbX10: Int?, val sugarX10: Int?, val fibreX10: Int?, val sodiumMg: Int?,
                     val cholesterolMg: Int?) { fun scaled(factorX1000: Int): Nutrients; operator fun plus(o: Nutrients): Nutrients }
enum class Basis(val code: Int) { PER_100G(0), PER_100ML(1), PER_ITEM(2) }
data class Serving(val seq: Int, val label: String, val gramsX10: Int?)
data class Food(val ref: FoodRef, val name: String, val brand: String?, val kind: Int, val basis: Basis,
                val per: Nutrients, val servings: List<Serving>, val dataYear: Int?, val sourceLabel: String,
                val barcode: Long?)
data class MealSlot(val id: Long, val name: String, val order: Int, val hidden: Boolean)
data class DiaryEntry(val id: Long, val day: Day, val mealSlotId: Long, val tSec: Long, val food: FoodRef?,
                      val name: String, val brand: String?, val amountX100: Int, val servingLabel: String,
                      val gramsX10: Int?, val nutrients: Nutrients /* copied, totals for this entry */,
                      val importId: Long?)
enum class RecipeKind(val code: Int) { RECIPE(1), SAVED_MEAL(2) }
enum class MatchState(val code: Int) { UNMATCHED(0), SUGGESTED(1), CONFIRMED(2), SKIPPED(3) }
data class RecipeItem(val seq: Int, val line: String, val food: FoodRef?, val amountX100: Int?, val unit: String?,
                      val gramsX10: Int?, val match: MatchState)
data class Recipe(val id: Long, val kind: RecipeKind, val name: String, val servingsX10: Int, val servingLabel: String?,
                  val prepMin: Int?, val cookMin: Int?, val steps: String?, val notes: String?, val sourceName: String?,
                  val sourceUrl: String?, val statedPerServing: Nutrients?, val tags: Set<String>,
                  val items: List<RecipeItem>, val importId: Long?)
enum class GoalKind(val code: Int) { KCAL_IN(1), PROTEIN_G(2), FAT_G(3), CARB_G(4), STEPS(5), WEIGHT_KG_X100(6),
                                     WATER_ML(7), FAST_MIN(8), ACTIVE_MIN(9), SLEEP_MIN(10) }
data class Goal(val kind: GoalKind, val value: Int, val fromDay: Day)
enum class Sex { FEMALE, MALE, UNSPECIFIED }
enum class UnitSystem { METRIC, IMPERIAL, FOLLOW_PHONE }
data class Profile(val heightCm: Int?, val birthYear: Int?, val sex: Sex, val units: UnitSystem)
```

### `app.selfwatch.model.watch` (driver contract; implemented by `garmin`, used by `service`)

```kotlin
interface WatchDriver {
    val info: kotlinx.coroutines.flow.StateFlow<DeviceInfo?>
    val state: kotlinx.coroutines.flow.StateFlow<ConnectionState>
    val events: kotlinx.coroutines.flow.Flow<WatchEvent>
    suspend fun run(port: BlePort, sink: HealthSink, files: FileStore)   // returns when disconnected or canceled
    suspend fun sync(reason: SyncReason): Outcome<SyncReport>
    suspend fun send(cmd: WatchCommand): Outcome<Unit>
}
interface HealthSink {                 // implemented by service.HealthIngest; one transaction per call
    suspend fun ingest(batch: IngestBatch): Outcome<Unit>
    suspend fun alreadyHave(key: SyncFileKey): Boolean
}
data class SyncFileKey(val deviceId: Long, val fileIndex: Int, val fileType: Int, val fileSubtype: Int, val tSec: Long, val size: Int)
data class IngestBatch(val key: SyncFileKey?, val activity: List<ActivityMinute> = emptyList(),
                       val metrics: List<MetricSample> = emptyList(), val sleepStages: List<SleepStageSample> = emptyList(),
                       val sleepSessions: List<SleepSession> = emptyList(), val workout: Workout? = null,
                       val body: List<BodyMeasure> = emptyList())
interface FileStore { suspend fun saveWorkoutFit(deviceId: Long, startSec: Long, bytes: ByteArray): String /* path */ }
data class SyncReport(val filesNew: Int, val filesFailed: Int, val durationMs: Long)

sealed interface WatchCommand {
    data class Notify(val n: PhoneNotification) : WatchCommand
    data class RemoveNotification(val id: Int) : WatchCommand
    data class IncomingCall(val number: String?, val name: String?) : WatchCommand
    data object CallEnded : WatchCommand
    data class Music(val state: MusicState) : WatchCommand
    data class Weather(val now: WeatherNow, val days: List<WeatherDay>) : WatchCommand
    data class FindWatch(val ring: Boolean) : WatchCommand
    data class SetAlarms(val alarms: List<Alarm>) : WatchCommand
    data class AppMessage(val appUuid: java.util.UUID, val payload: CiqValue) : WatchCommand
    data class InstallApp(val prgBytes: ByteArray, val settingsJson: String?) : WatchCommand
    data class DeleteApp(val appUuid: java.util.UUID) : WatchCommand
    data class LaunchApp(val appUuid: java.util.UUID) : WatchCommand
    data class WriteAppSettings(val appUuid: java.util.UUID, val values: Map<String, CiqValue>) : WatchCommand
    data class WebResponse(val requestId: Int, val status: Int, val body: ByteArray, val contentType: String?) : WatchCommand
    data object SyncTime : WatchCommand
}
sealed interface WatchEvent {
    data class FindPhone(val start: Boolean) : WatchEvent
    data class MusicControl(val action: MusicAction) : WatchEvent
    data class CallAction(val accept: Boolean) : WatchEvent
    data class NotificationAction(val id: Int, val action: String, val replyText: String?) : WatchEvent
    data class AppMessageReceived(val appUuid: java.util.UUID, val payload: CiqValue) : WatchEvent
    data class WebRequest(val requestId: Int, val appUuid: java.util.UUID?, val url: String, val method: String,
                          val headers: Map<String, String>, val body: ByteArray?) : WatchEvent
    data class Battery(val pct: Int) : WatchEvent
    data class InstalledApps(val apps: List<WatchApp>) : WatchEvent
    data class AppSettings(val appUuid: java.util.UUID, val values: Map<String, CiqValue>) : WatchEvent
}
/** Connect IQ (Monkey C) value as it travels in app messages and settings. */
sealed interface CiqValue {
    data object Null : CiqValue; data class Bool(val v: Boolean) : CiqValue; data class Num(val v: Long) : CiqValue
    data class Dbl(val v: Double) : CiqValue; data class Str(val v: String) : CiqValue
    data class Arr(val v: List<CiqValue>) : CiqValue; data class Dict(val v: Map<CiqValue, CiqValue>) : CiqValue
}
data class PhoneNotification(val id: Int, val appPackage: String, val appName: String, val title: String?, val text: String?,
                             val tSec: Long, val category: NotificationCategory, val canReply: Boolean,
                             val actions: List<String>)
enum class NotificationCategory { CALL, MESSAGE, EMAIL, CALENDAR, SOCIAL, OTHER }
enum class MusicAction { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }
data class MusicState(val playing: Boolean, val artist: String?, val album: String?, val title: String?,
                      val durationMs: Long?, val positionMs: Long?, val volumePct: Int?)
data class WeatherNow(val tSec: Long, val tempC10: Int, val feelsLikeC10: Int?, val condition: WeatherCondition,
                      val humidityPct: Int?, val windKmhX10: Int?, val windDeg: Int?, val location: String?)
data class WeatherDay(val day: Day, val minC10: Int, val maxC10: Int, val condition: WeatherCondition, val precipPct: Int?)
enum class WeatherCondition { CLEAR, PARTLY_CLOUDY, CLOUDY, RAIN, SHOWERS, THUNDER, SNOW, SLEET, FOG, WINDY, HAZE, UNKNOWN }
data class Alarm(val id: Int, val hour: Int, val minute: Int, val days: Set<java.time.DayOfWeek>, val enabled: Boolean, val label: String?)
data class WatchApp(val uuid: java.util.UUID, val name: String, val version: Int?, val kind: WatchAppKind)
enum class WatchAppKind { APP, WIDGET, WATCH_FACE, DATA_FIELD, OTHER }
```

### `app.selfwatch.model.net` (network contract; implemented by `net`)

```kotlin
enum class NetFeature(val code: Int, val defaultOn: Boolean) {
    FOOD_DATA(1, true), OPEN_FOOD_FACTS(2, true), WATCH_APP_WEB(3, false), GPS_ASSIST(4, false),
    RECIPE_BOOK(5, true), LOCAL_PHONE_APP(6, true) /* 127.0.0.1, never leaves the phone */
}
data class HttpRequest(val feature: NetFeature, val method: String, val url: String,
                       val headers: Map<String, String> = emptyMap(), val body: ByteArray? = null,
                       val timeoutMs: Int = 15_000, val maxBytes: Long = 50L shl 20)
data class HttpResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray)
interface Http { suspend fun send(req: HttpRequest): Outcome<HttpResponse>
                 suspend fun download(req: HttpRequest, to: java.io.File, onProgress: (Long, Long?) -> Unit): Outcome<Long> }
```

### `app.selfwatch.model.json`

A minimal JSON reader and writer (`JsonValue` sealed type, `Json.parse(String)`, `Json.write(JsonValue)`) for the Kotlin-only modules: about 300 lines, fully unit-tested, no dependencies.

### Test fixtures (`src/testFixtures`)

`FakeClock`, `FakeBlePort` (scripted pipes), `FakeWatchDriver`, `RecordingHealthSink`, `FakeHttp`. Other modules use them through `testFixtures(project(":core-model"))`.

## v1.1 additions (approved by HappyRock; additive only, tag `core-model-v1.1` after merge)

No renames or removals; every addition has a default or is a new case, so v1 code compiles unchanged
(exhaustive `when`s over `SwError` and `WatchEvent` need the new branches).

| Addition | Why | Used by |
|---|---|---|
| `MetricKind.RESTING_KCAL(8)`: kcal per whole day, one sample per day stamped at the local day's start | The watch's resting metabolic rate (FIT `monitoring_info` field 5) | `fit` `MonitoringDecoder` emits it; `service` `SummaryBuilder` prefers it over Mifflin-St Jeor |
| `SwError.WaitingForWifi` (data object) | Connected, but a Wi-Fi-only download is on a metered network (`Offline` stays for "no network") | `net` (FOOD_DATA, RECIPE_BOOK) |
| `SwError.RateLimited(retryAfterSec: Int?)` | Quota or rate-limit refusals | `interop` Health Connect sync |
| `WatchEvent.SecurePairingRequired` (data object) | The watch asked for Garmin secure pairing (R2); sent once per connection | `garmin` `GarminDriver` (its `securePairingRequested` flag stays) |
| `ble.CloseReason` enum (`CLOSED_BY_APP`, `REPAIR_NEEDED`, `PERMISSION_MISSING`) and `ble.HasCloseReason { val closeReason: StateFlow<CloseReason?> }` | Lets `garmin` see why a port closed without changing `BlePort`: `(port as? HasCloseReason)?.closeReason` | `ble` `BleConnection` implements it (its old `CloseReason` is now a typealias); `GarminDriver` maps `REPAIR_NEEDED` to `ConnectionState.Failed(SwError.Protocol("re-pair needed"), null)` |

## v1.2 additions (additive only; several devices and standard Bluetooth health devices)

New enum cases only: no renames, removals or changed signatures, so v1.1 code compiles unchanged
(exhaustive `when`s over `DeviceKind` need the new branches). Needs plan-lead approval and the tag
`core-model-v1.2` after merge.

| Addition | Why | Used by |
|---|---|---|
| `DeviceKind.WEIGHT_SCALE`, `HEART_RATE_SENSOR`, `BLOOD_PRESSURE_MONITOR` | Devices that follow the open Bluetooth SIG health profiles, paired from Devices › Other devices | `core-db` `DeviceKindCodes` (2, 3, 4); `ble.profile.HealthSensorDriver`; the app's driver factory and Devices screens |
| `Feature.HEALTH_WEIGHT`, `HEALTH_BODY_COMPOSITION`, `HEALTH_BLOOD_PRESSURE` | What a sensor offers (with the existing `HEALTH_HR` and `BATTERY`) | `HealthSensorDriver` reports them after subscribing |
| `MetricKind.BP_SYSTOLIC(9)`, `BP_DIASTOLIC(10)`, mmHg | Blood pressure readings (the cuff's pulse is stored as `HEART_RATE`) | `HealthSensorDriver`; `metric` table codes in V1.sql |

## Tests and definition of done

- [ ] Every type compiles with no Android dependency (the module is `kotlin.jvm`).
- [ ] `Day` conversions are correct across DST and time zones (tests for America/Detroit spring-forward and fall-back days).
- [ ] `Nutrients.scaled` / `plus` rounding is half away from zero, with null handling (null + value = value only when both are known: rule in KDoc).
- [ ] JSON reader passes a conformance set (strings with escapes and Unicode, numbers, nesting, errors).
- [ ] KDoc on every public type. Interfaces frozen with tag `core-model-v1`.
