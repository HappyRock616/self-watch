/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.profile

import app.selfwatch.ble.conn.CharacteristicReader
import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.ble.BytePipe
import app.selfwatch.model.ble.PortState
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SyncReason
import app.selfwatch.model.health.BodyKind
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.DataSource
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Clock
import app.selfwatch.model.watch.FileStore
import app.selfwatch.model.watch.HealthSink
import app.selfwatch.model.watch.IngestBatch
import app.selfwatch.model.watch.SyncFileKey
import app.selfwatch.model.watch.SyncReport
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchDriver
import app.selfwatch.model.watch.WatchEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * Driver for devices that follow the open Bluetooth SIG health profiles ([HealthProfile]): weight
 * scales, body composition scales, heart rate straps and blood pressure cuffs. It plugs into the
 * service's `DeviceManager` like the Garmin driver.
 *
 * On each connection it subscribes to the measurement characteristic of every supported service the
 * device offers (indications or notifications, whichever the characteristic has), reads the battery
 * level when the device has the Battery service, and stores what arrives through the [HealthSink]:
 * - weight, BMI, body fat, muscle mass and body water as body measures with source `SCALE`;
 * - heart rate as `HEART_RATE` metric samples, buffered and written every [hrFlushMs];
 * - blood pressure as `BP_SYSTOLIC` / `BP_DIASTOLIC` metric samples (mmHg), with the pulse as
 *   `HEART_RATE`.
 *
 * A reading that carries the device's own time stamp is stored at that time and keyed (sync-file
 * style) so a scale that sends the same stored reading again is not stored twice; one without a
 * usable time stamp is stored at the time it arrived. Scales that need user registration first (the
 * User Data Service) are not supported yet.
 */
public class HealthSensorDriver(
    private val device: DeviceInfo,
    private val clock: Clock,
    private val hrFlushMs: Long = HR_FLUSH_MS,
) : WatchDriver {
    private val deviceId: Long = device.id
    private val mutableInfo = MutableStateFlow<DeviceInfo?>(null)
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val mutableEvents = MutableSharedFlow<WatchEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val hrLock = Mutex()
    private val hrBuffer = ArrayList<MetricSample>()

    @Volatile
    private var port: BlePort? = null

    /** The last weight seen this connection (kg ×100) and when it arrived, for body water percent. */
    @Volatile
    private var lastWeight: Pair<Int, Long>? = null

    override val info: StateFlow<DeviceInfo?> = mutableInfo.asStateFlow()
    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    override val events: Flow<WatchEvent> = mutableEvents.asSharedFlow()

    override suspend fun run(port: BlePort, sink: HealthSink, files: FileStore) {
        mutableState.value = ConnectionState.Connecting
        val link = port.state.dropWhile { it == PortState.DISCONNECTED }.first { it != PortState.CONNECTING }
        if (link != PortState.CONNECTED) {
            mutableState.value = ConnectionState.Failed(SwError.Io("Bluetooth connection failed"), null)
            return
        }
        mutableState.value = ConnectionState.Handshaking
        val services = port.serviceUuids()
        val profiles = HealthProfile.present(services)
        if (profiles.isEmpty()) {
            SwLog.i(TAG, "device $deviceId offers no supported health service")
            mutableState.value = ConnectionState.Failed(SwError.Protocol("no supported health service"), null)
            return
        }
        val pipes = LinkedHashMap<HealthProfile, BytePipe>()
        try {
            for (p in profiles) {
                // A measurement characteristic is read-only, so it is both ends of the pipe.
                when (val r = port.openPipe(p.service, p.measurement, p.measurement)) {
                    is Outcome.Ok -> pipes[p] = r.value
                    is Outcome.Fail -> SwLog.w(TAG, "could not subscribe to ${p.name}: ${r.error}")
                }
            }
            if (pipes.isEmpty()) {
                mutableState.value = ConnectionState.Failed(SwError.Protocol("could not subscribe to the device"), null)
                return
            }
            val hasBattery = GattUuids.BATTERY_SERVICE in services
            val features = pipes.keys.mapTo(LinkedHashSet()) { it.feature }
            if (hasBattery) features += Feature.BATTERY
            mutableInfo.value = device.copy(features = features)
            this.port = port
            mutableState.value = ConnectionState.Ready
            SwLog.i(TAG, "device $deviceId ready: ${pipes.keys.joinToString { it.name }}")
            if (hasBattery) readBattery(port)
            coroutineScope {
                val readers = pipes.map { (profile, pipe) ->
                    launch { pipe.incoming.collect { bytes -> onValue(profile, bytes, sink, pipes.keys) } }
                }
                val watcher = launch {
                    port.state.first { it != PortState.CONNECTED }
                    readers.forEach { it.cancel() }
                }
                val flusher = if (HealthProfile.HEART_RATE in pipes) {
                    launch {
                        while (true) {
                            delay(hrFlushMs)
                            flushHeartRate(sink)
                        }
                    }
                } else {
                    null
                }
                readers.joinAll()
                watcher.cancel()
                flusher?.cancel()
            }
        } finally {
            this.port = null
            withContext(NonCancellable) {
                flushHeartRate(sink)
                for (pipe in pipes.values) {
                    try {
                        pipe.close()
                    } catch (e: Exception) {
                        SwLog.w(TAG, "closing a pipe failed", e)
                    }
                }
            }
            if (mutableState.value !is ConnectionState.Failed) mutableState.value = ConnectionState.Disconnected
        }
    }

    /** Nothing to pull: readings arrive by themselves. Refreshes the battery level while connected. */
    override suspend fun sync(reason: SyncReason): Outcome<SyncReport> {
        val p = port ?: return Outcome.Fail(SwError.NotConnected(deviceId))
        val started = System.currentTimeMillis()
        if (mutableInfo.value?.features?.contains(Feature.BATTERY) == true) readBattery(p)
        return Outcome.Ok(SyncReport(filesNew = 0, filesFailed = 0, durationMs = System.currentTimeMillis() - started))
    }

    /** Sensors take no commands. */
    override suspend fun send(cmd: WatchCommand): Outcome<Unit> = Outcome.Fail(SwError.Unsupported(featureOf(cmd)))

    private suspend fun readBattery(port: BlePort) {
        val reader = port as? CharacteristicReader ?: return
        when (val r = reader.read(GattUuids.BATTERY_SERVICE, GattUuids.BATTERY_LEVEL)) {
            is Outcome.Ok -> r.value.firstOrNull()?.let { mutableEvents.tryEmit(WatchEvent.Battery((it.toInt() and 0xFF).coerceIn(0, 100))) }
            is Outcome.Fail -> SwLog.i(TAG, "battery level not read: ${r.error}")
        }
    }

    private suspend fun onValue(profile: HealthProfile, bytes: ByteArray, sink: HealthSink, subscribed: Set<HealthProfile>) {
        try {
            when (profile) {
                HealthProfile.HEART_RATE -> onHeartRate(bytes)
                HealthProfile.WEIGHT_SCALE -> onWeight(bytes, sink)
                HealthProfile.BODY_COMPOSITION -> onBodyComposition(bytes, sink, HealthProfile.WEIGHT_SCALE in subscribed)
                HealthProfile.BLOOD_PRESSURE -> onBloodPressure(bytes, sink)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // Outside input must never stop the connection.
            SwLog.e(TAG, "${profile.name} value could not be handled", e)
        }
    }

    private suspend fun onHeartRate(bytes: ByteArray) {
        val m = HeartRateMeasurement.parse(bytes) ?: return malformed(HealthProfile.HEART_RATE)
        if (m.bpm !in MIN_BPM..MAX_BPM || m.contact == false) return
        hrLock.withLock { hrBuffer += MetricSample(deviceId, MetricKind.HEART_RATE, clock.nowSec(), m.bpm) }
    }

    private suspend fun flushHeartRate(sink: HealthSink) {
        val batch = hrLock.withLock {
            if (hrBuffer.isEmpty()) return
            hrBuffer.toList().also { hrBuffer.clear() }
        }
        val r = sink.ingest(IngestBatch(key = null, metrics = batch))
        if (r is Outcome.Fail) SwLog.w(TAG, "heart rate samples not stored: ${r.error}")
    }

    private suspend fun onWeight(bytes: ByteArray, sink: HealthSink) {
        val m = WeightMeasurement.parse(bytes) ?: return malformed(HealthProfile.WEIGHT_SCALE)
        val weight = m.weightKgX100 ?: return
        val now = clock.nowSec()
        val t = readingTime(m.time, now)
        lastWeight = weight to now
        val body = ArrayList<BodyMeasure>()
        body += measure(t, BodyKind.WEIGHT, weight)
        m.bmiX10?.let { body += measure(t, BodyKind.BMI, it * 10) }
        store(sink, HealthProfile.WEIGHT_SCALE, bytes, m.time, t, IngestBatch(key = null, body = body))
    }

    private suspend fun onBodyComposition(bytes: ByteArray, sink: HealthSink, weightFromScale: Boolean) {
        val m = BodyCompositionMeasurement.parse(bytes) ?: return malformed(HealthProfile.BODY_COMPOSITION)
        val fat = m.bodyFatX10 ?: return
        val now = clock.nowSec()
        val t = readingTime(m.time, now)
        val body = ArrayList<BodyMeasure>()
        body += measure(t, BodyKind.BODY_FAT, fat * 10)
        m.muscleKgX100?.let { body += measure(t, BodyKind.MUSCLE_MASS, it) }
        // The scale's own weight only when it has no Weight Scale service (else that one sends it).
        if (!weightFromScale) m.weightKgX100?.let { body += measure(t, BodyKind.WEIGHT, it) }
        val weight = m.weightKgX100 ?: lastWeight?.takeIf { now - it.second <= PAIR_WINDOW_SEC }?.first
        val water = m.waterKgX100
        if (water != null && weight != null && weight > 0) {
            body += measure(t, BodyKind.BODY_WATER, ((water.toLong() * PCT_X100 + weight / 2) / weight).toInt())
        }
        store(sink, HealthProfile.BODY_COMPOSITION, bytes, m.time, t, IngestBatch(key = null, body = body))
    }

    private suspend fun onBloodPressure(bytes: ByteArray, sink: HealthSink) {
        val m = BloodPressureMeasurement.parse(bytes) ?: return malformed(HealthProfile.BLOOD_PRESSURE)
        val sys = m.systolicMmHg ?: return
        val dia = m.diastolicMmHg ?: return
        val t = readingTime(m.time, clock.nowSec())
        val metrics = ArrayList<MetricSample>()
        metrics += MetricSample(deviceId, MetricKind.BP_SYSTOLIC, t, Math.round(sys).toInt())
        metrics += MetricSample(deviceId, MetricKind.BP_DIASTOLIC, t, Math.round(dia).toInt())
        m.pulseBpm?.let { p -> Math.round(p).toInt().takeIf { it in MIN_BPM..MAX_BPM }?.let { metrics += MetricSample(deviceId, MetricKind.HEART_RATE, t, it) } }
        store(sink, HealthProfile.BLOOD_PRESSURE, bytes, m.time, t, IngestBatch(key = null, metrics = metrics))
    }

    /** Stores [batch]; a reading with the device's own time gets a key so a resent copy is skipped. */
    private suspend fun store(sink: HealthSink, profile: HealthProfile, raw: ByteArray, deviceTime: LocalDateTime?, tSec: Long, batch: IngestBatch) {
        val key = if (deviceTime != null) readingKey(profile, raw, tSec) else null
        if (key != null && sink.alreadyHave(key)) {
            SwLog.d(TAG) { "${profile.name} reading already stored" }
            return
        }
        val r = sink.ingest(batch.copy(key = key))
        if (r is Outcome.Fail) SwLog.w(TAG, "${profile.name} reading not stored: ${r.error}")
    }

    private fun readingKey(profile: HealthProfile, raw: ByteArray, tSec: Long): SyncFileKey =
        SyncFileKey(deviceId, fileIndex = 0, fileType = KEY_TYPE_BASE + profile.measurement16, fileSubtype = 0, tSec = tSec, size = raw.contentHashCode())

    /**
     * The epoch second of a reading: the device's time stamp (its local time, read in the phone's
     * zone) when it is plausible, else [now].
     */
    private fun readingTime(time: LocalDateTime?, now: Long): Long {
        if (time == null || time.year < MIN_YEAR) return now
        val t = try {
            time.atZone(clock.zone()).toEpochSecond()
        } catch (e: java.time.DateTimeException) {
            return now
        }
        return if (t > now + FUTURE_SLACK_SEC) now else t
    }

    private fun measure(tSec: Long, kind: BodyKind, valueX100: Int) =
        BodyMeasure(id = 0, tSec = tSec, kind = kind, customKindId = null, valueX100 = valueX100, source = DataSource.SCALE, deviceId = deviceId, importId = null)

    private fun malformed(profile: HealthProfile) {
        SwLog.w(TAG, "malformed ${profile.name} value ignored")
    }

    private fun featureOf(cmd: WatchCommand): Feature = when (cmd) {
        is WatchCommand.Notify, is WatchCommand.RemoveNotification -> Feature.NOTIFICATIONS
        is WatchCommand.IncomingCall, WatchCommand.CallEnded -> Feature.CALLS
        is WatchCommand.Music -> Feature.MUSIC
        is WatchCommand.Weather -> Feature.WEATHER
        is WatchCommand.FindWatch -> Feature.FIND_WATCH
        is WatchCommand.SetAlarms -> Feature.ALARMS
        is WatchCommand.AppMessage -> Feature.WATCH_APP_MESSAGES
        is WatchCommand.InstallApp -> Feature.WATCH_APP_INSTALL
        is WatchCommand.DeleteApp -> Feature.WATCH_APP_MANAGE
        is WatchCommand.LaunchApp -> Feature.WATCH_APP_LAUNCH
        is WatchCommand.WriteAppSettings -> Feature.WATCH_APP_SETTINGS
        is WatchCommand.WebResponse -> Feature.WATCH_APP_WEB
        WatchCommand.SyncTime -> Feature.TIME_SYNC
    }

    /** Defaults. */
    public companion object {
        private const val TAG = "HealthSensor"

        /** How often buffered heart rate samples are written. */
        public const val HR_FLUSH_MS: Long = 10_000L

        /** `sync_file.file_type` of reading keys: 0x10000 + the measurement characteristic's 16-bit UUID. */
        public const val KEY_TYPE_BASE: Int = 0x10000

        private const val MIN_BPM = 25
        private const val MAX_BPM = 250
        private const val MIN_YEAR = 2015
        private const val FUTURE_SLACK_SEC = 24 * 3600L
        private const val PAIR_WINDOW_SEC = 600L
        private const val PCT_X100 = 10_000L
    }
}
