package com.sierranevadalabs.systemone.sdk

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createDefaultEngine(): HttpClientEngine = OkHttp.create()

internal actual val runtimeIdentity: String = "jvm/${System.getProperty("java.version") ?: "unknown"}"
