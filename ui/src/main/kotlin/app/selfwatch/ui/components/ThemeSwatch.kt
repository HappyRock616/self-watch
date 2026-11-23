/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.annotation.ColorInt
import app.selfwatch.ui.SwTheme
import app.selfwatch.ui.ThemeColors

/**
 * A color theme's swatch pair: two overlapping circles, primary then secondary, in the colors the theme
 * has in [context]'s look (dark or light). Used by the color theme picker.
 */
object ThemeSwatch {
    /** The swatch pair as a drawable, sized for a line of body text. */
    fun drawable(context: Context, theme: SwTheme.Theme): Drawable {
        val c = ThemeColors.of(theme, SwTheme.isNight(context))
        val d = PairDrawable(c.primary, c.secondary, c.neutrals.surface)
        val h = context.dp(SIZE_DP).toInt()
        d.setBounds(0, 0, (h * WIDTH_RATIO).toInt(), h)
        return d
    }

    /** "[swatch]  [name]": a list label with the swatch pair in front (TalkBack reads only [name]). */
    fun label(context: Context, theme: SwTheme.Theme, name: CharSequence): CharSequence {
        val b = SpannableStringBuilder(" ")
        b.setSpan(ImageSpan(drawable(context, theme), ImageSpan.ALIGN_BASELINE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.append("  ").append(name)
        return b
    }

    private const val SIZE_DP = 18f
    private const val WIDTH_RATIO = 1.6f

    /** Two circles; the second overlaps the first with a thin ring of [gap] between them. */
    private class PairDrawable(
        @ColorInt private val first: Int,
        @ColorInt private val second: Int,
        @ColorInt private val gap: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            val r = b.height() / 2f
            val cy = b.exactCenterY()
            paint.color = first
            canvas.drawCircle(b.left + r, cy, r, paint)
            paint.color = gap
            canvas.drawCircle(b.right - r, cy, r * RING, paint)
            paint.color = second
            canvas.drawCircle(b.right - r, cy, r, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private companion object {
            const val RING = 1.18f
        }
    }
}
