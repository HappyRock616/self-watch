/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.health

import app.selfwatch.model.watch.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Saves watch files in app-private storage under [filesDir] (`Context.filesDir`).
 *
 * Workouts go to `workouts/<deviceId>/<startSec>.fit`. The returned path is relative to [filesDir]
 * (it survives a backup restored on another phone); [resolve] turns it back into a [File].
 */
public class AppFileStore(private val filesDir: File) : FileStore {

    /**
     * Writes [bytes] to `workouts/<deviceId>/<startSec>.fit` (a temporary file renamed into place, so
     * a crash never leaves half a file) and returns that relative path. Replaces an existing file.
     *
     * @throws IOException when the file can't be written.
     */
    override suspend fun saveWorkoutFit(deviceId: Long, startSec: Long, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val rel = workoutPath(deviceId, startSec)
            val target = File(filesDir, rel)
            val dir = target.parentFile ?: throw IOException("no parent for $rel")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                // Windows-like file systems refuse to rename over an existing file.
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    throw IOException("cannot move ${tmp.name} into place")
                }
            }
            rel
        }

    /** The file for a [path] returned by [saveWorkoutFit]. */
    public fun resolve(path: String): File = File(filesDir, path)

    /** Paths. */
    public companion object {
        /** The relative path of a workout's FIT file. */
        public fun workoutPath(deviceId: Long, startSec: Long): String = "workouts/$deviceId/$startSec.fit"
    }
}
