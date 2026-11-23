/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.sample.link

import java.util.UUID

/**
 * The small switch a companion app needs to talk to its watch app through either Self Watch or
 * Garmin Connect. The rest of the app only sees this interface. All callbacks run on the main thread.
 */
interface CompanionTransport {
    /** Starts connecting. [onStatus] gets short human-readable status lines. */
    fun start(onStatus: (String) -> Unit)

    /** Receives messages from watch app [appUuid]. */
    fun listen(appUuid: UUID, onMessage: (Any?) -> Unit)

    /** Sends [message] (Map, List, String, number, Boolean or null) to watch app [appUuid]. */
    fun send(appUuid: UUID, message: Any?, onResult: (String) -> Unit)

    /** Disconnects. */
    fun stop()
}
