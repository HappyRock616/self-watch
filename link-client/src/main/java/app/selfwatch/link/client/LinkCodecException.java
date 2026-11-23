/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.link.client;

/** Thrown by {@link LinkCodec#decode(byte[])} when the bytes are not a valid link message. */
public final class LinkCodecException extends Exception {
    private static final long serialVersionUID = 1L;

    /** A decode failure with a short reason (never contains message content). */
    public LinkCodecException(String reason) {
        super(reason);
    }
}
