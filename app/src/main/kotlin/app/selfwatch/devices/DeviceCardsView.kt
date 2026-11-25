/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.ble.pair.PairedDevice
import app.selfwatch.ble.pair.Pairing
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceKind
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.service.device.WatchStatus
import app.selfwatch.shell.Views
import app.selfwatch.ui.R as UiR
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch

/**
 * The Devices page cards: one per paired device (watches, straps, scales, cuffs) in the user's order,
 * each with its type icon, name, connection state, battery, last sync and (for straps) live heart rate.
 *
 * - Tap a card: connect or disconnect that device (several connect at once: one watch plus every sensor).
 * - Trash icon: remove the device, after a confirmation that can also delete its data.
 * - Touch and hold 3 seconds (a ring fills around the icon): reset the device's data, workouts kept,
 *   after a confirmation. TalkBack offers the same as the "Reset data" action.
 * - Watches: the Exclusive switch, and "Sync now" on the watch in use.
 */
class DeviceCardsView(
    private val fragment: Fragment,
    private val container: LinearLayout,
    private val vm: DevicesViewModel,
    private val pairing: () -> Pairing,
    private val onSyncNow: () -> Unit,
) {
    private var shown: List<DeviceCard>? = null
    private var shownBusy = false

    /** True while a card is being held: rebuilding would cancel the hold, so updates wait. */
    private var holding = false
    private var pending: (() -> Unit)? = null

    /** Shows [devices]; [primaryId] is the first device of the order. */
    fun show(devices: List<DeviceCard>, primaryId: Long?, busy: Boolean) {
        if (holding) {
            pending = { show(devices, primaryId, busy) }
            return
        }
        if (devices == shown && busy == shownBusy) return
        shown = devices
        shownBusy = busy
        container.removeAllViews()
        val strap = devices.firstOrNull { it.kind == DeviceKind.HEART_RATE_SENSOR && it.state.isConnected() }
        devices.forEachIndexed { i, d -> addCard(d, d.id == primaryId, i == 0, busy, strap.takeIf { d.isWatch && d.wanted }) }
    }

    private fun addCard(d: DeviceCard, primary: Boolean, first: Boolean, busy: Boolean, strap: DeviceCard?) {
        val ctx = container.context
        val card = Views.card(container, null, first)
        val connected = d.state.isConnected()

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Views.dp(ctx, 56)
            isClickable = true
            isFocusable = true
            background = ripple(ctx)
        }
        val iconBox = FrameLayout(ctx)
        val icon = ImageView(ctx).apply {
            setImageResource(iconFor(d.kind))
            imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(container, if (connected) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant),
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val ring = CircularProgressIndicator(ctx).apply {
            isIndeterminate = false
            max = HOLD_STEPS
            indicatorSize = Views.dp(ctx, 48)
            trackThickness = Views.dp(ctx, 4)
            setIndicatorColor(MaterialColors.getColor(container, androidx.appcompat.R.attr.colorError))
            isVisible = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val size = Views.dp(ctx, 48)
        iconBox.addView(icon, FrameLayout.LayoutParams(Views.dp(ctx, 28), Views.dp(ctx, 28), Gravity.CENTER))
        iconBox.addView(ring, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        body.addView(iconBox, LinearLayout.LayoutParams(size, size))

        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val name = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_Title, d.name)
        val status = Views.text(ctx, UiR.style.TextAppearance_SelfWatch_BodyLarge, stateText(ctx, d)).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val detail = Views.caption(ctx, detail(ctx, d, primary))
        texts.addView(name)
        texts.addView(status, Views.matchWrap(ctx, 2))
        texts.addView(detail, Views.matchWrap(ctx, 2))
        body.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Views.dp(ctx, 12) })

        val trash = ImageButton(ctx).apply {
            setImageResource(R.drawable.devices_ic_trash)
            imageTintList = ColorStateList.valueOf(MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant))
            background = ripple(ctx, borderless = true)
            contentDescription = ctx.getString(R.string.devices_trash_cd, d.name)
            isEnabled = !busy
            setOnClickListener { confirmRemove(d) }
        }
        body.addView(trash, LinearLayout.LayoutParams(size, size))
        card.addView(body, Views.matchWrap(ctx))

        body.contentDescription = ctx.getString(R.string.devices_card_cd, d.name, status.text)
        body.setOnClickListener { if (!busy) toggle(d) }
        ViewCompat.addAccessibilityAction(body, ctx.getString(R.string.devices_reset_action)) { _, _ ->
            confirmReset(d)
            true
        }
        HoldToReset(body, ring, ::onHolding) { confirmReset(d) }.attach()

        if (d.isWatch) {
            val exclusive = DevicesUi.switch(card.content as LinearLayout, ctx.getString(R.string.devices_exclusive))
            exclusive.isChecked = d.exclusive
            exclusive.setOnCheckedChangeListener { _, on -> vm.setExclusive(d.id, on) }
            DevicesUi.note(card.content as LinearLayout, ctx.getString(R.string.devices_exclusive_note))
            if (strap != null) DevicesUi.note(card.content as LinearLayout, ctx.getString(R.string.devices_hr_from_strap, strap.name))
            if (d.wanted) {
                val sync = DevicesUi.button(card.content as LinearLayout, ctx.getString(R.string.devices_sync_now)) { onSyncNow() }
                sync.isEnabled = !busy
            }
        }
    }

    private fun onHolding(on: Boolean) {
        holding = on
        if (!on) {
            val p = pending
            pending = null
            // After the current touch event: the card being released may be the one rebuilt.
            if (p != null) container.post(p)
        }
    }

    private fun toggle(d: DeviceCard) {
        val app = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            val on = vm.toggle(d)
            val text = when {
                !on -> app.getString(R.string.devices_disconnected_from, d.name)
                d.isWatch && !d.activeWatch -> app.getString(R.string.devices_switch_watch, d.name)
                else -> app.getString(R.string.devices_connecting_to, d.name)
            }
            DevicesUi.toast(app, text)
        }
    }

    private fun confirmReset(d: DeviceCard) {
        val ctx = fragment.context ?: return
        val app = ctx.applicationContext
        DevicesUi.confirm(ctx, ctx.getString(R.string.devices_reset_title, d.name), ctx.getString(R.string.devices_reset_message), R.string.devices_reset_confirm) {
            fragment.lifecycleScope.launch {
                vm.resetData(d)
                DevicesUi.toast(app, app.getString(R.string.devices_reset_done))
            }
        }
    }

    private fun confirmRemove(d: DeviceCard) {
        val ctx = fragment.requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Views.dp(ctx, 24)
            setPadding(pad, Views.dp(ctx, 8), pad, 0)
        }
        val also = MaterialCheckBox(ctx).apply {
            text = ctx.getString(R.string.devices_remove_also_data)
            minHeight = Views.dp(ctx, 48)
        }
        box.addView(also, Views.matchWrap(ctx))
        MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.devices_remove_title, d.name))
            .setMessage(if (d.isWatch) R.string.devices_remove_message else R.string.devices_remove_device_message)
            .setView(box)
            .setPositiveButton(R.string.devices_other_remove) { _, _ -> remove(d, also.isChecked) }
            .setNegativeButton(R.string.devices_not_now, null)
            .show()
    }

    /** Removes [d] (and its data when [withData]); also used for "Remove watch" from Watch settings. */
    fun remove(d: DeviceCard, withData: Boolean) {
        val app = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            vm.setBusy(true)
            try {
                val r = pairing().unpair(PairedDevice(d.address, d.name, d.associationId))
                if (r is Outcome.Fail) SwLog.w(TAG, "unpair: ${r.error}")
                vm.forget(d, withData)
                val fully = r is Outcome.Ok && r.value.bondRemoved
                val text = when {
                    !fully -> R.string.devices_device_remove_forget
                    withData -> R.string.devices_remove_with_data_done
                    else -> R.string.devices_device_removed
                }
                DevicesUi.toast(app, app.getString(text), long = !fully)
            } finally {
                vm.setBusy(false)
            }
        }
    }

    /** Asks before removing [d] (Watch settings › Remove watch). */
    fun askRemove(d: DeviceCard) = confirmRemove(d)

    /**
     * Touch and hold [HOLD_MS] on [target] to fire [onDone]: [ring] appears after a short moment and
     * fills; letting go early is a normal tap (if quick) or nothing; scrolling away cancels.
     */
    private class HoldToReset(
        private val target: View,
        private val ring: CircularProgressIndicator,
        private val onHolding: (Boolean) -> Unit,
        private val onDone: () -> Unit,
    ) {
        private var animator: ValueAnimator? = null
        private var downAt = 0L
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(target.context).scaledTouchSlop
        private val tapMs = ViewConfiguration.getLongPressTimeout().toLong()

        @SuppressLint("ClickableViewAccessibility") // a quick release calls performClick; TalkBack has the custom action
        fun attach() {
            target.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downAt = e.eventTime
                        downX = e.x
                        downY = e.y
                        v.isPressed = true
                        start()
                    }
                    MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) {
                        v.isPressed = false
                        stop()
                    }
                    MotionEvent.ACTION_UP -> {
                        val wasRunning = animator != null
                        v.isPressed = false
                        stop()
                        if (wasRunning && e.eventTime - downAt < tapMs) v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        stop()
                    }
                }
                true
            }
        }

        private fun start() {
            stop()
            ring.progress = 0
            val a = ValueAnimator.ofInt(0, HOLD_STEPS).setDuration(HOLD_MS)
            a.interpolator = LinearInterpolator()
            a.addUpdateListener {
                val p = it.animatedValue as Int
                if (it.currentPlayTime >= SHOW_RING_MS) ring.isVisible = true
                ring.progress = p
            }
            a.addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || animator !== animation) return
                    animator = null
                    ring.isVisible = false
                    target.isPressed = false
                    target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onHolding(false)
                    onDone()
                }
            })
            animator = a
            onHolding(true)
            a.start()
        }

        private fun stop() {
            val a = animator ?: return
            animator = null
            a.cancel()
            ring.isVisible = false
            ring.progress = 0
            onHolding(false)
        }
    }

    /** Text helpers (pure, for tests). */
    companion object {
        private const val TAG = "DeviceCards"

        /** How long to hold a card to reset its data. */
        const val HOLD_MS: Long = 3_000L
        private const val HOLD_STEPS = 100
        private const val SHOW_RING_MS = 250L

        /** The type icon of [kind]. */
        @DrawableRes
        fun iconFor(kind: DeviceKind): Int = when (kind) {
            DeviceKind.GARMIN_WATCH -> R.drawable.ic_tab_devices
            DeviceKind.HEART_RATE_SENSOR -> R.drawable.devices_ic_heart
            DeviceKind.WEIGHT_SCALE -> R.drawable.devices_ic_scale
            DeviceKind.BLOOD_PRESSURE_MONITOR -> R.drawable.devices_ic_gauge
        }

        /** "Primary · Garmin watch · Battery 80% · Last sync Today 08:12 · Heart rate 72 bpm" for [d]. */
        fun detail(ctx: Context, d: DeviceCard, primary: Boolean = false): String {
            val parts = ArrayList<String>()
            val kind = ctx.getString(SensorType.label(d.kind))
            parts += if (primary) ctx.getString(R.string.device_order_primary, kind) else kind
            d.batteryPct?.let { parts += ctx.getString(R.string.devices_battery, it) }
            when {
                d.lastSyncSec != null -> parts += ctx.getString(R.string.devices_last_sync, DevicesFragment.whenText(ctx, d.lastSyncSec))
                d.isWatch -> parts += ctx.getString(R.string.devices_never_synced)
            }
            d.heartRateNow?.let { parts += ctx.getString(R.string.devices_hr_now, it) }
            return parts.joinToString(" · ")
        }

        /** The state line: off and not-in-use first; watches use the watch wording; a sensor not connected is asleep. */
        fun stateText(ctx: Context, d: DeviceCard): String {
            val s = d.state
            if (d.userOff) return ctx.getString(R.string.devices_state_user_off)
            if (!d.activeWatch) return ctx.getString(R.string.devices_state_standby)
            if (d.isWatch) return DevicesFragment.statusText(ctx, s)
            return when {
                s is ConnectionState.Ready || s is ConnectionState.Syncing -> ctx.getString(R.string.devices_state_ready)
                s is ConnectionState.Connecting || s is ConnectionState.Handshaking -> ctx.getString(R.string.devices_state_connecting)
                s is ConnectionState.Failed && s.retryInSec == null ->
                    ctx.getString(R.string.devices_state_failed, WatchStatus.reason(s.error))
                else -> ctx.getString(R.string.devices_sensor_waiting)
            }
        }

        private fun ConnectionState?.isConnected(): Boolean = this is ConnectionState.Ready || this is ConnectionState.Syncing

        private fun ripple(ctx: Context, borderless: Boolean = false): android.graphics.drawable.Drawable? {
            val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(attr, tv, true)
            return androidx.core.content.ContextCompat.getDrawable(ctx, tv.resourceId)
        }
    }
}
