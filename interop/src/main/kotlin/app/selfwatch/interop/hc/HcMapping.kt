/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import android.health.connect.datatypes.ActiveCaloriesBurnedRecord
import android.health.connect.datatypes.BodyFatRecord
import android.health.connect.datatypes.Device
import android.health.connect.datatypes.DistanceRecord
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.HeartRateVariabilityRmssdRecord
import android.health.connect.datatypes.HydrationRecord
import android.health.connect.datatypes.MealType
import android.health.connect.datatypes.Metadata
import android.health.connect.datatypes.NutritionRecord
import android.health.connect.datatypes.OxygenSaturationRecord
import android.health.connect.datatypes.Record
import android.health.connect.datatypes.RespiratoryRateRecord
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.StepsRecord
import android.health.connect.datatypes.WeightRecord
import android.health.connect.datatypes.units.Energy
import android.health.connect.datatypes.units.Length
import android.health.connect.datatypes.units.Mass
import android.health.connect.datatypes.units.Percentage
import android.health.connect.datatypes.units.Volume
import android.os.Build
import androidx.annotation.RequiresApi
import app.selfwatch.interop.db.MealSlots
import app.selfwatch.model.food.DiaryEntry
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.health.Workout
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.time.Day
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * Client record ids: `sw:<table>:<key>`, where key is the row id, or for tables without one the
 * device and time (`<deviceId>-<tSec>`). Record types that share a source row (steps, distance and
 * active calories of one activity hour) add a suffix so each id is unique across types too.
 * Health Connect keys `clientRecordId` per app and type: writing the same id again with a higher
 * `clientRecordVersion` updates the record instead of adding a second one.
 */
object HcIds {
    /** Prefix of every Self Watch client record id. */
    const val PREFIX: String = "sw:"

    /** `sw:<table>:<key>`. */
    fun of(table: String, key: String): String = "$PREFIX$table:$key"

    /** A `body_measure` row. */
    fun body(id: Long): String = of("body_measure", id.toString())

    /** A `diary_entry` row. */
    fun diary(id: Long): String = of("diary_entry", id.toString())

    /** A `water` row. */
    fun water(id: Long): String = of("water", id.toString())

    /** A `workout` row. */
    fun workout(id: Long): String = of("workout", id.toString())

    /** A `sleep_session` row. */
    fun sleep(id: Long): String = of("sleep_session", id.toString())

    /** One activity hour of a device, for the record type [kind] (`steps`, `distance`, `kcal`). */
    fun activityHour(deviceId: Long, hourSec: Long, kind: String): String = of("activity_minute", "$deviceId-$hourSec-$kind")

    /** One heart-rate hour of a device. */
    fun heartRateHour(deviceId: Long, hourSec: Long): String = of("metric", "${MetricKindCodes.HEART_RATE}-$deviceId-h$hourSec")

    /** One metric sample. */
    fun metric(s: MetricSample): String = of("metric", "${s.kind.code}-${s.deviceId}-${s.tSec}")

    /** True when [clientRecordId] was written by Self Watch. */
    fun isOurs(clientRecordId: String?): Boolean = clientRecordId?.startsWith(PREFIX) == true

    private object MetricKindCodes {
        const val HEART_RATE = 1
    }
}

/** Activity minutes of one device summed over one clock hour (UTC hour boundaries). */
internal data class ActivityHour(
    val deviceId: Long,
    val hourSec: Long,
    val firstSec: Long,
    val endSec: Long,
    val steps: Long,
    val distanceCm: Long,
    val kcalX10: Long,
)

/** A diary line to write, with its meal slot's name. */
internal data class DiaryOut(val entry: DiaryEntry, val slotName: String?)

/** A water entry to write. */
internal data class WaterOut(val id: Long, val tSec: Long, val ml: Int)

/** A sleep session row to write with its stages. */
internal data class SleepOut(val id: Long, val session: SleepSession, val stages: List<SleepStageSample>)

/**
 * Pure conversions between `selfwatch.db` rows and the platform's Health Connect records
 * (`android.health.connect.datatypes`, Android 14+).
 *
 * Units: weight kg (stored kg ×100), body fat % (×100), energy kcal (stored ×10; the platform's
 * [Energy] is in small calories), nutrients grams (×10), sodium and cholesterol mg, water ml (the
 * platform's [Volume] is in litres), distance m (stored cm), heart rate bpm, HRV ms, SpO₂ %,
 * respiration breaths/min (stored ×10). Values Health Connect would reject (out of its ranges) give
 * null instead of a record.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal object HcMapping {
    private const val TAG = "HcMapping"
    private val WATCH: Device by lazy { Device.Builder().setType(Device.DEVICE_TYPE_WATCH).setManufacturer("Garmin").build() }
    private val SCALE: Device by lazy { Device.Builder().setType(Device.DEVICE_TYPE_SCALE).build() }

    /** Length given to records that need an interval but whose row has a single time. */
    const val POINT_INTERVAL_SEC: Long = 60

    private fun offset(zone: ZoneId, t: Instant): ZoneOffset = zone.rules.getOffset(t)

    private fun at(sec: Long): Instant = Instant.ofEpochSecond(sec)

    private fun kcal(v: Double): Energy = Energy.fromCalories(v * 1000.0)

    private fun milligrams(v: Double): Mass = Mass.fromGrams(v / 1000.0)

    /** Runs [block], turning Health Connect's range checks (IllegalArgumentException) into null. */
    private inline fun <T : Record> build(what: String, block: () -> T): T? = try {
        block()
    } catch (e: IllegalArgumentException) {
        SwLog.d(TAG) { "skipped $what: ${e.message}" }
        null
    }

    private fun meta(method: Int, device: Device?, clientId: String, version: Long): Metadata =
        Metadata.Builder()
            .setClientRecordId(clientId)
            .setClientRecordVersion(version)
            .setRecordingMethod(method)
            .apply { if (device != null) setDevice(device) }
            .build()

    private fun auto(clientId: String, version: Long, device: Device = WATCH): Metadata =
        meta(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, device, clientId, version)

    private fun manual(clientId: String, version: Long): Metadata = meta(Metadata.RECORDING_METHOD_MANUAL_ENTRY, null, clientId, version)

    private fun bodyMeta(m: BodyMeasure, version: Long): Metadata = when (m.source) {
        DataSource.SCALE -> auto(HcIds.body(m.id), version, SCALE)
        DataSource.WATCH -> auto(HcIds.body(m.id), version)
        else -> manual(HcIds.body(m.id), version)
    }

    // ---- Self Watch -> Health Connect ----

    /** A weight record (kg) for a [BodyKind.WEIGHT] row. */
    fun weight(m: BodyMeasure, version: Long, zone: ZoneId): WeightRecord? = build("weight") {
        val t = at(m.tSec)
        WeightRecord.Builder(bodyMeta(m, version), t, Mass.fromGrams(m.valueX100 * 10.0)).setZoneOffset(offset(zone, t)).build()
    }

    /** A body-fat record (%) for a [BodyKind.BODY_FAT] row. */
    fun bodyFat(m: BodyMeasure, version: Long, zone: ZoneId): BodyFatRecord? = build("body fat") {
        val t = at(m.tSec)
        BodyFatRecord.Builder(bodyMeta(m, version), t, Percentage.fromValue(m.valueX100 / 100.0)).setZoneOffset(offset(zone, t)).build()
    }

    /** A nutrition record for one diary line; it lasts [POINT_INTERVAL_SEC] from the line's time. */
    fun nutrition(d: DiaryOut, version: Long, zone: ZoneId): NutritionRecord? = build("nutrition") {
        val e = d.entry
        val n = e.nutrients
        val start = at(e.tSec)
        val end = at(e.tSec + POINT_INTERVAL_SEC)
        val b = NutritionRecord.Builder(manual(HcIds.diary(e.id), version), start, end)
            .setStartZoneOffset(offset(zone, start))
            .setEndZoneOffset(offset(zone, end))
            .setEnergy(n.kcalX10?.takeIf { it >= 0 }?.let { kcal(it / 10.0) })
            .setProtein(gramsX10(n.proteinX10))
            .setTotalFat(gramsX10(n.fatX10))
            .setSaturatedFat(gramsX10(n.satfatX10))
            .setTotalCarbohydrate(gramsX10(n.carbX10))
            .setSugar(gramsX10(n.sugarX10))
            .setDietaryFiber(gramsX10(n.fibreX10))
            .setSodium(n.sodiumMg?.takeIf { it >= 0 }?.let { milligrams(it.toDouble()) })
            .setCholesterol(n.cholesterolMg?.takeIf { it >= 0 }?.let { milligrams(it.toDouble()) })
            // Food database v2 nutrients (Health Connect has no added-sugar field).
            .setTransFat(gramsX10(n.transfatX10))
            .setMonounsaturatedFat(gramsX10(n.monofatX10))
            .setPolyunsaturatedFat(gramsX10(n.polyfatX10))
            .setPotassium(n.potassiumMg?.takeIf { it >= 0 }?.let { milligrams(it.toDouble()) })
            .setCalcium(n.calciumMg?.takeIf { it >= 0 }?.let { milligrams(it.toDouble()) })
            .setIron(n.ironX10?.takeIf { it >= 0 }?.let { milligrams(it / 10.0) })
            .setVitaminA(n.vitaUg?.takeIf { it >= 0 }?.let { Mass.fromGrams(it / 1_000_000.0) })
            .setVitaminC(n.vitcX10?.takeIf { it >= 0 }?.let { milligrams(it / 10.0) })
            .setVitaminD(n.vitdX10?.takeIf { it >= 0 }?.let { Mass.fromGrams(it / 10_000_000.0) })
            .setMealType(mealType(d.slotName, e.mealSlotId))
        e.name.take(NAME_MAX).ifBlank { null }?.let { b.setMealName(it) }
        b.build()
    }

    private fun gramsX10(v: Int?): Mass? = v?.takeIf { it >= 0 }?.let { Mass.fromGrams(it / 10.0) }

    /** A hydration record (ml) for one water entry. */
    fun hydration(w: WaterOut, version: Long, zone: ZoneId): HydrationRecord? = build("hydration") {
        val start = at(w.tSec)
        val end = at(w.tSec + POINT_INTERVAL_SEC)
        HydrationRecord.Builder(manual(HcIds.water(w.id), version), start, end, Volume.fromLiters(w.ml / 1000.0))
            .setStartZoneOffset(offset(zone, start)).setEndZoneOffset(offset(zone, end)).build()
    }

    /** A steps record for an activity hour, or null when it has no steps. */
    fun steps(h: ActivityHour, version: Long, zone: ZoneId): StepsRecord? {
        if (h.steps <= 0) return null
        return build("steps") {
            val s = at(h.firstSec)
            val e = at(h.endSec)
            StepsRecord.Builder(auto(HcIds.activityHour(h.deviceId, h.hourSec, "steps"), version), s, e, h.steps)
                .setStartZoneOffset(offset(zone, s)).setEndZoneOffset(offset(zone, e)).build()
        }
    }

    /** A distance record (m) for an activity hour, or null when it has no distance. */
    fun distance(h: ActivityHour, version: Long, zone: ZoneId): DistanceRecord? {
        if (h.distanceCm <= 0) return null
        return build("distance") {
            val s = at(h.firstSec)
            val e = at(h.endSec)
            DistanceRecord.Builder(auto(HcIds.activityHour(h.deviceId, h.hourSec, "distance"), version), s, e, Length.fromMeters(h.distanceCm / 100.0))
                .setStartZoneOffset(offset(zone, s)).setEndZoneOffset(offset(zone, e)).build()
        }
    }

    /** An active-calories record (kcal) for an activity hour, or null when it has none. */
    fun activeKcal(h: ActivityHour, version: Long, zone: ZoneId): ActiveCaloriesBurnedRecord? {
        if (h.kcalX10 <= 0) return null
        return build("active kcal") {
            val s = at(h.firstSec)
            val e = at(h.endSec)
            ActiveCaloriesBurnedRecord.Builder(auto(HcIds.activityHour(h.deviceId, h.hourSec, "kcal"), version), s, e, kcal(h.kcalX10 / 10.0))
                .setStartZoneOffset(offset(zone, s)).setEndZoneOffset(offset(zone, e)).build()
        }
    }

    /** An exercise session for a workout. */
    fun exercise(w: Workout, version: Long, zone: ZoneId): ExerciseSessionRecord? = build("exercise") {
        val s = at(w.startSec)
        val endSec = if (w.endSec > w.startSec) w.endSec else w.startSec + w.durationSec.coerceAtLeast(1)
        val e = at(endSec)
        val meta = if (w.source == DataSource.WATCH) {
            meta(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED, WATCH, HcIds.workout(w.id), version)
        } else {
            manual(HcIds.workout(w.id), version)
        }
        ExerciseSessionRecord.Builder(meta, s, e, HcSports.toExerciseType(w.sport, w.subSport))
            .setStartZoneOffset(offset(zone, s)).setEndZoneOffset(offset(zone, e)).build()
    }

    /** A sleep session with its stages; stage samples become intervals that end at the next sample. */
    fun sleep(o: SleepOut, version: Long, zone: ZoneId): SleepSessionRecord? = build("sleep") {
        val ss = o.session
        val s = at(ss.startSec)
        val e = at(ss.endSec)
        val samples = o.stages.filter { it.tSec >= ss.startSec && it.tSec < ss.endSec }.sortedBy { it.tSec }
        val stages = ArrayList<SleepSessionRecord.Stage>()
        for (i in samples.indices) {
            val from = samples[i].tSec
            val to = if (i + 1 < samples.size) samples[i + 1].tSec else ss.endSec
            val type = hcStage(samples[i].stage) ?: continue
            if (to > from) stages += SleepSessionRecord.Stage(at(from), at(to), type)
        }
        SleepSessionRecord.Builder(auto(HcIds.sleep(o.id), version), s, e)
            .setStartZoneOffset(offset(zone, s)).setEndZoneOffset(offset(zone, e)).setStages(stages).build()
    }

    private fun hcStage(s: SleepStage): Int? = when (s) {
        SleepStage.AWAKE -> SleepSessionRecord.StageType.STAGE_TYPE_AWAKE
        SleepStage.LIGHT -> SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_LIGHT
        SleepStage.DEEP -> SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_DEEP
        SleepStage.REM -> SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_REM
        SleepStage.UNMEASURABLE -> null
    }

    /** A heart-rate series for one device hour; zero and out-of-range samples are dropped. */
    fun heartRate(deviceId: Long, hourSec: Long, samples: List<MetricSample>, version: Long, zone: ZoneId): HeartRateRecord? {
        val ok = samples.filter { it.value in HR_RANGE }.sortedBy { it.tSec }
        if (ok.isEmpty()) return null
        return build("heart rate") {
            val s = at(ok.first().tSec)
            val e = at(ok.last().tSec + 1)
            HeartRateRecord.Builder(
                auto(HcIds.heartRateHour(deviceId, hourSec), version), s, e,
                ok.map { HeartRateRecord.HeartRateSample(it.value.toLong(), at(it.tSec)) },
            ).setStartZoneOffset(offset(zone, s)).setEndZoneOffset(offset(zone, e)).build()
        }
    }

    /** An HRV (RMSSD, ms) record. */
    fun hrv(m: MetricSample, version: Long, zone: ZoneId): HeartRateVariabilityRmssdRecord? = build("hrv") {
        val t = at(m.tSec)
        HeartRateVariabilityRmssdRecord.Builder(auto(HcIds.metric(m), version), t, m.value.toDouble()).setZoneOffset(offset(zone, t)).build()
    }

    /** An oxygen saturation (%) record. */
    fun spo2(m: MetricSample, version: Long, zone: ZoneId): OxygenSaturationRecord? = build("spo2") {
        val t = at(m.tSec)
        OxygenSaturationRecord.Builder(auto(HcIds.metric(m), version), t, Percentage.fromValue(m.value.toDouble())).setZoneOffset(offset(zone, t)).build()
    }

    /** A respiratory rate record (breaths per minute; stored ×10). */
    fun respiration(m: MetricSample, version: Long, zone: ZoneId): RespiratoryRateRecord? = build("respiration") {
        val t = at(m.tSec)
        RespiratoryRateRecord.Builder(auto(HcIds.metric(m), version), t, m.value / 10.0).setZoneOffset(offset(zone, t)).build()
    }

    /** Health Connect meal type for a meal slot: by its name's keyword, else by the seed id, else unknown. */
    fun mealType(slotName: String?, slotId: Long): Int {
        val std = slotName?.let { MealSlots.byKeyword(it.trim().lowercase()) }
            ?: MealSlots.Standard.entries.firstOrNull { it.seedId == slotId }
        return when (std) {
            MealSlots.Standard.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
            MealSlots.Standard.LUNCH -> MealType.MEAL_TYPE_LUNCH
            MealSlots.Standard.DINNER -> MealType.MEAL_TYPE_DINNER
            MealSlots.Standard.SNACKS -> MealType.MEAL_TYPE_SNACK
            null -> MealType.MEAL_TYPE_UNKNOWN
        }
    }

    // ---- Health Connect -> Self Watch ----

    /** A body row (source HEALTH_CONNECT) for a weight record. */
    fun bodyFrom(r: WeightRecord, importId: Long?): BodyMeasure =
        BodyMeasure(0, r.time.epochSecond, BodyKind.WEIGHT, null, (r.weight.inGrams / 10.0).roundToInt(), DataSource.HEALTH_CONNECT, null, importId)

    /** A body row (source HEALTH_CONNECT) for a body-fat record. */
    fun bodyFrom(r: BodyFatRecord, importId: Long?): BodyMeasure =
        BodyMeasure(0, r.time.epochSecond, BodyKind.BODY_FAT, null, (r.percentage.value * 100).roundToInt(), DataSource.HEALTH_CONNECT, null, importId)

    /**
     * A diary line for a nutrition record, in the meal slot for its meal type (unknown: by time of day,
     * see [MealSlots]). The amount is one serving of the record's totals.
     */
    fun diaryFrom(r: NutritionRecord, slots: MealSlots, zone: ZoneId, importId: Long?): DiaryEntry {
        val local = r.startTime.atZone(zone)
        val label = when (r.mealType) {
            MealType.MEAL_TYPE_BREAKFAST -> MealSlots.Standard.BREAKFAST.label
            MealType.MEAL_TYPE_LUNCH -> MealSlots.Standard.LUNCH.label
            MealType.MEAL_TYPE_DINNER -> MealSlots.Standard.DINNER.label
            MealType.MEAL_TYPE_SNACK -> MealSlots.Standard.SNACKS.label
            else -> ""
        }
        val slotId = slots.resolve(label, local.toLocalTime().toSecondOfDay())
        val name: String? = r.mealName
        return DiaryEntry(
            id = 0, day = Day.of(local.toLocalDate()), mealSlotId = slotId, tSec = r.startTime.epochSecond, food = null,
            name = name?.trim()?.takeIf { it.isNotEmpty() }?.take(NAME_MAX) ?: DEFAULT_FOOD_NAME, brand = null,
            amountX100 = 100, servingLabel = SERVING_LABEL, gramsX10 = null,
            nutrients = Nutrients(
                kcalX10 = x10(kcalOf(r.energy)) ?: 0,
                proteinX10 = x10(gramsOf(r.protein)),
                fatX10 = x10(gramsOf(r.totalFat)),
                satfatX10 = x10(gramsOf(r.saturatedFat)),
                carbX10 = x10(gramsOf(r.totalCarbohydrate)),
                sugarX10 = x10(gramsOf(r.sugar)),
                fibreX10 = x10(gramsOf(r.dietaryFiber)),
                sodiumMg = gramsOf(r.sodium)?.let { (it * 1000).roundToInt() },
                cholesterolMg = gramsOf(r.cholesterol)?.let { (it * 1000).roundToInt() },
                transfatX10 = x10(gramsOf(r.transFat)),
                monofatX10 = x10(gramsOf(r.monounsaturatedFat)),
                polyfatX10 = x10(gramsOf(r.polyunsaturatedFat)),
                potassiumMg = gramsOf(r.potassium)?.let { (it * 1000).roundToInt() },
                calciumMg = gramsOf(r.calcium)?.let { (it * 1000).roundToInt() },
                ironX10 = gramsOf(r.iron)?.let { (it * 10_000).roundToInt() },
                vitaUg = gramsOf(r.vitaminA)?.let { (it * 1_000_000).roundToInt() },
                vitcX10 = gramsOf(r.vitaminC)?.let { (it * 10_000).roundToInt() },
                vitdX10 = gramsOf(r.vitaminD)?.let { (it * 10_000_000).roundToInt() },
            ),
            importId = importId,
        )
    }

    // The platform getters are nullable Java methods; take them as nullable explicitly.
    private fun kcalOf(e: Energy?): Double? = e?.inCalories?.div(1000.0)

    private fun gramsOf(m: Mass?): Double? = m?.inGrams

    private fun x10(v: Double?): Int? = v?.let { (it * 10).roundToInt() }

    /** A workout (source HEALTH_CONNECT, no device, no track) for an exercise session. */
    fun workoutFrom(r: ExerciseSessionRecord): Workout {
        val start = r.startTime.epochSecond
        val end = r.endTime.epochSecond.coerceAtLeast(start)
        val (sport, sub) = HcSports.fromExerciseType(r.exerciseType)
        return Workout(
            id = 0, deviceId = null, sport = sport, subSport = sub, startSec = start, endSec = end,
            durationSec = (end - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), distanceM = null, kcal = null,
            avgHr = null, maxHr = null, ascentM = null, descentM = null, hasTrack = false, fitPath = null, trackPath = null,
            routePreview = null, source = DataSource.HEALTH_CONNECT,
        )
    }

    /**
     * A sleep session under [deviceId] and its stage samples. Each Health Connect stage starts a sample;
     * a gap between stages starts an UNMEASURABLE sample; a session without stages is one LIGHT (asleep)
     * sample at its start.
     */
    fun sleepFrom(r: SleepSessionRecord, deviceId: Long): Pair<SleepSession, List<SleepStageSample>> {
        val start = r.startTime.epochSecond
        val end = r.endTime.epochSecond.coerceAtLeast(start)
        val session = SleepSession(deviceId, start, end, null)
        val out = ArrayList<SleepStageSample>()
        val stages = r.stages.sortedBy { it.startTime }
        if (stages.isEmpty()) {
            out += SleepStageSample(deviceId, start, SleepStage.LIGHT)
            return session to out
        }
        var cursor = start
        for (st in stages) {
            val s = st.startTime.epochSecond.coerceIn(start, end)
            val e = st.endTime.epochSecond.coerceIn(start, end)
            if (e <= s) continue
            if (s > cursor) out += SleepStageSample(deviceId, cursor, SleepStage.UNMEASURABLE)
            out += SleepStageSample(deviceId, s, ourStage(st.type))
            cursor = e
        }
        if (cursor < end && out.isNotEmpty()) out += SleepStageSample(deviceId, cursor, SleepStage.UNMEASURABLE)
        return session to out.distinctBy { it.tSec }
    }

    private fun ourStage(t: Int): SleepStage = when (t) {
        SleepSessionRecord.StageType.STAGE_TYPE_AWAKE,
        SleepSessionRecord.StageType.STAGE_TYPE_AWAKE_OUT_OF_BED,
        SleepSessionRecord.StageType.STAGE_TYPE_AWAKE_IN_BED,
        -> SleepStage.AWAKE
        SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_LIGHT, SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING -> SleepStage.LIGHT
        SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_DEEP -> SleepStage.DEEP
        SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_REM -> SleepStage.REM
        else -> SleepStage.UNMEASURABLE
    }

    /**
     * A steps record spread evenly over the minutes it covers (the remainder on the first minutes), as
     * activity minutes of [deviceId]. Distance, calories and intensity stay 0 (not read).
     */
    fun stepsToMinutes(r: StepsRecord, deviceId: Long): List<ActivityMinute> {
        if (r.count <= 0) return emptyList()
        val first = Math.floorDiv(r.startTime.epochSecond, 60L) * 60
        val endSec = r.endTime.epochSecond.coerceAtLeast(first + 1)
        val n = ((endSec - first + 59) / 60).coerceIn(1, MAX_SPREAD_MIN)
        val base = r.count / n
        val rem = r.count % n
        return (0 until n).mapNotNull { i ->
            val steps = base + if (i < rem) 1 else 0
            if (steps <= 0) null else ActivityMinute(deviceId, first + i * 60, steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), 0, 0, 0, null)
        }
    }

    /** Health Connect's heart-rate range, bpm. */
    private val HR_RANGE = 1..300

    /** Longest span a steps record is spread over: two days of minutes. */
    private const val MAX_SPREAD_MIN = 2L * 24 * 60

    /** Diary name for nutrition records without one. */
    const val DEFAULT_FOOD_NAME: String = "Health Connect"

    /** Serving label of diary lines read from Health Connect. */
    const val SERVING_LABEL: String = "serving"

    private const val NAME_MAX = 200
}
