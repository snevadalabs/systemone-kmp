package com.sierranevadalabs.systemone.sdk

import com.sierranevadalabs.systemone.sdk.errors.AuthenticationError
import com.sierranevadalabs.systemone.sdk.errors.BadRequestError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tier 3: the live API, opt-in twice over. The suite runs only when the Gradle property `typesafe.live` is
 * `true` **and** `TYPESAFE_API_KEY` is in the environment — the presence of the key alone is deliberately not
 * enough, because that is how the Python suite once fired ~29 live calls from a default test run. A default
 * `./gradlew check` cannot reach the network here. The key is read by this test, not by the SDK.
 *
 * Run it with `TYPESAFE_API_KEY=… ./gradlew jvmTest -Ptypesafe.live=true`. It runs locally only: no CI
 * workflow touches the paid API, so a live call happens only on this explicit request.
 * Assertions are loose on purpose: a live model is not a deterministic function.
 */
class LiveApiTest {
    @Test
    fun modelsListReturnsCards() =
        runBlocking {
            assumeLive()
            liveClient().use { client ->
                val cards = client.models.list()

                assertTrue(cards.isNotEmpty(), "the model catalogue must not be empty")
                assertTrue(cards.all { it.name.isNotBlank() }, "every card names a model: $cards")
            }
        }

    @Test
    fun aMixedThreePrimitiveCallReturnsCalibratedAnswers() =
        runBlocking {
            assumeLive()
            liveClient().use { client ->
                val urgent = noul("urgent", "Does this convey urgency?")
                val category = choice("category", "What is this about?", mapOf("billing" to null, "technical" to null))
                val impact = score("impact", "How bad is it?", listOf("can wait", "this week", "today"))

                val response = client.systemOne("Help! My payouts have been failing for 3 days.", urgent, category, impact)

                assertTrue(response[urgent].noul in 0.0..1.0, "noul out of range: ${response[urgent]}")
                val picked = response[category]
                assertTrue(picked.choice in setOf("billing", "technical"), "choice not one of the options: $picked")
                assertEquals(1.0, picked.probabilities.values.sum(), 0.1, "choice probabilities must sum to one")
                assertTrue(picked.confidence in 0.0..1.0, "confidence out of range: $picked")
                val rated = response[impact]
                assertTrue(rated.score in 0.0..2.0, "score outside the levels: $rated")
                assertEquals(1.0, rated.probabilities.values.sum(), 0.1, "score probabilities must sum to one")
                assertEquals(
                    listOf("can wait", "this week", "today"),
                    rated.legend
                        .toSortedMap()
                        .values
                        .map { (it as JsonPrimitive).content },
                    "the legend must echo the request's levels",
                )
            }
        }

    @Test
    fun aBadKeyRaisesTheAuthenticationError() {
        runBlocking {
            assumeLive()
            val failure =
                runCatching {
                    SystemOneClient(SystemOneConfig(apiKey = "definitely-not-a-real-key"))
                        .use { it.systemOne("hello", noul("urgent", "?")) }
                }.exceptionOrNull()

            assertIs<AuthenticationError>(failure)
        }
    }

    @Test
    fun anUnknownModelRaisesABadRequestNamingIt() =
        runBlocking {
            assumeLive()
            val model = "definitely-not-a-real-model"

            val failure =
                runCatching {
                    liveClient().use { it.systemOne("hello", noul("urgent", "?"), model = model) }
                }.exceptionOrNull()

            val badRequest = assertIs<BadRequestError>(failure)
            assertTrue(badRequest.message!!.contains(model), "the error must name the model: ${badRequest.message}")
        }
}

/**
 * Skips the test unless the live suite was explicitly requested. The Gradle property is the request; a missing
 * key with the request on is an error rather than a skip, so an opt-in that cannot run says so.
 */
private fun assumeLive() {
    Assume.assumeTrue("typesafe.live is not true", System.getProperty("typesafe.live") == "true")
    check(!System.getenv(LIVE_API_KEY_ENV).isNullOrBlank()) { "typesafe.live=true requires $LIVE_API_KEY_ENV in the environment" }
}

/** The variable this test reads. The SDK itself reads no environment. */
private const val LIVE_API_KEY_ENV = "TYPESAFE_API_KEY"

/** Builds a client against the live deployment, with the key this test read from the environment. */
private fun liveClient(): SystemOneClient = SystemOneClient(SystemOneConfig(apiKey = System.getenv(LIVE_API_KEY_ENV)))
