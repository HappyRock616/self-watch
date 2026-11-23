/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.gfdi.Bytes
import app.selfwatch.garmin.gfdi.FeatureReplies
import app.selfwatch.garmin.gfdi.FitData
import app.selfwatch.garmin.gfdi.FitDefinition
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.GfdiStatus
import app.selfwatch.garmin.gfdi.MalformedException
import app.selfwatch.garmin.gfdi.WeatherRequest
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.model.device.Feature
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Clock
import app.selfwatch.model.watch.WatchCommand
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Weather (A18), only with the `WEATHER_CONDITIONS` capability.
 *
 * [WatchCommand.Weather] stores the forecast and pushes it at once: FIT_DEFINITION with the
 * [WeatherFit] definitions, then FIT_DATA messages packed up to the watch's packet size, each waiting for
 * the watch's status. A WEATHER_REQUEST from the watch is acknowledged and answered with the stored
 * forecast (nothing is sent before the app has given one; WatchEvent has no weather request, so the
 * service pushes on its own schedule). With [repushIntervalMs] set, the stored forecast is pushed
 * again on that interval while connected (WP-06: "every 30 min if enabled"); off by default.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/GarminSupport.java (sendWeatherConditions: only with
 * WEATHER_CONDITIONS; WeatherRequestDeviceEvent → send weather), FitLocalMessageHandler.java (definitions first,
 * data after each status, 6 bytes framing overhead per FIT_DATA)
 */
public class WeatherHandler(
    private val clock: Clock,
    private val repushIntervalMs: Long? = null,
    private val requestTimeoutMs: Long = 10_000,
) : GfdiHandler {
    private val lock = Any()
    private var state: State? = null

    @Volatile
    private var stored: WatchCommand.Weather? = null

    private class State(val session: GarminSessionContext) {
        val pushLock = Mutex()
        var repush: Job? = null
    }

    private fun stateFor(session: GarminSessionContext): State = synchronized(lock) {
        state?.takeIf { it.session === session } ?: State(session).also { state = it }
    }

    private fun supported(session: GarminSessionContext) = GarminCapability.WEATHER_CONDITIONS in session.capabilities

    override suspend fun handle(m: GfdiMessage, session: GarminSessionContext): Boolean {
        if (m !is WeatherRequest || !supported(session)) return false
        session.channel.reply(m.id)
        SwLog.i(TAG, "watch asks for weather (${m.hours} h)")
        val w = stored
        if (w == null) {
            SwLog.i(TAG, "no weather yet")
        } else {
            val st = stateFor(session)
            session.scope.launch { st.pushLock.withLock { push(session, w) } }
        }
        return true
    }

    override suspend fun command(cmd: WatchCommand, session: GarminSessionContext): Outcome<Unit>? {
        if (cmd !is WatchCommand.Weather) return null
        if (!supported(session)) return Outcome.Fail(SwError.Unsupported(Feature.WEATHER))
        stored = cmd
        val st = stateFor(session)
        val r = st.pushLock.withLock { push(session, cmd) }
        val interval = repushIntervalMs
        if (interval != null && interval > 0) {
            synchronized(lock) {
                if (st.repush == null) {
                    st.repush = session.scope.launch {
                        while (isActive) {
                            delay(interval)
                            val w = stored ?: continue
                            st.pushLock.withLock { push(session, w) }
                        }
                    }
                }
            }
        }
        return r
    }

    private suspend fun push(session: GarminSessionContext, w: WatchCommand.Weather): Outcome<Unit> {
        val records = WeatherFit.encode(w.now, w.days, clock.zone())
        val def = when (val r = session.channel.request(FitDefinition(Bytes(records.definitions)), requestTimeoutMs)) {
            is Outcome.Ok -> r.value
            is Outcome.Fail -> return r
        }
        if (def.status != GfdiStatus.ACK) return Outcome.Fail(SwError.Protocol("weather definitions refused: ${def.status}"))
        val defStatus = fitStatus(def)
        if (defStatus != FeatureReplies.FitStatus.APPLIED) SwLog.w(TAG, "weather definitions not applied ($defStatus); sending data anyway")
        val maxFrame = session.transfers.maxPacketSize
        var i = 0
        while (i < records.data.size) {
            val chunk = java.io.ByteArrayOutputStream()
            var size = FIT_DATA_OVERHEAD
            while (i < records.data.size) {
                val next = records.data[i]
                if (chunk.size() > 0 && size + next.size > maxFrame) break
                chunk.write(next)
                size += next.size
                i++
            }
            val r = when (val rr = session.channel.request(FitData(Bytes(chunk.toByteArray())), requestTimeoutMs)) {
                is Outcome.Ok -> rr.value
                is Outcome.Fail -> return rr
            }
            val st = fitStatus(r)
            if (r.status != GfdiStatus.ACK || st != FeatureReplies.FitStatus.APPLIED) {
                return Outcome.Fail(SwError.Protocol("weather data refused (${r.status}, $st)"))
            }
        }
        SwLog.i(TAG, "weather sent: ${records.data.size} records")
        return Outcome.Ok(Unit)
    }

    private fun fitStatus(r: GfdiMessage.Response): Int = try {
        if (r.extra.size > 0) FeatureReplies.FitStatus.parse(r.extra.toByteArray()).code else FeatureReplies.FitStatus.APPLIED
    } catch (e: MalformedException) {
        FeatureReplies.FitStatus.APPLIED
    }

    private companion object {
        const val TAG = "garmin.weather"

        /** Frame bytes around FIT_DATA records: length, id, CRC. */
        const val FIT_DATA_OVERHEAD = 6
    }
}
