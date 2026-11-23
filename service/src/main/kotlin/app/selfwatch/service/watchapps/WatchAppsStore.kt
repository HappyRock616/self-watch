/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.watchapps

import android.content.Context
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.db.dao.WatchAppRow
import app.selfwatch.db.dao.WatchAppSettings
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Clock
import app.selfwatch.model.watch.CiqValue
import app.selfwatch.model.watch.WatchAppKind
import app.selfwatch.model.watch.WatchEvent
import app.selfwatch.service.device.WatchAppsHandler
import app.selfwatch.service.device.WatchLink
import app.selfwatch.service.link.LinkValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Sideloaded watch apps on the phone: `files/watchapps/<uuid>/` holds [PRG_FILE], and optionally the
 * settings definition [SETTINGS_FILE] and the app's strings resource [STRINGS_FILE], so the settings form
 * keeps working after the install. Usable from any process of the app (it is plain files).
 */
public class SideloadStore(private val root: File) {
    /** The folder of [appUuid]. */
    public fun dir(appUuid: UUID): File = File(root, appUuid.toString())

    /** Stores the files of [appUuid], replacing earlier ones; null leaves an optional file out (removing an old one). */
    public suspend fun save(appUuid: UUID, prg: ByteArray, settingsJson: String?, stringsXml: String? = null): Outcome<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val d = dir(appUuid)
                if (!d.isDirectory && !d.mkdirs()) return@withContext Outcome.Fail(SwError.Io("could not create ${d.name}"))
                write(File(d, PRG_FILE), prg)
                writeOrDelete(File(d, SETTINGS_FILE), settingsJson)
                writeOrDelete(File(d, STRINGS_FILE), stringsXml)
                Outcome.Ok(Unit)
            } catch (e: IOException) {
                SwLog.w(TAG, "could not store watch app $appUuid", e)
                Outcome.Fail(SwError.Io("could not store the watch app"))
            }
        }

    /** The stored `.prg` of [appUuid], or null. */
    public suspend fun prg(appUuid: UUID): ByteArray? = read(File(dir(appUuid), PRG_FILE))

    /** The stored settings definition of [appUuid], or null. */
    public suspend fun settingsJson(appUuid: UUID): String? = read(File(dir(appUuid), SETTINGS_FILE))?.toString(Charsets.UTF_8)

    /** The stored strings resource of [appUuid], or null. */
    public suspend fun stringsXml(appUuid: UUID): String? = read(File(dir(appUuid), STRINGS_FILE))?.toString(Charsets.UTF_8)

    /** Removes everything stored for [appUuid]. */
    public suspend fun delete(appUuid: UUID): Boolean = withContext(Dispatchers.IO) { dir(appUuid).deleteRecursively() }

    private suspend fun read(f: File): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (f.isFile && f.length() <= MAX_FILE_BYTES) f.readBytes() else null
        } catch (e: IOException) {
            SwLog.w(TAG, "could not read ${f.name}", e)
            null
        }
    }

    private fun writeOrDelete(f: File, text: String?) {
        if (text == null) {
            if (f.exists() && !f.delete()) throw IOException("could not delete ${f.name}")
        } else {
            write(f, text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun write(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw IOException("could not replace ${f.name}")
        }
    }

    /** File names. */
    public companion object {
        private const val TAG = "SideloadStore"

        /** The app file. */
        public const val PRG_FILE: String = "app.prg"

        /** The settings definition (`<app>-settings.json`). */
        public const val SETTINGS_FILE: String = "settings.json"

        /** The app's strings resource (Connect IQ `strings.xml`). */
        public const val STRINGS_FILE: String = "strings.xml"

        /** Largest file read back (bytes). */
        public const val MAX_FILE_BYTES: Long = 8L * 1024 * 1024

        /** The store under the app's `files/watchapps/`. */
        public fun forContext(ctx: Context): SideloadStore = SideloadStore(File(ctx.filesDir, "watchapps"))
    }
}

/**
 * Keeps `watch_app` in step with the watch's app list and keeps settings copies in `watch_app_settings`
 * (B26). As the [WatchAppsHandler]: [onInstalledApps] upserts every listed app (installed) and marks the
 * other rows of that watch not installed; [onAppSettings] stores the values the watch reported.
 * [backupWrite] stores what was written after a successful write. [form] builds the settings editor's model.
 */
public class WatchAppsStore(
    private val db: SelfWatchDb,
    private val clock: Clock,
    private val files: SideloadStore,
    private val language: () -> String? = { null },
    private val keepCopies: Int = KEEP_COPIES,
) : WatchAppsHandler {

    override suspend fun onInstalledApps(from: WatchLink, event: WatchEvent.InstalledApps) {
        sync(from.deviceId, event)
    }

    override suspend fun onAppSettings(from: WatchLink, event: WatchEvent.AppSettings) {
        save(event.appUuid, event.values)
    }

    /** Applies [event] to watch [deviceId]'s rows. */
    public suspend fun sync(deviceId: Long, event: WatchEvent.InstalledApps) {
        val now = clock.nowSec()
        val listed = HashSet<UUID>()
        for (a in event.apps) {
            listed.add(a.uuid)
            db.watchApps.upsert(WatchAppRow(deviceId, a.uuid, a.name, a.version, kindCode(a.kind), true, now))
        }
        for (row in db.watchApps.forDevice(deviceId)) {
            if (row.appUuid !in listed) db.watchApps.setInstalled(deviceId, row.appUuid, false, now)
        }
        SwLog.i(TAG, "watch $deviceId: ${listed.size} apps")
    }

    /** Stores [values] as the newest settings copy of [appUuid] (with its definition, when sideloaded). */
    public suspend fun backupWrite(appUuid: UUID, values: Map<String, CiqValue>) {
        save(appUuid, values)
    }

    /** The newest saved settings of [appUuid], or null. */
    public suspend fun latestValues(appUuid: UUID): Map<String, CiqValue>? =
        db.watchApps.latestSettings(appUuid)?.let { LinkValues.settingsFromJson(it.valuesJson) }

    /**
     * The settings editor's model for [appUuid]: from the sideloaded (or last saved) definition with its
     * strings, else from the last values (raw keys), else empty.
     */
    public suspend fun form(appUuid: UUID): SettingsForm {
        val saved = db.watchApps.latestSettings(appUuid)
        val definition = files.settingsJson(appUuid) ?: saved?.definitionJson
        if (definition != null) {
            val strings = files.stringsXml(appUuid)?.let { SettingsDefinitionParser.parseStrings(it) }.orEmpty()
            when (val f = SettingsDefinitionParser.parse(definition, strings, language())) {
                is Outcome.Ok -> return f.value
                is Outcome.Fail -> SwLog.w(TAG, "settings definition of $appUuid unreadable: ${f.error}")
            }
        }
        val values = saved?.let { LinkValues.settingsFromJson(it.valuesJson) }.orEmpty()
        return SettingsForm.fromValues(values)
    }

    private suspend fun save(appUuid: UUID, values: Map<String, CiqValue>) {
        val definition = files.settingsJson(appUuid) ?: db.watchApps.latestSettings(appUuid)?.definitionJson
        db.watchApps.saveSettings(WatchAppSettings(appUuid, clock.nowSec(), LinkValues.settingsToJson(values), definition))
        db.watchApps.pruneSettings(appUuid, keepCopies)
    }

    /** Constants. */
    public companion object {
        private const val TAG = "WatchAppsStore"

        /** Settings copies kept per app. */
        public const val KEEP_COPIES: Int = 20

        /** `watch_app.kind`: the Connect IQ app type code (1 app, 2 widget, 3 watch face, 4 data field, 0 other). */
        public fun kindCode(kind: WatchAppKind): Int = when (kind) {
            WatchAppKind.APP -> 1
            WatchAppKind.WIDGET -> 2
            WatchAppKind.WATCH_FACE -> 3
            WatchAppKind.DATA_FIELD -> 4
            WatchAppKind.OTHER -> 0
        }

        /** The kind for a `watch_app.kind` code. */
        public fun kindOf(code: Int?): WatchAppKind = when (code) {
            1 -> WatchAppKind.APP
            2 -> WatchAppKind.WIDGET
            3 -> WatchAppKind.WATCH_FACE
            4 -> WatchAppKind.DATA_FIELD
            else -> WatchAppKind.OTHER
        }
    }
}
