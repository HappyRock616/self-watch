/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.models

import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError

/**
 * One watch model from `models.json`.
 *
 * @property pattern regular expression matched against the advertised Bluetooth name.
 * @property link "V1", "V2", or null when unknown.
 * @property overrides feature switches the capability flags do not cover (false = hide).
 */
public data class GarminModel(
    val name: String,
    val pattern: Regex,
    val productIds: List<Int>,
    val family: String,
    val tier: SupportTier,
    val link: String?,
    val overrides: Map<String, Boolean>,
)

/** The model table, loaded from the `garmin/models.json` resource (WP-06 §7). */
public class GarminModels(public val models: List<GarminModel>) {
    /**
     * The model for a watch: first by [productId] (from DEVICE_INFORMATION), then by Bluetooth [name].
     * Null when unknown (the app then shows tier UNVERIFIED).
     */
    public fun match(name: String?, productId: Int?): GarminModel? {
        if (productId != null) models.firstOrNull { productId in it.productIds }?.let { return it }
        if (name != null) models.firstOrNull { it.pattern.containsMatchIn(name) }?.let { return it }
        return null
    }

    /** Loading. */
    public companion object {
        private const val TAG = "garmin.models"
        private const val RESOURCE = "/garmin/models.json"

        /** Loads the bundled table. */
        public fun load(): Outcome<GarminModels> {
            val text = GarminModels::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return Outcome.Fail(SwError.Io("missing resource $RESOURCE"))
            return parse(text)
        }

        /** Parses `models.json` text; entries with bad fields are skipped with a warning. */
        public fun parse(text: String): Outcome<GarminModels> {
            val root = when (val r = Json.parse(text)) {
                is Outcome.Ok -> r.value
                is Outcome.Fail -> return r
            }
            if (root["version"]?.asLong != 1L) return Outcome.Fail(SwError.BadInput("models.json version must be 1"))
            val list = root["models"]?.asList ?: return Outcome.Fail(SwError.BadInput("models.json has no models"))
            val out = ArrayList<GarminModel>(list.size)
            for (m in list) {
                val model = parseModel(m)
                if (model == null) SwLog.w(TAG, "skipping bad model entry") else out.add(model)
            }
            return Outcome.Ok(GarminModels(out))
        }

        private fun parseModel(m: JsonValue): GarminModel? {
            val name = m["name"]?.asString ?: return null
            val pattern = try {
                Regex(m["pattern"]?.asString ?: return null)
            } catch (e: IllegalArgumentException) {
                return null
            }
            val ids = m["productIds"]?.asList?.mapNotNull { it.asLong?.toInt() } ?: emptyList()
            val tier = when (m["tier"]?.asString) {
                "VERIFIED" -> SupportTier.VERIFIED
                "EXPECTED" -> SupportTier.EXPECTED
                else -> SupportTier.UNVERIFIED
            }
            val overrides = m["overrides"]?.asMap?.mapNotNull { (k, v) -> v.asBoolean?.let { k to it } }?.toMap() ?: emptyMap()
            return GarminModel(name, pattern, ids, m["family"]?.asString ?: "", tier, m["link"]?.asString, overrides)
        }
    }
}
