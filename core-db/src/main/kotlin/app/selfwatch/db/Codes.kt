/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import app.selfwatch.model.device.DeviceKind

/**
 * Code tables that only the database needs (core-model has no enum for them). The stored [code]s
 * are listed in the `-- codes` lines at the top of `V1.sql`; CodeTableSyncTest keeps both in step.
 */
public enum class SyncFileState(public val code: Int) {
    DONE(1), FAILED(2), SKIPPED(3);

    /** Lookup by stored code. */
    public companion object {
        /** The state with [code], or null. */
        public fun fromCode(code: Int): SyncFileState? = entries.firstOrNull { it.code == code }
    }
}

/** How a custom food entered the app (`custom_food.origin`). */
public enum class CustomFoodOrigin(public val code: Int) {
    TYPED(1), OPEN_FOOD_FACTS(2), IMPORT(3), MENU_BOARD(4);

    /** Lookup by stored code. */
    public companion object {
        /** The origin with [code], or null. */
        public fun fromCode(code: Int): CustomFoodOrigin? = entries.firstOrNull { it.code == code }
    }
}

/** How a recipe entered the recipe book (`recipe.origin`). */
public enum class RecipeOrigin(public val code: Int) {
    TYPED(1), WEB(2), IMPORT(3);

    /** Lookup by stored code. */
    public companion object {
        /** The origin with [code], or null. */
        public fun fromCode(code: Int): RecipeOrigin? = entries.firstOrNull { it.code == code }
    }
}

/** Stored codes for [DeviceKind] (`device.kind`); core-model's enum has no code of its own. */
public object DeviceKindCodes {
    /** The stored code of [kind]. */
    public fun code(kind: DeviceKind): Int = when (kind) {
        DeviceKind.GARMIN_WATCH -> 1
        DeviceKind.WEIGHT_SCALE -> 2
        DeviceKind.HEART_RATE_SENSOR -> 3
        DeviceKind.BLOOD_PRESSURE_MONITOR -> 4
    }

    /** The kind with [code], or null. */
    public fun fromCode(code: Int): DeviceKind? = DeviceKind.entries.firstOrNull { code(it) == code }
}
