package com.sierranevadalabs.systemone.sdk.conformance

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Every committed `conformance/` case, replayed through the engine seam. */
class ConformanceFixturesTest {
    @Test
    fun everyCaseReplaysThroughTheEngineSeam() =
        runTest {
            val cases = loadConformanceCases()
            // The proof set is deliberately capped: fixture sets rot once they grow past what anyone reads.
            assertEquals(16, cases.size, "the proof set is sixteen cases")
            cases.forEach { assertConformanceCase(it) }
        }

    @Test
    fun aCaseWhoseExpectationDoesNotMatchItsPayloadFails() =
        runTest {
            val case = loadConformanceCases().first { it.expect["answers"] != null }
            val broken =
                case.copy(
                    expect = JsonObject(case.expect + ("answers" to buildJsonObject { put("nope", JsonPrimitive("noul")) })),
                )
            assertFails { assertConformanceCase(broken) }
        }
}
