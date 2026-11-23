/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.UUID
import kotlin.random.Random

/**
 * The GATT characteristic parsers, fed byte vectors built field by field from the Bluetooth SIG GATT
 * Specification Supplement: flags, kg and lb units, time stamps, optional fields, special values and
 * truncated or random input.
 */
class HealthParsersTest {

    /** Little-endian builder for test vectors. */
    private class V {
        private val out = ArrayList<Byte>()
        fun u8(v: Int) = apply { out += v.toByte() }
        fun u16(v: Int) = apply { u8(v and 0xFF); u8(v shr 8 and 0xFF) }
        fun sfloat(mantissa: Int, exponent: Int) = u16(((exponent and 0x0F) shl 12) or (mantissa and 0x0FFF))
        fun time(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int) = u16(y).u8(mo).u8(d).u8(h).u8(mi).u8(s)
        fun bytes(): ByteArray = out.toByteArray()
    }

    // ---- UUIDs and SFLOAT ---------------------------------------------------------------------------

    @Test
    fun sigUuidsUseTheBluetoothBaseUuid() {
        assertEquals(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"), HealthProfile.HEART_RATE.service)
        assertEquals(UUID.fromString("00002a9d-0000-1000-8000-00805f9b34fb"), HealthProfile.WEIGHT_SCALE.measurement)
        assertEquals(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"), GattUuids.BATTERY_SERVICE)
        assertEquals(UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"), GattUuids.BATTERY_LEVEL)
        assertEquals(
            listOf(HealthProfile.WEIGHT_SCALE, HealthProfile.BLOOD_PRESSURE),
            HealthProfile.present(setOf(GattUuids.sig(0x1810), GattUuids.sig(0x181D), GattUuids.BATTERY_SERVICE)),
        )
    }

    @Test
    fun sfloatValuesAndSpecials() {
        assertEquals(120.0, GattValues.sfloat(0x0078), 1e-9) // mantissa 120, exponent 0
        assertEquals(12.5, GattValues.sfloat(0xF07D), 1e-9) // mantissa 125, exponent -1
        assertEquals(-5.0, GattValues.sfloat(0x0FFB), 1e-9) // mantissa -5
        assertEquals(1200.0, GattValues.sfloat(0x1078), 1e-9) // exponent +1
        for (special in listOf(0x07FF, 0x0800, 0x07FE, 0x0802, 0x0801)) assertTrue(GattValues.sfloat(special).isNaN())
    }

    // ---- Heart Rate Measurement ---------------------------------------------------------------------

    @Test
    fun heartRateUint8WithoutContactSupport() {
        val m = HeartRateMeasurement.parse(V().u8(0x00).u8(72).bytes())!!
        assertEquals(72, m.bpm)
        assertNull(m.contact)
        assertNull(m.energyKj)
        assertTrue(m.rrMs.isEmpty())
    }

    @Test
    fun heartRateUint16ContactEnergyAndRrIntervals() {
        // flags: uint16 format | contact supported+detected | energy | RR
        val flags = 0x01 or 0x06 or 0x08 or 0x10
        val m = HeartRateMeasurement.parse(V().u8(flags).u16(301).u16(1234).u16(1024).u16(512).bytes())!!
        assertEquals(301, m.bpm)
        assertEquals(true, m.contact)
        assertEquals(1234, m.energyKj)
        assertEquals(listOf(1000, 500), m.rrMs)
    }

    @Test
    fun heartRateContactSupportedButNotDetected() {
        assertEquals(false, HeartRateMeasurement.parse(V().u8(0x04).u8(0).bytes())!!.contact)
    }

    @Test
    fun heartRateTruncated() {
        assertNull(HeartRateMeasurement.parse(ByteArray(0)))
        assertNull(HeartRateMeasurement.parse(V().u8(0x01).u8(70).bytes())) // uint16 announced, one byte
        assertNull(HeartRateMeasurement.parse(V().u8(0x08).u8(70).u8(1).bytes())) // energy cut short
        // An odd trailing RR byte is ignored.
        assertEquals(listOf(1000), HeartRateMeasurement.parse(V().u8(0x10).u8(60).u16(1024).u8(9).bytes())!!.rrMs)
    }

    // ---- Weight Measurement -------------------------------------------------------------------------

    @Test
    fun weightInKilograms() {
        // 75.40 kg = 15080 × 0.005 kg
        val m = WeightMeasurement.parse(V().u8(0x00).u16(15080).bytes())!!
        assertEquals(7540, m.weightKgX100)
        assertNull(m.time)
        assertNull(m.userId)
        assertNull(m.bmiX10)
    }

    @Test
    fun weightInPoundsWithTimeUserBmiAndHeight() {
        // flags: imperial | time | user | BMI+height. 165.00 lb = 16500 × 0.01 lb = 74.84 kg; height 70.0 in.
        val v = V().u8(0x0F).u16(16500).time(2026, 11, 23, 7, 30, 5).u8(2).u16(237).u16(700)
        val m = WeightMeasurement.parse(v.bytes())!!
        assertEquals(7484, m.weightKgX100)
        assertEquals(LocalDateTime.of(2026, 11, 23, 7, 30, 5), m.time)
        assertEquals(2, m.userId)
        assertEquals(237, m.bmiX10)
        assertEquals(1778, m.heightMm)
    }

    @Test
    fun weightSiHeightUnknownUserUnknownTimeAndUnsuccessful() {
        val v = V().u8(0x0E).u16(14000).time(0, 0, 0, 0, 0, 0).u8(0xFF).u16(250).u16(1805)
        val m = WeightMeasurement.parse(v.bytes())!!
        assertEquals(7000, m.weightKgX100)
        assertNull(m.time)
        assertNull(m.userId)
        assertEquals(1805, m.heightMm)
        assertNull(WeightMeasurement.parse(V().u8(0).u16(0xFFFF).bytes())!!.weightKgX100)
        // Month 13 is not a date: treated as unknown.
        assertNull(WeightMeasurement.parse(V().u8(0x02).u16(1).time(2026, 13, 1, 0, 0, 0).bytes())!!.time)
    }

    @Test
    fun weightTruncated() {
        assertNull(WeightMeasurement.parse(V().u8(0).u8(1).bytes()))
        assertNull(WeightMeasurement.parse(V().u8(0x02).u16(15000).u16(2026).bytes())) // time cut short
        assertNull(WeightMeasurement.parse(V().u8(0x08).u16(15000).u16(240).bytes())) // height missing
    }

    // ---- Body Composition Measurement ---------------------------------------------------------------

    @Test
    fun bodyCompositionMinimal() {
        val m = BodyCompositionMeasurement.parse(V().u16(0).u16(215).bytes())!!
        assertEquals(215, m.bodyFatX10)
        assertNull(m.muscleKgX100)
        assertFalse(m.multiPacket)
    }

    @Test
    fun bodyCompositionEveryFieldInKilograms() {
        val flags = 0x0002 or 0x0004 or 0x0008 or 0x0010 or 0x0020 or 0x0040 or 0x0080 or 0x0100 or 0x0200 or 0x0400 or 0x0800
        val v = V().u16(flags).u16(184) // 18.4 %
            .time(2026, 1, 2, 3, 4, 5).u8(1)
            .u16(7000) // basal 7000 kJ
            .u16(412) // muscle 41.2 %
            .u16(6000) // muscle 30.00 kg
            .u16(12000) // fat free 60.00 kg
            .u16(11000) // soft lean 55.00 kg
            .u16(8400) // water 42.00 kg
            .u16(5005) // impedance 500.5 ohm
            .u16(14800) // weight 74.00 kg
            .u16(1780) // height 1.780 m
        val m = BodyCompositionMeasurement.parse(v.bytes())!!
        assertEquals(184, m.bodyFatX10)
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), m.time)
        assertEquals(1, m.userId)
        assertEquals(7000, m.basalKj)
        assertEquals(412, m.musclePctX10)
        assertEquals(3000, m.muscleKgX100)
        assertEquals(6000, m.fatFreeKgX100)
        assertEquals(5500, m.softLeanKgX100)
        assertEquals(4200, m.waterKgX100)
        assertEquals(5005, m.impedanceX10)
        assertEquals(7400, m.weightKgX100)
        assertEquals(1780, m.heightMm)
    }

    @Test
    fun bodyCompositionPoundsMultiPacketAndUnsuccessful() {
        val v = V().u16(0x0001 or 0x0020 or 0x0400 or 0x1000).u16(200).u16(6614).u16(16500)
        val m = BodyCompositionMeasurement.parse(v.bytes())!!
        assertEquals(3000, m.muscleKgX100) // 66.14 lb
        assertEquals(7484, m.weightKgX100) // 165.00 lb
        assertTrue(m.multiPacket)
        assertNull(BodyCompositionMeasurement.parse(V().u16(0).u16(0xFFFF).bytes())!!.bodyFatX10)
        assertNull(BodyCompositionMeasurement.parse(V().u16(0x0400).u16(200).u8(1).bytes())) // weight cut short
        assertNull(BodyCompositionMeasurement.parse(V().u8(0).bytes()))
    }

    // ---- Blood Pressure Measurement -----------------------------------------------------------------

    @Test
    fun bloodPressureInMmHg() {
        val m = BloodPressureMeasurement.parse(V().u8(0).sfloat(121, 0).sfloat(79, 0).sfloat(93, 0).bytes())!!
        assertEquals(121.0, m.systolicMmHg!!, 1e-9)
        assertEquals(79.0, m.diastolicMmHg!!, 1e-9)
        assertEquals(93.0, m.meanMmHg!!, 1e-9)
        assertNull(m.time)
        assertNull(m.pulseBpm)
        assertNull(m.status)
    }

    @Test
    fun bloodPressureInKpaWithTimePulseUserAndStatus() {
        // 16.0 kPa = 120.0 mmHg; 10.7 kPa = 80.3 mmHg.
        val v = V().u8(0x1F).sfloat(160, -1).sfloat(107, -1).sfloat(124, -1)
            .time(2026, 11, 22, 21, 15, 0).sfloat(64, 0).u8(3).u16(0x0004)
        val m = BloodPressureMeasurement.parse(v.bytes())!!
        assertEquals(120.0, m.systolicMmHg!!, 0.01)
        assertEquals(80.26, m.diastolicMmHg!!, 0.01)
        assertEquals(LocalDateTime.of(2026, 11, 22, 21, 15, 0), m.time)
        assertEquals(64.0, m.pulseBpm!!, 1e-9)
        assertEquals(3, m.userId)
        assertEquals(0x0004, m.status)
    }

    @Test
    fun bloodPressureSpecialValuesAndTruncation() {
        val m = BloodPressureMeasurement.parse(V().u8(0x04).sfloat(120, 0).sfloat(80, 0).u16(0x07FF).u16(0x07FF).bytes())!!
        assertNull(m.meanMmHg)
        assertNull(m.pulseBpm)
        assertNull(BloodPressureMeasurement.parse(V().u8(0).sfloat(120, 0).sfloat(80, 0).bytes()))
        assertNull(BloodPressureMeasurement.parse(V().u8(0x10).sfloat(1, 0).sfloat(1, 0).sfloat(1, 0).u8(0).bytes()))
    }

    // ---- Never crash ----------------------------------------------------------------------------------

    @Test
    fun randomInputNeverThrows() {
        val rnd = Random(20261123)
        repeat(20_000) {
            val b = ByteArray(rnd.nextInt(0, 40)).also { rnd.nextBytes(it) }
            HeartRateMeasurement.parse(b)
            WeightMeasurement.parse(b)
            BodyCompositionMeasurement.parse(b)
            BloodPressureMeasurement.parse(b)
        }
    }
}
