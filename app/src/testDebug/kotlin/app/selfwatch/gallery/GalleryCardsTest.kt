/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.gallery

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import app.selfwatch.ui.SwTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import app.selfwatch.ui.R as UiR

/**
 * The gallery's tab cards ([CardSamples]) drawn in every color theme, dark and light, and at 1.5× and
 * 2.0× font: every card fits, and a picture of each combination is written to
 * `app/build/reports/card-gallery/` for a look by eye.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryCardsTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    private fun themed(theme: SwTheme.Theme, night: Boolean, fontScale: Float): Context {
        val cfg = Configuration(app.resources.configuration).apply {
            this.fontScale = fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        return ContextThemeWrapper(app, UiR.style.Theme_SelfWatch).also {
            it.applyOverrideConfiguration(cfg)
            SwTheme.applyTheme(it, theme)
        }
    }

    private fun page(ctx: Context): LinearLayout {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
            setBackgroundColor(tv.data)
        }
        val host = CardSamples.Host(ctx)
        val gap = (16 * ctx.resources.displayMetrics.density).toInt()
        val samples = CardSamples.all()
        for (pair in samples.chunked(2)) {
            val (a, aData) = pair[0]
            val b = pair.getOrNull(1)
            val row = CardSamples.pair(ctx, CardSamples.small(a, aData, host, column), b?.let { (c, d) -> CardSamples.small(c, d, host, column) }, gap)
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        }
        for ((card, data) in samples) {
            column.addView(
                CardSamples.large(card, data, host, column),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = gap },
            )
        }
        return column
    }

    private fun draw(name: String, v: View, width: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, width, v.measuredHeight)
        assertTrue("$name has a height", v.measuredHeight > 0)
        val bmp = Bitmap.createBitmap(width, v.measuredHeight, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/reports/card-gallery").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun everyThemeDarkAndLight() {
        for (theme in SwTheme.Theme.entries) {
            for (night in listOf(true, false)) {
                draw("${theme.name.lowercase()}-${if (night) "dark" else "light"}-1.0", page(themed(theme, night, 1f)), WIDTH)
            }
        }
    }

    @Test
    fun largeFonts() {
        for (scale in listOf(1.5f, 2f)) {
            draw("turquoise-dark-$scale", page(themed(SwTheme.Theme.TURQUOISE, true, scale)), WIDTH)
            draw("forest-light-$scale", page(themed(SwTheme.Theme.FOREST, false, scale)), WIDTH)
        }
    }

    private companion object {
        const val WIDTH = 1233
    }
}
