/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import android.content.Context
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.changelog.ChangeLogTokenRequest
import android.health.connect.changelog.ChangeLogsRequest
import android.health.connect.datatypes.Record
import android.os.Build
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One page of records from [HcApi.readRecords]; [nextPageToken] is null on the last page. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
data class HcPage(val records: List<Record>, val nextPageToken: String?)

/**
 * One page of changes from [HcApi.getChanges].
 *
 * @property upserts records inserted or updated since the token.
 * @property deletedIds Health Connect ids of records deleted since the token.
 * @property nextToken the token to ask with next time (or for the next page when [hasMore]).
 * @property tokenExpired the token was too old; nothing else in the page is meaningful.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
data class HcChangesPage(
    val upserts: List<Record>,
    val deletedIds: List<String>,
    val nextToken: String,
    val hasMore: Boolean,
    val tokenExpired: Boolean,
)

/** Health Connect refused a call because this app made too many; try again later. */
class HcRateLimitedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The part of Android's built-in Health Connect ([HealthConnectManager], Android 14+) that Self Watch
 * uses, so the mapping and sync logic can be tested with a fake. [HcClientApi] is the real one.
 *
 * Every call may throw [SecurityException] (a permission was revoked), [HcRateLimitedException] (quota
 * used up) or another exception (I/O, remote process died); callers turn those into results.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
interface HcApi {
    /** The Health Connect permissions this app holds now. */
    suspend fun grantedPermissions(): Set<String>

    /** Whether this phone's Health Connect defines [permission] (history and background read are newer). */
    fun permissionSupported(permission: String): Boolean

    /**
     * Inserts [records]; a record whose `clientRecordId` already exists for this app is updated when its
     * `clientRecordVersion` is higher. Returns the Health Connect ids. At most [HcWriter.BATCH_MAX] records.
     */
    suspend fun insert(records: List<Record>): List<String>

    /** A changes token for [types], starting now. */
    suspend fun changesToken(types: Set<Class<out Record>>): String

    /** The changes since [token]. */
    suspend fun getChanges(token: String): HcChangesPage

    /** One page of [type] records whose time lies in [start] until [end]. */
    suspend fun readRecords(type: Class<out Record>, start: Instant, end: Instant, pageToken: String?): HcPage
}

/**
 * [HcApi] backed by the platform [HealthConnectManager]. Its calls are callback based (an [Executor]
 * and an [OutcomeReceiver]); each is wrapped in a cancellable coroutine. Errors are normalised as
 * [HcApi] describes: `ERROR_SECURITY` becomes [SecurityException], `ERROR_RATE_LIMIT_EXCEEDED`
 * becomes [HcRateLimitedException].
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class HcClientApi(private val ctx: Context, private val manager: HealthConnectManager) : HcApi {

    override suspend fun grantedPermissions(): Set<String> = HcPermissions.granted(ctx)

    override fun permissionSupported(permission: String): Boolean = HcPermissions.defined(ctx, permission)

    override suspend fun insert(records: List<Record>): List<String> = call { cb ->
        manager.insertRecords(records, DIRECT, receiver(cb) { r -> r.records.map { it.metadata.id } })
    }

    override suspend fun changesToken(types: Set<Class<out Record>>): String = call { cb ->
        val req = ChangeLogTokenRequest.Builder().apply { types.forEach { addRecordType(it) } }.build()
        manager.getChangeLogToken(req, DIRECT, receiver(cb) { it.token })
    }

    override suspend fun getChanges(token: String): HcChangesPage = try {
        call { cb ->
            manager.getChangeLogs(ChangeLogsRequest.Builder(token).build(), DIRECT, receiver(cb) { r ->
                HcChangesPage(
                    upserts = r.upsertedRecords,
                    deletedIds = r.deletedLogs.map { it.deletedRecordId },
                    nextToken = r.nextChangesToken,
                    hasMore = r.hasMorePages(),
                    tokenExpired = false,
                )
            })
        }
    } catch (e: HealthConnectException) {
        // The platform refuses an expired (or unknown) token as an invalid argument.
        if (e.errorCode != HealthConnectException.ERROR_INVALID_ARGUMENT) throw e
        HcChangesPage(emptyList(), emptyList(), token, hasMore = false, tokenExpired = true)
    }

    override suspend fun readRecords(type: Class<out Record>, start: Instant, end: Instant, pageToken: String?): HcPage = call { cb ->
        @Suppress("UNCHECKED_CAST")
        val builder = ReadRecordsRequestUsingFilters.Builder(type as Class<Record>)
            .setTimeRangeFilter(TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build())
            .setPageSize(PAGE_SIZE)
        pageToken?.toLongOrNull()?.let { builder.setPageToken(it) }
        manager.readRecords(builder.build(), DIRECT, receiver(cb) { r ->
            HcPage(r.records, r.nextPageToken.takeIf { it >= 0 }?.toString())
        })
    }

    /** An [OutcomeReceiver] that maps the result with [map] and hands it (or the error) to [cb]. */
    private fun <R, T> receiver(cb: (Result<T>) -> Unit, map: (R) -> T): OutcomeReceiver<R, HealthConnectException> =
        object : OutcomeReceiver<R, HealthConnectException> {
            override fun onResult(result: R) {
                cb(runCatching { map(result) })
            }

            override fun onError(error: HealthConnectException) {
                cb(Result.failure(error))
            }
        }

    /**
     * Runs [start] (which hands its outcome to the callback once) as a suspending call, and normalises
     * errors: [HealthConnectException] codes become [SecurityException] / [HcRateLimitedException];
     * an expired-token refusal stays a [HealthConnectException] for [getChanges].
     */
    private suspend fun <T> call(start: ((Result<T>) -> Unit) -> Unit): T = try {
        suspendCancellableCoroutine { cont ->
            start { r ->
                if (cont.isActive) r.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HealthConnectException) {
        when (e.errorCode) {
            HealthConnectException.ERROR_SECURITY -> throw SecurityException(e.message, e)
            HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED -> throw HcRateLimitedException("health connect rate limit", e)
            else -> throw e
        }
    } catch (e: SecurityException) {
        throw e
    } catch (e: Exception) {
        if (HcErrors.isRateLimit(e)) throw HcRateLimitedException("health connect rate limit", e)
        throw e
    }

    private companion object {
        const val PAGE_SIZE = 1000

        /** Callbacks run on the binder thread that delivers them; they only resume a coroutine. */
        val DIRECT: Executor = Executor { it.run() }
    }
}

/** Recognises Health Connect rate-limit refusals that do not arrive as a typed error code. */
object HcErrors {
    /**
     * True when [e] (or a cause) is a rate-limit refusal: the platform exception's error code, or a
     * "rate limit" / "quota" message.
     */
    fun isRateLimit(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < MAX_DEPTH) {
            if (isPlatformRateLimit(t)) return true
            val msg = t.message?.lowercase(Locale.ROOT).orEmpty()
            if ("rate limit" in msg || "quota" in msg) return true
            t = t.cause
            depth++
        }
        return false
    }

    private fun isPlatformRateLimit(t: Throwable): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return t is HealthConnectException && t.errorCode == HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED
    }

    private const val MAX_DEPTH = 6
}
