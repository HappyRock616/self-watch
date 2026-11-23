/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import androidx.annotation.ColorInt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG contrast maths on ARGB colour ints, with no Android types so it runs in JVM tests. Alpha is
 * ignored: pass opaque colours (blend translucent ones onto their background first).
 */
object SwContrast {
    /** Minimum contrast for normal-size text (WCAG AA). */
    const val TEXT_MIN: Double = 4.5

    /** Minimum contrast for graphics such as bars, lines and outlines (WCAG AA non-text). */
    const val GRAPHIC_MIN: Double = 3.0

    /** Near-black text used on light fills. */
    @ColorInt
    const val DARK_TEXT: Int = 0xFF0B0B0B.toInt()

    /** White text used on dark fills. */
    @ColorInt
    const val LIGHT_TEXT: Int = 0xFFFFFFFF.toInt()

    /** Relative luminance of [color], 0 (black) to 1 (white). */
    fun luminance(@ColorInt color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** Contrast ratio between two colours, 1 (same) to 21 (black on white); order does not matter. */
    fun ratio(@ColorInt a: Int, @ColorInt b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Text colour for a [fill]: [dark] or [light], whichever contrasts more with it. */
    @ColorInt
    fun textOn(@ColorInt fill: Int, @ColorInt dark: Int = DARK_TEXT, @ColorInt light: Int = LIGHT_TEXT): Int =
        if (ratio(dark, fill) >= ratio(light, fill)) dark else light

    /**
     * [fg] if it already reaches [min] contrast on [bg]; otherwise [fg] mixed towards black (on a light
     * [bg]) or white (on a dark one) just far enough to reach it, keeping its hue as much as possible.
     */
    @ColorInt
    fun ensure(@ColorInt fg: Int, @ColorInt bg: Int, min: Double = TEXT_MIN): Int {
        val opaque = fg or OPAQUE
        if (ratio(opaque, bg) >= min) return opaque
        val target = if (luminance(bg) > MID_LUMINANCE) DARK_TEXT else LIGHT_TEXT
        var step = 1
        while (step <= STEPS) {
            val mixed = mix(opaque, target, step.toDouble() / STEPS)
            if (ratio(mixed, bg) >= min) return mixed
            step++
        }
        return target
    }

    /** [a] mixed with [b]: 0 gives [a], 1 gives [b]. Opaque result. */
    @ColorInt
    fun mix(@ColorInt a: Int, @ColorInt b: Int, t: Double): Int {
        fun ch(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * t + 0.5).toInt().coerceIn(0, 255) shl shift
        }
        return OPAQUE or ch(16) or ch(8) or ch(0)
    }

    private const val OPAQUE: Int = 0xFF shl 24
    private const val STEPS: Int = 40

    // Luminance where black and white text contrast equally: (L + 0.05)² = 1.05 × 0.05.
    private const val MID_LUMINANCE: Double = 0.1791
}
