package com.sierranevadalabs.systemone.sdk

import io.ktor.client.engine.HttpClientEngine

/** The engine a caller gets when they configure none: OkHttp, Darwin or CIO depending on the target. */
internal expect fun createDefaultEngine(): HttpClientEngine

/** `<platform>/<version>`, for `X-TypeSafe-Runtime`. Computed once per process. */
internal expect val runtimeIdentity: String
