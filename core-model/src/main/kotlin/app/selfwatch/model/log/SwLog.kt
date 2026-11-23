/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.model.log

/**
 * The app's only logger. The app sets [sink] at start (Android: rotating file, plus logcat in debug
 * builds). Until then messages go nowhere.
 *
 * Privacy: never put health values, food names, notification text, payloads or contact names in
 * INFO, WARN or ERROR messages. Such details belong in [d], which is off unless [debugEnabled].
 */
public object SwLog {
    /** Where messages go. Defaults to [LogSink.NONE]. */
    @Volatile
    public var sink: LogSink = LogSink.NONE

    /** When false (the default), [d] does not even build its message. */
    @Volatile
    public var debugEnabled: Boolean = false

    /** Debug message, built lazily and only when [debugEnabled]. */
    public fun d(tag: String, msg: () -> String) {
        if (debugEnabled) sink.write(LogSink.DEBUG, tag, msg(), null)
    }

    /** Info message. */
    public fun i(tag: String, msg: String) {
        sink.write(LogSink.INFO, tag, msg, null)
    }

    /** Warning, with an optional cause. */
    public fun w(tag: String, msg: String, t: Throwable? = null) {
        sink.write(LogSink.WARN, tag, msg, t)
    }

    /** Error, with an optional cause. */
    public fun e(tag: String, msg: String, t: Throwable? = null) {
        sink.write(LogSink.ERROR, tag, msg, t)
    }
}

/** A log destination. Levels use the same numbers as `android.util.Log`. */
public fun interface LogSink {
    /** Writes one message. Must be thread-safe and must not throw. */
    public fun write(level: Int, tag: String, msg: String, t: Throwable?)

    /** Level constants and the no-op sink. */
    public companion object {
        /** Debug level (3). */
        public const val DEBUG: Int = 3

        /** Info level (4). */
        public const val INFO: Int = 4

        /** Warning level (5). */
        public const val WARN: Int = 5

        /** Error level (6). */
        public const val ERROR: Int = 6

        /** A sink that discards everything. */
        public val NONE: LogSink = LogSink { _, _, _, _ -> }
    }
}
