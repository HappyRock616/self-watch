/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.watch.MusicAction
import app.selfwatch.model.watch.MusicState
import app.selfwatch.model.watch.WatchCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Turns media-session data into [MusicState]. */
public object MusicMapping {
    /** True for the playback states the watch shows as playing. */
    public fun isPlaying(state: Int?): Boolean = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

    /**
     * The now-playing state. Negative or zero durations and negative positions count as unknown;
     * [volume] of [maxVolume] becomes a percentage.
     */
    public fun state(
        playbackState: Int?,
        artist: String?,
        album: String?,
        title: String?,
        durationMs: Long?,
        positionMs: Long?,
        volume: Int?,
        maxVolume: Int?,
    ): MusicState = MusicState(
        playing = isPlaying(playbackState),
        artist = artist?.takeIf { it.isNotBlank() },
        album = album?.takeIf { it.isNotBlank() },
        title = title?.takeIf { it.isNotBlank() },
        durationMs = durationMs?.takeIf { it > 0 },
        positionMs = positionMs?.takeIf { it >= 0 },
        volumePct = if (volume != null && maxVolume != null && maxVolume > 0) (volume * 100 / maxVolume).coerceIn(0, 100) else null,
    )
}

/**
 * Sends at most one update per [intervalMs]; changes in between are merged so the latest state is
 * sent at the end of the interval. Identical states are not sent twice in a row.
 */
public class MusicThrottle(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val send: suspend (MusicState) -> Unit,
    private val intervalMs: Long = INTERVAL_MS,
) {
    private val lock = Any()
    private var latest: MusicState? = null
    private var lastSent: MusicState? = null
    private var lastSentAt = Long.MIN_VALUE / 2
    private var pending: Job? = null

    /** Queues [state] to be sent. */
    public fun offer(state: MusicState) {
        synchronized(lock) {
            latest = state
            if (pending?.isActive == true) return
            val wait = (lastSentAt + intervalMs - nowMs()).coerceAtLeast(0)
            pending = scope.launch {
                if (wait > 0) delay(wait)
                val s = synchronized(lock) {
                    val l = latest
                    if (l == null || l == lastSent) {
                        null
                    } else {
                        lastSent = l
                        lastSentAt = nowMs()
                        l
                    }
                }
                if (s != null) send(s)
            }
        }
    }

    /** Defaults. */
    public companion object {
        /** One update per second (WP-07). */
        public const val INTERVAL_MS: Long = 1_000
    }
}

/**
 * The active media session to and from the watch (B21). Created by [NotificationRelay] while it is
 * bound (the session list needs the listener component). Metadata, playback and volume changes go
 * out as [WatchCommand.Music] through [RelayHub], throttled by [MusicThrottle]; buttons from the
 * watch go to the controller's transport controls, volume to [AudioManager.adjustStreamVolume].
 */
public class MusicRelay(
    private val ctx: Context,
    private val component: ComponentName,
    scope: CoroutineScope,
    private val send: suspend (WatchCommand) -> Unit = { RelayHub.sendToAll(it) },
) {
    private val main = Handler(Looper.getMainLooper())
    private val audio: AudioManager? = ctx.getSystemService(AudioManager::class.java)
    private val throttle = MusicThrottle(scope, { android.os.SystemClock.elapsedRealtime() }, { send(WatchCommand.Music(it)) })

    @Volatile
    private var active: MediaController? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> choose(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()

        override fun onPlaybackStateChanged(state: PlaybackState?) = push()

        override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) = push()

        override fun onSessionDestroyed() {
            choose(sessions())
        }
    }

    /** Starts following the active sessions. Without notification access it logs and does nothing. */
    public fun start() {
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            choose(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            SwLog.w(TAG, "no access to media sessions", e)
        }
    }

    /** Stops following sessions. */
    public fun stop() {
        try {
            ctx.getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: SecurityException) {
            SwLog.w(TAG, "removing the sessions listener failed", e)
        }
        active?.unregisterCallback(callback)
        active = null
    }

    private fun sessions(): List<MediaController> = try {
        ctx.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(component).orEmpty()
    } catch (e: SecurityException) {
        SwLog.w(TAG, "no access to media sessions", e)
        emptyList()
    }

    private fun choose(list: List<MediaController>) {
        val pick = list.firstOrNull { MusicMapping.isPlaying(it.playbackState?.state) } ?: list.firstOrNull()
        val old = active
        if (old?.sessionToken == pick?.sessionToken) return
        old?.unregisterCallback(callback)
        active = pick
        pick?.registerCallback(callback, main)
        push()
    }

    private fun push() {
        val c = active ?: return
        val md = c.metadata
        val info = c.playbackInfo
        val local = info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL
        val volume = if (local) audio?.getStreamVolume(AudioManager.STREAM_MUSIC) else info.currentVolume
        val max = if (local) audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) else info.maxVolume
        throttle.offer(
            MusicMapping.state(
                playbackState = c.playbackState?.state,
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION),
                positionMs = c.playbackState?.position,
                volume = volume,
                maxVolume = max,
            ),
        )
    }

    /** Presses [action]; false when no session is active (volume works without one). */
    public fun control(action: MusicAction): Boolean {
        when (action) {
            MusicAction.VOLUME_UP -> return volume(AudioManager.ADJUST_RAISE)
            MusicAction.VOLUME_DOWN -> return volume(AudioManager.ADJUST_LOWER)
            else -> Unit
        }
        val c = active ?: sessions().firstOrNull()?.also { choose(listOf(it)) } ?: return false
        val t = c.transportControls
        when (action) {
            MusicAction.PLAY -> t.play()
            MusicAction.PAUSE -> t.pause()
            MusicAction.TOGGLE -> if (MusicMapping.isPlaying(c.playbackState?.state)) t.pause() else t.play()
            MusicAction.NEXT -> t.skipToNext()
            MusicAction.PREVIOUS -> t.skipToPrevious()
            MusicAction.VOLUME_UP, MusicAction.VOLUME_DOWN -> Unit
        }
        return true
    }

    private fun volume(direction: Int): Boolean {
        val c = active
        val info = c?.playbackInfo
        if (c != null && info != null && info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
            c.adjustVolume(direction, 0)
        } else {
            val a = audio ?: return false
            a.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        }
        push()
        return true
    }

    private companion object {
        const val TAG = "MusicRelay"
    }
}
