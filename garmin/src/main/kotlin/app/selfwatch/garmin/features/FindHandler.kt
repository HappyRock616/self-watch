/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.gfdi.FindMyPhoneCancel
import app.selfwatch.garmin.gfdi.FindMyPhoneRequest
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.pb.PbHandler
import app.selfwatch.garmin.pb.PbResult
import app.selfwatch.garmin.pb.ProtobufService
import app.selfwatch.garmin.pb.proto.FindMyWatchProto
import app.selfwatch.garmin.pb.proto.SmartProto.Smart
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.model.device.Feature
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent

/**
 * Find my phone and find my watch (A19).
 *
 * - FIND_MY_PHONE_REQUEST / CANCEL (5039/5040, needs `FIND_MY_PHONE`): ACK, then [WatchEvent.FindPhone].
 * - [WatchCommand.FindWatch] (needs `FIND_MY_WATCH`): a find-my-watch protobuf request, ringing for
 *   [ringSeconds], or a cancel request. Sent without waiting for the answer, as the reference does; the
 *   watch's answers (and its own "found" cancel) are accepted by [pb].
 *
 * Ref: Gadgetbridge 160beab41 messages/FindMyPhoneRequestMessage.java, FindMyPhoneCancelMessage.java,
 * GarminSupport.java (onFindDevice: FindMyWatchRequest timeout 60 / FindMyWatchCancelRequest),
 * ProtocolBufferHandler.java (processProtobufFindMyWatchResponse)
 */
public class FindHandler(
    private val protobuf: ProtobufService,
    private val ringSeconds: Int = 60,
) : GfdiHandler {
    /** Accepts find-my-watch messages from the watch (responses, and the watch's own cancel when found). */
    public val pb: PbHandler = PbHandler { smart, _, _ ->
        if (!smart.hasFindMyWatchService()) return@PbHandler null
        val f = smart.findMyWatchService
        when {
            f.hasCancelRequest() -> SwLog.i(TAG, "watch found")
            f.hasFindResponse() -> SwLog.i(TAG, "find my watch: ${f.findResponse.status}")
            f.hasCancelResponse() -> SwLog.i(TAG, "find my watch cancelled: ${f.cancelResponse.status}")
        }
        PbResult.Done
    }

    override suspend fun handle(m: GfdiMessage, session: GarminSessionContext): Boolean {
        if ((m !is FindMyPhoneRequest && m !is FindMyPhoneCancel) || GarminCapability.FIND_MY_PHONE !in session.capabilities) return false
        session.channel.reply(m.id)
        session.emit(WatchEvent.FindPhone(m is FindMyPhoneRequest))
        return true
    }

    override suspend fun command(cmd: WatchCommand, session: GarminSessionContext): Outcome<Unit>? {
        if (cmd !is WatchCommand.FindWatch) return null
        if (GarminCapability.FIND_MY_WATCH !in session.capabilities) return Outcome.Fail(SwError.Unsupported(Feature.FIND_WATCH))
        val service = FindMyWatchProto.FindMyWatchService.newBuilder()
        if (cmd.ring) {
            service.setFindRequest(FindMyWatchProto.FindRequest.newBuilder().setTimeout(ringSeconds))
        } else {
            service.setCancelRequest(FindMyWatchProto.CancelRequest.getDefaultInstance())
        }
        return protobuf.send(session, Smart.newBuilder().setFindMyWatchService(service).build())
    }

    private companion object {
        const val TAG = "garmin.find"
    }
}
