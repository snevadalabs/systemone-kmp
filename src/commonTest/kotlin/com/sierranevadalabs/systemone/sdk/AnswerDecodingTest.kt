package com.sierranevadalabs.systemone.sdk

import com.sierranevadalabs.systemone.sdk.conformance.FixtureCase
import com.sierranevadalabs.systemone.sdk.conformance.loadConformanceCases
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The answer decoder, against the same bytes the conformance fixtures carry. The three decode cases are read
 * from `conformance/cases/` rather than restated here, so a fixture edit cannot silently disagree with us.
 */
class AnswerDecodingTest {
    @Test
    fun decodesTheThreePrimitivesFromTheMixedFixture() {
        val answers = fixtureAnswers("response-mixed-primitives")

        assertEquals(NoulAnswer(0.91), answers.getValue("urgent"))
        assertEquals(
            ChoiceAnswer("billing", mapOf("billing" to 0.87, "technical" to 0.13), 0.87),
            answers.getValue("category"),
        )
        assertEquals(
            ScoreAnswer(
                score = 1.7,
                legend = mapOf(0 to JsonPrimitive("can wait"), 1 to JsonPrimitive("this week"), 2 to JsonPrimitive("today")),
                probabilities = mapOf(0 to 0.1, 1 to 0.1, 2 to 0.8),
                confidence = 0.8,
            ),
            answers.getValue("impact"),
        )
    }

    @Test
    fun scoreKeysArriveStringifiedAndSurfaceAsIntegers() {
        val score = assertIs<ScoreAnswer>(fixtureAnswers("response-score-stringified-keys").getValue("quality"))

        assertEquals(mapOf(0 to JsonPrimitive("bad"), 1 to JsonPrimitive("great")), score.legend)
        assertEquals(mapOf(0 to 0.2, 1 to 0.8), score.probabilities)
    }

    @Test
    fun anUnknownAnswerTypeBecomesUnknownAnswerAndKeepsTheRawPayload() {
        val answers = fixtureAnswers("response-unknown-answer-type")

        val unknown = assertIs<UnknownAnswer>(answers.getValue("mystery"))
        assertEquals("sentiment", unknown.type)
        assertEquals("frustrated", (unknown.raw as JsonObject).getValue("label").let { (it as JsonPrimitive).content })
        assertEquals(NoulAnswer(0.91), answers.getValue("urgent"))
    }

    @Test
    fun unknownExtraFieldsOnAKnownAnswerAreTolerated() {
        val answers =
            decode(
                """
                {
                  "urgent": {"type": "noul", "noul": 0.5, "explanation": "because", "nested": {"a": [1]} },
                  "category": {"type": "choice", "choice": "a", "confidence": 0.9,
                               "probabilities": {"a": 1.0}, "future_field": true }
                }
                """,
            )

        assertEquals(NoulAnswer(0.5), answers.getValue("urgent"))
        assertEquals(ChoiceAnswer("a", mapOf("a" to 1.0), 0.9), answers.getValue("category"))
    }

    @Test
    fun aMissingNoulFieldFailsNamingTheExactFieldPath() {
        val failure = assertFailsWith<ResponseValidationException> { decode("""{"urgent": {"type": "noul"}}""") }

        assertEquals("answers.urgent.noul", failure.fieldPath)
    }

    @Test
    fun aChoiceWithoutProbabilitiesFailsNamingTheExactFieldPath() {
        val failure =
            assertFailsWith<ResponseValidationException> {
                decode("""{"category": {"type": "choice", "choice": "a", "confidence": 0.9}}""")
            }

        assertEquals("answers.category.probabilities", failure.fieldPath)
    }

    @Test
    fun aMissingAnswerTypeFailsNamingTheTypeField() {
        val failure = assertFailsWith<ResponseValidationException> { decode("""{"urgent": {"noul": 0.5}}""") }

        assertEquals("answers.urgent.type", failure.fieldPath)
    }

    @Test
    fun aMalformedScoreKeyFailsNamingTheOffendingEntry() {
        val failure =
            assertFailsWith<ResponseValidationException> {
                decode(
                    """
                    {"quality": {"type": "score", "score": 1.0, "confidence": 0.5,
                                 "legend": {"0": "bad", "1": "great"}, "probabilities": {"0": 0.5, "x": 0.5}}}
                    """,
                )
            }

        assertEquals("answers.quality.probabilities.x", failure.fieldPath)
    }

    @Test
    fun aStringifiedScoreValueFailsRatherThanBeingCoerced() {
        val failure =
            assertFailsWith<ResponseValidationException> {
                decode("""{"quality": {"type": "score", "score": "1.0"}}""")
            }

        assertEquals("answers.quality.score", failure.fieldPath)
    }

    @Test
    fun anAnswerEntryThatIsNotAnObjectFailsNamingTheAnswerField() {
        // ticket 22's `decodeAnswer` survivors: an entry that is not an object at all must fail at the answer's
        // own path, not coerce to an UnknownAnswer or blow up one frame later.
        for (answers in listOf(
            """{"urgent": 5}""",
            """{"urgent": "x"}""",
            """{"urgent": []}""",
            """{"urgent": null}""",
            """{"urgent": true}""",
        )) {
            val failure = assertFailsWith<ResponseValidationException>(answers) { decode(answers) }

            assertEquals("answers.urgent", failure.fieldPath, answers)
            assertEquals("answers.urgent: expected an answer object", failure.message, answers)
        }
    }

    @Test
    fun anOutOfRangeOrHugeNumberIsForwardedVerbatimRatherThanRangeChecked() {
        // The brief fixes client-side validation at exactly two things (a non-empty question list, a score with
        // two levels), so a numeric field decodes as sent: 0, a negative, a fraction and an overflow to
        // Infinity are the server's business. The KDoc's `0..1` describes the wire, not a check we perform.
        assertEquals(NoulAnswer(0.0), decode("""{"urgent": {"type": "noul", "noul": 0}}""").getValue("urgent"))
        assertEquals(NoulAnswer(-1.0), decode("""{"urgent": {"type": "noul", "noul": -1}}""").getValue("urgent"))
        assertEquals(NoulAnswer(2.0), decode("""{"urgent": {"type": "noul", "noul": 2}}""").getValue("urgent"))
        assertEquals(NoulAnswer(0.25), decode("""{"urgent": {"type": "noul", "noul": 0.25}}""").getValue("urgent"))
        assertEquals(
            NoulAnswer(Double.POSITIVE_INFINITY),
            decode("""{"urgent": {"type": "noul", "noul": 1e400}}""").getValue("urgent"),
        )
        assertEquals(
            ChoiceAnswer("a", mapOf("a" to 0.0, "b" to -1.0, "c" to Double.POSITIVE_INFINITY), 0.5),
            decode(
                """{"urgent": {"type": "choice", "choice": "a", "confidence": 0.5,
                   "probabilities": {"a": 0, "b": -1, "c": 1e400}}}""",
            ).getValue("urgent"),
        )
    }

    @Test
    fun aNumericFieldThatIsNotAJsonNumberFailsNamingTheFieldInsteadOfBeingCoerced() {
        // ticket 22's `doubleAt` / `asDouble` survivors: the right key with a value that is neither a JSON
        // number nor a JSON string about it. Object, array and explicit null all fail at the exact field.
        val cases =
            mapOf(
                """{"urgent": {"type": "noul", "noul": {}}}""" to
                    ("answers.urgent.noul" to "expected a numeric field 'noul'"),
                """{"urgent": {"type": "noul", "noul": []}}""" to
                    ("answers.urgent.noul" to "expected a numeric field 'noul'"),
                """{"urgent": {"type": "noul", "noul": null}}""" to
                    ("answers.urgent.noul" to "expected a numeric field 'noul'"),
                """{"urgent": {"type": "choice", "choice": "a", "confidence": 1.0, "probabilities": []}}""" to
                    ("answers.urgent.probabilities" to "expected an object field 'probabilities'"),
                """{"urgent": {"type": "choice", "choice": "a", "confidence": 1.0, "probabilities": {"a": {}}}}""" to
                    ("answers.urgent.probabilities.a" to "expected a number"),
                """{"urgent": {"type": "choice", "choice": "a", "confidence": 1.0, "probabilities": {"a": []}}}""" to
                    ("answers.urgent.probabilities.a" to "expected a number"),
            )

        for ((answers, expected) in cases) {
            val (field, detail) = expected
            val failure = assertFailsWith<ResponseValidationException>(answers) { decode(answers) }

            assertEquals(field, failure.fieldPath, answers)
            assertEquals("$field: $detail", failure.message, answers)
        }
    }

    @Test
    fun scoreOrdinalsThatDoNotFitAnIntFailNamingTheOffendingKey() {
        assertEquals(
            mapOf(-1 to JsonPrimitive("x")),
            assertIs<ScoreAnswer>(
                decode(
                    """{"urgent":{"type":"score","score":1.0,"confidence":0.5,"legend":{"-1":"x"},"probabilities":{"-1":0.5}}}""",
                ).getValue("urgent"),
            ).legend,
        )

        val cases =
            mapOf(
                """{"urgent":{"type":"score","score":1.0,"confidence":0.5,"legend":{"1.5":"x"},"probabilities":{"0":0.5}}}""" to
                    "answers.urgent.legend.1.5",
                """{"urgent":{"type":"score","score":1.0,"confidence":0.5,"legend":{"99999999999":"x"},"probabilities":{"0":0.5}}}""" to
                    "answers.urgent.legend.99999999999",
            )

        for ((answers, field) in cases) {
            val failure = assertFailsWith<ResponseValidationException>(answers) { decode(answers) }

            assertEquals(field, failure.fieldPath, answers)
            assertEquals("$field: expected an integer score ordinal", failure.message, answers)
        }
    }

    @Test
    fun anEmptyTypeStringDegradesToUnknownAnswerLikeAnyOtherUnknownPrimitive() {
        // `type` is a server-owned string with no enum on our side, so a value we do not model — including the
        // empty string — takes the documented degrade path rather than failing the whole response.
        val unknown = assertIs<UnknownAnswer>(decode("""{"urgent": {"type": "", "noul": 0.5}}""").getValue("urgent"))

        assertEquals("", unknown.type)
        assertEquals("0.5", (unknown.raw as JsonObject).getValue("noul").jsonPrimitive.content)
    }

    @Test
    fun aKnownAnswerFieldWithTheWrongJsonTypeFailsNamingTheFieldPath() {
        // ticket 22's `as? JsonObject` / `as? JsonPrimitive` survivors on Answers.kt: the key is right and the
        // JSON type is not, so the answer must fail naming the exact field rather than coerce or crash later.
        val cases =
            mapOf(
                """{"urgent": {"type": 7, "noul": 0.5}}""" to "answers.urgent.type",
                """{"urgent": {"type": "noul", "noul": "0.5"}}""" to "answers.urgent.noul",
                """{"urgent": {"type": "noul", "noul": true}}""" to "answers.urgent.noul",
                """{"category": {"type": "choice", "choice": 1, "confidence": 0.9, "probabilities": {"a": 1.0}}}""" to
                    "answers.category.choice",
                """{"category": {"type": "choice", "choice": "a", "confidence": 0.9, "probabilities": [1.0]}}""" to
                    "answers.category.probabilities",
                """{"category": {"type": "choice", "choice": "a", "confidence": "0.9", "probabilities": {"a": 1.0}}}""" to
                    "answers.category.confidence",
                """{"category": {"type": "choice", "choice": "a", "confidence": 0.9, "probabilities": {"a": "1.0"}}}""" to
                    "answers.category.probabilities.a",
                """{"category": {"type": "choice", "choice": "a", "confidence": 0.9, "probabilities": {"a": [1]}}}""" to
                    "answers.category.probabilities.a",
                """{"quality": {"type": "score", "score": 1.0, "confidence": 0.5, "legend": {"0": "x"}, "probabilities": {"0": true}}}""" to
                    "answers.quality.probabilities.0",
                """{"quality": {"type": "score", "score": 1.0, "confidence": 0.5, "legend": [1], "probabilities": {"0": 0.5}}}""" to
                    "answers.quality.legend",
            )

        for ((answers, field) in cases) {
            val failure = assertFailsWith<ResponseValidationException>(answers) { decode(answers) }
            assertEquals(field, failure.fieldPath, answers)
        }
    }

    @Test
    fun everyFixtureResponseDecodesOrFailsAtTheFieldTheFixtureNames() {
        val cases = loadConformanceCases().filter { it.id.startsWith("response-") && it.responses.last().status == 200 }
        assertTrue(cases.isNotEmpty())
        cases.forEach { case ->
            val answers =
                Json
                    .parseToJsonElement(
                        case.responses
                            .last()
                            .body
                            .decodeToString(),
                    ).jsonObject
                    .getValue("answers")
                    .jsonObject
            val expectedField = case.expect["field"]?.jsonPrimitive?.content
            if (expectedField == null) {
                decodeAnswers(answers)
            } else {
                val failure = assertFailsWith<ResponseValidationException> { decodeAnswers(answers) }
                assertEquals(expectedField, failure.fieldPath, case.id)
            }
        }
    }

    private fun fixtureAnswers(id: String): Map<String, Answer> {
        val case = fixture(id)
        val body =
            Json
                .parseToJsonElement(
                    case.responses
                        .last()
                        .body
                        .decodeToString(),
                ).jsonObject
        return decodeAnswers(body.getValue("answers").jsonObject)
    }

    private fun fixture(id: String): FixtureCase = loadConformanceCases().first { it.id == id }

    private fun decode(answers: String): Map<String, Answer> = decodeAnswers(Json.parseToJsonElement(answers).jsonObject)
}
