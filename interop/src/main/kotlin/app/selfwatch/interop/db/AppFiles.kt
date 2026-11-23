/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.db

import java.io.File

/**
 * The app's private files that belong with the database: workout originals under `workouts/` and
 * watch-app files under `watchapps/`, both inside [filesDir].
 *
 * Paths stored in the database (`workout.fit_path`, `workout.track_path`) are written by the import
 * sink **relative to [filesDir]** (for example `workouts/import/12/0-run.fit`), so a restored backup on
 * another phone still finds them. [resolve] also accepts absolute paths written by older code.
 */
class AppFiles(val filesDir: File) {
    /** The file a stored path names. */
    fun resolve(path: String): File {
        val f = File(path)
        return if (f.isAbsolute) f else File(filesDir, path)
    }

    /** The folder that holds the originals of import batch [importId]. */
    fun importDir(importId: Long): File = File(filesDir, "$WORKOUTS/import/$importId")

    /** [file] as a path relative to [filesDir] with `/` separators (absolute if it lies outside). */
    fun relative(file: File): String {
        val base = filesDir.absoluteFile.toPath()
        val p = file.absoluteFile.toPath()
        return if (p.startsWith(base)) base.relativize(p).joinToString("/") else file.absolutePath
    }

    /** Folder names. */
    companion object {
        /** Workout originals (FIT from the watch, imported FIT/GPX/TCX). */
        const val WORKOUTS: String = "workouts"

        /** Watch-app files (Connect IQ apps and their settings). */
        const val WATCHAPPS: String = "watchapps"

        /** [name] reduced to a safe file name (letters, digits, `.`, `_`, `-`; at most 80 characters). */
        fun safeName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base.map { if (it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '_' || it == '-') it else '_' }
                .joinToString("").trim('.')
            return cleaned.takeLast(80).ifEmpty { "file" }
        }
    }
}
