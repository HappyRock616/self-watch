/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.log

import app.selfwatch.model.log.LogSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLogSinkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun formatsOneLinePerMessage() {
        val line = FileLogSink.format(0L, LogSink.WARN, "Tag", "hello", null)
        assertEquals("1970-01-01T00:00:00Z W/Tag: hello\n", line)
    }

    @Test
    fun appendsStackTraces() {
        val line = FileLogSink.format(0L, LogSink.ERROR, "T", "boom", IllegalStateException("bad"))
        assertTrue(line.contains("E/T: boom\n"))
        assertTrue(line.contains("IllegalStateException: bad"))
    }

    @Test
    fun createsFolderAndWrites() {
        val dir = tmp.root.resolve("logs")
        val sink = FileLogSink(dir, "app", clockMs = { 0L })
        sink.write(LogSink.INFO, "T", "first", null)
        sink.flush()
        assertEquals("1970-01-01T00:00:00Z I/T: first\n", sink.current.readText())
        assertFalse(sink.previous.exists())
    }

    @Test
    fun rotatesIntoTwoFilesEachUnderTheLimit() {
        val max = 1_000L
        val sink = FileLogSink(tmp.root, "app", maxBytes = max, clockMs = { 0L })
        val msg = "x".repeat(60)
        repeat(100) { sink.write(LogSink.INFO, "T", "$it $msg", null) }
        sink.flush()

        assertTrue(sink.current.isFile)
        assertTrue(sink.previous.isFile)
        assertTrue(sink.current.length() <= max)
        assertTrue(sink.previous.length() <= max)
        assertEquals(listOf("app.1.log", "app.log"), sink.files().map { it.name })
        assertEquals(2, tmp.root.listFiles()?.size)
        // The newest line is in the current file; the oldest lines are gone.
        assertTrue(sink.current.readText().contains("99 $msg"))
        assertFalse(sink.previous.readText().contains("I/T: 0 $msg"))
    }

    @Test
    fun continuesAnExistingFileAfterRestart() {
        val first = FileLogSink(tmp.root, "watch", maxBytes = 200L, clockMs = { 0L })
        first.write(LogSink.INFO, "T", "a".repeat(100), null)
        first.close()
        val second = FileLogSink(tmp.root, "watch", maxBytes = 200L, clockMs = { 0L })
        second.write(LogSink.INFO, "T", "b".repeat(100), null)
        second.flush()
        assertTrue(second.previous.readText().contains("a".repeat(100)))
        assertTrue(second.current.readText().contains("b".repeat(100)))
    }

    @Test
    fun levelLetters() {
        assertEquals('D', FileLogSink.levelChar(LogSink.DEBUG))
        assertEquals('I', FileLogSink.levelChar(LogSink.INFO))
        assertEquals('W', FileLogSink.levelChar(LogSink.WARN))
        assertEquals('E', FileLogSink.levelChar(LogSink.ERROR))
        assertEquals('V', FileLogSink.levelChar(2))
    }
}
