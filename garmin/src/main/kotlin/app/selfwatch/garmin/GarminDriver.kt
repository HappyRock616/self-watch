/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin

import app.selfwatch.garmin.appmsg.AppMessages
import app.selfwatch.garmin.files.DirEntry
import app.selfwatch.garmin.files.FileTransfers
import app.selfwatch.garmin.files.FitDecoders
import app.selfwatch.garmin.files.FitFileKind
import app.selfwatch.garmin.files.SyncPlan
import app.selfwatch.garmin.gfdi.GfdiChannel
import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.gfdi.GfdiReplies
import app.selfwatch.garmin.gfdi.GfdiStatus
import app.selfwatch.garmin.gfdi.MalformedException
import app.selfwatch.garmin.gfdi.SystemEventType
import app.selfwatch.garmin.gfdi.DeviceSettingId
import app.selfwatch.garmin.gfdi.GfdiMessage.SettingValue
import app.selfwatch.garmin.link.GarminUuids
import app.selfwatch.garmin.link.GfdiTransport
import app.selfwatch.garmin.link.LinkSelector
import app.selfwatch.garmin.link.MultiLinkGfdiTransport
import app.selfwatch.garmin.features.LiveLink
import app.selfwatch.garmin.models.GarminModels
import app.selfwatch.garmin.session.AuthPolicy
import app.selfwatch.garmin.session.Features
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminConfig
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.garmin.session.GarminTime
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.garmin.session.SessionState
import app.selfwatch.model.ble.BlePort
import app.selfwatch.model.ble.CloseReason
import app.selfwatch.model.ble.HasCloseReason
import app.selfwatch.model.ble.PortState
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.device.Feature
import app.selfwatch.model.device.SupportTier
import app.selfwatch.model.device.SyncReason
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.result.valueOrNull
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.SystemClock
import app.selfwatch.model.watch.FileStore
import app.selfwatch.model.watch.HealthSink
import app.selfwatch.model.watch.SyncReport
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchDriver
import app.selfwatch.model.watch.WatchEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * [WatchDriver] for Garmin watches: link selection, handshake to READY, time, battery, directory and
 * FIT file sync with archive-after-ingest, and Connect IQ app messages. Feature handlers for later
 * tasks plug in through [handlers].
 *
 * A [sync] asked for while one is already running joins it (both callers get the same report)
 * instead of queueing a second pass. [run] waits for the port to connect first and returns as soon as
 * the link fails or drops, so the caller's back-off applies.
 *
 * @param deviceId `selfwatch.db` device id, used in sync keys and data rows.
 * @param config settings for the next connection (replaced by [connectConfig] when that is given).
 * @param decoders turns FIT files into ingest batches (the `fit` module's decoders in production).
 * @param decodeContext where decoding runs (Dispatchers.Default).
 * @param connectConfig when given, asked at the start of every [run] for that connection's settings
 *   (for example `firstConnect` only on the first connection after pairing).
 */
public class GarminDriver(
    private val deviceId: Long,
    @Volatile private var config: GarminConfig = GarminConfig(),
    private val clock: Clock = SystemClock,
    private val models: GarminModels? = GarminModels.load().valueOrNull(),
    private val decoders: FitDecoders = FitDecoders.None,
    private val decodeContext: CoroutineContext = Dispatchers.Default,
    private val handlers: List<GfdiHandler> = GarminFeatures(clock).handlers,
    private val connectConfig: (suspend () -> GarminConfig)? = null,
) : WatchDriver {
    private val _info = MutableStateFlow<DeviceInfo?>(null)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _events = MutableSharedFlow<WatchEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _session = MutableStateFlow(SessionState.DISCONNECTED)
    private val _securePairing = MutableStateFlow(false)

    @Volatile
    private var live: Live? = null

    override val info: StateFlow<DeviceInfo?> get() = _info
    override val state: StateFlow<ConnectionState> get() = _state
    override val events: Flow<WatchEvent> get() = _events

    /** Protocol-level state (finer than [state]). */
    public val sessionState: StateFlow<SessionState> get() = _session

    /** True once the watch asked for Garmin's secure pairing (R2), which Self Watch does not implement. */
    public val securePairingRequested: StateFlow<Boolean> get() = _securePairing

    override suspend fun run(port: BlePort, sink: HealthSink, files: FileStore) {
        _state.value = ConnectionState.Connecting
        _securePairing.value = false
        connectConfig?.let { source ->
            try {
                config = source()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.e(TAG, "reading the connection settings failed; keeping the last ones", e)
            }
        }
        val link = awaitLink(port)
        if (link != PortState.CONNECTED) {
            SwLog.i(TAG, "Bluetooth link not up ($link); giving up this connection")
            _state.value = ConnectionState.Failed(closedError(port) ?: SwError.Io("Bluetooth connection failed"), null)
            return
        }
        var failed = false
        try {
            coroutineScope {
                val outer = this
                val opening = async { LinkSelector.open(port, outer, config.reliableLink) }
                val dropped = async { port.state.first { it != PortState.CONNECTED && it != PortState.CONNECTING } }
                val opened = select<Outcome<GfdiTransport>?> {
                    opening.onAwait { it }
                    dropped.onAwait { null }
                }
                dropped.cancel()
                val transport = when (opened) {
                    is Outcome.Ok -> opened.value
                    else -> {
                        val error = (opened as? Outcome.Fail)?.error ?: SwError.Io("Bluetooth link lost while opening")
                        SwLog.w(TAG, "link failed: $error")
                        _state.value = ConnectionState.Failed(error, null)
                        failed = true
                        opening.cancel()
                        coroutineContext.cancelChildren()
                        return@coroutineScope
                    }
                }
                _session.value = SessionState.LINK_UP
                _state.value = ConnectionState.Handshaking
                val l = Live(this, port, transport, sink, files)
                live = l
                launch {
                    port.state.first { it != PortState.CONNECTED && it != PortState.CONNECTING }
                    transport.close()
                }
                launch {
                    delay(config.handshakeTimeoutMs)
                    if (_session.value < SessionState.READY) {
                        val err = if (_securePairing.value) {
                            SwError.Protocol("the watch requires Garmin secure pairing, which Self Watch does not support yet")
                        } else {
                            SwError.Timeout("Garmin handshake")
                        }
                        _state.value = ConnectionState.Failed(err, null)
                        failed = true
                        transport.close()
                    }
                }
                l.openAppChannel()
                l.loop()
                l.transfers.close()
                coroutineContext.cancelChildren()
            }
        } finally {
            live = null
            _session.value = SessionState.DISCONNECTED
            if (!failed) {
                val closed = closedError(port)
                _state.value = if (closed != null) ConnectionState.Failed(closed, null) else ConnectionState.Disconnected
            }
        }
    }

    /**
     * The failure to show when [port] closed for good because the bond is gone (`ble` reports
     * [CloseReason.REPAIR_NEEDED] through [HasCloseReason]); null otherwise.
     */
    private fun closedError(port: BlePort): SwError? {
        if (port.state.value != PortState.CLOSED) return null
        return when ((port as? HasCloseReason)?.closeReason?.value) {
            CloseReason.REPAIR_NEEDED -> SwError.Protocol(REPAIR_NEEDED_DETAIL)
            else -> null
        }
    }

    override suspend fun sync(reason: SyncReason): Outcome<SyncReport> {
        val l = live ?: return Outcome.Fail(SwError.NotConnected(deviceId))
        val running = l.syncShared(reason) ?: return Outcome.Fail(SwError.NotConnected(deviceId))
        return try {
            running.await()
        } catch (e: CancellationException) {
            // The connection ended mid-sync (its scope was cancelled). Rethrow only if we were cancelled.
            currentCoroutineContext().ensureActive()
            Outcome.Fail(SwError.NotConnected(deviceId))
        }
    }

    /**
     * The link state once [port] has finished connecting: [PortState.CONNECTED], or the state it fell
     * back to (DISCONNECTED after a failed attempt, or CLOSED). A port that is still DISCONNECTED when
     * [run] starts is waiting for its owner to connect it.
     */
    private suspend fun awaitLink(port: BlePort): PortState =
        port.state.dropWhile { it == PortState.DISCONNECTED }.first { it != PortState.CONNECTING }

    override suspend fun send(cmd: WatchCommand): Outcome<Unit> {
        val l = live ?: return Outcome.Fail(SwError.NotConnected(deviceId))
        if (_session.value < SessionState.READY) return Outcome.Fail(SwError.NotConnected(deviceId))
        for (h in handlers) h.command(cmd, l)?.let { return it }
        return when (cmd) {
            WatchCommand.SyncTime -> l.ch.send(GfdiMessage.SystemEvent(SystemEventType.TIME_UPDATED, 0))
            is WatchCommand.AppMessage -> l.appMessages?.send(cmd.appUuid, cmd.payload)
                ?: Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_MESSAGES))
            is WatchCommand.LiveData -> l.live?.set(cmd.enable) ?: Outcome.Fail(SwError.Unsupported(Feature.REALTIME_HR))
            else -> Outcome.Fail(SwError.Unsupported(featureOf(cmd)))
        }
    }

    private fun featureOf(cmd: WatchCommand): Feature = when (cmd) {
        is WatchCommand.Notify, is WatchCommand.RemoveNotification -> Feature.NOTIFICATIONS
        is WatchCommand.IncomingCall, WatchCommand.CallEnded -> Feature.CALLS
        is WatchCommand.Music -> Feature.MUSIC
        is WatchCommand.Weather -> Feature.WEATHER
        is WatchCommand.FindWatch -> Feature.FIND_WATCH
        is WatchCommand.SetAlarms -> Feature.ALARMS
        is WatchCommand.InstallApp -> Feature.WATCH_APP_INSTALL
        is WatchCommand.DeleteApp -> Feature.WATCH_APP_MANAGE
        is WatchCommand.LaunchApp -> Feature.WATCH_APP_LAUNCH
        is WatchCommand.WriteAppSettings -> Feature.WATCH_APP_SETTINGS
        is WatchCommand.WebResponse -> Feature.WATCH_APP_WEB
        is WatchCommand.AppMessage -> Feature.WATCH_APP_MESSAGES
        WatchCommand.SyncTime -> Feature.TIME_SYNC
        is WatchCommand.LiveData -> Feature.REALTIME_HR
    }

    /** Everything that lives for one connection. */
    private inner class Live(
        override val scope: CoroutineScope,
        val port: BlePort,
        val transport: GfdiTransport,
        val sink: HealthSink,
        val files: FileStore,
    ) : GarminSessionContext {
        val ch = GfdiChannel(transport, scope)
        override val transfers = FileTransfers(ch, config.chunkTimeoutMs, config.requestTimeoutMs)
        private val syncGate = Any()
        private var running: Deferred<Outcome<SyncReport>>? = null
        override val auth = AuthPolicy(config.fakeAuth)
        var appMessages: AppMessages? = null

        /** Live heart rate and steps (multi-link watches only). */
        val live: LiveLink? = (transport as? MultiLinkGfdiTransport)?.let { LiveLink(it.link, scope) { e -> emit(e) } }
        var deviceInfo: GfdiMessage.DeviceInformation? = null
        var fileTypes: List<GfdiReplies.FileTypeInfo> = emptyList()
        var batteryPct: Int? = null
        var initStarted = false

        @Volatile
        override var capabilities: Set<GarminCapability> = emptySet()

        override val channel: GfdiChannel get() = ch
        override val sessionState: SessionState get() = _session.value

        override fun emit(event: WatchEvent) {
            _events.tryEmit(event)
        }

        suspend fun openAppChannel() {
            if (GarminUuids.WATCH_APP_SERVICE !in port.serviceUuids()) return
            when (val p = port.openPipe(GarminUuids.WATCH_APP_SERVICE, GarminUuids.WATCH_APP_RX, GarminUuids.WATCH_APP_TX)) {
                is Outcome.Ok -> {
                    val am = AppMessages(p.value, scope)
                    appMessages = am
                    scope.launch { am.received.collect { emit(WatchEvent.AppMessageReceived(it.app, it.value)) } }
                }
                is Outcome.Fail -> SwLog.w(TAG, "watch-app channel unavailable: ${p.error}")
            }
        }

        suspend fun loop() {
            ch.incoming.collect { m ->
                try {
                    handle(m)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A bug in one handler must not end the session; the message gets a NAK.
                    SwLog.e(TAG, "handling message ${m.id} failed", e)
                    if (m !is GfdiMessage.Response) ch.reply(m.id, GfdiStatus.NAK)
                }
            }
        }

        private suspend fun handle(m: GfdiMessage) {
            for (h in handlers) if (h.handle(m, this)) return
            when (m) {
                is GfdiMessage.DeviceInformation -> onDeviceInformation(m)
                is GfdiMessage.Configuration -> onConfiguration(m)
                is GfdiMessage.CurrentTimeRequest -> if (config.timeSync) {
                    val reply = GarminTime.reply(m.referenceId, clock.nowSec(), clock.zone())
                    ch.reply(m.id, GfdiStatus.ACK, reply.encode())
                } else {
                    ch.reply(m.id, GfdiStatus.UNSUPPORTED)
                }
                is GfdiMessage.BatteryStatus -> {
                    ch.reply(m.id)
                    if (m.percent in 0..100) {
                        if (m.percent != batteryPct) SwLog.i(TAG, "watch battery ${m.percent}%")
                        batteryPct = m.percent
                        emit(WatchEvent.Battery(m.percent))
                        _info.value = _info.value?.copy(batteryPct = m.percent)
                    }
                }
                is GfdiMessage.FileTransferData -> if (!transfers.onChunk(m)) ch.reply(m.id, GfdiStatus.NAK)
                is GfdiMessage.FileAvailable -> {
                    ch.reply(m.id)
                    if (m.entry.kind != null) startSync(SyncReason.WATCH_REQUEST)
                }
                is GfdiMessage.Synchronization -> {
                    ch.reply(m.id)
                    if (m.shouldSync && _session.value >= SessionState.READY) {
                        // Ref: Gadgetbridge 160beab41 service/devices/garmin/FileTransferHandler.java (SYNCHRONIZATION → FILTER ONLY_NEW → directory)
                        scope.launch {
                            ch.request(GfdiMessage.Filter(GfdiMessage.Filter.ONLY_NEW), config.requestTimeoutMs)
                            startSync(SyncReason.WATCH_REQUEST)
                        }
                    }
                }
                is GfdiMessage.AuthNegotiation -> {
                    SwLog.i(TAG, "auth negotiation (flags 0x%x)".format(m.flags))
                    ch.reply(m.id, GfdiStatus.ACK, auth.negotiationReply(m).encode())
                }
                is GfdiMessage.SecurePairing -> {
                    SwLog.w(TAG, "watch asked for Garmin secure pairing (message ${m.id}); not supported (R2)")
                    if (!_securePairing.value) {
                        _securePairing.value = true
                        _events.tryEmit(WatchEvent.SecurePairingRequired)
                    }
                    ch.reply(m.id, GfdiStatus.UNSUPPORTED)
                }
                is GfdiMessage.Response -> SwLog.d(TAG) { "unrequested response to ${m.originalId}: ${m.status}" }
                else -> {
                    SwLog.d(TAG) { "unsupported message ${m.id}" }
                    ch.reply(m.id, GfdiStatus.UNSUPPORTED)
                }
            }
        }

        // Ref: Gadgetbridge 160beab41 service/devices/garmin/GarminSupport.java (onMessage: status first, then the
        // message's own reply; DeviceInformationMessage gets a generic ACK and then the RESPONSE with our info)
        private suspend fun onDeviceInformation(m: GfdiMessage.DeviceInformation) {
            deviceInfo = m
            if (m.maxPacketSize > 0) transfers.maxPacketSize = m.maxPacketSize
            SwLog.i(TAG, "watch: product ${m.productNumber}, firmware ${m.firmware}, protocol ${m.protocolVersion}, max packet ${m.maxPacketSize}")
            ch.reply(m.id)
            val reply = GfdiReplies.DeviceInfoReply(
                bluetoothName = config.phoneBluetoothName,
                manufacturer = config.manufacturer,
                model = config.model,
                protocolFlags = if (m.protocolVersion / 100 == 1) 1 else 0,
            )
            ch.reply(m.id, GfdiStatus.ACK, reply.encode())
            if (_session.value < SessionState.INFO_EXCHANGED) _session.value = SessionState.INFO_EXCHANGED
        }

        // Ref: Gadgetbridge 160beab41 service/devices/garmin/GarminSupport.java (completeInitialization: supported file
        // types, device settings, TIME_UPDATED, SYNC_READY; on first connect PAIR_COMPLETE, SYNC_COMPLETE, SETUP_WIZARD_COMPLETE)
        private suspend fun onConfiguration(m: GfdiMessage.Configuration) {
            capabilities = GarminCapability.fromBits(m.capabilityBits.toByteArray())
            SwLog.i(TAG, "watch has ${capabilities.size} capabilities")
            ch.reply(m.id)
            ch.send(GfdiMessage.Configuration(app.selfwatch.garmin.gfdi.Bytes(GarminCapability.toBits(config.advertisedCapabilities))))
            if (_session.value < SessionState.CONFIGURED) _session.value = SessionState.CONFIGURED
            if (initStarted) return
            initStarted = true
            scope.launch { completeInitialization() }
        }

        private suspend fun completeInitialization() {
            val types = ch.request(GfdiMessage.SupportedFileTypesRequest, config.requestTimeoutMs)
            fileTypes = (types as? Outcome.Ok)?.value?.takeIf { it.status == GfdiStatus.ACK }?.let {
                try {
                    GfdiReplies.SupportedFileTypes.parse(it.extra.toByteArray()).types
                } catch (e: MalformedException) {
                    null
                }
            } ?: emptyList()
            ch.send(
                GfdiMessage.DeviceSettings(
                    listOf(
                        DeviceSettingId.AUTO_UPLOAD_ENABLED to SettingValue.Bool(true),
                        DeviceSettingId.WEATHER_CONDITIONS_ENABLED to SettingValue.Bool(true), // the reference sends true (A18)
                        DeviceSettingId.WEATHER_ALERTS_ENABLED to SettingValue.Bool(false),
                    ),
                ),
            )
            if (config.timeSync) ch.send(GfdiMessage.SystemEvent(SystemEventType.TIME_UPDATED, 0))
            ch.send(GfdiMessage.SystemEvent(SystemEventType.SYNC_READY, 0))
            if (config.firstConnect) {
                ch.send(GfdiMessage.SystemEvent(SystemEventType.PAIR_COMPLETE, 0))
                ch.send(GfdiMessage.SystemEvent(SystemEventType.SYNC_COMPLETE, 0))
                ch.send(GfdiMessage.SystemEvent(SystemEventType.SETUP_WIZARD_COMPLETE, 0))
            }
            // Balanced once per connection, as Gadgetbridge does; never HIGH (short intervals cost the watch battery).
            port.setHighSpeed(false)
            _session.value = SessionState.READY
            _info.value = buildInfo()
            _state.value = ConnectionState.Ready
            SwLog.i(TAG, "READY")
            for (h in handlers) scope.launch { h.onReady(this@Live) }
            if (config.syncOnConnect) startSync(SyncReason.CONNECT)
        }

        private fun buildInfo(): DeviceInfo {
            val di = deviceInfo
            val name = di?.bluetoothName?.ifBlank { di.deviceName } ?: ""
            val model = models?.match(name, di?.productNumber)
            return DeviceInfo(
                id = deviceId,
                kind = DeviceKind.GARMIN_WATCH,
                address = port.address,
                name = name,
                model = model?.name ?: di?.deviceName?.ifBlank { null } ?: name,
                productId = di?.productNumber,
                firmware = di?.firmware,
                serial = di?.unitNumber,
                tier = model?.tier ?: SupportTier.UNVERIFIED,
                features = Features.resolve(capabilities, fileTypes, model?.overrides ?: emptyMap(), appMessages != null),
                batteryPct = batteryPct,
            )
        }

        fun startSync(reason: SyncReason) {
            syncShared(reason)
        }

        /**
         * The sync in progress, or a new one when none is running and the session is READY; null when
         * the session cannot sync now.
         */
        fun syncShared(reason: SyncReason): Deferred<Outcome<SyncReport>>? = synchronized(syncGate) {
            running?.takeIf { it.isActive }?.let { return it }
            if (_session.value != SessionState.READY) return null
            scope.async { syncSafely(reason) }.also { running = it }
        }

        private suspend fun syncSafely(reason: SyncReason): Outcome<SyncReport> = try {
            syncOnce(reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.e(TAG, "sync failed", e)
            Outcome.Fail(SwError.Io("sync failed"))
        }

        private suspend fun syncOnce(reason: SyncReason): Outcome<SyncReport> {
            if (_session.value != SessionState.READY) return Outcome.Fail(SwError.NotConnected(deviceId))
            val startNs = System.nanoTime()
            _session.value = SessionState.SYNCING
            _state.value = ConnectionState.Syncing(0, 0, "directory")
            return try {
                SwLog.i(TAG, "sync started ($reason)")
                syncLocked(startNs)
            } finally {
                if (_session.value == SessionState.SYNCING) {
                    _session.value = SessionState.READY
                    _state.value = ConnectionState.Ready
                }
            }
        }

        private suspend fun syncLocked(startNs: Long): Outcome<SyncReport> {
            val dirBytes = when (val d = transfers.download(0, MAX_DIRECTORY_BYTES)) {
                is Outcome.Ok -> d.value
                is Outcome.Fail -> return d
            }
            val entries = when (val p = DirEntry.parseDirectory(dirBytes)) {
                is Outcome.Ok -> p.value
                is Outcome.Fail -> return p
            }
            val plan = SyncPlan.build(entries, deviceId, sink, decoders)
            SwLog.i(TAG, "directory: ${entries.size} entries, ${plan.files.size} to fetch, ${plan.toArchive.size} to archive")
            if (config.archiveAfterIngest) for (p in plan.toArchive) transfers.archive(p.entry.index)
            var ok = 0
            var failedFiles = 0
            for ((i, p) in plan.files.withIndex()) {
                val label = p.kind.name.lowercase()
                _state.value = ConnectionState.Syncing(i, plan.files.size, label)
                if (fetchOne(p)) ok++ else failedFiles++
            }
            _state.value = ConnectionState.Syncing(plan.files.size, plan.files.size, "done")
            ch.send(GfdiMessage.SystemEvent(SystemEventType.SYNC_COMPLETE, 0))
            val ms = (System.nanoTime() - startNs) / 1_000_000
            SwLog.i(TAG, "sync done: $ok new, $failedFiles failed")
            return Outcome.Ok(SyncReport(ok, failedFiles, ms))
        }

        private suspend fun fetchOne(p: app.selfwatch.garmin.files.PlannedFile): Boolean {
            val bytes = when (val d = transfers.download(p.entry.index, SyncPlan.MAX_FILE_BYTES)) {
                is Outcome.Ok -> d.value
                is Outcome.Fail -> {
                    SwLog.w(TAG, "download of file ${p.entry.index} failed: ${d.error}")
                    return false
                }
            }
            val fitPath = if (p.kind == FitFileKind.ACTIVITY) {
                try {
                    files.saveWorkoutFit(deviceId, p.key.tSec, bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SwLog.e(TAG, "saving workout file ${p.entry.index} failed", e)
                    return false
                }
            } else {
                null
            }
            val batch = when (val b = withContext(decodeContext) { decoders.decode(p.kind, bytes, deviceId, fitPath) }) {
                is Outcome.Ok -> b.value.copy(key = p.key)
                is Outcome.Fail -> {
                    SwLog.w(TAG, "decoding file ${p.entry.index} (${p.kind}) failed: ${b.error}")
                    return false
                }
            }
            if (p.kind == FitFileKind.SLEEP || batch.sleepStages.isNotEmpty()) {
                // Keep the watch's original for checking odd nights later; never fails the sync.
                try {
                    files.keepSleepFile(deviceId, p.key.tSec, p.kind.name.lowercase(), bytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SwLog.w(TAG, "keeping sleep file ${p.entry.index} failed", e)
                }
            }
            when (val r = sink.ingest(batch)) {
                is Outcome.Ok -> Unit
                is Outcome.Fail -> {
                    SwLog.w(TAG, "storing file ${p.entry.index} failed: ${r.error}")
                    return false
                }
            }
            // Archive only after the data is safe: a crash before this point re-downloads, never loses data.
            if (config.archiveAfterIngest) {
                val a = transfers.archive(p.entry.index)
                if (a is Outcome.Fail) SwLog.w(TAG, "archiving file ${p.entry.index} failed: ${a.error}")
            }
            return true
        }
    }

    /** Constants. */
    public companion object {
        private const val TAG = "garmin"
        private const val MAX_DIRECTORY_BYTES = 4L * 1024 * 1024

        /** [SwError.Protocol] detail when the Bluetooth bond is gone and the watch must be paired again. */
        public const val REPAIR_NEEDED_DETAIL: String = "re-pair needed"
    }
}
