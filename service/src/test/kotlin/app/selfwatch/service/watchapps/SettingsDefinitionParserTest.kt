/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.watchapps

import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** B26: the Connect IQ settings definition parser on a sample file. */
@RunWith(RobolectricTestRunner::class)
class SettingsDefinitionParserTest {
    private fun res(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("watchapps/$name")) { name }.bufferedReader().use { it.readText() }

    private val json = res("sample-settings.json")

    private fun form(strings: Map<String, String> = emptyMap(), language: String? = null): SettingsForm =
        (SettingsDefinitionParser.parse(json, strings, language) as Outcome.Ok).value

    @Test
    fun listWithOptionsAndPropertyDefaults() {
        val f = form()
        assertTrue(f.fromDefinition)
        assertEquals(listOf("boatClass", "startMinutes", "windOffset", "haptics", "skipperName", "teamCode", "rawKey"), f.fields.map { it.key })
        val boat = f.field("boatClass")!!
        assertEquals("Boat class", boat.title)
        assertEquals("BoatClassTitle", boat.titleId)
        assertEquals("Used for the start timer", boat.prompt)
        assertEquals(SettingControl.LIST, boat.control)
        // Type and default come from the properties array.
        assertEquals(SettingValueType.NUMBER, boat.valueType)
        assertEquals(CiqValue.Num(1), boat.defaultValue)
        assertEquals(listOf("Dinghy", "Keelboat", "Multihull"), boat.options.map { it.label })
        assertEquals(listOf(CiqValue.Num(0), CiqValue.Num(1), CiqValue.Num(2)), boat.options.map { it.value })
        assertEquals("Keelboat", boat.optionLabel(CiqValue.Num(1)))
        assertTrue(boat.accepts(CiqValue.Num(2)))
        assertFalse(boat.accepts(CiqValue.Num(3)))
        assertFalse(boat.accepts(CiqValue.Str("1")))
    }

    @Test
    fun rangesBooleansAndStrings() {
        val f = form()
        val start = f.field("startMinutes")!!
        assertEquals(SettingControl.NUMBER, start.control)
        assertEquals(CiqValue.Num(5), start.defaultValue)
        assertEquals(1.0, start.min)
        assertEquals(10.0, start.max)
        assertTrue(start.accepts(CiqValue.Num(10)))
        assertFalse(start.accepts(CiqValue.Num(11)))
        assertFalse(start.accepts(CiqValue.Num(0)))

        val wind = f.field("windOffset")!!
        assertEquals(SettingValueType.FLOAT, wind.valueType)
        assertEquals(CiqValue.Dbl(0.5), wind.defaultValue)
        assertTrue(wind.accepts(CiqValue.Dbl(-14.5)))
        assertTrue(wind.accepts(CiqValue.Num(3)))
        assertFalse(wind.accepts(CiqValue.Dbl(15.5)))

        val haptics = f.field("haptics")!!
        assertEquals(SettingControl.SWITCH, haptics.control)
        assertEquals(CiqValue.Bool(true), haptics.defaultValue)
        assertFalse(haptics.accepts(CiqValue.Num(1)))

        val skipper = f.field("skipperName")!!
        assertEquals(SettingControl.TEXT, skipper.control)
        assertEquals(12, skipper.maxLength)
        assertTrue(skipper.accepts(CiqValue.Str("Phil")))
        assertFalse(skipper.accepts(CiqValue.Str("a much too long name")))

        val team = f.field("teamCode")!!
        assertEquals(SettingControl.PASSWORD, team.control)
        assertTrue(team.required)
        assertFalse(team.accepts(CiqValue.Str("")))
    }

    @Test
    fun missingTitlesFallBackToIdsAndKeys() {
        val f = form()
        // A title id without a string, or no title at all, shows the key.
        assertEquals("teamCode", f.field("teamCode")!!.title)
        assertEquals("NoSuchString", f.field("teamCode")!!.titleId)
        assertEquals("rawKey", f.field("rawKey")!!.title)
        assertEquals(SettingControl.NUMBER, f.field("rawKey")!!.control)
        // The settings array leaves hiddenCounter out: not a user setting.
        assertNull(f.field("hiddenCounter"))
    }

    @Test
    fun appStringsWinAndLanguagesCanBeChosen() {
        val strings = SettingsDefinitionParser.parseStrings(res("sample-strings.xml"))
        assertEquals("Race Helper", strings["AppName"])
        val f = form(strings)
        assertEquals("Skipper name", f.field("skipperName")!!.title)
        assertEquals("Vibrate on wind shifts", f.field("haptics")!!.title)
        assertEquals("Boat class", f.field("boatClass")!!.title)
        val fr = form(language = "FRE")
        assertEquals("Classe de bateau", fr.field("boatClass")!!.title)
        assertEquals("startMinutes", fr.field("startMinutes")!!.title)
    }

    @Test
    fun valuesAndValidation() {
        val f = form()
        val values = mapOf("boatClass" to CiqValue.Num(2), "haptics" to CiqValue.Bool(false))
        val shown = f.withValues(values).associate { (field, v) -> field.key to v }
        assertEquals(CiqValue.Num(2), shown["boatClass"])
        assertEquals(CiqValue.Num(5), shown["startMinutes"])
        assertEquals(setOf("startMinutes", "nope"), f.invalid(mapOf("startMinutes" to CiqValue.Num(99), "haptics" to CiqValue.Bool(true), "nope" to CiqValue.Null)))
    }

    @Test
    fun propertiesOnlyAndRawValues() {
        val f = (SettingsDefinitionParser.parse("""{"properties":[{"id":"speedUnit","type":"number","value":2},{"id":"label","type":"string","value":"x"}]}""") as Outcome.Ok).value
        assertEquals(listOf("speedUnit", "label"), f.fields.map { it.key })
        assertEquals(CiqValue.Num(2), f.field("speedUnit")!!.defaultValue)
        assertEquals(SettingControl.TEXT, f.field("label")!!.control)

        val raw = SettingsForm.fromValues(mapOf("b" to CiqValue.Bool(true), "a" to CiqValue.Dbl(1.5), "c" to CiqValue.Arr(emptyList())))
        assertFalse(raw.fromDefinition)
        assertEquals(listOf("a", "b", "c"), raw.fields.map { it.key })
        assertEquals(listOf("a", "b", "c"), raw.fields.map { it.title })
        assertEquals(SettingControl.SWITCH, raw.field("b")!!.control)
        assertTrue(raw.field("c")!!.readOnly)
    }

    @Test
    fun malformedInputIsAnErrorNotACrash() {
        for (bad in listOf("", "{", "[]", "{\"languages\":{}}", "{\"settings\":5}")) {
            val r = SettingsDefinitionParser.parse(bad)
            assertTrue(bad, r is Outcome.Fail && r.error is SwError.BadInput)
        }
        assertEquals(emptyMap<String, String>(), SettingsDefinitionParser.parseStrings("<strings><string id="))
        assertEquals(mapOf("A" to "b"), SettingsDefinitionParser.parseStrings("<strings><string id=\"A\">b</string><oops"))
    }
}
