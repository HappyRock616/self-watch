/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.presence

import android.companion.CompanionDeviceManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Presence registration by API level: none below 31, by address on 31–35. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 31, 35])
class PresenceObserverTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun registersByAddressFromApi31AndNeverCrashes() {
        val cdm = app.getSystemService(CompanionDeviceManager::class.java)
        shadowOf(cdm).addAssociation("AA:BB:CC:DD:EE:01")
        PresenceObserver.start(app, "AA:BB:CC:DD:EE:01", 7)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Robolectric records the address-based call only from its API 33 shadow; on 31–32 the
            // call is made but not recorded, so that level is covered by "never crashes" alone.
            assertEquals("AA:BB:CC:DD:EE:01", shadowOf(cdm).lastObservingDevicePresenceDeviceAddress)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            assertNull(shadowOf(cdm).lastObservingDevicePresenceDeviceAddress)
        }
        // Unknown devices only log.
        PresenceObserver.stop(app, "00:00:00:00:00:00", null)
    }
}
