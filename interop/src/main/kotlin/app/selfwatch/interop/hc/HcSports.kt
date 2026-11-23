/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import android.health.connect.datatypes.ExerciseSessionType as E
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * FIT `sport` / `sub_sport` (as stored in `workout`) to and from Health Connect exercise types.
 * Sports without a Health Connect match map to "other workout"; unknown exercise types map to FIT
 * generic (0). FIT numbers are from the public FIT profile (`sport`, `sub_sport` types).
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object HcSports {
    // FIT sub_sport values used below.
    private const val SUB_TREADMILL = 1
    private const val SUB_INDOOR_CYCLING = 6
    private const val SUB_INDOOR_ROWING = 14
    private const val SUB_LAP_SWIMMING = 17
    private const val SUB_OPEN_WATER = 18
    private const val SUB_STRENGTH = 20
    private const val SUB_YOGA = 43
    private const val SUB_PILATES = 44

    private val BY_SPORT: Map<Int, Int> = mapOf(
        0 to E.EXERCISE_SESSION_TYPE_OTHER_WORKOUT,
        1 to E.EXERCISE_SESSION_TYPE_RUNNING,
        2 to E.EXERCISE_SESSION_TYPE_BIKING,
        5 to E.EXERCISE_SESSION_TYPE_SWIMMING_POOL,
        6 to E.EXERCISE_SESSION_TYPE_BASKETBALL,
        7 to E.EXERCISE_SESSION_TYPE_SOCCER,
        8 to E.EXERCISE_SESSION_TYPE_TENNIS,
        9 to E.EXERCISE_SESSION_TYPE_FOOTBALL_AMERICAN,
        10 to E.EXERCISE_SESSION_TYPE_OTHER_WORKOUT,
        11 to E.EXERCISE_SESSION_TYPE_WALKING,
        12 to E.EXERCISE_SESSION_TYPE_SKIING,
        13 to E.EXERCISE_SESSION_TYPE_SKIING,
        14 to E.EXERCISE_SESSION_TYPE_SNOWBOARDING,
        15 to E.EXERCISE_SESSION_TYPE_ROWING,
        16 to E.EXERCISE_SESSION_TYPE_HIKING,
        17 to E.EXERCISE_SESSION_TYPE_HIKING,
        19 to E.EXERCISE_SESSION_TYPE_PADDLING,
        25 to E.EXERCISE_SESSION_TYPE_GOLF,
        30 to E.EXERCISE_SESSION_TYPE_SKATING,
        31 to E.EXERCISE_SESSION_TYPE_ROCK_CLIMBING,
        32 to E.EXERCISE_SESSION_TYPE_SAILING,
        33 to E.EXERCISE_SESSION_TYPE_ICE_SKATING,
        35 to E.EXERCISE_SESSION_TYPE_SNOWSHOEING,
        37 to E.EXERCISE_SESSION_TYPE_PADDLING,
        38 to E.EXERCISE_SESSION_TYPE_SURFING,
        41 to E.EXERCISE_SESSION_TYPE_PADDLING,
        47 to E.EXERCISE_SESSION_TYPE_BOXING,
        53 to E.EXERCISE_SESSION_TYPE_SCUBA_DIVING,
        62 to E.EXERCISE_SESSION_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
    )

    /** The Health Connect exercise type for a FIT [sport] and [subSport]. */
    fun toExerciseType(sport: Int, subSport: Int?): Int = when {
        sport == 1 && subSport == SUB_TREADMILL -> E.EXERCISE_SESSION_TYPE_RUNNING_TREADMILL
        sport == 2 && subSport == SUB_INDOOR_CYCLING -> E.EXERCISE_SESSION_TYPE_BIKING_STATIONARY
        sport == 5 && subSport == SUB_OPEN_WATER -> E.EXERCISE_SESSION_TYPE_SWIMMING_OPEN_WATER
        sport == 15 && subSport == SUB_INDOOR_ROWING -> E.EXERCISE_SESSION_TYPE_ROWING_MACHINE
        sport == 10 && subSport == SUB_STRENGTH -> E.EXERCISE_SESSION_TYPE_STRENGTH_TRAINING
        sport == 10 && subSport == SUB_YOGA -> E.EXERCISE_SESSION_TYPE_YOGA
        sport == 10 && subSport == SUB_PILATES -> E.EXERCISE_SESSION_TYPE_PILATES
        else -> BY_SPORT[sport] ?: E.EXERCISE_SESSION_TYPE_OTHER_WORKOUT
    }

    /** The FIT sport and sub-sport (null when none fits) for a Health Connect exercise type. */
    fun fromExerciseType(type: Int): Pair<Int, Int?> = when (type) {
        E.EXERCISE_SESSION_TYPE_RUNNING -> 1 to null
        E.EXERCISE_SESSION_TYPE_RUNNING_TREADMILL -> 1 to SUB_TREADMILL
        E.EXERCISE_SESSION_TYPE_BIKING -> 2 to null
        E.EXERCISE_SESSION_TYPE_BIKING_STATIONARY -> 2 to SUB_INDOOR_CYCLING
        E.EXERCISE_SESSION_TYPE_SWIMMING_POOL -> 5 to SUB_LAP_SWIMMING
        E.EXERCISE_SESSION_TYPE_SWIMMING_OPEN_WATER -> 5 to SUB_OPEN_WATER
        E.EXERCISE_SESSION_TYPE_BASKETBALL -> 6 to null
        E.EXERCISE_SESSION_TYPE_SOCCER -> 7 to null
        E.EXERCISE_SESSION_TYPE_TENNIS -> 8 to null
        E.EXERCISE_SESSION_TYPE_FOOTBALL_AMERICAN -> 9 to null
        E.EXERCISE_SESSION_TYPE_STRENGTH_TRAINING, E.EXERCISE_SESSION_TYPE_WEIGHTLIFTING -> 10 to SUB_STRENGTH
        E.EXERCISE_SESSION_TYPE_YOGA -> 10 to SUB_YOGA
        E.EXERCISE_SESSION_TYPE_PILATES -> 10 to SUB_PILATES
        E.EXERCISE_SESSION_TYPE_WALKING -> 11 to null
        E.EXERCISE_SESSION_TYPE_SKIING -> 13 to null
        E.EXERCISE_SESSION_TYPE_SNOWBOARDING -> 14 to null
        E.EXERCISE_SESSION_TYPE_ROWING -> 15 to null
        E.EXERCISE_SESSION_TYPE_ROWING_MACHINE -> 15 to SUB_INDOOR_ROWING
        E.EXERCISE_SESSION_TYPE_HIKING -> 17 to null
        E.EXERCISE_SESSION_TYPE_PADDLING -> 19 to null
        E.EXERCISE_SESSION_TYPE_GOLF -> 25 to null
        E.EXERCISE_SESSION_TYPE_SKATING -> 30 to null
        E.EXERCISE_SESSION_TYPE_ROCK_CLIMBING -> 31 to null
        E.EXERCISE_SESSION_TYPE_SAILING -> 32 to null
        E.EXERCISE_SESSION_TYPE_ICE_SKATING -> 33 to null
        E.EXERCISE_SESSION_TYPE_SNOWSHOEING -> 35 to null
        E.EXERCISE_SESSION_TYPE_SURFING -> 38 to null
        E.EXERCISE_SESSION_TYPE_BOXING -> 47 to null
        E.EXERCISE_SESSION_TYPE_SCUBA_DIVING -> 53 to null
        E.EXERCISE_SESSION_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> 62 to null
        else -> 0 to null
    }
}
