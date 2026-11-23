/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import app.selfwatch.food.diary.DiaryDay
import app.selfwatch.interop.api.DiaryRow
import app.selfwatch.interop.diary.CronometerExporter
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId

/**
 * "Export day" from the Food menu: one CSV of the day's diary, a row per entry with its meal, time,
 * amount and every nutrient (the same columns as the full diary export, so it opens in any
 * spreadsheet and can be imported again).
 */
object DayExport {
    /** MIME type of the file. */
    const val MIME_TYPE: String = "text/csv"

    /** File name offered for [day], for example `selfwatch-food-2026-11-23.csv`. */
    fun fileName(day: app.selfwatch.model.time.Day): String = "selfwatch-food-${day.toLocalDate()}.csv"

    /** The rows of [day] in meal order; times are local to [zone]. Meals without a name are left blank. */
    fun rows(day: DiaryDay, zone: ZoneId): List<DiaryRow> = day.meals.flatMap { m ->
        m.entries.map { e ->
            DiaryRow(
                day = e.day,
                meal = m.slot.name,
                secOfDay = Instant.ofEpochSecond(e.tSec).atZone(zone).toLocalTime().toSecondOfDay(),
                name = e.name,
                brand = e.brand,
                amountX100 = e.amountX100,
                servingLabel = e.servingLabel,
                gramsX10 = e.gramsX10,
                nutrients = e.nutrients,
            )
        }
    }

    /** Writes [rows] as CSV to [out] (flushed, not closed). */
    fun write(out: OutputStream, rows: List<DiaryRow>) {
        CronometerExporter.writeServings(out, rows)
    }
}
