/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.device

/**
 * Spots a watch that another phone or app keeps taking away. A Garmin watch works with one phone at
 * a time, and the protocol never says "another phone has me": the watch just goes quiet mid-session
 * and Android reports a link timeout (status 8) seconds later. Neither Gadgetbridge nor Pulse handles
 * this; they keep retrying, and every retry and sync costs the watch battery.
 *
 * The signature (from the Pixel 5 capture of 2026-11-23, to be confirmed by a two-phone test): links
 * that reach READY and then drop within [SHORT_SESSION_SEC], [COUNT] times within [WINDOW_SEC]. A watch
 * that is really out of range fails to connect at all, so its attempts never count here.
 *
 * Pure Kotlin and not thread-safe: one per device, used from the device's supervisor coroutine.
 */
public class ContestDetector {
    private val shortEnds = ArrayDeque<Long>()

    /** True from the moment the signature is seen until [reset]. */
    public var contested: Boolean = false
        private set

    /**
     * Records the end of one link at [endSec]. [readySec] is when it reached READY (null if it never
     * did). Returns [contested].
     */
    public fun onLinkEnded(readySec: Long?, endSec: Long): Boolean {
        if (readySec != null && endSec - readySec < SHORT_SESSION_SEC) {
            shortEnds.addLast(endSec)
        }
        while (shortEnds.isNotEmpty() && endSec - shortEnds.first() > WINDOW_SEC) shortEnds.removeFirst()
        if (shortEnds.size >= COUNT) contested = true
        return contested
    }

    /** Forgets everything (the user chose this phone, or a link stayed up). */
    public fun reset() {
        shortEnds.clear()
        contested = false
    }

    /** Thresholds. */
    public companion object {
        /** A link that drops less than this long after READY counts as short. */
        public const val SHORT_SESSION_SEC: Long = 120

        /** Short links needed within [WINDOW_SEC]. */
        public const val COUNT: Int = 3

        /** The window, seconds. */
        public const val WINDOW_SEC: Long = 15 * 60

        /** While contested, the background check runs this often (seconds). */
        public const val CHECK_EVERY_SEC: Int = 60 * 60

        /** Reason shown when another phone seems to have the watch. */
        public const val REASON_OTHER_PHONE: String = "in use by another phone; tap Sync now to use this phone"

        /** Reason shown when another app on this phone holds the watch's Bluetooth link. */
        public const val REASON_OTHER_APP: String = "in use by another app on this phone (Garmin Connect or Gadgetbridge?)"
    }
}
