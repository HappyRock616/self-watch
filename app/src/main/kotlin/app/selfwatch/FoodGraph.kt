/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch

import android.content.Context
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.food.budget.EnergyBudget
import app.selfwatch.food.diary.AndroidFastNotifier
import app.selfwatch.food.diary.BroadcastDayRefresher
import app.selfwatch.food.diary.DiaryService
import app.selfwatch.food.diary.FastService
import app.selfwatch.food.diary.WaterService
import app.selfwatch.food.install.FoodDbInstaller
import app.selfwatch.food.recipes.RecipeNutritionCalculator
import app.selfwatch.food.recipes.RecipeService
import app.selfwatch.food.source.FoodDbFormat
import app.selfwatch.food.source.FoodSearch
import app.selfwatch.food.source.FoodsDbHandle
import app.selfwatch.food.source.FoodsDbSource
import app.selfwatch.food.source.MyFoodsSource
import app.selfwatch.food.source.OffSource
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.net.Http
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.SystemClock
import app.selfwatch.net.Firewall
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * The food module's objects (WP-11), built only when a food screen first asks for [AppGraph.food].
 *
 * - [search] covers the user's own foods and recipes, then the installed foods.db (an absent file just
 *   answers nothing, so installing it later needs no rebuild).
 * - [off] is the Open Food Facts barcode lookup; screens call it only after a local miss and only when
 *   [onlineLookupAllowed] says the firewall lets it out (it has no text search, so it is not part of
 *   [search]).
 */
class FoodGraph internal constructor(
    /** Application context. */
    val ctx: Context,
    /** Database. */
    val db: SelfWatchDb,
    /** HTTP client (Open Food Facts, food data download). */
    val http: Http,
    /** Time source. */
    val clock: Clock = SystemClock,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> SwLog.e(TAG, "food scope failure", e) },
    )

    private val firewall: Firewall by lazy { Firewall(db.settings) }

    private val foodDataDir: File get() = File(ctx.filesDir, FoodDbFormat.DIR)

    private val foodsDbHandle: FoodsDbHandle by lazy { FoodsDbHandle(File(foodDataDir, FoodDbFormat.FOODS_FILE), scope) }

    /** foods.db (answers nothing until it is installed). */
    val foodsDb: FoodsDbSource by lazy { FoodsDbSource(foodsDbHandle) }

    /** Downloads, loads from file and removes foods.db; closes the open handle before swapping the file. */
    val installer: FoodDbInstaller by lazy {
        FoodDbInstaller(http, db.settings, foodDataDir, FOODS_MANIFEST_URL, AppInfo.versionCode(ctx), foodsDbHandle)
    }

    /** The user's foods, recipes and history; recipes resolve with their computed nutrition. */
    val myFoods: MyFoodsSource by lazy {
        MyFoodsSource(db) { r -> RecipeNutritionCalculator(search).perServing(r).perServing }
    }

    /** Text search and barcode lookup over the local sources, in display order. */
    val search: FoodSearch by lazy { FoodSearch(listOf(myFoods, foodsDb)) }

    /** Open Food Facts barcode lookup (through the firewall and network log). */
    val off: OffSource by lazy { OffSource(http, db, clock) }

    /** The food diary. */
    val diary: DiaryService by lazy { DiaryService(db, clock, BroadcastDayRefresher(ctx)) }

    /** The water log. */
    val water: WaterService by lazy { WaterService(db, clock, BroadcastDayRefresher(ctx)) }

    /** Fasting. */
    val fast: FastService by lazy { FastService(db, clock, AndroidFastNotifier(ctx)) }

    /** The recipe book. */
    val recipes: RecipeService by lazy { RecipeService(db, search, clock) }

    /** Calorie budget: goal − food + activity. */
    val budget: EnergyBudget by lazy { EnergyBudget(db, clock) }

    /** Whether the firewall lets Open Food Facts lookups out now. */
    suspend fun onlineLookupAllowed(): Boolean = firewall.allowed(NetFeature.OPEN_FOOD_FACTS)

    /** Constants. */
    companion object {
        private const val TAG = "FoodGraph"

        /**
         * Where the foods.db manifest is published (FOODDB_SPEC §9). The release location is decided
         * with task D12; until then the check fails cleanly and "Load from file" works.
         */
        const val FOODS_MANIFEST_URL: String = "https://fooddata.selfwatch.invalid/foods-manifest.json"
    }
}
