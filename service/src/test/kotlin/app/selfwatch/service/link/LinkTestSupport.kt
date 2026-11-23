/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.link

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import app.selfwatch.link.ILinkCallback
import app.selfwatch.model.device.ConnectionState
import app.selfwatch.model.device.DeviceInfo
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.WatchCommand
import app.selfwatch.service.device.DeviceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Watches for link tests: a settable device list, recorded sends and scripted answers. */
class FakeWatches : WatchAccess {
    val list = MutableStateFlow<List<DeviceStatus>>(emptyList())
    val sent = CopyOnWriteArrayList<Pair<Long, WatchCommand>>()
    val answers = ArrayDeque<Outcome<Unit>>()

    override val devices: StateFlow<List<DeviceStatus>> get() = list

    override suspend fun send(deviceId: Long, cmd: WatchCommand): Outcome<Unit> {
        sent.add(deviceId to cmd)
        return synchronized(answers) { answers.removeFirstOrNull() } ?: Outcome.Ok(Unit)
    }

    fun set(info: DeviceInfo, state: ConnectionState) {
        list.value = list.value.filter { it.info.id != info.id } + DeviceStatus(info, state, null)
    }
}

/** A callback that records what it gets. */
class RecordingCallback : ILinkCallback.Stub() {
    val messages = CopyOnWriteArrayList<Triple<Long, String, ByteArray>>()
    val results = CopyOnWriteArrayList<Pair<Int, Int>>()
    val statuses = CopyOnWriteArrayList<Pair<Long, Boolean>>()

    override fun onMessage(deviceId: Long, appUuid: String, message: ByteArray) {
        messages.add(Triple(deviceId, appUuid, message))
    }

    override fun onSendResult(requestId: Int, status: Int) {
        results.add(requestId to status)
    }

    override fun onDeviceStatus(deviceId: Long, connected: Boolean) {
        statuses.add(deviceId to connected)
    }
}

/** A broadcaster that records (package, device, app, json). */
class RecordingBroadcaster : LinkBroadcaster {
    data class Sent(val pkg: String, val deviceId: Long, val appUuid: UUID, val message: ByteArray, val json: String)

    val sent = CopyOnWriteArrayList<Sent>()

    override fun broadcast(packageName: String, deviceId: Long, appUuid: UUID, message: ByteArray, json: String) {
        sent.add(Sent(packageName, deviceId, appUuid, message, json))
    }
}

/** A prompt that records questions. */
class FakePrompt : ApprovalPrompt {
    val asked = CopyOnWriteArrayList<ApprovalRequest>()
    val cancelled = CopyOnWriteArrayList<Pair<String?, UUID>>()

    override suspend fun ask(request: ApprovalRequest) {
        asked.add(request)
    }

    override fun cancel(packageName: String?, appUuid: UUID) {
        cancelled.add(packageName to appUuid)
    }
}

/** Installs [pkg] for [uid] in Robolectric's package manager, signed with [cert]. */
fun installSigned(ctx: Context, pkg: String, uid: Int, cert: ByteArray) {
    val pi = PackageInfo()
    pi.packageName = pkg
    pi.applicationInfo = ApplicationInfo().also {
        it.packageName = pkg
        it.uid = uid
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val si = Shadow.newInstanceOf(SigningInfo::class.java)
        Shadow.extract<ShadowSigningInfo>(si).setSignatures(arrayOf(Signature(cert)))
        pi.signingInfo = si
    }
    @Suppress("DEPRECATION")
    pi.signatures = arrayOf(Signature(cert))
    val spm = shadowOf(ctx.packageManager)
    spm.installPackage(pi)
    spm.setPackagesForUid(uid, pkg)
}

/** The digest [PackageSignerReader] gives for [cert]. */
fun digestOf(cert: ByteArray): String = PackageSignerReader.digest(listOf(cert))
