package com.sierranevadalabs.systemone.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A question to send to the System One API, carrying both its wire name and the concrete [Answer] type it
 * predicts.
 *
 * The typed key *is* the wire question: [id] is the key the answer comes back under, and `T` is what
 * [SystemOneResponse.get] hands back. That is what lets one call mix primitives and still read each answer
 * statically typed:
 *
 * ```kotlin
 * val category = choice("category", "What is this about?", mapOf("billing" to null))
 * val urgent = noul("urgent", "Does this convey urgency?")
 *
 * val r = client.systemOne("Help!", category, urgent)
 * val c: ChoiceAnswer = r[category]
 * ```
 *
 * @property id the wire key; the answer to this question arrives under this key.
 * @property prompt the question text, as a [JsonElement] so structured prompts work too.
 */
public sealed interface Question<T : Answer> {
    /** The wire key this question's answer arrives under. */
    public val id: String

    /** The question text. */
    public val prompt: JsonElement
}

/** The question asked by [noul]. */
public data class NoulQuestion(
    override val id: String,
    override val prompt: JsonElement,
    /** Optional descriptions of the yes and no outcomes, or `null` for neither. */
    public val criteria: NoulCriteria? = null,
) : Question<NoulAnswer>

/**
 * Optional descriptions of a [noul] question's yes and no outcomes, mirroring the siblings' noul criteria.
 *
 * On the wire these are the two optional keys `criteria.true` and `criteria.false`. An outcome with no
 * description is omitted rather than sent as `null`, which is what both reference SDKs' encoders do. A
 * description is a [String] or any other [JsonElement], so an object or array works too.
 *
 * @property ifTrue the description of the yes outcome, or `null` to omit it. Wire key `true`.
 * @property ifFalse the description of the no outcome, or `null` to omit it. Wire key `false`.
 */
public data class NoulCriteria(
    public val ifTrue: JsonElement?,
    public val ifFalse: JsonElement?,
) {
    /**
     * Both outcome descriptions as plain text.
     *
     * @param ifTrue the yes outcome's text, or `null`.
     * @param ifFalse the no outcome's text, or `null`.
     */
    public constructor(
        ifTrue: String? = null,
        ifFalse: String? = null,
    ) : this(ifTrue?.let(::JsonPrimitive), ifFalse?.let(::JsonPrimitive))
}

/** The question asked by [choice]. */
public data class ChoiceQuestion(
    override val id: String,
    override val prompt: JsonElement,
    /** The option keys, mapped to their optional descriptions. */
    public val options: Map<String, JsonElement?>,
) : Question<ChoiceAnswer>

/** The question asked by [score]. */
public data class ScoreQuestion(
    override val id: String,
    override val prompt: JsonElement,
    /** The ordered levels, lowest first. At least two. */
    public val levels: List<JsonElement>,
) : Question<ScoreAnswer>

/**
 * Asks a boolean question: is [prompt] true of the state? Reads back as a [NoulAnswer].
 *
 * @param criteria optional descriptions of the yes and no outcomes.
 */
public fun noul(
    id: String,
    prompt: String,
    criteria: NoulCriteria? = null,
): NoulQuestion = NoulQuestion(id, JsonPrimitive(prompt), criteria)

/**
 * Asks a boolean question with a structured [prompt]. Reads back as a [NoulAnswer].
 *
 * @param criteria optional descriptions of the yes and no outcomes.
 */
public fun noul(
    id: String,
    prompt: JsonElement,
    criteria: NoulCriteria? = null,
): NoulQuestion = NoulQuestion(id, prompt, criteria)

/**
 * Asks the model to pick one of [options]. Reads back as a [ChoiceAnswer].
 *
 * @param options the option keys, each mapped to an optional plain-text description.
 */
public fun choice(
    id: String,
    prompt: String,
    options: Map<String, String?>,
): ChoiceQuestion = ChoiceQuestion(id, JsonPrimitive(prompt), options.mapValues { (_, description) -> description?.let(::JsonPrimitive) })

/**
 * Asks the model to pick one of [options], with a structured [prompt]. Reads back as a [ChoiceAnswer].
 *
 * @param options the option keys, each mapped to an optional structured description.
 */
public fun choice(
    id: String,
    prompt: JsonElement,
    options: Map<String, JsonElement>,
): ChoiceQuestion = ChoiceQuestion(id, prompt, options)

/**
 * Asks the model to rate the state on ordered [levels]. Reads back as a [ScoreAnswer].
 *
 * @param levels at least two levels, lowest first.
 */
public fun score(
    id: String,
    prompt: String,
    levels: List<String>,
): ScoreQuestion = ScoreQuestion(id, JsonPrimitive(prompt), levels.map(::JsonPrimitive))

/**
 * Asks the model to rate the state on ordered [levels], with a structured [prompt]. Reads back as a
 * [ScoreAnswer].
 *
 * @param levels at least two levels, lowest first.
 */
public fun score(
    id: String,
    prompt: JsonElement,
    levels: List<JsonElement>,
): ScoreQuestion = ScoreQuestion(id, prompt, levels)

/**
 * Whether [answer] is the answer type this question predicts.
 *
 * This is the type check behind [SystemOneResponse.get] and [SystemOneResponse.answerOrNull]. It cannot be a
 * reified cast: `T` is erased at runtime, so `answers[id] as? T` would compile to an unchecked cast to
 * [Answer] and hand back a wrong-typed value that only blows up at the caller's assignment. The exhaustive
 * `when` over the sealed hierarchy is what forces a new primitive to be wired up here.
 */
internal fun questionAccepts(
    question: Question<*>,
    answer: Answer,
): Boolean =
    when (question) {
        is NoulQuestion -> answer is NoulAnswer
        is ChoiceQuestion -> answer is ChoiceAnswer
        is ScoreQuestion -> answer is ScoreAnswer
    }

/**
 * The whole of client-side validation: a non-empty question list, and a `score` with at least two levels.
 *
 * Everything else is forwarded to the server, matching both reference SDKs. In particular a well-formed but
 * doubtful question — no options, duplicate levels, a blank id — must pass through so the server's `422` is
 * the thing that rejects it.
 */
internal fun validateQuestions(questions: List<Question<*>>) {
    require(questions.isNotEmpty()) { "At least one question is required." }
    for (question in questions) {
        if (question is ScoreQuestion) {
            require(question.levels.size >= 2) {
                "Score question '${question.id}' requires at least two levels, was ${question.levels.size}."
            }
        }
    }
}

/**
 * This question as its wire object: `{type, instructions, criteria?}`. The id is not part of it — the id is the
 * key of the enclosing `questions` map, which is why the question can serve as its own typed key.
 */
internal fun Question<*>.toWireJson(): JsonObject =
    buildJsonObject {
        when (val question = this@toWireJson) {
            is NoulQuestion -> {
                put("type", "noul")
                put("instructions", question.prompt)
                question.criteria?.let { criteria ->
                    put(
                        "criteria",
                        buildJsonObject {
                            criteria.ifTrue?.let { put("true", it) }
                            criteria.ifFalse?.let { put("false", it) }
                        },
                    )
                }
            }
            is ChoiceQuestion -> {
                put("type", "choice")
                put("instructions", question.prompt)
                put(
                    "criteria",
                    JsonObject(question.options.mapValues { (_, description) -> description ?: JsonNull }),
                )
            }
            is ScoreQuestion -> {
                put("type", "score")
                put("instructions", question.prompt)
                put("criteria", JsonArray(question.levels))
            }
        }
    }
