/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.watch

import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.SyncReason
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.BodyMeasure
import app.selfwatch.model.health.MetricSample
import app.selfwatch.model.health.SleepSession
import app.selfwatch.model.health.SleepStageSample
import app.selfwatch.model.health.Workout
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.time.Day
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.DayOfWeek
import java.util.UUID

/** A watch protocol driver. Implemented by `garmin`, used by `service`. */
public interface WatchDriver {
    /** What the driver knows about the watch; null before the handshake. */
    public val info: StateFlow<DeviceInfo?>

    /** Connection state. */
    public val state: StateFlow<ConnectionState>

    /** Things the watch asks the phone to do. */
    public val events: Flow<WatchEvent>

    /** Talks to the watch over [port], storing data through [sink] and [files]. Returns when disconnected or cancelled. */
    public suspend fun run(port: BlePort, sink: HealthSink, files: FileStore)

    /** Syncs health data and workouts now. */
    public suspend fun sync(reason: SyncReason): Outcome<SyncReport>

    /** Sends one command to the watch. */
    public suspend fun send(cmd: WatchCommand): Outcome<Unit>
}

/** Where decoded health data goes. Implemented by `service.HealthIngest`; one transaction per call. */
public interface HealthSink {
    /** Stores [batch] atomically and, if it has a key, marks that sync file as done. */
    public suspend fun ingest(batch: IngestBatch): Outcome<Unit>

    /** True if the sync file [key] was already ingested, so the driver can skip it. */
    public suspend fun alreadyHave(key: SyncFileKey): Boolean
}

/** Identifies one file offered by the watch during sync. */
public data class SyncFileKey(
    val deviceId: Long,
    val fileIndex: Int,
    val fileType: Int,
    val fileSubtype: Int,
    val tSec: Long,
    val size: Int,
)

/** Everything decoded from one sync file (or other unit of work), stored in one transaction. */
public data class IngestBatch(
    val key: SyncFileKey?,
    val activity: List<ActivityMinute> = emptyList(),
    val metrics: List<MetricSample> = emptyList(),
    val sleepStages: List<SleepStageSample> = emptyList(),
    val sleepSessions: List<SleepSession> = emptyList(),
    val workout: Workout? = null,
    val body: List<BodyMeasure> = emptyList(),
)

/** Stores original workout files. */
public interface FileStore {
    /** Saves a workout FIT file and returns its path. */
    public suspend fun saveWorkoutFit(deviceId: Long, startSec: Long, bytes: ByteArray): String

    /**
     * Keeps an original watch file that held sleep data (a sleep file, or a monitoring file with
     * sleep stages), so an odd night can be checked against exactly what the watch sent. Kept for
     * a short time only; does nothing by default.
     */
    public suspend fun keepSleepFile(deviceId: Long, fileSec: Long, kind: String, bytes: ByteArray) {}
}

/** What a sync achieved. */
public data class SyncReport(val filesNew: Int, val filesFailed: Int, val durationMs: Long)

/** Commands the phone sends to the watch. */
public sealed interface WatchCommand {
    /** Show a phone notification. */
    public data class Notify(val n: PhoneNotification) : WatchCommand

    /** Remove the notification with [id]. */
    public data class RemoveNotification(val id: Int) : WatchCommand

    /** A call is ringing. */
    public data class IncomingCall(val number: String?, val name: String?) : WatchCommand

    /** The call ended or was answered elsewhere. */
    public data object CallEnded : WatchCommand

    /** Now-playing update. */
    public data class Music(val state: MusicState) : WatchCommand

    /** Current weather and forecast days. */
    public data class Weather(val now: WeatherNow, val days: List<WeatherDay>) : WatchCommand

    /** Start or stop ringing the watch. */
    public data class FindWatch(val ring: Boolean) : WatchCommand

    /** Replace the watch's alarms. */
    public data class SetAlarms(val alarms: List<Alarm>) : WatchCommand

    /** Message to a Connect IQ app. */
    public data class AppMessage(val appUuid: UUID, val payload: CiqValue) : WatchCommand

    /** Install a Connect IQ app (PRG). Equality compares [prgBytes] by content. */
    public data class InstallApp(val prgBytes: ByteArray, val settingsJson: String?) : WatchCommand {
        override fun equals(other: Any?): Boolean =
            other is InstallApp && prgBytes.contentEquals(other.prgBytes) && settingsJson == other.settingsJson

        override fun hashCode(): Int = 31 * prgBytes.contentHashCode() + (settingsJson?.hashCode() ?: 0)
    }

    /** Delete a Connect IQ app. */
    public data class DeleteApp(val appUuid: UUID) : WatchCommand

    /** Open a Connect IQ app. */
    public data class LaunchApp(val appUuid: UUID) : WatchCommand

    /** Write a Connect IQ app's settings. */
    public data class WriteAppSettings(val appUuid: UUID, val values: Map<String, CiqValue>) : WatchCommand

    /** Answer to a [WatchEvent.WebRequest]. Equality compares [body] by content. */
    public data class WebResponse(val requestId: Int, val status: Int, val body: ByteArray, val contentType: String?) :
        WatchCommand {
        override fun equals(other: Any?): Boolean =
            other is WebResponse && requestId == other.requestId && status == other.status &&
                body.contentEquals(other.body) && contentType == other.contentType

        override fun hashCode(): Int =
            ((requestId * 31 + status) * 31 + body.contentHashCode()) * 31 + (contentType?.hashCode() ?: 0)
    }

    /** Set the watch clock from the phone. */
    public data object SyncTime : WatchCommand

    /**
     * Start ([enable]) or stop the watch's live heart-rate and step readings ([WatchEvent.LiveHeartRate],
     * [WatchEvent.LiveSteps]). Live readings cost watch battery: callers turn them on only while a screen
     * shows them. Added in core-model v1.3.
     */
    public data class LiveData(val enable: Boolean) : WatchCommand
}

/** Things the watch tells or asks the phone. */
public sealed interface WatchEvent {
    /** Start or stop ringing the phone. */
    public data class FindPhone(val start: Boolean) : WatchEvent

    /** A media button was pressed on the watch. */
    public data class MusicControl(val action: MusicAction) : WatchEvent

    /** Accept or reject the ringing call. */
    public data class CallAction(val accept: Boolean) : WatchEvent

    /** The user picked [action] (or sent [replyText]) on notification [id]. */
    public data class NotificationAction(val id: Int, val action: String, val replyText: String?) : WatchEvent

    /** A Connect IQ app sent a message. */
    public data class AppMessageReceived(val appUuid: UUID, val payload: CiqValue) : WatchEvent

    /** A Connect IQ app asks for a web request. Equality compares [body] by content. */
    public data class WebRequest(
        val requestId: Int,
        val appUuid: UUID?,
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
    ) : WatchEvent {
        override fun equals(other: Any?): Boolean =
            other is WebRequest && requestId == other.requestId && appUuid == other.appUuid && url == other.url &&
                method == other.method && headers == other.headers && body.contentEquals(other.body)

        override fun hashCode(): Int =
            (((requestId * 31 + url.hashCode()) * 31 + method.hashCode()) * 31 + headers.hashCode()) * 31 +
                body.contentHashCode()
    }

    /** Battery level update. */
    public data class Battery(val pct: Int) : WatchEvent

    /** The list of installed apps. */
    public data class InstalledApps(val apps: List<WatchApp>) : WatchEvent

    /** A Connect IQ app's current settings. */
    public data class AppSettings(val appUuid: UUID, val values: Map<String, CiqValue>) : WatchEvent

    /**
     * The watch asked for Garmin's secure pairing, which Self Watch does not support yet
     * (COMPATIBILITY R2); sent once per connection. Added in core-model v1.1.
     */
    public data object SecurePairingRequired : WatchEvent

    /**
     * The watch asked to sign in to Garmin Connect (COMPATIBILITY R1), which it does when its Garmin
     * sign-in has run out; weather and GPS help stop working until it gets one. [answered] is true when
     * the phone answered with a made-up sign-in (the per-watch setting is on). Added in core-model v1.4.
     */
    public data class GarminSignInRequested(val answered: Boolean) : WatchEvent

    /**
     * A live heart-rate reading ([bpm]) while [WatchCommand.LiveData] is on, with the watch's resting heart
     * rate for today when it sent one. Added in core-model v1.3.
     */
    public data class LiveHeartRate(val bpm: Int, val restingBpm: Int?) : WatchEvent

    /**
     * The watch's step count for today ([steps]) and its step goal ([goal], null when unknown) while
     * [WatchCommand.LiveData] is on. Added in core-model v1.3.
     */
    public data class LiveSteps(val steps: Int, val goal: Int?) : WatchEvent
}

/** Connect IQ (Monkey C) value as it travels in app messages and settings. */
public sealed interface CiqValue {
    /** Monkey C `null`. */
    public data object Null : CiqValue

    /** Boolean. */
    public data class Bool(val v: Boolean) : CiqValue

    /** Integer (Number or Long). */
    public data class Num(val v: Long) : CiqValue

    /** Floating point (Float or Double). */
    public data class Dbl(val v: Double) : CiqValue

    /** String. */
    public data class Str(val v: String) : CiqValue

    /** Array. */
    public data class Arr(val v: List<CiqValue>) : CiqValue

    /** Dictionary. */
    public data class Dict(val v: Map<CiqValue, CiqValue>) : CiqValue
}

/**
 * A phone notification to mirror on the watch.
 *
 * @property actions labels of the notification's action buttons.
 */
public data class PhoneNotification(
    val id: Int,
    val appPackage: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val tSec: Long,
    val category: NotificationCategory,
    val canReply: Boolean,
    val actions: List<String>,
)

/** Notification categories the watch shows icons for. */
public enum class NotificationCategory { CALL, MESSAGE, EMAIL, CALENDAR, SOCIAL, OTHER }

/** Media buttons on the watch. */
public enum class MusicAction { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }

/** The phone's now-playing state. */
public data class MusicState(
    val playing: Boolean,
    val artist: String?,
    val album: String?,
    val title: String?,
    val durationMs: Long?,
    val positionMs: Long?,
    val volumePct: Int?,
)

/**
 * Current weather.
 *
 * @property tempC10 temperature, °C ×10.
 * @property windKmhX10 wind speed, km/h ×10.
 * @property windDeg wind direction, degrees from north.
 */
public data class WeatherNow(
    val tSec: Long,
    val tempC10: Int,
    val feelsLikeC10: Int?,
    val condition: WeatherCondition,
    val humidityPct: Int?,
    val windKmhX10: Int?,
    val windDeg: Int?,
    val location: String?,
)

/** One forecast day; temperatures in °C ×10. */
public data class WeatherDay(
    val day: Day,
    val minC10: Int,
    val maxC10: Int,
    val condition: WeatherCondition,
    val precipPct: Int?,
)

/** Weather conditions the watch can show. */
public enum class WeatherCondition { CLEAR, PARTLY_CLOUDY, CLOUDY, RAIN, SHOWERS, THUNDER, SNOW, SLEET, FOG, WINDY, HAZE, UNKNOWN }

/** A watch alarm; repeats on [days], or once if [days] is empty. */
public data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek>,
    val enabled: Boolean,
    val label: String?,
)

/** An app installed on the watch. */
public data class WatchApp(val uuid: UUID, val name: String, val version: Int?, val kind: WatchAppKind)

/** Kinds of Connect IQ app. */
public enum class WatchAppKind { APP, WIDGET, WATCH_FACE, DATA_FIELD, OTHER }
