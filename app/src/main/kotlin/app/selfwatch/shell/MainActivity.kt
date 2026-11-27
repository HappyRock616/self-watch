/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.onboarding.OnboardingActivity
import app.selfwatch.onboarding.OnboardingViewModel
import app.selfwatch.service.SyncCommands
import app.selfwatch.ui.SwActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch

/**
 * The shell: five tabs in a bottom navigation bar, each a fragment kept with show / hide, and the +
 * button (Body and Food) that opens the [AddSheet]. Back from any tab but Body returns to Body; on
 * Body it leaves the app (system predictive back animation). Back is handled only through the
 * OnBackPressedDispatcher.
 */
class MainActivity : SwActivity() {

    private val vm: MainViewModel by viewModels { MainViewModel.factory(graph) }

    private lateinit var tabs: TabHost
    private lateinit var nav: BottomNavigationView
    private lateinit var fab: FloatingActionButton

    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // A detail screen on top closes first (this callback outranks the fragment manager's).
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            } else {
                select(Tab.BODY)
            }
        }
    }

    private val openAppSync by lazy {
        OpenAppSync(
            nowSec = { System.currentTimeMillis() / 1000 },
            lastSyncSec = { graph.db.devices.active().mapNotNull { graph.db.devices.lastSync(it.id) }.maxOrNull() },
            hasWatch = { graph.db.devices.active().isNotEmpty() },
            requestSync = { SyncCommands.start(applicationContext, SyncCommands.ACTION_SYNC_NOW) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nav = findViewById(R.id.bottom_nav)
        fab = findViewById(R.id.add_fab)
        tabs = TabHost(supportFragmentManager, R.id.tab_container)

        nav.isItemActiveIndicatorEnabled = false
        nav.setOnItemSelectedListener { item ->
            Tab.ofItem(item.itemId)?.let { show(it) }
            true
        }
        nav.setOnItemReselectedListener { }
        fab.setOnClickListener { openAddSheet() }
        padFabForSideInsets(fab)
        onBackPressedDispatcher.addCallback(this, backToHome)
        val tabLayer = findViewById<View>(R.id.tab_layer)
        supportFragmentManager.addOnBackStackChangedListener { hideTabsFromTalkBack(tabLayer) }
        hideTabsFromTalkBack(tabLayer)

        select(vm.tab.value)

        // First launch: onboarding on top (C21); it sets onboarding.done when finished or skipped.
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                if (OnboardingViewModel.needed(graph.settings)) startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.foodEnabled.collect { updateFab() } }
                // Fresh data while the app is on screen (no need to tap Sync now); see OpenAppSync.
                launch { openAppSync.runWhileOnScreen() }
            }
        }
    }

    /** Selects [tab] as if its bottom bar item was tapped. */
    fun select(tab: Tab) {
        if (nav.selectedItemId != tab.itemId) {
            nav.selectedItemId = tab.itemId // calls show() through the item listener
        } else {
            show(tab)
        }
    }

    private fun show(tab: Tab) {
        vm.select(tab)
        tabs.show(tab)
        backToHome.isEnabled = tab != Tab.BODY
        updateFab()
    }

    // While a detail screen covers the tabs, TalkBack must not reach the views underneath.
    private fun hideTabsFromTalkBack(tabLayer: View) {
        tabLayer.importantForAccessibility = if (supportFragmentManager.backStackEntryCount > 0) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    private fun updateFab() {
        val tab = vm.tab.value
        val visible = tab.hasAddButton && (tab != Tab.FOOD || vm.foodEnabled.value)
        if (visible) fab.show() else fab.hide()
    }

    private fun openAddSheet() {
        if (supportFragmentManager.findFragmentByTag(AddSheet.TAG) != null) return
        AddSheet.newInstance(vm.foodEnabled.value).show(supportFragmentManager, AddSheet.TAG)
    }

    private fun padFabForSideInsets(view: View) {
        val lp = view.layoutParams as ViewGroup.MarginLayoutParams
        val baseStart = lp.marginStart
        val baseEnd = lp.marginEnd
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val rtl = v.layoutDirection == View.LAYOUT_DIRECTION_RTL
            (v.layoutParams as ViewGroup.MarginLayoutParams).apply {
                marginStart = baseStart + if (rtl) bars.right else bars.left
                marginEnd = baseEnd + if (rtl) bars.left else bars.right
            }
            v.requestLayout()
            insets
        }
    }
}
