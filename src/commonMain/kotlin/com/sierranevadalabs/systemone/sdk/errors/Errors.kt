package com.sierranevadalabs.systemone.sdk.errors

import kotlinx.serialization.json.JsonElement

/**
 * The root of every failure this SDK raises.
 *
 * Catching [SystemOneError] catches everything the SDK itself throws. A caller's own abort is a
 * [kotlinx.coroutines.CancellationException], which is deliberately never wrapped, so it is not a [SystemOneError].
 *
 * The tree is for `catch`, not for construction: every constructor is internal, so matching the siblings by
 * name is the only thing the classes owe a caller.
 */
public open class SystemOneError internal constructor(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A non-2xx response from the API.
 *
 * The class names match the official Python and JavaScript SDKs, so an existing `catch` block ports by name.
 *
 * @property status the HTTP status code.
 * @property body the response body parsed as JSON, or `null` when the body was empty or was not JSON. The
 *   human-readable explanation is in [message].
 * @property requestId the server's `x-typesafe-request-id`, when it sent one.
 */
public open class APIError internal constructor(
    public val status: Int,
    public val body: JsonElement?,
    public val requestId: String?,
    message: String,
    cause: Throwable? = null,
) : SystemOneError(message, cause)

/** A `400`: the request was malformed. */
public class BadRequestError internal constructor(
    status: Int,
    body: JsonElement?,
    requestId: String?,
    message: String,
    cause: Throwable? = null,
) : APIError(status, body, requestId, message, cause)

/** A `401`: the API key is missing, malformed, or rejected. */
public class AuthenticationError internal constructor(
    status: Int,
    body: JsonElement?,
    requestId: String?,
    message: String,
    cause: Throwable? = null,
) : APIError(status, body, requestId, message, cause)

/** A `403`: the key is valid but is not allowed to do this. */
public class PermissionDeniedError internal constructor(
    status: Int,
    body: JsonElement?,
    requestId: String?,
    message: String,
    cause: Throwable? = null,
) : APIError(status, body, requestId, message, cause)

/** A `404`: the endpoint or the model does not exist. */
public class NotFoundError internal constructor(
    status: Int,
    body: JsonElement?,
    requestId: String?,
    message: String,
    cause: Throwable? = null,
) : APIError(status, body, requestId, message, cause)

/** A `422`: the request was well-formed but the API rejected its content. */
public class UnprocessableEntityError internal constructor(
    status: Int,
    body: JsonElement?,
    requestId: String?,
    message: String,
    cause: Throwable? = null,
) : APIError(status, body, requestId, message, cause)

/** A `429`: too many requests. */
public class RateLimitError internal constructor(
    /**
     * The delay the server asked for, in milliseconds, from `retry-after-ms` or `Retry-After`. `null` when the
     * server sent neither, or when the policy's `respectRetryAfter` is off.
     */
    public val retryAfterMs: Long?,
    status: Int,
    body: JsonElement?,
    requestId: String?,
    message: String,
    cause: Throwable? = null,
) : APIError(status, body, requestId, message, cause)

/** A `5xx`: the API itself failed. */
public class InternalServerError internal constructor(
    status: Int,
    body: JsonElement?,
    requestId: String?,
    message: String,
    cause: Throwable? = null,
) : APIError(status, body, requestId, message, cause)

/**
 * The response body could not be decoded — a `200` whose payload does not match the wire contract, not a
 * client-side programming error.
 *
 * @property fieldPath the dotted path of the offending field (`answers.urgent.noul`), or `null` when the whole
 *   body was unusable. Python's SDK names it `field_path`.
 */
public class APIResponseValidationError internal constructor(
    public val fieldPath: String?,
    status: Int,
    body: JsonElement?,
    requestId: String?,
    message: String,
    cause: Throwable? = null,
) : APIError(status, body, requestId, message, cause)

/** The request never reached a response: DNS, TLS, a refused connection, a dropped socket. */
public open class APIConnectionError internal constructor(
    message: String,
    cause: Throwable? = null,
) : SystemOneError(message, cause)

/** The request exceeded its timeout. A subtype of [APIConnectionError], matching the JS SDK's hierarchy. */
public class APITimeoutError internal constructor(
    message: String,
    cause: Throwable? = null,
) : APIConnectionError(message, cause)
