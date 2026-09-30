package com.sierranevadalabs.systemone.sdk

import android.os.Build
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createDefaultEngine(): HttpClientEngine = OkHttp.create()

// The integer API level, not the spoofable `Build.VERSION.RELEASE` string.
internal actual val runtimeIdentity: String = "android/${Build.VERSION.SDK_INT}"
