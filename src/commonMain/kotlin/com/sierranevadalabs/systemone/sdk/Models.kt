package com.sierranevadalabs.systemone.sdk

import com.sierranevadalabs.systemone.sdk.errors.APIResponseValidationError
import com.sierranevadalabs.systemone.sdk.errors.parseBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration

/** The model catalogue: what the API can be asked with. Reached as `client.models`. */
public interface Models {
    /**
     * Returns every model the API offers, from `GET /v1/models`.
     *
     * @param timeout the per-attempt request timeout for this call, or `null` for the client's configured
     *   default. Exactly as on [SystemOneClient.systemOne].
     * @param retry the retry policy for this call, or `null` for the client's configured default. Exactly as
     *   on [SystemOneClient.systemOne].
     * @param headers extra headers for this call, or `null` for none. Exactly as on
     *   [SystemOneClient.systemOne].
     * @throws com.sierranevadalabs.systemone.sdk.errors.APIResponseValidationError if a 200 body is not
     *   `{ models: [...] }` with a string `name` on every entry — a server-side contract break, not a caller
     *   error.
     * @throws com.sierranevadalabs.systemone.sdk.errors.SystemOneError for a failed call. Retry and the status-to-error
     *   mapping have already been applied by the client.
     */
    public suspend fun list(
        timeout: Duration? = null,
        retry: RetryPolicy? = null,
        headers: Map<String, String>? = null,
    ): List<ModelCard>
}

/**
 * One entry in the model catalogue. Fields the server adds later are ignored.
 *
 * @property name the model id to pass as `model`.
 * @property description a human-readable summary, when the server sends one.
 * @property releaseDate the release date as the server writes it, verbatim.
 */
public data class ModelCard(
    public val name: String,
    public val description: String? = null,
    public val releaseDate: String? = null,
)

/**
 * The token counters the server reports for one call. Every field is optional: the API omits `usage`
 * entirely on some responses and has never sent the OpenAPI schema's `billing_units`.
 *
 * @property inputTokens tokens the model read.
 * @property outputTokens tokens the model produced.
 */
public data class Usage(
    public val inputTokens: Int? = null,
    public val outputTokens: Int? = null,
)

/**
 * The `models` resource.
 *
 * It holds a closure over the client's request path rather than the transport itself, so the resource never
 * holds — and cannot leak — the API key. Errors and retries are the client's, already applied when the
 * closure returns.
 */
internal class ModelsApi(
    private val fetch: suspend (Duration?, RetryPolicy?, Map<String, String>?) -> TransportResponse,
) : Models {
    override suspend fun list(
        timeout: Duration?,
        retry: RetryPolicy?,
        headers: Map<String, String>?,
    ): List<ModelCard> {
        val response = fetch(timeout, retry, headers)
        val payload = parseBody(response.body) as? JsonObject
        val models = payload?.get("models") as? JsonArray
        if (models == null) {
            throw APIResponseValidationError(
                fieldPath = "models",
                status = response.status,
                body = parseBody(response.body),
                requestId = response.requestId,
                message = "GET /v1/models: expected { models: [...] }",
            )
        }
        return models.mapIndexed { index, element -> decodeModelCard(element, index, response) }
    }
}

private fun decodeModelCard(
    element: JsonElement,
    index: Int,
    response: TransportResponse,
): ModelCard {
    val card = element as? JsonObject
    val name = (card?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (card == null || name == null) {
        throw APIResponseValidationError(
            fieldPath = "models.$index.name",
            status = response.status,
            body = parseBody(response.body),
            requestId = response.requestId,
            message = "GET /v1/models: models.$index.name: expected a string field 'name'",
        )
    }
    return ModelCard(
        name = name,
        description = card.stringFieldOrNull("description"),
        releaseDate = card.stringFieldOrNull("release_date"),
    )
}

private fun JsonObject.stringFieldOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
