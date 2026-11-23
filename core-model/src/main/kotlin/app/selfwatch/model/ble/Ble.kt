/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.ble

import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** One connected Bluetooth LE device. Implemented by the `ble` module, used by `garmin`. */
public interface BlePort {
    /** Bluetooth MAC address. */
    public val address: String

    /** Link state. */
    public val state: StateFlow<PortState>

    /** GATT service UUIDs the device offers (after discovery). */
    public suspend fun serviceUuids(): Set<UUID>

    /** Opens a byte pipe on [service]: notifications from characteristic [rx], writes to [tx]. */
    public suspend fun openPipe(service: UUID, rx: UUID, tx: UUID): Outcome<BytePipe>

    /** Asks for high connection priority (during sync) or back to balanced. */
    public suspend fun setHighSpeed(on: Boolean)
}

/** State of a [BlePort] link. [CLOSED] is final. */
public enum class PortState { DISCONNECTED, CONNECTING, CONNECTED, CLOSED }

/** Why a [BlePort] reached [PortState.CLOSED]. Added in core-model v1.1. */
public enum class CloseReason {
    /** The app closed the port. */
    CLOSED_BY_APP,

    /** The bond with the watch is gone (keys missing or repeated authentication failures): the user must pair again. */
    REPAIR_NEEDED,

    /** `BLUETOOTH_CONNECT` was revoked; a new connection is needed once it is granted again. */
    PERMISSION_MISSING,
}

/**
 * Optionally implemented by a [BlePort] that can say why it closed (the `ble` module's connection
 * does). Check with `(port as? HasCloseReason)?.closeReason`. Added in core-model v1.1.
 */
public interface HasCloseReason {
    /** Why the port is [PortState.CLOSED]; null while it is not. */
    public val closeReason: StateFlow<CloseReason?>
}

/** A bidirectional byte stream over one GATT notify/write characteristic pair. */
public interface BytePipe {
    /** Bytes per write after MTU negotiation (MTU − 3). */
    public val maxWrite: Int

    /** Notifications, in order, never dropped. */
    public val incoming: Flow<ByteArray>

    /** One GATT write; writes are queued and sent in order. At most [maxWrite] bytes. */
    public suspend fun write(bytes: ByteArray): Outcome<Unit>

    /** Stops notifications and releases the pipe. */
    public suspend fun close()
}
