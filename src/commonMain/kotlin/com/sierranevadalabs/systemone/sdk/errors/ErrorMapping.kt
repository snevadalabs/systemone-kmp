package com.sierranevadalabs.systemone.sdk.errors

import com.sierranevadalabs.systemone.sdk.TransportException
import com.sierranevadalabs.systemone.sdk.TransportResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The message length cap both siblings apply to a served error body. */
private const val MAX_MESSAGE_LENGTH = 200

/** The response body parsed as JSON, or `null` when it is empty or is not JSON. */
internal fun parseBody(raw: String): JsonElement? =
    raw.trim().takeIf { it.isNotEmpty() }?.let { body -> runCatching { Json.parseToJsonElement(body) }.getOrNull() }

/**
 * The explanation inside an error body: `{error: string|{message}}`, `{message: string}`,
 * `{detail: string|{message}|[{loc, msg}]}`, a bare JSON string, or the raw text when the body is not JSON.
 * The result is capped at [MAX_MESSAGE_LENGTH] characters with a trailing ellipsis.
 */
internal fun extractErrorMessage(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
    val extracted = (parsed?.let(::messageFrom) ?: trimmed).trim()
    return if (extracted.length <= MAX_MESSAGE_LENGTH) extracted else extracted.take(MAX_MESSAGE_LENGTH) + "…"
}

private fun messageFrom(element: JsonElement): String? =
    when (element) {
        is JsonObject -> errorField(element) ?: stringField(element, "message") ?: detailField(element)
        is JsonPrimitive -> element.takeIf { it.isString }?.content
        else -> null
    }

private fun errorField(body: JsonObject): String? =
    when (val error = body["error"]) {
        is JsonPrimitive -> error.takeIf { it.isString }?.content
        is JsonObject -> stringField(error, "message")
        else -> null
    }

private fun detailField(body: JsonObject): String? =
    when (val detail = body["detail"]) {
        is JsonPrimitive -> detail.takeIf { it.isString }?.content
        is JsonObject -> stringField(detail, "message")
        is JsonArray -> detail.mapNotNull(::validationEntry).takeIf { it.isNotEmpty() }?.joinToString("; ")
        else -> null
    }

/** One FastAPI validation entry: `loc` with any leading `body` dropped, dotted, then `: msg`. */
private fun validationEntry(element: JsonElement): String? {
    val entry = element as? JsonObject ?: return null
    val message = stringField(entry, "msg") ?: return null
    val path = (entry["loc"] as? JsonArray).orEmpty().mapNotNull { it.asStringOrNull() }
    val dotted = path.dropWhile { it == "body" }.joinToString(".")
    return if (dotted.isEmpty()) message else "$dotted: $message"
}

private fun stringField(
    body: JsonObject,
    name: String,
): String? = body[name].asStringOrNull()

private fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The one status→class mapping in the SDK. Anything that is not `2xx` goes through here, and nothing else
 * decides which error class a response becomes.
 */
internal fun errorFor(
    status: Int,
    body: String,
    requestId: String?,
    retryAfterMs: Long?,
): APIError {
    val parsed = parseBody(body)
    val message = extractErrorMessage(body).ifEmpty { "HTTP $status" }
    return when (status) {
        400 -> BadRequestError(status, parsed, requestId, message)
        401 -> AuthenticationError(status, parsed, requestId, message)
        403 -> PermissionDeniedError(status, parsed, requestId, message)
        404 -> NotFoundError(status, parsed, requestId, message)
        422 -> UnprocessableEntityError(status, parsed, requestId, message)
        429 -> RateLimitError(retryAfterMs, status, parsed, requestId, message)
        in 500..599 -> InternalServerError(status, parsed, requestId, message)
        else -> APIError(status, parsed, requestId, message)
    }
}

/** [errorFor] applied to a buffered response. */
internal fun errorFor(response: TransportResponse): APIError =
    errorFor(response.status, response.body, response.requestId, response.retryAfterMs)

/** The transport's two failures, translated at the single boundary both resources share. */
internal fun TransportException.asPublicError(): SystemOneError =
    when (this) {
        // The original engine failure is the cause, not this internal wrapper, so a caller's stack trace and
        // `cause` chain stay inside their own stack.
        is TransportException.Timeout ->
            APITimeoutError(message ?: "The request timed out", cause)
        is TransportException.Connection ->
            APIConnectionError(message ?: "The request failed before a response arrived", cause)
    }
