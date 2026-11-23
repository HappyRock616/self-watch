/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.interop.io

import java.io.Flushable
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * A small streaming XML writer (UTF-8). Element and attribute names are written as given, so
 * namespaces are declared with `xmlns` attributes and prefixes are part of the name. Text and
 * attribute values are escaped, and characters XML 1.0 can't hold are dropped.
 *
 * Elements with only text stay on one line; elements with children are indented by [indent].
 */
class XmlWriter(private val out: Writer, private val indent: String = "  ") : Flushable {
    /** Writes UTF-8 to [stream] (call [flush] at the end; the stream is not closed). */
    constructor(stream: OutputStream, indent: String = "  ") : this(OutputStreamWriter(stream, Charsets.UTF_8).buffered(1 shl 16), indent)

    private class Open(val name: String) {
        var children = false
        var text = false
    }

    private val stack = ArrayList<Open>()
    private var tagOpen = false

    /** Writes the `<?xml …?>` declaration. */
    fun declaration(): XmlWriter {
        out.write("""<?xml version="1.0" encoding="UTF-8"?>""")
        return this
    }

    /** Opens element [name]; add attributes with [attr] before any content. */
    fun start(name: String): XmlWriter {
        closeTag()
        stack.lastOrNull()?.children = true
        newline(stack.size)
        out.write("<")
        out.write(name)
        stack.add(Open(name))
        tagOpen = true
        return this
    }

    /** Adds attribute [name] to the element just opened. */
    fun attr(name: String, value: String): XmlWriter {
        check(tagOpen) { "attribute outside a start tag" }
        out.write(" ")
        out.write(name)
        out.write("=\"")
        escape(value, true)
        out.write("\"")
        return this
    }

    /** Writes [value] as text content. */
    fun text(value: String): XmlWriter {
        closeTag()
        stack.lastOrNull()?.text = true
        escape(value, false)
        return this
    }

    /** Closes the innermost element, which must be [name]. */
    fun end(name: String): XmlWriter {
        val top = stack.removeAt(stack.size - 1)
        check(top.name == name) { "closing $name but ${top.name} is open" }
        if (tagOpen) {
            out.write("/>")
            tagOpen = false
        } else {
            if (top.children && !top.text) newline(stack.size)
            out.write("</")
            out.write(name)
            out.write(">")
        }
        return this
    }

    /** `<name>text</name>`. */
    fun element(name: String, text: String): XmlWriter = start(name).text(text).end(name)

    /** Ends the document with a newline and flushes. All elements must be closed. */
    fun finish() {
        check(stack.isEmpty()) { "unclosed elements" }
        out.write("\n")
        out.flush()
    }

    override fun flush() {
        out.flush()
    }

    private fun closeTag() {
        if (tagOpen) {
            out.write(">")
            tagOpen = false
        }
    }

    private fun newline(depth: Int) {
        out.write("\n")
        repeat(depth) { out.write(indent) }
    }

    private fun escape(s: String, attr: Boolean) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> out.write("&amp;")
                c == '<' -> out.write("&lt;")
                c == '>' -> out.write("&gt;")
                c == '"' && attr -> out.write("&quot;")
                (c == '\n' || c == '\t' || c == '\r') && attr -> out.write("&#" + c.code + ";")
                c == '\n' || c == '\t' -> out.write(c.code)
                c == '\r' -> out.write("&#13;")
                c < ' ' -> {} // not allowed in XML 1.0
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    out.write(c.code)
                    out.write(s[i + 1].code)
                    i++
                }
                c.isSurrogate() -> {} // unpaired
                c.code == 0xFFFE || c.code == 0xFFFF -> {}
                else -> out.write(c.code)
            }
            i++
        }
    }
}
