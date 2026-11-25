/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.content.Context
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityManager
import app.selfwatch.ui.R
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import kotlin.math.max

/**
 * Short messages at the bottom of the screen, mainly "Deleted. Undo".
 *
 * A message with an action (Undo) stays [ACTION_DURATION_MS] so there is time to read it and reach the
 * button. With accessibility services on it follows the system's recommended timeout (Android 10+), or
 * stays until dismissed while touch exploration is on (older versions).
 */
object SwSnackbar {
    /** How long a message with an action stays, before any accessibility timeout is applied. */
    const val ACTION_DURATION_MS: Int = 8_000

    /** Shows [message] with an optional action. [anchor] keeps it above a FAB or bottom bar. */
    fun show(
        view: View,
        message: CharSequence,
        actionText: CharSequence? = null,
        anchor: View? = null,
        onAction: (() -> Unit)? = null,
    ): Snackbar {
        val hasAction = actionText != null && onAction != null
        val duration = if (hasAction) actionDuration(view.context) else Snackbar.LENGTH_SHORT
        val bar = Snackbar.make(view, message, duration)
        if (actionText != null && onAction != null) bar.setAction(actionText) { onAction() }
        if (anchor != null) bar.anchorView = anchor
        bar.animationMode = BaseTransientBottomBar.ANIMATION_MODE_SLIDE
        holdUntilShown(bar)
        bar.show()
        return bar
    }

    /**
     * The newest message that is not on screen yet. Material's SnackbarManager keeps a message that waits
     * for the current one to slide away only through a WeakReference, and until it is shown nothing else
     * holds it (callers drop the returned bar). A garbage collection in that moment silently dropped it:
     * the food was logged but its Undo never appeared. Once shown, the view in the window holds the bar.
     * Only the newest is kept, matching SnackbarManager's single "next" slot. Main thread only.
     */
    private var waiting: Snackbar? = null

    /** The message [holdUntilShown] is holding, for tests. */
    internal val waitingForTests: Snackbar? get() = waiting

    private fun holdUntilShown(bar: Snackbar) {
        waiting = bar
        bar.addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
            override fun onShown(transientBottomBar: Snackbar) = release(transientBottomBar)
            override fun onDismissed(transientBottomBar: Snackbar, event: Int) = release(transientBottomBar)
        })
    }

    private fun release(bar: Snackbar) {
        if (waiting === bar) waiting = null
    }

    /** "Deleted. Undo": calls [onUndo] if Undo is tapped before the message goes away. */
    fun undo(view: View, message: CharSequence, anchor: View? = null, onUndo: () -> Unit): Snackbar =
        show(view, message, view.context.getString(R.string.sw_undo), anchor, onUndo)

    /** Duration in ms (or [Snackbar.LENGTH_INDEFINITE]) for a message with an action on this device now. */
    fun actionDuration(context: Context): Int {
        val am = context.getSystemService(AccessibilityManager::class.java)
        return actionDuration(
            sdk = Build.VERSION.SDK_INT,
            touchExploration = am?.isTouchExplorationEnabled == true,
            recommendedMs = { base -> recommendedTimeout(am, base) },
        )
    }

    private fun recommendedTimeout(am: AccessibilityManager?, baseMs: Int): Int =
        if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            am.getRecommendedTimeoutMillis(baseMs, AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS)
        } else {
            baseMs
        }

    /**
     * Pure rule behind [actionDuration]: on Android 10+ the longer of [ACTION_DURATION_MS] and the
     * system's recommended timeout ([recommendedMs] is given the base duration); before that,
     * [Snackbar.LENGTH_INDEFINITE] while touch exploration (TalkBack) is on, else [ACTION_DURATION_MS].
     */
    internal fun actionDuration(sdk: Int, touchExploration: Boolean, recommendedMs: (Int) -> Int): Int = when {
        sdk >= Build.VERSION_CODES.Q -> max(ACTION_DURATION_MS, recommendedMs(ACTION_DURATION_MS))
        touchExploration -> Snackbar.LENGTH_INDEFINITE
        else -> ACTION_DURATION_MS
    }
}
