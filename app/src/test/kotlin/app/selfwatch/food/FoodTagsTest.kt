/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import app.selfwatch.R
import app.selfwatch.food.source.FoodHit
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.ui.components.SourceTag
import org.junit.Assert.assertEquals
import org.junit.Test

/** The source pill of foods.db hits follows their data source, then their kind. */
class FoodTagsTest {
    private fun hit(sourceCode: Int?, kind: Int?) = FoodHit(
        ref = FoodRef(FoodOrigin.FOODS_DB, 1), name = "x", brand = null, kcalX10PerDefault = null, defaultServing = null,
        sourceTag = FoodHit.TAG_FOODS_DB, rank = 0, kind = kind, sourceCode = sourceCode,
    )

    @Test
    fun foodsDbTagsUseTheSourceCode() {
        assertEquals(R.string.tag_off to SourceTag.Style.DATABASE, FoodViews.tagOf(hit(1, 1)))
        assertEquals(R.string.tag_chain to SourceTag.Style.CHAIN, FoodViews.tagOf(hit(6, 0)))
        assertEquals(R.string.tag_packaged to SourceTag.Style.DATABASE, FoodViews.tagOf(hit(2, 1)))
        assertEquals(R.string.tag_usda to SourceTag.Style.DATABASE, FoodViews.tagOf(hit(4, 0)))
    }

    @Test
    fun withoutASourceCodeTheKindDecides() {
        assertEquals(R.string.tag_chain to SourceTag.Style.CHAIN, FoodViews.tagOf(hit(null, 2)))
        assertEquals(R.string.tag_packaged to SourceTag.Style.DATABASE, FoodViews.tagOf(hit(null, 1)))
        assertEquals(R.string.tag_usda to SourceTag.Style.DATABASE, FoodViews.tagOf(hit(null, null)))
    }
}
