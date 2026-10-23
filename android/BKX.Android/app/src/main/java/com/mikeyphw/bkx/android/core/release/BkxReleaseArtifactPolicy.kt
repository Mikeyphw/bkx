package com.mikeyphw.bkx.android.core.release

object BkxReleaseArtifactPolicy {
    const val REQUIRED_ABI = "arm64-v8a"
    const val REQUIRED_MIN_NATIVE_PAGE_SIZE = 16_384
    const val REQUIRED_BKX_RUNTIME = "libbkxgo.so"
    const val REQUIRED_RCLONE_RUNTIME = "libbkxrclone_exec.so"
    const val RCLONE_PROVENANCE = "assets/bkx-rclone-runtime.properties"
    val requiredEmbeddedMembers = setOf("lib/arm64-v8a/libbkxgo.so", "lib/arm64-v8a/libbkxrclone_exec.so", RCLONE_PROVENANCE)
    val requiredLiteMembers = setOf("lib/arm64-v8a/libbkxgo.so")
    val forbiddenLiteMembers = setOf("lib/arm64-v8a/libbkxrclone_exec.so", RCLONE_PROVENANCE)

    fun editionMembersAreValid(edition: String, members: Set<String>): Boolean = when (edition) {
        "embedded" -> members.containsAll(requiredEmbeddedMembers)
        "lite" -> members.containsAll(requiredLiteMembers) && forbiddenLiteMembers.none { it in members }
        else -> false
    }
    const val UNKNOWN_COMMIT = "dev"

    val releaseArtifactTasks: List<String> = listOf(
        ":app:assembleEmbeddedRelease",
        ":app:assembleLiteRelease",
        ":app:lintEmbeddedRelease",
        ":app:lintLiteRelease",
        ":app:verifyBkxEditionApkContents",
        ":app:verifyBkxReleaseMergedManifestContract",
        ":app:verifyBkx16KbNativePageSizeContract",
        ":app:verifyBkxReleasePackagingSigningNativeRuntimeSeal",
    )

    val exactReleaseQualificationExtras: Map<String, String> = mapOf(
        "bkxExactReleaseArtifact" to "true",
        "bkxDeviceQualification" to "true",
        "bkxVisualQualification" to "true",
    )

    fun androidVersionCode(versionName: String): Int {
        val parts = versionName.split(".").map { part ->
            part.toIntOrNull() ?: error("BKX Android requires numeric major.minor.patch: $versionName")
        }
        require(parts.size == 3) { "BKX Android requires major.minor.patch: $versionName" }
        val (major, minor, patch) = parts
        require(major in 0..1999 && minor in 0..999 && patch in 0..999) {
            "BKX Android version outside versionCode lane: $versionName"
        }
        return major * 1_000_000 + minor * 1_000 + patch
    }

    fun isReleaseCommit(value: String): Boolean {
        val commit = value.trim()
        return commit.length in 7..64 && commit != UNKNOWN_COMMIT && commit != "unknown" &&
            commit.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }

    fun validateReleaseAbi(abis: Collection<String>): Boolean = abis.toSet() == setOf(REQUIRED_ABI)

    fun requiredMergedManifestMarkers(): List<String> = listOf(
        "android.permission.INTERNET",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
        "com.mikeyphw.bkx.android.app.service.BkxOperationForegroundService",
        "android:foregroundServiceType=\"dataSync\"",
        "android:allowBackup=\"false\"",
        "android:fullBackupContent=\"false\"",
    )
}
