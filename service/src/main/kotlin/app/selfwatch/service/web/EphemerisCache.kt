/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.web

import app.selfwatch.model.log.SwLog
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties

/** A GPS assistance file the watch got before, with what is needed to ask the server whether it changed. */
public data class CachedEphemeris(
    val body: ByteArray,
    val contentType: String?,
    val etag: String?,
    val lastModified: String?,
    /** When it was fetched or last confirmed unchanged (304), epoch seconds. */
    val checkedSec: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is CachedEphemeris && body.contentEquals(other.body) && contentType == other.contentType && etag == other.etag &&
            lastModified == other.lastModified && checkedSec == other.checkedSec

    override fun hashCode(): Int = ((body.contentHashCode() * 31 + (etag?.hashCode() ?: 0)) * 31 + checkedSec.hashCode())
}

/** Where [CachedEphemeris] files live, one per URL. */
public interface EphemerisStore {
    /** The file cached for [url], or null. */
    public fun load(url: String): CachedEphemeris?

    /** Keeps [e] for [url]. */
    public fun save(url: String, e: CachedEphemeris)
}

/** In memory (tests). */
public class MemoryEphemerisStore : EphemerisStore {
    private val map = HashMap<String, CachedEphemeris>()

    @Synchronized
    override fun load(url: String): CachedEphemeris? = map[url]

    @Synchronized
    override fun save(url: String, e: CachedEphemeris) {
        map[url] = e
    }
}

/**
 * Files in [dir] (the app's cache folder: Android may clear it, which only costs one download). Each URL
 * has `<sha1>.bin` (the body) and `<sha1>.meta` (headers and time). Failures are logged and treated as
 * "nothing cached".
 */
public class FileEphemerisStore(private val dir: File) : EphemerisStore {

    @Synchronized
    override fun load(url: String): CachedEphemeris? {
        val key = key(url)
        val bin = File(dir, "$key.bin")
        val meta = File(dir, "$key.meta")
        if (!bin.isFile || !meta.isFile) return null
        return try {
            val p = Properties().apply { meta.inputStream().use { load(it) } }
            if (p.getProperty(P_URL) != url) return null
            CachedEphemeris(
                body = bin.readBytes(),
                contentType = p.getProperty(P_TYPE),
                etag = p.getProperty(P_ETAG),
                lastModified = p.getProperty(P_LAST_MODIFIED),
                checkedSec = p.getProperty(P_CHECKED)?.toLongOrNull() ?: 0L,
            )
        } catch (e: IOException) {
            SwLog.w(TAG, "GPS assistance cache unreadable", e)
            null
        } catch (e: IllegalArgumentException) {
            SwLog.w(TAG, "GPS assistance cache unreadable", e)
            null
        }
    }

    @Synchronized
    override fun save(url: String, e: CachedEphemeris) {
        val key = key(url)
        try {
            dir.mkdirs()
            val bin = File(dir, "$key.bin")
            val tmp = File(dir, "$key.bin.tmp")
            if (!bin.isFile || !bin.readBytes().contentEquals(e.body)) {
                tmp.writeBytes(e.body)
                if (!tmp.renameTo(bin)) {
                    bin.delete()
                    if (!tmp.renameTo(bin)) throw IOException("rename failed")
                }
            }
            val p = Properties()
            p.setProperty(P_URL, url)
            e.contentType?.let { p.setProperty(P_TYPE, it) }
            e.etag?.let { p.setProperty(P_ETAG, it) }
            e.lastModified?.let { p.setProperty(P_LAST_MODIFIED, it) }
            p.setProperty(P_CHECKED, e.checkedSec.toString())
            File(dir, "$key.meta").outputStream().use { p.store(it, null) }
        } catch (x: IOException) {
            SwLog.w(TAG, "GPS assistance cache not saved", x)
        }
    }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "EphemerisCache"
        const val P_URL = "url"
        const val P_TYPE = "type"
        const val P_ETAG = "etag"
        const val P_LAST_MODIFIED = "last_modified"
        const val P_CHECKED = "checked_sec"
    }
}
