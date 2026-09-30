package com.sierranevadalabs.systemone.sdk

/** Logging is off unless a caller turns it on. */
internal val DEFAULT_LOG_LEVEL: LogLevel = LogLevel.Off

/** The prefix on every line this SDK writes. One neutral prefix, whatever the provider. */
internal const val LOG_PREFIX: String = "[systemone-sdk]"

/**
 * How much the SDK logs. The default is [Off].
 *
 * What each level does today:
 *
 * - [Debug] and [Info] emit the same two lines. One is per call, after the response is read: the method, the
 *   path, the status, the duration in whole milliseconds, and the request id. The other is per retry: the
 *   attempt number and the status or cause. [Debug] is reserved for the byte-level output of the deferred
 *   pluggable-logger abstraction; it emits nothing extra yet.
 * - [Warn] and [Error] emit nothing and are accepted for sibling parity. This SDK's failures are *thrown*, and
 *   they already carry their own status, body and request id, so logging them as well would print each one
 *   twice.
 * - [Off] emits nothing.
 *
 * No level writes a header, a body, a query string, or the API key. Every line is built from the method, path,
 * status, duration and request id alone, so there is no redaction table that can be incomplete.
 */
public enum class LogLevel {
    /** Per-call and per-retry lines today; reserved for byte-level logging later. */
    Debug,

    /** The per-call line and the per-retry line. */
    Info,

    /** Accepted for sibling parity; emits nothing, because a failure is thrown rather than logged. */
    Warn,

    /** Accepted for sibling parity; emits nothing, because a failure is thrown rather than logged. */
    Error,

    /** Emits nothing. The default. */
    Off,
}

/**
 * The level gate in front of [sink], and the only place the provider's [prefix] is added. [sink] is `internal`
 * so a test can collect lines where a caller gets `println`; on Android stdout goes to logcat, so one sink
 * covers every platform without an `expect`/`actual` pair.
 */
internal fun logSink(
    prefix: String,
    level: LogLevel,
    sink: (String) -> Unit = ::println,
): (String) -> Unit =
    if (level == LogLevel.Debug || level == LogLevel.Info) {
        { message -> sink("$prefix $message") }
    } else {
        {}
    }
