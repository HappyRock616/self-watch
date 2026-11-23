/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.conn

import android.bluetooth.BluetoothGattCharacteristic
import app.selfwatch.ble.gatt.GattApi
import app.selfwatch.ble.gatt.GattCodes
import app.selfwatch.ble.gatt.GattConnector
import app.selfwatch.ble.gatt.GattListener
import app.selfwatch.model.result.Outcome
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** UUIDs shaped like Garmin's V2 multi-link service (values only matter to the fake). */
object Ids {
    val SERVICE: UUID = UUID.fromString("6a4e2800-667b-11e3-949a-0800200c9a66")
    val RX: UUID = UUID.fromString("6a4e2810-667b-11e3-949a-0800200c9a66")
    val TX: UUID = UUID.fromString("6a4e2820-667b-11e3-949a-0800200c9a66")
    val RX2: UUID = UUID.fromString("6a4e2811-667b-11e3-949a-0800200c9a66")
    val TX2: UUID = UUID.fromString("6a4e2821-667b-11e3-949a-0800200c9a66")
}

/** How one connection attempt behaves. */
enum class ConnectScript { SUCCEED, FAIL_133, SILENT, THROW_SECURITY, REFUSE }

/** How an operation is answered. */
enum class Reply { OK, SILENT, NOT_STARTED }

/**
 * A scriptable GATT stack. Callbacks are delivered through [deliver]: synchronously by default
 * (deterministic under virtual time) or on another thread for concurrency tests.
 */
internal class FakeGatt(
    var deliver: (() -> Unit) -> Unit = { it() },
    private val now: () -> Long = { 0L },
) : GattConnector {
    /** Script for attempt n (0-based). */
    var connectScript: (Int) -> ConnectScript = { ConnectScript.SUCCEED }
    var services: Map<UUID, Set<UUID>> = mapOf(Ids.SERVICE to setOf(Ids.RX, Ids.TX, Ids.RX2, Ids.TX2))
    var rxProperties: Int = BluetoothGattCharacteristic.PROPERTY_NOTIFY
    var discoverReplies: ArrayDeque<Reply> = ArrayDeque()
    var mtuReply: Reply = Reply.OK
    var mtuValue: Int = 517
    var writeReply: Reply = Reply.OK
    var writeStatus: (Int) -> Int = { GattCodes.GATT_SUCCESS }
    var descriptorReply: Reply = Reply.OK
    var priorityResult: Boolean = true

    /** Values answered to reads, by characteristic; a missing one makes the read not start. */
    var readValues: Map<UUID, ByteArray> = emptyMap()
    var readStatus: Int = GattCodes.GATT_SUCCESS

    val attempts = AtomicInteger()
    val attemptTimes: MutableList<Long> = Collections.synchronizedList(ArrayList())
    val autoFlags: MutableList<Boolean> = Collections.synchronizedList(ArrayList())
    val sessions: MutableList<Session> = Collections.synchronizedList(ArrayList())

    /** Every operation started, across sessions, as text. */
    val ops: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()
    private val writeCount = AtomicInteger()

    val current: Session get() = sessions.last()

    override fun connect(address: String, auto: Boolean, listener: GattListener): GattApi? {
        val n = attempts.getAndIncrement()
        attemptTimes += now()
        autoFlags += auto
        when (connectScript(n)) {
            ConnectScript.THROW_SECURITY -> throw SecurityException("no BLUETOOTH_CONNECT")
            ConnectScript.REFUSE -> return null
            else -> Unit
        }
        val s = Session(listener)
        sessions += s
        when (connectScript(n)) {
            ConnectScript.SUCCEED -> deliver { listener.onConnectionStateChange(0, GattCodes.STATE_CONNECTED) }
            ConnectScript.FAIL_133 -> deliver { listener.onConnectionStateChange(133, GattCodes.STATE_DISCONNECTED) }
            else -> Unit
        }
        return s
    }

    private fun begin(op: String) {
        ops += op
        val n = inFlight.incrementAndGet()
        maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
    }

    private fun end(block: () -> Unit) {
        deliver {
            inFlight.decrementAndGet()
            block()
        }
    }

    inner class Session(val listener: GattListener) : GattApi {
        @Volatile var closed = false
        val notifications = mutableListOf<Pair<UUID, Boolean>>()

        override fun discoverServices(): Boolean {
            val reply = discoverReplies.removeFirstOrNull() ?: Reply.OK
            if (reply == Reply.NOT_STARTED) return false
            begin("discover")
            if (reply == Reply.OK) end { listener.onServicesDiscovered(0) } else inFlight.decrementAndGet()
            return true
        }

        override fun services(): Map<UUID, Set<UUID>> = services

        override fun properties(service: UUID, characteristic: UUID): Int =
            if (services[service]?.contains(characteristic) == true) rxProperties else -1

        override fun hasDescriptor(service: UUID, characteristic: UUID, descriptor: UUID): Boolean = true

        override fun requestMtu(mtu: Int): Boolean {
            if (mtuReply == Reply.NOT_STARTED) return false
            begin("mtu $mtu")
            if (mtuReply == Reply.OK) end { listener.onMtuChanged(mtuValue, 0) } else inFlight.decrementAndGet()
            return true
        }

        override fun setNotification(service: UUID, characteristic: UUID, enable: Boolean): Boolean {
            ops += "notify $characteristic $enable"
            notifications += characteristic to enable
            return true
        }

        override fun writeDescriptor(service: UUID, characteristic: UUID, descriptor: UUID, value: ByteArray): Int {
            if (descriptorReply == Reply.NOT_STARTED) return GattApi.STATUS_NOT_STARTED
            begin("desc $characteristic ${value.hex()}")
            if (descriptorReply == Reply.OK) end { listener.onDescriptorWrite(service, characteristic, descriptor, 0) } else inFlight.decrementAndGet()
            return GattApi.STATUS_OK
        }

        override fun writeCharacteristic(service: UUID, characteristic: UUID, value: ByteArray, noResponse: Boolean): Int {
            if (writeReply == Reply.NOT_STARTED) return GattApi.STATUS_NOT_STARTED
            val n = writeCount.getAndIncrement()
            begin("write $characteristic ${value.hex()}${if (noResponse) " noResp" else ""}")
            val status = writeStatus(n)
            if (writeReply == Reply.OK) end { listener.onCharacteristicWrite(service, characteristic, status) } else inFlight.decrementAndGet()
            return GattApi.STATUS_OK
        }

        override fun readCharacteristic(service: UUID, characteristic: UUID): Boolean {
            val value = readValues[characteristic] ?: return false
            begin("read $characteristic")
            end { listener.onCharacteristicRead(service, characteristic, value.copyOf(), readStatus) }
            return true
        }

        override fun requestConnectionPriority(high: Boolean): Boolean {
            ops += "priority ${if (high) "high" else "balanced"}"
            return priorityResult
        }

        override fun disconnect() {
            ops += "disconnect"
        }

        override fun close() {
            closed = true
        }

        fun notify(characteristic: UUID, value: ByteArray, service: UUID = Ids.SERVICE) {
            listener.onCharacteristicChanged(service, characteristic, value)
        }

        fun drop(status: Int) {
            listener.onConnectionStateChange(status, GattCodes.STATE_DISCONNECTED)
        }
    }
}

/** A scriptable [LinkEnvironment]. */
internal class FakeEnv : LinkEnvironment {
    override val adapterOn = MutableStateFlow(true)
    override val bondLost = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    @Volatile var bonded = true
    var bondResult: Outcome<Unit> = Outcome.Ok(Unit)
    val bondCalls = AtomicInteger()
    @Volatile var started = 0
    @Volatile var stopped = 0

    override fun isBonded(): Boolean = bonded
    override suspend fun bond(): Outcome<Unit> {
        bondCalls.incrementAndGet()
        if (bondResult is Outcome.Ok) bonded = true
        return bondResult
    }
    override fun start() { started++ }
    override fun stop() { stopped++ }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
