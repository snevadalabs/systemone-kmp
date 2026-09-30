package com.sierranevadalabs.systemone.sdk

/** The header TypeSafe's deployment reads as the client fingerprint. */
internal const val TYPESAFE_SDK_HEADER: String = "X-TypeSafe-SDK"

/** The header TypeSafe's deployment reads as the client runtime. */
internal const val TYPESAFE_RUNTIME_HEADER: String = "X-TypeSafe-Runtime"

/** The header TypeSafe's deployment reads on a retry. */
internal const val TYPESAFE_RETRY_COUNT_HEADER: String = "X-TypeSafe-Retry-Count"

/** This SDK's value for [TYPESAFE_SDK_HEADER]. The sibling SDKs send `typesafe-sdk/<version>`. */
private const val TYPESAFE_SDK_NAME: String = "typesafe-sdk-kotlin"

/**
 * One System One deployment: where it lives, which route serves it, the model a call uses when it names none,
 * and the fingerprint it expects.
 *
 * System One is the same wire contract everywhere: the same request body, the same `noul` / `choice` / `score`
 * answers, the same errors. A provider carries the parts that are not that contract. Every value is explicit;
 * nothing is derived from a name.
 *
 * The presets cover the two hosted deployments. A caller running a compatible server writes one value:
 *
 * ```kotlin
 * val ollama = SystemOneProvider(baseUrl = "http://localhost:11434", defaultModel = "nimble")
 * ```
 *
 * @property baseUrl the API origin.
 * @property defaultModel the model a call uses when it names none.
 * @property pathPrefix the route prefix in front of `/v1/systemone` and `/v1/models`. Empty for TypeSafe,
 *   `/decisions` for Liquid. It lives here rather than inside [baseUrl] so that a caller who points `baseUrl`
 *   at a proxy keeps the route.
 * @property identityHeaders fingerprint headers sent on every request, or empty for none.
 * @property retryCountHeader the header sent on a retry, or `null` for none.
 */
public data class SystemOneProvider(
    public val baseUrl: String,
    public val defaultModel: String,
    public val pathPrefix: String = "",
    public val identityHeaders: Map<String, String> = emptyMap(),
    public val retryCountHeader: String? = null,
) {
    /** The deployments this SDK ships a preset for. */
    public companion object {
        /**
         * TypeSafe AI's hosted deployment, and the SDK's default. Sends the `X-TypeSafe-*` fingerprint, so it
         * behaves exactly as the sibling SDKs do.
         */
        public val TypeSafe: SystemOneProvider =
            SystemOneProvider(
                baseUrl = "https://api.typesafe.ai",
                defaultModel = "jev-latest",
                identityHeaders =
                    mapOf(
                        TYPESAFE_SDK_HEADER to "$TYPESAFE_SDK_NAME/$SDK_VERSION",
                        TYPESAFE_RUNTIME_HEADER to runtimeIdentity,
                    ),
                retryCountHeader = TYPESAFE_RETRY_COUNT_HEADER,
            )

        /**
         * Liquid AI's decision-model deployment: `d1:free` behind the `/decisions` route. It sends no
         * fingerprint, so it receives none of TypeSafe's headers.
         */
        public val Liquid: SystemOneProvider =
            SystemOneProvider(
                baseUrl = "https://api.liquid.ai",
                defaultModel = "d1:free",
                pathPrefix = "/decisions",
            )
    }
}
