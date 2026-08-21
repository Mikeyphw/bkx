package com.mikeyphw.bkx.android.core.release

object BkxAndroidReleaseContract {
    const val REQUIRED_PROTOCOL_VERSION = 1
    const val RELEASE_VERSION = "1.0.0"
    const val RELEASE_VERSION_CODE = 10000
    const val RELEASE_SEAL_TASK = ":app:bkxAndroidReleaseSeal"

    val requiredDevtoolTasks: List<String> = listOf(
        ":app:verifyBkxAndroidSdk36DependencyLane",
        ":app:verifyBkxEmbeddedEngineContract",
        ":app:verifyBkxRootStorageContract",
        ":app:verifyBkxProductUiContract",
        ":app:verifyBkxRuntimeHardeningContract",
        ":app:verifyBkxRestoreSafetySeal",
        ":app:verifyBkxPerformanceAndProgressContract",
        ":app:verifyBkxLargeTreePerformanceSeal",
        ":app:verifyBkxStorageCompletenessContract",
        ":app:verifyBkxAccessibilityVisualContract",
        ":app:verifyBkxUxHardeningContract",
        ":app:verifyBkxEmbeddedParitySeal",
        ":app:verifyBkxReleaseHygiene",
        ":app:verifyBkxDeviceQualificationContract",
        ":app:verifyBkxFinalReleaseContract",
        ":app:testDebugUnitTest",
        ":app:lintDebug",
        ":app:connectedDebugAndroidTest",
        ":app:assembleDebug",
        ":app:assembleDebugAndroidTest",
        ":app:assembleRelease",
        ":app:bkxAndroidReleaseSeal",
    )

    val requiredAdversarialCases: List<String> = listOf(
        "root denied with local fallback",
        "root-required mode without grant",
        "root policy changed while another request is active",
        "All files access denial",
        "Termux private path access through root",
        "SAF fallback grant loss",
        "provider tree entry-count and traversal bounds",
        "removable storage discovery",
        "archive link and special-entry classification",
        "malformed bridge JSON",
        "cooperative cancellation during long filesystem work",
        "process death before final result publication",
        "fresh restore destination already exists",
        "existing restore destination is missing",
        "protected Android restore root",
        "root-sensitive restore confirmation bypass",
        "archive traversal or link member",
    )
}
