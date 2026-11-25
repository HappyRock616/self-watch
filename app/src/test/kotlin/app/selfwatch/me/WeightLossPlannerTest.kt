/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import app.selfwatch.food.cards.BurnSource
import app.selfwatch.model.food.Profile
import app.selfwatch.model.food.Sex
import app.selfwatch.model.food.UnitSystem
import app.selfwatch.model.time.Day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The weight loss plan maths and the permission row rules (pure). */
class WeightLossPlannerTest {
    private val today = Day(20261123)
    private val man = Profile(180, 1980, Sex.MALE, UnitSystem.METRIC)

    @Test
    fun burnFromTheProfileOrTheDefault() {
        // Mifflin-St Jeor: 900 + 1,125 − 230 + 5 = 1,800 resting, × 1.2.
        assertEquals(2_160, WeightLossPlanner.estimatedBurn(90_00, man, today))
        assertEquals(1_920, WeightLossPlanner.estimatedBurn(null, man, today))
        assertEquals(1_920, WeightLossPlanner.estimatedBurn(90_00, Profile(null, null, Sex.UNSPECIFIED, UnitSystem.METRIC), today))
    }

    @Test
    fun aSafeDateGivesTheCalorieGoal() {
        // 4 kg in 10 weeks: 0.4 kg a week (under 1% of 90 kg) → 440 kcal a day less.
        val a = WeightLossPlanner.advise(90_00, 86_00, today, today.plusDays(70), 2_160, BurnSource.ESTIMATED, Sex.MALE)
        assertEquals(1_720, a.kcal)
        assertEquals(-40, a.ratePerWeekX100)
        assertEquals(-440, a.deltaKcal)
        assertFalse(a.tooSoon)
        assertFalse(a.floorApplied)
        assertNull(a.earliestSafe)
    }

    @Test
    fun tooSoonProposesTheEarliestSafeDate() {
        // 4 kg in 2 weeks needs 2 kg a week; capped at 0.9 kg, then raised to the 1,500 kcal minimum for men.
        val a = WeightLossPlanner.advise(90_00, 86_00, today, today.plusDays(14), 2_160, BurnSource.ESTIMATED, Sex.MALE)
        assertTrue(a.tooSoon)
        assertTrue(a.floorApplied)
        assertEquals(1_500, a.kcal)
        assertEquals(today.plusDays(47), a.earliestSafe)
        // That date is safe.
        val again = WeightLossPlanner.advise(90_00, 86_00, today, today.plusDays(47), 2_160, BurnSource.ESTIMATED, Sex.MALE)
        assertFalse(again.tooSoon)
    }

    @Test
    fun neverBelowTheSafeMinimum() {
        for ((sex, floor) in listOf(Sex.FEMALE to 1_200, Sex.MALE to 1_500, Sex.UNSPECIFIED to 1_350)) {
            val a = WeightLossPlanner.advise(70_00, 50_00, today, today.plusDays(7), 1_600, BurnSource.ESTIMATED, sex)
            assertTrue(sex.name, a.kcal >= floor)
            assertEquals(floor, a.floorKcal)
        }
        // A burn at the minimum cannot lose weight from food: said so, not "too soon".
        val stalled = WeightLossPlanner.advise(60_00, 55_00, today, today.plusDays(70), 1_200, BurnSource.ESTIMATED, Sex.FEMALE)
        assertTrue(stalled.stalled)
        assertFalse(stalled.tooSoon)
    }

    @Test
    fun gainIsCappedAtHalfAPercent() {
        val a = WeightLossPlanner.advise(60_00, 70_00, today, today.plusDays(14), 2_000, BurnSource.MEASURED, Sex.FEMALE)
        assertEquals(30, a.ratePerWeekX100)
        assertTrue(a.tooSoon)
    }

    @Test
    fun permissionRowsAskOrOpenTheRightPage() {
        fun of(kind: PermissionKind, granted: Boolean, asked: Boolean = false, rationale: Boolean = false) =
            PermissionAction.of(HeldPermission(kind, granted), asked, rationale)
        assertEquals(PermissionAction.REQUEST, of(PermissionKind.CAMERA, granted = false))
        assertEquals("denied once: Android still asks", PermissionAction.REQUEST, of(PermissionKind.CAMERA, false, asked = true, rationale = true))
        assertEquals("denied for good", PermissionAction.APP_DETAILS, of(PermissionKind.CAMERA, false, asked = true))
        assertEquals("granted: where it can be taken back", PermissionAction.APP_DETAILS, of(PermissionKind.CONTACTS, granted = true))
        assertEquals(PermissionAction.APP_NOTIFICATIONS, of(PermissionKind.NOTIFICATIONS, granted = true))
        assertEquals(PermissionAction.APP_NOTIFICATIONS, of(PermissionKind.NOTIFICATIONS, false, asked = true))
        assertEquals(PermissionAction.REQUEST, of(PermissionKind.NEARBY_DEVICES, granted = false))
        for (g in listOf(true, false)) {
            assertEquals(PermissionAction.NOTIFICATION_LISTENER, of(PermissionKind.NOTIFICATION_ACCESS, g))
            assertEquals(PermissionAction.BATTERY_OPTIMIZATION, of(PermissionKind.BATTERY_OPTIMIZATION, g))
        }
        assertEquals(PermissionAction.HEALTH_CONNECT_REQUEST, of(PermissionKind.HEALTH_CONNECT, false))
        assertEquals(PermissionAction.HEALTH_CONNECT_SETTINGS, of(PermissionKind.HEALTH_CONNECT, false, asked = true))
        assertEquals(PermissionAction.HEALTH_CONNECT_SETTINGS, of(PermissionKind.HEALTH_CONNECT, true))
        assertFalse(PermissionKind.NOTIFICATION_ACCESS.isRuntime)
        assertTrue(PermissionKind.CAMERA.isRuntime)
    }
}
