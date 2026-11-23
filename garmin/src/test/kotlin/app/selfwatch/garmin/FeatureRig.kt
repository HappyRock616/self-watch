/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.selfwatch.garmin

import app.selfwatch.garmin.gfdi.FeatureReplies
import app.selfwatch.garmin.gfdi.GfdiId
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.pb.proto.SmartProto.Smart
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminConfig
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.garmin.session.SessionState
import app.selfwatch.model.fake.FakeBlePort
import app.selfwatch.model.fake.FakeClock
import app.selfwatch.model.fake.FakeFileStore
import app.selfwatch.model.fake.RecordingHealthSink
import app.selfwatch.model.watch.WatchEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Collections
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

/** A connected driver + FakeWatch with the standard [GarminFeatures], for feature flow tests. */
class FeatureRig(
    val watch: FakeWatch,
    val driver: GarminDriver,
    val features: GarminFeatures,
    private val grabber: Grabber,
    val sink: RecordingHealthSink,
    val port: FakeBlePort,
) {
    val events: MutableList<WatchEvent> = Collections.synchronizedList(ArrayList())
    val session: GarminSessionContext get() = grabber.session!!
    fun sent(): List<GfdiMessage> = watch.fromPhone.toList()
    fun replies(id: Int): List<GfdiMessage.Response> = sent().filterIsInstance<GfdiMessage.Response>().filter { it.originalId == id }

    /** Protobuf statuses the phone sent for the watch's PROTOBUF_REQUEST chunks. */
    fun pbStatuses(): List<FeatureReplies.ProtobufStatus> =
        replies(GfdiId.PROTOBUF_REQUEST).filter { it.extra.size > 0 }.map { FeatureReplies.ProtobufStatus.parse(it.extra.toByteArray()) }

    /** Whole PROTOBUF_RESPONSE messages from the phone, as (request id, Smart). */
    fun pbResponses(): List<Pair<Int, Smart>> =
        watch.protobufFromPhone.filter { it.first == GfdiId.PROTOBUF_RESPONSE }.map { it.second to Smart.parseFrom(it.third) }

    /** Whole PROTOBUF_REQUEST messages from the phone. */
    fun pbRequests(): List<Smart> = watch.protobufFromPhone.filter { it.first == GfdiId.PROTOBUF_REQUEST }.map { Smart.parseFrom(it.third) }

    /** Grabs the live session. */
    class Grabber : GfdiHandler {
        @Volatile var session: GarminSessionContext? = null
        override suspend fun handle(m: GfdiMessage, session: GarminSessionContext): Boolean {
            this.session = session
            return false
        }
    }

    companion object {
        const val T0 = 1_795_435_200L

        /**
         * Runs [block] on a READY connection. Time moves only through [settle] (a few virtual seconds),
         * so web-request timeouts ([webTimeoutMs]) fire only when a test waits for them.
         */
        suspend fun TestScope.withRig(
            caps: Set<GarminCapability>,
            maxPacket: Int = 375,
            webTimeoutMs: Long = 600_000,
            files: List<FakeFile> = emptyList(),
            responder: ((ByteArray) -> ByteArray?)? = null,
            config: GarminConfig = GarminConfig(syncOnConnect = false),
            withWatchAppService: Boolean = false,
            block: suspend TestScope.(FeatureRig) -> Unit,
        ) {
            val port = FakeBlePort(maxWrite = 64)
            val watch = FakeWatch(
                backgroundScope, port, FakeWatch.Mode.V2_MLR, files, capabilities = caps + GarminCapability.SYNC,
                maxPacket = maxPacket, withWatchAppService = withWatchAppService,
            )
            watch.protobufResponder = responder
            watch.start()
            val clock = FakeClock(T0)
            val features = GarminFeatures(clock, webResponseTimeoutMs = webTimeoutMs)
            val grabber = Grabber()
            val driver = GarminDriver(7, config, clock, null, FakeDecoders(), EmptyCoroutineContext, listOf(grabber) + features.handlers)
            val sink = RecordingHealthSink()
            val r = FeatureRig(watch, driver, features, grabber, sink, port)
            backgroundScope.launch { driver.events.collect { r.events.add(it) } }
            val job = launch { driver.run(port, sink, FakeFileStore()) }
            withTimeout(60_000) { driver.sessionState.first { it == SessionState.READY } }
            settle()
            block(r)
            port.allPipes().forEach { it.endIncoming() }
            job.join()
        }

        /** Lets queued messages flow (MLR acks and retransmits need a little virtual time). */
        fun TestScope.settle(ms: Long = 3_000) {
            advanceTimeBy(ms)
            runCurrent()
        }
    }
}

/** Builds synthetic Connect IQ `.prg` files (head, entry points, padding sections, end). */
object TestPrg {
    fun build(uuid: UUID, appType: Int, codeBytes: Int = 900, entries: Int = 1, extraSectionsFirst: Boolean = true): ByteArray {
        val bos = ByteArrayOutputStream()
        val o = DataOutputStream(bos)
        fun section(magic: Int, body: ByteArray) {
            o.writeInt(magic); o.writeInt(body.size); o.write(body)
        }
        section(0xd000d00d.toInt(), ByteArray(12) { it.toByte() })
        if (extraSectionsFirst) section(0xc0debabe.toInt(), ByteArray(codeBytes) { (it * 7).toByte() })
        val ep = ByteArrayOutputStream()
        DataOutputStream(ep).apply {
            writeShort(entries)
            repeat(entries) {
                writeLong(uuid.mostSignificantBits); writeLong(uuid.leastSignificantBits)
                writeInt(1); writeInt(2); writeInt(3); writeInt(4); writeInt(appType)
            }
        }
        section(0x6060c0de, ep.toByteArray())
        if (!extraSectionsFirst) section(0xc0debabe.toInt(), ByteArray(codeBytes) { (it * 7).toByte() })
        section(0, ByteArray(0))
        return bos.toByteArray()
    }
}
