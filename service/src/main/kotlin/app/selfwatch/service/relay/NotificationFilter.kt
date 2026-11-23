/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.service.relay

import android.app.Notification
import app.selfwatch.model.watch.NotificationCategory

/** The parts of a posted notification the filter looks at (read from a `StatusBarNotification`). */
public data class NotificationInput(
    /** `StatusBarNotification.key`. */
    val key: String,
    /** The posting app. */
    val packageName: String,
    /** `FLAG_ONGOING_EVENT` or a foreground-service notification. */
    val ongoing: Boolean,
    /** `FLAG_GROUP_SUMMARY`. */
    val groupSummary: Boolean,
    /** Title text, if any. */
    val title: String?,
    /** Body text, if any. */
    val text: String?,
)

/** Why a notification was not relayed (for DEBUG logs and tests). */
public enum class SkipReason { DISABLED, OWN_PACKAGE, ONGOING, GROUP_SUMMARY, DENIED_APP, EMPTY, DUPLICATE }

/**
 * The relay filter (WP-07): skips ongoing notifications, group summaries, Self Watch's own
 * notifications, apps in `notify.apps_denied`, notifications with neither title nor text, and the
 * same key with the same text again within [duplicateWindowMs] (apps often re-post unchanged).
 *
 * Not thread-safe; [NotificationRelay] calls it from one coroutine at a time.
 */
public class NotificationFilter(
    private val ownPackage: String,
    private val nowMs: () -> Long,
    private val duplicateWindowMs: Long = DUPLICATE_WINDOW_MS,
) {
    private val recent = LinkedHashMap<String, Pair<Int, Long>>()

    /** Null when [n] should be relayed (and remembers it for duplicate checks), else why not. */
    public fun check(n: NotificationInput, enabled: Boolean, deniedApps: Collection<String>): SkipReason? {
        if (!enabled) return SkipReason.DISABLED
        if (n.packageName == ownPackage) return SkipReason.OWN_PACKAGE
        if (n.ongoing) return SkipReason.ONGOING
        if (n.groupSummary) return SkipReason.GROUP_SUMMARY
        if (n.packageName in deniedApps) return SkipReason.DENIED_APP
        if (n.title.isNullOrBlank() && n.text.isNullOrBlank()) return SkipReason.EMPTY
        val now = nowMs()
        val content = (n.title.orEmpty() + '\u0000' + n.text.orEmpty()).hashCode()
        val last = recent[n.key]
        if (last != null && last.first == content && now - last.second in 0 until duplicateWindowMs) return SkipReason.DUPLICATE
        recent.remove(n.key)
        recent[n.key] = content to now
        while (recent.size > MAX_REMEMBERED) recent.remove(recent.keys.first())
        return null
    }

    /** Forgets [key] (it was removed), so a re-post is relayed again. */
    public fun forget(key: String) {
        recent.remove(key)
    }

    /** Defaults. */
    public companion object {
        /** Same key and text within this many milliseconds counts as a duplicate. */
        public const val DUPLICATE_WINDOW_MS: Long = 2_000
        private const val MAX_REMEMBERED = 128
    }
}

/** Mapping helpers from Android notifications to the watch model. */
public object NotificationMapping {
    /** The watch category for an Android [Notification.category] value (null = [NotificationCategory.OTHER]). */
    public fun category(androidCategory: String?): NotificationCategory = when (androidCategory) {
        Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL -> NotificationCategory.CALL
        Notification.CATEGORY_MESSAGE -> NotificationCategory.MESSAGE
        Notification.CATEGORY_EMAIL -> NotificationCategory.EMAIL
        Notification.CATEGORY_EVENT, Notification.CATEGORY_REMINDER, Notification.CATEGORY_ALARM -> NotificationCategory.CALENDAR
        Notification.CATEGORY_SOCIAL -> NotificationCategory.SOCIAL
        else -> NotificationCategory.OTHER
    }

    /** The watch id for a notification [key]: stable for the key, never negative. */
    public fun idFor(key: String): Int = key.hashCode() and Int.MAX_VALUE
}

/** The action names the watch sends in [app.selfwatch.model.watch.WatchEvent.NotificationAction]. */
public object NotificationActions {
    /** Reply with the event's text (garmin `NotificationsHandler.ACTION_REPLY`). */
    public const val REPLY: String = "reply"

    /** Dismiss the notification on the phone too. */
    public const val DISMISS: String = "dismiss"

    /** Stop relaying this app (adds it to `notify.apps_denied`). */
    public const val MUTE: String = "mute"
}
