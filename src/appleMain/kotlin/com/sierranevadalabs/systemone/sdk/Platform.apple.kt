package com.sierranevadalabs.systemone.sdk

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo

internal actual fun createDefaultEngine(): HttpClientEngine = Darwin.create()

internal actual val runtimeIdentity: String = "${osFamilyName()}/${appleOsVersion()}"

// `operatingSystemVersion` rather than `operatingSystemVersionString`, which Apple does not guarantee parseable.
@OptIn(ExperimentalForeignApi::class)
private fun appleOsVersion(): String = NSProcessInfo.processInfo.operatingSystemVersion.useContents { "$majorVersion.$minorVersion" }
