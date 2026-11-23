/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.sample.link

import android.content.Context
import java.util.UUID

/**
 * ============================== STUB ==============================
 *
 * The Garmin Connect side of [CompanionTransport]. It is deliberately empty.
 *
 * Self Watch's repository never contains Garmin's phone toolkit, its code, names or documentation,
 * and Self Watch's authors have not looked inside it. If your companion app also supports phones with
 * Garmin Connect, implement these four methods in your own app with Garmin's toolkit, under Garmin's
 * licence. The steps line up one to one with [SelfWatchTransport]: connect, find a connected watch,
 * register for your watch app's messages, send with a result, disconnect.
 *
 * ==================================================================
 */
class GarminToolkitTransport(private val context: Context) : CompanionTransport {
    override fun start(onStatus: (String) -> Unit) {
        // STUB: connect to Garmin Connect with Garmin's toolkit here.
        onStatus(context.getString(R.string.garmin_stub))
    }

    override fun listen(appUuid: UUID, onMessage: (Any?) -> Unit) {
        // STUB: register for messages from your watch app with Garmin's toolkit here.
    }

    override fun send(appUuid: UUID, message: Any?, onResult: (String) -> Unit) {
        // STUB: send with Garmin's toolkit here and report its result.
        onResult(context.getString(R.string.garmin_stub))
    }

    override fun stop() {
        // STUB: disconnect from Garmin Connect here.
    }
}
