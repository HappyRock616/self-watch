/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.barcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.oned.EAN8Writer
import com.google.zxing.oned.UPCAWriter
import com.google.zxing.oned.UPCEWriter
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The analyzer on synthetic camera frames: barcodes rendered with ZXing's own writers into 1280×720
 * luminance planes (grey surround, sensor noise, padded row strides, turned 90°). No real camera.
 */
class BarcodeAnalyzerTest {

    private data class Frame(val y: ByteArray, val width: Int, val height: Int, val stride: Int)

    private data class Case(val format: BarcodeFormat, val code: String, val expectedGtin: Long, val rotated: Boolean = false, val stride: Int = W, val noise: Int = 0)

    private val cases = listOf(
        Case(BarcodeFormat.EAN_13, "4006381333931", 4006381333931L),
        Case(BarcodeFormat.EAN_13, "5012345678900", 5012345678900L, noise = 20),
        Case(BarcodeFormat.EAN_13, "9780306406157", 9780306406157L, rotated = true),
        Case(BarcodeFormat.EAN_13, "8711253001202", 8711253001202L, stride = 1344),
        Case(BarcodeFormat.EAN_13, "7622210449283", 7622210449283L, rotated = true, stride = 1408, noise = 15),
        Case(BarcodeFormat.EAN_13, "3017620422003", 3017620422003L, noise = 22),
        Case(BarcodeFormat.EAN_8, "96385074", 96385074L),
        Case(BarcodeFormat.EAN_8, "40170725", 40170725L, rotated = true),
        Case(BarcodeFormat.EAN_8, "55123457", 55123457L, stride = 1344, noise = 20),
        Case(BarcodeFormat.EAN_8, "12345670", 12345670L, noise = 10),
        Case(BarcodeFormat.UPC_A, "012345678905", 12345678905L),
        Case(BarcodeFormat.UPC_A, "036000291452", 36000291452L, rotated = true),
        Case(BarcodeFormat.UPC_A, "041196910759", 41196910759L, stride = 1344),
        Case(BarcodeFormat.UPC_A, "070470003016", 70470003016L, noise = 25),
        Case(BarcodeFormat.UPC_A, "028400090896", 28400090896L, rotated = true, noise = 10),
        Case(BarcodeFormat.UPC_E, "04252614", 42100005264L),
        Case(BarcodeFormat.UPC_E, "01234505", 12000003455L, rotated = true),
        Case(BarcodeFormat.UPC_E, "01234572", 12345000072L, stride = 1344),
        Case(BarcodeFormat.UPC_E, "11234562", 11234500006L * 10 + 2, noise = 15),
        Case(BarcodeFormat.UPC_E, "00123457", 1234000057L, rotated = true, stride = 1344),
    )

    @Test
    fun readsAllTwentyFrames() {
        assertEquals(20, cases.size)
        for ((i, c) in cases.withIndex()) {
            val f = render(c, seed = i)
            val analyzer = BarcodeAnalyzer()
            assertNull("first read is not enough: $c", analyzer.analyze(f.y, f.width, f.height, f.stride))
            val r = analyzer.analyze(f.y, f.width, f.height, f.stride)
            assertNotNull("frame $i not read: $c", r)
            assertEquals("frame $i", c.expectedGtin, r!!.gtin)
            assertEquals(c.format, r.format)
        }
    }

    @Test
    fun needsTwoMatchingReadsAndThenStartsAgain() {
        val a = render(cases[0], 1)
        val b = render(cases[6], 2)
        val blank = Frame(ByteArray(W * H) { 128.toByte() }, W, H, W)
        val an = BarcodeAnalyzer()
        assertNull(an.analyze(a.y, W, H))
        assertNull(an.analyze(b.y, W, H)) // a different code restarts the run
        assertNull(an.analyze(blank.y, W, H)) // a frame with nothing does not break it
        assertEquals(96385074L, an.analyze(b.y, W, H)?.gtin)
        assertNull(an.analyze(b.y, W, H)) // after an accept the run starts again
        assertEquals(96385074L, an.analyze(b.y, W, H)?.gtin)
        an.reset()
        assertNull(an.analyze(a.y, W, H))
    }

    @Test
    fun blankAndNoiseFramesReadNothing() {
        val r = Random(3)
        val an = BarcodeAnalyzer()
        repeat(5) {
            val noise = ByteArray(W * H) { r.nextInt(256).toByte() }
            assertNull(an.decode(noise, W, H))
        }
        assertNull(an.decode(ByteArray(W * H), W, H))
    }

    @Test
    fun toScanExpandsUpcEAndRejectsBadCheckDigits() {
        assertEquals(42100005264L, BarcodeAnalyzer.toScan("04252614", BarcodeFormat.UPC_E)?.gtin)
        assertNull(BarcodeAnalyzer.toScan("04252615", BarcodeFormat.UPC_E))
        assertNull(BarcodeAnalyzer.toScan("4006381333932", BarcodeFormat.EAN_13))
        assertEquals(12345678905L, BarcodeAnalyzer.toScan("012345678905", BarcodeFormat.UPC_A)?.gtin)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortBuffers() {
        BarcodeAnalyzer().decode(ByteArray(10), W, H)
    }

    private fun render(c: Case, seed: Int): Frame {
        val matrix: BitMatrix = when (c.format) {
            BarcodeFormat.EAN_13 -> EAN13Writer().encode(c.code, c.format, 0, 0)
            BarcodeFormat.EAN_8 -> EAN8Writer().encode(c.code, c.format, 0, 0)
            BarcodeFormat.UPC_A -> UPCAWriter().encode(c.code, c.format, 0, 0)
            BarcodeFormat.UPC_E -> UPCEWriter().encode(c.code, c.format, 0, 0)
            else -> error("format")
        }
        // matrix is one module per pixel and 1 row tall; draw it at MODULE px per module.
        val modules = matrix.width
        val barW = modules * MODULE
        val barH = 220
        val r = Random(seed)
        val y = ByteArray(c.stride * (H - 1) + W)
        for (i in y.indices) y[i] = (r.nextInt(256)).toByte() // garbage, including the stride padding
        for (row in 0 until H) for (col in 0 until W) y[row * c.stride + col] = shade(SURROUND, c.noise, r)
        if (!c.rotated) {
            val left = (W - barW) / 2
            val top = (H - barH) / 2
            for (row in 0 until barH) for (x in 0 until barW) {
                val black = matrix.get(x / MODULE, 0)
                y[(top + row) * c.stride + left + x] = shade(if (black) DARK else LIGHT, c.noise, r)
            }
        } else {
            // Bars run horizontally: the code reads top to bottom.
            val barsTall = modules * 3 // 3 px per module so it fits in 720 rows
            val left = (W - barH) / 2
            val top = (H - barsTall) / 2
            for (yy in 0 until barsTall) for (x in 0 until barH) {
                val black = matrix.get(yy / 3, 0)
                y[(top + yy) * c.stride + left + x] = shade(if (black) DARK else LIGHT, c.noise, r)
            }
        }
        return Frame(y, W, H, c.stride)
    }

    private fun shade(base: Int, noise: Int, r: Random): Byte {
        val v = if (noise == 0) base else base + r.nextInt(-noise, noise + 1)
        return v.coerceIn(0, 255).toByte()
    }

    private companion object {
        const val W = 1280
        const val H = 720
        const val MODULE = 5
        const val DARK = 30
        const val LIGHT = 225
        const val SURROUND = 170
    }
}
