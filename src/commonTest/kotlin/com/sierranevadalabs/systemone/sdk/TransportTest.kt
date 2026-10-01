package com.sierranevadalabs.systemone.sdk

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.toHttpDate
import io.ktor.util.date.GMTDate
import io.ktor.util.date.getTimeMillis
import io.ktor.util.date.truncateToSeconds
import io.ktor.utils.io.ClosedByteChannelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class TransportTest {
    private val unavailable = HttpStatusCode.ServiceUnavailable

    @Test
    fun retriesAndRecordsTheDelaysAndTheAttemptHeader() =
        runTest {
            val delays = mutableListOf<Long>()
            val retryCounts = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    retryCounts += request.headers[TYPESAFE_RETRY_COUNT_HEADER]
                    if (retryCounts.size < 3) {
                        respondError(unavailable, """{"error":{"message":"later"}}""")
                    } else {
                        respond("""{"ok":true}""")
                    }
                }
            val transport = transport(engine, sleeper = { delays += it })

            val response = transport.request(HttpMethod.Post, "/v1/system-one", body = """{"state":"x"}""")

            assertEquals(200, response.status)
            assertEquals(listOf(null, "1", "2"), retryCounts)
            assertEquals(listOf(500L, 1_000L), delays)
            transport.close()
        }

    @Test
    fun retriesOnlyTheConfiguredStatuses() =
        runTest {
            suspend fun attemptsFor(status: Int): Int {
                var attempts = 0
                val engine =
                    MockEngine {
                        attempts++
                        respond("body", HttpStatusCode.fromValue(status))
                    }
                val transport = transport(engine)
                transport.request(HttpMethod.Get, "/v1/models")
                transport.close()
                return attempts
            }

            for (status in listOf(408, 429, 500, 502, 599)) {
                assertEquals(3, attemptsFor(status), "expected $status to be retried")
            }
            for (status in listOf(200, 400, 401, 403, 404, 422)) {
                assertEquals(1, attemptsFor(status), "expected $status not to be retried")
            }
        }

    @Test
    fun honoursRetryAfterSecondsThroughKtor() =
        runTest {
            assertEquals(listOf(2_000L), delaysFor(headersOf("Retry-After", "2")))
        }

    @Test
    fun honoursRetryAfterHttpDatesThroughKtor() =
        runTest {
            // The first backoff is 500 ms, so anything in this band can only be the parsed server value. This is
            // what pins `respectRetryAfterHeader = false`: Ktor's own handling only reads integer seconds.
            val header = headersOf("Retry-After", GMTDate(getTimeMillis() + 4_000).truncateToSeconds().toHttpDate())

            val delays = delaysFor(header)

            assertEquals(1, delays.size)
            assertTrue(delays.single() in 2_500..4_000, "expected the server's delay, got ${delays.single()}")
        }

    @Test
    fun fallsBackToTheBackoffWhenRetryAfterExceedsTheCap() =
        runTest {
            // The real regression test for `respectRetryAfterHeader = false`: with Ktor's own handling these two
            // headers would win over our 60 s cap and the recorded delays would be 120_000 and 1_500.
            assertEquals(listOf(500L), delaysFor(headersOf("Retry-After", "120")))
            assertEquals(listOf(1_500L), delaysFor(headersOf("retry-after-ms", "1500")))
        }

    @Test
    fun ignoresRetryAfterValuesItCannotUse() =
        runTest {
            assertEquals(listOf(500L), delaysFor(headersOf("Retry-After", "soon")))
            assertEquals(listOf(500L), delaysFor(headersOf("Retry-After", "-3")))
            assertEquals(listOf(500L), delaysFor(headersOf()))
        }

    @Test
    fun appliesCappedExponentialBackoff() =
        runTest {
            val delays = mutableListOf<Long>()
            val engine = MockEngine { respondError(unavailable) }
            val transport =
                transport(
                    engine,
                    retryPolicy = RetryPolicy(maxRetries = 5, backoffJitter = 0.0),
                    sleeper = { delays += it },
                )

            transport.request(HttpMethod.Get, "/v1/models")

            assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 5_000L), delays)
            transport.close()
        }

    @Test
    fun zeroMaxRetriesDisablesRetrying() =
        runTest {
            // ported from typesafe-sdk-js/test/reliability.test.ts — "per-call maxRetries overrides the client,
            // and 0 disables retries". Zero is the value a caller reaches for to stop the SDK retrying at all.
            var attempts = 0
            val engine =
                MockEngine {
                    attempts++
                    respondError(unavailable)
                }
            val transport = transport(engine, retryPolicy = RetryPolicy(maxRetries = 0))

            val response = transport.request(HttpMethod.Get, "/v1/models")

            assertEquals(unavailable.value, response.status)
            assertEquals(1, attempts)
            transport.close()
        }

    @Test
    fun anAlreadyCancelledScopeNeverReachesTheEngine() =
        runTest {
            // ported from typesafe-sdk-js/test/retry.test.ts — "rejects immediately if the signal is already
            // aborted". The coroutine is our abort signal, so a cancelled scope must not put a request on the
            // wire, let alone retry one.
            var attempts = 0
            val engine =
                MockEngine {
                    attempts++
                    respond("""{"ok":true}""")
                }
            val transport = transport(engine)
            val cancelled = Job().apply { cancel() }

            val failure =
                runCatching {
                    withContext(cancelled) { transport.request(HttpMethod.Get, "/v1/models") }
                }.exceptionOrNull()

            assertIs<CancellationException>(failure)
            assertEquals(0, attempts)
            transport.close()
        }

    @Test
    fun perCallPoliciesDoNotLeakBetweenConcurrentCalls() =
        runTest {
            val engine =
                MockEngine { request ->
                    when (request.headers[TYPESAFE_RETRY_COUNT_HEADER]) {
                        null, "1" -> respondError(unavailable)
                        else -> respond("""{"ok":true}""")
                    }
                }
            val transport = transport(engine, retryPolicy = RetryPolicy(maxRetries = 5))

            awaitAll(
                async { transport.request(HttpMethod.Get, "/v1/models", policy = RetryPolicy(maxRetries = 0)) },
                async { transport.request(HttpMethod.Post, "/v1/system-one", policy = RetryPolicy(maxRetries = 2)) },
            )

            val attemptsByPath = engine.requestHistory.groupBy { it.url.encodedPath }.mapValues { it.value.size }
            assertEquals(1, attemptsByPath["/v1/models"], "the per-call zero-retry policy must win over the client's five")
            assertEquals(3, attemptsByPath["/v1/system-one"])
            transport.close()
        }

    @Test
    fun cancellationDuringTheDelayPropagatesAndDoesNotRetry() =
        runTest {
            var attempts = 0
            val reachedDelay = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    attempts++
                    respondError(unavailable)
                }
            // The engine's handler runs on a real dispatcher, so waiting on the sleeper is the only
            // deterministic point at which one attempt has happened and the retry delay is in flight.
            val transport =
                transport(
                    engine,
                    sleeper = {
                        reachedDelay.complete(Unit)
                        delay(it)
                    },
                )

            val call = launch { transport.request(HttpMethod.Get, "/v1/models") }
            reachedDelay.await()
            call.cancelAndJoin()

            assertTrue(call.isCancelled)
            assertEquals(1, attempts)
            transport.close()
        }

    @Test
    fun aCancellationThrownByTheEngineIsNotRetried() =
        runTest {
            var attempts = 0
            val engine =
                MockEngine {
                    attempts++
                    throw CancellationException("caller aborted")
                }
            val transport = transport(engine)

            val failure = runCatching { transport.request(HttpMethod.Get, "/v1/models") }.exceptionOrNull()

            assertIs<CancellationException>(failure)
            assertEquals(1, attempts)
            transport.close()
        }

    @Test
    fun surfacesTheLastFailureWithItsCauseIntact() =
        runTest {
            var attempt = 0
            val engine =
                MockEngine {
                    attempt++
                    throw IOException("fail-$attempt")
                }
            val transport = transport(engine)

            val failure = runCatching { transport.request(HttpMethod.Get, "/v1/models") }.exceptionOrNull()

            assertIs<TransportException.Connection>(failure)
            assertIs<IOException>(failure.cause)
            assertEquals("fail-3", failure.cause?.message)
            assertNull(engine.requestHistory.firstOrNull(), "a throwing handler records no request history")
            transport.close()
        }

    @Test
    fun classifiesConnectionFailuresSeparatelyAndHonoursApiConnectionError() =
        runTest {
            var attempts = 0
            val engine =
                MockEngine {
                    attempts++
                    throw IOException("connect refused")
                }

            val retrying = transport(engine)
            assertIs<TransportException.Connection>(
                runCatching { retrying.request(HttpMethod.Get, "/v1/models") }.exceptionOrNull(),
            )
            assertEquals(3, attempts, "apiConnectionError defaults to true")
            retrying.close()

            attempts = 0
            val notRetrying = transport(engine, retryPolicy = RetryPolicy(apiConnectionError = false))
            assertIs<TransportException.Connection>(
                runCatching { notRetrying.request(HttpMethod.Get, "/v1/models") }.exceptionOrNull(),
            )
            assertEquals(1, attempts)
            notRetrying.close()
        }

    @Test
    fun classifiesTimeoutsSeparatelyAndHonoursApiTimeoutError() =
        runTest {
            var attempts = 0
            val engine =
                MockEngine {
                    attempts++
                    throw HttpRequestTimeoutException("https://api.typesafe.ai/v1/models", 1_000)
                }

            val retrying = transport(engine)
            assertIs<TransportException.Timeout>(
                runCatching { retrying.request(HttpMethod.Get, "/v1/models") }.exceptionOrNull(),
            )
            assertEquals(3, attempts, "apiTimeoutError defaults to true")
            retrying.close()

            attempts = 0
            val notRetrying = transport(engine, retryPolicy = RetryPolicy(apiTimeoutError = false))
            assertIs<TransportException.Timeout>(
                runCatching { notRetrying.request(HttpMethod.Get, "/v1/models") }.exceptionOrNull(),
            )
            assertEquals(1, attempts)
            notRetrying.close()
        }

    @Test
    fun classifiesATimeoutWrappedInAChannelExceptionAsATimeout() =
        runTest {
            // CIO closes the read channel on a socket timeout and surfaces `ClosedByteChannelException`, an
            // `IOException` that wraps the real `SocketTimeoutException`. Reading only the head would report
            // this timeout as a connection failure, which is the flake this test pins down.
            val engine =
                MockEngine {
                    throw ClosedByteChannelException(
                        ClosedByteChannelException(SocketTimeoutException("read timed out", null)),
                    )
                }
            val transport = transport(engine)

            val failure = runCatching { transport.request(HttpMethod.Get, "/v1/models") }.exceptionOrNull()

            assertIs<TransportException.Timeout>(failure)
            transport.close()
        }

    @Test
    fun treatsAnInnerCancellationAsAConnectionFailure() {
        // Only a head-level `CancellationException` is a caller abort. One below the head is an engine wrapper;
        // treating it as an abort would stop the retry of a real transport failure, which is the bug this
        // classifier exists to avoid.
        val failure = ClosedByteChannelException(CancellationException("engine cancelled"))

        assertEquals(TransportFailureKind.Connection, failure.transportFailureKind())
    }

    @Test
    fun buildsTheRequestFromTheBaseUrlAndThePath() =
        runTest {
            val engine = MockEngine { respond("""{"ok":true}""") }
            val transport = transport(engine)

            transport.request(HttpMethod.Get, "/v1/models")

            val request = engine.requestHistory.single()
            assertEquals("https://api.typesafe.ai/v1/models", request.url.toString())
            assertEquals(HttpMethod.Get, request.method)
            transport.close()
        }

    @Test
    fun assemblesHeadersWithTheSiblingsPrecedenceRules() =
        runTest {
            val engine = MockEngine { respond("""{"ok":true}""") }
            val transport =
                transport(
                    engine,
                    defaultHeaders =
                        mapOf(
                            "X-Default" to "from-default",
                            "accept" to "text/plain",
                            "Authorization" to "Bearer caller-attempt",
                        ),
                )

            transport.request(
                HttpMethod.Post,
                "/v1/system-one",
                body = """{"state":"x"}""",
                headers =
                    mapOf(
                        "X-Default" to "from-caller",
                        "Accept" to "application/xml",
                        "Content-Type" to "text/plain",
                        TYPESAFE_RETRY_COUNT_HEADER to "9",
                    ),
            )

            val request = engine.requestHistory.single()
            assertEquals("from-caller", request.headers["X-Default"], "caller headers beat the client defaults")
            assertEquals("Bearer test-key", request.headers[AUTHORIZATION_HEADER])
            assertEquals("application/json", request.headers["Accept"])
            assertEquals(1, request.headers.getAll("Accept")?.size, "names must be merged case-insensitively")
            assertNull(request.headers[TYPESAFE_RETRY_COUNT_HEADER], "a caller-supplied retry count is stripped, not honoured")
            assertEquals("typesafe-sdk-kotlin/$SDK_VERSION", request.headers[TYPESAFE_SDK_HEADER])
            assertTrue(request.headers[TYPESAFE_RUNTIME_HEADER]?.contains('/') == true)
            assertEquals("""{"state":"x"}""", request.body.toByteArray().decodeToString())
            transport.close()
        }

    @Test
    fun replacesProtectedHeadersWhateverCaseTheCallerSpelledThemIn() =
        runTest {
            // ported from typesafe-sdk-js/test/release-regressions.test.ts — "replaces mixed-case defaults and
            // protects every SDK header on every attempt". The exact-case `Accept` pair and the retry-count drop
            // are already pinned above; what was missing is a differently-spelled protected name, which the
            // lowercase-keyed merge has to fold onto the same entry rather than leave beside ours.
            val engine = MockEngine { respond("""{"ok":true}""") }
            val transport =
                transport(
                    engine,
                    defaultHeaders =
                        mapOf(
                            "authorization" to "Bearer caller",
                            "content-TYPE" to "text/plain",
                            "x-typesafe-sdk" to "caller/9",
                            "X-TYPESAFE-RUNTIME" to "caller",
                            "x-typesafe-retry-count" to "9",
                        ),
                )

            transport.request(HttpMethod.Post, "/v1/system-one", body = """{"state":"x"}""")

            val request = engine.requestHistory.single()
            assertEquals("Bearer test-key", request.headers[AUTHORIZATION_HEADER])
            assertEquals("typesafe-sdk-kotlin/$SDK_VERSION", request.headers[TYPESAFE_SDK_HEADER])
            assertTrue(request.headers[TYPESAFE_RUNTIME_HEADER]?.contains('/') == true, request.headers[TYPESAFE_RUNTIME_HEADER])
            assertNull(request.headers[TYPESAFE_RETRY_COUNT_HEADER], "a caller-supplied retry count is removed, whatever case it used")
            for (name in listOf(AUTHORIZATION_HEADER, TYPESAFE_SDK_HEADER, TYPESAFE_RUNTIME_HEADER)) {
                assertEquals(1, request.headers.getAll(name)?.size, "$name must appear exactly once")
            }
            assertEquals(ContentType.Application.Json, request.body.contentType, "the caller's content type loses to ours")
            assertTrue((request.headers.getAll(CONTENT_TYPE_HEADER)?.size ?: 0) <= 1, "content type is not duplicated")
            transport.close()
        }

    @Test
    fun aBodylessRequestCarriesNoContentType() =
        runTest {
            val engine = MockEngine { respond("""{"models":[]}""") }
            val transport = transport(engine)

            transport.request(HttpMethod.Get, "/v1/models")

            assertNull(engine.requestHistory.single().headers["Content-Type"])
            transport.close()
        }

    @Test
    fun surfacesTheStatusBodyHeadersAndRequestIdWithoutConsumingThem() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        """{"error":{"message":"slow down"}}""",
                        HttpStatusCode.TooManyRequests,
                        multiHeaders("x-typesafe-request-id" to "req_123", "Retry-After" to "7"),
                    )
                }
            val transport = transport(engine, retryPolicy = RetryPolicy(maxRetries = 0))

            val response = transport.request(HttpMethod.Get, "/v1/models")

            assertEquals(429, response.status)
            assertEquals("""{"error":{"message":"slow down"}}""", response.body)
            assertEquals("req_123", response.requestId)
            assertEquals("req_123", response.headers["x-typesafe-request-id"])
            assertEquals(7_000L, response.retryAfterMs)
            assertEquals(1, engine.requestHistory.size)
            transport.close()
        }

    @Test
    fun respectRetryAfterFalseAlsoHidesTheDelayFromTheError() =
        runTest {
            val engine = MockEngine { respond("", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "7")) }
            val transport = transport(engine, retryPolicy = RetryPolicy(maxRetries = 0, respectRetryAfter = false))

            assertNull(transport.request(HttpMethod.Get, "/v1/models").retryAfterMs)
            transport.close()
        }

    @Test
    fun perCallTimeoutOverridesTheRequestTimeout() =
        runTest {
            val engine = MockEngine { respond("""{"ok":true}""") }
            val transport = transport(engine, timeout = 10.seconds)

            transport.request(HttpMethod.Get, "/v1/models", timeout = 250.milliseconds)

            val capability = engine.requestHistory.single().getCapabilityOrNull(HttpTimeoutCapability)
            assertEquals(250L, capability?.requestTimeoutMillis)
            transport.close()
        }

    @Test
    fun logsOneLinePerRetryAndNeverHeadersOrBodies() =
        runTest {
            val lines = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    if (request.headers[TYPESAFE_RETRY_COUNT_HEADER] == null) {
                        respond("secret-body", unavailable, multiHeaders("x-secret" to "secret-header"))
                    } else {
                        respond("""{"ok":true}""")
                    }
                }
            val transport = transport(engine, log = { lines += it })

            transport.request(HttpMethod.Get, "/v1/models")

            // One line per retry, then one for the response the call finally read. Both are leak-tested.
            assertEquals(2, lines.size, lines.toString())
            assertTrue(lines[0].contains("retry 1"), lines[0])
            assertTrue(lines[0].contains("503"), lines[0])
            assertTrue(lines[1].contains("GET /v1/models <- 200"), lines[1])
            assertFalse(lines.joinToString("\n").contains("secret"), lines.toString())
            transport.close()
        }

    @Test
    fun closeIsIdempotentAndLeavesAnEngineWeWereHandedAlive() =
        runTest {
            val engine = MockEngine { respond("""{"ok":true}""") }
            val transport = transport(engine)

            transport.close()
            transport.close()

            val second = transport(engine)
            assertEquals(200, second.request(HttpMethod.Get, "/v1/models").status)
            second.close()
        }

    @Test
    fun aClosedTransportNeverReachesTheEngineAgain() =
        runTest {
            var attempts = 0
            val engine =
                MockEngine {
                    attempts++
                    respond("""{"ok":true}""")
                }
            val transport = transport(engine)
            assertEquals(200, transport.request(HttpMethod.Get, "/v1/models").status)

            transport.close()
            val refusal =
                assertIs<IllegalStateException>(
                    runCatching { transport.request(HttpMethod.Get, "/v1/models") }.exceptionOrNull(),
                )
            assertTrue(
                refusal.message?.contains("transport is closed") == true,
                "the transport must refuse the call itself, not leave it to Ktor: $refusal",
            )

            // A closed transport must not reach the engine. This count was 2 on the CI runners while the guard
            // was Ktor's alone, which let the request through.
            assertEquals(1, attempts)
        }

    private suspend fun delaysFor(retryAfter: Headers): List<Long> {
        val delays = mutableListOf<Long>()
        val engine =
            MockEngine { request ->
                if (request.headers[TYPESAFE_RETRY_COUNT_HEADER] == null) {
                    respond("", unavailable, retryAfter)
                } else {
                    respond("""{"ok":true}""")
                }
            }
        val transport = transport(engine, sleeper = { delays += it })
        return try {
            transport.request(HttpMethod.Get, "/v1/models")
            delays
        } finally {
            transport.close()
        }
    }
}

private fun multiHeaders(vararg pairs: Pair<String, String>): Headers =
    HeadersBuilder().apply { pairs.forEach { (name, value) -> append(name, value) } }.build()

private fun transport(
    engine: MockEngine,
    defaultHeaders: Map<String, String> = emptyMap(),
    timeout: Duration = 10.seconds,
    retryPolicy: RetryPolicy = RetryPolicy(),
    log: (String) -> Unit = {},
    random: () -> Double = { 0.0 },
    sleeper: suspend (Long) -> Unit = {},
    timeSource: TimeSource = TimeSource.Monotonic,
): Transport =
    createTransport(
        apiKey = "test-key",
        baseUrl = "https://api.typesafe.ai/",
        engine = engine,
        defaultHeaders = defaultHeaders,
        timeout = timeout,
        retryPolicy = retryPolicy,
        log = log,
        random = random,
        sleeper = sleeper,
        timeSource = timeSource,
    )
