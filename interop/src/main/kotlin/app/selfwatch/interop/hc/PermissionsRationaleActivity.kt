/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.hc

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import app.selfwatch.interop.R

/**
 * Shows the Health Connect privacy text (what is read and written and why; no network). Android 14+
 * Health Connect opens it from its permission screen through the `ViewPermissionUsageActivity` alias
 * (`android.intent.action.VIEW_PERMISSION_USAGE` + `android.intent.category.HEALTH_PERMISSIONS`),
 * declared in interop's manifest and merged into the app.
 */
class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.hc_rationale)
        findViewById<Button>(R.id.hc_rationale_close).setOnClickListener { finish() }
    }
}
