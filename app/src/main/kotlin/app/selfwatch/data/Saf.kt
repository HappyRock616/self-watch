/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import androidx.core.net.toUri
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContract
import app.selfwatch.R
import app.selfwatch.food.FoodViews
import app.selfwatch.interop.api.InputSource
import app.selfwatch.interop.api.OutputSink
import app.selfwatch.model.log.SwLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import app.selfwatch.ui.R as UiR

/** Storage Access Framework helpers: no storage permission is ever used (WP-12). */
internal object Saf {
    private const val TAG = "Saf"

    /** The display name of [uri], or its last path segment. */
    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (e: RuntimeException) {
            SwLog.w(TAG, "display name unavailable", e)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** Size of [uri] in bytes, if the provider says. */
    fun size(ctx: Context, uri: Uri): Long? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (e: RuntimeException) {
        SwLog.w(TAG, "size unavailable", e)
        null
    }

    /** A readable source for a picked document. */
    fun input(ctx: Context, uri: Uri): InputSource =
        InputSource.ofUri(ctx.applicationContext.contentResolver, uri, displayName(ctx, uri), size(ctx, uri))

    /** A writable sink for a created document. */
    fun output(ctx: Context, uri: Uri): OutputSink =
        OutputSink.ofUri(ctx.applicationContext.contentResolver, uri, displayName(ctx, uri))

    /** The first bytes of [uri] (empty if unreadable). */
    fun head(ctx: Context, uri: Uri, n: Int): ByteArray = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = s.read(b, got, n - got)
                if (r < 0) break
                got += r
            }
            b.copyOf(got)
        } ?: ByteArray(0)
    } catch (e: java.io.IOException) {
        SwLog.w(TAG, "head unreadable", e)
        ByteArray(0)
    } catch (e: SecurityException) {
        SwLog.w(TAG, "head unreadable", e)
        ByteArray(0)
    }

    /** Keeps read and write access to a picked folder across restarts. */
    fun keepFolder(ctx: Context, tree: Uri): Boolean = try {
        ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        SwLog.w(TAG, "folder permission not kept", e)
        false
    }

    /** A short name for a stored folder URI ("Backups" from `primary:Backups`). */
    fun folderLabel(uri: String?): String? {
        if (uri.isNullOrEmpty()) return null
        return try {
            val id = DocumentsContract.getTreeDocumentId(uri.toUri())
            id.substringAfter(':').ifEmpty { id }
        } catch (e: IllegalArgumentException) {
            SwLog.w(TAG, "not a folder uri", e)
            uri
        }
    }

    /**
     * Asks for a passphrase. [onDone] gets the characters (the caller wipes them), or an empty array when
     * [allowEmpty] and the field was left blank; nothing is called on Cancel.
     */
    fun askPassphrase(activity: Activity, title: CharSequence, message: CharSequence, allowEmpty: Boolean, onDone: (CharArray) -> Unit) {
        val (layout, edit) = FoodViews.field(activity, R.string.data_passphrase_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        layout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        val pad = activity.resources.getDimensionPixelSize(UiR.dimen.sw_space_2)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(pad, pad / 2, pad, 0)
            addView(layout, FoodViews.matchWrap())
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val e = edit.text
                val chars = if (e == null) CharArray(0) else CharArray(e.length).also { android.text.TextUtils.getChars(e, 0, e.length, it, 0) }
                e?.clear()
                if (chars.isNotEmpty() || allowEmpty) onDone(chars)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> edit.text?.clear() }
            .show()
    }
}

/** `ACTION_CREATE_DOCUMENT` with the file name and MIME type chosen at launch time. */
internal class CreateFile : ActivityResultContract<CreateFile.Request, Uri?>() {
    /** What to create. */
    data class Request(val fileName: String, val mimeType: String)

    override fun createIntent(context: Context, input: Request): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
