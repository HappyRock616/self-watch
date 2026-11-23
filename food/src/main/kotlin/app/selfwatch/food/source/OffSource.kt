/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.source

import app.selfwatch.db.CustomFoodOrigin
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.dao.CustomFood
import app.selfwatch.food.barcode.Gtin
import app.selfwatch.model.food.Basis
import app.selfwatch.model.food.Food
import app.selfwatch.model.food.FoodOrigin
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.food.Nutrients
import app.selfwatch.model.food.Serving
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.net.Http
import app.selfwatch.model.net.HttpRequest
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Clock
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Open Food Facts product lookup by barcode (no online text search, for privacy and speed).
 *
 * `GET https://world.openfoodfacts.org/api/v2/product/<code>?fields=…` through [http] as
 * [NetFeature.OPEN_FOOD_FACTS] (the firewall can block it; `net` adds the app's User-Agent). A found
 * product is saved as a custom food (origin "Open Food Facts lookup", `CustomFoodOrigin.OPEN_FOOD_FACTS`,
 * with the barcode) so the next scan works offline from `MyFoodsSource`; the returned food is that
 * saved copy (`FoodOrigin.MY_FOOD`).
 *
 * Mapping, per 100 g: `energy-kcal_100g` (or kJ ÷ 4.184 when only `energy-kj_100g`/`energy_100g` is
 * given), `proteins_100g`, `fat_100g`, `saturated-fat_100g`, `carbohydrates_100g`, `sugars_100g`,
 * `fiber_100g`, and `sodium_100g` / `cholesterol_100g` from grams to mg. Missing values stay unknown.
 */
class OffSource(
    private val http: Http,
    private val db: SelfWatchDb,
    private val clock: Clock,
    private val baseUrl: String = BASE_URL,
) : FoodSource, DetailedBarcodeSource {

    override val origin: FoodOrigin = FoodOrigin.OPEN_FOOD_FACTS

    /** No online text search. */
    override suspend fun search(query: String, limit: Int): List<FoodHit> = emptyList()

    override suspend fun byBarcode(gtin: Long): Food? = (lookup(gtin) as? BarcodeLookup.Found)?.food

    /** Lookups are saved as custom foods, so there is nothing to get by an Open Food Facts id. */
    override suspend fun get(id: Long): Food? = null

    override suspend fun lookup(gtin: Long): BarcodeLookup {
        val code = Gtin.shortForm(gtin)
        val req = HttpRequest(
            feature = NetFeature.OPEN_FOOD_FACTS,
            method = "GET",
            url = "$baseUrl/api/v2/product/$code?fields=$FIELDS",
            headers = mapOf("Accept" to "application/json"),
            maxBytes = MAX_BYTES,
        )
        val resp = when (val r = http.send(req)) {
            is Outcome.Fail -> return BarcodeLookup.Unavailable(r.error)
            is Outcome.Ok -> r.value
        }
        if (resp.status == HTTP_NOT_FOUND) return BarcodeLookup.NotFound
        if (resp.status !in 200..299) {
            SwLog.w(TAG, "Open Food Facts answered HTTP ${resp.status}")
            return BarcodeLookup.Unavailable(SwError.Io("HTTP ${resp.status}"))
        }
        val parsed = when (val p = parseProduct(resp.body.toString(Charsets.UTF_8), gtin)) {
            is Outcome.Fail -> {
                SwLog.w(TAG, "Open Food Facts reply unreadable: ${(p.error as? SwError.BadInput)?.detail}")
                return BarcodeLookup.Unavailable(p.error)
            }
            is Outcome.Ok -> p.value ?: return BarcodeLookup.NotFound
        }
        val now = clock.nowSec()
        val custom = CustomFood(
            food = parsed,
            origin = CustomFoodOrigin.OPEN_FOOD_FACTS,
            originRef = code,
            hidden = false,
            createdSec = now,
            updatedSec = now,
            importId = null,
        )
        val id = db.foods.insert(custom)
        SwLog.i(TAG, "saved an Open Food Facts product as custom food $id")
        return BarcodeLookup.Found(parsed.copy(ref = FoodRef(FoodOrigin.MY_FOOD, id)), FoodHit.TAG_OPEN_FOOD_FACTS)
    }

    /** Constants and the reply parser (pure, for tests). */
    companion object {
        /** Open Food Facts' world site. */
        const val BASE_URL: String = "https://world.openfoodfacts.org"

        /** The fields asked for, nothing more. */
        const val FIELDS: String = "product_name,brands,nutriments,serving_size,serving_quantity"

        /** `Food.sourceLabel` of foods from this lookup. */
        const val SOURCE_LABEL: String = "Open Food Facts"

        private const val TAG = "OffSource"
        private const val HTTP_NOT_FOUND = 404
        private const val MAX_BYTES = 1L shl 20
        private const val NAME_MAX = 80
        private const val BRAND_MAX = 60
        private val KJ_PER_KCAL = BigDecimal("4.184")

        /**
         * Parses a v2 product reply into an unsaved custom [Food] (ref id 0), or null when the reply
         * says the product is unknown (`status` 0 or no `product`). Fails with `BadInput` on bad JSON.
         */
        fun parseProduct(json: String, gtin: Long): Outcome<Food?> {
            val root = when (val r = Json.parse(json)) {
                is Outcome.Fail -> return r
                is Outcome.Ok -> r.value
            }
            if (root !is JsonValue.Obj) return Outcome.Fail(SwError.BadInput("reply is not an object"))
            val status = root["status"]?.let { numberOf(it) }
            val product = root["product"] as? JsonValue.Obj
            if (product == null || (status != null && status.signum() == 0)) return Outcome.Ok(null)

            val brand = product["brands"]?.asString?.split(',')?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
                ?.let { clean(it, BRAND_MAX) }
            val name = product["product_name"]?.asString?.let { clean(it, NAME_MAX) }?.takeIf { it.isNotEmpty() }
                ?: brand
                ?: Gtin.shortForm(gtin)
            val n = product["nutriments"] as? JsonValue.Obj
            val kcal = n?.let { x10(it["energy-kcal_100g"]) }
                ?: n?.let { (numberOf(it["energy-kj_100g"]) ?: numberOf(it["energy_100g"]))?.let { kj -> kjToKcalX10(kj) } }
            val per = Nutrients(
                kcalX10 = kcal,
                proteinX10 = n?.let { x10(it["proteins_100g"]) },
                fatX10 = n?.let { x10(it["fat_100g"]) },
                satfatX10 = n?.let { x10(it["saturated-fat_100g"]) },
                carbX10 = n?.let { x10(it["carbohydrates_100g"]) },
                sugarX10 = n?.let { x10(it["sugars_100g"]) },
                fibreX10 = n?.let { x10(it["fiber_100g"]) },
                sodiumMg = n?.let { gramsToMg(it["sodium_100g"]) },
                cholesterolMg = n?.let { gramsToMg(it["cholesterol_100g"]) },
            )
            val servings = ArrayList<Serving>()
            val qty = numberOf(product["serving_quantity"])?.takeIf { it.signum() > 0 }
            val sizeLabel = product["serving_size"]?.asString?.let { clean(it, NAME_MAX) }?.takeIf { it.isNotEmpty() }
            if (qty != null) {
                val g = qty.movePointRight(1).setScale(0, RoundingMode.HALF_UP).toLong()
                if (g in 1..Int.MAX_VALUE) servings.add(Serving(0, sizeLabel ?: "1 serving", g.toInt()))
            }
            servings.add(Serving(servings.size, "100 g", 1000))
            return Outcome.Ok(
                Food(
                    ref = FoodRef(FoodOrigin.MY_FOOD, 0),
                    name = name,
                    brand = brand,
                    kind = 1,
                    basis = Basis.PER_100G,
                    per = per,
                    servings = servings,
                    dataYear = null,
                    sourceLabel = SOURCE_LABEL,
                    barcode = gtin,
                ),
            )
        }

        /** A number, or a string holding one ("12.5"); null otherwise. */
        private fun numberOf(v: JsonValue?): BigDecimal? = when (v) {
            is JsonValue.Num -> try {
                BigDecimal(v.text)
            } catch (_: NumberFormatException) {
                null
            }
            is JsonValue.Str -> v.v.trim().replace(',', '.').toBigDecimalOrNull()
            else -> null
        }

        private fun toIntOrNull(v: BigDecimal): Int? =
            if (v.signum() < 0 || v > BigDecimal(Int.MAX_VALUE)) null else v.toInt()

        private fun x10(v: JsonValue?): Int? =
            numberOf(v)?.movePointRight(1)?.setScale(0, RoundingMode.HALF_UP)?.let { toIntOrNull(it) }

        private fun gramsToMg(v: JsonValue?): Int? =
            numberOf(v)?.movePointRight(3)?.setScale(0, RoundingMode.HALF_UP)?.let { toIntOrNull(it) }

        private fun kjToKcalX10(kj: BigDecimal): Int? =
            kj.movePointRight(1).divide(KJ_PER_KCAL, 0, RoundingMode.HALF_UP).let { toIntOrNull(it) }

        private fun clean(s: String, max: Int): String {
            val one = s.replace(Regex("\\s+"), " ").trim()
            return if (one.length <= max) one else one.substring(0, max).trimEnd()
        }
    }
}
