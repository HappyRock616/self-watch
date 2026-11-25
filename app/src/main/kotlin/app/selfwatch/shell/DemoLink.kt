/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import android.content.Context
import android.content.Intent

/**
 * The link to the debug-only demo data screen (`src/debug`, class [CLASS_NAME]). Release builds have
 * no such class and hide the menu items (`DebugTools.ENABLED` is false), so this is never used
 * there. The same intent works from adb:
 * `adb shell am start -n app.selfwatch.debug/app.selfwatch.demo.DemoActivity --es action load`.
 */
object DemoLink {
    /** The debug activity's class name. */
    const val CLASS_NAME: String = "app.selfwatch.demo.DemoActivity"

    /** Extra: what to do ([ACTION_LOAD] or [ACTION_CLEAR]). */
    const val EXTRA_ACTION: String = "action"

    /** Fill the database with generated data. */
    const val ACTION_LOAD: String = "load"

    /** Remove the generated data. */
    const val ACTION_CLEAR: String = "clear"

    /** The intent that runs [action]. */
    fun intent(ctx: Context, action: String): Intent =
        Intent().setClassName(ctx, CLASS_NAME).putExtra(EXTRA_ACTION, action)
}
