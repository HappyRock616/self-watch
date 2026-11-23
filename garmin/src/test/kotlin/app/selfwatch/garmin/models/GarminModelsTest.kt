/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.models

import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.result.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminModelsTest {
    private val models = (GarminModels.load() as Outcome.Ok).value

    @Test
    fun bundledTableLoads() {
        assertTrue(models.models.size >= 100)
        assertTrue(models.models.all { it.name.isNotBlank() && it.family.isNotBlank() })
        assertEquals("only the vívoactive 5 is verified", 1, models.models.count { it.tier == SupportTier.VERIFIED })
    }

    @Test
    fun vivoactive5Entry() {
        val va5 = models.match("vívoactive 5", null)!!
        assertEquals("vívoactive 5", va5.name)
        assertEquals(SupportTier.VERIFIED, va5.tier)
        assertEquals("vivoactive", va5.family)
        assertEquals("V2", va5.link)
        assertTrue(4426 in va5.productIds)
        assertEquals(false, va5.overrides["trainingLoad"])
        assertEquals(false, va5.overrides["vO2MultiSport"])
        assertEquals(va5, models.match("something else", 4426))
    }

    @Test
    fun matchesByNamePattern() {
        assertEquals("vívoactive 3", models.match("vívoactive3", null)?.name)
        assertEquals("forerunner", models.match("Garmin Forerunner 265S", null)?.family)
        assertNull(models.match("Some Phone", null))
        assertNull(models.match(null, null))
    }

    @Test
    fun badInputIsRejectedOrSkipped() {
        assertTrue(GarminModels.parse("{") is Outcome.Fail)
        assertTrue(GarminModels.parse("""{"version":2,"models":[]}""") is Outcome.Fail)
        val m = (GarminModels.parse("""{"version":1,"models":[{"name":"x"},{"name":"y","pattern":"^y$","tier":"EXPECTED"}]}""") as Outcome.Ok).value
        assertEquals(listOf("y"), m.models.map { it.name })
        assertEquals(SupportTier.EXPECTED, m.models[0].tier)
    }
}
