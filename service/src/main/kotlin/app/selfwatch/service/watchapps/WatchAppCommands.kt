/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.watchapps

import app.selfwatch.db.SettingsStore
import app.selfwatch.model.device.Feature
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Clock
import app.selfwatch.model.watch.WatchApp
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.service.link.LinkValues
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The watch-app calls that are not watch commands. The app implements it on each driver's
 * `garmin.GarminFeatures` (`apps.refresh()`, `appSettings.request(uuid)`).
 */
public interface WatchAppControl {
    /** Asks watch [deviceId] for its app list (it also arrives as `WatchEvent.InstalledApps`). */
    public suspend fun refreshApps(deviceId: Long): Outcome<List<WatchApp>>

    /** Asks watch [deviceId] for [appUuid]'s settings (they arrive as `WatchEvent.AppSettings`). */
    public suspend fun requestSettings(deviceId: Long, appUuid: UUID): Outcome<Unit>

    /** Defaults. */
    public companion object {
        /** Not wired: every call fails with Unsupported. */
        public val NONE: WatchAppControl = object : WatchAppControl {
            override suspend fun refreshApps(deviceId: Long): Outcome<List<WatchApp>> =
                Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_MANAGE))

            override suspend fun requestSettings(deviceId: Long, appUuid: UUID): Outcome<Unit> =
                Outcome.Fail(SwError.Unsupported(Feature.WATCH_APP_SETTINGS))
        }
    }
}

/** What a watch-app request does. */
public enum class WatchAppOp(
    /** The text used in intents and results. */
    public val key: String,
) {
    /** Re-read the installed apps. */
    REFRESH("refresh"),

    /** Install the sideloaded `.prg` of the app ([SideloadStore]). */
    INSTALL("install"),

    /** Delete the app from the watch. */
    DELETE("delete"),

    /** Open the app on the watch. */
    LAUNCH("launch"),

    /** Read the app's settings (they land in `watch_app_settings`). */
    READ_SETTINGS("read_settings"),

    /** Write the app's settings (a copy is kept in `watch_app_settings`). */
    WRITE_SETTINGS("write_settings"),
    ;

    /** Lookup by key. */
    public companion object {
        /** The op called [key], or null. */
        public fun of(key: String?): WatchAppOp? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One watch-app request from the screens. [requestId] is the caller's own token, echoed in the result;
 * [valuesJson] is a JSON object of setting key → value for [WatchAppOp.WRITE_SETTINGS].
 */
public data class WatchAppRequest(
    val op: WatchAppOp,
    val deviceId: Long,
    val appUuid: UUID?,
    val requestId: String,
    val valuesJson: String? = null,
)

/** How a request ended, as the screens read it ([WatchAppResults]). */
public data class WatchAppResult(
    val requestId: String,
    val op: WatchAppOp,
    val appUuid: UUID?,
    val ok: Boolean,
    /** Why it failed (short, safe to show), or null. */
    val reason: String?,
    /** When it ended, UTC epoch seconds. */
    val tSec: Long,
)

/**
 * How results of watch-app requests reach the screens (another process): the service writes the setting
 * [key] `watchapps.result.<deviceId>` as `<requestId>|<op>|<uuid or ->|ok|<tSec>|` or
 * `…|fail|<tSec>|<reason>`; the settings write broadcasts a `DbChanges` notice for `Table.SETTING`.
 * App lists arrive in `watch_app` (`Table.WATCH_APP`) and settings in `watch_app_settings`
 * (`Table.WATCH_APP_SETTINGS`).
 */
public object WatchAppResults {
    /** The setting key holding watch [deviceId]'s last watch-app result. */
    public fun key(deviceId: Long): String = "watchapps.result.$deviceId"

    /** [r] as stored. */
    public fun encode(r: WatchAppResult): String =
        listOf(clean(r.requestId), r.op.key, r.appUuid?.toString() ?: "-", if (r.ok) "ok" else "fail", r.tSec.toString(), clean(r.reason ?: ""))
            .joinToString("|")

    /** The result stored as [text], or null when missing or unreadable. */
    public fun decode(text: String?): WatchAppResult? {
        val p = text?.split('|', limit = 6) ?: return null
        if (p.size < 6) return null
        val op = WatchAppOp.of(p[1]) ?: return null
        val uuid = if (p[2] == "-") null else runCatching { UUID.fromString(p[2]) }.getOrNull()
        val ok = when (p[3]) {
            "ok" -> true
            "fail" -> false
            else -> return null
        }
        return WatchAppResult(p[0], op, uuid, ok, p[5].ifEmpty { null }, p[4].toLongOrNull() ?: 0)
    }

    private fun clean(s: String): String = s.replace('|', '/').replace('\n', ' ').take(MAX_TEXT)

    private const val MAX_TEXT = 120
}

/**
 * Runs [WatchAppRequest]s in the service (B26): commands go to the watch through [send], list and settings
 * reads through [control]; a successful settings write is backed up in [store]. [run] publishes each
 * result for the screens ([WatchAppResults]).
 */
public class WatchAppsCommander(
    private val send: suspend (deviceId: Long, cmd: WatchCommand) -> Outcome<Unit>,
    private val control: WatchAppControl,
    private val store: WatchAppsStore,
    private val files: SideloadStore,
    private val settings: SettingsStore,
    private val clock: Clock,
) {
    /** Runs [req], publishes and returns the outcome. Never throws (a bug is logged and reported as failed). */
    public suspend fun run(req: WatchAppRequest): Outcome<Unit> {
        val r = try {
            execute(req)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.e(TAG, "watch-app ${req.op.key} failed", e)
            Outcome.Fail(SwError.Io("${req.op.key} failed"))
        }
        if (r is Outcome.Fail) SwLog.i(TAG, "watch-app ${req.op.key} on ${req.deviceId} failed: ${r.error}")
        publish(req, r)
        return r
    }

    /** Publishes "failed: [why]" for [req] without running it (the service is not connected). */
    public suspend fun refuse(req: WatchAppRequest, why: SwError) {
        publish(req, Outcome.Fail(why))
    }

    private suspend fun execute(req: WatchAppRequest): Outcome<Unit> {
        if (req.op == WatchAppOp.REFRESH) {
            return when (val r = control.refreshApps(req.deviceId)) {
                is Outcome.Ok -> Outcome.Ok(Unit)
                is Outcome.Fail -> r
            }
        }
        val app = req.appUuid ?: return Outcome.Fail(SwError.BadInput("no watch app given"))
        return when (req.op) {
            WatchAppOp.REFRESH -> Outcome.Ok(Unit)
            WatchAppOp.INSTALL -> {
                val prg = files.prg(app) ?: return Outcome.Fail(SwError.BadInput("no app file stored for $app"))
                send(req.deviceId, WatchCommand.InstallApp(prg, files.settingsJson(app)))
            }
            WatchAppOp.DELETE -> send(req.deviceId, WatchCommand.DeleteApp(app))
            WatchAppOp.LAUNCH -> send(req.deviceId, WatchCommand.LaunchApp(app))
            WatchAppOp.READ_SETTINGS -> control.requestSettings(req.deviceId, app)
            WatchAppOp.WRITE_SETTINGS -> {
                val values = req.valuesJson?.let { LinkValues.settingsFromJson(it) }
                    ?: return Outcome.Fail(SwError.BadInput("settings are not a JSON object"))
                val r = send(req.deviceId, WatchCommand.WriteAppSettings(app, values))
                if (r is Outcome.Ok) store.backupWrite(app, values)
                r
            }
        }
    }

    private suspend fun publish(req: WatchAppRequest, r: Outcome<Unit>) {
        val result = WatchAppResult(
            req.requestId, req.op, req.appUuid, r is Outcome.Ok,
            (r as? Outcome.Fail)?.let { WatchStatus.reason(it.error) }, clock.nowSec(),
        )
        try {
            settings.set(WatchAppResults.key(req.deviceId), WatchAppResults.encode(result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SwLog.w(TAG, "could not publish the watch-app result", e)
        }
    }

    private companion object {
        const val TAG = "WatchAppsCommander"
    }
}
