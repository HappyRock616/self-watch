/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.conn

import app.selfwatch.model.result.Outcome
import java.util.UUID

/**
 * Optionally implemented by a `BlePort` that can read a characteristic once (the `ble` module's
 * [BleConnection] does). Drivers check with `(port as? CharacteristicReader)`, so `BlePort` itself
 * stays unchanged.
 */
public interface CharacteristicReader {
    /** True if the connected device has [characteristic] in [service] (after discovery). */
    public suspend fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean

    /** Reads [characteristic] of [service] once; fails when it is missing or the link is down. */
    public suspend fun read(service: UUID, characteristic: UUID): Outcome<ByteArray>
}
