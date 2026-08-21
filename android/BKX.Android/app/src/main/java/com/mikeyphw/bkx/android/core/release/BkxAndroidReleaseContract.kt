
package com.mikeyphw.bkx.android.core.release

object BkxAndroidReleaseContract {
    const val REQUIRED_PROTOCOL_VERSION = 1
    const val RELEASE_SEAL_TASK = ":app:bkxAndroidReleaseSeal"
    val requiredDevtoolTasks: List<String> = listOf(
        ":app:testDebugUnitTest",
        ":app:lintDebug",
        ":app:connectedDebugAndroidTest",
        ":app:assembleDebug",
        ":app:assembleRelease",
        ":app:bkxAndroidReleaseSeal",
    )
    val requiredAdversarialCases: List<String> = listOf(
        "root denied with local fallback",
        "root-required mode without grant",
        "All files access denial",
        "Termux private path access through root",
        "SAF fallback grant loss",
        "malformed bridge JSON",
        "restore confirmation bypass",
    )
}
