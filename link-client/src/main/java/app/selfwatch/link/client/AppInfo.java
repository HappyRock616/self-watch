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

/** Whether a watch app is installed on a watch, and its version. Immutable. */
public final class AppInfo {
    private final boolean installed;
    private final int version;

    /**
     * @param installed whether the watch app is installed.
     * @param version the installed app's version number; 0 when not installed or unknown.
     */
    public AppInfo(boolean installed, int version) {
        this.installed = installed;
        this.version = version;
    }

    /** Whether the watch app is installed on the watch. */
    public boolean isInstalled() {
        return installed;
    }

    /** The installed version number; 0 when not installed or unknown. */
    public int getVersion() {
        return version;
    }

    /**
     * Parses the service's JSON. Returns {@link SendStatus#SUCCESS} with {@code out} filled, or the error
     * status ({"error":code}, or {@link SendStatus#FAILED} when malformed).
     */
    static SendStatus parse(String json, AppInfo[] out) {
        if (json == null) {
            return SendStatus.FAILED;
        }
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("error")) {
                SendStatus s = SendStatus.fromCode(o.getInt("error"));
                return s == SendStatus.SUCCESS ? SendStatus.FAILED : s;
            }
            if (!o.has("installed")) {
                return SendStatus.FAILED;
            }
            out[0] = new AppInfo(o.getBoolean("installed"), o.optInt("version", 0));
            return SendStatus.SUCCESS;
        } catch (JSONException e) {
            return SendStatus.FAILED;
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AppInfo && ((AppInfo) other).installed == installed
                && ((AppInfo) other).version == version;
    }

    @Override
    public int hashCode() {
        return version * 2 + (installed ? 1 : 0);
    }

    @Override
    public String toString() {
        return "AppInfo(installed=" + installed + ", version=" + version + ")";
    }
}
