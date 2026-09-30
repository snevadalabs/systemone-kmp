package com.sierranevadalabs.systemone.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicyTest {
    @Test
    fun defaultsMatchTheSiblingSdks() {
        val policy = RetryPolicy()

        assertEquals(2, policy.maxRetries)
        assertEquals(500.milliseconds, policy.backoffInitial)
        assertEquals(5.seconds, policy.backoffMax)
        assertEquals(60.seconds, policy.maxRetryAfter)
        assertEquals(0.25, policy.backoffJitter)
        assertEquals(setOf(408, 429) + (500..599).toSet(), policy.httpStatuses)
        assertTrue(policy.respectRetryAfter)
        assertTrue(policy.apiConnectionError)
        assertTrue(policy.apiTimeoutError)
    }

    @Test
    fun rejectsValuesThatCannotProduceAUsableDelay() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxRetries = -1) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(backoffInitial = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy(backoffInitial = 1.seconds, backoffMax = 500.milliseconds)
        }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(backoffJitter = -0.1) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(backoffJitter = 1.5) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxRetryAfter = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(httpStatuses = setOf(1000)) }
    }
}
