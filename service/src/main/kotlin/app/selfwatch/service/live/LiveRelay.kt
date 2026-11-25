/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.live

import android.content.Context
import android.content.Intent
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The latest live reading of one watch, as the screens get it.
 *
 * @property deviceId the watch.
 * @property bpm live heart rate (null until the watch sent one).
 * @property restingBpm the watch's resting heart rate for today, when it sent one.
 * @property steps the watch's steps today (null until it sent them).
 * @property stepGoal the watch's step goal, when it sent one.
 * @property tSec when the newest of these arrived (epoch seconds).
 * @property hrSourceId the device [bpm] came from when it is not [deviceId]: a connected heart-rate strap
 *   or sensor, whose value always replaces the watch's (sensor priority). Null: the watch's own.
 */
public data class LiveReading(
    val deviceId: Long,
    val bpm: Int?,
    val restingBpm: Int?,
    val steps: Int?,
    val stepGoal: Int?,
    val tSec: Long,
    val hrSourceId: Long? = null,
) {
    /** The reading as broadcast extras, and back. */
    public companion object {
        /** Broadcast from the `:watch` process to the screens with each new reading (only to this package). */
        public const val BROADCAST: String = "app.selfwatch.service.broadcast.LIVE"
        private const val EXTRA_DEVICE = "app.selfwatch.service.extra.LIVE_DEVICE"
        private const val EXTRA_BPM = "app.selfwatch.service.extra.LIVE_BPM"
        private const val EXTRA_RESTING = "app.selfwatch.service.extra.LIVE_RESTING"
        private const val EXTRA_STEPS = "app.selfwatch.service.extra.LIVE_STEPS"
        private const val EXTRA_GOAL = "app.selfwatch.service.extra.LIVE_GOAL"
        private const val EXTRA_T = "app.selfwatch.service.extra.LIVE_T"
        private const val EXTRA_HR_SOURCE = "app.selfwatch.service.extra.LIVE_HR_SOURCE"
        private const val NONE = -1

        /** [r] as a broadcast for [ctx]'s package. */
        public fun toIntent(ctx: Context, r: LiveReading): Intent = Intent(BROADCAST).setPackage(ctx.packageName)
            .putExtra(EXTRA_DEVICE, r.deviceId)
            .putExtra(EXTRA_BPM, r.bpm ?: NONE)
            .putExtra(EXTRA_RESTING, r.restingBpm ?: NONE)
            .putExtra(EXTRA_STEPS, r.steps ?: NONE)
            .putExtra(EXTRA_GOAL, r.stepGoal ?: NONE)
            .putExtra(EXTRA_T, r.tSec)
            .putExtra(EXTRA_HR_SOURCE, r.hrSourceId ?: NONE.toLong())

        /** The reading in [i], or null when it is not one. */
        public fun fromIntent(i: Intent): LiveReading? {
            if (i.action != BROADCAST) return null
            val t = i.getLongExtra(EXTRA_T, 0L).takeIf { it > 0 } ?: return null
            fun opt(key: String): Int? = i.getIntExtra(key, NONE).takeIf { it >= 0 }
            val source = i.getLongExtra(EXTRA_HR_SOURCE, NONE.toLong()).takeIf { it >= 0 }
            return LiveReading(i.getLongExtra(EXTRA_DEVICE, 0L), opt(EXTRA_BPM), opt(EXTRA_RESTING), opt(EXTRA_STEPS), opt(EXTRA_GOAL), t, source)
        }
    }
}

/**
 * Keeps the watches' live readings on only while a screen asks for them (battery). A screen asks with
 * [request] (true) and repeats the ask while it shows live values; each ask holds the readings on for
 * [leaseMs]. They go off on [request] (false), when the lease runs out without a renewal (a screen that
 * went away without saying so), or on [stop]. A watch that connects while they are on is turned on too.
 *
 * @param connected the ids of the watches connected now.
 * @param send sends a command to one watch.
 * @param publish gets each new reading (the service broadcasts it to the screens).
 * @param nowSec the clock.
 * @param isSensor true for a heart-rate strap or sensor. Its heart rate (sent while it is connected, at no
 *   extra cost) replaces the watches' for [SENSOR_FRESH_SEC] after each value: sensor priority.
 */
public class LiveRelay(
    private val scope: CoroutineScope,
    private val connected: () -> List<Long>,
    private val send: suspend (Long, WatchCommand) -> Outcome<Unit>,
    private val publish: (LiveReading) -> Unit,
    private val nowSec: () -> Long,
    private val leaseMs: Long = LEASE_MS,
    private val isSensor: (Long) -> Boolean = { false },
) {
    private val lock = Mutex()
    private val on = HashSet<Long>()
    private val last = HashMap<Long, LiveReading>()
    private var lease: Job? = null

    /** The newest sensor heart rate: device id, bpm, when. */
    private var sensorHr: Triple<Long, Int, Long>? = null

    /** True while a screen holds the live readings on. */
    @Volatile
    public var active: Boolean = false
        private set

    /** A screen asks for live readings ([enable]) or no longer needs them. */
    public suspend fun request(enable: Boolean) {
        if (!enable) {
            stop()
            return
        }
        lock.withLock {
            active = true
            lease?.cancel()
            lease = scope.launch {
                delay(leaseMs)
                SwLog.i(TAG, "live lease ran out")
                lock.withLock { turnAllOff() }
            }
            for (id in connected()) if (id !in on) turnOn(id)
        }
    }

    /** The connected watches changed: turn on the new ones while active, forget the gone ones. */
    public suspend fun onConnected(ids: List<Long>) {
        lock.withLock {
            // A watch that dropped forgets the live setting, so it must count as off and be turned on
            // again when it comes back.
            on.retainAll(ids.toSet())
            synchronized(last) { last.keys.retainAll(ids.toSet()) }
            if (active) for (id in ids) if (id !in on) turnOn(id)
        }
    }

    /** A watch event; live readings are merged with the watch's last ones and published. */
    public fun onEvent(deviceId: Long, event: WatchEvent): Unit = synchronized(last) {
        if (isSensor(deviceId)) {
            onSensor(deviceId, event)
            return@synchronized
        }
        val now = nowSec()
        val prev = last[deviceId] ?: LiveReading(deviceId, null, null, null, null, 0)
        var next = when (event) {
            is WatchEvent.LiveHeartRate -> prev.copy(bpm = event.bpm, restingBpm = event.restingBpm ?: prev.restingBpm, hrSourceId = null)
            is WatchEvent.LiveSteps -> prev.copy(steps = event.steps, stepGoal = event.goal ?: prev.stepGoal)
            else -> return@synchronized
        }.copy(tSec = now)
        freshSensor(now)?.let { (id, bpm) -> next = next.copy(bpm = bpm, hrSourceId = id) }
        last[deviceId] = next
        offer(prev, next)
    }

    /** A strap's heart rate: it replaces the bpm of every watch reading (or stands alone with no watch). */
    private fun onSensor(sensorId: Long, event: WatchEvent) {
        if (event !is WatchEvent.LiveHeartRate) return
        val now = nowSec()
        sensorHr = Triple(sensorId, event.bpm, now)
        // Straps stream on their own; only bother the screens while one asks for live values.
        if (!active) return
        val watches = last.keys.filter { it != sensorId }
        if (watches.isEmpty()) {
            val prev = last[sensorId] ?: LiveReading(sensorId, null, null, null, null, 0)
            val next = prev.copy(bpm = event.bpm, tSec = now, hrSourceId = sensorId)
            last[sensorId] = next
            offer(prev, next)
            return
        }
        // With a watch there, the strap's value rides on the watch's reading; a standalone strap reading
        // left from before would show as a second, stale device.
        last.remove(sensorId)
        for (id in watches) {
            val prev = last.getValue(id)
            val next = prev.copy(bpm = event.bpm, hrSourceId = sensorId, tSec = now)
            last[id] = next
            offer(prev, next)
        }
    }

    /** The sensor heart rate (id, bpm) when it is recent enough to win, else null. */
    private fun freshSensor(now: Long): Pair<Long, Int>? =
        sensorHr?.takeIf { now - it.third <= SENSOR_FRESH_SEC }?.let { it.first to it.second }

    private fun offer(prev: LiveReading, next: LiveReading) {
        // Unchanged values are sent again only now and then, so the screens' "seconds ago" stays honest.
        if (next.copy(tSec = 0) == prev.copy(tSec = 0) && next.tSec - prev.tSec < REPEAT_SEC) return
        publish(next)
    }

    /** Turns the live readings off on every watch. */
    public suspend fun stop() {
        lock.withLock {
            lease?.cancel()
            turnAllOff()
        }
    }

    private suspend fun turnAllOff() {
        active = false
        lease = null
        for (id in on.toList()) {
            val r = send(id, WatchCommand.LiveData(false))
            if (r is Outcome.Fail) SwLog.i(TAG, "live off on watch $id failed: ${r.error}")
        }
        on.clear()
    }

    private suspend fun turnOn(id: Long) {
        // Only a watch that took the command counts as on: a failed one is tried again on the screen's
        // next renewal ([RENEW_MS]).
        when (val r = send(id, WatchCommand.LiveData(true))) {
            is Outcome.Ok -> on += id
            is Outcome.Fail -> SwLog.i(TAG, "live on for watch $id failed: ${r.error}")
        }
    }

    /** Defaults. */
    public companion object {
        /** How long one ask keeps the readings on. Screens renew well before (every [RENEW_MS]). */
        public const val LEASE_MS: Long = 90_000L

        /** How often a screen showing live values repeats its ask. */
        public const val RENEW_MS: Long = 30_000L

        /** A sensor heart rate replaces the watch's for this long after it arrived (straps send about 1/s). */
        public const val SENSOR_FRESH_SEC: Long = 10L

        private const val REPEAT_SEC = 5L
        private const val TAG = "LiveRelay"
    }
}
