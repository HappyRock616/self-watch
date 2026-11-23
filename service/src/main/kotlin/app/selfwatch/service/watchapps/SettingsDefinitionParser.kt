/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.watchapps

import android.util.Xml
import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.service.link.LinkValues
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader

/**
 * Reads the settings definition the Connect IQ compiler writes beside a `.prg` (`<app>-settings.json`)
 * into a [SettingsForm] (B26).
 *
 * The file is an object with a `settings` array; each entry has `key`, `valueType`, `defaultValue`,
 * `configTitle` (a string id), `configPrompt`, `configType`, `configOptions` (`[{display, value}]`,
 * `display` a string id), `configMin`, `configMax`, `configMaxLength`, `configReadonly` and
 * `configRequired` (`min` / `max` / `options` are accepted too). An optional `properties` array
 * (`key` or `id`, `valueType` or `type`, `defaultValue` or `value`) fills in types and defaults that a
 * settings entry leaves out. Titles are resolved from, in order: the app's strings file passed as
 * [parse]'s `strings`, the file's own `languages` table (the [parse] `language` if present, else the
 * first), and finally the raw key / id.
 *
 * Bad input never throws: a malformed file is [SwError.BadInput], and bad entries are skipped (logged).
 */
public object SettingsDefinitionParser {
    private const val TAG = "SettingsDefinition"

    /** Most settings read from one file. */
    public const val MAX_SETTINGS: Int = 200

    /**
     * Parses [json]. [strings] (string id → text, from the app's strings resource) wins over the file's
     * `languages`; [language] picks a `languages` entry (for example "eng").
     */
    public fun parse(json: String, strings: Map<String, String> = emptyMap(), language: String? = null): Outcome<SettingsForm> {
        val root = when (val j = Json.parse(json)) {
            is Outcome.Ok -> j.value
            is Outcome.Fail -> return j
        }
        val obj = root as? JsonValue.Obj ?: return Outcome.Fail(SwError.BadInput("settings definition is not an object"))
        val settings = obj["settings"]?.asList
        val props = obj["properties"]?.asList.orEmpty()
        if (settings == null && props.isEmpty()) return Outcome.Fail(SwError.BadInput("settings definition has no settings"))
        val texts = LinkedHashMap<String, String>()
        texts.putAll(languageTable(obj["languages"], language))
        texts.putAll(strings)

        val propInfo = LinkedHashMap<String, Pair<SettingValueType, JsonValue?>>()
        for (p in props) {
            val key = (p["key"] ?: p["id"])?.asString ?: continue
            propInfo[key] = SettingValueType.of((p["valueType"] ?: p["type"])?.asString) to (p["defaultValue"] ?: p["value"])
        }

        val fields = ArrayList<SettingField>()
        val seen = HashSet<String>()
        for (entry in settings.orEmpty()) {
            if (fields.size >= MAX_SETTINGS) break
            val f = field(entry, propInfo, texts)
            if (f == null) {
                SwLog.d(TAG) { "skipped a settings entry without a key" }
                continue
            }
            if (seen.add(f.key)) fields.add(f)
        }
        // With a settings array, properties it leaves out are not user settings. Without one, every
        // property is shown under its key.
        if (settings == null) {
            for ((key, info) in propInfo) {
                if (fields.size >= MAX_SETTINGS) break
                if (!seen.add(key)) continue
                val type = info.first
                fields.add(SettingField(key, key, null, null, type, SettingControl.forType(type), info.second?.let { valueFor(it, type) }))
            }
        }
        return Outcome.Ok(SettingsForm(fields, fromDefinition = true))
    }

    private fun field(e: JsonValue, props: Map<String, Pair<SettingValueType, JsonValue?>>, texts: Map<String, String>): SettingField? {
        if (e !is JsonValue.Obj) return null
        val key = e["key"]?.asString?.takeIf { it.isNotEmpty() } ?: return null
        val prop = props[key]
        val type = SettingValueType.of(e["valueType"]?.asString).takeIf { it != SettingValueType.UNKNOWN } ?: prop?.first ?: SettingValueType.UNKNOWN
        val control = SettingControl.of(e["configType"]?.asString) ?: SettingControl.forType(type)
        val titleId = e["configTitle"]?.asString
        val promptId = e["configPrompt"]?.asString
        val default = (e["defaultValue"]?.takeIf { it != JsonValue.Null } ?: prop?.second)?.let { valueFor(it, type) }
        val options = (e["configOptions"] ?: e["options"])?.asList.orEmpty().mapNotNull { o ->
            val v = o["value"] ?: return@mapNotNull null
            val id = o["display"]?.asString
            val value = valueFor(v, type)
            SettingOption(value, id?.let { texts[it] } ?: id ?: display(value), id)
        }
        return SettingField(
            key = key,
            title = titleId?.let { texts[it] } ?: key,
            titleId = titleId,
            prompt = promptId?.let { texts[it] ?: it },
            valueType = type,
            control = control,
            defaultValue = default,
            options = options,
            min = (e["configMin"] ?: e["min"])?.asDouble,
            max = (e["configMax"] ?: e["max"])?.asDouble,
            maxLength = (e["configMaxLength"] ?: e["maxLength"])?.asLong?.toInt(),
            readOnly = e["configReadonly"]?.asBoolean == true,
            required = e["configRequired"]?.asBoolean == true,
        )
    }

    /** [j] as a value of [type]: numbers follow the type (whole or decimal), others convert as JSON. */
    private fun valueFor(j: JsonValue, type: SettingValueType): CiqValue {
        if (j is JsonValue.Num) {
            return when (type) {
                SettingValueType.FLOAT, SettingValueType.DOUBLE -> CiqValue.Dbl(j.toDouble())
                SettingValueType.NUMBER, SettingValueType.LONG -> j.toLongOrNull()?.let { CiqValue.Num(it) } ?: CiqValue.Dbl(j.toDouble())
                else -> LinkValues.fromJson(j)
            }
        }
        if (j is JsonValue.Str && type == SettingValueType.BOOLEAN) {
            j.v.toBooleanStrictOrNull()?.let { return CiqValue.Bool(it) }
        }
        return LinkValues.fromJson(j)
    }

    private fun display(v: CiqValue): String = when (v) {
        is CiqValue.Str -> v.v
        is CiqValue.Num -> v.v.toString()
        is CiqValue.Dbl -> v.v.toString()
        is CiqValue.Bool -> v.v.toString()
        else -> "?"
    }

    private fun languageTable(languages: JsonValue?, language: String?): Map<String, String> {
        val langs = languages?.asMap ?: return emptyMap()
        val chosen = language?.let { l -> langs.entries.firstOrNull { it.key.equals(l, ignoreCase = true) }?.value } ?: langs.values.firstOrNull()
        return chosen?.asMap.orEmpty().mapNotNull { (k, v) -> v.asString?.let { k to it } }.toMap()
    }

    /**
     * Reads a Connect IQ strings resource (`<strings><string id="ThemeTitle">Colour theme</string></strings>`)
     * into string id → text. Malformed XML gives what was read before the error.
     */
    public fun parseStrings(xml: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val p = Xml.newPullParser()
        try {
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(StringReader(xml))
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && p.name == "string") {
                    val id = p.getAttributeValue(null, "id")
                    val text = p.nextText().trim()
                    if (id != null) out[id] = text
                }
                event = p.next()
            }
        } catch (e: XmlPullParserException) {
            SwLog.w(TAG, "strings file is malformed: ${e.message}")
        } catch (e: IOException) {
            SwLog.w(TAG, "strings file could not be read", e)
        }
        return out
    }
}
