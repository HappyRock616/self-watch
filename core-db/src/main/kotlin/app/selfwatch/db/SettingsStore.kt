/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import android.database.sqlite.SQLiteDatabase
import app.selfwatch.model.food.Profile
import app.selfwatch.model.food.Sex
import app.selfwatch.model.food.UnitSystem
import app.selfwatch.model.net.NetFeature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Setting keys (table `setting`) and their defaults, per WP-03. Defaults live here in code, not in
 * seeded rows: a key with no row reads as its default.
 */
public object SettingKeys {
    /** Height in cm (int). */
    public const val PROFILE_HEIGHT_CM: String = "profile.height_cm"

    /** Birth year (int). */
    public const val PROFILE_BIRTH_YEAR: String = "profile.birth_year"

    /** Sex: `female`, `male` or absent. */
    public const val PROFILE_SEX: String = "profile.sex"

    /** Display units: `metric`, `imperial` or `phone`. */
    public const val UNITS: String = "units"

    /** `dark`, `light` or `system`. */
    public const val THEME: String = "theme"

    /** Accent colour, `#RRGGBB`. */
    public const val ACCENT: String = "accent"

    /** `always` or `sync_only`. */
    public const val CONNECTION_MODE: String = "connection.mode"

    /** Hours between syncs in sync-only mode (int). */
    public const val CONNECTION_SYNC_ONLY_HOURS: String = "connection.sync_only_hours"

    /** Food tracking switch (bool). */
    public const val FOOD_ENABLED: String = "food.enabled"

    /** Installed foods.db data version (int). */
    public const val FOOD_DB_INSTALLED_VERSION: String = "food.db.installed_version"

    /** Notification relay (bool). */
    public const val NOTIFY_ENABLED: String = "notify.enabled"

    /** Package names whose notifications are not relayed (comma list). */
    public const val NOTIFY_APPS_DENIED: String = "notify.apps_denied"

    /** Call relay (bool). */
    public const val CALLS_ENABLED: String = "calls.enabled"

    /** Show the caller's number (bool). */
    public const val CALLS_SHOW_NUMBER: String = "calls.show_number"

    /** Show the caller's name (bool). */
    public const val CALLS_SHOW_NAME: String = "calls.show_name"

    /** Weather to watch (bool). */
    public const val WEATHER_ENABLED: String = "weather.enabled"

    /** Weather source (text). */
    public const val WEATHER_SOURCE: String = "weather.source"

    /** Garmin fake sign-in (bool, COMPATIBILITY R1: off by default). */
    public const val GARMIN_FAKE_AUTH: String = "garmin.fake_auth"

    /** Automatic backup (bool). */
    public const val BACKUP_AUTO: String = "backup.auto"

    /** Backup folder (SAF tree URI). */
    public const val BACKUP_FOLDER_URI: String = "backup.folder_uri"

    /** Days between automatic backups (int). */
    public const val BACKUP_EVERY_DAYS: String = "backup.every_days"

    /** Encrypt backups (bool). */
    public const val BACKUP_ENCRYPT: String = "backup.encrypt"

    /** Export every workout as GPX automatically (bool). */
    public const val EXPORT_AUTO_GPX: String = "export.auto_gpx"

    /** Export folder (SAF tree URI). */
    public const val EXPORT_FOLDER_URI: String = "export.folder_uri"

    /** Onboarding finished (bool). */
    public const val ONBOARDING_DONE: String = "onboarding.done"

    /** Food goal mode: `auto` or `manual` (no default: auto). Owned by food's `EnergyBudget`. */
    public const val FOOD_GOAL_MODE: String = "food.goal.mode"

    /** Planned weight change, grams per week (int, negative to lose; no default: 0). */
    public const val FOOD_GOAL_RATE_G_PER_WEEK: String = "food.goal.rate_g_per_week"

    /**
     * Today's effective daily calorie goal in kcal (int), written by food's `EnergyBudget` whenever it
     * computes the budget (manual or automatic); read by the service's calories-left message.
     */
    public const val FOOD_GOAL_EFFECTIVE_KCAL: String = "food.goal.effective_kcal"

    /** Installed recipes.db data version (int). */
    public const val RECIPES_DB_INSTALLED_VERSION: String = "recipes.db.installed_version"

    /** Sync with Health Connect on app open and after each watch sync (bool; no default: false). */
    public const val HC_ENABLED: String = "hc.enabled"

    /** Also read from Health Connect in the background (bool; no default: false). */
    public const val HC_BACKGROUND_READ: String = "hc.background_read"

    /** Bluetooth capture to `files/blelog/` (bool; no default: on in debug builds, off in release). */
    public const val DEBUG_BLE_CAPTURE: String = "debug.ble_capture"

    /** Other apps may use the link (bool; no default: true). */
    public const val LINK_ENABLED: String = "link.enabled"

    /** The watch app (UUID) that gets the calories-left message; absent = off. */
    public const val LINK_CALORIES_APP: String = "link.calories_app"

    /** True once the notification listener has been bound at least once (bool). */
    public const val NOTIFY_LISTENER_SEEN: String = "notify.listener_seen"

    /** The last weather from a weather app (the service's compact JSON). */
    public const val WEATHER_LAST: String = "weather.last"

    /** Daily active-calories goal in kcal (int). */
    public const val GOAL_ACTIVE_KCAL: String = "goal.active_kcal"

    /** Main goal: `weight_loss`, `athletic` or `general_health` (no default: general health). */
    public const val MAIN_GOAL: String = "goal.main"

    /**
     * Old Body card list (comma list of card ids). Replaced by `cards.body`: the app moves it there the
     * first time it reads the Body cards. Every card tab now uses `cards.<tab>` (visible ids in order),
     * `cards.<tab>.known` and `cards.<tab>.preset`; see the app's `CardStore`.
     */
    public const val BODY_CARDS: String = "body.cards"

    /** Old companion of [BODY_CARDS]: every card id known when it was saved. Moved to `cards.body.known`. */
    public const val BODY_CARDS_KNOWN: String = "body.cards.known"

    /** Old companion of [BODY_CARDS]: the main goal it was last reset to. Moved to `cards.body.preset`. */
    public const val BODY_CARDS_PRESET: String = "body.cards.preset"

    /** The firewall key for [feature]: `net.` plus the lower-case enum name, for example `net.food_data`. */
    public fun net(feature: NetFeature): String = "net." + feature.name.lowercase()

    private val defaults: Map<String, String> = buildMap {
        put(UNITS, "phone")
        put(THEME, "dark")
        put(ACCENT, "#FF8A1F")
        put(CONNECTION_MODE, "always")
        put(CONNECTION_SYNC_ONLY_HOURS, "4")
        put(FOOD_ENABLED, "true")
        put(NOTIFY_ENABLED, "true")
        put(NOTIFY_APPS_DENIED, "")
        put(CALLS_ENABLED, "true")
        put(CALLS_SHOW_NUMBER, "false")
        put(CALLS_SHOW_NAME, "false")
        put(WEATHER_ENABLED, "false")
        put(WEATHER_SOURCE, "broadcast")
        put(GARMIN_FAKE_AUTH, "false")
        put(BACKUP_AUTO, "false")
        put(BACKUP_EVERY_DAYS, "7")
        put(BACKUP_ENCRYPT, "true")
        put(EXPORT_AUTO_GPX, "false")
        put(ONBOARDING_DONE, "false")
        for (f in NetFeature.entries) put(net(f), f.defaultOn.toString())
    }

    /** The default value of [key], or null when it has none. */
    public fun defaultOf(key: String): String? = defaults[key]

    /** Every key that has a default. */
    public val keysWithDefaults: Set<String> get() = defaults.keys
}

/**
 * The only settings store: typed access to table `setting`, with defaults from [SettingKeys].
 * Values are text; booleans are `true`/`false`, numbers are decimal. Setting a value to null removes
 * the row so the key reads as its default again.
 */
public class SettingsStore internal constructor(private val db: SelfWatchDb) {

    /** The stored value of [key], else its default, else null. */
    public suspend fun get(key: String): String? = db.read { read(it, key) } ?: SettingKeys.defaultOf(key)

    /** [get] as an Int, or null when absent or not a number. */
    public suspend fun getInt(key: String): Int? = get(key)?.toIntOrNull()

    /** [get] as a Long, or null when absent or not a number. */
    public suspend fun getLong(key: String): Long? = get(key)?.toLongOrNull()

    /** [get] as a Boolean; absent or unreadable values give [fallback]. */
    public suspend fun getBool(key: String, fallback: Boolean = false): Boolean =
        get(key)?.toBooleanStrictOrNull() ?: fallback

    /** [get] split on commas, blanks dropped. */
    public suspend fun getList(key: String): List<String> =
        get(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** Stores [value] for [key]; null removes the row (back to the default). */
    public suspend fun set(key: String, value: String?) {
        db.write(setOf(Table.SETTING)) { put(it, key, value) }
    }

    /** Stores an Int (null removes). */
    public suspend fun setInt(key: String, value: Int?): Unit = set(key, value?.toString())

    /** Stores a Long (null removes). */
    public suspend fun setLong(key: String, value: Long?): Unit = set(key, value?.toString())

    /** Stores a Boolean. */
    public suspend fun setBool(key: String, value: Boolean): Unit = set(key, value.toString())

    /** Stores a comma list. */
    public suspend fun setList(key: String, values: List<String>): Unit =
        set(key, values.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","))

    /** Stores several values in one transaction (null values remove their rows). */
    public suspend fun setAll(values: Map<String, String?>) {
        db.write(setOf(Table.SETTING)) { d -> values.forEach { (k, v) -> put(d, k, v) } }
    }

    /** Every stored row (defaults not included), for backup. */
    public suspend fun all(): Map<String, String> = db.read { d ->
        d.queryList("SELECT key, value FROM setting ORDER BY key", null) { it.getString(0) to it.getString(1) }.toMap()
    }

    /**
     * The value of [key] (as [get] gives it) now and after every change in any process. Repeats are
     * dropped.
     */
    public fun observe(key: String): Flow<String?> =
        db.changes.observe(setOf(Table.SETTING)).map { get(key) }.distinctUntilChanged()

    /** Whether the firewall allows [feature]. */
    public suspend fun netAllowed(feature: NetFeature): Boolean = getBool(SettingKeys.net(feature), feature.defaultOn)

    /** Allows or blocks [feature]. */
    public suspend fun setNetAllowed(feature: NetFeature, allowed: Boolean): Unit =
        setBool(SettingKeys.net(feature), allowed)

    /** The profile from the `profile.*` and `units` keys. */
    public suspend fun profile(): Profile = Profile(
        heightCm = getInt(SettingKeys.PROFILE_HEIGHT_CM),
        birthYear = getInt(SettingKeys.PROFILE_BIRTH_YEAR),
        sex = when (get(SettingKeys.PROFILE_SEX)) {
            SEX_FEMALE -> Sex.FEMALE
            SEX_MALE -> Sex.MALE
            else -> Sex.UNSPECIFIED
        },
        units = when (get(SettingKeys.UNITS)) {
            UNITS_METRIC -> UnitSystem.METRIC
            UNITS_IMPERIAL -> UnitSystem.IMPERIAL
            else -> UnitSystem.FOLLOW_PHONE
        },
    )

    /** Saves [p] in one transaction. */
    public suspend fun setProfile(p: Profile): Unit = setAll(
        mapOf(
            SettingKeys.PROFILE_HEIGHT_CM to p.heightCm?.toString(),
            SettingKeys.PROFILE_BIRTH_YEAR to p.birthYear?.toString(),
            SettingKeys.PROFILE_SEX to when (p.sex) {
                Sex.FEMALE -> SEX_FEMALE
                Sex.MALE -> SEX_MALE
                Sex.UNSPECIFIED -> null
            },
            SettingKeys.UNITS to when (p.units) {
                UnitSystem.METRIC -> UNITS_METRIC
                UnitSystem.IMPERIAL -> UNITS_IMPERIAL
                UnitSystem.FOLLOW_PHONE -> UNITS_PHONE
            },
        ),
    )

    private fun read(d: SQLiteDatabase, key: String): String? =
        d.queryOne("SELECT value FROM setting WHERE key = ?", arrayOf(key)) { it.getString(0) }

    private fun put(d: SQLiteDatabase, key: String, value: String?) {
        if (value == null) {
            d.delete("setting", "key = ?", arrayOf(key))
        } else {
            d.withStatement("INSERT OR REPLACE INTO setting (key, value) VALUES (?, ?)") { st ->
                st.bindString(1, key)
                st.bindString(2, value)
                st.executeInsert()
            }
        }
    }

    private companion object {
        const val SEX_FEMALE = "female"
        const val SEX_MALE = "male"
        const val UNITS_METRIC = "metric"
        const val UNITS_IMPERIAL = "imperial"
        const val UNITS_PHONE = "phone"
    }
}
