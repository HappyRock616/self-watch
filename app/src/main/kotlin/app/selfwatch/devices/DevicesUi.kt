/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import app.selfwatch.R
import app.selfwatch.data.Saf
import app.selfwatch.model.log.SwLog
import app.selfwatch.shell.Views
import app.selfwatch.ui.R as UiR
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Runtime permission requests for a fragment, asked at the moment of use (COMPATIBILITY §3). Create it
 * as a fragment property (it registers an activity-result launcher, which must happen before STARTED).
 */
class PermissionRequester(fragment: Fragment) {
    private var wait: CompletableDeferred<Map<String, Boolean>>? = null
    private val launcher = fragment.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        wait?.complete(result)
        wait = null
    }

    /** Asks for [permissions]; returns each one's answer. */
    suspend fun request(permissions: List<String>): Map<String, Boolean> {
        if (permissions.isEmpty()) return emptyMap()
        val w = CompletableDeferred<Map<String, Boolean>>()
        wait?.cancel()
        wait = w
        launcher.launch(permissions.toTypedArray())
        return w.await()
    }
}

/** Small UI helpers shared by the Devices sub-screens. */
object DevicesUi {
    private const val TAG = "DevicesUi"

    /** Shows the one-sentence [reason] with Continue / Not now; true on Continue. */
    suspend fun askReason(ctx: Context, @StringRes reason: Int): Boolean = suspendCancellableCoroutine { cont ->
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setMessage(reason)
            .setPositiveButton(R.string.devices_continue) { _, _ -> if (cont.isActive) cont.resume(true) }
            .setNegativeButton(R.string.devices_not_now) { _, _ -> if (cont.isActive) cont.resume(false) }
            .setOnCancelListener { if (cont.isActive) cont.resume(false) }
            .show()
        cont.invokeOnCancellation { dialog.dismiss() }
    }

    /** Asks "[title]?" with a destructive [confirm] button; runs [onYes] when confirmed. */
    fun confirm(ctx: Context, title: CharSequence, message: CharSequence?, @StringRes confirm: Int, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(confirm) { _, _ -> onYes() }
            .setNegativeButton(R.string.devices_not_now, null)
            .show()
    }

    /** Starts [intent], else [fallback]; false when neither could open. */
    fun open(ctx: Context, intent: Intent, fallback: Intent? = null): Boolean {
        for (i in listOfNotNull(intent, fallback)) {
            try {
                ctx.startActivity(i)
                return true
            } catch (e: ActivityNotFoundException) {
                SwLog.w(TAG, "no screen for ${i.action}", e)
            } catch (e: SecurityException) {
                SwLog.w(TAG, "screen ${i.action} refused", e)
            }
        }
        return false
    }

    /** A short toast. */
    fun toast(ctx: Context, text: CharSequence, long: Boolean = false) {
        Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /** A full-width switch with [text], 48 dp tall, added to [parent]. */
    fun switch(parent: LinearLayout, text: CharSequence): MaterialSwitch {
        val ctx = parent.context
        val s = MaterialSwitch(ctx).apply {
            this.text = text
            minHeight = Views.dp(ctx, 48)
            setTextAppearance(UiR.style.TextAppearance_SelfWatch_BodyLarge)
        }
        parent.addView(s, Views.matchWrap(ctx))
        return s
    }

    /** A muted explanation under a control, added to [parent]. */
    fun note(parent: LinearLayout, text: CharSequence?): android.widget.TextView {
        val ctx = parent.context
        val t = Views.caption(ctx, text)
        parent.addView(t, Views.matchWrap(ctx, 4))
        return t
    }

    /** A button ([outlined] or filled) with [text], added to [parent]. */
    fun button(parent: LinearLayout, text: CharSequence, outlined: Boolean = false, onClick: (View) -> Unit): MaterialButton {
        val ctx = parent.context
        val b = if (outlined) {
            MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        } else {
            MaterialButton(ctx)
        }
        b.text = text
        b.minHeight = Views.dp(ctx, 48)
        b.isAllCaps = false
        b.setOnClickListener(onClick)
        parent.addView(
            b,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Views.dp(ctx, 8)
            },
        )
        return b
    }

    /** Reads the document at [uri] (at most [maxBytes]); null when too big or unreadable. */
    suspend fun read(ctx: Context, uri: Uri, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        val size = Saf.size(ctx, uri)
        if (size != null && size > maxBytes) return@withContext null
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(BUFFER)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return@use null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            SwLog.w(TAG, "could not read the picked file", e)
            null
        } catch (e: SecurityException) {
            SwLog.w(TAG, "picked file not readable", e)
            null
        }
    }

    /** The picked document's display name. */
    fun name(ctx: Context, uri: Uri): String = Saf.displayName(ctx, uri)

    private const val BUFFER = 64 * 1024
}
