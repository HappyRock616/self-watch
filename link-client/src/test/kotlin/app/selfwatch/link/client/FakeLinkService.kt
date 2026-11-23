/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.link.client

import app.selfwatch.link.ILinkCallback
import app.selfwatch.link.ILinkService

/** In-process stand-in for Self Watch's link service. Records calls; answers are configurable. */
class FakeLinkService : ILinkService.Stub() {
    var version: Int = 1
    var deviceJson: List<String> = listOf("""{"id":42,"name":"vívoactive 5","connected":true}""")
    var appInfoJson: String = """{"installed":true,"version":12}"""
    var sendCode: Int = LinkStatus.SUCCESS
    var openCode: Int = LinkStatus.SUCCESS
    var throwOnSend: RuntimeException? = null

    val callbacks: MutableMap<String, ILinkCallback> = linkedMapOf()
    val calls: MutableList<String> = mutableListOf()
    val sent: MutableList<Triple<Long, String, ByteArray>> = mutableListOf()
    val requestIds: MutableList<Int> = mutableListOf()

    override fun apiVersion(): Int {
        calls += "apiVersion"
        return version
    }

    override fun devices(): List<String> {
        calls += "devices"
        return deviceJson
    }

    override fun appInfo(deviceId: Long, appUuid: String): String {
        calls += "appInfo $deviceId $appUuid"
        return appInfoJson
    }

    override fun register(appUuid: String, cb: ILinkCallback) {
        calls += "register $appUuid"
        callbacks[appUuid] = cb
    }

    override fun unregister(appUuid: String) {
        calls += "unregister $appUuid"
        callbacks.remove(appUuid)
    }

    override fun send(deviceId: Long, appUuid: String, message: ByteArray, requestId: Int): Int {
        calls += "send $deviceId $appUuid"
        throwOnSend?.let { throw it }
        sent += Triple(deviceId, appUuid, message)
        requestIds += requestId
        return sendCode
    }

    override fun openApp(deviceId: Long, appUuid: String): Int {
        calls += "openApp $deviceId $appUuid"
        return openCode
    }
}
