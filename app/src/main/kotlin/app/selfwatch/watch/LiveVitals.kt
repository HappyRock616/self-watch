/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.selfwatch.cards.CardHost
import app.selfwatch.db.SelfWatchDb
import app.selfwatch.devices.ActiveWatches
import app.selfwatch.model.log.SwLog
import app.selfwatch.service.SyncCommands
import app.selfwatch.service.live.LiveReading
import app.selfwatch.service.live.LiveRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Asks the `:watch` service for live readings (the real one sends an intent; tests use a fake). */
fun interface LiveRequests {
    /** Live readings wanted ([enable]) or no longer shown. */
    fun request(enable: Boolean)
}

/**
 * The screens' side of live heart rate and steps. Live readings cost watch battery, so they are on only
 * while at least one card that shows them is on screen ([hold]) and a watch is [connected]: then the
 * service is asked every [renewMs] (its lease lapses on its own if this process dies); when the last
 * holder leaves or the watch disconnects, it is told to stop at once. The newest reading is [reading].
 */
class LiveVitals(
    scope: CoroutineScope,
    private val requests: LiveRequests,
    connected: Flow<Boolean>,
    private val renewMs: Long = LiveRelay.RENEW_MS,
) {
    private val holders = MutableStateFlow(0)
    private val latest = MutableStateFlow<LiveReading?>(null)

    /** The newest live reading this process heard of (null until one arrives). */
    val reading: StateFlow<LiveReading?> = latest.asStateFlow()

    /** True while the service is being asked for live readings. */
    val asking: StateFlow<Boolean> get() = askingFlow
    private val askingFlow = MutableStateFlow(false)

    init {
        scope.launch {
            combine(holders.map { it > 0 }.distinctUntilChanged(), connected.distinctUntilChanged()) { held, on -> held && on }
                .distinctUntilChanged()
                .collectLatest { on ->
                    if (!on) return@collectLatest
                    askingFlow.value = true
                    try {
                        while (true) {
                            requests.request(true)
                            delay(renewMs)
                        }
                    } finally {
                        askingFlow.value = false
                        requests.request(false)
                    }
                }
        }
    }

    /** A new reading from the service. */
    fun onReading(r: LiveReading) {
        latest.value = r
    }

    /** Keeps the live readings on for as long as the calling coroutine runs (cancel it to let go). */
    suspend fun hold(): Nothing {
        holders.update { it + 1 }
        try {
            awaitCancellation()
        } finally {
            holders.update { it - 1 }
        }
    }

    /** The process-wide instance and the card helper. */
    companion object {
        @Volatile
        private var shared: LiveVitals? = null

        /** This process's instance: requests go to the service by intent, readings come back by broadcast. */
        fun get(ctx: Context): LiveVitals = shared ?: synchronized(this) {
            shared ?: create(ctx.applicationContext).also { shared = it }
        }

        private fun create(app: Context): LiveVitals {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val db = SelfWatchDb.get(app)
            val vitals = LiveVitals(
                scope,
                { enable -> if (!SyncCommands.live(app, enable)) SwLog.i(TAG, "live request refused by Android") },
                ActiveWatches.observe(db).map { it?.connected == true },
            )
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    LiveReading.fromIntent(intent)?.let(vitals::onReading)
                }
            }
            // Not exported: only this app's own processes (the :watch service) can send it.
            ContextCompat.registerReceiver(app, receiver, IntentFilter(LiveReading.BROADCAST), ContextCompat.RECEIVER_NOT_EXPORTED)
            return vitals
        }

        /**
         * Runs [onReading] with each live reading while [view] is on screen: its tab is the one shown (the
         * screen resumed), and at least part of the view is inside the window. Only then are live readings
         * held on; scrolling the card away or leaving the screen lets them go.
         */
        fun follow(host: CardHost, view: View, onReading: (LiveReading?) -> Unit) {
            val owner = host.fragment.viewLifecycleOwner
            val vitals = get(host.context)
            owner.lifecycleScope.launch {
                owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    val visible = OnScreen(view)
                    try {
                        launch { vitals.reading.collect { onReading(it) } }
                        // Refresh now and then too, so "live" and "minutes ago" stay true without new readings.
                        launch {
                            while (true) {
                                delay(TICK_MS)
                                onReading(vitals.reading.value)
                            }
                        }
                        visible.state.collectLatest { shown -> if (shown) vitals.hold() }
                    } finally {
                        visible.close()
                    }
                }
            }
        }

        private const val TAG = "LiveVitals"
        private const val TICK_MS = 15_000L
    }
}

/** Whether [view] is attached and at least partly inside the window; follows scrolling and layout. */
private class OnScreen(private val view: View) {
    private val rect = Rect()
    private val mutable = MutableStateFlow(check())

    /** True while some of the view is on screen. */
    val state: StateFlow<Boolean> = mutable.asStateFlow()

    private val scroll = ViewTreeObserver.OnScrollChangedListener { mutable.value = check() }
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { mutable.value = check() }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            listen()
            mutable.value = check()
        }

        override fun onViewDetachedFromWindow(v: View) {
            mutable.value = false
            v.viewTreeObserver.removeOnScrollChangedListener(scroll)
            v.viewTreeObserver.removeOnGlobalLayoutListener(layout)
        }
    }

    init {
        view.addOnAttachStateChangeListener(attach)
        if (view.isAttachedToWindow) listen()
    }

    private fun listen() {
        view.viewTreeObserver.addOnScrollChangedListener(scroll)
        view.viewTreeObserver.addOnGlobalLayoutListener(layout)
    }

    private fun check(): Boolean = view.isAttachedToWindow && view.isShown && view.getGlobalVisibleRect(rect) && rect.height() > 0

    fun close() {
        view.removeOnAttachStateChangeListener(attach)
        view.viewTreeObserver.removeOnScrollChangedListener(scroll)
        view.viewTreeObserver.removeOnGlobalLayoutListener(layout)
    }
}
