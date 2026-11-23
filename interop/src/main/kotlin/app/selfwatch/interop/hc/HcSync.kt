/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import android.content.Context
import android.os.Build
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.model.time.Clock
import app.selfwatch.model.time.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/** Which way [HcSync.runOnce] syncs. */
enum class HcDirection { WRITE, READ, BOTH }

/** Why a run did nothing (or skipped its read half). */
enum class HcSkip {
    /** Setting `hc.enabled` is off. */
    DISABLED,

    /** Health Connect is not available on this phone. */
    UNAVAILABLE,

    /** Waiting after a rate-limit refusal (`hc.backoff_until`). */
    BACKING_OFF,

    /** A background run without background read (setting or permission): only the write half ran. */
    BACKGROUND_READ_OFF,
}

/**
 * What one run did.
 *
 * @property written records written per [HcType.key].
 * @property read rows stored per [HcType.key].
 * @property skipped set when the run (or its read half) was skipped.
 */
data class HcSyncReport(val written: Map<String, Int>, val read: Map<String, Int>, val skipped: HcSkip?)

/**
 * Health Connect sync orchestrator. The app calls [runOnce] on open (foreground) and after each watch
 * sync (pass `background = true` when the app is not in the foreground) when `hc.enabled` is on.
 *
 * - Writes first ([HcWriter]), then reads ([HcReader]); each type only when its permission is granted.
 * - Background runs read only when `hc.background_read` is on and [HcPermissions.BACKGROUND] is granted.
 * - **Permission revoked:** no needed permission granted, or Health Connect refuses one mid-run, gives
 *   `Outcome.Fail(SwError.PermissionMissing(permission))`; progress already made is kept.
 * - **Rate limit:** each call is retried briefly ([HcRetry]); if still refused the run stops with
 *   `Outcome.Fail(SwError.RateLimited(seconds until the next try))` and further runs are skipped for 1, 2,
 *   4 … up to 60 minutes.
 * - **Token expiry:** handled by [HcReader] (re-reads the last 30 days).
 * - Runs never overlap within a process.
 */
class HcSync(
    private val db: SelfWatchDb,
    private val apiProvider: () -> HcApi?,
    private val ownPackage: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: Clock = SystemClock,
    private val retry: HcRetry = HcRetry(),
) {
    /** Sync for this process's database and the phone's Health Connect. */
    constructor(ctx: Context) : this(SelfWatchDb.get(ctx), { HcAvailability.api(ctx) }, ctx.packageName)

    /** One sync run in [direction]; see the class description for results. */
    suspend fun runOnce(direction: HcDirection = HcDirection.BOTH, background: Boolean = false): Outcome<HcSyncReport> = lock.withLock {
        val settings = db.settings
        if (!settings.getBool(HcKeys.ENABLED)) return@withLock skip(HcSkip.DISABLED)
        val now = clock.nowSec()
        if ((settings.getLong(HcKeys.BACKOFF_UNTIL) ?: 0L) > now) return@withLock skip(HcSkip.BACKING_OFF)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@withLock skip(HcSkip.UNAVAILABLE)
        val api = apiProvider() ?: return@withLock skip(HcSkip.UNAVAILABLE)
        guarded {
            val granted = retry.run { api.grantedPermissions() }
            val doWrite = direction != HcDirection.READ
            var doRead = direction != HcDirection.WRITE
            val wantWrite = if (doWrite) HcType.WRITTEN.map { it.writePermission } else emptyList()
            val wantRead = if (doRead) HcType.READ.map { it.readPermission } else emptyList()
            val wanted = wantWrite + wantRead
            if (wanted.none { it in granted }) {
                return@guarded Outcome.Fail(SwError.PermissionMissing(wanted.first()))
            }
            var skipped: HcSkip? = null
            if (doRead && background && !(settings.getBool(HcKeys.BACKGROUND_READ) && HcPermissions.BACKGROUND in granted)) {
                doRead = false
                skipped = HcSkip.BACKGROUND_READ_OFF
            }
            val written = if (doWrite) HcWriter(db, api, zone, clock, retry).writeAll(granted) else emptyMap()
            val read = if (doRead) HcReader(db, api, ownPackage, zone, clock, retry).readAll(granted) else emptyMap()
            Outcome.Ok(HcSyncReport(written.mapKeys { it.key.key }, read.mapKeys { it.key.key }, skipped))
        }
    }

    /**
     * Explicit import of [types] from [startSec] to now. Data older than 30 days needs
     * [HcPermissions.HISTORY] (asked for only on this explicit request); without it the result is
     * `PermissionMissing(HISTORY)`. Does not need `hc.enabled`.
     */
    suspend fun importRange(startSec: Long, types: Collection<HcType> = HcType.READ): Outcome<HcSyncReport> = lock.withLock {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@withLock skip(HcSkip.UNAVAILABLE)
        val api = apiProvider() ?: return@withLock skip(HcSkip.UNAVAILABLE)
        guarded {
            val granted = retry.run { api.grantedPermissions() }
            val now = clock.nowSec()
            if (startSec < now - HcReader.WINDOW_SEC && HcPermissions.HISTORY !in granted) {
                return@guarded Outcome.Fail(SwError.PermissionMissing(HcPermissions.HISTORY))
            }
            val allowed = types.filter { it.read && it.readPermission in granted }
            if (allowed.isEmpty()) {
                return@guarded Outcome.Fail(SwError.PermissionMissing(types.firstOrNull { it.read }?.readPermission ?: HcPermissions.HISTORY))
            }
            val read = HcReader(db, api, ownPackage, zone, clock, retry).readRange(allowed, startSec, now)
            Outcome.Ok(HcSyncReport(emptyMap(), read.mapKeys { it.key.key }, null))
        }
    }

    private fun skip(why: HcSkip): Outcome<HcSyncReport> = Outcome.Ok(HcSyncReport(emptyMap(), emptyMap(), why))

    private suspend fun guarded(block: suspend () -> Outcome<HcSyncReport>): Outcome<HcSyncReport> {
        val settings = db.settings
        return try {
            val r = block()
            if (r is Outcome.Ok) settings.setAll(mapOf(HcKeys.BACKOFF_STEP to null, HcKeys.BACKOFF_UNTIL to null))
            r
        } catch (e: CancellationException) {
            throw e
        } catch (e: HcPermissionException) {
            SwLog.w(TAG, "permission missing: ${e.permission}")
            Outcome.Fail(SwError.PermissionMissing(e.permission))
        } catch (e: SecurityException) {
            SwLog.w(TAG, "permission refused", e)
            Outcome.Fail(SwError.PermissionMissing(HcPermissions.writePermissions().first()))
        } catch (e: HcRateLimitedException) {
            val step = (settings.getInt(HcKeys.BACKOFF_STEP) ?: 0).coerceIn(0, MAX_STEP)
            val waitSec = backoffSec(step)
            settings.setAll(
                mapOf(HcKeys.BACKOFF_STEP to (step + 1).toString(), HcKeys.BACKOFF_UNTIL to (clock.nowSec() + waitSec).toString()),
            )
            SwLog.w(TAG, "rate limited, waiting $waitSec s")
            Outcome.Fail(SwError.RateLimited(waitSec.toInt()))
        } catch (e: Exception) {
            SwLog.e(TAG, "sync failed", e)
            Outcome.Fail(SwError.Io("health connect: ${e.javaClass.simpleName}"))
        }
    }

    /** Constants. */
    companion object {
        private const val TAG = "HcSync"
        private const val MAX_STEP = 6
        private val lock = Mutex()

        /** Back-off after [step] earlier refusals: 60 s × 2^step, at most an hour. */
        fun backoffSec(step: Int): Long = (60L shl step.coerceIn(0, MAX_STEP)).coerceAtMost(3600L)
    }
}
