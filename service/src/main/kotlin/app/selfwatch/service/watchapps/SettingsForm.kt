/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.watchapps

import app.selfwatch.model.watch.CiqValue

/** The type a Connect IQ setting stores (`valueType`). */
public enum class SettingValueType(
    /** The name in the settings file. */
    public val key: String,
) {
    /** 32-bit whole number. */
    NUMBER("number"),

    /** 64-bit whole number. */
    LONG("long"),

    /** Single-precision decimal. */
    FLOAT("float"),

    /** Double-precision decimal. */
    DOUBLE("double"),

    /** On / off. */
    BOOLEAN("boolean"),

    /** Text. */
    STRING("string"),

    /** Anything else; edited as text. */
    UNKNOWN(""),
    ;

    /** True for the whole-number and decimal types. */
    public val numeric: Boolean get() = this == NUMBER || this == LONG || this == FLOAT || this == DOUBLE

    /** Lookup by file name. */
    public companion object {
        /** The type called [key] (case-insensitive); [UNKNOWN] when unknown or missing. */
        public fun of(key: String?): SettingValueType =
            entries.firstOrNull { it != UNKNOWN && it.key.equals(key, ignoreCase = true) } ?: UNKNOWN
    }
}

/** How the UI shows a setting (`configType`, or derived from the value type when absent). */
public enum class SettingControl(
    /** The name in the settings file. */
    public val key: String,
) {
    /** A drop-down of [SettingField.options]. */
    LIST("list"),

    /** An on/off switch. */
    SWITCH("boolean"),

    /** A number field, limited to [SettingField.min]..[SettingField.max]. */
    NUMBER("numeric"),

    /** A text field. */
    TEXT("alphaNumeric"),

    /** A hidden text field. */
    PASSWORD("password"),

    /** An e-mail address field. */
    EMAIL("email"),

    /** A web address field. */
    URL("url"),

    /** A phone number field. */
    PHONE("phone"),

    /** A date (stored as a number of seconds). */
    DATE("date"),
    ;

    /** Lookup by file name. */
    public companion object {
        /** The control called [key] (case-insensitive), or null. */
        public fun of(key: String?): SettingControl? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }

        /** The control for a setting of [type] without a `configType`. */
        public fun forType(type: SettingValueType): SettingControl = when (type) {
            SettingValueType.BOOLEAN -> SWITCH
            SettingValueType.NUMBER, SettingValueType.LONG, SettingValueType.FLOAT, SettingValueType.DOUBLE -> NUMBER
            SettingValueType.STRING, SettingValueType.UNKNOWN -> TEXT
        }
    }
}

/** One choice of a [SettingControl.LIST] setting: stored [value], shown [label]. */
public data class SettingOption(
    /** The value stored on the watch. */
    val value: CiqValue,
    /** What the user sees (the resolved string, or [labelId] / the value when unresolved). */
    val label: String,
    /** The string id from the file, if any. */
    val labelId: String?,
)

/** One setting of a watch app, ready for the settings editor. */
public data class SettingField(
    /** The property key written to the watch. */
    val key: String,
    /** The label (resolved title, else the [key]). */
    val title: String,
    /** The title's string id from the file, if any. */
    val titleId: String?,
    /** Help text shown under the field, if any. */
    val prompt: String?,
    /** What it stores. */
    val valueType: SettingValueType,
    /** How it is shown. */
    val control: SettingControl,
    /** The value when the watch has none. */
    val defaultValue: CiqValue?,
    /** Choices for [SettingControl.LIST]. */
    val options: List<SettingOption> = emptyList(),
    /** Lowest number allowed, if limited. */
    val min: Double? = null,
    /** Highest number allowed, if limited. */
    val max: Double? = null,
    /** Longest text allowed, if limited. */
    val maxLength: Int? = null,
    /** Shown but not editable. */
    val readOnly: Boolean = false,
    /** Must not be empty. */
    val required: Boolean = false,
) {
    /** True when [v] has the right type and fits the options, range and length. */
    public fun accepts(v: CiqValue): Boolean {
        val typeOk = when (valueType) {
            SettingValueType.NUMBER -> v is CiqValue.Num && v.v in Int.MIN_VALUE..Int.MAX_VALUE
            SettingValueType.LONG -> v is CiqValue.Num
            SettingValueType.FLOAT, SettingValueType.DOUBLE -> v is CiqValue.Dbl || v is CiqValue.Num
            SettingValueType.BOOLEAN -> v is CiqValue.Bool
            SettingValueType.STRING -> v is CiqValue.Str
            SettingValueType.UNKNOWN -> true
        }
        if (!typeOk) return false
        if (control == SettingControl.LIST && options.isNotEmpty() && options.none { same(it.value, v) }) return false
        val n = when (v) {
            is CiqValue.Num -> v.v.toDouble()
            is CiqValue.Dbl -> v.v
            else -> null
        }
        if (n != null && ((min != null && n < min) || (max != null && n > max))) return false
        if (v is CiqValue.Str) {
            if (maxLength != null && v.v.length > maxLength) return false
            if (required && v.v.isEmpty()) return false
        }
        return true
    }

    /** The label of the option whose value is [v], or null. */
    public fun optionLabel(v: CiqValue?): String? = v?.let { x -> options.firstOrNull { same(it.value, x) }?.label }

    private fun same(a: CiqValue, b: CiqValue): Boolean = when {
        a is CiqValue.Num && b is CiqValue.Dbl -> a.v.toDouble() == b.v
        a is CiqValue.Dbl && b is CiqValue.Num -> a.v == b.v.toDouble()
        else -> a == b
    }
}

/**
 * The settings editor's model for one watch app: the [fields] in file order. [fromDefinition] is false
 * when there was no settings file and the fields were made from the watch's values (raw keys).
 */
public data class SettingsForm(
    /** The settings, in order. */
    val fields: List<SettingField>,
    /** True when built from the app's settings definition file. */
    val fromDefinition: Boolean,
) {
    /** The field for [key], or null. */
    public fun field(key: String): SettingField? = fields.firstOrNull { it.key == key }

    /** Each field with its value in [values], or its default. */
    public fun withValues(values: Map<String, CiqValue>): List<Pair<SettingField, CiqValue?>> =
        fields.map { it to (values[it.key] ?: it.defaultValue) }

    /** The keys of [values] that the form would refuse ([SettingField.accepts]); unknown keys are refused too. */
    public fun invalid(values: Map<String, CiqValue>): Set<String> =
        values.filter { (k, v) -> field(k)?.accepts(v) != true }.keys

    /** Factories. */
    public companion object {
        /** A form without a definition: one field per key of [values], typed from the value, titled by its key. */
        public fun fromValues(values: Map<String, CiqValue>): SettingsForm = SettingsForm(
            values.entries.sortedBy { it.key }.map { (k, v) ->
                val type = when (v) {
                    is CiqValue.Bool -> SettingValueType.BOOLEAN
                    is CiqValue.Num -> if (v.v in Int.MIN_VALUE..Int.MAX_VALUE) SettingValueType.NUMBER else SettingValueType.LONG
                    is CiqValue.Dbl -> SettingValueType.FLOAT
                    is CiqValue.Str -> SettingValueType.STRING
                    else -> SettingValueType.UNKNOWN
                }
                SettingField(k, k, null, null, type, SettingControl.forType(type), null, readOnly = type == SettingValueType.UNKNOWN)
            },
            fromDefinition = false,
        )
    }
}
