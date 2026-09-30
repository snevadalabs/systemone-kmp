package com.sierranevadalabs.systemone.sdk

import com.sierranevadalabs.systemone.sdk.conformance.FixtureCase
import com.sierranevadalabs.systemone.sdk.conformance.loadConformanceCases
import com.sierranevadalabs.systemone.sdk.errors.APIConnectionError
import com.sierranevadalabs.systemone.sdk.errors.APIError
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
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The public entry point, end to end through `MockEngine`: the request it puts on the wire, the response
 * surface it hands back, the error it throws for a non-2xx status, and its lifecycle.
 */
class ClientTest {
    @Test
    fun roundTripsTheFixturesRequestAndResponseSurface() =
        runTest {
            val fixture = loadConformanceCases().first { it.id == "response-mixed-primitives" }
            val engine = MockEngine { respond(fixture.responses.single().body, HttpStatusCode.OK, fixtureHeaders(fixture)) }
            val client = client(engine)
            val urgent = noul("urgent", "Does this convey urgency?")
            val category = choice("category", "What is this about?", mapOf("billing" to null, "technical" to null))
            val impact = score("impact", "How bad is it?", listOf("can wait", "this week", "today"))

            val response = client.systemOne("Help! My payouts have been failing for 3 days.", urgent, category, impact)

            val request = engine.requestHistory.single()
            assertEquals(
                Json.parseToJsonElement(fixture.requestBody),
                Json.parseToJsonElement(request.body.toByteArray().decodeToString()),
                "the request body must match the fixture exactly",
            )
            assertEquals("Bearer test-key", request.headers[AUTHORIZATION_HEADER])
            assertEquals("application/json", request.headers[ACCEPT_HEADER])
            // Ktor moves the content type onto the outgoing body; the engine writes it as the header on the wire.
            assertEquals(ContentType.Application.Json, request.body.contentType)
            assertEquals("typesafe-sdk-kotlin/$SDK_VERSION", request.headers[TYPESAFE_SDK_HEADER])
            assertTrue(request.headers[TYPESAFE_RUNTIME_HEADER]?.contains('/') == true, "the runtime header names a platform and version")
            assertNull(request.headers[TYPESAFE_RETRY_COUNT_HEADER], "attempt 0 never carries a retry count")

            assertEquals("jev-latest", response.model)
            assertEquals(Usage(12, 3), response.usage)
            assertEquals("req_123", response.requestId)
            assertEquals(200, response.status)
            assertEquals("application/json", response.headers["content-type"])
            assertEquals(NoulAnswer(0.91), response[urgent])
            assertEquals(ChoiceAnswer("billing", mapOf("billing" to 0.87, "technical" to 0.13), 0.87), response[category])
            assertEquals(1.7, response[impact].score)
            client.close()
        }

    @Test
    fun structuredStateAndPerCallModelAndTimeoutReachTheWire() =
        runTest {
            val fixture = loadConformanceCases().first { it.id == "request-primitives-structured-state" }
            val engine = MockEngine { respond(fixture.responses.single().body, HttpStatusCode.OK, fixtureHeaders(fixture)) }
            val client = client(engine)
            val urgent = noul("urgent", "Does this convey urgency?")
            val category = choice("category", "What is this about?", mapOf("billing" to null, "technical" to null))
            val impact = score("impact", "How bad is it?", listOf("can wait", "this week", "today"))
            val state =
                buildJsonObject {
                    put("document", "Hello 🌍")
                    put("locale", "en-US")
                }

            client.systemOne(state, urgent, category, impact, model = "jev-fast", timeout = 250.milliseconds)

            val request = engine.requestHistory.single()
            val sent = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            val expected = Json.parseToJsonElement(fixture.requestBody).jsonObject
            assertEquals(expected.getValue("state"), sent.getValue("state"))
            assertEquals(expected.getValue("questions"), sent.getValue("questions"))
            assertEquals(JsonPrimitive("jev-fast"), sent.getValue("model"))
            assertEquals(250L, request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
            client.close()
        }

    @Test
    fun aJsonNullStateIsSentRatherThanDropped() =
        runTest {
            // after python tests/test_types.py's "test_json_value_and_state_exclude_top_level_none": the wire
            // wants an explicit `state` on every call, and a caller's JSON null is that value.
            val engine = MockEngine { respond("""{"model":"jev-latest","answers":{}}""") }
            val client = client(engine)

            client.systemOne(JsonNull, noul("urgent", "?"))

            val request = engine.requestHistory.single()
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            assertTrue(body.containsKey("state"), "the key is always sent")
            assertEquals(JsonNull, body["state"])
            client.close()
        }

    @Test
    fun perCallRetryPolicyReplacesTheClientsOwn() =
        runTest {
            val engine = MockEngine { respond("""{"error":{"message":"later"}}""", HttpStatusCode.ServiceUnavailable) }
            val client = client(engine, retry = RetryPolicy(maxRetries = 0))

            runCatching { client.systemOne("hello", noul("urgent", "?"), retry = RetryPolicy(maxRetries = 2)) }

            assertEquals(3, engine.requestHistory.size, "the per-call policy wins over the client default")
            client.close()
        }

    @Test
    fun mapsEveryStatusToItsErrorClassInOnePlace() =
        runTest {
            val expected =
                listOf(
                    400 to BadRequestError::class,
                    401 to AuthenticationError::class,
                    403 to PermissionDeniedError::class,
                    404 to NotFoundError::class,
                    408 to APIError::class,
                    418 to APIError::class,
                    422 to UnprocessableEntityError::class,
                    429 to RateLimitError::class,
                    500 to InternalServerError::class,
                    529 to InternalServerError::class,
                )
            for ((status, type) in expected) {
                val engine = MockEngine { respond("""{"error":{"message":"boom"}}""", HttpStatusCode.fromValue(status)) }
                val failure = runCatching { client(engine).systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()
                assertEquals(type, failure?.let { it::class }, "status $status")
            }
        }

    @Test
    fun rateLimitCarriesTheServersRetryAfter() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        """{"error":{"message":"slow down"}}""",
                        HttpStatusCode.TooManyRequests,
                        fixtureHeaders("Retry-After" to "7", "x-typesafe-request-id" to "req_123"),
                    )
                }

            val failure = runCatching { client(engine).systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()

            val rateLimit = assertIs<RateLimitError>(failure)
            assertEquals(7_000L, rateLimit.retryAfterMs)
            assertEquals("slow down", rateLimit.message)
            assertEquals("req_123", rateLimit.requestId)
            assertEquals(429, rateLimit.status)
        }

    @Test
    fun rateLimitHasNoRetryAfterWhenTheServerSentNone() =
        runTest {
            // ported from typesafe-sdk-js/test/reliability.test.ts — "is undefined when the server sent no
            // Retry-After". A sentinel 0 would read as a server instruction to retry immediately.
            val engine = MockEngine { respond("""{"error":{"message":"slow down"}}""", HttpStatusCode.TooManyRequests) }

            val failure = runCatching { client(engine).systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()

            assertNull(assertIs<RateLimitError>(failure).retryAfterMs)
        }

    @Test
    fun aJsonErrorBodyIsParsedWhateverTheContentTypeSays() =
        runTest {
            // ported from typesafe-sdk-js/test/errors.test.ts — "parses JSON even when content-type is missing".
            // The body is parsed from its bytes and content-type is never read, so a body claiming text/plain
            // still yields its message instead of the raw text.
            for (contentType in listOf(null, "text/plain")) {
                val engine =
                    MockEngine {
                        respond(
                            """{"error":{"message":"no content type"}}""",
                            HttpStatusCode.BadRequest,
                            contentType?.let { headersOf(HttpHeaders.ContentType, it) } ?: headersOf(),
                        )
                    }

                val failure = runCatching { client(engine).systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()

                val badRequest = assertIs<BadRequestError>(failure, "content-type: $contentType")
                assertEquals("no content type", badRequest.message)
                assertEquals(Json.parseToJsonElement("""{"error":{"message":"no content type"}}"""), badRequest.body)
            }
        }

    @Test
    fun mapsTransportFailuresToTheConnectionAndTimeoutBranch() =
        runTest {
            val connection =
                runCatching {
                    client(MockEngine { throw IOException("connect failed") }).systemOne("hello", noul("urgent", "?"))
                }.exceptionOrNull()
            assertIs<APIConnectionError>(connection)
            // APITimeoutError is a subclass, so the narrower assertion alone would pass with the two mapped
            // the wrong way round.
            assertTrue(connection !is APITimeoutError, "a connection failure stays a connection failure")
            assertIs<IOException>(connection.cause)
            assertIs<SystemOneError>(connection)

            val timeout =
                runCatching {
                    client(MockEngine { throw HttpRequestTimeoutException("https://api.typesafe.ai", 1_000) })
                        .systemOne("hello", noul("urgent", "?"))
                }.exceptionOrNull()
            assertIs<APITimeoutError>(timeout)
            assertIs<APIConnectionError>(timeout)
        }

    @Test
    fun aMalformedAnswerFailsWithTheFieldPath() =
        runTest {
            val engine = MockEngine { respond("""{"model":"jev-latest","answers":{"urgent":{"type":"noul"}}}""") }

            val failure = runCatching { client(engine).systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()

            val validation = assertIs<APIResponseValidationError>(failure)
            assertEquals("answers.urgent.noul", validation.fieldPath)
            assertEquals("answers.urgent.noul: expected a numeric field 'noul'", validation.message)
            assertEquals(200, validation.status)
        }

    @Test
    fun decodeRejectsAnAnswersFieldThatIsNotAnObject() =
        runTest {
            // ticket 22's survivors on `decodeSystemOneResponse`'s `as? JsonObject` guards: the key is right
            // and the JSON type is not.
            for (body in listOf(
                """{"model":"jev-latest","answers":[]}""",
                """{"model":"jev-latest","answers":"none"}""",
                """{"model":"jev-latest","answers":5}""",
                """{"model":"jev-latest","answers":null}""",
                """{"model":"jev-latest"}""",
            )) {
                val engine = MockEngine { respond(body) }

                val failure = runCatching { client(engine).systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()

                val validation = assertIs<APIResponseValidationError>(failure, body)
                assertEquals("answers", validation.fieldPath)
                assertEquals("answers: expected an object field 'answers'", validation.message)
            }
        }

    @Test
    fun decodeTreatsAWrongJsonTypeOnModelAndUsageAsAbsent() =
        runTest {
            // ticket 22's survivors on the `as? JsonPrimitive` / `as? JsonObject` guards behind the response
            // fields: a non-string model and non-numeric counters read as absent rather than failing the call.
            val engine =
                MockEngine {
                    respond(
                        """{"model":7,"answers":{"urgent":{"type":"noul","noul":0.5}},"usage":{"input_tokens":"12"}}""",
                    )
                }
            val client = client(engine)

            val response = client.systemOne("hello", noul("urgent", "?"))

            assertNull(response.model, "a non-string model is absent, not a failure")
            assertEquals(Usage(null, null), response.usage, "a stringified token counter decodes as absent")
            client.close()

            val structuredEngine =
                MockEngine {
                    respond("""{"model":{},"answers":{},"usage":{"input_tokens":{},"output_tokens":true}}""")
                }
            val structured = client(structuredEngine)

            val structuredResponse = structured.systemOne("hello", noul("urgent", "?"))

            assertNull(structuredResponse.model, "a structured model is absent, not a failure")
            assertEquals(Usage(null, null), structuredResponse.usage, "a structured or boolean counter decodes as absent")
            structured.close()
        }

    @Test
    fun usageIsNullWhenTheServerOmitsIt() =
        runTest {
            val fixture = loadConformanceCases().first { it.id == "response-usage-absent" }
            val engine = MockEngine { respond(fixture.responses.single().body, HttpStatusCode.OK, fixtureHeaders(fixture)) }

            val response = client(engine).systemOne("hello", noul("urgent", "?"))

            assertNull(response.usage)
        }

    @Test
    fun validationRejectsTheTwoLocalCasesBeforeTheNetwork() =
        runTest {
            val engine = MockEngine { respond("""{"model":"jev-latest","answers":{}}""") }
            val client = client(engine)

            assertIs<IllegalArgumentException>(
                runCatching { client.systemOne("hello") }.exceptionOrNull(),
                "a call with no questions is rejected locally",
            )
            assertIs<IllegalArgumentException>(
                runCatching { client.systemOne("hello", score("impact", "How bad?", listOf("only"))) }.exceptionOrNull(),
                "a one-level score is rejected locally",
            )
            assertEquals(0, engine.requestHistory.size)
            client.close()
        }

    @Test
    fun modelsListTakesTheSamePerCallTimeoutAndRetryOverridesAsSystemOne() =
        runTest {
            val engine = MockEngine { respond("""{"models":[]}""", HttpStatusCode.ServiceUnavailable) }
            val client = client(engine, retry = RetryPolicy(maxRetries = 0))

            runCatching {
                client.models.list(retry = RetryPolicy(maxRetries = 2), timeout = 250.milliseconds)
            }

            assertEquals(3, engine.requestHistory.size, "the per-call policy wins over the client default")
            val first = engine.requestHistory.first()
            assertEquals(250L, first.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
            client.close()
        }

    @Test
    fun modelsListWithNoOverridesInheritsTheClientsPolicy() =
        runTest {
            val engine = MockEngine { respond("""{"models":[]}""", HttpStatusCode.ServiceUnavailable) }
            val client = client(engine, retry = RetryPolicy(maxRetries = 2))

            runCatching { client.models.list() }

            assertEquals(3, engine.requestHistory.size, "a null per-call policy inherits the client's")
            client.close()
        }

    @Test
    fun modelsListReturnsTheCardsAndIgnoresExtraFields() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        """{"models":[{"name":"jev-latest","description":"flagship","release_date":"2026-01-01","tags":["a"]}]}""",
                    )
                }
            val client = client(engine)

            val cards = client.models.list()

            assertEquals(listOf(ModelCard("jev-latest", "flagship", "2026-01-01")), cards)
            val request = engine.requestHistory.single()
            assertEquals("https://api.typesafe.ai/v1/models", request.url.toString())
            assertNull(request.headers[CONTENT_TYPE_HEADER], "a GET carries no body and no content type")
            client.close()
        }

    @Test
    fun modelsListNamesTheEndpointWhenTheShapeIsWrong() =
        runTest {
            for (body in listOf(
                """{"data":[]}""",
                "[]",
                "not json",
                "",
                "{\"models\":5}",
                "{\"models\":\"x\"}",
                "{\"models\":{}}",
                "{\"models\":null}",
                "{}",
                "\"x\"",
                "5",
                "null",
            )) {
                val engine = MockEngine { respond(body) }

                val failure = runCatching { client(engine).models.list() }.exceptionOrNull()

                val validation = assertIs<APIResponseValidationError>(failure, "body: $body")
                assertEquals("models", validation.fieldPath, "body: $body")
                assertTrue(validation.message!!.contains("GET /v1/models"), validation.message)
                assertTrue(validation.message!!.contains("expected { models: [...] }"), validation.message)
            }
        }

    @Test
    fun modelsListRejectsAnEntryThatIsNotACardAtItsOwnPosition() =
        runTest {
            // ticket 22's `decodeModelCard` / `ModelsApi.list` survivors: an entry that is not an object, one
            // with no `name`, and one whose `name` is not a string all fail at that entry's own index, so a
            // catalogue that is half junk does not silently drop or renumber the rest.
            val cases =
                mapOf(
                    """{"models":[5]}""" to "models.0.name",
                    """{"models":["x"]}""" to "models.0.name",
                    """{"models":[[]]}""" to "models.0.name",
                    """{"models":[null]}""" to "models.0.name",
                    """{"models":[{}]}""" to "models.0.name",
                    """{"models":[{"name":5}]}""" to "models.0.name",
                    """{"models":[{"name":null}]}""" to "models.0.name",
                    """{"models":[{"name":["a"]}]}""" to "models.0.name",
                    """{"models":[{"name":"a"},5]}""" to "models.1.name",
                    """{"models":[5,{"name":"a"}]}""" to "models.0.name",
                )

            for ((body, field) in cases) {
                val engine = MockEngine { respond(body) }

                val failure = runCatching { client(engine).models.list() }.exceptionOrNull()

                val validation = assertIs<APIResponseValidationError>(failure, body)
                assertEquals(field, validation.fieldPath, body)
                assertEquals("GET /v1/models: $field: expected a string field 'name'", validation.message, body)
                assertEquals(200, validation.status, body)
            }
        }

    @Test
    fun modelsListDropsAnOptionalFieldOfTheWrongTypeRatherThanCoercingIt() =
        runTest {
            // ticket 22's four `stringFieldOrNull` survivors: `description` and `release_date` are optional, so a
            // value that is not a string reads as absent instead of being stringified or failing the catalogue.
            val bodies =
                listOf(
                    """{"models":[{"name":"a","description":5,"release_date":true}]}""",
                    """{"models":[{"name":"a","description":{},"release_date":[]}]}""",
                    """{"models":[{"name":"a","description":null,"release_date":null}]}""",
                )

            for (body in bodies) {
                val engine = MockEngine { respond(body) }

                assertEquals(listOf(ModelCard("a", null, null)), client(engine).models.list(), body)
            }
        }

    @Test
    fun systemOneRejectsATopLevelBodyThatIsNotAnObject() =
        runTest {
            // ticket 22's `decodeSystemOneResponse` survivors on the first `as? JsonObject`: a top-level array,
            // number, string or null is a validation failure with no field to name, never a ClassCastException.
            for (body in listOf("[]", "5", "\"x\"", "null", "not json", "")) {
                val engine = MockEngine { respond(body) }

                val failure = runCatching { client(engine).systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()

                val validation = assertIs<APIResponseValidationError>(failure, body)
                assertNull(validation.fieldPath, body)
                assertEquals("expected a JSON object response body", validation.message, body)
                assertEquals(200, validation.status, body)
            }
        }

    @Test
    fun systemOneToleratesAMalformedUsageAndModelWithoutFailing() =
        runTest {
            // ticket 22's `intOrNull` survivor and the `usage as? JsonObject` guard: `usage` and `model` are
            // optional response fields, so a wrong JSON type reads as absent while a real integer is kept.
            val usages =
                mapOf(
                    """{"answers":{},"usage":5}""" to null,
                    """{"answers":{},"usage":[]}""" to null,
                    """{"answers":{},"usage":null}""" to null,
                    """{"answers":{},"usage":{}}""" to Usage(null, null),
                    """{"answers":{},"usage":{"billing_units":9}}""" to Usage(null, null),
                    """{"answers":{},"usage":{"input_tokens":3.5}}""" to Usage(null, null),
                    """{"answers":{},"usage":{"input_tokens":3.0}}""" to Usage(null, null),
                    """{"answers":{},"usage":{"input_tokens":true}}""" to Usage(null, null),
                    """{"answers":{},"usage":{"input_tokens":null}}""" to Usage(null, null),
                    """{"answers":{},"usage":{"input_tokens":99999999999}}""" to Usage(null, null),
                    """{"answers":{},"usage":{"input_tokens":-1}}""" to Usage(-1, null),
                    """{"answers":{},"usage":{"input_tokens":12,"output_tokens":3}}""" to Usage(12, 3),
                )

            for ((body, expected) in usages) {
                val engine = MockEngine { respond(body) }

                assertEquals(expected, client(engine).systemOne("hello", noul("urgent", "?")).usage, body)
            }

            for (body in listOf("""{"answers":{},"model":null}""", """{"answers":{},"model":[]}""")) {
                val engine = MockEngine { respond(body) }

                assertNull(client(engine).systemOne("hello", noul("urgent", "?")).model, body)
            }
        }

    @Test
    fun anEmpty200BodyFailsLoudlyInsteadOfReadingAsAnEmptyResult() =
        runTest {
            // ported from typesafe-sdk-js/test/release-regressions.test.ts — "returns a null-body response
            // without trying to read a stream". Kotlin has no null body, so the analogue of that case is a 200
            // whose body is empty: it must neither hang nor invent an empty answer map, and it must name what
            // it was looking for.
            val engine = MockEngine { respond("", HttpStatusCode.OK) }

            val failure = runCatching { client(engine).systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()

            val validation = assertIs<APIResponseValidationError>(failure)
            assertEquals("expected a JSON object response body", validation.message)
            assertNull(validation.fieldPath, "there is no answer field to name")
            assertEquals(200, validation.status)

            val modelsFailure = runCatching { client(engine).models.list() }.exceptionOrNull()
            val modelsValidation = assertIs<APIResponseValidationError>(modelsFailure)
            assertEquals("models", modelsValidation.fieldPath)
            assertTrue(modelsValidation.message!!.contains("GET /v1/models"), modelsValidation.message)
        }

    @Test
    fun theApiKeyNeverAppearsInAStringRepresentation() =
        runTest {
            val engine = MockEngine { respond("""{"error":{"message":"invalid api key"}}""", HttpStatusCode.Unauthorized) }
            val client = client(engine)

            assertFalse("test-key" in SystemOneConfig(apiKey = "test-key").toString(), "the config's toString")
            assertFalse("test-key" in client.toString(), "the client's toString")

            val failure = runCatching { client.systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()
            assertIs<AuthenticationError>(failure)
            assertFalse("test-key" in failure.toString(), failure.toString())
            assertFalse("Bearer" in failure.toString(), failure.toString())
            client.close()
        }

    @Test
    fun closesIdempotentlyAndLeavesAnInjectedEngineAlive() =
        runTest {
            val engine = MockEngine { respond("""{"model":"jev-latest","answers":{"urgent":{"type":"noul","noul":0.5}}}""") }
            val first = client(engine)

            first.close()
            first.close()

            val second = client(engine)
            val response = second.systemOne("hello", noul("urgent", "?"))
            assertEquals(NoulAnswer(0.5), response.answers["urgent"])
            second.close()
        }

    @Test
    fun closesTheEngineItCreated() =
        runTest {
            val owned = RecordingEngine(MockEngine { respond("""{"model":"jev-latest","answers":{}}""") })

            val client = createClient(SystemOneConfig(apiKey = "test-key"), engineFactory = { owned })
            client.close()

            assertTrue(owned.closed, "an engine the client created is the client's to close")
        }

    @Test
    fun aBlankApiKeyIsRejectedBeforeAnyRequest() =
        runTest {
            // A blank key is a caller error, caught when the client is built rather than as a 401 later.
            val failure =
                runCatching {
                    createClient(SystemOneConfig(apiKey = "   ", engine = MockEngine { respond("""{"model":"x","answers":{}}""") }))
                }.exceptionOrNull()

            assertIs<IllegalArgumentException>(failure)
        }

    @Test
    fun resolvedSettingsReportWhatTookEffectAfterTheProviderDefaults() =
        runTest {
            // ported from typesafe-sdk-js/test/reliability.test.ts — "defaults to the SDK policy and exposes the
            // resolved policy on the client". Both sources are observable: the provider, then the explicit value.
            val engine = MockEngine { respond("""{"models":[]}""") }

            val defaults = createClient(SystemOneConfig(apiKey = "test-key", engine = engine))
            assertEquals(SystemOneProvider.TypeSafe.baseUrl, defaults.baseUrl)
            assertEquals(SystemOneProvider.TypeSafe.defaultModel, defaults.defaultModel)
            assertEquals(LogLevel.Off, defaults.logLevel)

            val explicit =
                createClient(
                    SystemOneConfig(
                        apiKey = "test-key",
                        engine = engine,
                        baseUrl = "https://explicit.test",
                        defaultModel = "jev-explicit",
                        logLevel = LogLevel.Error,
                    ),
                )
            assertEquals("https://explicit.test", explicit.baseUrl)
            assertEquals("jev-explicit", explicit.defaultModel)
            assertEquals(LogLevel.Error, explicit.logLevel)
        }

    @Test
    fun resolvedRetryTimeoutAndHeadersReportTheEffectiveValues() =
        runTest {
            val engine = MockEngine { respond("""{"models":[]}""") }

            val defaults = createClient(SystemOneConfig(apiKey = "test-key", engine = engine))
            assertEquals(RetryPolicy(), defaults.retry, "the resolved policy is the SDK default when none was passed")
            assertEquals(DEFAULT_TIMEOUT, defaults.timeout)
            assertEquals(emptyMap(), defaults.defaultHeaders)

            val configured =
                createClient(
                    SystemOneConfig(
                        apiKey = "test-key",
                        engine = engine,
                        retry = RetryPolicy(maxRetries = 7),
                        timeout = 250.milliseconds,
                        defaultHeaders = mapOf("X-Team" to "sdk"),
                    ),
                )
            assertEquals(RetryPolicy(maxRetries = 7), configured.retry)
            assertEquals(250.milliseconds, configured.timeout)
            assertEquals(mapOf("X-Team" to "sdk"), configured.defaultHeaders)
        }

    @Test
    fun threadsPerCallHeadersFromThePublicApiToTheWire() =
        runTest {
            // Both siblings take per-call headers (`extra_headers` in Python, `RequestOptions.headers` in
            // JavaScript). The transport has merged them since the start, but the public surface could not
            // reach it, so this pins the new plumbing on both resources and the names that still win.
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/v1/models" -> respond("""{"models":[]}""")
                        else ->
                            respond(
                                """{"model":"jev-latest","answers":{"urgent":{"type":"noul","noul":1.0}}}""",
                            )
                    }
                }
            val client = client(engine)
            val urgent = noul("urgent", "Does this convey urgency?")

            client.systemOne(
                "hello",
                urgent,
                headers = mapOf("X-Call" to "system-one", "Authorization" to "Bearer caller"),
            )
            client.models.list(headers = mapOf("X-Call" to "models", "x-typesafe-sdk" to "caller/9"))

            val systemOneRequest = engine.requestHistory[0]
            assertEquals("system-one", systemOneRequest.headers["X-Call"], "a per-call header reaches systemOne")
            assertEquals("Bearer test-key", systemOneRequest.headers[AUTHORIZATION_HEADER], "the SDK's key still wins")
            val listRequest = engine.requestHistory[1]
            assertEquals("models", listRequest.headers["X-Call"], "a per-call header reaches the catalogue")
            assertEquals("typesafe-sdk-kotlin/$SDK_VERSION", listRequest.headers[TYPESAFE_SDK_HEADER], "the SDK header still wins")
        }
}

/** An [HttpClientEngine] that only records whether it was closed, so ownership is observable. */
private class RecordingEngine(
    private val delegate: HttpClientEngine,
) : HttpClientEngine by delegate {
    var closed = false
        private set

    override fun close() {
        closed = true
    }
}

private fun client(
    engine: HttpClientEngine,
    retry: RetryPolicy = RetryPolicy(maxRetries = 0),
): SystemOneClient = createClient(SystemOneConfig(apiKey = "test-key", engine = engine, retry = retry))

private fun fixtureHeaders(vararg extra: Pair<String, String>): Headers =
    HeadersBuilder()
        .apply { extra.forEach { (name, value) -> append(name, value) } }
        .build()

private fun fixtureHeaders(fixture: FixtureCase): Headers =
    HeadersBuilder()
        .apply {
            fixture.responses
                .single()
                .headers
                .forEach { (name, value) -> append(name, value) }
        }.build()
