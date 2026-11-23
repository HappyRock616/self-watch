/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

/** The tables of `selfwatch.db`, used to say what changed (see [DbChanges]). [sql] is the table name. */
public enum class Table(public val sql: String) {
    DEVICE("device"),
    SYNC_FILE("sync_file"),
    ACTIVITY("activity_minute"),
    METRIC("metric"),
    SLEEP_SESSION("sleep_session"),
    SLEEP_STAGE("sleep_stage"),
    WORKOUT("workout"),
    MEASURE_KIND("measure_kind"),
    BODY("body_measure"),
    CUSTOM_FOOD("custom_food"),
    CUSTOM_SERVING("custom_serving"),
    MEAL_SLOT("meal_slot"),
    DIARY("diary_entry"),
    FAVOURITE("favourite"),
    WATER("water"),
    FAST("fast"),
    RECIPE("recipe"),
    RECIPE_ITEM("recipe_item"),
    RECIPE_TAG("recipe_tag"),
    MY_FOOD_FTS("my_food_fts"),
    GOAL("goal"),
    SETTING("setting"),
    DAY_SUMMARY("day_summary"),
    WATCH_APP("watch_app"),
    WATCH_APP_SETTINGS("watch_app_settings"),
    LINK_PERMISSION("link_permission"),
    NET_LOG("net_log"),
    IMPORT_BATCH("import_batch"),
    ;

    /** Lookup by table name. */
    public companion object {
        /** The table called [sql], or null. */
        public fun fromSql(sql: String): Table? = entries.firstOrNull { it.sql == sql }
    }
}
