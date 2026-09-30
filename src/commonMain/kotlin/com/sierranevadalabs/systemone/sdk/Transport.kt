package com.sierranevadalabs.systemone.sdk

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestRetryEvent
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.utils.unwrapCancellationException
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The one place an HTTP call leaves this SDK. Everything above it sees [TransportResponse] or
 * [TransportException] and never a Ktor type, a status-to-error mapping, or an `IOException`.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class Transport(
    private val http: HttpClient,
    private val engine: HttpClientEngine,
    private val ownsEngine: Boolean,
    private val apiKey: String,
    private val baseUrl: String,
    private val provider: SystemOneProvider,
    private val defaultHeaders: Map<String, String>,
    private val retryPolicy: RetryPolicy,
    private val random: () -> Double,
    private val log: (String) -> Unit,
    private val timeSource: TimeSource,
) : AutoCloseable {
    /**
     * Set before the client and the engine are closed, so a call that races [close] never sends. Ktor's own
     * guard is not enough: a request after close reached the engine on the CI runners, which is the one thing a
     * closed transport promises not to do. Atomic, so the write in [close] is visible to a request on another
     * thread without a data race on the flag itself.
     */
    private val closed = AtomicBoolean(false)

    /**
     * Sends one request. [body] must be a `String`, because Ktor copies the body object by reference on each
     * retry: a streamed body would be consumed by the first attempt and fail the second.
     *
     * [policy] replaces the client's retry policy for this call only, [timeout] its request timeout.
     */
    suspend fun request(
        method: HttpMethod,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Duration? = null,
        policy: RetryPolicy? = null,
    ): TransportResponse {
        check(!closed.load()) { "this transport is closed" }
        val effectivePolicy = policy ?: retryPolicy
        val started = timeSource.markNow()
        try {
            val response =
                http.request(joinUrl(baseUrl, path)) {
                    this.method = method
                    assembleHeaders(provider, defaultHeaders, headers, apiKey, hasBody = body != null).forEach { (name, value) ->
                        this.headers[name] = value
                    }
                    if (body != null) setBody(TextContent(body, ContentType.Application.Json))
                    // Only the request timeout is per-call: one timeout capability per client keeps OkHttp from
                    // rebuilding its dispatcher, and socket/connect timeouts stay pinned at the client.
                    if (timeout != null) this.timeout { requestTimeoutMillis = timeout.inWholeMilliseconds }
                    retry { applyPolicy(effectivePolicy, random, provider.retryCountHeader) }
                }
            val transportResponse = response.toTransportResponse(effectivePolicy)
            log(responseLine(method, path, transportResponse, started))
            return transportResponse
        } catch (cause: Throwable) {
            val failure = cause.asTransportFailure()
            // A call that never got a response is exactly when a reader wants a line, so it gets one carrying
            // the failure's class where a response would carry its status.
            if (failure is TransportException) log(failureLine(method, path, failure.cause, started))
            throw failure
        }
    }

    override fun close() {
        closed.store(true)
        http.close()
        if (ownsEngine) engine.close()
    }
}

/** What the layers above see: a status, the raw body, and the two headers the SDK contract surfaces. */
internal class TransportResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    val requestId: String?,
    val retryAfterMs: Long?,
)

/**
 * A Ktor or engine failure, translated at the single transport boundary. `expectSuccess` is off, so a non-2xx
 * response is data ([TransportResponse]) rather than an exception; these two are the case where no response exists.
 */
internal sealed class TransportException(
    message: String,
    cause: Throwable,
) : Exception(message, cause) {
    class Timeout(
        cause: Throwable,
    ) : TransportException("The request timed out", cause)

    class Connection(
        cause: Throwable,
    ) : TransportException("The request failed before a response arrived", cause)
}

/**
 * Builds the transport, and with it the only [HttpClient] in the module.
 *
 * The caller's [httpClientConfig] runs first, so a caller can add `Logging` or a custom auth plugin but cannot
 * break the order [HttpRequestRetry] and [HttpTimeout] require. When [engine] is `null` a platform default is
 * created by [engineFactory] and owned here; an engine that was handed to us is never closed.
 */
internal fun createTransport(
    apiKey: String,
    baseUrl: String,
    provider: SystemOneProvider = SystemOneProvider.TypeSafe,
    engine: HttpClientEngine? = null,
    defaultHeaders: Map<String, String> = emptyMap(),
    timeout: Duration = 10.seconds,
    retryPolicy: RetryPolicy = RetryPolicy(),
    log: (String) -> Unit = {},
    random: () -> Double = { Random.nextDouble() },
    sleeper: suspend (Long) -> Unit = { delay(it) },
    timeSource: TimeSource = TimeSource.Monotonic,
    httpClientConfig: HttpClientConfig<*>.() -> Unit = {},
    engineFactory: () -> HttpClientEngine = ::createDefaultEngine,
): Transport {
    val ownsEngine = engine == null
    val resolvedEngine = engine ?: engineFactory()
    val http =
        HttpClient(resolvedEngine) {
            // A 429 is data we map ourselves, not an exception Ktor raises for us.
            expectSuccess = false
            httpClientConfig()
            install(HttpRequestRetry) {
                // Per-request `retry { }` never copies `delay`, so this seam reaches every request.
                delay(sleeper)
                applyPolicy(retryPolicy, random, provider.retryCountHeader)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = timeout.inWholeMilliseconds
                socketTimeoutMillis = timeout.inWholeMilliseconds
            }
        }

    http.monitor.subscribe(HttpRequestRetryEvent) { event ->
        // One line per retry: the attempt number and the status or cause. Never headers, never bodies.
        log("retry ${event.retryCount}: ${event.response?.status?.value ?: event.cause?.let { it::class.simpleName }}")
    }

    return Transport(
        http = http,
        engine = resolvedEngine,
        ownsEngine = ownsEngine,
        apiKey = apiKey,
        baseUrl = baseUrl,
        provider = provider,
        defaultHeaders = defaultHeaders,
        retryPolicy = retryPolicy,
        random = random,
        log = log,
        timeSource = timeSource,
    )
}

/**
 * The per-call line: method, path, status, duration and request id. Nothing else — never a header, a body, the
 * query string, or the API key, all of which are unreachable from here by construction.
 */
private fun responseLine(
    method: HttpMethod,
    path: String,
    response: TransportResponse,
    started: TimeMark,
): String =
    "$method ${logPath(path)} <- ${response.status} in ${started.elapsedNow().inWholeMilliseconds}ms" +
        (response.requestId?.let { " (request $it)" } ?: "")

/** The same line for a call that never got a response, carrying the failure's class in place of a status. */
private fun failureLine(
    method: HttpMethod,
    path: String,
    cause: Throwable?,
    started: TimeMark,
): String =
    "$method ${logPath(path)} <- failed in ${started.elapsedNow().inWholeMilliseconds}ms " +
        "(${cause?.let { it::class.simpleName } ?: "unknown"})"

/** The path only: a query string never reaches a log line, whatever a caller puts in one. */
private fun logPath(path: String): String = path.substringBefore('?')

private fun joinUrl(
    baseUrl: String,
    path: String,
): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

private suspend fun HttpResponse.toTransportResponse(policy: RetryPolicy): TransportResponse =
    TransportResponse(
        status = status.value,
        headers = headers.flattened(),
        body = bodyAsText(),
        requestId = headers[REQUEST_ID_HEADER],
        retryAfterMs = if (policy.respectRetryAfter) parseRetryAfterMs(headers) else null,
    )

private fun Headers.flattened(): Map<String, String> = entries().associate { entry -> entry.key to entry.value.joinToString(", ") }

private fun Throwable.asTransportFailure(): Throwable {
    val cause = unwrapCancellationException()
    if (cause is CancellationException) return cause
    return when (cause.transportFailureKind()) {
        TransportFailureKind.Timeout -> TransportException.Timeout(cause)
        else -> TransportException.Connection(cause)
    }
}
