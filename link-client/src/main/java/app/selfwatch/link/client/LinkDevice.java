/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package app.selfwatch.link.client;

import org.json.JSONException;
import org.json.JSONObject;

/** A watch known to Self Watch. Immutable. */
public final class LinkDevice {
    private final long id;
    private final String name;
    private final boolean connected;

    /**
     * @param id Self Watch's id for the watch, stable while the watch stays paired.
     * @param name the watch's name as shown in Self Watch; empty when not known yet.
     * @param connected whether the watch is connected now.
     */
    public LinkDevice(long id, String name, boolean connected) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.connected = connected;
    }

    /** Self Watch's id for the watch. */
    public long getId() {
        return id;
    }

    /** The watch's name; empty when not known yet. */
    public String getName() {
        return name;
    }

    /** Whether the watch was connected when this object was made. */
    public boolean isConnected() {
        return connected;
    }

    /** Parses the service's JSON ({"id":…,"name":…,"connected":…}); null when malformed. */
    static LinkDevice fromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            if (!o.has("id")) {
                return null;
            }
            return new LinkDevice(o.getLong("id"), o.optString("name", ""), o.optBoolean("connected", false));
        } catch (JSONException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof LinkDevice)) {
            return false;
        }
        LinkDevice d = (LinkDevice) other;
        return id == d.id && connected == d.connected && name.equals(d.name);
    }

    @Override
    public int hashCode() {
        return (int) (id ^ (id >>> 32)) * 31 + name.hashCode() * 2 + (connected ? 1 : 0);
    }

    @Override
    public String toString() {
        return "LinkDevice(id=" + id + ", name=" + name + ", connected=" + connected + ")";
    }
}
