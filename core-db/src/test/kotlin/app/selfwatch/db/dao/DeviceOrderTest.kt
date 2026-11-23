/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db.dao

import app.selfwatch.db.DbTest
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import org.junit.Assert.assertEquals
import org.junit.Test

/** Several devices: kinds round-trip, the user's order is stored, and new devices go last. */
class DeviceOrderTest : DbTest() {

    @Test
    fun sensorKindsRoundTrip() = test {
        val scale = device("AA:00:00:00:00:02", "Scale").copy(
            kind = DeviceKind.WEIGHT_SCALE,
            features = setOf(Feature.HEALTH_WEIGHT, Feature.HEALTH_BODY_COMPOSITION, Feature.BATTERY),
        )
        val id = db.devices.upsert(scale)
        val back = db.devices.get(id)!!
        assertEquals(DeviceKind.WEIGHT_SCALE, back.kind)
        assertEquals(scale.features, back.features)
        for (k in DeviceKind.entries) {
            val d = db.devices.upsert(device("BB:00:00:00:00:0${k.ordinal}", k.name).copy(kind = k))
            assertEquals(k, db.devices.get(d)!!.kind)
        }
    }

    @Test
    fun orderIsStoredAndNewDevicesGoLast() = test {
        val watch = db.devices.upsert(device("AA:00:00:00:00:01", "Watch"))
        val second = db.devices.upsert(device("AA:00:00:00:00:02", "Second watch"))
        val strap = db.devices.upsert(device("AA:00:00:00:00:03", "Strap").copy(kind = DeviceKind.HEART_RATE_SENSOR))
        assertEquals(listOf(watch, second, strap), db.devices.active().map { it.id })

        db.devices.setOrder(listOf(second, strap, watch))
        assertEquals(listOf(second, strap, watch), db.devices.active().map { it.id })

        val cuff = db.devices.upsert(device("AA:00:00:00:00:04", "Cuff").copy(kind = DeviceKind.BLOOD_PRESSURE_MONITOR))
        assertEquals(listOf(second, strap, watch, cuff), db.devices.active().map { it.id })

        // Re-pairing a device keeps its place.
        db.devices.upsert(device("AA:00:00:00:00:02", "Second watch"))
        assertEquals(second, db.devices.active().first().id)

        db.devices.setOrder(emptyList())
        assertEquals(listOf(second, strap, watch, cuff), db.devices.active().map { it.id })
    }
}
