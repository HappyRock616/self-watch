/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.data.shortText
import app.selfwatch.food.install.DataInstaller
import app.selfwatch.food.install.DataManifest
import app.selfwatch.food.install.UpdateCheck
import app.selfwatch.model.net.NetFeature
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.result.SwError
import app.selfwatch.net.Firewall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream

/** What the Food data screen does with the food database (a seam over [DataInstaller] for tests). */
interface FoodDataOps {
    /** Installed data version, or null when not installed. */
    suspend fun installedVersion(): Int?

    /** Size of the installed file in bytes (0 when not installed). */
    suspend fun sizeBytes(): Long

    /** Fetches the manifest (Wi-Fi only, through the firewall). */
    suspend fun check(): Outcome<UpdateCheck>

    /** Downloads and installs [m]. */
    suspend fun install(m: DataManifest, onProgress: (Long, Long?) -> Unit): Outcome<Int>

    /** Installs a picked `.db` or `.db.gz`. */
    suspend fun installFrom(input: InputStream): Outcome<Int>

    /** Removes the installed file. */
    suspend fun remove()

    /** Factory. */
    companion object {
        /** Over the real [installer]; installs and removals take [lock] so two never run at once. */
        fun of(installer: DataInstaller, lock: Mutex = Mutex()): FoodDataOps = object : FoodDataOps {
            override suspend fun installedVersion(): Int? = if (installer.isInstalled) installer.installedVersion() ?: 0 else null
            override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { installer.file.takeIf { it.isFile }?.length() ?: 0L }
            override suspend fun check() = installer.checkForUpdate()
            override suspend fun install(m: DataManifest, onProgress: (Long, Long?) -> Unit) = lock.withLock { installer.install(m, onProgress) }
            override suspend fun installFrom(input: InputStream) = lock.withLock { installer.installFrom(input) }
            override suspend fun remove() = lock.withLock { installer.remove() }
        }
    }
}

/** One-shot messages for the Food data screen. */
sealed interface FoodDataMessage {
    /** The installed file is current. */
    data object UpToDate : FoodDataMessage

    /** The published file needs a newer app. */
    data object NeedsNewerApp : FoodDataMessage

    /** Installed version [version]. */
    data class Installed(val version: Int) : FoodDataMessage

    /** The file was removed. */
    data object Removed : FoodDataMessage

    /** The firewall blocks food data downloads. */
    data object Blocked : FoodDataMessage

    /** Waiting for Wi-Fi (or no network). */
    data object Offline : FoodDataMessage

    /** Something failed. */
    data class Failed(val detail: String) : FoodDataMessage
}

/** Food data screen state. */
data class FoodDataUiState(
    val loading: Boolean = true,
    val version: Int? = null,
    val sizeBytes: Long = 0,
    val busy: Boolean = false,
    /** Bytes so far and total (if known) while downloading. */
    val progress: Pair<Long, Long?>? = null,
    /** A newer file the user can download. */
    val available: DataManifest? = null,
    val message: FoodDataMessage? = null,
) {
    /** True when foods.db is installed. */
    val installed: Boolean get() = version != null
}

/** Food data (C20): installed version and size; check for an update (Wi-Fi), download, load from file, remove. */
class FoodDataViewModel(private val ops: FoodDataOps) : ViewModel() {

    private val mutable = MutableStateFlow(FoodDataUiState())

    /** Screen state. */
    val state: StateFlow<FoodDataUiState> = mutable.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val v = ops.installedVersion()
        val size = ops.sizeBytes()
        mutable.update { it.copy(loading = false, version = v, sizeBytes = size) }
    }

    /** Checks the published manifest. */
    fun check() = work {
        when (val r = ops.check()) {
            is Outcome.Ok -> when (val c = r.value) {
                is UpdateCheck.Available -> {
                    mutable.update { it.copy(available = c.manifest) }
                    null
                }
                UpdateCheck.UpToDate -> FoodDataMessage.UpToDate
                is UpdateCheck.NeedsNewerApp -> FoodDataMessage.NeedsNewerApp
            }
            is Outcome.Fail -> failure(r.error)
        }
    }

    /** Downloads and installs the available file. */
    fun download() {
        val m = mutable.value.available ?: return
        work {
            val r = ops.install(m) { done, total -> mutable.update { it.copy(progress = done to total) } }
            mutable.update { it.copy(progress = null, available = if (r is Outcome.Ok) null else it.available) }
            done(r)
        }
    }

    /** Installs from a picked file. The stream is closed here. */
    fun loadFrom(open: () -> InputStream?) = work {
        val input = withContext(Dispatchers.IO) { open() } ?: return@work FoodDataMessage.Failed("cannot open file")
        val r = input.use { ops.installFrom(it) }
        done(r)
    }

    /** Removes the installed file. */
    fun remove() = work {
        ops.remove()
        reload()
        FoodDataMessage.Removed
    }

    /** Clears the shown message. */
    fun messageShown() = mutable.update { it.copy(message = null) }

    private suspend fun done(r: Outcome<Int>): FoodDataMessage = when (r) {
        is Outcome.Ok -> {
            reload()
            FoodDataMessage.Installed(r.value)
        }
        is Outcome.Fail -> failure(r.error)
    }

    private fun failure(e: SwError): FoodDataMessage = when (e) {
        is SwError.Blocked -> FoodDataMessage.Blocked
        SwError.Offline, SwError.WaitingForWifi -> FoodDataMessage.Offline
        else -> FoodDataMessage.Failed(e.shortText())
    }

    private fun work(block: suspend () -> FoodDataMessage?) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            val msg = try {
                block()
            } finally {
                mutable.update { it.copy(busy = false) }
            }
            if (msg != null) mutable.update { it.copy(message = msg) }
        }
    }

    /** Factory. */
    companion object {
        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { FoodDataViewModel(FoodDataOps.of(graph.food.installer, graph.food.installLock)) }
        }

        /** Whether the firewall lets food data downloads out. */
        suspend fun downloadsAllowed(graph: AppGraph): Boolean = Firewall(graph.settings).allowed(NetFeature.FOOD_DATA)
    }
}
