/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food.barcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap

/** An accepted scan: the GTIN-14 number, the digits as read and the symbology. */
data class ScanResult(val gtin: Long, val text: String, val format: BarcodeFormat)

/**
 * Finds EAN-13, EAN-8, UPC-A and UPC-E barcodes in camera frames (the Y plane of `YUV_420_888`).
 *
 * - ZXing `MultiFormatReader` over a `PlanarYUVLuminanceSource`, those four formats only, `TRY_HARDER`
 *   off for speed.
 * - Tries the frame as it is, then turned 90°, so a barcode held either way reads.
 * - UPC-E is expanded to UPC-A, then every code is normalised with [Gtin.normalize]; codes with a bad
 *   check digit are ignored.
 * - A code is accepted only when two decoded frames in a row agree (frames with no code in between
 *   do not break the run; a different code starts a new one). After an accept the run resets.
 *
 * Not thread-safe: feed it from one thread (the camera's analysis thread).
 */
class BarcodeAnalyzer(private val confirmations: Int = 2) {
    private val reader = MultiFormatReader().apply {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E)
        setHints(hints)
    }
    private var rotated = ByteArray(0)
    private var lastGtin: Long? = null
    private var runLength = 0

    /**
     * Analyses one frame: [y] holds the luminance rows, each [rowStride] bytes apart (≥ [width]); the
     * last row may be only [width] long. Returns the accepted code, or null (nothing yet).
     */
    fun analyze(y: ByteArray, width: Int, height: Int, rowStride: Int = width): ScanResult? {
        val read = decode(y, width, height, rowStride) ?: return null
        if (read.gtin == lastGtin) {
            runLength++
        } else {
            lastGtin = read.gtin
            runLength = 1
        }
        if (runLength < confirmations) return null
        reset()
        return read
    }

    /** Forgets the current run (call when the scanner reopens). */
    fun reset() {
        lastGtin = null
        runLength = 0
    }

    /** Decodes one frame without the two-in-a-row rule: the code in it, or null. */
    fun decode(y: ByteArray, width: Int, height: Int, rowStride: Int = width): ScanResult? {
        require(width > 0 && height > 0 && rowStride >= width) { "bad frame size" }
        require(y.size >= rowStride.toLong() * (height - 1) + width) { "frame buffer too small" }
        decodeSource(PlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false))?.let { return it }
        // Turned 90° clockwise: new width = height.
        val n = width * height
        if (rotated.size < n) rotated = ByteArray(n)
        val r = rotated
        for (row in 0 until height) {
            val src = row * rowStride
            val dstCol = height - 1 - row
            for (col in 0 until width) r[col * height + dstCol] = y[src + col]
        }
        return decodeSource(PlanarYUVLuminanceSource(r, height, width, 0, 0, height, width, false))
    }

    private fun decodeSource(src: PlanarYUVLuminanceSource): ScanResult? {
        val result = try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(src)))
        } catch (_: ReaderException) {
            return null
        } finally {
            reader.reset()
        }
        return toScan(result.text, result.barcodeFormat)
    }

    /** Constants and the code rule. */
    companion object {
        /** A decoded [text] of [format] as a [ScanResult], or null when it is not a valid GTIN. */
        fun toScan(text: String, format: BarcodeFormat): ScanResult? {
            val digits = if (format == BarcodeFormat.UPC_E) Gtin.expandUpcE(text) ?: return null else text
            val gtin = Gtin.normalize(digits) ?: return null
            return ScanResult(gtin, text, format)
        }
    }
}
