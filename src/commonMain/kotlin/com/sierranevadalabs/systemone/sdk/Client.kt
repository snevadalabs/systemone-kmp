package com.sierranevadalabs.systemone.sdk

import com.sierranevadalabs.systemone.sdk.errors.APIResponseValidationError
import com.sierranevadalabs.systemone.sdk.errors.asPublicError
import com.sierranevadalabs.systemone.sdk.errors.errorFor
import com.sierranevadalabs.systemone.sdk.errors.parseBody
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The System One client.
 *
 * Build one with the [SystemOneClient] factory function. Every call is `suspend`; there is no blocking entry
 * point. The client is [AutoCloseable] and
 * closes the HTTP engine it created — never one it was handed — so closing twice is safe.
 *
 * An implementation holds the API key and never prints it: not in a generated `toString()`, not in any
 * `toString()` we write, not in a log line, and not in an exception.
 *
 * Every property below is the value that actually took effect, resolved once when the client was built as
 * `explicit → provider default`. The API key is deliberately absent: no accessor exposes it.
 */
public interface SystemOneClient : AutoCloseable {
    /** The model catalogue, reached as `client.models.list()`. */
    public val models: Models

    /** The effective API root every request is sent to: explicit, then the provider's own origin. */
    public val baseUrl: String

    /** The effective model a call uses when it names none: explicit, then the provider's own model. */
    public val defaultModel: String

    /** The effective per-attempt request timeout a call uses when it overrides nothing: explicit, else 10 seconds. */
    public val timeout: Duration

    /** The effective retry policy a call uses when it passes none. */
    public val retry: RetryPolicy

    /** The effective log level: explicit, else [LogLevel.Off]. */
    public val logLevel: LogLevel

    /** The effective headers sent on every request, before the caller's per-call headers and the SDK's own. */
    public val defaultHeaders: Map<String, String>

    /**
     * Asks one or more typed questions of [state] in a single call.
     *
     * @param state the content to evaluate: a string, object, or array.
     * @param questions the questions. At least one is required, and a `score` question needs two or more
     *   levels; both are checked locally before the request, with an [IllegalArgumentException].
     * @param model the model to use, or `null` for the client's configured default.
     * @param timeout the per-attempt request timeout, or `null` for the client's configured default.
     * @param retry the retry policy for this call, or `null` for the client's configured default.
     * @param headers extra headers for this call, or `null` for none. They merge over [defaultHeaders] and
     *   under the SDK's own headers, which always win, so a caller cannot replace `Authorization`, `Accept`,
     *   `Content-Type` or the identity headers.
     * @throws com.sierranevadalabs.systemone.sdk.errors.SystemOneError for a failed call; the concrete class matches the
     *   siblings' names.
     */
    public suspend fun systemOne(
        state: JsonElement,
        vararg questions: Question<*>,
        model: String? = null,
        timeout: Duration? = null,
        retry: RetryPolicy? = null,
        headers: Map<String, String>? = null,
    ): SystemOneResponse
}

/**
 * [SystemOneClient.systemOne] with plain-text [state], the convenience form of the same call.
 *
 * @param state the content to evaluate.
 * @param questions the questions; at least one.
 * @param headers extra headers for this call; exactly as on [SystemOneClient.systemOne].
 */
public suspend fun SystemOneClient.systemOne(
    state: String,
    vararg questions: Question<*>,
    model: String? = null,
    timeout: Duration? = null,
    retry: RetryPolicy? = null,
    headers: Map<String, String>? = null,
): SystemOneResponse =
    systemOne(
        JsonPrimitive(state),
        *questions,
        model = model,
        timeout = timeout,
        retry = retry,
        headers = headers,
    )

/**
 * Builds a client from [config], resolving anything left `null` against the provider's own values.
 */
public fun SystemOneClient(config: SystemOneConfig): SystemOneClient = createClient(config)

internal fun createClient(
    config: SystemOneConfig,
    engineFactory: () -> HttpClientEngine = ::createDefaultEngine,
    random: () -> Double = { Random.nextDouble() },
    sleeper: suspend (Long) -> Unit = { delay(it) },
    timeSource: TimeSource = TimeSource.Monotonic,
    sink: (String) -> Unit = ::println,
): SystemOneClient {
    val resolved = ResolvedConfig(config)
    val transport =
        createTransport(
            apiKey = resolved.apiKey,
            baseUrl = resolved.baseUrl,
            provider = resolved.provider,
            engine = config.engine,
            defaultHeaders = resolved.defaultHeaders,
            timeout = resolved.timeout,
            retryPolicy = resolved.retry,
            log = logSink(LOG_PREFIX, resolved.logLevel, sink),
            httpClientConfig = config.httpClientConfig,
            engineFactory = engineFactory,
            random = random,
            sleeper = sleeper,
            timeSource = timeSource,
        )
    return SystemOneClientImpl(transport, resolved)
}

internal class SystemOneClientImpl(
    private val transport: Transport,
    resolved: ResolvedConfig,
) : SystemOneClient {
    override val baseUrl: String = resolved.baseUrl

    override val defaultModel: String = resolved.defaultModel

    override val timeout: Duration = resolved.timeout

    override val retry: RetryPolicy = resolved.retry

    override val logLevel: LogLevel = resolved.logLevel

    override val defaultHeaders: Map<String, String> = resolved.defaultHeaders

    private val systemOnePath: String = resolved.systemOnePath

    private val modelsPath: String = resolved.modelsPath

    // The resource gets a closure, not the transport, so it never has the API key in reach.
    override val models: Models =
        ModelsApi { timeout, retry, headers ->
            request(HttpMethod.Get, modelsPath, timeout = timeout, retry = retry, headers = headers)
        }

    override suspend fun systemOne(
        state: JsonElement,
        vararg questions: Question<*>,
        model: String?,
        timeout: Duration?,
        retry: RetryPolicy?,
        headers: Map<String, String>?,
    ): SystemOneResponse {
        validateQuestions(questions.toList())
        val body =
            buildJsonObject {
                put("state", state)
                put("model", model ?: defaultModel)
                put("questions", JsonObject(questions.associate { it.id to it.toWireJson() }))
            }.toString()
        return decodeSystemOneResponse(
            request(HttpMethod.Post, systemOnePath, body, headers = headers, timeout = timeout, retry = retry),
        )
    }

    override fun close() {
        transport.close()
    }

    private suspend fun request(
        method: HttpMethod,
        path: String,
        body: String? = null,
        timeout: Duration? = null,
        retry: RetryPolicy? = null,
        headers: Map<String, String>? = null,
    ): TransportResponse {
        val response =
            try {
                transport.request(
                    method,
                    path,
                    body = body,
                    headers = headers.orEmpty(),
                    timeout = timeout,
                    policy = retry,
                )
            } catch (failure: TransportException) {
                throw failure.asPublicError()
            }
        if (response.status !in 200..299) throw errorFor(response)
        return response
    }
}

/** Reads a Wire System One payload into the response surface, mapping a malformed body to a validation error. */
internal fun decodeSystemOneResponse(response: TransportResponse): SystemOneResponse {
    val payload =
        parseBody(response.body) as? JsonObject
            ?: throw invalidResponse(response, "expected a JSON object response body", null)
    val answersBody =
        payload["answers"] as? JsonObject
            ?: throw invalidResponse(response, "answers: expected an object field 'answers'", "answers")
    val answers =
        try {
            decodeAnswers(answersBody)
        } catch (failure: ResponseValidationException) {
            throw invalidResponse(response, failure.message ?: failure.fieldPath, failure.fieldPath)
        }
    return SystemOneResponse(
        answers = answers,
        model = (payload["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        usage = (payload["usage"] as? JsonObject)?.let(::decodeUsage),
        requestId = response.requestId,
        status = response.status,
        headers = response.headers,
    )
}

private fun invalidResponse(
    response: TransportResponse,
    message: String,
    fieldPath: String?,
): APIResponseValidationError =
    APIResponseValidationError(
        fieldPath = fieldPath,
        status = response.status,
        body = parseBody(response.body),
        requestId = response.requestId,
        message = message,
    )

private fun decodeUsage(payload: JsonObject): Usage =
    Usage(
        inputTokens = payload.intOrNull("input_tokens"),
        outputTokens = payload.intOrNull("output_tokens"),
    )

private fun JsonObject.intOrNull(name: String): Int? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
