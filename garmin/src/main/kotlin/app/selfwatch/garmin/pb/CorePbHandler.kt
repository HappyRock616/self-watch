/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.pb

import app.selfwatch.garmin.pb.proto.CoreProto
import app.selfwatch.garmin.pb.proto.CredentialsProto
import app.selfwatch.garmin.pb.proto.DeviceStatusProto
import app.selfwatch.garmin.pb.proto.SmartProto.Smart
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.watch.WatchEvent
import java.security.SecureRandom
import java.util.UUID

/**
 * The always-on protobuf services: core (connection ready, sync status, location), device status
 * (battery over protobuf) and Garmin Connect sign-in requests (COMPATIBILITY R1).
 *
 * - Connection-ready notification and sync response: noted, no answer (the reference found that some
 *   firmware misbehaves when this gets a response).
 * - Location request: answered "no valid location" (the module has no location source; a later task
 *   may pass one in).
 * - Battery status response: becomes [WatchEvent.Battery].
 * - Sign-in (OAuth) request: answered with fake credentials only when the session's auth policy allows it
 *   (`garmin.fake_auth`); otherwise left unhandled, which the service reports as "unknown request",
 *   exactly as the reference does with fake OAuth off.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/ProtocolBufferHandler.java (processProtobufCoreRequest,
 * processProtobufDeviceStatusResponse, the gc_oauth_request branch)
 */
public class CorePbHandler : PbHandler {
    private val random = SecureRandom()

    override suspend fun onMessage(smart: Smart, isResponse: Boolean, session: GarminSessionContext): PbResult? {
        if (smart.hasCoreService()) return core(smart.coreService)
        if (smart.hasDeviceStatusService()) {
            val ds = smart.deviceStatusService
            if (ds.hasBatteryStatusResponse()) {
                val b = ds.batteryStatusResponse
                SwLog.i(TAG, "battery over protobuf: ${b.status}, ${b.currentBatteryLevel}%")
                if (b.status == DeviceStatusProto.BatteryStatusResponse.Status.OK && b.hasCurrentBatteryLevel() &&
                    b.currentBatteryLevel in 0..100
                ) {
                    session.emit(WatchEvent.Battery(b.currentBatteryLevel))
                }
            }
            return PbResult.Done
        }
        if (smart.hasCredentialsService() && smart.credentialsService.hasGcOauthRequest()) {
            if (!session.auth.allowFakeOAuth) {
                SwLog.w(TAG, "watch asked for Garmin Connect sign-in; fake sign-in is off (garmin.fake_auth)")
                return null
            }
            SwLog.w(TAG, "answering Garmin Connect sign-in with fake credentials (garmin.fake_auth is on)")
            val creds = CredentialsProto.OAuthCredentials.newBuilder()
                .setConsumerKey(UUID.randomUUID().toString())
                .setConsumerSecret(randomText(35))
                .setOauthToken(UUID.randomUUID().toString())
                .setOauthSecret(randomText(35))
            val response = CredentialsProto.OAuthResponse.newBuilder()
                .setOauthCredentials(creds)
                .setLocation(CredentialsProto.ServerLocation.PRODUCTION)
            return PbResult.Reply(
                Smart.newBuilder()
                    .setCredentialsService(CredentialsProto.CredentialsService.newBuilder().setGcOauthResponse(response))
                    .build(),
            )
        }
        return null
    }

    private fun core(core: CoreProto.CoreService): PbResult? = when {
        core.hasConnectionReadyNotification() -> {
            SwLog.i(TAG, "watch says the connection is ready")
            PbResult.Done
        }
        core.hasSyncResponse() -> {
            SwLog.i(TAG, "sync status: ${core.syncResponse.status}")
            PbResult.Done
        }
        core.hasGetLocationRequest() -> PbResult.Reply(
            Smart.newBuilder().setCoreService(
                CoreProto.CoreService.newBuilder().setGetLocationResponse(
                    CoreProto.GetLocationResponse.newBuilder().setStatus(CoreProto.GetLocationResponse.Status.NO_VALID_LOCATION),
                ),
            ).build(),
        )
        else -> null
    }

    private fun randomText(n: Int): String = buildString(n) { repeat(n) { append(ALPHANUM[random.nextInt(ALPHANUM.length)]) } }

    private companion object {
        const val TAG = "garmin.pb"
        const val ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
