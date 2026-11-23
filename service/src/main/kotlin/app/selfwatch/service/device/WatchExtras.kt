/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.device

import app.selfwatch.model.json.Json
import app.selfwatch.model.json.JsonValue
import app.selfwatch.model.result.Outcome
import app.selfwatch.model.watch.Alarm
import java.time.DayOfWeek

/**
 * The watch alarms the Devices screen edits (C19), kept per watch as the setting [key]
 * `alarms.<deviceId>` (a JSON array). The service sends them with `WatchCommand.SetAlarms` on
 * `SyncCommands.ACTION_SET_ALARMS` and publishes how that went as [resultKey].
 *
 * Format: `[{"id":1,"h":7,"m":30,"days":["MONDAY",…],"on":true,"label":"Run"}, …]`.
 */
public object AlarmSettings {
    /** Most alarms a watch keeps. */
    public const val MAX: Int = 10

    /** The setting key holding watch [deviceId]'s alarms. */
    public fun key(deviceId: Long): String = "alarms.$deviceId"

    /** The setting key holding the last send result of watch [deviceId] ([encodeResult]). */
    public fun resultKey(deviceId: Long): String = "alarms.result.$deviceId"

    /** [alarms] as stored (at most [MAX]). */
    public fun encode(alarms: List<Alarm>): String = Json.write(
        JsonValue.Arr(
            alarms.take(MAX).map { a ->
                val fields = linkedMapOf<String, JsonValue>(
                    "id" to JsonValue.Num.of(a.id.toLong()),
                    "h" to JsonValue.Num.of(a.hour.toLong()),
                    "m" to JsonValue.Num.of(a.minute.toLong()),
                    "days" to JsonValue.Arr(a.days.sorted().map { JsonValue.Str(it.name) }),
                    "on" to JsonValue.Bool(a.enabled),
                )
                a.label?.let { fields["label"] = JsonValue.Str(it) }
                JsonValue.Obj(fields)
            },
        ),
    )

    /** The alarms stored as [text]; empty when missing or unreadable. Bad entries are skipped. */
    public fun decode(text: String?): List<Alarm> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = (Json.parse(text) as? Outcome.Ok)?.value?.asList ?: return emptyList()
        return arr.mapNotNull { j ->
            val id = j["id"]?.asLong?.toInt() ?: return@mapNotNull null
            val h = j["h"]?.asLong?.toInt()?.takeIf { it in 0..23 } ?: return@mapNotNull null
            val m = j["m"]?.asLong?.toInt()?.takeIf { it in 0..59 } ?: return@mapNotNull null
            val days = j["days"]?.asList.orEmpty().mapNotNull { d ->
                d.asString?.let { n -> DayOfWeek.entries.firstOrNull { it.name == n } }
            }.toSet()
            Alarm(id, h, m, days, j["on"]?.asBoolean ?: true, j["label"]?.asString?.takeIf { it.isNotBlank() })
        }.take(MAX)
    }

    /** A send result as stored: `ok|<tSec>|` or `fail|<tSec>|<reason>`. */
    public fun encodeResult(ok: Boolean, tSec: Long, reason: String?): String =
        listOf(if (ok) "ok" else "fail", tSec.toString(), (reason ?: "").replace('\n', ' ').take(MAX_REASON)).joinToString("|")

    /** A decoded send result. */
    public data class SendResult(
        /** True when the watch took the alarms. */
        val ok: Boolean,
        /** When, UTC epoch seconds. */
        val tSec: Long,
        /** Why it failed, or null. */
        val reason: String?,
    )

    /** The result stored as [text], or null. */
    public fun decodeResult(text: String?): SendResult? {
        val p = text?.split('|', limit = 3) ?: return null
        if (p.size < 3) return null
        val ok = when (p[0]) {
            "ok" -> true
            "fail" -> false
            else -> return null
        }
        return SendResult(ok, p[1].toLongOrNull() ?: return null, p[2].ifEmpty { null })
    }

    private const val MAX_REASON = 120
}
