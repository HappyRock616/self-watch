/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.service.R
import app.selfwatch.service.device.FindPhoneHandler
import app.selfwatch.service.device.WatchLink
import app.selfwatch.service.notify.ServiceNotifications
import java.io.IOException

/** Makes the phone loud: sound and vibration. */
public interface AlertPlayer {
    /** Starts ringing (and vibrating) until [stop]. */
    public fun start()

    /** Stops. Safe to call when not ringing. */
    public fun stop()
}

/**
 * The default alarm sound on the alarm stream ([AudioAttributes.USAGE_ALARM], so it rings in silent
 * and vibrate mode), looping, plus a repeating vibration with alarm usage.
 */
public class AlarmPlayer(private val ctx: Context) : AlertPlayer {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun start() {
        stop()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri != null) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(ctx, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
            } catch (e: IOException) {
                SwLog.w(TAG, "alarm sound unavailable", e)
                mp.release()
            } catch (e: IllegalStateException) {
                SwLog.w(TAG, "alarm sound failed", e)
                mp.release()
            } catch (e: SecurityException) {
                SwLog.w(TAG, "alarm sound not readable", e)
                mp.release()
            }
        }
        vibrate(attrs)
    }

    private fun vibrate(attrs: AudioAttributes) {
        val v = vibrator() ?: return
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(PATTERN, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            vibrateLegacy(v, effect, attrs)
        }
        vibrator = v
    }

    @Suppress("DEPRECATION") // vibrate(effect, AudioAttributes) is the alarm-usage form before API 33.
    private fun vibrateLegacy(v: Vibrator, effect: VibrationEffect, attrs: AudioAttributes) {
        v.vibrate(effect, attrs)
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        legacyVibrator()
    }

    @Suppress("DEPRECATION") // Vibrator system service before VibratorManager (API 31).
    private fun legacyVibrator(): Vibrator? = ctx.getSystemService(Vibrator::class.java)

    override fun stop() {
        player?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                SwLog.d(TAG) { "player already stopped: $e" }
            }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    private companion object {
        const val TAG = "AlarmPlayer"
        val PATTERN = longArrayOf(0, 800, 400)
    }
}

/**
 * Find my phone (B23): a heads-up notification on channel `find_phone` with a Stop action, the
 * alarm sound and vibration. Stops on [WatchEvent.FindPhone] with `start = false`, the Stop action,
 * swiping the notification away, or after [timeoutMs]. No full-screen intent (API 34 limits).
 * Works in silent mode because the sound uses the alarm stream. One alert per process ([active]).
 */
public class FindPhoneAlert(
    private val ctx: Context,
    private val player: AlertPlayer = AlarmPlayer(ctx),
    private val timeoutMs: Long = TIMEOUT_MS,
) : FindPhoneHandler {
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stop() }

    /** True while ringing. */
    @Volatile
    public var ringing: Boolean = false
        private set

    override suspend fun onFindPhone(from: WatchLink, event: WatchEvent.FindPhone) {
        main.post { if (event.start) start() else stop() }
    }

    /** Starts ringing and shows the notification. Call on the main thread. */
    public fun start() {
        active = this
        ServiceNotifications.createChannels(ctx)
        post()
        if (!ringing) player.start()
        ringing = true
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, timeoutMs)
        SwLog.i(TAG, "find my phone: ringing")
    }

    /** Stops ringing and removes the notification. Call on the main thread. */
    public fun stop() {
        main.removeCallbacks(timeout)
        if (ringing) SwLog.i(TAG, "find my phone: stopped")
        ringing = false
        player.stop()
        NotificationManagerCompat.from(ctx).cancel(ServiceNotifications.ID_FIND_PHONE)
    }

    private fun post() {
        if (!ServiceNotifications.canPost(ctx)) return
        val stopIntent = PendingIntent.getBroadcast(
            ctx,
            REQUEST_STOP,
            Intent(ctx, FindPhoneStopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, ServiceNotifications.CHANNEL_FIND_PHONE)
            .setSmallIcon(R.drawable.svc_ic_watch)
            .setContentTitle(ctx.getString(R.string.svc_find_phone_title))
            .setContentText(ctx.getString(R.string.svc_find_phone_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(true)
            .setSilent(true) // the sound comes from AlarmPlayer on the alarm stream
            .setContentIntent(stopIntent)
            .setDeleteIntent(stopIntent)
            .addAction(0, ctx.getString(R.string.svc_find_phone_stop), stopIntent)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ServiceNotifications.ID_FIND_PHONE, n)
        } catch (e: SecurityException) {
            SwLog.w(TAG, "find-phone notification refused", e)
        }
    }

    /** Names and the process's current alert. */
    public companion object {
        /** Action of the Stop button's broadcast. */
        public const val ACTION_STOP: String = "app.selfwatch.service.action.FIND_PHONE_STOP"

        /** Rings at most this long (2 min) if nobody stops it. */
        public const val TIMEOUT_MS: Long = 120_000
        private const val REQUEST_STOP = 3
        private const val TAG = "FindPhoneAlert"

        /** The alert that rang last in this process (holds only the application context). */
        @Volatile
        public var active: FindPhoneAlert? = null
            private set
    }
}

/** The Stop action of [FindPhoneAlert] (not exported; reached only through its immutable PendingIntent). */
public class FindPhoneStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FindPhoneAlert.ACTION_STOP) return
        val a = FindPhoneAlert.active
        if (a != null) a.stop() else NotificationManagerCompat.from(context).cancel(ServiceNotifications.ID_FIND_PHONE)
    }
}
