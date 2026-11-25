/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.net

import app.selfwatch.model.result.Outcome
import java.io.File

/**
 * Every reason the app may use the network. The firewall allows or blocks each one; [defaultOn]
 * is its state while the user has not touched its switch (no stored value), so changing a default
 * changes untouched installs and keeps every stored choice. [code] is stored in the network log.
 * [listed]: shown in the Privacy center (false for features that are not wired yet).
 *
 * On by default is only what the app needs out of the box: the food database download (sends nothing
 * about the user), watch apps' web requests (the TCS Companion watch app needs them), apps on this
 * phone (127.0.0.1, never leaves the phone) and GPS help for the watch. Everything else starts off.
 */
public enum class NetFeature(public val code: Int, public val defaultOn: Boolean, public val listed: Boolean = true) {
    FOOD_DATA(1, true),

    /** Off by default: a lookup sends the barcode to Open Food Facts. */
    OPEN_FOOD_FACTS(2, false),
    WATCH_APP_WEB(3, true),
    GPS_ASSIST(4, true),

    /** The starter recipe book: no file is published and no URL is wired yet, so off and not listed. */
    RECIPE_BOOK(5, false, listed = false),

    /** 127.0.0.1; never leaves the phone. */
    LOCAL_PHONE_APP(6, true),
    ;

    /** Lookup by stored code. */
    public companion object {
        /** The feature with [code], or null. */
        public fun fromCode(code: Int): NetFeature? = entries.firstOrNull { it.code == code }
    }
}

/**
 * An HTTP request made for [feature]. Responses longer than [maxBytes] fail.
 * Equality compares [body] by content.
 */
public data class HttpRequest(
    val feature: NetFeature,
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMs: Int = 15_000,
    val maxBytes: Long = 50L shl 20,
) {
    override fun equals(other: Any?): Boolean =
        other is HttpRequest && feature == other.feature && method == other.method && url == other.url &&
            headers == other.headers && body.contentEquals(other.body) && timeoutMs == other.timeoutMs &&
            maxBytes == other.maxBytes

    override fun hashCode(): Int =
        (((feature.hashCode() * 31 + method.hashCode()) * 31 + url.hashCode()) * 31 + headers.hashCode()) * 31 +
            body.contentHashCode()
}

/** An HTTP response. Header names are as received. Equality compares [body] by content. */
public data class HttpResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is HttpResponse && status == other.status && headers == other.headers && body.contentEquals(other.body)

    override fun hashCode(): Int = (status * 31 + headers.hashCode()) * 31 + body.contentHashCode()
}

/** The only way to reach the network. Implemented by `net`; it applies the firewall and the network log. */
public interface Http {
    /** Sends [req] and reads the whole response. Fails with `SwError.Blocked` if the firewall blocks it. */
    public suspend fun send(req: HttpRequest): Outcome<HttpResponse>

    /**
     * Downloads the response body of [req] to [to], calling [onProgress] with (bytes so far, total or null).
     * Returns the number of bytes written.
     */
    public suspend fun download(req: HttpRequest, to: File, onProgress: (Long, Long?) -> Unit): Outcome<Long>
}
