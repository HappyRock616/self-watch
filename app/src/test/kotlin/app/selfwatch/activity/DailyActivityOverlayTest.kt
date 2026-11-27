/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.activity

import org.robolectric.RuntimeEnvironment
import app.selfwatch.ui.charts.BarLayer
import app.selfwatch.ui.charts.LineLayer
import app.selfwatch.ui.format.Units
import app.selfwatch.model.time.Day
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class DailyActivityOverlayTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val m = OverlayMath.MISSING

    @Test
    fun layersInChipOrderWithDistanceInHundredthsOfAMile() {
        val o = DailyActivityOverlay(Day(20261124), intArrayOf(8000, m, 12000), intArrayOf(1609, m, 3219), intArrayOf(400, m, 650), intArrayOf(30, m, 55), Units.IMPERIAL)
        val layers = DailyActivityDetailFragment.layersOf(ctx, o, ZoneId.of("America/Detroit"))
        assertEquals(listOf(OverlayLayers.STEPS, OverlayLayers.DISTANCE, OverlayLayers.ACTIVE_KCAL, OverlayLayers.ACTIVE_MIN), layers.map { it.id })
        assertTrue(layers[0] is BarLayer)
        assertEquals(listOf(100, 200), (layers[1] as LineLayer).values.toList())
        assertTrue(o.hasData)
    }

    @Test
    fun noDataNoLayers() {
        val o = DailyActivityOverlay(Day(20261124), intArrayOf(m), intArrayOf(m), intArrayOf(m), intArrayOf(m), Units.METRIC)
        assertFalse(o.hasData)
        assertTrue(DailyActivityDetailFragment.layersOf(ctx, o, ZoneId.of("UTC")).isEmpty())
    }
}
