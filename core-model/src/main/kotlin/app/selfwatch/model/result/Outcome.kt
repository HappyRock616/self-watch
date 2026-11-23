/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.result

import app.selfwatch.model.device.Feature
import app.selfwatch.model.net.NetFeature

/**
 * The result of an operation that can fail in an expected way. Bugs throw; expected failures
 * (no connection, bad input, timeouts) come back as [Fail] with an [SwError].
 */
public sealed interface Outcome<out T> {
    /** Success carrying [value]. */
    public data class Ok<T>(val value: T) : Outcome<T>

    /** Expected failure described by [error]. */
    public data class Fail(val error: SwError) : Outcome<Nothing>
}

/** The value if this is [Outcome.Ok], else null. */
public fun <T> Outcome<T>.valueOrNull(): T? = (this as? Outcome.Ok<T>)?.value

/** The error if this is [Outcome.Fail], else null. */
public fun Outcome<*>.errorOrNull(): SwError? = (this as? Outcome.Fail)?.error

/** Transforms the success value; failures pass through unchanged. */
public inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Fail -> this
}

/** Expected failure reasons shared by every module. */
public sealed interface SwError {
    /** The device with [deviceId] is not connected. */
    public data class NotConnected(val deviceId: Long) : SwError

    /** The connected device does not support [feature]. */
    public data class Unsupported(val feature: Feature) : SwError

    /** Waiting for [what] took too long. */
    public data class Timeout(val what: String) : SwError

    /** The other side broke the protocol; [detail] says how. */
    public data class Protocol(val detail: String) : SwError

    /** The network firewall blocks [feature]. */
    public data class Blocked(val feature: NetFeature) : SwError

    /** No network. */
    public data object Offline : SwError

    /**
     * Connected, but the request is Wi-Fi only (large downloads) and the network is metered; it can
     * go ahead once Wi-Fi (or another unmetered network) is up. Added in core-model v1.1.
     */
    public data object WaitingForWifi : SwError

    /**
     * The other side refused because of a rate limit or quota; try again after [retryAfterSec]
     * seconds, or later when null. Added in core-model v1.1.
     */
    public data class RateLimited(val retryAfterSec: Int?) : SwError

    /** File, database or socket failure. */
    public data class Io(val detail: String) : SwError

    /** Input (file, import, message, JSON) was malformed. */
    public data class BadInput(val detail: String) : SwError

    /** An Android runtime [permission] is not granted. */
    public data class PermissionMissing(val permission: String) : SwError
}
