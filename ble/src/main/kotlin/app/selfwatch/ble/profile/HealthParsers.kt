/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.profile

import java.time.LocalDateTime

// Layouts from the Bluetooth SIG GATT Specification Supplement (public). Every parser returns null
// for a value that is too short for the fields its flags announce; extra trailing bytes are ignored.

/**
 * One Heart Rate Measurement (0x2A37).
 *
 * @property bpm heart rate in beats per minute.
 * @property contact true or false when the sensor reports skin contact, null when it can't tell.
 * @property energyKj energy expended since the last reset, in kilojoules, if sent.
 * @property rrMs beat-to-beat intervals in milliseconds, oldest first (may be empty).
 */
public data class HeartRateMeasurement(val bpm: Int, val contact: Boolean?, val energyKj: Int?, val rrMs: List<Int>) {
    /** Parser. */
    public companion object {
        private const val FORMAT_UINT16 = 0x01
        private const val CONTACT_DETECTED = 0x02
        private const val CONTACT_SUPPORTED = 0x04
        private const val ENERGY = 0x08
        private const val RR = 0x10

        /** Parses [value]; null when it is malformed. */
        public fun parse(value: ByteArray): HeartRateMeasurement? {
            val r = GattReader(value)
            val flags = r.u8() ?: return null
            val bpm = (if (flags and FORMAT_UINT16 != 0) r.u16() else r.u8()) ?: return null
            val contact = if (flags and CONTACT_SUPPORTED != 0) flags and CONTACT_DETECTED != 0 else null
            val energy = if (flags and ENERGY != 0) r.u16() ?: return null else null
            val rr = ArrayList<Int>()
            if (flags and RR != 0) {
                while (r.remaining >= 2) rr += GattValues.rrMs(r.u16() ?: break)
            }
            return HeartRateMeasurement(bpm, contact, energy, rr)
        }
    }
}

/**
 * One Weight Measurement (0x2A9D), converted to metric.
 *
 * @property weightKgX100 weight in kg ×100; null when the scale reports "measurement unsuccessful".
 * @property time the reading time on the scale's clock (local time), or null when not sent or unknown.
 * @property userId the scale's user index, or null when not sent or "unknown user" (255).
 * @property bmiX10 BMI ×10, if sent.
 * @property heightMm height in millimetres, if sent.
 */
public data class WeightMeasurement(
    val weightKgX100: Int?,
    val time: LocalDateTime?,
    val userId: Int?,
    val bmiX10: Int?,
    val heightMm: Int?,
) {
    /** Parser. */
    public companion object {
        private const val IMPERIAL = 0x01
        private const val TIME = 0x02
        private const val USER = 0x04
        private const val BMI_HEIGHT = 0x08
        private const val UNSUCCESSFUL = 0xFFFF

        /** Parses [value]; null when it is malformed. */
        public fun parse(value: ByteArray): WeightMeasurement? {
            val r = GattReader(value)
            val flags = r.u8() ?: return null
            val imperial = flags and IMPERIAL != 0
            val raw = r.u16() ?: return null
            val time = if (flags and TIME != 0) r.dateTime() ?: return null else null
            val user = if (flags and USER != 0) r.u8() ?: return null else null
            var bmi: Int? = null
            var height: Int? = null
            if (flags and BMI_HEIGHT != 0) {
                bmi = r.u16() ?: return null
                height = GattValues.heightMm(r.u16() ?: return null, imperial)
            }
            return WeightMeasurement(
                weightKgX100 = if (raw == UNSUCCESSFUL) null else GattValues.massKgX100(raw, imperial),
                time = time.known(),
                userId = user.knownUser(),
                bmiX10 = bmi,
                heightMm = height,
            )
        }
    }
}

/**
 * One Body Composition Measurement (0x2A9C), converted to metric. Masses are kg ×100.
 *
 * @property bodyFatX10 body fat in percent ×10; null when the scale reports "measurement unsuccessful".
 * @property basalKj basal metabolism in kilojoules, if sent.
 * @property musclePctX10 muscle in percent ×10, if sent.
 * @property impedanceX10 impedance in ohms ×10, if sent.
 * @property multiPacket true when the scale split this measurement over more than one value.
 */
public data class BodyCompositionMeasurement(
    val bodyFatX10: Int?,
    val time: LocalDateTime?,
    val userId: Int?,
    val basalKj: Int?,
    val musclePctX10: Int?,
    val muscleKgX100: Int?,
    val fatFreeKgX100: Int?,
    val softLeanKgX100: Int?,
    val waterKgX100: Int?,
    val impedanceX10: Int?,
    val weightKgX100: Int?,
    val heightMm: Int?,
    val multiPacket: Boolean,
) {
    /** Parser. */
    public companion object {
        private const val IMPERIAL = 0x0001
        private const val TIME = 0x0002
        private const val USER = 0x0004
        private const val BASAL = 0x0008
        private const val MUSCLE_PCT = 0x0010
        private const val MUSCLE_MASS = 0x0020
        private const val FAT_FREE = 0x0040
        private const val SOFT_LEAN = 0x0080
        private const val WATER = 0x0100
        private const val IMPEDANCE = 0x0200
        private const val WEIGHT = 0x0400
        private const val HEIGHT = 0x0800
        private const val MULTI_PACKET = 0x1000
        private const val UNSUCCESSFUL = 0xFFFF

        /** Parses [value]; null when it is malformed. */
        public fun parse(value: ByteArray): BodyCompositionMeasurement? {
            val r = GattReader(value)
            val flags = r.u16() ?: return null
            val imperial = flags and IMPERIAL != 0
            fun has(bit: Int) = flags and bit != 0
            val fat = r.u16() ?: return null
            val time = if (has(TIME)) r.dateTime() ?: return null else null
            val user = if (has(USER)) r.u8() ?: return null else null
            val basal = if (has(BASAL)) r.u16() ?: return null else null
            val musclePct = if (has(MUSCLE_PCT)) r.u16() ?: return null else null
            val muscle = if (has(MUSCLE_MASS)) r.u16() ?: return null else null
            val fatFree = if (has(FAT_FREE)) r.u16() ?: return null else null
            val softLean = if (has(SOFT_LEAN)) r.u16() ?: return null else null
            val water = if (has(WATER)) r.u16() ?: return null else null
            val impedance = if (has(IMPEDANCE)) r.u16() ?: return null else null
            val weight = if (has(WEIGHT)) r.u16() ?: return null else null
            val height = if (has(HEIGHT)) r.u16() ?: return null else null
            fun mass(v: Int?) = v?.let { GattValues.massKgX100(it, imperial) }
            return BodyCompositionMeasurement(
                bodyFatX10 = if (fat == UNSUCCESSFUL) null else fat,
                time = time.known(),
                userId = user.knownUser(),
                basalKj = basal,
                musclePctX10 = musclePct,
                muscleKgX100 = mass(muscle),
                fatFreeKgX100 = mass(fatFree),
                softLeanKgX100 = mass(softLean),
                waterKgX100 = mass(water),
                impedanceX10 = impedance,
                weightKgX100 = mass(weight),
                heightMm = height?.let { GattValues.heightMm(it, imperial) },
                multiPacket = has(MULTI_PACKET),
            )
        }
    }
}

/**
 * One Blood Pressure Measurement (0x2A35), converted to mmHg. A pressure is null when the cuff sent
 * a special value (not a number).
 *
 * @property pulseBpm pulse rate in beats per minute, if sent.
 * @property status the Measurement Status bits (body movement, cuff fit, irregular pulse...), if sent.
 */
public data class BloodPressureMeasurement(
    val systolicMmHg: Double?,
    val diastolicMmHg: Double?,
    val meanMmHg: Double?,
    val time: LocalDateTime?,
    val pulseBpm: Double?,
    val userId: Int?,
    val status: Int?,
) {
    /** Parser. */
    public companion object {
        private const val KPA = 0x01
        private const val TIME = 0x02
        private const val PULSE = 0x04
        private const val USER = 0x08
        private const val STATUS = 0x10

        /** Parses [value]; null when it is malformed. */
        public fun parse(value: ByteArray): BloodPressureMeasurement? {
            val r = GattReader(value)
            val flags = r.u8() ?: return null
            val kpa = flags and KPA != 0
            fun pressure(v: Double): Double? = v.takeIf { it.isFinite() }?.let { if (kpa) GattValues.kpaToMmHg(it) else it }
            val sys = r.sfloat() ?: return null
            val dia = r.sfloat() ?: return null
            val mean = r.sfloat() ?: return null
            val time = if (flags and TIME != 0) r.dateTime() ?: return null else null
            val pulse = if (flags and PULSE != 0) r.sfloat() ?: return null else null
            val user = if (flags and USER != 0) r.u8() ?: return null else null
            val status = if (flags and STATUS != 0) r.u16() ?: return null else null
            return BloodPressureMeasurement(
                systolicMmHg = pressure(sys),
                diastolicMmHg = pressure(dia),
                meanMmHg = pressure(mean),
                time = time.known(),
                pulseBpm = pulse?.takeIf { it.isFinite() },
                userId = user.knownUser(),
                status = status,
            )
        }
    }
}

private fun LocalDateTime?.known(): LocalDateTime? = this?.takeIf { it != GattValues.UNKNOWN_TIME }

private fun Int?.knownUser(): Int? = this?.takeIf { it != UNKNOWN_USER }

private const val UNKNOWN_USER = 0xFF
