/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.net

import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.dao.NetLogEntry
import app.selfwatch.model.net.NetFeature
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Firewall and NetworkLog against the real `selfwatch.db` (one test: the database is a per-process singleton). */
@RunWith(RobolectricTestRunner::class)
class FirewallAndLogDbTest {

    @Test
    fun firewallAndLogUseTheDatabase() = runBlocking {
        val db = SelfWatchDb.get(RuntimeEnvironment.getApplication())
        val firewall = Firewall(db.settings)
        val log = NetworkLog(db)

        // Defaults come from NetFeature.defaultOn.
        for (f in NetFeature.entries) assertEquals(f.name, f.defaultOn, firewall.allowed(f))
        assertEquals(NetFeature.entries.associateWith { it.defaultOn }, firewall.observe().first())

        firewall.set(NetFeature.OPEN_FOOD_FACTS, false)
        firewall.set(NetFeature.GPS_ASSIST, true)
        assertFalse(firewall.allowed(NetFeature.OPEN_FOOD_FACTS))
        assertTrue(firewall.allowed(NetFeature.GPS_ASSIST))
        val now = firewall.observe().first()
        assertEquals(false, now[NetFeature.OPEN_FOOD_FACTS])
        assertEquals(true, now[NetFeature.GPS_ASSIST])

        log.record(NetFeature.OPEN_FOOD_FACTS, "https://world.openfoodfacts.org/api/v2/product/123?fields=x", "get", 0, 120, 200)
        log.record(NetFeature.FOOD_DATA, "cdn.example.org", "GET", 0, 0, NetLogEntry.BLOCKED)
        val today = log.today().first()
        assertEquals(listOf("world.openfoodfacts.org", "cdn.example.org"), today.map { it.host })
        assertEquals(listOf(200, NetLogEntry.BLOCKED), today.map { it.result })
        assertEquals("GET", today[0].method)
        assertEquals(120L, today[0].bytesDown)
        assertEquals(2, log.recent(10).first().size)
        assertEquals(0, log.prune())
    }
}
