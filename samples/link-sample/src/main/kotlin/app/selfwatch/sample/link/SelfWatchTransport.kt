/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.sample.link

import android.content.Context
import app.selfwatch.link.client.LinkDevice
import app.selfwatch.link.client.SelfWatchLink
import app.selfwatch.link.client.SendStatus
import java.util.UUID

/** [CompanionTransport] over Self Watch's link client. */
class SelfWatchTransport(private val context: Context) : CompanionTransport {
    private var link: SelfWatchLink? = null
    private var watch: LinkDevice? = null

    override fun start(onStatus: (String) -> Unit) {
        link = SelfWatchLink.connect(context, object : SelfWatchLink.Listener {
            override fun onReady() {
                onStatus(context.getString(R.string.ready))
                link?.devices { status, devices ->
                    watch = devices.firstOrNull { it.isConnected }
                    val w = watch
                    onStatus(
                        when {
                            status != SendStatus.SUCCESS -> context.getString(R.string.unavailable, status.name)
                            w == null -> context.getString(R.string.no_watch)
                            else -> context.getString(R.string.watch_found, w.name)
                        },
                    )
                }
            }

            override fun onUnavailable(reason: SelfWatchLink.Reason) {
                onStatus(context.getString(R.string.unavailable, reason.name))
            }
        })
        link?.setDeviceStatusListener { device, connected ->
            if (connected) watch = device else if (watch?.id == device.id) watch = null
        }
    }

    override fun listen(appUuid: UUID, onMessage: (Any?) -> Unit) {
        link?.onMessage(appUuid) { _, message -> onMessage(message) }
    }

    override fun send(appUuid: UUID, message: Any?, onResult: (String) -> Unit) {
        val l = link
        val w = watch
        if (l == null || w == null) {
            onResult(context.getString(R.string.no_watch))
            return
        }
        onResult(context.getString(R.string.sending, w.name))
        l.send(w, appUuid, message) { status -> onResult(context.getString(R.string.send_result, status.name)) }
    }

    override fun stop() {
        link?.close()
        link = null
        watch = null
    }
}
