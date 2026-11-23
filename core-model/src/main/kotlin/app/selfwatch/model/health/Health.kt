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
    ;

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
