/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.link.client;

/** Outcome of a send, an open-app request or a query. Each value has a {@link LinkStatus} code. */
public enum SendStatus {
    /** Delivered to the watch app (send) or the open request reached the watch (openApp). */
    SUCCESS(LinkStatus.SUCCESS),
    /** The watch did not confirm delivery. */
    FAILED(LinkStatus.FAILED),
    /** The message is too large for the watch; send less data. */
    TOO_LARGE(LinkStatus.TOO_LARGE),
    /** The watch app is not installed on that watch. */
    NOT_INSTALLED(LinkStatus.NOT_INSTALLED),
    /** The watch is unknown or not connected. */
    NOT_CONNECTED(LinkStatus.NOT_CONNECTED),
    /** The user denied this app access to the watch app. */
    DENIED(LinkStatus.DENIED),
    /** Waiting for the user to approve this app in Self Watch; try again after they answer. */
    PENDING_APPROVAL(LinkStatus.PENDING_APPROVAL),
    /** The message contains a value {@link LinkCodec} can't encode, or is too deep or too big. */
    INVALID_MESSAGE(LinkStatus.INVALID_MESSAGE),
    /** Self Watch is not bound right now. */
    UNAVAILABLE(LinkStatus.UNAVAILABLE);

    private final int code;

    SendStatus(int code) {
        this.code = code;
    }

    /** The wire code ({@link LinkStatus}). */
    public int code() {
        return code;
    }

    /** The status for a wire code; unknown codes (from a newer Self Watch) map to {@link #FAILED}. */
    public static SendStatus fromCode(int code) {
        for (SendStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return FAILED;
    }
}
