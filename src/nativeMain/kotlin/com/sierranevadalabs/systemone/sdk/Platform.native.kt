package com.sierranevadalabs.systemone.sdk

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

/** Shared by the Apple and Linux actuals of [runtimeIdentity]. */
@OptIn(ExperimentalNativeApi::class)
internal fun osFamilyName(): String =
    when (Platform.osFamily) {
        OsFamily.MACOSX -> "macos"
        else -> Platform.osFamily.name.lowercase()
    }
