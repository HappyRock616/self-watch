# WP-04 fit (FIT file reader and writer)

Lane A. Milestones M1 (reader, decoders), M2 (workout track for GPX), M4 (weight writer). Kotlin-only (JVM). Depends on: core-model.

## Goal

Read every FIT file a Garmin watch gives us, without the Garmin SDK. Turn monitoring, sleep, HRV, stress, breathing, SpO2, workouts and weight into core-model objects. Write FIT weight files that Garmin Connect accepts. Never crash on bad input.

FIT is a public binary format. The facts below are from the public FIT protocol, checked against `references/Gadgetbridge/.../garmin/fit/`. **T-A05 re-checks every field number against the reference before decoders are finished.**

## File structure

**File header** (12 or 14 bytes, little-endian):

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | header size (12 or 14) |
| 1 | 1 | protocol version |
| 2 | 2 | profile version |
| 4 | 4 | data size (bytes of records, excluding header and final CRC) |
| 8 | 4 | `".FIT"` |
| 12 | 2 | header CRC (only if size 14; 0 means none) |

The file ends with a 2-byte CRC over everything before it.

**Record header** (1 byte):
- **Normal:** bit 7 = 0. Bit 6 = 1 for a definition, 0 for data. Bit 5 = developer data present (definitions only). Bits 0–3 = local message type (0–15).
- **Compressed timestamp:** bit 7 = 1. Bits 5–6 = local type (0–3). Bits 0–4 = seconds offset added to the last full timestamp (with rollover handling).

**Definition message:**
- reserved (1 byte)
- architecture (1 byte: 0 little-endian, 1 big-endian)
- global message number (2 bytes, in that byte order)
- field count (1 byte), then 3 bytes per field: field number, size, base type
- if developer data is present: a developer field count, then 3 bytes each (field number, size, developer data index)

**Base types:**

| Code | Type | Invalid value |
|---|---|---|
| 0x00 | enum | 0xFF |
| 0x01 | sint8 | 0x7F |
| 0x02 | uint8 | 0xFF |
| 0x83 | sint16 | 0x7FFF |
| 0x84 | uint16 | 0xFFFF |
| 0x85 | sint32 | 0x7FFFFFFF |
| 0x86 | uint32 | 0xFFFFFFFF |
| 0x07 | string (UTF-8, null-terminated) | empty |
| 0x88 / 0x89 | float32 / float64 | all bits set |
| 0x0A / 0x8B / 0x8C | uint8z / uint16z / uint32z | 0 |
| 0x0D | byte | 0xFF |
| 0x8E / 0x8F / 0x90 | sint64 / uint64 / uint64z | max / max / 0 |

A field of a type's size × n is an array of n.

**Time:** FIT timestamps are seconds since 1989-12-31 00:00 UTC. Convert with `tSec = fit + 631_065_600`. `timestamp_16` (used in monitoring) is matched to the nearest full time, so a sample a little before the last full timestamp works: `d = t16 − (last and 0xFFFF)`, then `d −= 65536` if `d > 32768`, `d += 65536` if `d < −32768`, `full = last + d` (in FIT time). Verified against reference (Gadgetbridge `FitMonitoring.computeTimestamp`); the earlier forward-only formula jumped 18 h on such samples.

**CRC-16** (also used by the Garmin protocol, WP-06):
```kotlin
private val T = intArrayOf(0x0000,0xCC01,0xD801,0x1400,0xF001,0x3C00,0x2800,0xE401,
                           0xA001,0x6C00,0x7800,0xB401,0x5000,0x9C01,0x8801,0x4400)
fun crc16(crcIn: Int, b: Int): Int {
    var crc = crcIn; var tmp = T[crc and 0xF]; crc = (crc shr 4) and 0x0FFF; crc = crc xor tmp xor T[b and 0xF]
    tmp = T[crc and 0xF]; crc = (crc shr 4) and 0x0FFF; return crc xor tmp xor T[(b shr 4) and 0xF]
}
```
(This is the published algorithm; it lives in `core-model` as `Crc16` so `garmin` can share it.)

## Messages we read

| Global # | Message | Fields used (number: name, type, scale) | Goes to |
|---|---|---|---|
| 0 | file_id | 0 type (enum), 1 manufacturer, 2 product, 3 serial, 4 time_created | file kind |
| 23 | device_info | 253 timestamp, 2 manufacturer, 4 product, 5 software_version (/100) | DeviceInfo firmware |
| 21 | event | 253 timestamp, 0 event, 1 event_type | workout pauses |
| 20 | record | 253 timestamp, 0 lat, 1 long (sint32 semicircles), 2 altitude (/5 −500 m), 78 enhanced_altitude, 3 heart_rate, 4 cadence, 5 distance (/100 m), 6 speed (/1000 m/s), 73 enhanced_speed, 13 temperature (°C) | TrackPoint |
| 19 | lap | 2 start_time, 7 total_elapsed_time (/1000 s), 9 total_distance (/100 m) | workout laps (later) |
| 18 | session | 2 start_time, 5 sport, 6 sub_sport, 7 total_elapsed_time, 8 total_timer_time, 9 total_distance, 11 total_calories, 16 avg_heart_rate, 17 max_heart_rate, 22 total_ascent, 23 total_descent | Workout |
| 34 | activity | 253 timestamp, 0 total_timer_time, 1 num_sessions, 5 local_timestamp | time-zone offset |
| 55 | monitoring | 253 timestamp, 26 timestamp_16, 2 distance (uint32 /100 m, cumulative), 3 cycles (uint32, scale 2: the raw value is steps, cumulative), 9 cycles_16 (low 16 bits of raw cycles), 5 activity_type (enum), 11 local_timestamp, 19 active_calories (uint16 kcal, cumulative), 24 current_activity_type_intensity (byte: bits 0–4 type, 5–7 intensity), 27 heart_rate (0 = no reading) | ActivityMinute, HEART_RATE (verified against reference) |
| 103 | monitoring_info | 253 timestamp, 0 local_timestamp, 1 activity_type[], 3 cycles_to_distance (/5000 m per cycle), 4 cycles_to_calories (/5000 kcal per cycle), 5 resting_metabolic_rate (kcal/day) | time-zone offset, resting kcal (verified against reference) |
| 78 | hrv | 0 time[] (/1000 s beat intervals) | HRV during workouts (later) |
| 227 | stress_level | 0 stress_level_value (sint16; negative = not measured), 1 stress_level_time (the message's own time; it has no field 253), 3 body_energy (sint8) | STRESS (verified against reference) |
| 297 | respiration_rate | 253 timestamp, 0 respiration_rate (sint16 /100) | RESPIRATION |
| 275 | sleep_level | 253 timestamp, 0 sleep_level (0 unmeasurable, 1 awake, 2 light, 3 deep, 4 REM) | SleepStageSample |
| 346 | sleep_assessment | 0 overall_sleep_score | SleepSession.score |
| 269 | spo2_data | 253 timestamp, 0 reading_spo2 (%), 1 reading_confidence | SPO2 |
| 370 | hrv_status_summary | 253 timestamp, 0 weekly_average, 1 last_night_average (both /128 ms), 6 status (0 none, 1 poor, 2 low, 3 unbalanced, 4 balanced) | `HrvDecoder.nightly` (verified against reference) |
| 371 | hrv_value | 253 timestamp, 0 value (/128 ms, 5-minute RMSSD) | HRV_RMSSD |
| 30 | weight_scale | 253 timestamp, 0 weight (/100 kg), 1 percent_fat (/100), 2 percent_hydration (/100), 4 bone_mass (/100 kg), 5 muscle_mass (/100 kg), 13 bmi (/10) | BodyMeasure |
| 211 | monitoring_hr_data | 253 timestamp, 0 resting_heart_rate, 1 current_day_resting_heart_rate (preferred) | RESTING_HR (verified against reference) |
| 398 | skin_temp_overnight | 253 timestamp, 0 local_timestamp, 1 average_deviation (float32 °C from baseline), 2 average_7_day_deviation, 4 nightly_value | SKIN_TEMP_DELTA (verified against reference) |
| 397 | skin_temp_raw | 253 timestamp, 1 deviation (float32 °C) | not stored yet (verified against reference) |
| 384 | daily_sleep | 2 sleep_score, 9 sleep_start_time, 11 sleep_end_time | SleepSession fallback (community-documented in the reference profile; check with real files) |
| 411 | sleep_summary | 0 sleep_score, 1–5 deep/light/REM/awake/unmeasurable minutes, 8 sleep_end_timestamp_utc, 10 sleep_start_timestamp_utc | SleepSession bounds (community-documented; check with real files) |

**T-A05 results** (verified against reference, Gadgetbridge 160beab41: `FitCodeGenerator/src/main/resources/fit_profile.json`, `FitImporter.java`, `messages/FitMonitoring.java`, `GarminActivitySampleProvider.java`, `FileType.java`):
- Every global message and field number in the table matches the reference profile. Rows marked "verified against reference" were changed or added.
- Steps: the reference counts the raw `cycles` value as steps (scaled cycles × 2) for **every** activity type and sums the types; it does not limit steps to walking/running. We do the same. Real files (H02) must confirm this against the watch's total, especially with cycling or swimming in the day.
- Skin temperature: 398 `skin_temp_overnight` (nightly deviation) and 397 `skin_temp_raw`; we store 398 field 1.
- Resting heart rate: dedicated message 211 `monitoring_hr_data`; field 1 (today) is preferred over field 0, as in the reference.
- File types (`file_id.type`): 4 activity, 9 weight, 15 monitoring A, 28 monitoring daily, 32 monitoring B, 44 metrics, 49 sleep, 68 HRV status.
- Open until H02: whether a monitor file can start mid-day (then the service passes a `MonitoringCarry` between a watch's files), and whether 384/411 appear in vívoactive 5 sleep files.

Unknown messages and fields are skipped, never errors.

## API

```kotlin
// io/
class FitReader(private val input: java.io.InputStream, private val maxBytes: Long = 64L shl 20) {
    fun read(onRecord: (FitRecord) -> Unit): Outcome<FitSummary>     // streams; never loads the whole file
}
data class FitRecord(val global: Int, val fields: Map<Int, FitValue>, val dev: Map<Int, FitValue>, val tSec: Long?)
sealed interface FitValue { data class I(val v: Long) : FitValue; data class D(val v: Double) : FitValue
    data class S(val v: String) : FitValue; data class A(val v: List<FitValue>) : FitValue; data object Invalid : FitValue }
data class FitSummary(val fileType: Int?, val manufacturer: Int?, val product: Int?, val records: Int, val crcOk: Boolean)
class FitWriter(out: java.io.OutputStream) { fun header(); fun define(local: Int, global: Int, fields: List<FieldDef>);
    fun data(local: Int, values: List<Any?>); fun finish() /* writes data size and CRC */ }

// decode/
object MonitoringDecoder { fun decode(file: java.io.InputStream, deviceId: Long): Outcome<IngestBatch> }  // activity minutes + HR + stress + respiration + SpO2
object SleepDecoder      { fun decode(file: java.io.InputStream, deviceId: Long): Outcome<IngestBatch> }  // stages + session + score
object HrvDecoder        { fun decode(file: java.io.InputStream, deviceId: Long): Outcome<IngestBatch> }
object WorkoutDecoder    { fun summary(file: java.io.InputStream, deviceId: Long?): Outcome<Workout>
                           fun track(file: java.io.InputStream, onPoint: (TrackPoint) -> Unit): Outcome<Int>
                           fun routePreview(file: java.io.InputStream, maxPoints: Int = 200): ByteArray /* encoded polyline */ }
object WeightDecoder     { fun decode(file: java.io.InputStream): Outcome<List<BodyMeasure>> }

// encode/
object WeightFileWriter  { fun write(out: java.io.OutputStream, entries: List<BodyMeasure>, created: Long) }  // file_id type 9 + weight_scale
```

**Rules:**
- Monitoring minutes are bucketed to whole minutes: steps summed, kcal summed, HR the last value in the minute.
- Monitoring files repeat overlapping data, so the database's `INSERT OR REPLACE` keyed by minute handles duplicates.
- Activity minutes carry cumulative counters (steps, distance, active kcal) per activity type since local midnight. The decoder keeps one counter per type, converts to deltas and sums the types. A message at time t covers up to t, so its delta goes to the minute containing t − 1 s (the 00:00:00 message still holds the previous day's total). A message whose period starts on a new local day counts its full value; a counter that drops within a day adds nothing and becomes the new baseline. Local midnight uses the given zone, else the file's `local_timestamp − timestamp` offset, else UTC. Verified against reference (`GarminActivitySampleProvider`, `AbstractSampleProvider.convertCumulativeSteps`); real files still to confirm.
- CRC policy: a wrong header or file CRC on a structurally complete file still reads every message and reports `FitSummary.crcOk = false` (decoders log a warning and use the data). Structural damage (truncation, undefined local type, bad architecture, larger than `maxBytes`) returns `Outcome.Fail`.
- The route preview uses Douglas-Peucker simplification to at most 200 points, stored as Google-polyline-style encoding (our own implementation) in `workout.route_preview`.

## Tests and definition of done

- [ ] Real files from HappyRock's vívoactive 5 in `testdata/fit/` (monitoring, sleep, HRV status, metrics, activity: walk, run with GPS, indoor). Collected over USB: the watch shows up as a drive with `GARMIN/Monitor`, `GARMIN/Sleep`, `GARMIN/Activity` (T-H02).
- [ ] Golden tests: each sample's decoded totals (steps per day, sleep minutes per stage, workout distance and time) match what the watch shows. HappyRock notes those numbers when collecting (T-H02).
- [ ] Fuzz test: 10,000 random mutations of the sample files. No exception escapes `read()`, and memory stays bounded.
- [ ] Round trip: `WeightFileWriter` output read back by `FitReader` gives the same values. T-Q09 checks that Garmin Connect's web import accepts it.
- [ ] A 10 MB activity file decodes in under 1 s on a laptop (streaming, no full buffering).
