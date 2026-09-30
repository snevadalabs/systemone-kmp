package com.sierranevadalabs.systemone.sdk

/**
 * The identity this SDK advertises in `User-Agent` on every request, whatever the provider. It names the client
 * to the server; a provider's own fingerprint headers are separate and live on [SystemOneProvider].
 */
internal const val SDK_IDENTITY: String = "systemone-sdk-kotlin"

/** Kept in step with `gradle.properties` by the `checkVersion` task; it is the public release version. */
public const val SDK_VERSION: String = "0.1.0"

internal const val AUTHORIZATION_HEADER = "Authorization"
internal const val ACCEPT_HEADER = "Accept"
internal const val CONTENT_TYPE_HEADER = "Content-Type"
internal const val USER_AGENT_HEADER = "User-Agent"
internal const val REQUEST_ID_HEADER = "x-typesafe-request-id"
internal const val RETRY_AFTER_MS_HEADER = "retry-after-ms"

/**
 * Builds the outgoing header set: client defaults, then the caller's headers, last-wins and case-insensitive,
 * then the protected names this SDK owns. `null`-style deletion is not expressible through a `Map`, so a
 * caller cannot remove a protected header — only replace their own.
 *
 * The provider declares its own fingerprint headers ([SystemOneProvider.identityHeaders]) and the retry-count
 * header it wants ([SystemOneProvider.retryCountHeader]). A caller-supplied retry count is dropped here,
 * whatever its case, because only the retry loop sets that header.
 */
internal fun assembleHeaders(
    provider: SystemOneProvider,
    defaultHeaders: Map<String, String>,
    callerHeaders: Map<String, String>,
    apiKey: String,
    hasBody: Boolean = false,
): Map<String, String> {
    val byLowercaseName = LinkedHashMap<String, Pair<String, String>>()

    fun merge(
        name: String,
        value: String,
    ) {
        byLowercaseName[name.lowercase()] = name to value
    }

    defaultHeaders.forEach { (name, value) -> merge(name, value) }
    callerHeaders.forEach { (name, value) -> merge(name, value) }
    provider.retryCountHeader?.let { byLowercaseName.remove(it.lowercase()) }

    merge(AUTHORIZATION_HEADER, "Bearer $apiKey")
    merge(ACCEPT_HEADER, "application/json")
    if (hasBody) merge(CONTENT_TYPE_HEADER, "application/json")
    merge(USER_AGENT_HEADER, "$SDK_IDENTITY/$SDK_VERSION")
    provider.identityHeaders.forEach { (name, value) -> merge(name, value) }

    return byLowercaseName.values.associate { it.first to it.second }
}
