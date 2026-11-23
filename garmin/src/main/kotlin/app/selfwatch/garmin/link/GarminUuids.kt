/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.link

import java.util.UUID

/** Bluetooth GATT UUIDs of the Garmin protocol generations. */
public object GarminUuids {
    private fun g(prefix: String): UUID = UUID.fromString("$prefix-667b-11e3-949a-0800200c9a66")

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/v2/CommunicatorV2.java (ML UUIDs)
    /** V2 multi-link service. */
    public val ML_SERVICE: UUID = g("6a4e2800")

    /** V2 receive (notify) characteristics, tried in order. */
    public val ML_RX: List<UUID> = (0x2810..0x2814).map { g("6a4e%04x".format(it)) }

    /** V2 send (write) characteristics; `ML_TX[i]` pairs with `ML_RX[i]` (receive + 0x10). */
    public val ML_TX: List<UUID> = (0x2820..0x2824).map { g("6a4e%04x".format(it)) }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/v1/CommunicatorV1.java (V0/V1 UUIDs)
    /** V1 service. */
    public val V1_SERVICE: UUID = g("6a4e2401")

    /** V1 send (write) characteristic. */
    public val V1_TX: UUID = g("6a4e4c80")

    /** V1 receive (notify) characteristic. */
    public val V1_RX: UUID = g("6a4ecd28")

    /** V0 service (oldest watches; not supported). */
    public val V0_SERVICE: UUID = UUID.fromString("9b012401-bc30-ce9a-e111-0f67e491abde")

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/GarminWatchAppCommunicator.java (watch-app service)
    /** Connect IQ watch-app message service; uses the V1 characteristic UUIDs under its own service. */
    public val WATCH_APP_SERVICE: UUID = g("6a4e8022")

    /** Watch-app send (write) characteristic. */
    public val WATCH_APP_TX: UUID = V1_TX

    /** Watch-app receive (notify) characteristic. */
    public val WATCH_APP_RX: UUID = V1_RX
}
