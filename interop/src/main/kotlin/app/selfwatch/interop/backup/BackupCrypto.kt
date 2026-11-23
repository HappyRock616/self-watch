/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.backup

import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted backup container (`.swbak`).
 *
 * Layout: `SWBK` (4 bytes) · version (1 byte, currently 1) · salt (16 bytes) · nonce (12 bytes) ·
 * AES-256-GCM ciphertext of the backup zip with its 16-byte tag at the end. The key is
 * PBKDF2-HMAC-SHA256 of the passphrase with 310,000 rounds. The 33 header bytes are authenticated as
 * additional data, so changing any byte of the file fails the GCM check.
 *
 * Decryption verifies the tag before returning success; callers write the output to a temporary
 * file and discard it on failure (the JCA buffers GCM plaintext until the tag checks, but not every
 * provider promises that).
 */
object BackupCrypto {
    /** File magic. */
    val MAGIC: ByteArray = byteArrayOf('S'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())

    /** Current format version. */
    const val VERSION: Int = 1

    /** PBKDF2 rounds. */
    const val ITERATIONS: Int = 310_000

    /** Salt length in bytes. */
    const val SALT_BYTES: Int = 16

    /** GCM nonce length in bytes. */
    const val NONCE_BYTES: Int = 12

    /** Header length: magic, version, salt, nonce. */
    const val HEADER_BYTES: Int = 4 + 1 + SALT_BYTES + NONCE_BYTES

    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val CHUNK = 1 shl 16

    /** True when [head] starts with the SWBK magic. */
    fun isEncrypted(head: ByteArray): Boolean = head.size >= 4 && head.copyOf(4).contentEquals(MAGIC)

    private fun key(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Encrypts all of [input] (the backup zip) into [output] with [passphrase] (version 1: 310,000
     * PBKDF2 rounds, fresh random salt and nonce).
     */
    fun encrypt(input: InputStream, output: OutputStream, passphrase: CharArray): Outcome<Unit> {
        if (passphrase.isEmpty()) return Outcome.Fail(SwError.BadInput("empty passphrase"))
        return try {
            val rnd = SecureRandom()
            val salt = ByteArray(SALT_BYTES).also { rnd.nextBytes(it) }
            val nonce = ByteArray(NONCE_BYTES).also { rnd.nextBytes(it) }
            val header = MAGIC + byteArrayOf(VERSION.toByte()) + salt + nonce
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            output.write(header)
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                cipher.update(buf, 0, n)?.let { output.write(it) }
            }
            output.write(cipher.doFinal())
            output.flush()
            Outcome.Ok(Unit)
        } catch (e: IOException) {
            SwLog.w(TAG, "encrypt I/O failed", e)
            Outcome.Fail(SwError.Io("encrypt failed: ${e.javaClass.simpleName}"))
        } catch (e: GeneralSecurityException) {
            SwLog.e(TAG, "encrypt failed", e)
            Outcome.Fail(SwError.Io("encryption unavailable: ${e.javaClass.simpleName}"))
        }
    }

    /**
     * Decrypts [input] (a `.swbak`) into [output]. A wrong passphrase and a damaged or tampered file
     * both fail with [SwError.BadInput] ("wrong passphrase or damaged file"); a file that isn't SWBK or
     * has an unknown version fails with its own message.
     */
    fun decrypt(input: InputStream, output: OutputStream, passphrase: CharArray): Outcome<Unit> {
        return try {
            val header = ByteArray(HEADER_BYTES)
            readFully(input, header)
            if (!isEncrypted(header)) return Outcome.Fail(SwError.BadInput("not an encrypted Self Watch backup"))
            val version = header[4].toInt() and 0xFF
            if (version != VERSION) return Outcome.Fail(SwError.BadInput("backup format version $version is not supported"))
            val salt = header.copyOfRange(5, 5 + SALT_BYTES)
            val nonce = header.copyOfRange(5 + SALT_BYTES, HEADER_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                cipher.update(buf, 0, n)?.let { output.write(it) }
            }
            output.write(cipher.doFinal())
            output.flush()
            Outcome.Ok(Unit)
        } catch (e: AEADBadTagException) {
            Outcome.Fail(SwError.BadInput("wrong passphrase or damaged file"))
        } catch (e: EOFException) {
            Outcome.Fail(SwError.BadInput("backup file is truncated"))
        } catch (e: IOException) {
            SwLog.w(TAG, "decrypt I/O failed", e)
            Outcome.Fail(SwError.Io("decrypt failed: ${e.javaClass.simpleName}"))
        } catch (e: GeneralSecurityException) {
            // Too-short ciphertext and similar end up here on some providers.
            Outcome.Fail(SwError.BadInput("wrong passphrase or damaged file"))
        }
    }

    /** [encrypt] over byte arrays. */
    fun encrypt(zip: ByteArray, passphrase: CharArray): Outcome<ByteArray> {
        val out = ByteArrayOutputStream()
        return when (val r = encrypt(ByteArrayInputStream(zip), out, passphrase)) {
            is Outcome.Ok -> Outcome.Ok(out.toByteArray())
            is Outcome.Fail -> r
        }
    }

    /** [decrypt] over byte arrays. */
    fun decrypt(file: ByteArray, passphrase: CharArray): Outcome<ByteArray> {
        val out = ByteArrayOutputStream()
        return when (val r = decrypt(ByteArrayInputStream(file), out, passphrase)) {
            is Outcome.Ok -> Outcome.Ok(out.toByteArray())
            is Outcome.Fail -> r
        }
    }

    private fun readFully(input: InputStream, dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            val n = input.read(dst, off, dst.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    private const val TAG = "BackupCrypto"
}
