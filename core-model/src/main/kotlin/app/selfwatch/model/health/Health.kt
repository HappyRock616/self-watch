/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.health

import app.selfwatch.model.time.Day

/**
 * One minute of activity from a device.
 *
 * @property tSec start of the minute, UTC epoch seconds.
 * @property intensity 0 sedentary, 1 light, 2 moderate, 3 vigorous.
 * @property activityType FIT `activity_type`, if known.
 */
public data class ActivityMinute(
    val deviceId: Long,
    val tSec: Long,
    val steps: Int,
    val activeKcalX10: Int,
    val distanceCm: Int,
    val intensity: Int,
    val activityType: Int?,
)

/** Kinds of time-series metric. [code] is stored in `metric.kind`; the value scaling is per kind. */
public enum class MetricKind(public val code: Int) {
    /** Beats per minute. */
    HEART_RATE(1),

    /** Milliseconds. */
    HRV_RMSSD(2),

    /** Percent. */
    SPO2(3),

    /** Breaths per minute ×10. */
    RESPIRATION(4),

    /** 0..100. */
    STRESS(5),

    /** °C ×100 relative to baseline. */
    SKIN_TEMP_DELTA(6),

    /** Beats per minute, one per day. */
    RESTING_HR(7),

    /**
     * The watch's resting metabolic rate in kcal per whole day (FIT `monitoring_info` field 5), one
     * per day, stamped at the start of the local day it applies to. Added in core-model v1.1.
     */
    RESTING_KCAL(8),

    /** Systolic blood pressure, mmHg. Added in core-model v1.2. */
    BP_SYSTOLIC(9),

    /** Diastolic blood pressure, mmHg. Added in core-model v1.2. */
    BP_DIASTOLIC(10),

    /**
     * The watch's energy level, 0..100 (FIT `stress_level` field 3), as often as the watch reports it.
     * Added in core-model v1.3.
     */
    ENERGY_LEVEL(11),

    /**
     * Running VO2 max, ml/kg/min ×10, stamped when the watch updated its estimate (FIT `max_met_data`
     * `update_time`), or at the workout's end when it comes from an activity file. Watches that keep a
     * single VO2 max (no separate cycling value) report it here, as Garmin Connect does. One estimate per
     * update, so a day can hold several: readers take the day's last. Added in core-model v1.4.
     */
    VO2_MAX_RUNNING(12),

    /** Cycling VO2 max, ml/kg/min ×10, stamped as [VO2_MAX_RUNNING]. Added in core-model v1.4. */
    VO2_MAX_CYCLING(13),

    /**
     * The watch's HRV status summary (FIT `hrv_status_summary`, 370), one set per night, stamped at the
     * summary: last night's average in ms. Added in core-model v1.5 (0.0.4).
     */
    HRV_NIGHT_AVG(14),

    /** The watch's 7-day HRV average in ms (same summary). */
    HRV_WEEK_AVG(15),

    /** Lower edge of the watch's balanced HRV baseline in ms (same summary). */
    HRV_BASELINE_LOW(16),

    /** Upper edge of the watch's balanced HRV baseline in ms (same summary). */
    HRV_BASELINE_HIGH(17),

    /** The watch's HRV status: 1 poor, 2 low, 3 unbalanced, 4 balanced (same summary; 0 none is not stored). */
    HRV_STATUS(18),

    /**
     * The watch's recovery time in minutes (FIT `metric_recovery`, 284), stamped at the moment it was
     * set (the countdown starts there). Added in 0.0.4.
     */
    RECOVERY_MIN(19),

    /** A nap (FIT `nap_event`, 412): its length in minutes, stamped at its start. Added in 0.0.4. */
    NAP_MIN(20),
    ;

    /** True for the VO2 max kinds ([VO2_MAX_RUNNING], [VO2_MAX_CYCLING]). */
    public val isVo2Max: Boolean get() = this == VO2_MAX_RUNNING || this == VO2_MAX_CYCLING

    /** Lookup by stored code. */
    public companion object {
        /** The kind with [code], or null. */
        public fun fromCode(code: Int): MetricKind? = entries.firstOrNull { it.code == code }
    }
}

/** One metric reading; [value] is scaled as its [kind] says. */
public data class MetricSample(val deviceId: Long, val kind: MetricKind, val tSec: Long, val value: Int)

/** Sleep stages. [code] is stored in the database. */
public enum class SleepStage(public val code: Int) {
    AWAKE(0), LIGHT(1), DEEP(2), REM(3), UNMEASURABLE(4);

    /** Lookup by stored code. */
    public companion object {
        /** The stage with [code], or null. */
        public fun fromCode(code: Int): SleepStage? = entries.firstOrNull { it.code == code }
    }
}

/** The sleep stage that starts at [tSec] and lasts until the next sample. */
public data class SleepStageSample(val deviceId: Long, val tSec: Long, val stage: SleepStage)

/** One night (or nap) of sleep; [score] is the device's sleep score if it gave one. */
public data class SleepSession(val deviceId: Long, val startSec: Long, val endSec: Long, val score: Int?)

/**
 * A recorded workout.
 *
 * @property sport FIT `sport`.
 * @property subSport FIT `sub_sport`, if known.
 * @property fitPath path of the saved original FIT file, if any.
 * @property trackPath path of the decoded track file, if any.
 * @property routePreview small encoded route outline for list thumbnails, if any.
 *
 * Equality compares [routePreview] by content.
 */
public data class Workout(
    val id: Long,
    val deviceId: Long?,
    val sport: Int,
    val subSport: Int?,
    val startSec: Long,
    val endSec: Long,
    val durationSec: Int,
    val distanceM: Int?,
    val kcal: Int?,
    val avgHr: Int?,
    val maxHr: Int?,
    val ascentM: Int?,
    val descentM: Int?,
    val hasTrack: Boolean,
    val fitPath: String?,
    val trackPath: String?,
    val routePreview: ByteArray?,
    val source: DataSource,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Workout) return false
        return id == other.id && deviceId == other.deviceId && sport == other.sport && subSport == other.subSport &&
            startSec == other.startSec && endSec == other.endSec && durationSec == other.durationSec &&
            distanceM == other.distanceM && kcal == other.kcal && avgHr == other.avgHr && maxHr == other.maxHr &&
            ascentM == other.ascentM && descentM == other.descentM && hasTrack == other.hasTrack &&
            fitPath == other.fitPath && trackPath == other.trackPath &&
            routePreview.contentEquals(other.routePreview) && source == other.source
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + startSec.hashCode()
        h = 31 * h + endSec.hashCode()
        h = 31 * h + sport
        h = 31 * h + (deviceId?.hashCode() ?: 0)
        h = 31 * h + routePreview.contentHashCode()
        h = 31 * h + source.hashCode()
        return h
    }
}

/**
 * One point of a workout track. Every field except [tSec] is null when not recorded.
 *
 * @property latE7 latitude, degrees ×10⁷.
 * @property lonE7 longitude, degrees ×10⁷.
 * @property altCm altitude in centimetres.
 * @property tempC10 temperature, °C ×10.
 * @property speedMms speed in millimetres per second.
 */
public data class TrackPoint(
    val tSec: Long,
    val latE7: Int?,
    val lonE7: Int?,
    val altCm: Int?,
    val hrBpm: Int?,
    val cadence: Int?,
    val tempC10: Int?,
    val speedMms: Int?,
)

/** Body measurement kinds. [code] is stored in `body_measure.kind`; [unit] is the display unit. */
public enum class BodyKind(public val code: Int, public val unit: String) {
    WEIGHT(1, "kg"), BODY_FAT(2, "%"), BMI(3, ""), WAIST(4, "cm"), HIPS(5, "cm"), CHEST(6, "cm"), ARM(7, "cm"),
    THIGH(8, "cm"), NECK(9, "cm"), MUSCLE_MASS(10, "kg"), BONE_MASS(11, "kg"), BODY_WATER(12, "%"), CUSTOM(99, "");

    /** Lookup by stored code. */
    public companion object {
        /** The kind with [code], or null. */
        public fun fromCode(code: Int): BodyKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One body measurement. [valueX100] is in the kind's unit ×100 (for example kg ×100).
 * [customKindId] is set only when [kind] is [BodyKind.CUSTOM].
 */
public data class BodyMeasure(
    val id: Long,
    val tSec: Long,
    val kind: BodyKind,
    val customKindId: Long?,
    val valueX100: Int,
    val source: DataSource,
    val deviceId: Long?,
    val importId: Long?,
)

/** Where a record came from. [code] is stored in `workout.source` and `body_measure.source`. */
public enum class DataSource(public val code: Int) {
    WATCH(1), SCALE(2), MANUAL(3), IMPORT(4), HEALTH_CONNECT(5);

    /** Lookup by stored code. */
    public companion object {
        /** The source with [code], or null. */
        public fun fromCode(code: Int): DataSource? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Totals for one local day. Null means unknown (no data), 0 means a real zero.
 *
 * @property tzOffsetMin the UTC offset, in minutes, that defined this day.
 */
public data class DaySummary(
    val day: Day,
    val tzOffsetMin: Int,
    val steps: Int?,
    val activeKcal: Int?,
    val restingKcal: Int?,
    val distanceM: Int?,
    val activeMin: Int?,
    val restingHr: Int?,
    val hrvAvgMs: Int?,
    val sleepMin: Int?,
    val kcalIn: Int?,
    val proteinG: Int?,
    val fatG: Int?,
    val carbG: Int?,
    val weightKgX100: Int?,
    val waterMl: Int?,
)
