/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ui.components

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.selfwatch.ui.R
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A message shown while another is still on screen waits for that one to slide away. Material's
 * SnackbarManager holds the waiting one only weakly, so SwSnackbar holds it until it is shown;
 * otherwise a garbage collection in that moment dropped it (the food was logged, its Undo never came).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SwSnackbarQueueTest {

    @Test
    fun aWaitingMessageIsHeldUntilItIsShown() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_SelfWatch)
        val activity = controller.create().get()
        val root = FrameLayout(activity)
        activity.setContentView(root)
        controller.start().resume().visible()
        val looper = shadowOf(Looper.getMainLooper())

        SwSnackbar.undo(root, "First added") {}
        looper.idleFor(Duration.ofSeconds(1))
        assertTrue("the first message is shown", shows(root, "First added"))
        assertNull("a shown message is held by its view, not by SwSnackbar", SwSnackbar.waitingForTests)

        val second = SwSnackbar.undo(root, "Second added") {} // waits for "First added" to slide away
        assertSame("the waiting message is held", second, SwSnackbar.waitingForTests)
        System.gc()
        looper.idleFor(Duration.ofSeconds(2))

        assertTrue("the second message is shown", shows(root, "Second added"))
        assertNull("released once shown", SwSnackbar.waitingForTests)
        controller.pause().stop().destroy()
    }

    private fun shows(v: View, text: String): Boolean {
        fun walk(x: View): Boolean = when {
            x is TextView && x.isShown && x.text.toString() == text -> true
            x is ViewGroup -> (0 until x.childCount).any { walk(x.getChildAt(it)) }
            else -> false
        }
        return walk(v.rootView)
    }
}
