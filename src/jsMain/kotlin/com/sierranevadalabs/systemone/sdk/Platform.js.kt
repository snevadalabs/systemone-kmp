package com.sierranevadalabs.systemone.sdk

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

internal actual fun createDefaultEngine(): HttpClientEngine = Js.create()

// Node exposes `process.version`, a browser exposes a user agent and no version. The shape stays
// `<platform>/<version>` like every other target: whichever identifier the runtime itself offers.
internal actual val runtimeIdentity: String =
    if (isNode()) {
        "js/node-${js("process.version")}"
    } else {
        "js/${js("typeof navigator !== 'undefined' && navigator.userAgent ? navigator.userAgent : 'unknown'")}"
    }

// `typeof` never throws on an absent global, so this is safe in a browser and in a non-Node worker.
private fun isNode(): Boolean = js("typeof process !== 'undefined' && typeof process.versions !== 'undefined'") as Boolean
