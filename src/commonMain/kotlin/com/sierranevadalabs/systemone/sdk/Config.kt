package com.sierranevadalabs.systemone.sdk

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The route that answers typed questions, relative to a provider's [SystemOneProvider.pathPrefix]. */
internal const val SYSTEM_ONE_PATH: String = "/v1/systemone"

/** The route that lists models, relative to a provider's [SystemOneProvider.pathPrefix]. */
internal const val MODELS_PATH: String = "/v1/models"

internal val DEFAULT_TIMEOUT: Duration = 10.seconds

/**
 * Everything a caller may configure. A plain class, deliberately **not** a `data class`: a generated
 * `toString()` would print [apiKey] verbatim, so the explicit [toString] below omits it — and
 * [defaultHeaders], which can carry credentials too.
 *
 * The SDK reads no process environment. Every value is either configured here or supplied by [provider], so a
 * client is a pure function of its config.
 *
 * @property apiKey the API key, sent as `Authorization: Bearer <key>`. Required; there is no environment
 *   fallback.
 * @property baseUrl the API origin, or `null` for the provider's own.
 * @property defaultModel the model a call uses when it names none, or `null` for the provider's own.
 * @property timeout the per-attempt request timeout. `null` means 10 seconds.
 * @property defaultHeaders headers added to every request, before the caller's per-call headers and before the
 *   headers the SDK owns.
 * @property retry the default retry policy. A call may override it.
 * @property logLevel how much the SDK logs. `null` falls back to [LogLevel.Off]; see [LogLevel] for what each
 *   level emits.
 * @property engine the HTTP engine to use. `null` creates the platform default (OkHttp on JVM and Android,
 *   Darwin on Apple, CIO on Linux), which the client then owns and closes.
 * @property httpClientConfig extra Ktor client configuration. Runs before the SDK installs its own plugins, so
 *   `Logging` or custom auth can be added but the retry and timeout ordering cannot be broken.
 * @property provider the System One deployment to talk to. [SystemOneProvider.TypeSafe] by default. It supplies
 *   the origin, the route prefix, the default model, and the fingerprint headers; see [SystemOneProvider].
 */
public class SystemOneConfig(
    public val apiKey: String,
    public val baseUrl: String? = null,
    public val defaultModel: String? = null,
    public val timeout: Duration? = null,
    public val defaultHeaders: Map<String, String> = emptyMap(),
    public val retry: RetryPolicy = RetryPolicy(),
    public val logLevel: LogLevel? = null,
    public val engine: HttpClientEngine? = null,
    public val httpClientConfig: HttpClientConfig<*>.() -> Unit = {},
    public val provider: SystemOneProvider = SystemOneProvider.TypeSafe,
) {
    /** The config as text. [apiKey] is deliberately absent, so a config cannot leak the key into a log. */
    override fun toString(): String = "SystemOneConfig(baseUrl=$baseUrl, defaultModel=$defaultModel, timeout=$timeout, retry=$retry)"
}

/**
 * [SystemOneConfig] with every value resolved: the explicit value, then the provider's own. Internal, so the
 * resolution rules are testable without the public surface growing a seam for it.
 */
internal class ResolvedConfig(
    config: SystemOneConfig,
) {
    val provider: SystemOneProvider = config.provider

    val apiKey: String = config.apiKey
    val baseUrl: String = config.baseUrl ?: provider.baseUrl
    val defaultModel: String = config.defaultModel ?: provider.defaultModel
    val timeout: Duration = config.timeout ?: DEFAULT_TIMEOUT
    val logLevel: LogLevel = config.logLevel ?: DEFAULT_LOG_LEVEL

    // No fallback reaches either of these, so the resolved value is the configured one. They live here so every
    // effective setting on the client comes from one object.
    val retry: RetryPolicy = config.retry
    val defaultHeaders: Map<String, String> = config.defaultHeaders.toMap()

    /** The route `systemOne` posts to for this provider, prefix included. */
    val systemOnePath: String = provider.pathPrefix + SYSTEM_ONE_PATH

    /** The route the model catalogue is read from for this provider, prefix included. */
    val modelsPath: String = provider.pathPrefix + MODELS_PATH

    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(timeout > Duration.ZERO) { "timeout must be positive, was $timeout" }
    }
}
