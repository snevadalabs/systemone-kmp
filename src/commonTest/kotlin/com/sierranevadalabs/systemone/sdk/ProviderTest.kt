package com.sierranevadalabs.systemone.sdk

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The provider: what each deployment resolves, and what it puts on the wire.
 *
 * Resolution is exercised through [ResolvedConfig] and the wire through [createClient] over `MockEngine`, the
 * same two seams the rest of the config and client suites use.
 */
class ProviderTest {
    @Test
    fun liquidResolvesItsOwnOriginModelAndRoutes() {
        val resolved = ResolvedConfig(SystemOneConfig(apiKey = "key", provider = SystemOneProvider.Liquid))

        assertEquals("https://api.liquid.ai", resolved.baseUrl)
        assertEquals("d1:free", resolved.defaultModel)
        assertEquals("/decisions/v1/systemone", resolved.systemOnePath)
        assertEquals("/decisions/v1/models", resolved.modelsPath)
    }

    @Test
    fun typesafeRemainsTheDefaultProvider() {
        val resolved = ResolvedConfig(SystemOneConfig(apiKey = "k"))

        assertEquals("https://api.typesafe.ai", resolved.baseUrl)
        assertEquals("jev-latest", resolved.defaultModel)
        assertEquals("/v1/systemone", resolved.systemOnePath)
    }

    @Test
    fun aCustomProviderCarriesOnlyWhatItDeclares() {
        val provider = SystemOneProvider(baseUrl = "http://localhost:11434", defaultModel = "nimble")

        assertEquals("http://localhost:11434", provider.baseUrl)
        assertEquals("nimble", provider.defaultModel)
        assertEquals("", provider.pathPrefix)
        assertTrue(provider.identityHeaders.isEmpty(), "a provider with no fingerprint declares no headers")
        assertNull(provider.retryCountHeader)
    }

    @Test
    fun aCustomProviderIsResolvedLikeAnyOther() {
        val resolved =
            ResolvedConfig(
                SystemOneConfig(
                    apiKey = "key",
                    provider = SystemOneProvider(baseUrl = "http://localhost:11434", defaultModel = "nimble"),
                ),
            )

        assertEquals("http://localhost:11434", resolved.baseUrl)
        assertEquals("nimble", resolved.defaultModel)
        assertEquals("/v1/systemone", resolved.systemOnePath)
    }

    @Test
    fun aLiquidCallPostsToTheDecisionsRouteAndSendsNoTypeSafeFingerprint() =
        runTest {
            val engine = MockEngine { respond(okBody("d1:free"), HttpStatusCode.OK, jsonHeaders) }
            val client = liquidClient(engine)

            client.systemOne("hello", noul("q", "?"))
            client.close()

            val request = engine.requestHistory.single()
            assertEquals("https://api.liquid.ai/decisions/v1/systemone", request.url.toString())
            assertEquals("$SDK_IDENTITY/$SDK_VERSION", request.headers[USER_AGENT_HEADER])
            assertNull(request.headers[TYPESAFE_SDK_HEADER], "Liquid gets no TypeSafe fingerprint")
            assertNull(request.headers[TYPESAFE_RUNTIME_HEADER], "Liquid gets no TypeSafe runtime header")
        }

    @Test
    fun liquidListsModelsFromTheDecisionsCatalogue() =
        runTest {
            val engine = MockEngine { respond("""{"models":[]}""", HttpStatusCode.OK, jsonHeaders) }
            val client = liquidClient(engine)

            client.models.list()
            client.close()

            assertEquals(
                "https://api.liquid.ai/decisions/v1/models",
                engine.requestHistory
                    .single()
                    .url
                    .toString(),
            )
        }

    @Test
    fun typesafeKeepsItsFingerprintAndAlsoSendsAUserAgent() =
        runTest {
            val engine = MockEngine { respond(okBody("jev-latest"), HttpStatusCode.OK, jsonHeaders) }
            val client = createClient(SystemOneConfig(apiKey = "test-key", engine = engine))

            client.systemOne("hello", noul("q", "?"))
            client.close()

            val request = engine.requestHistory.single()
            assertEquals("$SDK_IDENTITY/$SDK_VERSION", request.headers[USER_AGENT_HEADER])
            assertEquals("typesafe-sdk-kotlin/$SDK_VERSION", request.headers[TYPESAFE_SDK_HEADER])
            assertTrue(
                request.headers[TYPESAFE_RUNTIME_HEADER]?.contains('/') == true,
                "the runtime header names a platform and version",
            )
        }

    @Test
    fun aCallerCannotReplaceTheUserAgent() =
        runTest {
            val engine = MockEngine { respond("""{"models":[]}""", HttpStatusCode.OK, jsonHeaders) }
            val client = createClient(SystemOneConfig(apiKey = "test-key", engine = engine))

            client.models.list(headers = mapOf("user-agent" to "caller/9"))
            client.close()

            val request = engine.requestHistory.single()
            assertEquals("$SDK_IDENTITY/$SDK_VERSION", request.headers[USER_AGENT_HEADER], "the SDK's User-Agent wins")
            assertEquals(1, request.headers.getAll(USER_AGENT_HEADER)?.size, "User-Agent must appear exactly once")
        }

    private fun liquidClient(engine: MockEngine): SystemOneClient =
        createClient(SystemOneConfig(provider = SystemOneProvider.Liquid, apiKey = "liquid_key", engine = engine))

    private fun okBody(model: String): String = """{"model":"$model","answers":{"q":{"type":"noul","noul":0.5}}}"""
}

private val jsonHeaders = headersOf("Content-Type", "application/json")
