/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.appmsg

/**
 * XXTEA (Corrected Block TEA, Wheeler and Needham) with 32-bit words read little-endian, as the
 * Connect IQ app-message channel uses it. Data must be a multiple of 4 bytes and at least 8; the key is 16 bytes.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/Xxtea.java (little-endian words, delta 0x9E3779B9,
 * 6 + 52/n rounds)
 */
public object Xxtea {
    private const val DELTA: Int = -0x61c88647 // 0x9E3779B9

    /** Encrypts [data] with [key]; returns a new array. */
    public fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        val v = words(data)
        val k = keyWords(key)
        val n = v.size
        var rounds = 6 + 52 / n
        var sum = 0
        var z = v[n - 1]
        while (rounds-- > 0) {
            sum += DELTA
            val e = (sum ushr 2) and 3
            for (p in 0 until n) {
                val y = v[(p + 1) % n]
                v[p] += mx(sum, y, z, p, e, k)
                z = v[p]
            }
        }
        return bytes(v)
    }

    /** Decrypts [data] with [key]; returns a new array. */
    public fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        val v = words(data)
        val k = keyWords(key)
        val n = v.size
        val rounds = 6 + 52 / n
        var sum = rounds * DELTA
        var y = v[0]
        while (sum != 0) {
            val e = (sum ushr 2) and 3
            for (p in n - 1 downTo 0) {
                val z = v[(p + n - 1) % n]
                v[p] -= mx(sum, y, z, p, e, k)
                y = v[p]
            }
            sum -= DELTA
        }
        return bytes(v)
    }

    private fun mx(sum: Int, y: Int, z: Int, p: Int, e: Int, k: IntArray): Int =
        (((z ushr 5) xor (y shl 2)) + ((y ushr 3) xor (z shl 4))) xor ((sum xor y) + (k[(p and 3) xor e] xor z))

    private fun keyWords(key: ByteArray): IntArray {
        require(key.size == 16) { "XXTEA key must be 16 bytes" }
        return words(key)
    }

    private fun words(b: ByteArray): IntArray {
        require(b.size % 4 == 0 && b.size >= 8) { "XXTEA data must be a multiple of 4 and at least 8 bytes: ${b.size}" }
        return IntArray(b.size / 4) { i ->
            (b[4 * i].toInt() and 0xFF) or ((b[4 * i + 1].toInt() and 0xFF) shl 8) or
                ((b[4 * i + 2].toInt() and 0xFF) shl 16) or ((b[4 * i + 3].toInt() and 0xFF) shl 24)
        }
    }

    private fun bytes(v: IntArray): ByteArray {
        val out = ByteArray(v.size * 4)
        for (i in v.indices) for (j in 0 until 4) out[4 * i + j] = (v[i] ushr (8 * j)).toByte()
        return out
    }
}
