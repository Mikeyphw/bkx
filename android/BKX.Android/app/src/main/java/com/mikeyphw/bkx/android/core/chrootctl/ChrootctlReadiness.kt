package com.mikeyphw.bkx.android.core.chrootctl

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ChrootctlReadinessCode {
    Ready,
    RootDisabled,
    RootUnavailable,
    RootTransportUnavailable,
    ModuleNotInstalled,
    ModuleDisabled,
    ModulePackageSealInvalid,
    ModuleRuntimeSealInvalid,
    UnsupportedAbi,
    PublicLauncherMissing,
    BinaryNotExecutable,
    BinaryOwnerInvalid,
    UnsafeBinaryPermissions,
    UntrustedBinaryPath,
    BinaryUntrusted,
    BinaryVersionIncompatible,
    BinaryExecFailed,
    VersionProbeFailed,
    ProbeFailed,
}

@Serializable
data class ChrootctlBinaryCandidate(
    val path: String,
    val source: String,
    val exists: Boolean,
    val executable: Boolean,
    val trusted: Boolean,
    val compatible: Boolean,
    val exitCode: Int? = null,
    val detail: String,
    val uid: Int? = null,
    val gid: Int? = null,
    val mode: Int? = null,
    val canonicalPath: String? = null,
)

@Serializable
data class ChrootctlReadiness(
    val code: ChrootctlReadinessCode,
    val detail: String,
    val selectedPath: String? = null,
    val selectedSource: String? = null,
    val activeModulePath: String? = null,
    val moduleInstalled: Boolean = false,
    val moduleDisabled: Boolean = false,
    val packageSealReady: Boolean = false,
    val runtimeSealReady: Boolean = false,
    val pendingUpdate: Boolean = false,
    val pendingUpdatePaths: List<String> = emptyList(),
    val rootProvider: String = "unknown",
    val abi: String = "unknown",
    val moduleVersion: String? = null,
    val moduleVersionCode: Int? = null,
    val backendVersion: String? = null,
    val bridgeProtocolVersion: Int? = null,
    val operations: List<String> = emptyList(),
    val candidates: List<ChrootctlBinaryCandidate> = emptyList(),
) {
    val ready: Boolean get() = code == ChrootctlReadinessCode.Ready

    companion object {
        fun rootDisabled(): ChrootctlReadiness = ChrootctlReadiness(
            code = ChrootctlReadinessCode.RootDisabled,
            detail = "BKX root access is disabled in settings. Enable root before using chrootctl integration.",
        )

        fun rootUnavailable(): ChrootctlReadiness = ChrootctlReadiness(
            code = ChrootctlReadinessCode.RootUnavailable,
            detail = "Root authorization is unavailable. Grant BKX root access before using chrootctl integration.",
        )

        fun rootTransportUnavailable(reason: String): ChrootctlReadiness = ChrootctlReadiness(
            code = ChrootctlReadinessCode.RootTransportUnavailable,
            detail = reason.ifBlank { "BKX could not establish its UID-0 RootService transport." },
        )
    }
}

interface ChrootctlReadinessPort {
    suspend fun probe(): ChrootctlReadiness
}

object ChrootctlReadinessCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(value: ChrootctlReadiness): String = json.encodeToString(value)
    fun decode(raw: String): ChrootctlReadiness = json.decodeFromString(raw)
}
