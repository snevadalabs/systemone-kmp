package com.sierranevadalabs.systemone.sdk

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The typed accessors on [SystemOneResponse].
 *
 * The two typed reads in [typedAccessReturnsTheConcreteAnswer] are the compile-time half of this ticket: they
 * assign the result of `get` to a concrete answer type, so the moment the accessor loses its type parameter —
 * or a `NoulQuestion` stops predicting a `NoulAnswer` — this file stops compiling and `./gradlew check` fails.
 * The committed `api/jvm/systemone-kmp.api` dump catches the other direction, a new erased shape such as a
 * `get(String)` overload, because it records every public method descriptor.
 *
 * ponytail: a literal negative-compilation test (`val c: ChoiceAnswer = response[urgent]` must *not* compile)
 * would need a separate compile-and-expect-failure Gradle task. The positive reads above fail on exactly the
 * same regression, so the task is not worth its keep.
 */
class SystemOneResponseTest {
    private val category = choice("category", "What is this about?", mapOf("billing" to null))
    private val urgent = noul("urgent", "Does this convey urgency?")
    private val choiceAnswer = ChoiceAnswer("billing", mapOf("billing" to 0.9), 0.9)
    private val noulAnswer = NoulAnswer(0.8)
    private val response = SystemOneResponse(mapOf("category" to choiceAnswer, "urgent" to noulAnswer))

    @Test
    fun typedAccessReturnsTheConcreteAnswer() {
        val c: ChoiceAnswer = response[category]
        val u: NoulAnswer = response[urgent]

        assertEquals(choiceAnswer, c)
        assertEquals(noulAnswer, u)
    }

    @Test
    fun readingTheWrongAnswerTypeThrowsAndNamesTheQuestion() {
        val mismatch = noul("category", "Does this convey urgency?")

        val failure = assertFailsWith<AnswerTypeMismatchException> { response[mismatch] }

        assertIs<ClassCastException>(failure)
        assertTrue(failure.message!!.contains("category"), failure.message)
        assertTrue(failure.message!!.contains("NoulQuestion"), failure.message)
        assertTrue(failure.message!!.contains("ChoiceAnswer"), failure.message)
    }

    @Test
    fun answerOrNullReturnsNullWhereGetThrows() {
        val mismatch = noul("category", "Does this convey urgency?")
        val absent = noul("absent", "Is this true?")

        assertNull(response.answerOrNull(mismatch))
        assertNull(response.answerOrNull(absent))
        assertFailsWith<NoSuchElementException> { response[absent] }
    }

    @Test
    fun answerOrNullReturnsNullForAnUnknownPrimitive() {
        val unknown = SystemOneResponse(mapOf("urgent" to UnknownAnswer("noul_v2", JsonPrimitive(0.5))))

        assertNull(unknown.answerOrNull(urgent))
    }

    @Test
    fun getOnAnUnknownPrimitiveNamesBothTypes() {
        // The raw map keeps an unknown primitive; the typed accessor is a checked cast against the question's
        // own answer type, so reading an unknown one is the same mismatch as reading a known-but-wrong one.
        val response = SystemOneResponse(mapOf("mystery" to UnknownAnswer("sentiment", JsonPrimitive("frustrated"))))

        val failure = assertFailsWith<AnswerTypeMismatchException> { response[noul("mystery", "?")] }

        assertTrue(failure.message!!.contains("NoulQuestion"), failure.message)
        assertTrue(failure.message!!.contains("UnknownAnswer"), failure.message)
    }

    @Test
    fun unknownAnswerSurvivesInTheRawAnswersMap() {
        val unknownAnswer = UnknownAnswer("noul_v2", JsonPrimitive(0.5))
        val unknown = SystemOneResponse(mapOf("urgent" to unknownAnswer))

        assertSame(unknownAnswer, unknown.answers["urgent"])
    }

    @Test
    fun anAnswerKeyTheRequestNeverAskedForStaysInTheRawMap() {
        val extra = SystemOneResponse(response.answers + ("surprise" to NoulAnswer(0.1)))

        assertEquals(NoulAnswer(0.1), extra.answers["surprise"])
        assertEquals(response.answers, response.answers.filterKeys { it != "surprise" })
    }

    @Test
    fun anAnswerKeyTheRequestAskedForButTheResponseOmittedIsSimplyAbsent() {
        val omitted = SystemOneResponse(mapOf("urgent" to noulAnswer))

        assertNull(omitted.answerOrNull(category))
        assertFailsWith<NoSuchElementException> { omitted[category] }
    }
}
