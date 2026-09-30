package com.sierranevadalabs.systemone.sdk

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestRetryConfig
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.utils.unwrapCancellationException
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.fromHttpToGmtDate
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlin.math.pow
import kotlin.math.round

/**
 * The two ways a Ktor call can fail before a response exists. Everything else — an abort, a programming error —
 * is not a transport failure and is classified as `null`.
 */
internal enum class TransportFailureKind {
    Timeout,
    Connection,
}

/**
 * Classifies a failure thrown by Ktor or an engine, so the retry predicate and the boundary translate a call the
 * same way. `CancellationException` is never a transport failure: the caller aborted.
 */
internal fun Throwable.transportFailureKind(): TransportFailureKind? {
    val cause = unwrapCancellationException()
    if (cause is CancellationException) return null
    return when (cause) {
        // Ktor's own `isTimeoutException()` is private, so the three-class check is reimplemented here.
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is SocketTimeoutException,
        -> TransportFailureKind.Timeout

        is IOException -> TransportFailureKind.Connection

        else -> null
    }
}

/** Whether [this] failure is retried by [policy]. */
internal fun Throwable.isRetriedBy(policy: RetryPolicy): Boolean =
    when (transportFailureKind()) {
        TransportFailureKind.Timeout -> policy.apiTimeoutError
        TransportFailureKind.Connection -> policy.apiConnectionError
        null -> false
    }

/**
 * Parses `retry-after-ms` then `Retry-After`, preferring the first, into a delay in milliseconds. Returns `null`
 * for anything that is not a non-negative finite delay. Matches the JavaScript SDK, which is also the parser
 * `RateLimitError.retryAfterMs` reads from.
 */
internal fun parseRetryAfterMs(
    headers: Headers,
    now: Long = getTimeMillis(),
): Long? {
    val milliseconds = headers[RETRY_AFTER_MS_HEADER]?.trim()?.toDoubleOrNull()
    if (milliseconds != null && milliseconds.isFinite() && milliseconds >= 0) return milliseconds.toLong()

    val raw = headers[HttpHeaders.RetryAfter]?.trim().orEmpty()
    raw.toDoubleOrNull()?.let { seconds ->
        // A non-finite delta is not a delta; fall through to the date form rather than accepting it.
        if (seconds.isFinite()) return if (seconds >= 0) (seconds * 1000).toLong() else null
    }

    val timestamp = runCatching { raw.fromHttpToGmtDate().timestamp }.getOrNull() ?: return null
    return (timestamp - now).coerceAtLeast(0)
}

/**
 * The retry delay for a zero-based [attempt]: the server's `Retry-After` when it is usable and within the
 * policy's cap, otherwise capped exponential backoff with subtractive jitter. This is the JavaScript SDK's
 * `retryDelayMs`, exactly.
 */
internal fun retryDelayMs(
    attempt: Int,
    headers: Headers?,
    policy: RetryPolicy,
    random: () -> Double,
): Long {
    if (policy.respectRetryAfter && headers != null) {
        val serverDelay = parseRetryAfterMs(headers)
        if (serverDelay != null && serverDelay <= policy.maxRetryAfter.inWholeMilliseconds) return serverDelay
    }

    val exponential =
        minOf(
            policy.backoffInitial.inWholeMilliseconds * 2.0.pow(attempt),
            policy.backoffMax.inWholeMilliseconds.toDouble(),
        )
    return round(exponential * (1.0 - random() * policy.backoffJitter)).toLong()
}

/**
 * Translates [policy] into a complete Ktor retry configuration. Every field a partial override could silently
 * restore Ktor's own default from is written here, which is why this is applied to the client and, again, to
 * every request.
 */
internal fun HttpRequestRetryConfig.applyPolicy(
    policy: RetryPolicy,
    random: () -> Double,
    retryCountHeader: String? = null,
) {
    maxRetries = policy.maxRetries
    val statuses = policy.httpStatuses.toSet()
    retryIf { _, response -> response.status.value in statuses }
    retryOnExceptionIf { _, cause -> cause.isRetriedBy(policy) }
    // A provider that declares no retry-count header gets none; the retry itself still happens.
    retryCountHeader?.let { header -> modifyRequest { it.headers[header] = retryCount.toString() } }
    delayMillis(respectRetryAfterHeader = false) { retry ->
        // Ktor counts retries from 1; our policy counts from 0.
        retryDelayMs(retry - 1, response?.headers, policy, random)
    }
}
