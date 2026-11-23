/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.workout

import app.selfwatch.interop.TestSupport
import app.selfwatch.interop.io.XmlWriter
import app.selfwatch.model.health.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.SchemaFactory

/** Plain JVM tests: the writers are pure Kotlin, and the JDK's XML tools check the output. */
class TrackWritersTest {
    private fun parse(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun children(e: Element): List<Element> =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()

    private fun gpx(points: List<TrackPoint> = TestSupport.track()): ByteArray {
        val out = ByteArrayOutputStream()
        GpxWriter.write(out, TestSupport.workout(), points, "Running <test> & more")
        return out.toByteArray()
    }

    @Test
    fun gpxValidatesAgainstTheSubsetSchemas() {
        val sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        val url = javaClass.classLoader!!.getResource("gpx11-subset.xsd")!!
        val schema = sf.newSchema(url)
        schema.newValidator().validate(StreamSource(ByteArrayInputStream(gpx())))
    }

    @Test
    fun gpxStructure() {
        val doc = parse(gpx())
        val root = doc.documentElement
        assertEquals(TrackNs.GPX, root.namespaceURI)
        assertEquals("gpx", root.localName)
        assertEquals("1.1", root.getAttribute("version"))
        assertTrue(root.getAttribute("creator").isNotEmpty())
        assertEquals(listOf("metadata", "trk"), children(root).map { it.localName })
        val trk = children(root)[1]
        assertEquals(listOf("name", "type", "trkseg"), children(trk).map { it.localName })
        assertEquals("Running <test> & more", children(trk)[0].textContent)
        assertEquals("running", children(trk)[1].textContent)
        val pts = children(children(trk)[2])
        assertEquals(20, pts.size)
        val first = pts[0]
        assertEquals("47.6", first.getAttribute("lat"))
        assertEquals("-122.33", first.getAttribute("lon"))
        assertEquals(listOf("ele", "time", "extensions"), children(first).map { it.localName })
        assertEquals("12", children(first)[0].textContent)
        assertEquals("2026-04-12T13:20:00Z", children(first)[1].textContent)
        val ext = children(children(first)[2])[0]
        assertEquals(TrackNs.TPX_V1, ext.namespaceURI)
        assertEquals("TrackPointExtension", ext.localName)
        assertEquals(listOf("atemp", "hr", "cad"), children(ext).map { it.localName })
        assertTrue(children(ext).all { it.namespaceURI == TrackNs.TPX_V1 })
        assertEquals(listOf("9.5", "120", "80"), children(ext).map { it.textContent })
    }

    @Test
    fun gpxSkipsPointsWithoutPositionAndExtensionsWithoutValues() {
        val pts = listOf(
            TrackPoint(1_776_000_000, null, null, 100, 120, null, null, null),
            TrackPoint(1_776_000_010, 10, 20, null, null, null, null, null),
        )
        val doc = parse(gpx(pts))
        val trkpts = doc.getElementsByTagNameNS(TrackNs.GPX, "trkpt")
        assertEquals(1, trkpts.length)
        assertEquals(listOf("time"), children(trkpts.item(0) as Element).map { it.localName })
    }

    @Test
    fun tcxStructure() {
        val out = ByteArrayOutputStream()
        TcxWriter.write(out, TestSupport.workout(sport = 2), TestSupport.track())
        val doc = parse(out.toByteArray())
        val root = doc.documentElement
        assertEquals(TrackNs.TCX, root.namespaceURI)
        val activity = doc.getElementsByTagNameNS(TrackNs.TCX, "Activity").item(0) as Element
        assertEquals("Biking", activity.getAttribute("Sport"))
        assertEquals(listOf("Id", "Lap"), children(activity).map { it.localName })
        val lap = children(activity)[1]
        assertEquals(
            listOf("TotalTimeSeconds", "DistanceMeters", "Calories", "AverageHeartRateBpm", "MaximumHeartRateBpm", "Intensity", "TriggerMethod", "Track"),
            children(lap).map { it.localName },
        )
        val tps = children(children(lap).last())
        assertEquals(20, tps.size)
        assertEquals(listOf("Time", "Position", "AltitudeMeters", "DistanceMeters", "HeartRateBpm", "Cadence"), children(tps[0]).map { it.localName })
        val lastDist = children(tps.last()).first { it.localName == "DistanceMeters" }.textContent.toDouble()
        assertTrue("distance $lastDist", lastDist in 100.0..300.0)
    }

    @Test
    fun tcxWithoutPointsHasNoTrack() {
        val out = ByteArrayOutputStream()
        TcxWriter.write(out, TestSupport.workout(), emptyList())
        val lap = parse(out.toByteArray()).getElementsByTagNameNS(TrackNs.TCX, "Lap").item(0) as Element
        assertTrue(children(lap).none { it.localName == "Track" })
    }

    @Test
    fun xmlWriterEscapesAndDropsInvalidCharacters() {
        val sw = StringWriter()
        val x = XmlWriter(sw)
        x.declaration()
        x.start("a").attr("q", "say \"hi\" <&>\n").text("1 < 2 & " + 1.toChar() + "ok").start("b").end("b").end("a")
        x.finish()
        val doc = parse(sw.toString().toByteArray())
        assertEquals("say \"hi\" <&>\n", doc.documentElement.getAttribute("q"))
        assertEquals("1 < 2 & ok", doc.documentElement.firstChild.textContent.trim())
    }
}
