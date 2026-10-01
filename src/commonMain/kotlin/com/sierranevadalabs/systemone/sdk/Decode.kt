package com.sierranevadalabs.systemone.sdk

import com.sierranevadalabs.systemone.sdk.errors.APIResponseValidationError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A wire value that does not match the shape the SDK expects — a missing field, or a field of the wrong JSON
 * type. Carries the dotted path of the offending field, the way Python's `field_path` does.
 *
 * `internal` because the public error tree in `com.sierranevadalabs.systemone.sdk.errors` is the surface; the
 * decode entry maps this into `APIResponseValidationError`.
 */
internal class ResponseValidationException(
    val fieldPath: String,
    detail: String,
) : Exception("$fieldPath: $detail")

/**
 * One JSON value and the dotted path it sits at, so every read names only the field and the path is built by
 * the reader. A read states whether the field is required (it raises, naming its path) or optional (a missing
 * or wrong-typed field reads as `null`).
 */
internal class WireValue(
    val element: JsonElement,
    val path: String,
) {
    /** The value under [name], at this path extended by it. Missing keys read as `JsonNull`. */
    fun child(name: String): WireValue = WireValue(fetch(name) ?: JsonNull, childPath(name))

    /** The string under [name], or a failure naming the field. */
    fun requiredString(name: String): String =
        optionalString(name) ?: throw ResponseValidationException(childPath(name), "expected a string field '$name'")

    /** The string under [name], or `null` when it is missing or not a string. */
    fun optionalString(name: String): String? = (fetch(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The number under [name], or a failure naming the field. A stringified number is not a number. */
    fun requiredDouble(name: String): Double =
        fetch(name)?.doubleOrNull() ?: throw ResponseValidationException(childPath(name), "expected a numeric field '$name'")

    /** The integer under [name], or `null` when it is missing, not a number, or outside `Int`. */
    fun optionalInt(name: String): Int? = (fetch(name) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

    /** The object under [name] as a cursor at its own path, or a failure naming the field. */
    fun requiredObject(name: String): WireValue = WireValue(objectAt(name), childPath(name))

    /** The object under [name] as a cursor at its own path, or `null` when it is missing or not an object. */
    fun optionalObject(name: String): WireValue? = (fetch(name) as? JsonObject)?.let { WireValue(it, childPath(name)) }

    /** The array under [name] as one cursor per element, or `null` when it is missing or not an array. */
    fun optionalArray(name: String): List<WireValue>? {
        val array = fetch(name) as? JsonArray ?: return null
        val base = childPath(name)
        return array.mapIndexed { index, item -> WireValue(item, append(base, index.toString())) }
    }

    /** The object under [name] as string-keyed numbers, or a failure naming the field or the value's key. */
    fun stringKeyedDoubles(name: String): Map<String, Double> {
        val at = childPath(name)
        return objectAt(name).mapValues { (key, value) -> value.doubleAt(append(at, key)) }
    }

    /** The object under [name] as integer-keyed numbers; the wire keys are stringified ordinals. */
    fun intKeyedDoubles(name: String): Map<Int, Double> {
        val at = childPath(name)
        return objectAt(name).entries.associate { (key, value) ->
            key.scoreOrdinal(append(at, key)) to value.doubleAt(append(at, key))
        }
    }

    /** The object under [name] as integer-keyed raw elements, for a `score` question's legend. */
    fun intKeyedElements(name: String): Map<Int, JsonElement> {
        val at = childPath(name)
        return objectAt(name).entries.associate { (key, value) -> key.scoreOrdinal(append(at, key)) to value }
    }

    private fun objectAt(name: String): JsonObject =
        fetch(name) as? JsonObject
            ?: throw ResponseValidationException(childPath(name), "expected an object field '$name'")

    /** The value under [name], or `null` when this cursor is not an object or the key is absent. */
    private fun fetch(name: String): JsonElement? = (element as? JsonObject)?.get(name)

    private fun childPath(segment: String): String = append(path, segment)

    private fun append(
        base: String,
        segment: String,
    ): String = if (base.isEmpty()) segment else "$base.$segment"
}

private fun String.scoreOrdinal(path: String): Int =
    toIntOrNull() ?: throw ResponseValidationException(path, "expected an integer score ordinal")

private fun JsonElement.doubleOrNull(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

private fun JsonElement.doubleAt(path: String): Double = doubleOrNull() ?: throw ResponseValidationException(path, "expected a number")

/** The response body parsed as JSON, or `null` when it is empty or is not JSON. */
internal fun parseBody(raw: String): JsonElement? =
    raw.trim().takeIf { it.isNotEmpty() }?.let { body -> runCatching { Json.parseToJsonElement(body) }.getOrNull() }

/**
 * Runs [block] over the response body as a JSON object, and turns any reader failure into the public
 * validation error. A body that is not a JSON object fails with [bodyFieldPath] and [bodyMessage]; every
 * reader failure keeps its dotted path, with [prefix] in front of the message when one is set.
 */
internal fun <T> decodeBody(
    response: TransportResponse,
    bodyFieldPath: String? = null,
    bodyMessage: String = "expected a JSON object response body",
    prefix: String? = null,
    block: (WireValue) -> T,
): T {
    val body =
        parseBody(response.body) as? JsonObject
            ?: throw validationError(response, bodyFieldPath, bodyMessage)
    return try {
        block(WireValue(body, ""))
    } catch (failure: ResponseValidationException) {
        val message = if (prefix == null) failure.message else "$prefix: ${failure.message}"
        throw validationError(response, failure.fieldPath, message ?: failure.fieldPath)
    }
}

/** The one place a malformed response becomes the public validation error. */
internal fun validationError(
    response: TransportResponse,
    fieldPath: String?,
    message: String,
): APIResponseValidationError =
    APIResponseValidationError(
        fieldPath = fieldPath,
        status = response.status,
        body = parseBody(response.body),
        requestId = response.requestId,
        message = message,
    )
