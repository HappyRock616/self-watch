/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.backup

import app.selfwatch.interop.TestSupport
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.random.Random

class BackupCryptoTest {
    companion object {
        private val zip: ByteArray = TestSupport.zipOf("selfwatch.db" to Random(1).nextBytes(200_000), "manifest.json" to "{}".toByteArray())
        private lateinit var file: ByteArray

        @JvmStatic
        @BeforeClass
        fun encryptOnce() {
            file = (BackupCrypto.encrypt(zip, "correct horse".toCharArray()) as Outcome.Ok).value
        }
    }

    private fun bad(r: Outcome<ByteArray>): String = ((r as Outcome.Fail).error as SwError.BadInput).detail

    @Test
    fun layoutAndRoundTrip() {
        assertTrue(BackupCrypto.isEncrypted(file))
        assertEquals(1, file[4].toInt())
        assertEquals(zip.size + BackupCrypto.HEADER_BYTES + 16, file.size)
        assertArrayEquals(zip, (BackupCrypto.decrypt(file, "correct horse".toCharArray()) as Outcome.Ok).value)
    }

    @Test
    fun freshSaltAndNonceEachTime() {
        val again = (BackupCrypto.encrypt(zip, "correct horse".toCharArray()) as Outcome.Ok).value
        assertFalse(file.copyOf(BackupCrypto.HEADER_BYTES).contentEquals(again.copyOf(BackupCrypto.HEADER_BYTES)))
    }

    @Test
    fun wrongPassphraseFailsCleanly() {
        assertEquals("wrong passphrase or damaged file", bad(BackupCrypto.decrypt(file, "Correct horse".toCharArray())))
    }

    @Test
    fun tamperingFailsTheGcmCheck() {
        for (pos in listOf(BackupCrypto.HEADER_BYTES + 10, file.size / 2, file.size - 1, 10, 30)) {
            val t = file.copyOf()
            t[pos] = (t[pos].toInt() xor 0x01).toByte()
            assertEquals("pos $pos", "wrong passphrase or damaged file", bad(BackupCrypto.decrypt(t, "correct horse".toCharArray())))
        }
    }

    @Test
    fun truncatedAndForeignFilesFail() {
        assertEquals("backup file is truncated", bad(BackupCrypto.decrypt(file.copyOf(20), "correct horse".toCharArray())))
        assertEquals("wrong passphrase or damaged file", bad(BackupCrypto.decrypt(file.copyOf(file.size - 5), "correct horse".toCharArray())))
        assertEquals("not an encrypted Self Watch backup", bad(BackupCrypto.decrypt(zip, "x".toCharArray())))
        val v2 = file.copyOf().also { it[4] = 2 }
        assertTrue(bad(BackupCrypto.decrypt(v2, "correct horse".toCharArray())).contains("version 2"))
        assertTrue(BackupCrypto.encrypt(zip, CharArray(0)) is Outcome.Fail)
    }
}
