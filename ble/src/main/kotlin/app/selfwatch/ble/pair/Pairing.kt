/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.ble.pair

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.selfwatch.ble.GARMIN_MANUFACTURER_ID
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Which devices the system picker offers. Each non-null criterion becomes its own filter, and the
 * picker shows devices matching **any** of them. With both null every LE device is offered.
 */
public data class PairFilter(
    /** Bluetooth name pattern (from `garmin/models.json`), or null. */
    val namePattern: Regex?,
    /** Bluetooth SIG company id in the advertisement's manufacturer data, or null. */
    val manufacturerId: Int?,
    /**
     * GATT service UUIDs the device advertises (for example the Bluetooth SIG Heart Rate service);
     * each one is its own filter.
     */
    val serviceUuids: List<UUID> = emptyList(),
) {
    /** Filter presets. */
    public companion object {
        /** Any device advertising Garmin's manufacturer id ([GARMIN_MANUFACTURER_ID]). */
        public val GARMIN: PairFilter = PairFilter(namePattern = null, manufacturerId = GARMIN_MANUFACTURER_ID)
    }
}

/** A watch associated with the app through the Companion Device Manager. */
public data class PairedDevice(
    /** Bluetooth MAC address, upper case. */
    val address: String,
    /** Bluetooth name when known. */
    val name: String?,
    /** CDM association id (API 33+), else null. */
    val associationId: Int?,
)

/** What [Pairing.unpair] did. */
public data class UnpairResult(
    /**
     * True if Android's bond was removed too (API 36+). When false the user must "Forget" the watch
     * in Bluetooth settings to remove it fully.
     */
    val bondRemoved: Boolean,
)

/** Launches the system pairing picker and returns its result. */
public fun interface IntentSenderLauncher {
    /** Launches [sender] for a result. Called on the main thread. */
    public suspend fun launch(sender: IntentSender): ActivityResult
}

/**
 * An [IntentSenderLauncher] on an [ActivityResultRegistry] (for example
 * `ComponentActivity.activityResultRegistry`). It registers a one-off launcher per call and
 * unregisters it when the result arrives or the caller is cancelled, so it can be used at any point
 * in the activity's life, not only before `onStart`. If the activity is recreated while the picker
 * is open the result is lost; on API 33+ the association still exists and shows in
 * [Pairing.associations].
 */
public class RegistryIntentSenderLauncher(private val registry: ActivityResultRegistry) : IntentSenderLauncher {
    override suspend fun launch(sender: IntentSender): ActivityResult = suspendCancellableCoroutine { cont ->
        val key = "app.selfwatch.ble.pair#" + counter.incrementAndGet()
        val holder = arrayOfNulls<ActivityResultLauncher<IntentSenderRequest>>(1)
        val launcher = registry.register(key, ActivityResultContracts.StartIntentSenderForResult()) { result ->
            holder[0]?.unregister()
            if (cont.isActive) cont.resume(result)
        }
        holder[0] = launcher
        cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { launcher.unregister() } }
        launcher.launch(IntentSenderRequest.Builder(sender).build())
    }

    private companion object {
        val counter = AtomicInteger()
    }
}

/**
 * Pairs watches through the Companion Device Manager: no location permission and no
 * `BLUETOOTH_SCAN`, because the system scans and shows its own picker. The association also grants
 * the companion background exemptions declared in the manifest.
 *
 * Call [pair] and [unpair] from the main thread (for example in `lifecycleScope`).
 */
public class Pairing internal constructor(
    context: Context,
    private val launcher: IntentSenderLauncher,
    private val cdm: CompanionDeviceManager?,
    private val bonding: Bonding,
    private val sdkInt: Int,
) {
    private val appContext: Context = context.applicationContext

    /** Pairing that shows the picker through [launcher]. */
    public constructor(context: Context, launcher: IntentSenderLauncher) : this(
        context = context,
        launcher = launcher,
        cdm = context.getSystemService(CompanionDeviceManager::class.java),
        bonding = Bonding(context),
        sdkInt = Build.VERSION.SDK_INT,
    )

    /** Pairing that shows the picker through [activity]'s activity-result registry. */
    public constructor(activity: ComponentActivity) :
        this(activity, RegistryIntentSenderLauncher(activity.activityResultRegistry))

    @ChecksSdkIntAtLeast(parameter = 0)
    private fun atLeast(api: Int): Boolean = sdkInt >= api

    private fun hasConnectPermission(): Boolean = !atLeast(Build.VERSION_CODES.S) ||
        ContextCompat.checkSelfPermission(appContext, BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Shows the system picker for devices matching [filter] and associates the one the user picks.
     * With [bond] true it also bonds right away (otherwise the connection bonds when the watch asks).
     * Fails with [SwError.PermissionMissing] without `BLUETOOTH_CONNECT` (API 31+), and with
     * [SwError.Io] when the user cancels or the picker finds nothing.
     */
    public suspend fun pair(filter: PairFilter, bond: Boolean = false): Outcome<PairedDevice> {
        if (!hasConnectPermission()) return Outcome.Fail(SwError.PermissionMissing(BLUETOOTH_CONNECT))
        val cdm = cdm ?: return Outcome.Fail(SwError.Io("companion device setup is not available"))
        val request = buildRequest(filter)
        val paired = try {
            if (atLeast(Build.VERSION_CODES.TIRAMISU)) associate(cdm, request) else associateLegacy(cdm, request)
        } catch (e: SecurityException) {
            SwLog.w(TAG, "associate: permission missing", e)
            Outcome.Fail(SwError.PermissionMissing(BLUETOOTH_CONNECT))
        }
        if (paired !is Outcome.Ok || !bond) return paired
        return when (val b = bonding.bond(paired.value.address)) {
            is Outcome.Ok -> paired
            is Outcome.Fail -> b
        }
    }

    // API 33+: onAssociationPending gives the picker, onAssociationCreated the result.
    @SuppressLint("NewApi") // called only when atLeast(33)
    private suspend fun associate(cdm: CompanionDeviceManager, request: AssociationRequest): Outcome<PairedDevice> =
        coroutineScope {
            val picker = CompletableDeferred<IntentSender>()
            val done = CompletableDeferred<Outcome<PairedDevice>>()
            val callback = object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    picker.complete(intentSender)
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    done.complete(associationInfo.toPaired()?.let { Outcome.Ok(it) } ?: Outcome.Fail(SwError.Protocol("association without address")))
                }

                override fun onFailure(error: CharSequence?) {
                    SwLog.i(TAG, "association failed: $error")
                    done.complete(Outcome.Fail(SwError.Io("pairing failed: $error")))
                }
            }
            cdm.associate(request, ContextCompat.getMainExecutor(appContext), callback)
            val ui = launch {
                val result = launcher.launch(picker.await())
                if (result.resultCode != Activity.RESULT_OK) {
                    done.complete(Outcome.Fail(SwError.Io("pairing cancelled")))
                } else {
                    val info = result.data?.let {
                        IntentCompat.getParcelableExtra(it, CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                    }
                    info?.toPaired()?.let { done.complete(Outcome.Ok(it)) }
                    // else onAssociationCreated completes it
                }
            }
            val outcome = done.await()
            ui.cancel()
            outcome
        }

    @SuppressLint("NewApi") // API 33 type, reached only from the 33+ path
    private fun AssociationInfo.toPaired(): PairedDevice? {
        val mac = deviceMacAddress?.toString()?.uppercase() ?: return null
        return PairedDevice(mac, displayName?.toString(), id)
    }

    // API 26–32: onDeviceFound gives the picker; the result intent carries the device.
    private suspend fun associateLegacy(cdm: CompanionDeviceManager, request: AssociationRequest): Outcome<PairedDevice> =
        coroutineScope {
            val picker = CompletableDeferred<IntentSender>()
            val failed = CompletableDeferred<Outcome<PairedDevice>>()
            val callback = object : CompanionDeviceManager.Callback() {
                @Deprecated("API 26–32 callback")
                override fun onDeviceFound(intentSender: IntentSender) {
                    picker.complete(intentSender)
                }

                override fun onFailure(error: CharSequence?) {
                    SwLog.i(TAG, "association failed: $error")
                    failed.complete(Outcome.Fail(SwError.Io("pairing failed: $error")))
                }
            }
            cdm.associate(request, callback, Handler(Looper.getMainLooper()))
            val ui = launch {
                val result = launcher.launch(picker.await())
                failed.complete(
                    if (result.resultCode != Activity.RESULT_OK) Outcome.Fail(SwError.Io("pairing cancelled"))
                    else legacyDevice(result.data),
                )
            }
            val outcome = failed.await()
            ui.cancel()
            outcome
        }

    @SuppressLint("MissingPermission") // permission checked in pair(); SecurityException caught there
    internal fun legacyDevice(data: Intent?): Outcome<PairedDevice> {
        @Suppress("DEPRECATION") // CompanionDeviceManager.EXTRA_DEVICE is the API 26–32 result
        val key = CompanionDeviceManager.EXTRA_DEVICE
        val parcel = data?.let { IntentCompat.getParcelableExtra(it, key, Parcelable::class.java) }
        return when (parcel) {
            is ScanResult -> Outcome.Ok(
                PairedDevice(parcel.device.address.uppercase(), parcel.scanRecord?.deviceName ?: safeName(parcel.device), null),
            )
            is BluetoothDevice -> Outcome.Ok(PairedDevice(parcel.address.uppercase(), safeName(parcel), null))
            else -> Outcome.Fail(SwError.Protocol("picker returned no device"))
        }
    }

    @SuppressLint("MissingPermission") // SecurityException caught
    private fun safeName(device: BluetoothDevice): String? = try {
        device.name
    } catch (e: SecurityException) {
        SwLog.w(TAG, "device name: permission missing", e)
        null
    }

    /** The watches associated with this app. Names are known only on API 33+. */
    @SuppressLint("NewApi") // branched with atLeast()
    public fun associations(): List<PairedDevice> {
        val cdm = cdm ?: return emptyList()
        return if (atLeast(Build.VERSION_CODES.TIRAMISU)) {
            cdm.myAssociations.mapNotNull { it.toPaired() }
        } else {
            legacyAssociations(cdm).map { PairedDevice(it.uppercase(), null, null) }
        }
    }

    @Suppress("DEPRECATION") // API 26–32
    private fun legacyAssociations(cdm: CompanionDeviceManager): List<String> = cdm.associations

    /**
     * Ends the association with [device]. On API 36+ with [removeBond] it also removes Android's
     * bond (`removeBond` is public only from 36); below that the user is told to forget the watch in
     * Bluetooth settings if they want it fully gone.
     */
    @SuppressLint("NewApi", "MissingPermission") // branched with atLeast(); SecurityException caught
    public fun unpair(device: PairedDevice, removeBond: Boolean = true): Outcome<UnpairResult> {
        val cdm = cdm ?: return Outcome.Fail(SwError.Io("companion device setup is not available"))
        return try {
            val id = device.associationId
            var bondRemoved = false
            if (removeBond && id != null && atLeast(36)) {
                bondRemoved = cdm.removeBond(id)
            }
            if (id != null && atLeast(Build.VERSION_CODES.TIRAMISU)) cdm.disassociate(id) else legacyDisassociate(cdm, device.address)
            Outcome.Ok(UnpairResult(bondRemoved))
        } catch (e: SecurityException) {
            SwLog.w(TAG, "unpair: permission missing", e)
            Outcome.Fail(SwError.PermissionMissing(BLUETOOTH_CONNECT))
        } catch (e: IllegalArgumentException) {
            SwLog.w(TAG, "unpair: no such association", e)
            Outcome.Fail(SwError.BadInput("not associated"))
        }
    }

    @Suppress("DEPRECATION") // API 26–32
    private fun legacyDisassociate(cdm: CompanionDeviceManager, address: String) {
        cdm.disassociate(address)
    }

    internal companion object {
        private const val TAG = "Pairing"
        private const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"

        /** The association request: one LE filter per criterion (OR), user picks (not single-device). */
        fun buildRequest(filter: PairFilter): AssociationRequest {
            val builder = AssociationRequest.Builder().setSingleDevice(false)
            var any = false
            filter.namePattern?.let {
                builder.addDeviceFilter(BluetoothLeDeviceFilter.Builder().setNamePattern(it.toPattern()).build())
                any = true
            }
            filter.manufacturerId?.let { id ->
                val scan = ScanFilter.Builder().setManufacturerData(id, ByteArray(0)).build()
                builder.addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(scan).build())
                any = true
            }
            for (uuid in filter.serviceUuids) {
                val scan = ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
                builder.addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(scan).build())
                any = true
            }
            if (!any) builder.addDeviceFilter(BluetoothLeDeviceFilter.Builder().build())
            return builder.build()
        }
    }
}
