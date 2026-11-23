/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.selfwatch.model.time.Day
import app.selfwatch.model.time.TimeRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * B08: the `:watch` process writes 5,000 short transactions while this process does 5,000 mixed
 * reads and writes on the same file. No operation may fail with SQLITE_BUSY (or anything else), and
 * change notices from the other process must arrive here by broadcast (B06).
 */
@RunWith(AndroidJUnit4::class)
class TwoProcessTest {

    @Test
    fun watchProcessWritesWhileAppReads(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase(SelfWatchDb.NAME)
        val db = SelfWatchDb.get(ctx)
        db.writableDatabase // create the schema before the other process starts

        val remoteActivityNotices = AtomicInteger()
        val watcher = launch(Dispatchers.Default) {
            // This process never writes ACTIVITY, so these notices can only come from :watch.
            db.changes.changes.collect { if (Table.ACTIVITY in it) remoteActivityNotices.incrementAndGet() }
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val intent = Intent(ctx, WatchWriterService::class.java).putExtra(WatchWriterService.EXTRA_OPS, OPS)
        assertTrue(ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE))

        val failures = AtomicInteger()
        val started = System.nanoTime()
        try {
            for (i in 0 until OPS) {
                try {
                    when (i % 5) {
                        0 -> db.activity.minutes(TimeRange(WatchWriterService.BASE_SEC, WatchWriterService.BASE_SEC + 86_400L))
                        1 -> db.settings.get("test.writer.progress")
                        2 -> db.metrics.series(app.selfwatch.model.health.MetricKind.HEART_RATE, TimeRange(WatchWriterService.BASE_SEC, WatchWriterService.BASE_SEC + 86_400L))
                        3 -> db.water.add(Day(20261123), i.toLong(), 1) // a write from this process too
                        else -> db.summaries.days(app.selfwatch.model.time.DayRange(Day(20260101), Day(20261231)))
                    }
                } catch (e: Exception) {
                    failures.incrementAndGet()
                    Log.e(TAG, "main op $i failed", e)
                }
            }
            val mainMs = (System.nanoTime() - started) / 1_000_000
            // Wait for the writer to finish (it records its result in settings).
            var waited = 0
            while (db.settings.get(WatchWriterService.KEY_OPS) == null && waited < 300_000) {
                delay(200)
                waited += 200
            }
            delay(1000) // let the last broadcasts arrive
            val writerOps = db.settings.getInt(WatchWriterService.KEY_OPS)
            val writerFailures = db.settings.getInt(WatchWriterService.KEY_FAILURES)
            val totalMs = (System.nanoTime() - started) / 1_000_000
            Log.i(
                PERF_TAG,
                "two-process: main $OPS ops in $mainMs ms, failures ${failures.get()}; writer ops $writerOps, " +
                    "failures $writerFailures; total $totalMs ms; remote ACTIVITY notices ${remoteActivityNotices.get()}",
            )
            assertEquals(0, failures.get())
            assertEquals(OPS, writerOps)
            assertEquals(0, writerFailures)
            assertTrue("change notices from :watch must arrive", remoteActivityNotices.get() > 0)
            assertEquals(OPS / 5, db.water.dayTotal(Day(20261123)))
        } finally {
            ctx.unbindService(conn)
            watcher.cancel()
        }
    }

    private companion object {
        const val OPS = 5000
        const val TAG = "TwoProcessTest"
        const val PERF_TAG = "SelfWatchPerf"
    }
}
