/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.log

import app.selfwatch.model.log.LogSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A rotating log file: `<dir>/<baseName>.log`, moved to `<baseName>.1.log` when it would grow past
 * [maxBytes]. So at most two files of [maxBytes] each exist per base name. Lines are written on one
 * background thread, so callers never wait for the disk.
 *
 * Each process uses its own [baseName] (`app`, `watch`): two processes never append to one file.
 */
class FileLogSink(
    private val dir: File,
    private val baseName: String,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : LogSink {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sw-log-$baseName").apply { isDaemon = true }
    }

    // Touched only on the executor thread.
    private var out: OutputStream? = null
    private var size: Long = 0L

    /** The current file. */
    val current: File get() = File(dir, "$baseName.log")

    /** The previous file (rotated out). */
    val previous: File get() = File(dir, "$baseName.1.log")

    /** The log files that exist, oldest first (for "Export logs" in About). */
    fun files(): List<File> = listOf(previous, current).filter { it.isFile }

    override fun write(level: Int, tag: String, msg: String, t: Throwable?) {
        val line = format(clockMs(), level, tag, msg, t)
        try {
            executor.execute { append(line) }
        } catch (e: RuntimeException) {
            // Executor shut down (process ending): nothing sensible left to do but report it.
            System.err.println("SwLog dropped a line: ${e.message}")
        }
    }

    /** Waits until every line written so far is on disk (tests, log export). */
    fun flush() {
        executor.submit { out?.flush() }.get(FLUSH_TIMEOUT_SEC, TimeUnit.SECONDS)
    }

    /** Writes what is queued, closes the file and stops the writer thread. Later lines are dropped. */
    fun close() {
        executor.submit { closeQuietly() }.get(FLUSH_TIMEOUT_SEC, TimeUnit.SECONDS)
        executor.shutdown()
    }

    private fun append(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        try {
            val stream = out ?: open()
            if (size > 0 && size + bytes.size > maxBytes) {
                rotate()
                writeBytes(open(), bytes)
            } else {
                writeBytes(stream, bytes)
            }
        } catch (e: IOException) {
            closeQuietly()
            System.err.println("SwLog cannot write ${current.path}: ${e.message}")
        }
    }

    private fun writeBytes(stream: OutputStream, bytes: ByteArray) {
        stream.write(bytes)
        stream.flush()
        size += bytes.size
    }

    private fun open(): OutputStream {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")
        size = if (current.isFile) current.length() else 0L
        return FileOutputStream(current, true).also { out = it }
    }

    private fun rotate() {
        closeQuietly()
        if (previous.exists() && !previous.delete()) throw IOException("cannot delete ${previous.name}")
        if (!current.renameTo(previous)) throw IOException("cannot rename ${current.name}")
        size = 0L
    }

    private fun closeQuietly() {
        try {
            out?.close()
        } catch (e: IOException) {
            System.err.println("SwLog close failed: ${e.message}")
        }
        out = null
    }

    /** Defaults and the line format. */
    companion object {
        /** 1 MB per file. */
        const val DEFAULT_MAX_BYTES: Long = 1_000_000L

        private const val FLUSH_TIMEOUT_SEC: Long = 5L

        /** One line: `2026-11-23T10:15:00.123Z I/Tag: message`, then the stack trace if any. */
        fun format(nowMs: Long, level: Int, tag: String, msg: String, t: Throwable?): String = buildString {
            append(Instant.ofEpochMilli(nowMs).toString())
            append(' ').append(levelChar(level)).append('/').append(tag).append(": ").append(msg).append('\n')
            if (t != null) append(t.stackTraceToString()).append('\n')
        }

        /** `D`, `I`, `W`, `E` (or `V` for anything lower). */
        fun levelChar(level: Int): Char = when {
            level >= LogSink.ERROR -> 'E'
            level >= LogSink.WARN -> 'W'
            level >= LogSink.INFO -> 'I'
            level >= LogSink.DEBUG -> 'D'
            else -> 'V'
        }
    }
}
