package com.sierranevadalabs.systemone.sdk

import com.sierranevadalabs.systemone.sdk.errors.APIConnectionError
import com.sierranevadalabs.systemone.sdk.errors.APITimeoutError
import com.sierranevadalabs.systemone.sdk.errors.SystemOneError
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Tier 2: the real transport over a real loopback socket. These are the assertions a `MockEngine` cannot make
 * honestly — the bytes on the wire, a body that stalls after a `200`, a connection dropped mid-body, a caller's
 * cancellation, and a `Retry-After` that actually waits. JVM-only, because binding a `ServerSocket` is.
 */
class WireTransportTest {
    @Test
    fun putsTheExactBytesAndFingerprintHeadersOnTheWire() =
        runBlocking {
            val server =
                WireServer {
                    WireReply.Complete(
                        status = 200,
                        body = """{"model":"jev-latest","answers":{"urgent":{"type":"noul","noul":0.5}}}""",
                        headers = mapOf("Content-Type" to "application/json"),
                    )
                }
            server.use {
                val client = client(server.port)
                val response = client.systemOne("Hello 🌍", noul("urgent", "Does this convey urgency?"))
                client.close()

                assertEquals(NoulAnswer(0.5), response.answers["urgent"])
                val request = server.recordedRequests.single()
                assertEquals("POST /v1/systemone HTTP/1.1", request.requestLine)
                assertEquals("Bearer test-key", request.header("Authorization"))
                assertEquals("application/json", request.header("Accept"))
                assertEquals("application/json", request.header("Content-Type"))
                assertEquals("typesafe-sdk-kotlin/$SDK_VERSION", request.header("X-TypeSafe-SDK"))
                assertEquals("jvm/${System.getProperty("java.version")}", request.header("X-TypeSafe-Runtime"))
                assertEquals("127.0.0.1:${server.port}", request.header("Host"))
                assertNull(request.header("X-TypeSafe-Retry-Count"), "attempt 0 carries no retry count")
                assertEquals(
                    """{"state":"Hello 🌍","model":"jev-latest","questions":{"urgent":{"type":"noul","instructions":"Does this convey urgency?"}}}""",
                    request.bodyText,
                )
            }
        }

    @Test
    fun aBodyThatStallsAfterA200TimesOutOnEveryConsumerPath() =
        runBlocking {
            val server =
                WireServer {
                    WireReply.Stalled(status = 200, declaredLength = 64, partialBody = """{"model":"jev-""")
                }
            server.use {
                val client = client(server.port, timeout = 300.milliseconds)

                val systemOne = runCatching { client.systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()
                assertIs<APITimeoutError>(systemOne, "systemOne")
                // ported from typesafe-sdk-js/test/reliability.test.ts — "retries after a timeout, each attempt
                // getting its own timeout": the default policy retries a timeout, so a stalled body is three
                // full attempts, not one budget shared across them.
                assertEquals(3, server.recordedRequests.size, "the first attempt plus one per retry")

                val models = runCatching { client.models.list() }.exceptionOrNull()
                assertIs<APITimeoutError>(models, "models.list")

                client.close()
            }
        }

    @Test
    fun aConnectionDroppedMidBodyIsRetriedAndTheFinalCauseSurvives() =
        runBlocking {
            val server =
                WireServer {
                    WireReply.Aborted(declaredLength = 64, partialBody = """{"model":"jev-""")
                }
            server.use {
                val client =
                    client(
                        server.port,
                        retry = RetryPolicy(maxRetries = 2, backoffInitial = 10.milliseconds, backoffMax = 20.milliseconds),
                    )

                val failure = runCatching { client.systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()
                client.close()

                val connection = assertIs<APIConnectionError>(failure)
                // The engine's own failure survives as the cause. Its class belongs to the engine: a dropped
                // connection arrives as an IOException, and a channel already closed arrives as an
                // IllegalStateException. So assert that the error was preserved, not that it has one class.
                val cause = connection.cause
                assertTrue(cause != null && cause !is SystemOneError, "the engine's failure must survive as the cause: $cause")
                assertEquals(3, server.recordedRequests.size, "the first attempt plus one per retry")
            }
        }

    @Test
    fun aCallerCancellationAfterHeadersNeverBecomesAnSdkError() =
        runBlocking {
            val server =
                WireServer {
                    WireReply.Stalled(status = 200, declaredLength = 64, partialBody = """{"model":"jev-""")
                }
            server.use {
                val client = client(server.port, timeout = 10.seconds)
                val outcome = CompletableDeferred<Throwable?>()

                val call =
                    launch(Dispatchers.Default) {
                        val failure =
                            try {
                                client.systemOne("hello", noul("urgent", "?"))
                                null
                            } catch (thrown: Throwable) {
                                thrown
                            }
                        outcome.complete(failure)
                    }

                assertTrue(server.awaitFirstReply(5.seconds), "the server handed out the headers")
                call.cancelAndJoin()

                val failure = outcome.await()
                // A CancellationException is never a SystemOneError, so this one assertion is the whole guard.
                assertTrue(failure is CancellationException, "an abort stays an abort, was $failure")
                client.close()
            }
        }

    @Test
    fun a429WithRetryAfterActuallyDelaysTheRetryAndStampsTheAttempt() =
        runBlocking {
            val server =
                WireServer { attempt ->
                    if (attempt == 0) {
                        WireReply.Complete(
                            status = 429,
                            body = """{"error":{"message":"slow down"}}""",
                            headers = mapOf("Content-Type" to "application/json", "Retry-After" to "1"),
                        )
                    } else {
                        WireReply.Complete(
                            status = 200,
                            body = """{"model":"jev-latest","answers":{"urgent":{"type":"noul","noul":0.7}}}""",
                            headers = mapOf("Content-Type" to "application/json"),
                        )
                    }
                }
            server.use {
                val client = client(server.port)

                val elapsed = TimeSource.Monotonic.measureTime { client.systemOne("hello", noul("urgent", "?")) }
                client.close()

                assertEquals(2, server.recordedRequests.size)
                assertTrue(elapsed >= 900.milliseconds, "waited $elapsed for the server's `Retry-After: 1`")
                assertNull(server.recordedRequests[0].header("X-TypeSafe-Retry-Count"))
                assertEquals("1", server.recordedRequests[1].header("X-TypeSafe-Retry-Count"))
            }
        }
}

/** A scripted HTTP/1.1 reply. */
internal sealed class WireReply {
    class Complete(
        val status: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    ) : WireReply()

    /** A head whose declared body length outruns the bytes sent; the connection stays open. */
    class Stalled(
        val status: Int,
        val declaredLength: Int,
        val partialBody: String,
    ) : WireReply()

    /** A head whose body is cut off by an abort, so the peer sees the connection reset mid-body. */
    class Aborted(
        val declaredLength: Int,
        val partialBody: String,
    ) : WireReply()
}

/** One request the server read, kept as the bytes that arrived plus the parsed head. */
internal class RecordedRequest(
    val head: String,
    val body: ByteArray,
) {
    val requestLine: String get() = head.lineSequence().first()

    fun header(name: String): String? =
        head
            .lineSequence()
            .drop(1)
            .takeWhile { it.isNotBlank() }
            .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    val bodyText: String get() = body.decodeToString()
}

/**
 * A raw HTTP/1.1 server on `127.0.0.1:0`, one daemon thread per connection. Hand-rolled rather than a Ktor
 * server on purpose: the ticket names only the CIO *engine*, and raw bytes are the point — a stalled body and a
 * mid-body abort are not expressible through a framework's response builder.
 */
internal class WireServer(
    private val script: (attempt: Int) -> WireReply,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val firstReplyFlushed = CountDownLatch(1)
    private val nextAttempt = AtomicInteger()

    val port: Int get() = server.localPort
    val recordedRequests: List<RecordedRequest> get() = synchronized(requests) { requests.toList() }

    init {
        Thread(::acceptLoop, "wire-server").apply {
            isDaemon = true
            start()
        }
    }

    fun awaitFirstReply(timeout: Duration): Boolean = firstReplyFlushed.await(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    override fun close() {
        server.close()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket =
                try {
                    server.accept()
                } catch (_: SocketException) {
                    return
                }
            val attempt = nextAttempt.getAndIncrement()
            Thread(
                {
                    try {
                        handle(socket, attempt)
                    } catch (_: IOException) {
                        // The peer went away first; the test asserts on what did arrive.
                    } finally {
                        runCatching { socket.close() }
                    }
                },
                "wire-server-$attempt",
            ).apply { isDaemon = true }.start()
        }
    }

    private fun handle(
        socket: Socket,
        attempt: Int,
    ) {
        requests += readRequest(socket.getInputStream())
        val reply = script(attempt)
        val out = socket.getOutputStream()
        out.write(reply.toBytes())
        out.flush()
        firstReplyFlushed.countDown()
        when (reply) {
            is WireReply.Stalled -> Thread.sleep(REPLY_STALL_MS)
            is WireReply.Aborted -> socket.setSoLinger(true, 0)
            is WireReply.Complete -> Unit
        }
    }
}

private fun reasonPhrase(status: Int): String =
    when (status) {
        200 -> "OK"
        429 -> "Too Many Requests"
        503 -> "Service Unavailable"
        else -> "Status"
    }

/** How long a stalled reply holds the socket open, comfortably past every test's timeout. */
private const val REPLY_STALL_MS = 30_000L

private class RawReply(
    val status: Int,
    val declaredLength: Int,
    val body: ByteArray,
    val headers: Map<String, String>,
)

private fun WireReply.toBytes(): ByteArray {
    val raw =
        when (this) {
            is WireReply.Complete -> {
                val bytes = body.encodeToByteArray()
                RawReply(status, bytes.size, bytes, headers)
            }
            is WireReply.Stalled -> RawReply(status, declaredLength, partialBody.encodeToByteArray(), emptyMap())
            is WireReply.Aborted -> RawReply(200, declaredLength, partialBody.encodeToByteArray(), emptyMap())
        }
    val head =
        buildString {
            append("HTTP/1.1 ${raw.status} ${reasonPhrase(raw.status)}\r\n")
            raw.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Content-Length: ${raw.declaredLength}\r\n")
            append("Connection: close\r\n\r\n")
        }
    return head.toByteArray(Charsets.ISO_8859_1) + raw.body
}

/** Reads one request: the head up to the blank line, then `Content-Length` bytes of body. */
private fun readRequest(input: InputStream): RecordedRequest {
    val buffer = ByteArrayOutputStream()
    val window = ArrayDeque<Int>()
    while (true) {
        val byte = input.read()
        if (byte == -1) break
        buffer.write(byte)
        window.addLast(byte)
        if (window.size > 4) window.removeFirst()
        if (window.size == 4 && window.toList() == listOf(CR, LF, CR, LF)) break
    }
    val head = buffer.toByteArray().toString(Charsets.ISO_8859_1)
    val contentLength =
        Regex("(?i)content-length:\\s*(\\d+)")
            .find(head)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: 0
    val body = input.readNBytes(contentLength)
    return RecordedRequest(head, body)
}

private const val CR = 13
private const val LF = 10

private fun client(
    port: Int,
    timeout: Duration = 10.seconds,
    retry: RetryPolicy = RetryPolicy(),
): SystemOneClient =
    createClient(
        SystemOneConfig(
            apiKey = "test-key",
            baseUrl = "http://127.0.0.1:$port",
            timeout = timeout,
            retry = retry,
            engine = CIO.create(),
        ),
    )
