package com.sierranevadalabs.systemone.sdk.conformance

import com.sierranevadalabs.systemone.sdk.Answer
import com.sierranevadalabs.systemone.sdk.ChoiceAnswer
import com.sierranevadalabs.systemone.sdk.NoulAnswer
import com.sierranevadalabs.systemone.sdk.NoulCriteria
import com.sierranevadalabs.systemone.sdk.Question
import com.sierranevadalabs.systemone.sdk.RetryPolicy
import com.sierranevadalabs.systemone.sdk.ScoreAnswer
import com.sierranevadalabs.systemone.sdk.SystemOneConfig
import com.sierranevadalabs.systemone.sdk.SystemOneResponse
import com.sierranevadalabs.systemone.sdk.UnknownAnswer
import com.sierranevadalabs.systemone.sdk.choice
import com.sierranevadalabs.systemone.sdk.createClient
import com.sierranevadalabs.systemone.sdk.errors.APIConnectionError
import com.sierranevadalabs.systemone.sdk.errors.APIResponseValidationError
import com.sierranevadalabs.systemone.sdk.errors.APITimeoutError
import com.sierranevadalabs.systemone.sdk.errors.AuthenticationError
import com.sierranevadalabs.systemone.sdk.errors.BadRequestError
import com.sierranevadalabs.systemone.sdk.errors.InternalServerError
import com.sierranevadalabs.systemone.sdk.errors.NotFoundError
import com.sierranevadalabs.systemone.sdk.errors.PermissionDeniedError
import com.sierranevadalabs.systemone.sdk.errors.RateLimitError
import com.sierranevadalabs.systemone.sdk.errors.SystemOneError
import com.sierranevadalabs.systemone.sdk.errors.UnprocessableEntityError
import com.sierranevadalabs.systemone.sdk.noul
import com.sierranevadalabs.systemone.sdk.score
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/** One response in a case's script: the status, the headers, and the exact bytes of the body. */
internal class FixtureResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** One conformance case, decoded from the `conformance/` fixture set. */
internal data class FixtureCase(
    val id: String,
    val method: String,
    val path: String,
    val requestBody: String,
    val responses: List<FixtureResponse>,
    val expect: JsonObject,
)

private val KNOWN_ANSWER_TYPES = setOf("noul", "choice", "score")

/** Reads every `*.json` under the manifest's case directory and hands back one [FixtureCase] each. */
internal fun loadConformanceCases(files: Map<String, String> = FIXTURE_FILES): List<FixtureCase> {
    val manifest = Json.parseToJsonElement(files.getValue("manifest.json")).jsonObject
    val directory = manifest.getValue("cases").jsonPrimitive.content
    return files.keys
        .filter { it.startsWith("$directory/") && it.endsWith(".json") }
        .sorted()
        .map { parseCase(it, files.getValue(it)) }
}

private fun parseCase(
    path: String,
    text: String,
): FixtureCase {
    val case = Json.parseToJsonElement(text).jsonObject
    val id = case.getValue("id").jsonPrimitive.content
    require(id == path.substringAfterLast('/').removeSuffix(".json")) { "$path: id '$id' must match the file name" }
    val request = case.getValue("request").jsonObject
    return FixtureCase(
        id = id,
        method = request.getValue("method").jsonPrimitive.content,
        path = request.getValue("path").jsonPrimitive.content,
        requestBody = request.getValue("body").toString(),
        responses = case.getValue("responses").jsonArray.map(::parseResponse),
        expect = case.getValue("expect").jsonObject,
    )
}

private fun parseResponse(element: JsonElement): FixtureResponse {
    val response = element.jsonObject
    val body = response["bodyRaw"]?.jsonPrimitive?.content ?: response.getValue("body").toString()
    return FixtureResponse(
        status = response.getValue("status").jsonPrimitive.int,
        headers = response["headers"]?.jsonObject.orEmpty().mapValues { (_, value) -> value.jsonPrimitive.content },
        body = body.encodeToByteArray(),
    )
}

/**
 * Replays one case through the **real client** over Ktor's `MockEngine`, and checks everything the format's
 * `expect` block declares.
 *
 * The response list *is* the attempt script, so a one-response case runs with retries off and makes exactly one
 * attempt even when its status would otherwise be retryable (the `502` HTML case), while a two-response case
 * runs the client's own policy and the `429` actually retries. `expect.delayMs` is read from the transport's
 * `sleeper` seam — the same seam the retry tests use — rather than waited out.
 */
internal suspend fun assertConformanceCase(case: FixtureCase) {
    val delays = mutableListOf<Long>()
    var attempt = 0
    val engine =
        MockEngine { _ ->
            val response = case.responses[attempt++]
            respond(
                response.body,
                HttpStatusCode.fromValue(response.status),
                response.headers.asHeaders(),
            )
        }
    val client =
        createClient(
            SystemOneConfig(apiKey = "test-key", engine = engine),
            random = { 0.0 },
            sleeper = { delays += it },
        )
    try {
        val request = Json.parseToJsonElement(case.requestBody).jsonObject
        val questions = request.getValue("questions").toQuestions()
        // The script's length is the attempt count, so a lone response means no retries even for a 5xx status.
        val retry = if (case.responses.size == 1) RetryPolicy(maxRetries = 0) else null

        var response: SystemOneResponse? = null
        var failure: Throwable? = null
        try {
            response =
                client.systemOne(
                    request.getValue("state"),
                    *questions.toTypedArray(),
                    model = request["model"]?.jsonPrimitive?.content,
                    retry = retry,
                )
        } catch (thrown: Throwable) {
            failure = thrown
        }

        assertWire(case, engine)

        val declaredError = case.expect["error"]?.jsonPrimitive?.content
        if (declaredError != null) {
            val error = assertIs<SystemOneError>(failure, "${case.id}: expected $declaredError, got ${failure ?: "success"}")
            assertEquals(declaredError.toErrorClass(), error::class, "${case.id}: error class")
            case.expect["message"]?.jsonPrimitive?.content?.let { message ->
                assertEquals(message, error.message, "${case.id}: extracted message")
            }
            case.expect["field"]?.jsonPrimitive?.content?.let { field ->
                val validation = error as? APIResponseValidationError ?: fail("${case.id}: '$declaredError' carries no field")
                assertEquals(field, validation.fieldPath, "${case.id}: offending field")
            }
        } else {
            assertEquals(null, failure, "${case.id}: expected success")
            assertAnswers(case, requireNotNull(response).answers)
            case.expect["usage"]?.jsonPrimitive?.content?.let { expected ->
                assertEquals(expected == "present", response.usage != null, "${case.id}: usage must be $expected")
            }
        }

        case.expect["delayMs"]?.jsonPrimitive?.content?.toLong()?.let { expected ->
            assertEquals(listOf(expected), delays, "${case.id}: the transport's retry delay")
        }
    } finally {
        client.close()
    }
}

/** The request the real client put on the wire is the fixture's request — no key added, dropped, or retyped. */
private suspend fun assertWire(
    case: FixtureCase,
    engine: MockEngine,
) {
    val attempts = engine.requestHistory
    assertEquals(case.responses.size, attempts.size, "${case.id}: attempts the client made")
    val first = attempts.first()
    assertEquals(case.method, first.method.value, "${case.id}: method")
    assertEquals(case.path, first.url.encodedPath, "${case.id}: path")
    assertEquals(
        Json.parseToJsonElement(case.requestBody),
        Json.parseToJsonElement(first.body.toByteArray().decodeToString()),
        "${case.id}: request body",
    )
}

/** The answer types a case declares, checked against the decoded answer hierarchy. */
private fun assertAnswers(
    case: FixtureCase,
    answers: Map<String, Answer>,
) {
    case.expect["answers"]?.jsonObject?.forEach { (key, declared) ->
        val answer = answers[key] ?: fail("${case.id}: no answer '$key'")
        when (val type = declared.jsonPrimitive.content) {
            in KNOWN_ANSWER_TYPES -> assertEquals(type, answer.declaredTypeName(), "${case.id}: '$key' answer type")
            "unknown" -> {
                val unknown = assertIs<UnknownAnswer>(answer, "${case.id}: '$key' must stay unknown")
                val expected =
                    case.expect["unknownTypes"]
                        ?.jsonObject
                        ?.get(key)
                        ?.jsonPrimitive
                        ?.content
                assertEquals(expected, unknown.type, "${case.id}: '$key' carries the server's own type string")
            }
            else -> fail("${case.id}: '$key' declares an unknown expected type '$type'")
        }
    }
}

private fun Answer.declaredTypeName(): String =
    when (this) {
        is NoulAnswer -> "noul"
        is ChoiceAnswer -> "choice"
        is ScoreAnswer -> "score"
        is UnknownAnswer -> "unknown"
    }

/** `expect.error` is the class name every SDK uses, with any SDK prefix removed. */
private fun String.toErrorClass(): KClass<out SystemOneError> =
    when (this) {
        "BadRequestError" -> BadRequestError::class
        "AuthenticationError" -> AuthenticationError::class
        "PermissionDeniedError" -> PermissionDeniedError::class
        "NotFoundError" -> NotFoundError::class
        "UnprocessableEntityError" -> UnprocessableEntityError::class
        "RateLimitError" -> RateLimitError::class
        "InternalServerError" -> InternalServerError::class
        "APIConnectionError" -> APIConnectionError::class
        "APITimeoutError" -> APITimeoutError::class
        "APIResponseValidationError" -> APIResponseValidationError::class
        else -> fail("no canonical error class named '$this'")
    }

private fun JsonElement.toQuestions(): List<Question<*>> =
    jsonObject.map { (id, element) ->
        val question = element.jsonObject
        val prompt = question.getValue("instructions")
        when (val type = question.getValue("type").jsonPrimitive.content) {
            "noul" -> {
                val criteria = question["criteria"]?.jsonObject?.let { NoulCriteria(it["true"], it["false"]) }
                noul(id, prompt, criteria)
            }
            "choice" -> choice(id, prompt, question.getValue("criteria").jsonObject)
            "score" -> score(id, prompt, question.getValue("criteria").jsonArray.map { it })
            else -> fail("no question builder for fixture type '$type'")
        }
    }

private fun Map<String, String>.asHeaders(): Headers = HeadersBuilder().apply { forEach { (name, value) -> append(name, value) } }.build()
