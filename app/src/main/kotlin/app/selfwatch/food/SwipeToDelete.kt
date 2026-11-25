/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.sign

/**
 * Swipe a row sideways to delete it (rows built in code, not in a RecyclerView). A short tap still
 * reaches the row's click listener; a mostly-horizontal drag moves the row, and letting go past
 * [THRESHOLD] of its width calls [onDelete] (the screen then offers Undo), else it springs back.
 */
internal class SwipeToDelete(private val onDelete: () -> Unit) : View.OnTouchListener {
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    override fun onTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                swiping = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!swiping && abs(dx) > ViewConfiguration.get(v.context).scaledTouchSlop && abs(dx) > abs(dy) * 2) {
                    swiping = true
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    // Stop the row's own press and click.
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    v.onTouchEvent(cancel)
                    cancel.recycle()
                }
                if (!swiping) return false
                v.translationX = dx
                v.alpha = 1f - (abs(dx) / v.width.coerceAtLeast(1)).coerceAtMost(1f) * FADE
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!swiping) return false
                swiping = false
                val dx = ev.rawX - downX
                if (abs(dx) >= v.width * THRESHOLD) {
                    v.animate().translationX(sign(dx) * v.width).alpha(0f).setDuration(ANIM_MS).start()
                    onDelete()
                } else {
                    v.animate().translationX(0f).alpha(1f).setDuration(ANIM_MS).start()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!swiping) return false
                swiping = false
                v.animate().translationX(0f).alpha(1f).setDuration(ANIM_MS).start()
                return true
            }
        }
        return false
    }

    /** Swipe distance and timing. */
    companion object {
        /** Share of the row's width a swipe must pass to delete. */
        const val THRESHOLD: Float = 0.4f
        private const val FADE = 0.7f
        private const val ANIM_MS = 150L
    }
}
