/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.link

import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.flow.Flow

/** Which Garmin link generation is in use. */
public enum class LinkVersion { V1, V2 }

/**
 * Carries whole GFDI frames (length, type, payload, CRC; not COBS-encoded) to and from the watch.
 * The link below handles COBS, fragmentation, multi-link handles and the reliable layer.
 */
public interface GfdiTransport {
    /** Decoded GFDI frames from the watch, in order. Completes when the link closes. */
    public val incoming: Flow<ByteArray>

    /** Sends one GFDI frame. */
    public suspend fun send(frame: ByteArray): Outcome<Unit>

    /** Largest single Bluetooth write on this link, in bytes. */
    public val maxPacket: Int

    /** The link generation. */
    public val version: LinkVersion

    /** True when frames go through the multi-link reliable layer (MLR). */
    public val reliable: Boolean

    /** Closes the link and its pipes; [incoming] completes. */
    public suspend fun close()
}
