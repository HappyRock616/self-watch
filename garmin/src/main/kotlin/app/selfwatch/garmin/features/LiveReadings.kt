/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.link.MlService
import app.selfwatch.garmin.link.MultiLink
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.WatchEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reads the payloads of the multi-link live services (one message per reading, no framing). */
public object LiveReadings {
    private const val MIN_BPM = 25
    private const val MAX_BPM = 250
    private const val MAX_STEPS = 500_000L

    /**
     * A live heart-rate message: byte 0 a type, byte 1 the heart rate, byte 2 the resting heart rate.
     * Null when it is too short or carries no reading (0 while the sensor has no contact).
     */
    // Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/v2/CommunicatorV2.java (RealtimeHeartRateCallback: type, hr, resting bytes)
    public fun heartRate(bytes: ByteArray): WatchEvent.LiveHeartRate? {
        if (bytes.size < 2) return null
        val bpm = bytes[1].toInt() and 0xFF
        if (bpm !in MIN_BPM..MAX_BPM) return null
        val resting = if (bytes.size > 2) (bytes[2].toInt() and 0xFF).takeIf { it in MIN_BPM..MAX_BPM } else null
        return WatchEvent.LiveHeartRate(bpm, resting)
    }

    /** A live steps message: today's steps then the step goal, both unsigned 32-bit little-endian. */
    // Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/v2/CommunicatorV2.java (RealtimeStepsCallback: u32 steps, u32 goal)
    public fun steps(bytes: ByteArray): WatchEvent.LiveSteps? {
        if (bytes.size < 4) return null
        val steps = u32(bytes, 0)
        if (steps > MAX_STEPS) return null
        val goal = if (bytes.size >= 8) u32(bytes, 4).takeIf { it in 1..MAX_STEPS }?.toInt() else null
        return WatchEvent.LiveSteps(steps.toInt(), goal)
    }

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
}

/**
 * Turns the watch's live heart-rate and step services on and off on one connection's multi-link. While
 * on, each reading is passed to [emit]. Turning it on twice (or off twice) does nothing the second time.
 * The services end with the connection; the next connection starts with them off.
 */
public class LiveLink(
    private val link: MultiLink,
    private val scope: CoroutineScope,
    private val emit: (WatchEvent) -> Unit,
) {
    private val lock = Mutex()
    private val readers = ArrayList<Job>()

    /** True while the live services are on. */
    @Volatile
    public var isOn: Boolean = false
        private set

    /** Turns the live services on ([enable]) or off. Fails when the watch refuses the heart-rate service. */
    public suspend fun set(enable: Boolean): Outcome<Unit> = lock.withLock {
        if (enable == isOn) return@withLock Outcome.Ok(Unit)
        if (enable) on() else off()
    }

    private suspend fun on(): Outcome<Unit> {
        val hr = link.openService(MlService.REALTIME_HR, reliable = false)
        if (hr is Outcome.Fail) return hr
        hr as Outcome.Ok
        readers += scope.launch { hr.value.incoming.collect { b -> LiveReadings.heartRate(b)?.let(emit) } }
        // Steps are a bonus: a watch without the service still gives live heart rate.
        when (val st = link.openService(MlService.REALTIME_STEPS, reliable = false)) {
            is Outcome.Ok -> readers += scope.launch { st.value.incoming.collect { b -> LiveReadings.steps(b)?.let(emit) } }
            is Outcome.Fail -> SwLog.i(TAG, "live steps unavailable: ${st.error}")
        }
        isOn = true
        SwLog.i(TAG, "live readings on")
        return Outcome.Ok(Unit)
    }

    private suspend fun off(): Outcome<Unit> {
        isOn = false
        val hr = link.closeService(MlService.REALTIME_HR)
        link.closeService(MlService.REALTIME_STEPS)
        for (j in readers) j.cancel()
        readers.clear()
        SwLog.i(TAG, "live readings off")
        return hr
    }

    private companion object {
        const val TAG = "garmin.live"
    }
}
