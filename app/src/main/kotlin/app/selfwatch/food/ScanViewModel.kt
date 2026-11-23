/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.selfwatch.food.barcode.Gtin
import app.selfwatch.food.source.BarcodeLookup
import app.selfwatch.model.food.FoodRef
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.result.SwError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a scan stands. */
sealed interface ScanState {
    /** Waiting for a barcode (camera or typed). */
    data object Scanning : ScanState

    /** Looking [gtin] up ([online] true while asking Open Food Facts). */
    data class Looking(val gtin: Long, val online: Boolean) : ScanState

    /** Found: open the food detail for [ref]. */
    data class Found(val gtin: Long, val ref: FoodRef, val name: String) : ScanState

    /**
     * No local food has [gtin]. [canLookOnline] offers the Open Food Facts lookup (firewall allows it
     * and it was not tried yet); otherwise offer the custom food form.
     */
    data class NotFound(val gtin: Long, val canLookOnline: Boolean, val triedOnline: Boolean) : ScanState

    /** The online lookup could not run ([error]: Blocked, Offline or Io); offer the custom food form. */
    data class Unavailable(val gtin: Long, val error: SwError) : ScanState

    /** A typed code is not a valid barcode. */
    data object BadCode : ScanState
}

/**
 * Barcode scan routing: local lookup first (no network), then — only if the user asks and the
 * firewall allows — Open Food Facts, else the custom food form prefilled with the barcode.
 */
class ScanViewModel(private val backendProvider: () -> FoodBackend) : ViewModel() {

    private val backend: FoodBackend by lazy(backendProvider)
    private val mutable = MutableStateFlow<ScanState>(ScanState.Scanning)

    /** Screen state. */
    val state: StateFlow<ScanState> = mutable.asStateFlow()

    /** A barcode was read by the camera. Ignored unless scanning. */
    fun onBarcode(gtin: Long) {
        if (mutable.value != ScanState.Scanning && mutable.value != ScanState.BadCode) return
        mutable.value = ScanState.Looking(gtin, online = false)
        viewModelScope.launch {
            mutable.value = try {
                route(gtin, backend.barcodeLocal(gtin), triedOnline = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "local lookup failed", e)
                ScanState.NotFound(gtin, canLookOnline = false, triedOnline = false)
            }
        }
    }

    /** A code was typed. */
    fun onTyped(text: String) {
        val gtin = Gtin.normalize(text)
        if (gtin == null) {
            mutable.value = ScanState.BadCode
            return
        }
        mutable.value = ScanState.Scanning
        onBarcode(gtin)
    }

    /** Asks Open Food Facts about the code that was not found. */
    fun lookOnline() {
        val s = mutable.value as? ScanState.NotFound ?: return
        if (!s.canLookOnline) return
        mutable.value = ScanState.Looking(s.gtin, online = true)
        viewModelScope.launch {
            mutable.value = try {
                route(s.gtin, backend.barcodeOnline(s.gtin), triedOnline = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SwLog.w(TAG, "online lookup failed", e)
                ScanState.Unavailable(s.gtin, SwError.Io(e.javaClass.simpleName))
            }
        }
    }

    /** Back to scanning. */
    fun scanAgain() {
        mutable.value = ScanState.Scanning
    }

    private suspend fun route(gtin: Long, r: BarcodeLookup, triedOnline: Boolean): ScanState = when (r) {
        is BarcodeLookup.Found -> ScanState.Found(gtin, r.food.ref, r.food.name)
        BarcodeLookup.NotFound -> ScanState.NotFound(gtin, canLookOnline = !triedOnline && backend.onlineLookupAllowed(), triedOnline = triedOnline)
        is BarcodeLookup.Unavailable -> ScanState.Unavailable(gtin, r.error)
    }

    private companion object {
        const val TAG = "ScanVM"
    }
}
