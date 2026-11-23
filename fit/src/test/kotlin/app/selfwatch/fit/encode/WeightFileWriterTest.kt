/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.encode

import app.selfwatch.fit.Synth
import app.selfwatch.fit.decode.FitFileType
import app.selfwatch.fit.decode.WeightDecoder
import app.selfwatch.fit.readAll
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.result.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WeightFileWriterTest {
    private val t = Synth.T0 + 7 * 3600

    private fun m(tSec: Long, kind: BodyKind, x100: Int) = BodyMeasure(0, tSec, kind, null, x100, DataSource.MANUAL, null, null)

    @Test
    fun roundTrip() {
        val entries = listOf(
            m(t, BodyKind.WEIGHT, 8245), m(t, BodyKind.BODY_FAT, 2210), m(t, BodyKind.BODY_WATER, 5530),
            m(t, BodyKind.BONE_MASS, 320), m(t, BodyKind.MUSCLE_MASS, 3510), m(t, BodyKind.BMI, 2450),
            m(t, BodyKind.WAIST, 9000), // not in the FIT weight message: skipped
            m(t + 86_400, BodyKind.WEIGHT, 8190),
        )
        val out = ByteArrayOutputStream()
        WeightFileWriter.write(out, entries, t + 90_000)
        val bytes = out.toByteArray()

        val (r, recs) = readAll(bytes)
        val s = r.valueOrNull()!!
        assertTrue(s.crcOk)
        assertEquals(FitFileType.WEIGHT, s.fileType)
        assertEquals(255, s.manufacturer)
        assertEquals(listOf(0, 23, 30, 30), recs.map { it.global })

        val back = WeightDecoder.decode(ByteArrayInputStream(bytes)).valueOrNull()!!
        val expected = entries.filter { it.kind != BodyKind.WAIST }.map { it.copy(source = DataSource.IMPORT) }
        assertEquals(expected.sortedBy { it.tSec * 100 + it.kind.code }, back.sortedBy { it.tSec * 100 + it.kind.code })
    }

    @Test
    fun emptyAndOnlyUnsupportedKindsGiveAValidFileWithNoMeasures() {
        val out = ByteArrayOutputStream()
        WeightFileWriter.write(out, listOf(m(t, BodyKind.NECK, 4000)), t)
        val (r, recs) = readAll(out.toByteArray())
        assertTrue(r.valueOrNull()!!.crcOk)
        assertEquals(listOf(0, 23), recs.map { it.global })
        assertTrue(WeightDecoder.decode(ByteArrayInputStream(out.toByteArray())).valueOrNull()!!.isEmpty())
    }
}
