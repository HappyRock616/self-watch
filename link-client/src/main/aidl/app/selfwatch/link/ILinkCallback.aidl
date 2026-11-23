/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.link;

/** Calls from Self Watch back to a linked phone app. Never blocks the service. */
oneway interface ILinkCallback {
    /** A message from the watch app; message is LinkCodec bytes. */
    void onMessage(long deviceId, String appUuid, in byte[] message);

    /** Result of send(): 0 delivered, 1 failed, 2 too large, 3 not installed, 4 not connected, 5 denied. */
    void onSendResult(int requestId, int status);

    /** A watch connected or disconnected. */
    void onDeviceStatus(long deviceId, boolean connected);
}
