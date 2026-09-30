package com.sierranevadalabs.systemone.sdk

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.uname
import platform.posix.utsname

internal actual fun createDefaultEngine(): HttpClientEngine = CIO.create()

internal actual val runtimeIdentity: String = "${osFamilyName()}/${kernelRelease()}"

@OptIn(ExperimentalForeignApi::class)
private fun kernelRelease(): String =
    memScoped {
        val buffer = alloc<utsname>()
        if (uname(buffer.ptr) != 0) "unknown" else buffer.release.toKString()
    }
