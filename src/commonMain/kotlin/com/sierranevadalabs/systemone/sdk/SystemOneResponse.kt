package com.sierranevadalabs.systemone.sdk

/**
 * The response to a `systemOne` call.
 *
 * [answers] is the raw, always-available view; [get] and [answerOrNull] are the typed ones, keyed by the
 * [Question] that produced the answer. The request is not consulted, so an answer key the request never asked
 * for still appears in [answers], and a key the request asked for but the server omitted is simply absent.
 *
 * @property answers every answer the server returned, keyed by question id, including unknown primitives.
 * @property model the model that answered, as the server named it.
 * @property usage the token counters, or `null` when the server omitted them.
 * @property requestId the server's `x-typesafe-request-id`, when it sent one.
 * @property status the HTTP status of the response.
 * @property headers every response header, as the server sent them.
 */
public class SystemOneResponse(
    public val answers: Map<String, Answer>,
    public val model: String? = null,
    public val usage: Usage? = null,
    public val requestId: String? = null,
    public val status: Int = 200,
    public val headers: Map<String, String> = emptyMap(),
) {
    /**
     * The answer to [question], statically typed.
     *
     * @throws AnswerTypeMismatchException if the answer under this key is not a `T` — a caller-side contract
     *   error, since the question's own type says what it asked for.
     * @throws NoSuchElementException if the response has no answer under this key.
     */
    public operator fun <T : Answer> get(question: Question<T>): T {
        val answer =
            answers[question.id]
                ?: throw NoSuchElementException("The response has no answer for question '${question.id}'.")
        if (!questionAccepts(question, answer)) {
            throw AnswerTypeMismatchException(
                "Question '${question.id}' is a ${question::class.simpleName} " +
                    "but the server answered with ${answer::class.simpleName}.",
            )
        }
        // Sound: questionAccepts above established the runtime type.
        @Suppress("UNCHECKED_CAST")
        return answer as T
    }

    /**
     * The answer to [question], or `null` if it is missing, an unknown primitive, or not a `T`.
     *
     * The non-throwing twin of [get].
     */
    public fun <T : Answer> answerOrNull(question: Question<T>): T? {
        val answer = answers[question.id] ?: return null
        if (!questionAccepts(question, answer)) return null
        @Suppress("UNCHECKED_CAST")
        return answer as T
    }
}

/**
 * Thrown when a question is read back as the wrong answer type.
 *
 * This is a programming error and nothing on the wire can produce it, so it deliberately does not live in
 * `com.sierranevadalabs.systemone.sdk.errors` next to the transport failures. It is a [ClassCastException] because
 * the accessor *is* a checked cast: that is exactly what the naive unchecked implementation throws, so a
 * caller's `catch` sees the same family either way.
 *
 * @param message names the question id and both types.
 */
public class AnswerTypeMismatchException(
    message: String,
) : ClassCastException(message)
