/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.Table
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.DayRange

/**
 * A kind of data a card shows. A card lists the kinds it needs ([Card.needs]); it is offered by default
 * only when one of the user's devices records one of them ([Recorded]). [stored] is false for data Self
 * Watch does not receive from any device yet: cards for it show "Your devices don't record this" until a
 * device driver stores that data and maps it here (see [RecordedLoader]).
 */
enum class DataKind(val stored: Boolean = true) {
    STEPS, DISTANCE, ACTIVE_CALORIES, ACTIVE_TIME, RESTING_CALORIES,
    HEART_RATE, RESTING_HR, HRV, SPO2, RESPIRATION, STRESS, ENERGY_LEVEL, SKIN_TEMP,
    SLEEP, SLEEP_SCORE, WORKOUTS, VO2_MAX, RECOVERY_TIME,
    WEIGHT, BODY_FAT, BODY_COMPOSITION, MEASUREMENTS, BLOOD_PRESSURE,

    // Live readings while connected, and device data (battery, syncs).
    LIVE, BATTERY, DEVICE_SYNC,

    // Not received from any device yet (the cards wait for them).
    FLOORS(false), ELEVATION(false), TRAINING_STATUS(false),
    RACE_TIMES(false), ENDURANCE(false), HILL(false), LACTATE_THRESHOLD(false), CYCLING_POWER(false),
    HEAT_ALTITUDE(false), PAI(false), ECG(false), BLOOD_GLUCOSE(false), CYCLING_SENSOR(false),
    SPEED_ZONES(false), SLEEP_NEED(false), NAPS(false), HEALTH_CHECK(false), FITNESS_AGE(false),
    CYCLE_TRACKING(false), JET_LAG(false), SUGGESTED_WORKOUT(false),
    ACHIEVEMENTS(false), SOLAR(false), ROOM_CLIMATE(false),
}

/** The data kinds the user's devices record (plus what can always be typed in by hand). */
class Recorded(val kinds: Set<DataKind>) {

    /** True when [card] needs nothing from a device or one of its kinds is recorded. */
    fun records(card: Card<*>): Boolean = card.needs.isEmpty() || card.needs.any { it in kinds }

    override fun equals(other: Any?): Boolean = other is Recorded && other.kinds == kinds

    override fun hashCode(): Int = kinds.hashCode()

    override fun toString(): String = "Recorded($kinds)"

    /** Fixed sets. */
    companion object {
        /** Kinds the user can always enter by hand (Weight, Measurements). */
        val MANUAL: Set<DataKind> = setOf(DataKind.WEIGHT, DataKind.BODY_FAT, DataKind.MEASUREMENTS)

        /** Every stored kind: used where no device information is at hand (and in tests). */
        val ALL: Recorded = Recorded(DataKind.entries.filter { it.stored }.toSet())

        /** No device: only what can be typed in. */
        val NONE: Recorded = Recorded(MANUAL)
    }
}

/**
 * Works out [Recorded] from the active devices' features (what they can record) and the data already
 * stored (an import or a removed device still counts while its data is there, so the cards keep showing
 * it). Cheap: one indexed lookup per kind.
 */
class RecordedLoader(private val db: SelfWatchDb, private val clock: Clock) {

    /** The kinds recorded now. */
    suspend fun load(): Recorded {
        val out = HashSet(Recorded.MANUAL)
        for (d in db.devices.active()) out += fromDevice(d)
        for ((metric, kind) in METRIC_KINDS) if (kind !in out && db.metrics.hasAny(metric)) out += kind
        for ((body, kind) in BODY_KINDS) if (kind !in out && db.body.latest(body) != null) out += kind
        if (DataKind.WORKOUTS !in out && db.workouts.list(1).isNotEmpty()) out += DataKind.WORKOUTS
        if (DataKind.BATTERY !in out && db.battery.hasAny()) out += DataKind.BATTERY
        val today = clock.today()
        val recent = DayRange(today.plusDays(-RECENT_DAYS), today)
        for (s in db.summaries.days(recent)) {
            if (s.steps != null) out += DataKind.STEPS
            if (s.distanceM != null) out += DataKind.DISTANCE
            if (s.activeKcal != null) out += DataKind.ACTIVE_CALORIES
            if (s.activeMin != null) out += DataKind.ACTIVE_TIME
            if (s.restingKcal != null) out += DataKind.RESTING_CALORIES
            if (s.restingHr != null) out += DataKind.RESTING_HR
            if (s.hrvAvgMs != null) out += DataKind.HRV
            if (s.sleepMin != null) out += DataKind.SLEEP
        }
        val sessions = db.sleep.sessions(recent, clock.zone())
        if (sessions.isNotEmpty()) out += DataKind.SLEEP
        if (sessions.any { it.score != null }) out += DataKind.SLEEP_SCORE
        return Recorded(out)
    }

    /** Pure parts. */
    companion object {
        private const val RECENT_DAYS = 60

        /** Tables whose changes can change [load]. */
        // Not METRIC: it changes all the time during a sync, and the day summaries follow it anyway.
        val TABLES: Set<Table> = setOf(Table.DEVICE, Table.BODY, Table.WORKOUT, Table.DAY_SUMMARY, Table.SLEEP_SESSION, Table.DEVICE_BATTERY)

        private val METRIC_KINDS: Map<MetricKind, DataKind> = mapOf(
            MetricKind.HEART_RATE to DataKind.HEART_RATE,
            MetricKind.HRV_RMSSD to DataKind.HRV,
            MetricKind.SPO2 to DataKind.SPO2,
            MetricKind.RESPIRATION to DataKind.RESPIRATION,
            MetricKind.STRESS to DataKind.STRESS,
            MetricKind.SKIN_TEMP_DELTA to DataKind.SKIN_TEMP,
            MetricKind.RESTING_HR to DataKind.RESTING_HR,
            MetricKind.RESTING_KCAL to DataKind.RESTING_CALORIES,
            MetricKind.BP_SYSTOLIC to DataKind.BLOOD_PRESSURE,
            MetricKind.ENERGY_LEVEL to DataKind.ENERGY_LEVEL,
            // VO2 max counts once an estimate is stored: no capability flag says a watch sends it.
            MetricKind.VO2_MAX_RUNNING to DataKind.VO2_MAX,
            MetricKind.VO2_MAX_CYCLING to DataKind.VO2_MAX,
            MetricKind.RECOVERY_MIN to DataKind.RECOVERY_TIME,
        )

        private val BODY_KINDS: Map<BodyKind, DataKind> = mapOf(
            BodyKind.MUSCLE_MASS to DataKind.BODY_COMPOSITION,
            BodyKind.BONE_MASS to DataKind.BODY_COMPOSITION,
            BodyKind.BODY_WATER to DataKind.BODY_COMPOSITION,
        )

        /** What device [d] can record, from its features (as Gadgetbridge reads capability flags). */
        fun fromDevice(d: DeviceInfo): Set<DataKind> {
            val out = HashSet<DataKind>()
            val f = d.features
            val garmin = d.kind == DeviceKind.GARMIN_WATCH
            // Every device syncs and reports its battery; live readings come from watches and straps.
            out += DataKind.DEVICE_SYNC
            if (Feature.BATTERY in f || garmin) out += DataKind.BATTERY
            if (garmin || Feature.REALTIME_HR in f || d.kind == DeviceKind.HEART_RATE_SENSOR) out += DataKind.LIVE
            if (Feature.HEALTH_ACTIVITY in f) out += listOf(DataKind.STEPS, DataKind.DISTANCE, DataKind.ACTIVE_CALORIES, DataKind.ACTIVE_TIME)
            if (Feature.HEALTH_SLEEP in f) {
                out += DataKind.SLEEP
                if (garmin) out += DataKind.SLEEP_SCORE
            }
            if (Feature.HEALTH_HR in f || Feature.REALTIME_HR in f || d.kind == DeviceKind.HEART_RATE_SENSOR) out += DataKind.HEART_RATE
            if (Feature.HEALTH_HR in f) out += DataKind.RESTING_HR
            if (Feature.HEALTH_HRV in f) out += DataKind.HRV
            if (Feature.HEALTH_SPO2 in f) out += DataKind.SPO2
            if (Feature.HEALTH_STRESS in f) {
                out += DataKind.STRESS
                // The 0–100 energy reserve comes with Garmin's stress tracking.
                if (garmin) out += DataKind.ENERGY_LEVEL
            }
            if (Feature.HEALTH_RESPIRATION in f) out += DataKind.RESPIRATION
            if (Feature.HEALTH_SKIN_TEMP in f) out += DataKind.SKIN_TEMP
            if (Feature.WORKOUTS in f) out += DataKind.WORKOUTS
            if (Feature.HEALTH_WEIGHT in f || d.kind == DeviceKind.WEIGHT_SCALE) out += DataKind.WEIGHT
            if (Feature.HEALTH_BODY_COMPOSITION in f) out += listOf(DataKind.BODY_FAT, DataKind.BODY_COMPOSITION)
            if (Feature.HEALTH_BLOOD_PRESSURE in f || d.kind == DeviceKind.BLOOD_PRESSURE_MONITOR) out += DataKind.BLOOD_PRESSURE
            return out
        }
    }
}
