/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.session

import app.selfwatch.garmin.files.FileTransfers
import app.selfwatch.garmin.gfdi.GfdiChannel
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.GfdiReplies
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import kotlinx.coroutines.CoroutineScope

/** Garmin session states (WP-06 §3). */
public enum class SessionState { DISCONNECTED, LINK_UP, INFO_EXCHANGED, CONFIGURED, READY, SYNCING }

/**
 * Driver settings. Defaults are the safe choices for a first live test.
 *
 * @property reliableLink ask for the multi-link reliable layer (the reference's default).
 * @property advertisedCapabilities capability flags we send in CONFIGURATION.
 * @property fakeAuth setting `garmin.fake_auth` (R1): fake Garmin sign-in replies. Off by default.
 * @property timeSync answer time requests and send TIME_UPDATED on connect.
 * @property firstConnect send the pairing system events (pair complete, sync complete, setup wizard complete).
 * @property archiveAfterIngest set the archive flag after a file is safely stored.
 * @property handshakeTimeoutMs fail the connection if READY is not reached by then.
 */
public data class GarminConfig(
    val reliableLink: Boolean = true,
    val advertisedCapabilities: Set<GarminCapability> = GarminCapability.SELF_WATCH,
    val fakeAuth: Boolean = false,
    val timeSync: Boolean = true,
    val firstConnect: Boolean = false,
    val syncOnConnect: Boolean = true,
    val archiveAfterIngest: Boolean = true,
    val phoneBluetoothName: String = "Self Watch",
    val manufacturer: String = "Self Watch",
    val model: String = "Android",
    val handshakeTimeoutMs: Long = 30_000,
    val requestTimeoutMs: Long = 10_000,
    val chunkTimeoutMs: Long = 15_000,
)

/**
 * Auth replies (COMPATIBILITY R1) and secure-pairing detection (R2).
 *
 * AUTH_NEGOTIATION gets the reference's minimal reply: ACK, "guess OK", the watch's own byte and flags
 * echoed. Garmin Connect credential/OAuth protobuf requests (handled in `pb/`, A15) must be answered
 * "not available" unless [allowFakeOAuth]; that is off unless the user turns on `garmin.fake_auth`.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/messages/AuthNegotiationMessage.java (status message built in
 * the constructor) and ProtocolBufferHandler.java (fake OAuth only when the preference is on).
 */
public class AuthPolicy(private val fakeAuth: Boolean) {
    /** The status body for an AUTH_NEGOTIATION. */
    public fun negotiationReply(m: GfdiMessage.AuthNegotiation): GfdiReplies.AuthReply =
        GfdiReplies.AuthReply(GfdiReplies.AuthReply.GUESS_OK, m.unknown, m.flags)

    /** True only when fake Garmin sign-in replies are allowed (default false). */
    public val allowFakeOAuth: Boolean get() = fakeAuth
}

/** What a feature handler can use from the live session. */
public interface GarminSessionContext {
    /** The message channel. */
    public val channel: GfdiChannel

    /** The watch's capability flags (empty before CONFIGURATION). */
    public val capabilities: Set<GarminCapability>

    /** The current state. */
    public val sessionState: SessionState

    /** Auth policy for credential requests. */
    public val auth: AuthPolicy

    /** Publishes an event to the app. */
    public fun emit(event: WatchEvent)

    /** The connection's scope: work launched here ends with the connection. */
    public val scope: CoroutineScope

    /** File transfers (uploads for GPS assistance and apps); its `maxPacketSize` is the watch's frame limit. */
    public val transfers: FileTransfers
}

/**
 * Extension point for features (notifications, music, weather, protobuf services, ...; A15–A24).
 * Handlers see each incoming message before the session's defaults; a message nobody handles gets
 * an UNSUPPORTED status reply. Commands go to [command] in handler order before the driver's own.
 *
 * Handlers live as long as the driver and see many connections: per-connection state must be reset
 * when [session] is a different object than last time.
 */
public interface GfdiHandler {
    /** Handles [m] and returns true if it did (and sent any reply itself). */
    public suspend fun handle(m: GfdiMessage, session: GarminSessionContext): Boolean

    /** Carries out [cmd] if this handler owns it; null means "not mine". */
    public suspend fun command(cmd: WatchCommand, session: GarminSessionContext): Outcome<Unit>? = null

    /**
     * Called once per connection when the session reaches READY (in its own coroutine on the session scope),
     * for work the reference does on connect, such as asking for the installed apps.
     */
    public suspend fun onReady(session: GarminSessionContext) {}
}
