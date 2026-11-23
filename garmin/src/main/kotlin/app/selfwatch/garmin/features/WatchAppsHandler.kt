/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.garmin.features

import app.selfwatch.garmin.gfdi.GfdiMessage
import app.selfwatch.garmin.pb.PbHandler
import app.selfwatch.garmin.pb.PbResult
import app.selfwatch.garmin.pb.ProtobufService
import app.selfwatch.garmin.pb.proto.InstalledAppsProto
import app.selfwatch.garmin.pb.proto.InstalledAppsProto.AppType
import app.selfwatch.garmin.pb.proto.InstalledAppsProto.InstalledAppsService
import app.selfwatch.garmin.pb.proto.SmartProto.Smart
import app.selfwatch.garmin.session.GarminCapability
import app.selfwatch.garmin.session.GarminSessionContext
import app.selfwatch.garmin.session.GfdiHandler
import app.selfwatch.model.device.Feature
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.watch.WatchApp
import app.selfwatch.model.watch.WatchAppKind
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.model.watch.WatchEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Connect IQ watch apps (A21): list, install, delete, launch.
 *
 * - **List** (on READY, after install/delete, or [refresh]): GetInstalledAppsRequest(ALL) → the watch's
 *   GetInstalledAppsResponse → [WatchEvent.InstalledApps]. System apps (entries without a file name) are
 *   left out unless [includeSystemApps]. Needs `CONNECTIQ_APP_MANAGEMENT` or `CONNECTIQ_WATCH_APP_DOWNLOAD`.
 * - **Install** ([WatchCommand.InstallApp]): the PRG header is read ([PrgFile]) for its type, the type's
 *   download flag is checked, then the bytes are uploaded as file type 255/17 (CREATE_FILE → UPLOAD_REQUEST →
 *   FILE_TRANSFER_DATA). `settingsJson` is the app's settings definitions, which stay on the phone.
 * - **Delete** ([WatchCommand.DeleteApp], `CONNECTIQ_APP_MANAGEMENT`): DeleteAppRequest with the store app id
 *   and type from the last list (listed first if unknown).
 * - **Launch** ([WatchCommand.LaunchApp], `LAUNCH_REMOTE_CIQ_APP_SUPPORT`): LaunchAppRequest (normal start-up).
 *   The reference does not launch apps, so this follows the proto only; T-A27 confirms it.
 *
 * Ref: Gadgetbridge 160beab41 service/devices/garmin/GarminSupport.java (onAppInfoReq: GetInstalledAppsRequest,
 * WATCH_APP on connect; onAppListReceived: system app = no file name, version 0 = none; onAppDelete:
 * DeleteAppRequest(store_app_id, app_type) of the listed app; installItem: upload as FILETYPE.PRG 255/17, then
 * refresh the list), ProtocolBufferHandler.java (delete OK → refresh), GarminPrgFile.kt
 */
public class WatchAppsHandler(
    private val protobuf: ProtobufService,
    private val includeSystemApps: Boolean = false,
    private val timeoutMs: Long = 10_000,
) : GfdiHandler {
    private val holder = SessionHolder()
    private val known = ConcurrentHashMap<UUID, InstalledAppsProto.GetInstalledAppsResponse.InstalledApp>()

    /** Accepts installed-apps messages the watch sends on its own (an app list it pushes). */
    public val pb: PbHandler = PbHandler { smart, _, session ->
        if (!smart.hasConnectIqInstalledAppsService()) return@PbHandler null
        val s = smart.connectIqInstalledAppsService
        if (s.hasGetInstalledAppsResponse()) publish(s.getInstalledAppsResponse, session)
        PbResult.Done
    }

    override suspend fun handle(m: GfdiMessage, session: GarminSessionContext): Boolean {
        holder.set(session)
        return false
    }

    override suspend fun onReady(session: GarminSessionContext) {
        holder.set(session)
        known.clear()
        if (canList(session)) refresh(session)
    }

    override suspend fun command(cmd: WatchCommand, session: GarminSessionContext): Outcome<Unit>? {
        holder.set(session)
        return when (cmd) {
            is WatchCommand.InstallApp -> install(session, cmd.prgBytes)
            is WatchCommand.DeleteApp -> delete(session, cmd.appUuid)
            is WatchCommand.LaunchApp -> launch(session, cmd.appUuid)
            else -> null
        }
    }

    /** Asks the connected watch for its apps; also published as [WatchEvent.InstalledApps]. */
    public suspend fun refresh(): Outcome<List<WatchApp>> = when (val s = holder.live()) {
        is Outcome.Ok -> refresh(s.value)
        is Outcome.Fail -> s
    }

    /** As [refresh], on [session]. */
    public suspend fun refresh(session: GarminSessionContext): Outcome<List<WatchApp>> {
        if (!canList(session)) return Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_MANAGE))
        val req = InstalledAppsService.newBuilder().setGetInstalledAppsRequest(
            InstalledAppsProto.GetInstalledAppsRequest.newBuilder().setAppType(AppType.ALL),
        )
        val r = when (val rr = protobuf.request(session, Smart.newBuilder().setConnectIqInstalledAppsService(req).build(), timeoutMs)) {
            is Outcome.Ok -> rr.value
            is Outcome.Fail -> return rr
        }
        val s = r.connectIqInstalledAppsService
        if (!s.hasGetInstalledAppsResponse()) return Outcome.Fail(SwError.Protocol("no app list in the answer"))
        return Outcome.Ok(publish(s.getInstalledAppsResponse, session))
    }

    private fun canList(session: GarminSessionContext) =
        GarminCapability.CONNECTIQ_APP_MANAGEMENT in session.capabilities ||
            GarminCapability.CONNECTIQ_WATCH_APP_DOWNLOAD in session.capabilities

    private fun publish(resp: InstalledAppsProto.GetInstalledAppsResponse, session: GarminSessionContext): List<WatchApp> {
        known.clear()
        val apps = ArrayList<WatchApp>()
        for (a in resp.installedAppList) {
            val uuid = UuidBytes.fromBytes(a.storeAppId) ?: continue
            known[uuid] = a
            if (!includeSystemApps && !a.hasFilename()) continue
            apps.add(WatchApp(uuid, a.name, a.version.takeIf { a.hasVersion() && it != 0 }, kindOf(a.appType)))
        }
        SwLog.i(TAG, "watch has ${resp.installedAppCount} apps (${apps.size} listed), ${resp.availableSlots} slots free")
        session.emit(WatchEvent.InstalledApps(apps))
        return apps
    }

    private suspend fun install(session: GarminSessionContext, prg: ByteArray): Outcome<Unit> {
        val info = when (val p = PrgFile.parse(prg)) {
            is Outcome.Ok -> p.value
            is Outcome.Fail -> return p
        }
        if (info.requiredCapability !in session.capabilities) return Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_INSTALL))
        SwLog.i(TAG, "installing ${info.appUuid} (${info.kind}, ${prg.size} bytes)")
        return when (val r = session.transfers.upload(prg, PRG_DATA_TYPE, PRG_SUB_TYPE)) {
            is Outcome.Ok -> {
                if (canList(session)) refresh(session)
                Outcome.Ok(Unit)
            }
            is Outcome.Fail -> r
        }
    }

    private suspend fun delete(session: GarminSessionContext, uuid: UUID): Outcome<Unit> {
        if (GarminCapability.CONNECTIQ_APP_MANAGEMENT !in session.capabilities) return Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_MANAGE))
        if (!known.containsKey(uuid)) refresh(session)
        val app = known[uuid] ?: return Outcome.Fail(SwError.BadInput("app $uuid is not installed"))
        val req = InstalledAppsService.newBuilder().setDeleteAppRequest(
            InstalledAppsProto.DeleteAppRequest.newBuilder().setStoreAppId(app.storeAppId).setAppType(app.appType),
        )
        val r = when (val rr = protobuf.request(session, Smart.newBuilder().setConnectIqInstalledAppsService(req).build(), timeoutMs)) {
            is Outcome.Ok -> rr.value.connectIqInstalledAppsService
            is Outcome.Fail -> return rr
        }
        val status = if (r.hasDeleteAppResponse()) r.deleteAppResponse.status else null
        if (status != InstalledAppsProto.DeleteAppResponse.Status.OK) return Outcome.Fail(SwError.Protocol("delete of $uuid refused ($status)"))
        refresh(session)
        return Outcome.Ok(Unit)
    }

    private suspend fun launch(session: GarminSessionContext, uuid: UUID): Outcome<Unit> {
        if (GarminCapability.LAUNCH_REMOTE_CIQ_APP_SUPPORT !in session.capabilities) return Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_LAUNCH))
        val req = InstalledAppsService.newBuilder().setLaunchAppRequest(
            InstalledAppsProto.LaunchAppRequest.newBuilder().setAppUuid(UuidBytes.toBytes(uuid))
                .setAppStartupMode(InstalledAppsProto.LaunchAppRequest.StartUpMode.APP_STARTUP_MODE_NORMAL),
        )
        val r = when (val rr = protobuf.request(session, Smart.newBuilder().setConnectIqInstalledAppsService(req).build(), timeoutMs)) {
            is Outcome.Ok -> rr.value.connectIqInstalledAppsService
            is Outcome.Fail -> return rr
        }
        val status = if (r.hasLaunchAppResponse()) r.launchAppResponse.status else null
        return when (status) {
            InstalledAppsProto.LaunchAppResponse.Status.LAUNCH_STATUS_SUCCESS,
            InstalledAppsProto.LaunchAppResponse.Status.LAUNCH_STATUS_APP_ALREADY_RUNNING,
            -> Outcome.Ok(Unit)
            InstalledAppsProto.LaunchAppResponse.Status.LAUNCH_STATUS_APP_NOT_INSTALLED -> Outcome.Fail(SwError.BadInput("app $uuid is not installed"))
            else -> Outcome.Fail(SwError.Protocol("launch of $uuid failed ($status)"))
        }
    }

    /** Constants. */
    public companion object {
        private const val TAG = "garmin.apps"

        /** File data type of a PRG upload (Ref: Gadgetbridge 160beab41 service/devices/garmin/FileType.java PRG(255, 17)). */
        public const val PRG_DATA_TYPE: Int = 255

        /** File subtype of a PRG upload. */
        public const val PRG_SUB_TYPE: Int = 17

        /** The app kind for a protobuf app type. */
        public fun kindOf(t: AppType): WatchAppKind = when (t) {
            AppType.WATCH_APP, AppType.AUDIO_CONTENT_PROVIDER -> WatchAppKind.APP
            AppType.WIDGET -> WatchAppKind.WIDGET
            AppType.WATCH_FACE -> WatchAppKind.WATCH_FACE
            AppType.DATA_FIELD, AppType.ACTIVITY -> WatchAppKind.DATA_FIELD
            else -> WatchAppKind.OTHER
        }
    }
}
