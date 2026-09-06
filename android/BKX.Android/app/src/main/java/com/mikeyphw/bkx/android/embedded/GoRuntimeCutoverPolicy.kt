package com.mikeyphw.bkx.android.embedded

import android.os.Build

/** GO13 production cutover policy for the generated Android Go runtime. */
object GoRuntimeCutoverPolicy {
    const val ENABLED_BY_DEFAULT: Boolean = true
    const val REQUIRE_GO_ON_ARM64: Boolean = true
    const val TARGET_ABI: String = "arm64-v8a"

    fun isArm64Device(): Boolean = Build.SUPPORTED_ABIS.any { it == TARGET_ABI }

    fun requireGoOnCurrentDevice(): Boolean =
        ENABLED_BY_DEFAULT && REQUIRE_GO_ON_ARM64 && isArm64Device()
}
