/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.link.client;

/**
 * Status codes used on the wire by {@code ILinkService} and {@code ILinkCallback}. Self Watch and the
 * client share these numbers; {@link SendStatus} is the typed form for app code.
 */
public final class LinkStatus {
    /** Delivered (send), request sent (openApp), or queued (the return value of send). */
    public static final int SUCCESS = 0;
    /** The watch did not confirm delivery, even after a retry. */
    public static final int FAILED = 1;
    /** The encoded message is larger than the watch accepts. */
    public static final int TOO_LARGE = 2;
    /** The watch app is not installed on that watch. */
    public static final int NOT_INSTALLED = 3;
    /** The watch is unknown or not connected. */
    public static final int NOT_CONNECTED = 4;
    /** The user denied (or revoked) this phone app's access to the watch app. */
    public static final int DENIED = 5;
    /** The user has not decided yet; Self Watch is showing an approval notification. */
    public static final int PENDING_APPROVAL = 6;
    /** The message bytes are not valid {@link LinkCodec} data, or a value can't be encoded. */
    public static final int INVALID_MESSAGE = 7;
    /** Client side only: Self Watch is not bound right now (not installed, restarting or closed). */
    public static final int UNAVAILABLE = 8;

    private LinkStatus() {
    }
}
