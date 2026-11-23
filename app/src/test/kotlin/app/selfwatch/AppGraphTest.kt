/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch

import android.app.Application
import app.selfwatch.log.LogSinks
import app.selfwatch.model.log.LogSink
import app.selfwatch.model.log.SwLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppGraphTest {
    private val ctx: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(Job())

    @After
    fun tearDown() {
        scope.cancel()
        SwLog.sink = LogSink.NONE
    }

    @Test
    fun nothingIsBuiltUpFront() {
        val g = AppGraph(ctx)
        assertFalse(g.isDbBuilt)
        assertFalse(g.isFoodBuilt)
        assertFalse(g.isInteropBuilt)
    }

    @Test
    fun settingsDoNotBuildTheFoodOrInteropGraphs() {
        val g = AppGraph(ctx)
        assertNotNull(g.settings)
        assertNotNull(g.service)
        assertTrue(g.isDbBuilt)
        assertFalse(g.isFoodBuilt)
        assertFalse(g.isInteropBuilt)
        assertNotNull(g.food)
        assertTrue(g.isFoodBuilt)
        assertFalse(g.isInteropBuilt)
    }

    @Test
    fun watchProcessStartInstallsTheLogButNeverBuildsTheGraph() {
        val file = AppStartup.start(ctx, ProcessRole.WATCH, scope) { fail("graph built in :watch"); AppGraph(ctx) }
        SwLog.i("Test", "hello from watch")
        file.flush()
        assertTrue(file.current.readText().contains("I/Test: hello from watch"))
        assertTrue(file.current.parentFile == LogSinks.dir(ctx))
        assertTrue(file.current.name == "watch.log")
    }

    @Test
    fun mainProcessStartBuildsTheGraphButNotFood() {
        var built: AppGraph? = null
        AppStartup.start(ctx, ProcessRole.MAIN, scope) { AppGraph(ctx).also { built = it } }
        val g = built ?: error("main process must build the graph for the theme")
        assertFalse(g.isFoodBuilt)
        assertFalse(g.isInteropBuilt)
    }
}
