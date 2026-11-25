/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.model.device.Feature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError

/**
 * GPS assistance (A24, skeleton). The firewall switch GPS_ASSIST is on by default; this upload route is not yet in
 * `Features.IMPLEMENTED`: nothing here has been tried on a watch.
 *
 * Two routes exist:
 * - **Served on request** (what the reference does): the watch asks for
 *   `https://api.gcs.garmin.com/ephemeris/...` through a Connect IQ web request; the phone answers with
 *   the cached file. [isEphemerisRequest] lets the web-request task (A23) recognise those requests.
 * - **Uploaded** (WP-06's plan): [upload] pushes the file with CREATE_FILE / UPLOAD_REQUEST. Which file
 *   type the watch expects is not confirmed, so the type is a parameter and must be set from the live test.
 *
 * [kindOf] checks a downloaded file is one of the formats the reference accepts before it is used.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/agps/GarminAgpsFile.java (tar of CPE_*.BIN, gzip with header
 * 01 00 (RxNetworks), Sony CPE header 2a 12 a0 02), agps/GarminAgpsDataType.java (file names),
 * http/interceptors/AgpsInterceptor.java (api.gcs.garmin.com / .cn, path /ephemeris/)
 */
public class GpsAssist : GfdiHandler {
    @Volatile
    private var session: GarminSessionContext? = null

    /** Formats of assistance files. */
    public enum class Kind { TAR, RXNETWORKS_GZ, SONY_CPE }

    override suspend fun handle(m: GfdiMessage, session: GarminSessionContext): Boolean {
        this.session = session
        return false
    }

    /**
     * Uploads [bytes] as a file of [dataType]/[subType] to the connected watch. Fails with Unsupported
     * without `GPS_EPHEMERIS_DOWNLOAD`, and with BadInput when the bytes are not a known [Kind].
     */
    public suspend fun upload(bytes: ByteArray, dataType: Int, subType: Int): Outcome<Unit> {
        val s = session ?: return Outcome.Fail(SwError.Io("no Garmin session"))
        return upload(s, bytes, dataType, subType)
    }

    /** As [upload], on [session]. */
    public suspend fun upload(session: GarminSessionContext, bytes: ByteArray, dataType: Int, subType: Int): Outcome<Unit> {
        if (GarminCapability.GPS_EPHEMERIS_DOWNLOAD !in session.capabilities) return Outcome.Fail(SwError.Unsupported(Feature.GPS_ASSIST))
        if (kindOf(bytes) == null) return Outcome.Fail(SwError.BadInput("not a GPS assistance file"))
        return when (val r = session.transfers.upload(bytes, dataType, subType)) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Fail -> r
        }
    }

    /** Detection helpers. */
    public companion object {
        /** Constellation file names inside a tar. */
        public val TAR_FILES: List<String> = listOf("CPE_GPS.BIN", "CPE_GLO.BIN", "CPE_GAL.BIN", "CPE_QZSS.BIN")

        private val GZ = byteArrayOf(0x1f, 0x8b.toByte())
        private val SONY = byteArrayOf(0x2a, 0x12, 0xa0.toByte(), 0x02)

        /** True for the watch's ephemeris download URLs. */
        public fun isEphemerisRequest(url: String): Boolean {
            val u = try {
                java.net.URI(url)
            } catch (e: java.net.URISyntaxException) {
                return false
            }
            val host = u.host ?: return false
            return (host == "api.gcs.garmin.com" || host == "api.gcs.garmin.cn") && (u.path ?: "").startsWith("/ephemeris/")
        }

        /** The format of [b], or null if it is none the reference accepts. */
        public fun kindOf(b: ByteArray): Kind? = when {
            startsWith(b, SONY) -> Kind.SONY_CPE
            startsWith(b, GZ) -> if (gzipStartsWith(b, byteArrayOf(0x01, 0x00))) Kind.RXNETWORKS_GZ else null
            isTar(b) -> Kind.TAR
            else -> null
        }

        private fun startsWith(b: ByteArray, p: ByteArray) = b.size >= p.size && p.indices.all { b[it] == p[it] }

        private fun gzipStartsWith(b: ByteArray, p: ByteArray): Boolean = try {
            java.util.zip.GZIPInputStream(b.inputStream()).use { z ->
                val head = z.readNBytes(p.size)
                head.contentEquals(p)
            }
        } catch (e: java.io.IOException) {
            false
        }

        // POSIX tar: "ustar" magic at offset 257 of the first 512-byte header.
        private fun isTar(b: ByteArray): Boolean =
            b.size >= 512 && String(b, 257, 5, Charsets.US_ASCII) == "ustar"
    }
}
