/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.device

import android.app.Application
import app.selfwatch.model.watch.Alarm
import app.selfwatch.service.SyncCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.DayOfWeek

/** C19: the stored alarms, their send result and the new service commands. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlarmSettingsTest {

    @Test
    fun alarmsRoundTripAndBadEntriesAreSkipped() {
        val alarms = listOf(
            Alarm(1, 6, 45, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), true, "Run"),
            Alarm(2, 22, 0, emptySet(), false, null),
        )
        assertEquals(alarms, AlarmSettings.decode(AlarmSettings.encode(alarms)))
        val text = """[{"id":1,"h":25,"m":0},{"id":3,"h":7,"m":5,"days":["SUNDAY","NOPE"]},5,{"h":1,"m":1}]"""
        assertEquals(listOf(Alarm(3, 7, 5, setOf(DayOfWeek.SUNDAY), true, null)), AlarmSettings.decode(text))
        assertEquals(emptyList<Alarm>(), AlarmSettings.decode("{"))
        assertEquals(emptyList<Alarm>(), AlarmSettings.decode(null))
        val many = (1..15).map { Alarm(it, 7, 0, emptySet(), true, null) }
        assertEquals(AlarmSettings.MAX, AlarmSettings.decode(AlarmSettings.encode(many)).size)
    }

    @Test
    fun sendResultRoundTrip() {
        val ok = AlarmSettings.decodeResult(AlarmSettings.encodeResult(true, 100, null))
        assertTrue(ok?.ok == true)
        assertEquals(100L, ok?.tSec)
        assertNull(ok?.reason)
        val fail = AlarmSettings.decodeResult(AlarmSettings.encodeResult(false, 7, "not connected"))
        assertFalse(fail?.ok ?: true)
        assertEquals("not connected", fail?.reason)
        assertNull(AlarmSettings.decodeResult("maybe|x|"))
        assertEquals("alarms.3", AlarmSettings.key(3))
    }

    @Test
    fun commandIntentsNameTheService() {
        val ctx: Application = RuntimeEnvironment.getApplication()
        val i = SyncCommands.intent(ctx, SyncCommands.ACTION_SET_ALARMS)
        assertEquals("app.selfwatch.service.SyncService", i.component?.className)
        assertTrue(SyncCommands.findWatch(ctx, 4, true))
        assertTrue(SyncCommands.setAlarms(ctx, 4))
        assertTrue(SyncCommands.testNotification(ctx))
    }
}
