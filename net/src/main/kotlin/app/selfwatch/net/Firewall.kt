/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.net

import app.selfwatch.db.SettingKeys
import app.selfwatch.db.SettingsStore
import app.selfwatch.model.log.SwLog
import app.selfwatch.model.net.NetFeature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Per-feature network switches, stored as settings `net.<feature>` (see [SettingKeys.net]); a
 * feature with no stored value uses [NetFeature.defaultOn]. If the setting cannot be read the
 * firewall fails closed (blocks).
 */
public class Firewall internal constructor(
    private val read: suspend (NetFeature) -> Boolean,
    private val write: suspend (NetFeature, Boolean) -> Unit,
    private val watch: (NetFeature) -> Flow<Boolean>,
) {
    /** The firewall backed by [settings]. */
    public constructor(settings: SettingsStore) : this(
        read = { settings.netAllowed(it) },
        write = { f, on -> settings.setNetAllowed(f, on) },
        watch = { f -> settings.observe(SettingKeys.net(f)).map { it?.toBooleanStrictOrNull() ?: f.defaultOn } },
    )

    /** Whether requests for [f] may go out now. */
    public suspend fun allowed(f: NetFeature): Boolean = try {
        read(f)
    } catch (e: RuntimeException) {
        SwLog.w(TAG, "firewall setting for ${f.name} not readable; blocking", e)
        false
    }

    /** Allows or blocks [f]. */
    public suspend fun set(f: NetFeature, allowed: Boolean): Unit = write(f, allowed)

    /** Every feature's switch, now and after each change (in any process). Repeats are dropped. */
    public fun observe(): Flow<Map<NetFeature, Boolean>> =
        combine(NetFeature.entries.map { f -> watch(f).map { f to it } }) { pairs -> pairs.toMap() }
            .distinctUntilChanged()

    private companion object {
        const val TAG: String = "Firewall"
    }
}
