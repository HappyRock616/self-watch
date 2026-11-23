/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.profile

import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import java.util.UUID

/**
 * The open Bluetooth SIG health profiles Self Watch supports, written from the public Bluetooth
 * specifications (Assigned Numbers for the 16-bit UUIDs; the GATT Specification Supplement for the
 * characteristic layouts). No vendor protocol is involved.
 */
public enum class HealthProfile(
    /** The 16-bit service UUID. */
    public val service16: Int,
    /** The 16-bit UUID of the measurement characteristic (notify or indicate). */
    public val measurement16: Int,
    /** What the app gains from it. */
    public val feature: Feature,
) {
    /** Weight Scale service, Weight Measurement characteristic (indicate). */
    WEIGHT_SCALE(0x181D, 0x2A9D, Feature.HEALTH_WEIGHT),

    /** Body Composition service, Body Composition Measurement characteristic (indicate). */
    BODY_COMPOSITION(0x181B, 0x2A9C, Feature.HEALTH_BODY_COMPOSITION),

    /** Heart Rate service, Heart Rate Measurement characteristic (notify). */
    HEART_RATE(0x180D, 0x2A37, Feature.HEALTH_HR),

    /** Blood Pressure service, Blood Pressure Measurement characteristic (indicate). */
    BLOOD_PRESSURE(0x1810, 0x2A35, Feature.HEALTH_BLOOD_PRESSURE),
    ;

    /** The service as a full UUID. */
    public val service: UUID get() = GattUuids.sig(service16)

    /** The measurement characteristic as a full UUID. */
    public val measurement: UUID get() = GattUuids.sig(measurement16)

    /** Lookups. */
    public companion object {
        /** The profiles whose service is among [services], in declaration order. */
        public fun present(services: Set<UUID>): List<HealthProfile> = entries.filter { it.service in services }

        /** The profiles a device of [kind] is paired for (its picker filter). */
        public fun forKind(kind: DeviceKind): List<HealthProfile> = when (kind) {
            DeviceKind.GARMIN_WATCH -> emptyList()
            DeviceKind.WEIGHT_SCALE -> listOf(WEIGHT_SCALE, BODY_COMPOSITION)
            DeviceKind.HEART_RATE_SENSOR -> listOf(HEART_RATE)
            DeviceKind.BLOOD_PRESSURE_MONITOR -> listOf(BLOOD_PRESSURE)
        }
    }
}

/** Bluetooth SIG UUIDs. */
public object GattUuids {
    /** Battery service (0x180F). */
    public val BATTERY_SERVICE: UUID = sig(0x180F)

    /** Battery Level characteristic (0x2A19): one byte, 0 to 100 percent. */
    public val BATTERY_LEVEL: UUID = sig(0x2A19)

    /** A 16-bit Bluetooth SIG UUID on the Bluetooth base UUID `0000xxxx-0000-1000-8000-00805f9b34fb`. */
    public fun sig(short: Int): UUID = UUID(((short.toLong() and 0xFFFF) shl 32) or 0x1000L, BASE_LOW)

    private const val BASE_LOW: Long = -0x7fffff7fa064cb05L // 8000-00805f9b34fb
}
