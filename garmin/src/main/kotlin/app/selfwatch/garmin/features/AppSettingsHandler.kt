/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.appmsg.CiqCodec
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.pb.PbHandler
import app.selfwatch.garmin.pb.PbResult
import app.selfwatch.garmin.pb.ProtobufService
import app.selfwatch.garmin.pb.proto.AppSettingsProto
import app.selfwatch.garmin.pb.proto.AppSettingsProto.AppSettingsService
import app.selfwatch.garmin.pb.proto.SmartProto.Smart
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.model.device.Feature
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import com.google.protobuf.ByteString
import java.util.UUID

/**
 * Connect IQ app settings (A22), needs the watch's `CONNECTIQ_SETTINGS` flag.
 *
 * - **Read** ([request]): GetAppSettingsRequest(app UUID). The watch answers GetAppSettingsResponse (status)
 *   and then sends the values in a SendAppSettingsRequest of its own, which becomes [WatchEvent.AppSettings]
 *   (accepted with a "kept" status, as the reference does).
 * - **Write** ([WatchCommand.WriteAppSettings]): SaveAppSettingsRequest(app UUID, values); succeeds when the
 *   watch's SaveAppSettingsResponse says SUCCESS.
 *
 * Values are a Monkey C serialized dictionary ([CiqCodec]) of setting key (string) → value. The reference
 * only writes booleans, numbers and strings; every [CiqValue] type encodes, but apps' settings use those three.
 * Entries with a non-string key are dropped when reading.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/AppConfigHandler.kt (get/save requests, SendAppSettingsRequest
 * with GarminJson values, statuses) and ProtocolBufferHandler.java (app settings messages processed, no reply)
 */
public class AppSettingsHandler(
    private val protobuf: ProtobufService,
    private val timeoutMs: Long = 10_000,
) : GfdiHandler {
    private val holder = SessionHolder()

    /** Accepts the watch's settings values (SendAppSettingsRequest) and stray responses. */
    public val pb: PbHandler = PbHandler { smart, _, session ->
        if (!smart.hasConnectIqAppSettingsService()) return@PbHandler null
        val s = smart.connectIqAppSettingsService
        when {
            s.hasSendAppSettingsRequest() -> onValues(s.sendAppSettingsRequest, session)
            s.hasSaveAppSettingsResponse() -> SwLog.i(TAG, "settings saved: ${s.saveAppSettingsResponse.response}")
            s.hasGetAppSettingsResponse() -> SwLog.i(TAG, "settings read: ${s.getAppSettingsResponse.response}")
            else -> return@PbHandler null
        }
        PbResult.Done
    }

    override suspend fun handle(m: GfdiMessage, session: GarminSessionContext): Boolean {
        holder.set(session)
        return false
    }

    override suspend fun command(cmd: WatchCommand, session: GarminSessionContext): Outcome<Unit>? {
        if (cmd !is WatchCommand.WriteAppSettings) return null
        holder.set(session)
        return write(session, cmd.appUuid, cmd.values)
    }

    /**
     * Asks the connected watch for [app]'s settings. Ok means the watch accepted; the values follow as
     * [WatchEvent.AppSettings].
     */
    public suspend fun request(app: UUID): Outcome<Unit> = when (val s = holder.live()) {
        is Outcome.Ok -> request(s.value, app)
        is Outcome.Fail -> s
    }

    /** As [request], on [session]. */
    public suspend fun request(session: GarminSessionContext, app: UUID): Outcome<Unit> {
        if (GarminCapability.CONNECTIQ_SETTINGS !in session.capabilities) return Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_SETTINGS))
        val req = AppSettingsService.newBuilder().setGetAppSettingsRequest(
            AppSettingsProto.GetAppSettingsRequest.newBuilder().setAppIdentifier(UuidBytes.toBytes(app)),
        )
        val r = when (val rr = protobuf.request(session, Smart.newBuilder().setConnectIqAppSettingsService(req).build(), timeoutMs)) {
            is Outcome.Ok -> rr.value.connectIqAppSettingsService
            is Outcome.Fail -> return rr
        }
        val status = if (r.hasGetAppSettingsResponse()) r.getAppSettingsResponse.response else null
        return when (status) {
            AppSettingsProto.GetAppSettingsResponse.Response.SUCCESS -> Outcome.Ok(Unit)
            AppSettingsProto.GetAppSettingsResponse.Response.APP_NOT_INSTALLED -> Outcome.Fail(SwError.BadInput("app $app is not installed"))
            else -> Outcome.Fail(SwError.Protocol("settings read for $app failed ($status)"))
        }
    }

    private suspend fun write(session: GarminSessionContext, app: UUID, values: Map<String, CiqValue>): Outcome<Unit> {
        if (GarminCapability.CONNECTIQ_SETTINGS !in session.capabilities) return Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_SETTINGS))
        val dict = CiqValue.Dict(values.entries.associate { (k, v) -> CiqValue.Str(k) to v })
        val req = AppSettingsService.newBuilder().setSaveAppSettingsRequest(
            AppSettingsProto.SaveAppSettingsRequest.newBuilder().setAppIdentifier(UuidBytes.toBytes(app))
                .setSettingsValues(ByteString.copyFrom(CiqCodec.encode(dict))),
        )
        val r = when (val rr = protobuf.request(session, Smart.newBuilder().setConnectIqAppSettingsService(req).build(), timeoutMs)) {
            is Outcome.Ok -> rr.value.connectIqAppSettingsService
            is Outcome.Fail -> return rr
        }
        val status = if (r.hasSaveAppSettingsResponse()) r.saveAppSettingsResponse.response else null
        return if (status == AppSettingsProto.SaveAppSettingsResponse.Response.SUCCESS) {
            Outcome.Ok(Unit)
        } else {
            Outcome.Fail(SwError.Protocol("settings write for $app failed ($status)"))
        }
    }

    private fun onValues(m: AppSettingsProto.SendAppSettingsRequest, session: GarminSessionContext) {
        val app = UuidBytes.fromBytes(m.appIdentifier) ?: run {
            SwLog.w(TAG, "settings with a bad app id")
            return
        }
        val decoded = if (m.settingsValues.isEmpty) Outcome.Ok(CiqValue.Dict(emptyMap())) else CiqCodec.decode(m.settingsValues.toByteArray())
        val dict = (decoded as? Outcome.Ok)?.value as? CiqValue.Dict ?: run {
            SwLog.w(TAG, "settings for $app are not a dictionary: $decoded")
            return
        }
        val values = LinkedHashMap<String, CiqValue>()
        for ((k, v) in dict.v) if (k is CiqValue.Str) values[k.v] = v
        session.emit(WatchEvent.AppSettings(app, values))
    }

    private companion object {
        const val TAG = "garmin.settings"
    }
}
