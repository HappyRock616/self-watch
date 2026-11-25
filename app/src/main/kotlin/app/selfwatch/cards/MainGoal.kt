/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.cards

import androidx.annotation.StringRes
import app.selfwatch.R
import app.selfwatch.db.SettingKeys

/**
 * The user's main goal. It sets the charts' default layers ([ChartDefaults]), the weight band and whether
 * onboarding asks for a protein goal. It no longer picks cards (one default layout for everyone).
 */
enum class MainGoal(
    /** Stable id stored in [SettingKeys.MAIN_GOAL]. */
    val id: String,
    /** Name on the choice. */
    @field:StringRes @get:StringRes val label: Int,
    /** One line under the name. */
    @field:StringRes @get:StringRes val help: Int,
) {
    /** Lose weight. */
    WEIGHT_LOSS("weight_loss", R.string.main_goal_weight_loss, R.string.main_goal_weight_loss_help),

    /** Keep a steady weight. */
    WEIGHT_MANAGEMENT("weight_management", R.string.main_goal_weight_management, R.string.main_goal_weight_management_help),

    /** Build muscle. */
    BUILD_MUSCLE("build_muscle", R.string.main_goal_build_muscle, R.string.main_goal_build_muscle_help),

    /** Athletic performance. */
    ATHLETIC("athletic", R.string.main_goal_athletic, R.string.main_goal_athletic_help),

    /** Endurance training. */
    ENDURANCE("endurance", R.string.main_goal_endurance, R.string.main_goal_endurance_help),

    /** Heart health. */
    HEART_HEALTH("heart_health", R.string.main_goal_heart_health, R.string.main_goal_heart_health_help),

    /** Stress and wellbeing. */
    STRESS_WELLBEING("stress_wellbeing", R.string.main_goal_stress, R.string.main_goal_stress_help),

    /** Stay active with age. */
    ACTIVE_AGING("active_aging", R.string.main_goal_active_aging, R.string.main_goal_active_aging_help),

    /** General health (the default). */
    GENERAL_HEALTH("general_health", R.string.main_goal_general, R.string.main_goal_general_help),
    ;

    /** True when onboarding asks for a protein goal with this goal. */
    val asksProtein: Boolean get() = this == BUILD_MUSCLE || this == ATHLETIC || this == ENDURANCE

    /** Lookup. */
    companion object {
        /** Used when nothing is stored. */
        val DEFAULT: MainGoal = GENERAL_HEALTH

        /** The goal with [id], else [DEFAULT]. */
        fun byId(id: String?): MainGoal = entries.firstOrNull { it.id == id } ?: DEFAULT

        /** The goal with [id], or null when [id] is absent or unknown. */
        fun byIdOrNull(id: String?): MainGoal? = entries.firstOrNull { it.id == id }
    }
}
