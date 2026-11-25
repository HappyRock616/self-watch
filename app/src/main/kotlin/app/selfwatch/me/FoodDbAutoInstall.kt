/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import app.selfwatch.data.shortText
import app.selfwatch.food.install.UpdateCheck
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.net.AndroidConnectivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Where the automatic food database download is. */
sealed interface AutoFoodDbState {
    /** Nothing to show (installed, turned off in the Privacy center, or done and acknowledged). */
    data object Idle : AutoFoodDbState

    /** Not on Wi-Fi: queued until Wi-Fi. */
    data object WaitingForWifi : AutoFoodDbState

    /** Fetching the manifest. */
    data object Checking : AutoFoodDbState

    /** Downloading: bytes so far and the total when known. */
    data class Downloading(val done: Long, val total: Long?) : AutoFoodDbState

    /** Installed [version] (shown once, then [FoodDbAutoInstall.acknowledge]). */
    data class Installed(val version: Int) : AutoFoodDbState

    /** The published file needs a newer app (shown once). */
    data object NeedsNewerApp : AutoFoodDbState

    /** Failed with [detail] (shown once). */
    data class Failed(val detail: String) : AutoFoodDbState
}

/** Knows whether the phone is on Wi-Fi (or another unmetered network) and waits for it. */
interface WifiWaiter {
    /** True on Wi-Fi now. */
    fun onWifi(): Boolean

    /** Returns once on Wi-Fi (at once when already). */
    suspend fun awaitWifi()
}

/** [WifiWaiter] from Android's `ConnectivityManager` (a network callback, no polling). */
class AndroidWifiWaiter(ctx: Context) : WifiWaiter {
    private val app = ctx.applicationContext ?: ctx
    private val connectivity = AndroidConnectivity(app)

    override fun onWifi(): Boolean = connectivity.state().let { it.connected && it.unmetered }

    override suspend fun awaitWifi() {
        if (onWifi()) return
        val cm = ContextCompat.getSystemService(app, ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            .build()
        var registered: ConnectivityManager.NetworkCallback? = null
        try {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                try {
                    cm.registerNetworkCallback(request, cb)
                    registered = cb
                } catch (e: RuntimeException) {
                    SwLog.w(TAG, "network callback refused", e)
                    cont.resume(Unit)
                }
            }
        } finally {
            // Registered only while waiting.
            registered?.let { cb -> runCatching { cm.unregisterNetworkCallback(cb) } }
        }
    }

    private companion object {
        const val TAG = "AndroidWifiWaiter"
    }
}

/**
 * Downloads foods.db by itself the first time food entry opens ([ensure]): only when it is not
 * installed and the Privacy center allows food data, through the same check/install path as Me ›
 * Food data ([FoodDataOps], Wi-Fi only). Off Wi-Fi it waits for Wi-Fi in [scope] (the app's food
 * scope, so leaving the screen doesn't stop it). It runs at most once per app run: a failure is shown
 * once and not retried until the next run or until the user retries in Me › Food data.
 */
class FoodDbAutoInstall(
    private val ops: FoodDataOps,
    private val allowed: suspend () -> Boolean,
    private val wifi: WifiWaiter,
    private val scope: CoroutineScope,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    private val mutable = MutableStateFlow<AutoFoodDbState>(AutoFoodDbState.Idle)

    /** Where it is. */
    val state: StateFlow<AutoFoodDbState> = mutable.asStateFlow()

    private var job: Job? = null
    private var done = false

    /** Starts the download when it is needed and allowed. Returns at once; safe to call on every screen open. */
    @Synchronized
    fun ensure(): Job? {
        if (done || job?.isActive == true) return job
        return scope.launch { run() }.also { job = it }
    }

    /** The one-time message ([AutoFoodDbState.Installed], [AutoFoodDbState.Failed]…) was shown. */
    fun acknowledge() {
        val s = mutable.value
        if (s is AutoFoodDbState.Installed || s is AutoFoodDbState.Failed || s == AutoFoodDbState.NeedsNewerApp) {
            mutable.value = AutoFoodDbState.Idle
        }
    }

    private suspend fun run() {
        if (ops.installedVersion() != null || !allowed()) return
        repeat(MAX_TRIES) {
            if (!wifi.onWifi()) {
                mutable.value = AutoFoodDbState.WaitingForWifi
                wifi.awaitWifi()
            }
            // The user may have installed it by hand or turned it off while we waited.
            if (ops.installedVersion() != null || !allowed()) {
                mutable.value = AutoFoodDbState.Idle
                return
            }
            mutable.value = AutoFoodDbState.Checking
            when (val r = attempt()) {
                null -> {
                    // Lost Wi-Fi or offline: wait again (a short pause so a flapping network doesn't spin).
                    mutable.value = AutoFoodDbState.WaitingForWifi
                    delay(retryDelayMs)
                }
                else -> {
                    finish(r)
                    return
                }
            }
        }
        finish(AutoFoodDbState.Failed(SwError.Offline.shortText()))
    }

    /** One check and install; null when it should wait for Wi-Fi and try again. */
    private suspend fun attempt(): AutoFoodDbState? {
        val m = when (val c = ops.check()) {
            is Outcome.Fail -> return failure(c.error)
            is Outcome.Ok -> when (val u = c.value) {
                is UpdateCheck.Available -> u.manifest
                UpdateCheck.UpToDate -> return AutoFoodDbState.Idle
                is UpdateCheck.NeedsNewerApp -> return AutoFoodDbState.NeedsNewerApp
            }
        }
        mutable.value = AutoFoodDbState.Downloading(0, m.sizeGz)
        return when (val r = ops.install(m) { d, t -> mutable.value = AutoFoodDbState.Downloading(d, t) }) {
            is Outcome.Ok -> AutoFoodDbState.Installed(r.value)
            is Outcome.Fail -> failure(r.error)
        }
    }

    private fun failure(e: SwError): AutoFoodDbState? = when (e) {
        SwError.Offline, SwError.WaitingForWifi -> null
        is SwError.Blocked -> AutoFoodDbState.Idle
        else -> AutoFoodDbState.Failed(e.shortText())
    }

    private fun finish(s: AutoFoodDbState) {
        SwLog.i(TAG, "automatic food database download: $s")
        done = true
        mutable.value = s
    }

    /** Limits. */
    companion object {
        private const val TAG = "FoodDbAutoInstall"
        private const val MAX_TRIES = 5
        private const val RETRY_DELAY_MS = 30_000L
    }
}
