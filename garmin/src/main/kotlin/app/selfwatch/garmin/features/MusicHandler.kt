/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.gfdi.FeatureReplies
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.GfdiStatus
import app.selfwatch.garmin.gfdi.MusicCommand
import app.selfwatch.garmin.gfdi.MusicControl
import app.selfwatch.garmin.gfdi.MusicControlCapabilities
import app.selfwatch.garmin.gfdi.MusicEntity
import app.selfwatch.garmin.gfdi.MusicEntityId as E
import app.selfwatch.garmin.gfdi.MusicEntityUpdate
import app.selfwatch.garmin.gfdi.utf8Prefix
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.model.device.Feature
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.MusicAction
import app.selfwatch.model.watch.MusicState
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import java.util.Locale

/**
 * Music control (A17), only with `BASIC_MUSIC_CONTROLS` or `ADVANCED_MUSIC_CONTROLS`.
 *
 * - MUSIC_CONTROL_CAPABILITIES from the watch: answered with every command we understand.
 * - MUSIC_CONTROL from the watch: ACK, then [WatchEvent.MusicControl]. Skip forward/backwards within a
 *   track have no [MusicAction] and are only logged.
 * - [WatchCommand.Music]: one MUSIC_CONTROL_ENTITY_UPDATE with artist, album, title, duration (seconds),
 *   playback info `playing,rate,positionSec` and, when known, volume 0.00..1.00.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/GarminSupport.java (onSetMusicInfo, sendMusicState with
 * "%d,%.1f,%.3f", onSetPhoneVolume with "%.2f"), messages/MusicControl*.java
 */
public class MusicHandler : GfdiHandler {
    private fun supported(session: GarminSessionContext): Boolean =
        GarminCapability.BASIC_MUSIC_CONTROLS in session.capabilities || GarminCapability.ADVANCED_MUSIC_CONTROLS in session.capabilities

    override suspend fun handle(m: GfdiMessage, session: GarminSessionContext): Boolean {
        if ((m !is MusicControl && m !is MusicControlCapabilities) || !supported(session)) return false
        when (m) {
            is MusicControlCapabilities -> session.channel.reply(
                m.id, GfdiStatus.ACK, FeatureReplies.MusicCapabilities((0 until MusicCommand.COUNT).toList()).encode(),
            )
            is MusicControl -> {
                session.channel.reply(m.id)
                val action = actionOf(m.command)
                if (action == null) SwLog.i(TAG, "music command ${m.command} has no app action") else session.emit(WatchEvent.MusicControl(action))
            }
        }
        return true
    }

    override suspend fun command(cmd: WatchCommand, session: GarminSessionContext): Outcome<Unit>? {
        if (cmd !is WatchCommand.Music) return null
        if (!supported(session)) return Outcome.Fail(SwError.Unsupported(Feature.MUSIC))
        return session.channel.send(update(cmd.state))
    }

    /** Public mapping helpers. */
    public companion object {
        private const val TAG = "garmin.music"

        /** The [MusicAction] for a watch [MusicCommand], or null for skip forward/backwards. */
        public fun actionOf(command: Int): MusicAction? = when (command) {
            MusicCommand.TOGGLE_PLAY_PAUSE -> MusicAction.TOGGLE
            MusicCommand.SKIP_TO_NEXT_ITEM -> MusicAction.NEXT
            MusicCommand.SKIP_TO_PREVIOUS_ITEM -> MusicAction.PREVIOUS
            MusicCommand.VOLUME_UP -> MusicAction.VOLUME_UP
            MusicCommand.VOLUME_DOWN -> MusicAction.VOLUME_DOWN
            MusicCommand.PLAY -> MusicAction.PLAY
            MusicCommand.PAUSE -> MusicAction.PAUSE
            else -> null
        }

        /** The entity update for [s]. */
        public fun update(s: MusicState): MusicEntityUpdate {
            val list = ArrayList<MusicEntity>()
            list.add(MusicEntity(E.TRACK, E.TRACK_ARTIST, text(s.artist)))
            list.add(MusicEntity(E.TRACK, E.TRACK_ALBUM, text(s.album)))
            list.add(MusicEntity(E.TRACK, E.TRACK_TITLE, text(s.title)))
            list.add(MusicEntity(E.TRACK, E.TRACK_DURATION, ((s.durationMs ?: 0L) / 1000).toString()))
            val positionSec = (s.positionMs ?: 0L) / 1000.0
            val info = String.format(Locale.ROOT, "%d,%.1f,%.3f", if (s.playing) 1 else 0, if (s.playing) 1.0 else 0.0, positionSec)
            list.add(MusicEntity(E.PLAYER, E.PLAYER_PLAYBACK_INFO, info))
            s.volumePct?.let { list.add(MusicEntity(E.PLAYER, E.PLAYER_VOLUME, String.format(Locale.ROOT, "%.2f", it.coerceIn(0, 100) / 100.0))) }
            return MusicEntityUpdate(list)
        }

        /** Longest artist, album or title in bytes, so the whole update fits one 375-byte frame. */
        public const val MAX_TEXT_BYTES: Int = 80

        private fun text(s: String?): String = String(utf8Prefix(s ?: "", MAX_TEXT_BYTES), Charsets.UTF_8)
    }
}
