/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch

import android.app.Application
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * Which of the app's processes is running. Only [MAIN] does app setup (theme, graph); [WATCH] (the
 * always-on service) and anything unexpected get the log sink only.
 */
enum class ProcessRole(
    /** File name prefix for this process's log. */
    val logName: String,
) {
    /** The UI process (process name = package name). */
    MAIN("app"),

    /** The always-on service process (`<package>:watch`). */
    WATCH("watch"),

    /** Any other process. */
    OTHER("other"),
    ;

    /** True when app-only setup (theme, AppGraph) may run. */
    val doesAppSetup: Boolean get() = this == MAIN

    /** Process name parsing. */
    companion object {
        /** The suffix of the service process. */
        const val WATCH_SUFFIX: String = ":watch"

        /**
         * The role of [processName] in the app [packageName]. An unknown (null or blank) name counts as
         * [MAIN], since only the main process starts without a declared process name.
         */
        fun of(processName: String?, packageName: String): ProcessRole = when {
            processName.isNullOrBlank() || processName == packageName -> MAIN
            processName == packageName + WATCH_SUFFIX -> WATCH
            else -> OTHER
        }

        /** This process's name: `Application.getProcessName()` on API 28+, else `/proc/self/cmdline`. */
        fun currentProcessName(): String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                try {
                    File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }
                        .toByteArray().toString(Charsets.UTF_8).trim()
                } catch (e: IOException) {
                    System.err.println("cannot read process name: ${e.message}")
                    null
                }
            }
    }
}
