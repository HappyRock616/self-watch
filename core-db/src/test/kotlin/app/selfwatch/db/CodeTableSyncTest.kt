/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.GoalKind
import app.selfwatch.model.food.MatchState
import app.selfwatch.model.food.RecipeKind
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.SleepStage
import app.selfwatch.model.net.NetFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The `-- codes` lines in V1.sql must list exactly the enum codes the DAOs store. */
class CodeTableSyncTest : DbTest() {

    private val codes: Map<String, Map<Int, String>> by lazy {
        val line = Regex("^--\\s*codes\\s+([\\w.]+):\\s*(.*)$")
        File("src/main/assets/db/V1.sql").readLines().mapNotNull { l ->
            val m = line.find(l.trim()) ?: return@mapNotNull null
            m.groupValues[1] to m.groupValues[2].trim().split(Regex("\\s+")).associate { pair ->
                val (k, v) = pair.split('=')
                k.toInt() to v
            }
        }.toMap()
    }

    private fun check(column: String, enumCodes: Map<Int, String>) {
        assertEquals("code table $column", enumCodes, codes[column])
    }

    @Test
    fun enumsMatchSqlComments() {
        check("body_measure.kind", BodyKind.entries.associate { it.code to it.name })
        check("metric.kind", MetricKind.entries.associate { it.code to it.name })
        check("food_origin", FoodOrigin.entries.associate { it.code to it.name })
        check("source", DataSource.entries.associate { it.code to it.name })
        check("sleep_stage.stage", SleepStage.entries.associate { it.code to it.name })
        check("custom_food.basis", Basis.entries.associate { it.code to it.name })
        check("custom_food.origin", CustomFoodOrigin.entries.associate { it.code to it.name })
        check("recipe.kind", RecipeKind.entries.associate { it.code to it.name })
        check("recipe.origin", RecipeOrigin.entries.associate { it.code to it.name })
        check("recipe_item.match_state", MatchState.entries.associate { it.code to it.name })
        check("goal.kind", GoalKind.entries.associate { it.code to it.name })
        check("net_log.feature", NetFeature.entries.associate { it.code to it.name })
        check("sync_file.state", SyncFileState.entries.associate { it.code to it.name })
        check("device.kind", DeviceKind.entries.associate { DeviceKindCodes.code(it) to it.name })
    }

    @Test
    fun everyCodeLineIsChecked() {
        assertEquals(14, codes.size)
    }

    @Test
    fun customFoodOriginMatchesDdlComment() {
        // The DDL comment on custom_food.origin: "1 typed, 2 Open Food Facts lookup, 3 import, 4 menu board".
        val sql = File("src/main/assets/db/V1.sql").readText()
        assertTrue(sql.contains("-- 1 typed, 2 Open Food Facts lookup, 3 import, 4 menu board"))
        assertEquals(listOf(1, 2, 3, 4), CustomFoodOrigin.entries.map { it.code })
    }

    @Test
    fun seededMealSlotsAreTheDefaults() {
        val n = count("meal_slot", "ord BETWEEN 1 AND 4 AND hidden = 0")
        assertEquals(4L, n)
    }

    @Test
    fun fromCodeRoundTrips() {
        SyncFileState.entries.forEach { assertEquals(it, SyncFileState.fromCode(it.code)) }
        CustomFoodOrigin.entries.forEach { assertEquals(it, CustomFoodOrigin.fromCode(it.code)) }
        RecipeOrigin.entries.forEach { assertEquals(it, RecipeOrigin.fromCode(it.code)) }
        DeviceKind.entries.forEach { assertEquals(it, DeviceKindCodes.fromCode(DeviceKindCodes.code(it))) }
        Table.entries.forEach { assertEquals(it, Table.fromSql(it.sql)) }
    }
}
