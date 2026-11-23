/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.link

import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.ble.BytePipe
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.CoroutineScope

/** Picks the Garmin link generation the watch offers and opens a [GfdiTransport] on it. */
public object LinkSelector {
    private const val TAG = "garmin.link"

    /**
     * Opens the GFDI link on [port]: V2 multi-link when the watch has it (registering GFDI, with the
     * reliable layer when [reliable]), else V1. V0 watches are refused. Reader coroutines run in [scope].
     */
    public suspend fun open(port: BlePort, scope: CoroutineScope, reliable: Boolean = true): Outcome<GfdiTransport> {
        val services = port.serviceUuids()
        return when {
            GarminUuids.ML_SERVICE in services -> openV2(port, scope, reliable)
            GarminUuids.V1_SERVICE in services -> when (val p = port.openPipe(GarminUuids.V1_SERVICE, GarminUuids.V1_RX, GarminUuids.V1_TX)) {
                is Outcome.Ok -> {
                    SwLog.i(TAG, "using V1 link")
                    Outcome.Ok(V1Link(p.value, scope))
                }
                is Outcome.Fail -> p
            }
            GarminUuids.V0_SERVICE in services -> Outcome.Fail(SwError.Protocol("this watch uses the oldest (V0) Garmin protocol, which is not supported"))
            else -> Outcome.Fail(SwError.Protocol("no Garmin service found"))
        }
    }

    private suspend fun openV2(port: BlePort, scope: CoroutineScope, reliable: Boolean): Outcome<GfdiTransport> {
        val pipe = openMlPipe(port) ?: return Outcome.Fail(SwError.Protocol("no usable multi-link characteristics"))
        val link = MultiLink(pipe, scope)
        val started = link.start()
        if (started is Outcome.Fail) {
            link.close()
            return started
        }
        return when (val ch = link.openService(MlService.GFDI, reliable)) {
            is Outcome.Ok -> {
                SwLog.i(TAG, "using V2 multi-link, reliable=${ch.value.reliable}")
                Outcome.Ok(MultiLinkGfdiTransport(link, ch.value, scope))
            }
            is Outcome.Fail -> {
                link.close()
                ch
            }
        }
    }

    // Ref: Gadgetbridge 160beab41 service/devices/garmin/communicator/v2/CommunicatorV2.java (first existing
    // rx/tx pair; fallback to a single bidirectional 281x characteristic)
    private suspend fun openMlPipe(port: BlePort): BytePipe? {
        for (i in GarminUuids.ML_RX.indices) {
            val r = port.openPipe(GarminUuids.ML_SERVICE, GarminUuids.ML_RX[i], GarminUuids.ML_TX[i])
            if (r is Outcome.Ok) return r.value
        }
        for (rx in GarminUuids.ML_RX) {
            val r = port.openPipe(GarminUuids.ML_SERVICE, rx, rx)
            if (r is Outcome.Ok) return r.value
        }
        return null
    }
}
