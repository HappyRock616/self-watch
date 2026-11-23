/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.log

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import app.selfwatch.model.log.LogSink
import java.io.File

/** Mirrors messages to logcat. Installed in debuggable builds only. */
class LogcatSink : LogSink {
    override fun write(level: Int, tag: String, msg: String, t: Throwable?) {
        val text = if (t == null) msg else msg + '\n' + Log.getStackTraceString(t)
        Log.println(level, tag, text)
    }
}

/** Sends every message to each of [sinks]. */
class TeeLogSink(private val sinks: List<LogSink>) : LogSink {
    override fun write(level: Int, tag: String, msg: String, t: Throwable?) {
        for (s in sinks) s.write(level, tag, msg, t)
    }
}

/** Builds the process's log sink: the rotating file in `filesDir/logs`, plus logcat when debuggable. */
object LogSinks {
    /** The log folder. */
    fun dir(ctx: Context): File = File(ctx.filesDir, "logs")

    /** The sink for a process whose files start with [baseName]. */
    fun create(ctx: Context, baseName: String): FileLogSink = FileLogSink(dir(ctx), baseName)

    /** [file], tee'd with logcat in debuggable builds. */
    fun withLogcatIfDebuggable(ctx: Context, file: FileLogSink): LogSink {
        val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (debuggable) TeeLogSink(listOf(file, LogcatSink())) else file
    }
}
