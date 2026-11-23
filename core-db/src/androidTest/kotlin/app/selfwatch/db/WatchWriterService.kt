/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.db

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import app.selfwatch.model.health.ActivityMinute
import app.selfwatch.model.health.MetricKind
import app.selfwatch.model.health.MetricSample
import kotlinx.coroutines.runBlocking

/**
 * Runs in process `:watch` and writes to `selfwatch.db` like the real sync service: short
 * transactions of minute data, metrics and settings. When done it stores its own result in settings
 * (`test.writer.ops`, `test.writer.failures`) so the main process can read it.
 */
class WatchWriterService : Service() {
    @Volatile
    private var started = false

    override fun onBind(intent: Intent): IBinder {
        val ops = intent.getIntExtra(EXTRA_OPS, 5000)
        if (!started) {
            started = true
            Thread({ runWrites(ops) }, "watch-writer").start()
        }
        return Binder()
    }

    private fun runWrites(ops: Int) {
        val db = SelfWatchDb.get(this)
        var failures = 0
        var done = 0
        runBlocking {
            for (i in 0 until ops) {
                try {
                    val t = BASE_SEC + i * 3600L
                    when (i % 3) {
                        0 -> db.activity.insert((0 until 60).map { ActivityMinute(1, t + it * 60L, it, 10, 100, it % 4, null) })
                        1 -> db.metrics.insert((0 until 30).map { MetricSample(1, MetricKind.HEART_RATE, t + it * 60L, 60 + it) })
                        else -> db.settings.set("test.writer.progress", i.toString())
                    }
                    done++
                } catch (e: Exception) {
                    failures++
                    Log.e(TAG, "write $i failed", e)
                }
            }
            db.settings.setAll(mapOf(KEY_FAILURES to failures.toString(), KEY_OPS to done.toString()))
        }
        Log.i(TAG, "writer finished: $done ok, $failures failed")
    }

    companion object {
        const val EXTRA_OPS = "ops"
        const val KEY_OPS = "test.writer.ops"
        const val KEY_FAILURES = "test.writer.failures"
        const val BASE_SEC = 1_790_000_000L
        private const val TAG = "WatchWriter"
    }
}
