/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.devices

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.selfwatch.model.log.SwLog
import java.io.File

/**
 * Shares files from `files/blelog/` and `files/logs/` through the app's FileProvider (paths in
 * `res/xml/shared_files.xml`). The receiving app gets a read grant for these files only.
 */
object FileShare {
    private const val TAG = "FileShare"

    /** The FileProvider authority for this app (`<applicationId>.files`). */
    fun authority(ctx: Context): String = ctx.packageName + ".files"

    /** The non-empty files directly in [dir], oldest name first. */
    fun filesIn(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.length() > 0 }?.sortedBy { it.name } ?: emptyList()

    /**
     * Builds the share intent for [files] (a chooser around `ACTION_SEND_MULTIPLE`), or null when
     * there is nothing to share.
     */
    fun intent(ctx: Context, files: List<File>, mime: String, subject: String, chooserTitle: CharSequence): Intent? {
        if (files.isEmpty()) return null
        val uris = ArrayList<Uri>(files.size)
        for (f in files) uris.add(FileProvider.getUriForFile(ctx, authority(ctx), f))
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = ClipData.newUri(ctx.contentResolver, subject, uris[0])
        for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
        send.clipData = clip
        return Intent.createChooser(send, chooserTitle)
    }

    /** Starts [intent]; false when no app can take it. */
    fun start(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        SwLog.w(TAG, "no app to share with", e)
        false
    }
}
