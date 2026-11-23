/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.annotation.StyleRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatDelegate
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Appearance choices: night mode and color theme.
 *
 * The neutral tokens have a light set (`values/`) and a dark set (`values-night/`), so the look follows
 * the night mode. Dark is the app's default look: call [applyNightMode] with [NightMode.DARK] at start
 * unless the user chose otherwise. The color theme is a theme overlay applied per activity with
 * [applyTheme]; [SwActivity] does that and recreates itself when [current] changes.
 */
object SwTheme {
    /** The Appearance setting. */
    enum class NightMode(internal val delegateMode: Int) {
        /** Default look. */
        DARK(AppCompatDelegate.MODE_NIGHT_YES),
        LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
        SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    }

    /**
     * The color themes, the same five as the Just Go 5K watch app (same order, same colors). People
     * pick one theme; it colors every screen and every chart. Orange is the default.
     *
     * @property primary the main color (Just Go 5K's run color), exact value used in the dark look.
     * @property secondary the complementary color (Just Go 5K's walk color).
     * @property onPrimary Just Go 5K's text color on [primary] (used where it reaches 4.5:1).
     * @property onSecondary Just Go 5K's text color on [secondary].
     * @property overlay the theme overlay that sets the Material color roles.
     */
    enum class Theme(
        @field:ColorInt @get:ColorInt val primary: Int,
        @field:ColorInt @get:ColorInt val secondary: Int,
        @field:ColorInt @get:ColorInt val onPrimary: Int,
        @field:ColorInt @get:ColorInt val onSecondary: Int,
        @field:StyleRes @get:StyleRes val overlay: Int,
    ) {
        TURQUOISE(0xFF19D3C5.toInt(), 0xFFFFD84D.toInt(), 0xFF04342C.toInt(), 0xFF412402.toInt(), R.style.ThemeOverlay_SelfWatch_Theme_Turquoise),
        PURPLE(0xFFBD93F9.toInt(), 0xFF7CC8FF.toInt(), 0xFF26215C.toInt(), 0xFF042C53.toInt(), R.style.ThemeOverlay_SelfWatch_Theme_Purple),
        ORANGE(0xFFFF8A1F.toInt(), 0xFF4DABF7.toInt(), 0xFF412402.toInt(), 0xFF042C53.toInt(), R.style.ThemeOverlay_SelfWatch_Theme_Orange),
        YELLOW(0xFFF5B841.toInt(), 0xFF2E9E5B.toInt(), 0xFF412402.toInt(), 0xFFE8F5EC.toInt(), R.style.ThemeOverlay_SelfWatch_Theme_Yellow),
        FOREST(0xFF2E9E5B.toInt(), 0xFFF5B841.toInt(), 0xFFE8F5EC.toInt(), 0xFF412402.toInt(), R.style.ThemeOverlay_SelfWatch_Theme_Forest),
    }

    /** The default night mode for a fresh install. */
    val DEFAULT_NIGHT_MODE: NightMode = NightMode.DARK

    /** The default color theme. */
    val DEFAULT_THEME: Theme = Theme.ORANGE

    private val listeners = CopyOnWriteArraySet<(Theme) -> Unit>()

    /** The app's color theme. Set it with [setCurrent]; [SwActivity] follows it. */
    @Volatile
    var current: Theme = DEFAULT_THEME
        private set

    /** Changes [current] and tells every started [SwActivity] (call on the main thread). */
    @MainThread
    fun setCurrent(theme: Theme) {
        if (theme == current) return
        current = theme
        for (l in listeners) l(theme)
    }

    /** Called with the new theme whenever [current] changes. */
    fun addListener(listener: (Theme) -> Unit) {
        listeners += listener
    }

    /** Stops calling [listener]. */
    fun removeListener(listener: (Theme) -> Unit) {
        listeners -= listener
    }

    /**
     * Forgets every listener and sets [current] to [theme] without telling anyone. For tests, whose
     * activities are never stopped and would otherwise stay registered across tests.
     */
    @VisibleForTesting
    fun resetForTests(theme: Theme = DEFAULT_THEME) {
        listeners.clear()
        current = theme
    }

    /** Sets the app-wide night mode. Activities recreate themselves when it changes. */
    fun applyNightMode(mode: NightMode) {
        AppCompatDelegate.setDefaultNightMode(mode.delegateMode)
    }

    /**
     * Applies [theme] to [context]'s theme. Call in `onCreate` before inflating views (after
     * `super.onCreate`), or on a `ContextThemeWrapper`.
     */
    fun applyTheme(context: Context, theme: Theme) {
        context.theme.applyStyle(theme.overlay, true)
    }

    /** The color theme [context] renders with (from its overlay); [DEFAULT_THEME] without one. */
    fun themeOf(context: Context): Theme {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(R.attr.swThemeId, tv, true)) return DEFAULT_THEME
        return Theme.entries.getOrNull(tv.data) ?: DEFAULT_THEME
    }

    /** True when [context] currently renders with the dark palette. */
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
