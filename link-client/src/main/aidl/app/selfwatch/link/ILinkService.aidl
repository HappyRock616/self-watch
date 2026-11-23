/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.link;

import app.selfwatch.link.ILinkCallback;

/**
 * Self Watch link service, bound with action "app.selfwatch.link.BIND" in package "app.selfwatch".
 * Status codes are listed in app.selfwatch.link.client.LinkStatus. Messages are LinkCodec bytes.
 */
interface ILinkService {
    /** Interface version, 1 for this file. */
    int apiVersion();

    /** Known watches as JSON strings: {"id":123,"name":"vivoactive 5","connected":true}. */
    List<String> devices();

    /** JSON {"installed":true,"version":12}, or {"error":code} with a LinkStatus code. */
    String appInfo(long deviceId, String appUuid);

    /** Receive messages for this watch app (one callback per app; a new one replaces the old). */
    void register(String appUuid, ILinkCallback cb);

    /** Stop receiving messages for this watch app. */
    void unregister(String appUuid);

    /** Returns 0 when queued (result follows in onSendResult), or a LinkStatus error code. */
    int send(long deviceId, String appUuid, in byte[] message, int requestId);

    /** Asks the watch to open the app. Returns 0 when the request was sent, or a LinkStatus code. */
    int openApp(long deviceId, String appUuid);
}
