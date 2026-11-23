/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import app.selfwatch.model.log.SwLog

/**
 * The phone's network state as the firewall needs it.
 *
 * @property connected the active network offers internet access.
 * @property unmetered the active network is Wi-Fi or otherwise not metered (allowed for the large
 *   Wi-Fi-only downloads: food data and recipe book).
 */
public data class NetState(val connected: Boolean, val unmetered: Boolean)

/** Reads the current [NetState]. A small seam so tests can fake the network. */
public fun interface Connectivity {
    /** The state right now. Cheap; called once per request. */
    public fun state(): NetState
}

/** [Connectivity] from Android's `ConnectivityManager`. */
public class AndroidConnectivity(ctx: Context) : Connectivity {
    private val cm: ConnectivityManager? =
        ContextCompat.getSystemService(ctx.applicationContext ?: ctx, ConnectivityManager::class.java)

    override fun state(): NetState {
        val manager = cm ?: return NetState(connected = false, unmetered = false)
        return try {
            val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                ?: return NetState(connected = false, unmetered = false)
            NetState(
                connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            )
        } catch (e: SecurityException) {
            // ACCESS_NETWORK_STATE is install-time, but be safe: unknown state, let the socket decide.
            SwLog.w(TAG, "network state not readable", e)
            NetState(connected = true, unmetered = false)
        }
    }

    private companion object {
        const val TAG: String = "Connectivity"
    }
}
