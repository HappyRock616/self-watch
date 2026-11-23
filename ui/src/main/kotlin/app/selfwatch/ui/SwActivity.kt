/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import app.selfwatch.ui.components.InsetsPadding

/** Edge-to-edge setup shared by every activity (required from API 35, enforced from API 36). */
object EdgeToEdge {
    /**
     * Draws behind the system bars, with bar icons that match the activity's own light or dark palette
     * (which can differ from the phone's when the user picks dark or light in Appearance).
     */
    fun enable(activity: ComponentActivity) {
        val night = SwTheme.isNight(activity)
        val style = if (night) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

/**
 * Base activity: edge-to-edge on, color theme overlay applied. Subclasses call [setRootContent] (or call
 * [InsetsPadding.apply] on their own root) so content clears the system bars.
 *
 * The overlay is applied at creation, so when the theme changes (see [SwTheme.setCurrent]) a started
 * activity recreates itself at once and a stopped one when it comes back.
 */
abstract class SwActivity : AppCompatActivity() {
    /** The color theme for this activity: the app's ([SwTheme.current]) unless overridden. */
    protected open val colorTheme: SwTheme.Theme get() = SwTheme.current

    private var appliedTheme: SwTheme.Theme = SwTheme.DEFAULT_THEME

    private val themeListener: (SwTheme.Theme) -> Unit = { recreateIfThemeChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedTheme = colorTheme
        SwTheme.applyTheme(this, appliedTheme)
        EdgeToEdge.enable(this)
    }

    override fun onStart() {
        super.onStart()
        SwTheme.addListener(themeListener)
        recreateIfThemeChanged()
    }

    override fun onStop() {
        SwTheme.removeListener(themeListener)
        super.onStop()
    }

    /** Recreates the activity when [colorTheme] differs from the theme it was created with. */
    protected fun recreateIfThemeChanged() {
        if (colorTheme != appliedTheme && !isFinishing) recreate()
    }

    /** Sets [root] as the content view and pads it for system bars, cut-outs and the keyboard. */
    protected fun setRootContent(root: View) {
        setContentView(root)
        InsetsPadding.apply(root, top = true, bottom = true, ime = true)
    }
}
