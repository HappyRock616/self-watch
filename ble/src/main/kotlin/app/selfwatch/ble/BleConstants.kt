/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble

import java.util.UUID

/** Client Characteristic Configuration Descriptor (turns notifications on and off). */
public const val CCCD: String = "00002902-0000-1000-8000-00805f9b34fb"

/** [CCCD] as a [UUID]. */
public val CCCD_UUID: UUID = UUID.fromString(CCCD)

/** Packet size (ATT MTU) asked for after connecting. */
public const val MTU_REQUEST: Int = 517

/** ATT MTU before negotiation. */
public const val DEFAULT_MTU: Int = 23

/** Longest attribute value, and so longest single write, that GATT allows whatever the MTU. */
public const val MAX_ATTRIBUTE_LENGTH: Int = 512

/** Bytes per write when the MTU was never negotiated ([DEFAULT_MTU] − 3). */
public const val DEFAULT_MAX_WRITE: Int = DEFAULT_MTU - 3

/** Longest wait for one GATT operation's callback. */
public const val OP_TIMEOUT_MS: Long = 10_000L

/** Longest wait for a direct (non-auto) connection to come up. */
public const val CONNECT_TIMEOUT_MS: Long = 30_000L

/** Wait after the link comes up before service discovery (Samsung needs it). */
public const val DISCOVERY_DELAY_MS: Long = 600L

/** Longest wait for service discovery; retried once. */
public const val DISCOVERY_TIMEOUT_MS: Long = 15_000L

/** Longest wait for the user to confirm bonding (a passkey may be shown on the watch). */
public const val BOND_TIMEOUT_MS: Long = 60_000L

/** Reconnect delays in seconds; the last one repeats. Reset after a successful connection. */
public val BACKOFF_SEC: IntArray get() = intArrayOf(1, 2, 5, 10, 30, 60, 300)

/** Garmin's Bluetooth SIG company identifier, found in its advertisements' manufacturer data. */
public const val GARMIN_MANUFACTURER_ID: Int = 0x0087
