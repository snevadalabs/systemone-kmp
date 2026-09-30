package com.sierranevadalabs.systemone.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Config resolution: an explicit value beats the provider's own, the provider supplies the rest, and the SDK
 * reads no process environment.
 */
class ConfigTest {
    private val provider = SystemOneProvider.TypeSafe

    @Test
    fun explicitValuesBeatTheProviderDefaults() {
        val resolved =
            ResolvedConfig(
                SystemOneConfig(
                    apiKey = "key",
                    baseUrl = "https://explicit.test",
                    defaultModel = "jev-explicit",
                    logLevel = LogLevel.Error,
                ),
            )

        assertEquals("key", resolved.apiKey)
        assertEquals("https://explicit.test", resolved.baseUrl)
        assertEquals("jev-explicit", resolved.defaultModel)
        assertEquals(LogLevel.Error, resolved.logLevel)
    }

    @Test
    fun theProviderSuppliesEveryUnsetValue() {
        val resolved = ResolvedConfig(SystemOneConfig(apiKey = "key"))

        assertEquals(provider.baseUrl, resolved.baseUrl)
        assertEquals(provider.defaultModel, resolved.defaultModel)
        assertEquals(LogLevel.Off, resolved.logLevel)
        assertEquals(10.seconds, resolved.timeout)
        assertEquals("/v1/systemone", resolved.systemOnePath)
        assertEquals("/v1/models", resolved.modelsPath)
    }

    @Test
    fun aBlankApiKeyIsRejected() {
        assertFailsWith<IllegalArgumentException> { ResolvedConfig(SystemOneConfig(apiKey = "   ")) }
    }

    @Test
    fun aNonPositiveTimeoutIsRejected() {
        assertFailsWith<IllegalArgumentException> { ResolvedConfig(SystemOneConfig(apiKey = "key", timeout = Duration.ZERO)) }
        assertFailsWith<IllegalArgumentException> { ResolvedConfig(SystemOneConfig(apiKey = "key", timeout = (-1).seconds)) }
    }
}
