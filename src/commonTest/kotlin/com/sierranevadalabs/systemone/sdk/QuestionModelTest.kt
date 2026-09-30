package com.sierranevadalabs.systemone.sdk

import com.sierranevadalabs.systemone.sdk.conformance.loadConformanceCases
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuestionModelTest {
    @Test
    fun buildersCarryTheIdTheTypeAndTheCriteria() {
        assertEquals(NoulQuestion("urgent", JsonPrimitive("Is it urgent?")), noul("urgent", "Is it urgent?"))
        assertEquals(
            ChoiceQuestion("category", JsonPrimitive("Which?"), mapOf("a" to null, "b" to JsonPrimitive("second"))),
            choice("category", "Which?", mapOf("a" to null, "b" to "second")),
        )
        assertEquals(
            ScoreQuestion("impact", JsonPrimitive("How bad?"), listOf(JsonPrimitive("low"), JsonPrimitive("high"))),
            score("impact", "How bad?", listOf("low", "high")),
        )
        assertEquals(JsonPrimitive("plain"), noul("urgent", "plain").prompt)
    }

    @Test
    fun structuredPromptsAndCriteriaSurviveTheJsonElementOverloads() {
        val prompt = buildJsonObject { put("text", "hi") }

        assertEquals(prompt, noul("urgent", prompt).prompt)
        assertEquals(mapOf("a" to JsonPrimitive(1)), choice("c", prompt, mapOf("a" to JsonPrimitive(1))).options)
        assertEquals(listOf(JsonPrimitive(1), JsonPrimitive(2)), score("s", prompt, listOf(JsonPrimitive(1), JsonPrimitive(2))).levels)
    }

    @Test
    fun noulCriteriaEncodesOnlyTheOutcomeThatIsDescribed() {
        val yesOnly = noul("urgent", "Is it urgent?", NoulCriteria(ifTrue = "needs a human"))
        assertEquals(
            buildJsonObject {
                put("type", "noul")
                put("instructions", "Is it urgent?")
                put("criteria", buildJsonObject { put("true", "needs a human") })
            },
            yesOnly.toWireJson(),
        )

        val noOnly = noul("urgent", "Is it urgent?", NoulCriteria(ifFalse = "routine"))
        assertEquals(
            buildJsonObject {
                put("type", "noul")
                put("instructions", "Is it urgent?")
                put("criteria", buildJsonObject { put("false", "routine") })
            },
            noOnly.toWireJson(),
        )
    }

    @Test
    fun choiceCriteriaReachTheWireWithTheDescriptionEachOptionCarries() {
        assertEquals(
            buildJsonObject {
                put("type", "choice")
                put("instructions", "Which?")
                put(
                    "criteria",
                    buildJsonObject {
                        put("a", JsonNull)
                        put("b", "second")
                    },
                )
            },
            choice("category", "Which?", mapOf("a" to null, "b" to "second")).toWireJson(),
        )
    }

    @Test
    fun noulCriteriaWithBothOutcomesEncodesBothSides() {
        val question = noul("urgent", "Is it urgent?", NoulCriteria("needs a human", "routine"))

        assertEquals(
            buildJsonObject {
                put("type", "noul")
                put("instructions", "Is it urgent?")
                put(
                    "criteria",
                    buildJsonObject {
                        put("true", "needs a human")
                        put("false", "routine")
                    },
                )
            },
            question.toWireJson(),
        )
    }

    @Test
    fun noulWithoutCriteriaOmitsTheKeyEntirely() {
        assertEquals(
            buildJsonObject {
                put("type", "noul")
                put("instructions", "Is it urgent?")
            },
            noul("urgent", "Is it urgent?").toWireJson(),
        )
    }

    @Test
    fun noulCriteriaAcceptsStructuredOutcomeDescriptions() {
        val yes = buildJsonObject { put("label", "needs a human") }
        val no = JsonArray(listOf(JsonPrimitive("routine")))
        val question = noul("urgent", "Is it urgent?", NoulCriteria(ifTrue = yes, ifFalse = no))

        assertEquals(
            buildJsonObject {
                put("type", "noul")
                put("instructions", "Is it urgent?")
                put(
                    "criteria",
                    buildJsonObject {
                        put("true", yes)
                        put("false", no)
                    },
                )
            },
            question.toWireJson(),
        )
    }

    @Test
    fun questionIdsBecomeTheWireQuestionKeysWithTheFixturesRequestShape() {
        val fixture = loadConformanceCases().first { it.id == "request-primitives-string-state" }
        val questions =
            listOf(
                noul("urgent", "Does this convey urgency?"),
                choice("category", "What is this about?", mapOf("billing" to null, "technical" to null)),
                score("impact", "How bad is it?", listOf("can wait", "this week", "today")),
            )

        val encoded = JsonObject(questions.associate { it.id to it.toWireJson() })

        assertEquals(Json.parseToJsonElement(fixture.requestBody).jsonObject.getValue("questions"), encoded)
    }

    @Test
    fun rejectsAnEmptyQuestionList() {
        assertFailsWith<IllegalArgumentException> { validateQuestions(emptyList()) }
    }

    @Test
    fun rejectsAScoreWithFewerThanTwoLevelsAndNamesTheQuestion() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                validateQuestions(listOf(score("impact", "How bad?", listOf("only"))))
            }

        assertTrue(failure.message!!.contains("impact"), failure.message)
    }

    @Test
    fun acceptsAScoreWithTwoLevels() {
        validateQuestions(listOf(score("impact", "How bad?", listOf("low", "high"))))
    }

    /**
     * The two local checks are the whole of client-side validation. Everything a caller could get wrong that
     * is not one of them must be forwarded to the server, exactly as both siblings do. Nothing here may throw.
     */
    @Test
    fun forwardsEveryWellFormedQuestionThatIsNotOneOfTheTwoCheckedCases() {
        validateQuestions(
            listOf(
                choice("no-options", "", emptyMap()),
                score("duplicate-levels", "How bad?", listOf("same", "same")),
                noul("", ""),
                noul("odd id with spaces", "?"),
                choice("empty-description", "Which?", mapOf("a" to null)),
            ),
        )
    }
}
