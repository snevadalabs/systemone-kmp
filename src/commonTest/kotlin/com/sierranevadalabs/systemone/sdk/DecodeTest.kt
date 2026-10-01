package com.sierranevadalabs.systemone.sdk

import com.sierranevadalabs.systemone.sdk.errors.APIResponseValidationError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The wire reader at its own seam: one JSON value and its dotted path, with one place to name a field that
 * is missing or the wrong type. The higher suites pin the same rules through the public client.
 */
class DecodeTest {
    @Test
    fun aRequiredStringReadsTheField() {
        assertEquals("a", payload("""{"name":"a"}""").requiredString("name"))
    }

    @Test
    fun aMissingRequiredStringNamesItsPath() {
        val failure = assertFailsWith<ResponseValidationException> { payload("""{}""").requiredString("name") }

        assertEquals("name", failure.fieldPath)
        assertEquals("name: expected a string field 'name'", failure.message)
    }

    @Test
    fun aRequiredStringOfTheWrongTypeNamesItsPath() {
        for (value in listOf("5", "null", "true", "{}", "[]")) {
            val failure =
                assertFailsWith<ResponseValidationException>(value) {
                    payload("""{"name":$value}""").requiredString("name")
                }

            assertEquals("name", failure.fieldPath, value)
        }
    }

    @Test
    fun anOptionalStringReadsTheField() {
        assertEquals("a", payload("""{"name":"a"}""").optionalString("name"))
    }

    @Test
    fun anOptionalStringIsNullWhenItIsMissingOrTheWrongType() {
        for (value in listOf(null, "5", "null", "true", "{}", "[]")) {
            val json = if (value == null) "{}" else """{"name":$value}"""

            assertNull(payload(json).optionalString("name"), json)
        }
    }

    @Test
    fun aNestedReadNamesTheWholeDottedPath() {
        val failure =
            assertFailsWith<ResponseValidationException> {
                payload("""{"answers":{"urgent":{}}}""").child("answers").child("urgent").requiredString("type")
            }

        assertEquals("answers.urgent.type", failure.fieldPath)
        assertEquals("answers.urgent.type: expected a string field 'type'", failure.message)
    }

    @Test
    fun aRequiredDoubleReadsTheField() {
        assertEquals(0.5, payload("""{"noul":0.5}""").requiredDouble("noul"))
    }

    @Test
    fun aRequiredDoubleOfTheWrongTypeNamesItsPath() {
        for (value in listOf("null", "true", "\"0.5\"", "{}", "[]")) {
            val failure =
                assertFailsWith<ResponseValidationException>(value) {
                    payload("""{"noul":$value}""").requiredDouble("noul")
                }

            assertEquals("noul", failure.fieldPath, value)
            assertEquals("noul: expected a numeric field 'noul'", failure.message, value)
        }
    }

    @Test
    fun anOptionalIntReadsTheField() {
        assertEquals(12, payload("""{"input_tokens":12}""").optionalInt("input_tokens"))
        assertEquals(-1, payload("""{"input_tokens":-1}""").optionalInt("input_tokens"))
    }

    @Test
    fun anOptionalIntIsNullWhenItIsMissingOrTheWrongType() {
        for (value in listOf(null, "\"12\"", "99999999999", "true", "{}", "[]")) {
            val json = if (value == null) "{}" else """{"input_tokens":$value}"""

            assertNull(payload(json).optionalInt("input_tokens"), json)
        }
    }

    @Test
    fun aRequiredObjectReturnsACursorAtItsOwnPath() {
        val failure =
            assertFailsWith<ResponseValidationException> {
                payload("""{"answers":{}}""").requiredObject("answers").requiredString("type")
            }

        assertEquals("answers.type", failure.fieldPath)
    }

    @Test
    fun aRequiredObjectOfTheWrongTypeNamesItsPath() {
        for (value in listOf("null", "[]", "5", "\"x\"")) {
            val failure =
                assertFailsWith<ResponseValidationException>(value) {
                    payload("""{"answers":$value}""").requiredObject("answers")
                }

            assertEquals("answers", failure.fieldPath, value)
            assertEquals("answers: expected an object field 'answers'", failure.message, value)
        }
    }

    @Test
    fun anOptionalObjectReadsTheFieldAndIsNullWhenItIsMissingOrTheWrongType() {
        assertEquals(12, payload("""{"usage":{"input_tokens":12}}""").optionalObject("usage")?.optionalInt("input_tokens"))

        for (value in listOf(null, "null", "[]", "5", "\"x\"")) {
            val json = if (value == null) "{}" else """{"usage":$value}"""

            assertNull(payload(json).optionalObject("usage"), json)
        }
    }

    @Test
    fun anOptionalArrayReadsEveryElementAtItsOwnPath() {
        val models = payload("""{"models":[{"name":"a"},{}]}""").optionalArray("models")

        assertEquals(2, models?.size)
        assertEquals("a", models?.get(0)?.requiredString("name"))
        val failure = assertFailsWith<ResponseValidationException> { models?.get(1)?.requiredString("name") }
        assertEquals("models.1.name", failure.fieldPath)
    }

    @Test
    fun anOptionalArrayIsNullWhenItIsMissingOrNotAnArray() {
        for (json in listOf("{}", """{"models":null}""", """{"models":5}""", """{"models":{}}""", """{"models":"x"}""")) {
            assertNull(payload(json).optionalArray("models"), json)
        }
    }

    @Test
    fun stringKeyedDoublesReadsEveryValue() {
        val cursor = at("answers.category", """{"probabilities":{"billing":0.87,"technical":0.13}}""")

        assertEquals(mapOf("billing" to 0.87, "technical" to 0.13), cursor.stringKeyedDoubles("probabilities"))
    }

    @Test
    fun stringKeyedDoublesNamesTheKeyOfAValueThatIsNotANumber() {
        val cursor = at("answers.category", """{"probabilities":{"billing":"x"}}""")

        val failure = assertFailsWith<ResponseValidationException> { cursor.stringKeyedDoubles("probabilities") }

        assertEquals("answers.category.probabilities.billing", failure.fieldPath)
        assertEquals("answers.category.probabilities.billing: expected a number", failure.message)
    }

    @Test
    fun aKeyedMapOfTheWrongTypeNamesTheMap() {
        val failure =
            assertFailsWith<ResponseValidationException> {
                at("answers.category", """{"probabilities":[]}""").stringKeyedDoubles("probabilities")
            }

        assertEquals("answers.category.probabilities", failure.fieldPath)
        assertEquals("answers.category.probabilities: expected an object field 'probabilities'", failure.message)
    }

    @Test
    fun intKeyedDoublesReadsStringifiedOrdinalsAsInts() {
        val cursor = at("answers.impact", """{"probabilities":{"0":0.1,"2":0.9}}""")

        assertEquals(mapOf(0 to 0.1, 2 to 0.9), cursor.intKeyedDoubles("probabilities"))
    }

    @Test
    fun intKeyedDoublesNamesAKeyThatIsNotAnOrdinal() {
        val cursor = at("answers.impact", """{"probabilities":{"x":0.1}}""")

        val failure = assertFailsWith<ResponseValidationException> { cursor.intKeyedDoubles("probabilities") }

        assertEquals("answers.impact.probabilities.x", failure.fieldPath)
        assertEquals("answers.impact.probabilities.x: expected an integer score ordinal", failure.message)
    }

    @Test
    fun intKeyedElementsKeepsTheRawLevels() {
        val cursor = at("answers.impact", """{"legend":{"0":"can wait","1":{"x":1}}}""")

        assertEquals(
            mapOf(0 to JsonPrimitive("can wait"), 1 to element("""{"x":1}""")),
            cursor.intKeyedElements("legend"),
        )
    }

    @Test
    fun aBodyThatIsNotAJsonObjectFailsWithTheBodyMessage() {
        for (body in listOf("", "not json", "[]", "5", "\"x\"", "null")) {
            val failure = assertFailsWith<APIResponseValidationError>(body) { decodeBody(response(body)) { true } }

            assertNull(failure.fieldPath, body)
            assertEquals("expected a JSON object response body", failure.message, body)
        }
    }

    @Test
    fun aBodyFieldPathNamesABodyThatIsNotAJsonObject() {
        val failure =
            assertFailsWith<APIResponseValidationError> {
                decodeBody(
                    response("[]"),
                    bodyFieldPath = "models",
                    bodyMessage = "GET /v1/models: expected { models: [...] }",
                ) { true }
            }

        assertEquals("models", failure.fieldPath)
        assertEquals("GET /v1/models: expected { models: [...] }", failure.message)
    }

    @Test
    fun decodeBodyTranslatesAReaderFailureWithItsPath() {
        val failure =
            assertFailsWith<APIResponseValidationError> {
                decodeBody(response("""{"answers":{"urgent":{"type":"noul"}}}""")) { payload ->
                    payload.requiredObject("answers").child("urgent").requiredDouble("noul")
                }
            }

        assertEquals("answers.urgent.noul", failure.fieldPath)
        assertEquals("answers.urgent.noul: expected a numeric field 'noul'", failure.message)
        assertEquals(200, failure.status)
        assertEquals("req_1", failure.requestId)
    }

    @Test
    fun decodeBodyLeadsTheMessageWithThePrefix() {
        val failure =
            assertFailsWith<APIResponseValidationError> {
                decodeBody(response("""{"models":[{}]}"""), prefix = "GET /v1/models") { payload ->
                    payload.optionalArray("models")!![0].requiredString("name")
                }
            }

        assertEquals("models.0.name", failure.fieldPath)
        assertEquals("GET /v1/models: models.0.name: expected a string field 'name'", failure.message)
    }

    private fun payload(json: String): WireValue = WireValue(Json.parseToJsonElement(json), "")

    private fun at(
        path: String,
        json: String,
    ): WireValue = WireValue(Json.parseToJsonElement(json), path)

    private fun element(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun response(body: String): TransportResponse =
        TransportResponse(status = 200, headers = emptyMap(), body = body, requestId = "req_1", retryAfterMs = null)
}
