/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle

/**
 * Keeps one fragment per tab in [containerId], switching with `show()` / `hide()` so each tab keeps its
 * state. A tab's fragment is created the first time it is shown. Hidden tabs are capped at STARTED, the
 * shown tab is RESUMED. Fragments restored after a configuration change are found again by tag.
 */
class TabHost(private val fm: FragmentManager, @param:IdRes private val containerId: Int) {

    /** Shows [tab], hiding every other tab. Runs the transaction at once. */
    fun show(tab: Tab) {
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        for (t in Tab.entries) {
            val f = fm.findFragmentByTag(t.tag)
            if (t == tab) {
                if (f == null) {
                    tx.add(containerId, t.newFragment(), t.tag)
                } else {
                    tx.show(f).setMaxLifecycle(f, Lifecycle.State.RESUMED)
                }
            } else if (f != null && !f.isHidden) {
                tx.hide(f).setMaxLifecycle(f, Lifecycle.State.STARTED)
            }
        }
        tx.commitNow()
    }

    /** The fragment of [tab], if it was ever shown. */
    fun fragment(tab: Tab): Fragment? = fm.findFragmentByTag(tab.tag)
}
