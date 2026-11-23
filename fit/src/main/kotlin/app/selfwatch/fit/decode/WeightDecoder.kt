/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.fit.decode

import app.selfwatch.fit.io.FitReader
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import java.io.InputStream

/**
 * Decodes `weight_scale` (30) messages into [BodyMeasure]s: weight, body fat, body water, bone mass,
 * muscle mass and BMI. Measures have `id` 0, source [DataSource.IMPORT] and no device or import id;
 * the caller fills those in with `copy()`.
 */
object WeightDecoder {
    private const val TAG = "WeightDecoder"

    // Ref: Gadgetbridge 160beab41 FitCodeGenerator/src/main/resources/fit_profile.json (weight_scale: 0 weight /100 kg, 1 percent_fat /100, 2 percent_hydration /100, 4 bone_mass /100 kg, 5 muscle_mass /100 kg, 13 bmi /10)
    internal const val F_WEIGHT = 0
    internal const val F_FAT = 1
    internal const val F_HYDRATION = 2
    internal const val F_BONE = 4
    internal const val F_MUSCLE = 5
    internal const val F_BMI = 13

    /** Decodes [file]; measures come in file order. */
    fun decode(file: InputStream): Outcome<List<BodyMeasure>> {
        val out = ArrayList<BodyMeasure>()
        val result = FitReader(file).read { rec ->
            if (rec.global != FitMsg.WEIGHT_SCALE) return@read
            val t = rec.tSec ?: return@read
            fun add(kind: BodyKind, x100: Long?) {
                if (x100 == null || x100 <= 0 || x100 > Int.MAX_VALUE) return
                out.add(BodyMeasure(0, t, kind, null, x100.toInt(), DataSource.IMPORT, null, null))
            }
            add(BodyKind.WEIGHT, rec.long(F_WEIGHT))
            add(BodyKind.BODY_FAT, rec.long(F_FAT))
            add(BodyKind.BODY_WATER, rec.long(F_HYDRATION))
            add(BodyKind.BONE_MASS, rec.long(F_BONE))
            add(BodyKind.MUSCLE_MASS, rec.long(F_MUSCLE))
            add(BodyKind.BMI, rec.long(F_BMI)?.times(10))
        }
        return when (result) {
            is Outcome.Fail -> result
            is Outcome.Ok -> {
                if (!result.value.crcOk) SwLog.w(TAG, "weight file CRC mismatch; using its data anyway")
                Outcome.Ok(out)
            }
        }
    }
}
