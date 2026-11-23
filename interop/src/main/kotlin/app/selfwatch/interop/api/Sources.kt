/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.api

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Something an importer can read, possibly more than once (detection reads the head, the plan reads
 * the whole file). Each [open] returns a fresh stream that the caller closes.
 */
interface InputSource {
    /** The file's display name, for example `servings.csv`. Used for detection and the preview. */
    val name: String

    /** Size in bytes, if known. */
    val sizeBytes: Long?

    /** Opens a new stream from the start. Throws [IOException] if the source can't be read. */
    fun open(): InputStream

    /** Factories. */
    companion object {
        /** An in-memory source (tests, zip entries). */
        fun ofBytes(name: String, bytes: ByteArray): InputSource = object : InputSource {
            override val name: String = name
            override val sizeBytes: Long = bytes.size.toLong()
            override fun open(): InputStream = ByteArrayInputStream(bytes)
        }

        /** A local file. */
        fun ofFile(file: File): InputSource = object : InputSource {
            override val name: String = file.name
            override val sizeBytes: Long = file.length()
            override fun open(): InputStream = FileInputStream(file)
        }

        /** Any stream supplier. */
        fun of(name: String, sizeBytes: Long? = null, open: () -> InputStream): InputSource = object : InputSource {
            override val name: String = name
            override val sizeBytes: Long? = sizeBytes
            override fun open(): InputStream = open()
        }

        /** A Storage Access Framework document. [name] comes from the document's display name. */
        fun ofUri(resolver: ContentResolver, uri: Uri, name: String, sizeBytes: Long? = null): InputSource =
            of(name, sizeBytes) { resolver.openInputStream(uri) ?: throw IOException("cannot open document") }
    }
}

/** Somewhere an exporter writes one file. Each [open] returns a fresh stream that the caller closes. */
interface OutputSink {
    /** Display name of the target, for logs and results. */
    val name: String

    /** Opens the target for writing, replacing any previous content. */
    fun open(): OutputStream

    /** Factories. */
    companion object {
        /** A local file. */
        fun ofFile(file: File): OutputSink = object : OutputSink {
            override val name: String = file.name
            override fun open(): OutputStream = FileOutputStream(file)
        }

        /** Any stream supplier. */
        fun of(name: String, open: () -> OutputStream): OutputSink = object : OutputSink {
            override val name: String = name
            override fun open(): OutputStream = open()
        }

        /** A Storage Access Framework document opened for writing ("wt" truncates). */
        fun ofUri(resolver: ContentResolver, uri: Uri, name: String): OutputSink =
            of(name) { resolver.openOutputStream(uri, "wt") ?: throw IOException("cannot open document") }
    }
}

/** An [OutputSink] that keeps what was written in memory; for tests and for zipping. */
class MemorySink(override val name: String = "memory") : OutputSink {
    private var buffer = ByteArrayOutputStream()

    override fun open(): OutputStream {
        buffer = ByteArrayOutputStream()
        return buffer
    }

    /** Everything written by the last [open]. */
    fun bytes(): ByteArray = buffer.toByteArray()
}
