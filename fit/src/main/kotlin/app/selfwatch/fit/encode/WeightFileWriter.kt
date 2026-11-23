/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.encode

import app.selfwatch.fit.decode.FitFileType
import app.selfwatch.fit.decode.FitMsg
import app.selfwatch.fit.io.FieldDef
import app.selfwatch.fit.io.FitBaseType
import app.selfwatch.fit.io.FitTime
import app.selfwatch.fit.io.FitWriter
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import java.io.OutputStream

/**
 * Writes a FIT weight file (file type 9) for Garmin Connect's import: one `file_id`, one `device_info`
 * and one `weight_scale` message per distinct time. Weight, body fat, body water, bone mass, muscle mass
 * and BMI are written; other kinds are skipped. Missing values are written as invalid.
 */
object WeightFileWriter {
    /** Manufacturer "development" (255), so the file does not claim to come from a real scale. */
    private const val MANUFACTURER_DEVELOPMENT = 255
    private const val PRODUCT = 1
    private const val SERIAL = 1L

    /** Writes [entries] to [out]; [created] is the file's creation time in UTC epoch seconds. */
    fun write(out: OutputStream, entries: List<BodyMeasure>, created: Long) {
        val w = FitWriter(out)
        w.header()
        w.define(
            0, FitMsg.FILE_ID,
            listOf(
                FieldDef(0, 1, FitBaseType.ENUM),
                FieldDef(1, 2, FitBaseType.UINT16),
                FieldDef(2, 2, FitBaseType.UINT16),
                FieldDef(3, 4, FitBaseType.UINT32Z),
                FieldDef(4, 4, FitBaseType.UINT32),
            ),
        )
        w.data(0, listOf(FitFileType.WEIGHT, MANUFACTURER_DEVELOPMENT, PRODUCT, SERIAL, FitTime.fromEpoch(created)))
        w.define(
            1, FitMsg.DEVICE_INFO,
            listOf(
                FieldDef(253, 4, FitBaseType.UINT32),
                FieldDef(2, 2, FitBaseType.UINT16),
                FieldDef(4, 2, FitBaseType.UINT16),
                FieldDef(5, 2, FitBaseType.UINT16),
            ),
        )
        w.data(1, listOf(FitTime.fromEpoch(created), MANUFACTURER_DEVELOPMENT, PRODUCT, 100))
        w.define(
            2, FitMsg.WEIGHT_SCALE,
            listOf(
                FieldDef(253, 4, FitBaseType.UINT32),
                FieldDef(0, 2, FitBaseType.UINT16),
                FieldDef(1, 2, FitBaseType.UINT16),
                FieldDef(2, 2, FitBaseType.UINT16),
                FieldDef(4, 2, FitBaseType.UINT16),
                FieldDef(5, 2, FitBaseType.UINT16),
                FieldDef(13, 2, FitBaseType.UINT16),
            ),
        )
        val byTime = entries.groupBy { it.tSec }.toSortedMap()
        for ((t, list) in byTime) {
            fun v(kind: BodyKind, div: Int = 1): Int? =
                list.lastOrNull { it.kind == kind }?.valueX100?.let { it / div }?.takeIf { it in 1..0xFFFE }
            val row = listOf(
                FitTime.fromEpoch(t),
                v(BodyKind.WEIGHT),
                v(BodyKind.BODY_FAT),
                v(BodyKind.BODY_WATER),
                v(BodyKind.BONE_MASS),
                v(BodyKind.MUSCLE_MASS),
                v(BodyKind.BMI, 10),
            )
            if (row.drop(1).all { it == null }) continue
            w.data(2, row)
        }
        w.finish()
    }
}
