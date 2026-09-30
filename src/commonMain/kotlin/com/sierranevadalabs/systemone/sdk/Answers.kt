package com.sierranevadalabs.systemone.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One answer from the System One API, discriminated by the question's primitive.
 *
 * This is the raw, untyped view: every value in [SystemOneResponse.answers] is an [Answer], and an exhaustive
 * `when` over it keeps working when the server adds a primitive, because a new variant has to be added here
 * first. Prefer the typed accessors on [SystemOneResponse] when the primitive is known.
 */
public sealed interface Answer

/**
 * The answer to a [NoulQuestion].
 *
 * @property noul the model's probability that the statement in the question is true, in `0..1`. `0.5` means yes
 *   and no are equally likely; it is not a medium position on a scale.
 */
public data class NoulAnswer(
    public val noul: Double,
) : Answer

/**
 * The answer to a [ChoiceQuestion].
 *
 * @property choice the option the model picked, one of the question's option keys.
 * @property probabilities per-option probabilities, keyed by the question's option keys.
 * @property confidence the model's confidence in [choice], in `0..1`.
 */
public data class ChoiceAnswer(
    public val choice: String,
    public val probabilities: Map<String, Double>,
    public val confidence: Double,
) : Answer

/**
 * The answer to a [ScoreQuestion].
 *
 * @property score the position the model picked. It is a number, not a level label, and it may fall between
 *   two adjacent levels.
 * @property legend the question's levels echoed back by ordinal, so the position can be read against the
 *   labels that defined it. Values are the levels as sent, so they may be structured rather than plain text.
 * @property probabilities per-level probabilities, keyed by integer ordinal.
 * @property confidence the model's confidence in [score], in `0..1`.
 */
public data class ScoreAnswer(
    public val score: Double,
    public val legend: Map<Int, JsonElement>,
    public val probabilities: Map<Int, Double>,
    public val confidence: Double,
) : Answer

/**
 * A primitive this SDK version does not know, kept so that a newer server degrades instead of failing.
 *
 * A typed accessor never returns this variant — a [Question] cannot ask for it. It surfaces only through
 * [SystemOneResponse.answers], so read that map if the server may be newer than this SDK.
 *
 * When a new primitive is modelled, [Answer] gains a variant, and every caller's exhaustive `when` over the
 * hierarchy stops compiling. That is the intended failure, and it makes modelling a new primitive a
 * source-breaking change: it ships in a minor or major release, never in a patch.
 *
 * @property type the server's `type` discriminator, verbatim.
 * @property raw the answer body, unparsed.
 */
public data class UnknownAnswer(
    public val type: String,
    public val raw: JsonElement,
) : Answer

/**
 * A known answer that does not match its own primitive's shape — a missing `noul`, a `choice` with no
 * `probabilities`. Carries the dotted path of the offending field, the way Python's `field_path` does.
 *
 * `internal` because the public error tree in `com.sierranevadalabs.systemone.sdk.errors` is ticket 12's surface;
 * it maps this into the response-validation error once the tree exists.
 */
internal class ResponseValidationException(
    val fieldPath: String,
    detail: String,
) : Exception("$fieldPath: $detail")

/** Builds a dotted field path. One helper, so every field path in the module reads the same way. */
internal fun fieldPath(vararg segments: String): String = segments.joinToString(".")

/**
 * Decodes the `answers` object of a System One response.
 *
 * Decoding rules, all pinned by tests:
 * - an unknown `type` becomes an [UnknownAnswer] with the raw payload intact, never an error;
 * - unknown extra fields on a known answer are ignored;
 * - `score`'s `legend` and `probabilities` arrive keyed by stringified ordinals and surface keyed by integers;
 * - a malformed known answer fails with a [ResponseValidationException] naming the exact field path.
 */
internal fun decodeAnswers(answers: JsonObject): Map<String, Answer> =
    answers.entries.associate { (id, payload) -> id to decodeAnswer(id, payload) }

private fun decodeAnswer(
    id: String,
    payload: JsonElement,
): Answer {
    val at = fieldPath("answers", id)
    val body = payload as? JsonObject ?: throw ResponseValidationException(at, "expected an answer object")
    val type = body.stringAt("type", fieldPath(at, "type"))
    return when (type) {
        "noul" -> NoulAnswer(body.doubleAt("noul", fieldPath(at, "noul")))
        "choice" ->
            ChoiceAnswer(
                choice = body.stringAt("choice", fieldPath(at, "choice")),
                probabilities = body.stringKeyedDoubles("probabilities", fieldPath(at, "probabilities")),
                confidence = body.doubleAt("confidence", fieldPath(at, "confidence")),
            )
        "score" ->
            ScoreAnswer(
                score = body.doubleAt("score", fieldPath(at, "score")),
                legend = body.intKeyedElements("legend", fieldPath(at, "legend")),
                probabilities = body.intKeyedDoubles("probabilities", fieldPath(at, "probabilities")),
                confidence = body.doubleAt("confidence", fieldPath(at, "confidence")),
            )
        else -> UnknownAnswer(type, body)
    }
}

private fun JsonObject.stringAt(
    name: String,
    path: String,
): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw ResponseValidationException(path, "expected a string field '$name'")

private fun JsonObject.doubleAt(
    name: String,
    path: String,
): Double {
    val value = this[name] as? JsonPrimitive
    return value?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        ?: throw ResponseValidationException(path, "expected a numeric field '$name'")
}

private fun JsonObject.stringKeyedDoubles(
    name: String,
    path: String,
): Map<String, Double> = objectAt(name, path).mapValues { (key, value) -> value.asDouble(fieldPath(path, key)) }

private fun JsonObject.intKeyedDoubles(
    name: String,
    path: String,
): Map<Int, Double> =
    objectAt(name, path).entries.associate { (key, value) ->
        key.asScoreOrdinal(path) to value.asDouble(fieldPath(path, key))
    }

private fun JsonObject.intKeyedElements(
    name: String,
    path: String,
): Map<Int, JsonElement> = objectAt(name, path).entries.associate { (key, value) -> key.asScoreOrdinal(path) to value }

private fun JsonObject.objectAt(
    name: String,
    path: String,
): JsonObject = this[name] as? JsonObject ?: throw ResponseValidationException(path, "expected an object field '$name'")

private fun String.asScoreOrdinal(path: String): Int =
    toIntOrNull() ?: throw ResponseValidationException(fieldPath(path, this), "expected an integer score ordinal")

private fun JsonElement.asDouble(path: String): Double {
    val value = this as? JsonPrimitive
    return value?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        ?: throw ResponseValidationException(path, "expected a number")
}
