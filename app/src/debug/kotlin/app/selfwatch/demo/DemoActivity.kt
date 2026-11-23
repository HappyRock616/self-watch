/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.demo

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.selfwatch.R
import app.selfwatch.graph
import app.selfwatch.model.log.SwLog
import app.selfwatch.shell.DemoLink
import app.selfwatch.ui.SwActivity
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import app.selfwatch.ui.R as UiR

/**
 * Debug builds only: runs "Load demo data" or "Clear demo data" ([DemoLink.EXTRA_ACTION]) with a
 * progress message, then closes. Reached from Body's menu or from adb:
 * `adb shell am start -n app.selfwatch.debug/app.selfwatch.demo.DemoActivity --es action load`.
 */
class DemoActivity : SwActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra(DemoLink.EXTRA_ACTION) ?: DemoLink.ACTION_LOAD
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        root.addView(CircularProgressIndicator(this).apply { isIndeterminate = true })
        root.addView(
            TextView(this).apply {
                setTextAppearance(UiR.style.TextAppearance_SelfWatch_BodyLarge)
                text = getString(if (action == DemoLink.ACTION_CLEAR) R.string.demo_clearing else R.string.demo_loading)
                gravity = Gravity.CENTER
            },
        )
        setRootContent(root)
        if (savedInstanceState != null) return
        val demo = DemoData(applicationContext, graph.db)
        lifecycleScope.launch {
            val msg = try {
                if (action == DemoLink.ACTION_CLEAR) {
                    demo.clear()
                    getString(R.string.demo_cleared)
                } else {
                    val summary = demo.load()
                    SwLog.i(TAG, "demo: $summary")
                    getString(R.string.demo_loaded)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.e(TAG, "demo data failed", e)
                getString(R.string.demo_failed, e.javaClass.simpleName)
            }
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private companion object {
        const val TAG = "DemoActivity"
    }
}
