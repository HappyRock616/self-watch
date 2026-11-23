/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.net

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

/** F02: the shipped config allows cleartext only to the phone itself and trusts system certificates only. */
@RunWith(RobolectricTestRunner::class)
class NetworkSecurityConfigTest {

    @Test
    fun cleartextOnlyForLoopbackAndSystemTrustOnly() {
        val xml = RuntimeEnvironment.getApplication().resources.getXml(R.xml.network_security_config)
        val path = ArrayDeque<String>()
        var baseCleartext: String? = null
        val cleartextDomains = mutableListOf<String>()
        var domainCleartext: String? = null
        val trustSources = mutableListOf<String>()
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            when (xml.eventType) {
                XmlPullParser.START_TAG -> {
                    path.addLast(xml.name)
                    when (xml.name) {
                        "base-config" -> baseCleartext = xml.getAttributeValue(null, "cleartextTrafficPermitted")
                        "domain-config" -> domainCleartext = xml.getAttributeValue(null, "cleartextTrafficPermitted")
                        "certificates" -> trustSources += xml.getAttributeValue(null, "src")
                    }
                }
                XmlPullParser.TEXT -> if (path.lastOrNull() == "domain" && domainCleartext == "true") cleartextDomains += xml.text.trim()
                XmlPullParser.END_TAG -> path.removeLast()
            }
        }
        assertEquals("false", baseCleartext)
        assertEquals(listOf("127.0.0.1", "localhost"), cleartextDomains)
        assertEquals(listOf("system"), trustSources)
    }
}
