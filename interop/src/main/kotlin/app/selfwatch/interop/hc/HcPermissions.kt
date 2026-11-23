/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.datatypes.ActiveCaloriesBurnedRecord
import android.health.connect.datatypes.BodyFatRecord
import android.health.connect.datatypes.DistanceRecord
import android.health.connect.datatypes.ExerciseSessionRecord
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.HeartRateVariabilityRmssdRecord
import android.health.connect.datatypes.HydrationRecord
import android.health.connect.datatypes.NutritionRecord
import android.health.connect.datatypes.OxygenSaturationRecord
import android.health.connect.datatypes.Record
import android.health.connect.datatypes.RespiratoryRateRecord
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.StepsRecord
import android.health.connect.datatypes.WeightRecord
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * The Health Connect data types Self Watch exchanges (WP-12 §hc).
 *
 * The permission names are the platform's `android.permission.health.*` strings (they are plain
 * strings, so this enum is safe to touch on any Android version); the record class is only reachable
 * on Android 14+ through [recordClass].
 *
 * @property key short name used in setting keys (`hc.last_write.<key>`, `hc.changes_token.<key>`).
 * @property permissionName the suffix of the permission strings (`READ_<name>`, `WRITE_<name>`).
 * @property write Self Watch writes this type.
 * @property read Self Watch reads this type.
 */
enum class HcType(val key: String, private val permissionName: String, val write: Boolean, val read: Boolean) {
    WEIGHT("weight", "WEIGHT", write = true, read = true),
    BODY_FAT("body_fat", "BODY_FAT", write = true, read = true),
    NUTRITION("nutrition", "NUTRITION", write = true, read = true),
    HYDRATION("hydration", "HYDRATION", write = true, read = false),
    STEPS("steps", "STEPS", write = true, read = true),
    DISTANCE("distance", "DISTANCE", write = true, read = false),
    ACTIVE_KCAL("active_kcal", "ACTIVE_CALORIES_BURNED", write = true, read = false),
    EXERCISE("exercise", "EXERCISE", write = true, read = true),
    SLEEP("sleep", "SLEEP", write = true, read = true),
    HEART_RATE("heart_rate", "HEART_RATE", write = true, read = false),
    HRV("hrv", "HEART_RATE_VARIABILITY", write = true, read = false),
    SPO2("spo2", "OXYGEN_SATURATION", write = true, read = false),
    RESPIRATORY_RATE("respiratory_rate", "RESPIRATORY_RATE", write = true, read = false),
    ;

    /** The write permission string (`android.permission.health.WRITE_…`). */
    val writePermission: String get() = "${HcPermissions.PREFIX}WRITE_$permissionName"

    /** The read permission string (`android.permission.health.READ_…`). */
    val readPermission: String get() = "${HcPermissions.PREFIX}READ_$permissionName"

    /** The framework record class of this type. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun recordClass(): Class<out Record> = when (this) {
        WEIGHT -> WeightRecord::class.java
        BODY_FAT -> BodyFatRecord::class.java
        NUTRITION -> NutritionRecord::class.java
        HYDRATION -> HydrationRecord::class.java
        STEPS -> StepsRecord::class.java
        DISTANCE -> DistanceRecord::class.java
        ACTIVE_KCAL -> ActiveCaloriesBurnedRecord::class.java
        EXERCISE -> ExerciseSessionRecord::class.java
        SLEEP -> SleepSessionRecord::class.java
        HEART_RATE -> HeartRateRecord::class.java
        HRV -> HeartRateVariabilityRmssdRecord::class.java
        SPO2 -> OxygenSaturationRecord::class.java
        RESPIRATORY_RATE -> RespiratoryRateRecord::class.java
    }

    /** Lookups. */
    companion object {
        /** Types Self Watch writes. */
        val WRITTEN: List<HcType> = entries.filter { it.write }

        /** Types Self Watch reads. */
        val READ: List<HcType> = entries.filter { it.read }

        /** The type of a record instance, or null for a type Self Watch does not handle. */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        fun of(record: Record): HcType? = entries.firstOrNull { it.recordClass().isInstance(record) }
    }
}

/**
 * Builds the permission set to request and reads which are held.
 *
 * On Android 14+ health permissions are ordinary runtime permissions: the app asks for them with the
 * standard `ActivityResultContracts.RequestMultiplePermissions` contract (the system shows Health
 * Connect's own permission screen) and checks them with `checkSelfPermission`. Usage in the app:
 * ```
 * val launcher = registerForActivityResult(RequestMultiplePermissions()) { r -> ... r.filterValues { it }.keys ... }
 * launcher.launch(HcPermissions.build(api, background = autoSyncOn).toTypedArray())
 * ```
 * The same strings must be declared in the manifest (interop's manifest does this, see
 * [PermissionsRationaleActivity]).
 */
object HcPermissions {
    /** Prefix of every health permission. */
    const val PREFIX: String = "android.permission.health."

    /** `READ_HEALTH_DATA_HISTORY`: read data older than 30 days before the first grant (newer Health Connect only). */
    const val HISTORY: String = "${PREFIX}READ_HEALTH_DATA_HISTORY"

    /** `READ_HEALTH_DATA_IN_BACKGROUND`: read while the app is not in the foreground (newer Health Connect only). */
    const val BACKGROUND: String = "${PREFIX}READ_HEALTH_DATA_IN_BACKGROUND"

    /** Write permissions of every [HcType.WRITTEN] type. */
    fun writePermissions(): Set<String> = HcType.WRITTEN.mapTo(LinkedHashSet()) { it.writePermission }

    /** Read permissions of every [HcType.READ] type. */
    fun readPermissions(): Set<String> = HcType.READ.mapTo(LinkedHashSet()) { it.readPermission }

    /**
     * The permissions to request.
     *
     * @param write include the write permissions.
     * @param read include the read permissions.
     * @param history include [HISTORY]; pass true only when the user explicitly asks to import data older
     *   than 30 days (COMPATIBILITY row 36).
     * @param background include [BACKGROUND]; pass true only when the user turned on automatic sync.
     */
    fun build(write: Boolean = true, read: Boolean = true, history: Boolean = false, background: Boolean = false): Set<String> {
        val out = LinkedHashSet<String>()
        if (write) out += writePermissions()
        if (read) out += readPermissions()
        if (read && history) out += HISTORY
        if (read && background) out += BACKGROUND
        return out
    }

    /**
     * [build], with [history] and [background] dropped when this phone's Health Connect does not define
     * that permission (it arrived in a later Health Connect module update than Android 14's first).
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun build(api: HcApi, write: Boolean = true, read: Boolean = true, history: Boolean = false, background: Boolean = false): Set<String> =
        build(
            write = write,
            read = read,
            history = history && api.permissionSupported(HISTORY),
            background = background && api.permissionSupported(BACKGROUND),
        )

    /** The permissions of [requested] that are not in [granted]. */
    fun missing(requested: Set<String>, granted: Set<String>): Set<String> = requested - granted

    /** Every permission Self Watch may hold (the full [build] set with history and background). */
    fun all(): Set<String> = build(history = true, background = true)

    /**
     * The health permissions this app holds now: none below Android 14 (Health Connect is not offered
     * there), otherwise every one of [all] that `checkSelfPermission` reports granted.
     */
    fun granted(ctx: Context): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return emptySet()
        return all().filterTo(LinkedHashSet()) { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    /** True when the platform defines [permission] (so asking for it can't fail the request). */
    fun defined(ctx: Context, permission: String): Boolean = try {
        ctx.packageManager.getPermissionInfo(permission, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
