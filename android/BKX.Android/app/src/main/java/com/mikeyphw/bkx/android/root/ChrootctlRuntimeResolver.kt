package com.mikeyphw.bkx.android.root

import android.os.Build
import android.system.Os
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlBinaryCandidate
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlReadiness
import com.mikeyphw.bkx.android.core.chrootctl.ChrootctlReadinessCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * BKX-side verifier for an installed chrootctl root module.
 *
 * Adapted from chrootctl's Android RuntimeResolver, but deliberately models BKX
 * as an external protocol consumer: the chrootctl companion application's
 * applicationId is not part of BKX compatibility. Module/runtime/package
 * identity, filesystem trust, protocol-v1 capabilities, and the absence of an
 * arbitrary-shell bridge are authoritative.
 *
 * Pending modules_update trees are diagnostics only and are never executable
 * candidates. All process invocation is a fixed absolute executable followed by
 * the fixed `app capabilities` subcommand; no shell or user-controlled argv is
 * accepted by this resolver.
 */
internal object ChrootctlRuntimeResolver {
    private val json = Json { ignoreUnknownKeys = true }
    private val dataRoot = File(File.separator, "data")
    private val adbRoot = File(dataRoot, "adb")
    private val ksuRoot = File(adbRoot, "ksu")
    private val activeModuleDir = File(adbRoot, "modules/chrootctl-magisk")
    private val pendingModuleDirs = listOf(
        File(adbRoot, "modules_update/chrootctl-magisk"),
        File(ksuRoot, "modules_update/chrootctl-magisk"),
    )
    private val publicLaunchers = listOf(
        File(adbRoot, "chrootctl/bin/chrootctl") to "public-launcher",
        File(File.separator, "system/bin/chrootctl") to "public-launcher",
        File(File.separator, "system/xbin/chrootctl") to "public-launcher",
    )
    private val moduleLauncher = File(activeModuleDir, "system/bin/chrootctl")

    fun resolve(): ChrootctlReadiness {
        val abi = currentDeviceAbi()
        val provider = detectRootProvider()
        val pendingPaths = pendingModuleDirs.filter(File::exists).map { it.path }
        val pending = pendingPaths.isNotEmpty()
        val moduleInstalled = activeModuleDir.isDirectory
        val moduleDisabled = moduleInstalled && (
            File(activeModuleDir, "disable").exists() || File(activeModuleDir, "remove").exists()
        )

        if (!moduleInstalled) {
            return report(
                code = ChrootctlReadinessCode.ModuleNotInstalled,
                detail = "The active chrootctl root module was not found at ${activeModuleDir.path}.",
                pending = pending,
                pendingPaths = pendingPaths,
                provider = provider,
                abi = abi,
            )
        }
        if (moduleDisabled) {
            return report(
                code = ChrootctlReadinessCode.ModuleDisabled,
                detail = "The active chrootctl module is disabled or pending removal. Enable it and reboot before BKX uses it.",
                installed = true,
                disabled = true,
                pending = pending,
                pendingPaths = pendingPaths,
                provider = provider,
                abi = abi,
            )
        }
        if (Build.SUPPORTED_ABIS.none { it == EXPECTED_ABI }) {
            return report(
                code = ChrootctlReadinessCode.UnsupportedAbi,
                detail = "The installed chrootctl native runtime requires $EXPECTED_ABI; device ABI is $abi.",
                installed = true,
                pending = pending,
                pendingPaths = pendingPaths,
                provider = provider,
                abi = abi,
            )
        }

        val moduleProp = readModuleProperties()
            ?: return report(
                code = ChrootctlReadinessCode.ModulePackageSealInvalid,
                detail = "chrootctl module.prop is missing, unsafe, or unreadable.",
                installed = true,
                pending = pending,
                pendingPaths = pendingPaths,
                provider = provider,
                abi = abi,
            )
        val moduleVersion = moduleProp["version"]?.takeIf(String::isNotBlank)
        val moduleVersionCode = moduleProp["versionCode"]?.toIntOrNull()
        if (moduleProp["id"] != MODULE_ID || moduleVersionCode == null || moduleVersionCode < MIN_MODULE_VERSION_CODE) {
            return report(
                code = ChrootctlReadinessCode.BinaryVersionIncompatible,
                detail = "The installed chrootctl module identity/version is incompatible with BKX (requires $MODULE_ID versionCode >= $MIN_MODULE_VERSION_CODE).",
                installed = true,
                pending = pending,
                pendingPaths = pendingPaths,
                provider = provider,
                abi = abi,
                moduleVersion = moduleVersion,
                moduleVersionCode = moduleVersionCode,
            )
        }

        val packageIdentity = validatePackageSeal(moduleVersion, moduleVersionCode)
            ?: return report(
                code = ChrootctlReadinessCode.ModulePackageSealInvalid,
                detail = "The active chrootctl module package inventory failed its sealed size/hash/executable contract.",
                installed = true,
                pending = pending,
                pendingPaths = pendingPaths,
                provider = provider,
                abi = abi,
                moduleVersion = moduleVersion,
                moduleVersionCode = moduleVersionCode,
            )

        if (!validateExternalCompatibility(packageIdentity)) {
            return report(
                code = ChrootctlReadinessCode.BinaryVersionIncompatible,
                detail = "The chrootctl compatibility manifest does not satisfy BKX's external protocol-v1 consumer contract.",
                installed = true,
                packageSeal = true,
                pending = pending,
                pendingPaths = pendingPaths,
                provider = provider,
                abi = abi,
                moduleVersion = moduleVersion,
                moduleVersionCode = moduleVersionCode,
                backendVersion = packageIdentity.backendVersion,
                bridgeProtocolVersion = packageIdentity.bridgeProtocolVersion,
            )
        }

        if (!validateNativeRuntime(packageIdentity)) {
            return report(
                code = ChrootctlReadinessCode.ModuleRuntimeSealInvalid,
                detail = "The active chrootctl native runtime inventory/ELF seal failed validation.",
                installed = true,
                packageSeal = true,
                pending = pending,
                pendingPaths = pendingPaths,
                provider = provider,
                abi = abi,
                moduleVersion = moduleVersion,
                moduleVersionCode = moduleVersionCode,
                backendVersion = packageIdentity.backendVersion,
                bridgeProtocolVersion = packageIdentity.bridgeProtocolVersion,
            )
        }

        val candidates = buildList {
            publicLaunchers.forEach { (path, source) -> add(Candidate(path, source, moduleInternal = false)) }
            add(Candidate(moduleLauncher, "module-launcher", moduleInternal = true))
        }
        val probes = mutableListOf<ChrootctlBinaryCandidate>()
        var selected: CapabilityProbe? = null
        var selectedCandidate: Candidate? = null

        for (candidate in candidates) {
            val trust = inspectCandidate(candidate)
            if (!trust.trusted) {
                probes += trust.candidate
                continue
            }
            val capability = probeCapabilities(candidate.path, packageIdentity)
            probes += trust.candidate.copy(
                compatible = capability.compatible,
                exitCode = capability.exitCode,
                detail = capability.detail,
            )
            if (capability.compatible) {
                selected = capability
                selectedCandidate = candidate
                break
            }
        }

        if (selected != null && selectedCandidate != null) {
            return ChrootctlReadiness(
                code = ChrootctlReadinessCode.Ready,
                detail = buildString {
                    append("Compatible chrootctl bridge ready at ${selectedCandidate.path.path}.")
                    if (pending) append(" A pending module update exists but is diagnostic-only until reboot.")
                },
                selectedPath = selectedCandidate.path.path,
                selectedSource = selectedCandidate.source,
                activeModulePath = activeModuleDir.path,
                moduleInstalled = true,
                packageSealReady = true,
                runtimeSealReady = true,
                pendingUpdate = pending,
                pendingUpdatePaths = pendingPaths,
                rootProvider = provider,
                abi = abi,
                moduleVersion = moduleVersion,
                moduleVersionCode = moduleVersionCode,
                backendVersion = selected.backendVersion,
                bridgeProtocolVersion = BRIDGE_PROTOCOL_VERSION,
                operations = selected.operations.sorted(),
                candidates = probes,
            )
        }

        val code = when {
            probes.any { it.exists && !it.executable } -> ChrootctlReadinessCode.BinaryNotExecutable
            probes.any { it.exists && !it.trusted && it.detail.startsWith("owner uid") } -> ChrootctlReadinessCode.BinaryOwnerInvalid
            probes.any { it.exists && !it.trusted && it.detail.startsWith("unsafe writable mode") } -> ChrootctlReadinessCode.UnsafeBinaryPermissions
            probes.any { it.exists && !it.trusted && it.detail.startsWith("canonical path mismatch") } -> ChrootctlReadinessCode.UntrustedBinaryPath
            probes.any { it.exists && !it.trusted } -> ChrootctlReadinessCode.BinaryUntrusted
            probes.any { it.trusted && !it.compatible && it.detail.startsWith("probe timed out") } -> ChrootctlReadinessCode.VersionProbeFailed
            probes.any { it.trusted && !it.compatible && it.detail.startsWith("execution failed") } -> ChrootctlReadinessCode.BinaryExecFailed
            probes.any { it.trusted && !it.compatible && it.detail.startsWith("probe failed") } -> ChrootctlReadinessCode.ProbeFailed
            probes.any { it.trusted && !it.compatible } -> ChrootctlReadinessCode.BinaryVersionIncompatible
            else -> ChrootctlReadinessCode.PublicLauncherMissing
        }
        return report(
            code = code,
            detail = readinessDetail(code, pending),
            installed = true,
            packageSeal = true,
            runtimeSeal = true,
            pending = pending,
            pendingPaths = pendingPaths,
            provider = provider,
            abi = abi,
            moduleVersion = moduleVersion,
            moduleVersionCode = moduleVersionCode,
            backendVersion = packageIdentity.backendVersion,
            bridgeProtocolVersion = packageIdentity.bridgeProtocolVersion,
            candidates = probes,
        )
    }

    private fun readModuleProperties(): Map<String, String>? {
        val prop = File(activeModuleDir, "module.prop")
        if (!isCanonicalTrustedModuleFile(prop)) return null
        return runCatching {
            prop.useLines { lines ->
                lines.mapNotNull { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#") || '=' !in line) null
                    else line.substringBefore('=').trim() to line.substringAfter('=').trim()
                }.toMap()
            }
        }.getOrNull()
    }

    private fun validatePackageSeal(moduleVersion: String?, moduleVersionCode: Int): PackageIdentity? {
        val manifestFile = File(activeModuleDir, MODULE_PACKAGE_MANIFEST_PATH)
        if (!isCanonicalTrustedModuleFile(manifestFile)) return null
        val manifest = readJsonObject(manifestFile) ?: return null
        val backendVersion = manifest.string("backendVersion") ?: return null
        val identity = PackageIdentity(
            moduleVersion = manifest.string("moduleVersion") ?: return null,
            moduleVersionCode = manifest.int("moduleVersionCode") ?: return null,
            backendVersion = backendVersion,
            bridgeProtocolVersion = manifest.int("bridgeProtocolVersion") ?: return null,
            runtimeSchemaVersion = manifest.int("runtimeSchemaVersion") ?: return null,
            runtimeKind = manifest.string("runtimeKind") ?: return null,
            runtimeAbi = manifest.string("runtimeAbi") ?: return null,
            runtimeEntrypoint = manifest.string("runtimeEntrypoint") ?: return null,
        )
        if (manifest.int("schemaVersion") != MODULE_PACKAGE_SCHEMA_VERSION ||
            manifest.string("moduleId") != MODULE_ID ||
            identity.moduleVersionCode != moduleVersionCode ||
            moduleVersion != null && identity.moduleVersion != moduleVersion ||
            identity.moduleVersionCode < MIN_MODULE_VERSION_CODE ||
            identity.backendVersion.isBlank() ||
            identity.bridgeProtocolVersion != BRIDGE_PROTOCOL_VERSION ||
            identity.runtimeSchemaVersion != NATIVE_RUNTIME_SCHEMA_VERSION ||
            identity.runtimeKind != NATIVE_RUNTIME_KIND ||
            identity.runtimeAbi != EXPECTED_ABI ||
            identity.runtimeEntrypoint != NATIVE_RUNTIME_ENTRYPOINT
        ) return null

        val files = manifest["files"] as? JsonArray ?: return null
        if (files.isEmpty()) return null
        val seen = linkedSetOf<String>()
        for (element in files) {
            val metadata = element as? JsonObject ?: return null
            val relative = metadata.string("path") ?: return null
            if (!isSafeModuleRelativePath(relative) || !seen.add(relative)) return null
            val file = File(activeModuleDir, relative)
            if (!isCanonicalTrustedModuleFile(file)) return null
            if (metadata.long("size") != file.length()) return null
            if (metadata.string("sha256") != sha256(file)) return null
            if (metadata.boolean("executable") != file.canExecute()) return null
        }
        val actual = activeModuleDir.walkTopDown()
            .onEnter { directory -> runCatching { directory.canonicalFile == directory.absoluteFile }.getOrDefault(false) }
            .filter(File::isFile)
            .map { it.relativeTo(activeModuleDir).invariantSeparatorsPath }
            .filter { it != MODULE_PACKAGE_MANIFEST_PATH && !isModuleManagerMetadata(it) }
            .toSet()
        if (actual != seen || !hasRequiredModulePackageMembers(seen)) return null
        return identity
    }

    private fun validateExternalCompatibility(identity: PackageIdentity): Boolean {
        val compatibilityFile = File(activeModuleDir, COMPATIBILITY_MANIFEST_PATH)
        if (!isCanonicalTrustedModuleFile(compatibilityFile)) return false
        val compatibility = readJsonObject(compatibilityFile) ?: return false
        return validateExternalCompatibilityDocument(compatibility, identity)
    }

    private fun validateNativeRuntime(identity: PackageIdentity): Boolean {
        val runtimeRoot = File(activeModuleDir, NATIVE_RUNTIME_ROOT)
        if (!isCanonicalTrustedModuleDir(runtimeRoot)) return false
        val manifestFile = File(runtimeRoot, "runtime-manifest.json")
        if (!isCanonicalTrustedModuleFile(manifestFile)) return false
        val manifest = readJsonObject(manifestFile) ?: return false
        if (manifest.int("schemaVersion") != identity.runtimeSchemaVersion ||
            manifest.string("runtimeKind") != identity.runtimeKind ||
            manifest.string("backendVersion") != identity.backendVersion ||
            manifest.string("platform") != NATIVE_RUNTIME_PLATFORM ||
            manifest.string("abi") != identity.runtimeAbi ||
            manifest.string("entrypoint") != identity.runtimeEntrypoint ||
            manifest.int("bridgeProtocolVersion") != identity.bridgeProtocolVersion
        ) return false

        val inventory = manifest["files"] as? JsonArray ?: return false
        if (inventory.isEmpty()) return false
        val seen = linkedSetOf<String>()
        for (element in inventory) {
            val metadata = element as? JsonObject ?: return false
            val relative = metadata.string("path") ?: return false
            if (!isSafeNativeRelativePath(relative) || !seen.add(relative)) return false
            val file = File(runtimeRoot, relative)
            if (!isCanonicalTrustedModuleFile(file)) return false
            if (metadata.long("size") != file.length()) return false
            if (metadata.string("sha256") != sha256(file)) return false
            if (metadata.boolean("executable") != file.canExecute()) return false
        }
        val actual = runtimeRoot.walkTopDown()
            .onEnter { directory -> runCatching { directory.canonicalFile == directory.absoluteFile }.getOrDefault(false) }
            .filter(File::isFile)
            .map { it.relativeTo(runtimeRoot).invariantSeparatorsPath }
            .filter { it != "runtime-manifest.json" }
            .toSet()
        if (actual != seen) return false
        val entrypoint = File(runtimeRoot, identity.runtimeEntrypoint)
        return identity.runtimeEntrypoint in seen && entrypoint.canExecute() && validateAndroidArm64Elf(entrypoint)
    }

    private fun inspectCandidate(candidate: Candidate): TrustProbe {
        val path = candidate.path
        val exists = path.isFile
        val executable = exists && path.canExecute()
        val stat = if (exists) runCatching { Os.lstat(path.path) }.getOrNull() else null
        val canonical = if (exists) runCatching { path.canonicalPath }.getOrNull() else null
        val trusted = when {
            !exists || !executable -> false
            candidate.moduleInternal -> isCanonicalTrustedModuleFile(path)
            else -> isTrustedRootExecutable(path)
        }
        val detail = when {
            !exists -> "not found"
            !executable -> "not executable"
            stat != null && stat.st_uid.toInt() != ROOT_UID -> "owner uid ${stat.st_uid} is not root (expected 0)"
            stat != null && hasUnsafeWriteBits(stat.st_mode) -> "unsafe writable mode ${Integer.toOctalString(stat.st_mode and 0x1ff)}"
            canonical != path.absolutePath -> "canonical path mismatch: ${canonical ?: "unavailable"}"
            !trusted -> "failed binary trust checks"
            else -> "trusted; compatibility probe pending"
        }
        return TrustProbe(
            trusted = trusted,
            candidate = ChrootctlBinaryCandidate(
                path = path.path,
                source = candidate.source,
                exists = exists,
                executable = executable,
                trusted = trusted,
                compatible = false,
                detail = detail,
                uid = stat?.st_uid?.toInt(),
                gid = stat?.st_gid?.toInt(),
                mode = stat?.st_mode,
                canonicalPath = canonical,
            ),
        )
    }

    private fun probeCapabilities(executable: File, identity: PackageIdentity): CapabilityProbe {
        val result = runCatching { runCapabilitiesProbe(executable) }.getOrElse { error ->
            return CapabilityProbe(false, null, "execution failed: ${error.message ?: error.javaClass.simpleName}")
        }
        if (result.timedOut) return CapabilityProbe(false, result.exitCode, "probe timed out: capabilities probe exceeded ${PROBE_TIMEOUT_SECONDS}s")
        if (result.truncated) return CapabilityProbe(false, result.exitCode, "probe failed: capabilities response exceeded the bounded probe capture")
        val contract = validateCapabilitiesResponse(result.stdout, result.exitCode, identity)
        return CapabilityProbe(
            compatible = contract.compatible,
            exitCode = result.exitCode,
            detail = contract.detail,
            backendVersion = contract.backendVersion,
            operations = contract.operations,
        )
    }

    internal fun validateCapabilitiesResponse(
        raw: String,
        exitCode: Int?,
        identity: PackageIdentity,
    ): CapabilityContractResult {
        val envelopeRaw = extractJsonEnvelope(raw)
            ?: return CapabilityContractResult(false, "probe failed: capabilities returned no JSON envelope")
        val envelope = runCatching { parseJsonObject(envelopeRaw) }.getOrNull()
            ?: return CapabilityContractResult(false, "probe failed: capabilities returned malformed JSON")
        if (envelope.int("protocolVersion") != BRIDGE_PROTOCOL_VERSION || envelope.boolean("ok") != true) {
            return CapabilityContractResult(false, "incompatible: capabilities envelope did not satisfy protocol-$BRIDGE_PROTOCOL_VERSION")
        }
        val data = envelope.obj("data")
            ?: return CapabilityContractResult(false, "probe failed: capabilities response had no data object")
        val protocol = data.int("protocolVersion")
        val backendVersion = data.string("backendVersion")
        if (exitCode != 0 || protocol != BRIDGE_PROTOCOL_VERSION || backendVersion != identity.backendVersion) {
            return CapabilityContractResult(false, "incompatible: protocol/backend identity mismatch (protocol=$protocol backend=$backendVersion)", backendVersion)
        }
        if (data.boolean("arbitraryShell") != false) {
            return CapabilityContractResult(false, "incompatible: bridge did not prove arbitraryShell=false", backendVersion)
        }
        val operations = capabilityNames(data["operations"] as? JsonArray)
        val missing = REQUIRED_OPERATIONS - operations
        if (missing.isNotEmpty()) {
            return CapabilityContractResult(
                compatible = false,
                detail = "incompatible: bridge is missing required BKX chroot operations: ${missing.sorted().joinToString()}",
                backendVersion = backendVersion,
                operations = operations,
            )
        }
        return CapabilityContractResult(
            compatible = true,
            detail = "protocol-$protocol bridge ready; required chroot export/import capabilities present",
            backendVersion = backendVersion,
            operations = operations,
        )
    }

    private fun runCapabilitiesProbe(executable: File): ProcessResult {
        val process = ProcessBuilder(executable.path, "app", "capabilities")
            .apply {
                environment().clear()
                environment().putAll(rootBridgeEnvironment())
            }
            .start()
        runCatching { process.outputStream.close() }
        val stdout = BoundedOutputStream(MAX_PROBE_CAPTURE_BYTES)
        val stderr = BoundedOutputStream(MAX_PROBE_CAPTURE_BYTES)
        val outThread = drain(process.inputStream, stdout, "bkx-chrootctl-probe-out")
        val errThread = drain(process.errorStream, stderr, "bkx-chrootctl-probe-err")
        val finished = try {
            process.waitForChrootctlCompat(PROBE_TIMEOUT_SECONDS)
        } catch (interrupted: InterruptedException) {
            process.killChrootctlCompat()
            Thread.currentThread().interrupt()
            throw interrupted
        }
        if (!finished) process.killChrootctlCompat()
        outThread.join(PROBE_READER_JOIN_MS)
        errThread.join(PROBE_READER_JOIN_MS)
        return ProcessResult(
            exitCode = if (finished) process.exitValue() else null,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
            truncated = stdout.truncated || stderr.truncated,
        )
    }

    private fun isCanonicalTrustedModuleDir(path: File): Boolean {
        if (!path.isDirectory || runCatching { path.canonicalFile != path.absoluteFile }.getOrDefault(true)) return false
        val canonical = runCatching { path.canonicalFile }.getOrNull() ?: return false
        val activeCanonical = runCatching { activeModuleDir.canonicalFile }.getOrNull() ?: return false
        if (canonical != path.absoluteFile || !(canonical == activeCanonical || canonical.path.startsWith(activeCanonical.path + File.separator))) return false
        val stat = runCatching { Os.lstat(path.path) }.getOrNull() ?: return false
        return trustedRootMetadata(stat.st_uid.toInt(), stat.st_mode)
    }

    private fun isCanonicalTrustedModuleFile(path: File): Boolean {
        if (!path.isFile || runCatching { path.canonicalFile != path.absoluteFile }.getOrDefault(true)) return false
        val canonical = runCatching { path.canonicalFile }.getOrNull() ?: return false
        val activeCanonical = runCatching { activeModuleDir.canonicalFile }.getOrNull() ?: return false
        if (canonical != path.absoluteFile || !canonical.path.startsWith(activeCanonical.path + File.separator)) return false
        val stat = runCatching { Os.lstat(path.path) }.getOrNull() ?: return false
        return trustedRootMetadata(stat.st_uid.toInt(), stat.st_mode)
    }

    private fun isTrustedRootExecutable(path: File): Boolean {
        if (!path.isFile || !path.canExecute() || runCatching { path.canonicalFile != path.absoluteFile }.getOrDefault(true)) return false
        val canonical = runCatching { path.canonicalFile }.getOrNull() ?: return false
        if (canonical != path.absoluteFile) return false
        val stat = runCatching { Os.lstat(path.path) }.getOrNull() ?: return false
        return trustedRootMetadata(stat.st_uid.toInt(), stat.st_mode)
    }

    private fun detectRootProvider(): String = when {
        File(adbRoot, "ksu").exists() -> "KernelSU"
        File(adbRoot, "ap").exists() -> "APatch"
        File(adbRoot, "magisk").exists() || File(adbRoot, "magisk.db").exists() -> "Magisk"
        else -> "unknown"
    }

    private fun currentDeviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    private fun sha256(path: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.getOrDefault("")

    private fun report(
        code: ChrootctlReadinessCode,
        detail: String,
        installed: Boolean = false,
        disabled: Boolean = false,
        packageSeal: Boolean = false,
        runtimeSeal: Boolean = false,
        pending: Boolean,
        pendingPaths: List<String> = emptyList(),
        provider: String,
        abi: String,
        moduleVersion: String? = null,
        moduleVersionCode: Int? = null,
        backendVersion: String? = null,
        bridgeProtocolVersion: Int? = null,
        candidates: List<ChrootctlBinaryCandidate> = emptyList(),
    ): ChrootctlReadiness = ChrootctlReadiness(
        code = code,
        detail = detail,
        activeModulePath = activeModuleDir.path.takeIf { installed },
        moduleInstalled = installed,
        moduleDisabled = disabled,
        packageSealReady = packageSeal,
        runtimeSealReady = runtimeSeal,
        pendingUpdate = pending,
        pendingUpdatePaths = pendingPaths,
        rootProvider = provider,
        abi = abi,
        moduleVersion = moduleVersion,
        moduleVersionCode = moduleVersionCode,
        backendVersion = backendVersion,
        bridgeProtocolVersion = bridgeProtocolVersion,
        candidates = candidates,
    )

    private fun readinessDetail(code: ChrootctlReadinessCode, pending: Boolean): String {
        val base = when (code) {
            ChrootctlReadinessCode.BinaryNotExecutable -> "A chrootctl launcher exists but is not executable. Reinstall the active module."
            ChrootctlReadinessCode.BinaryOwnerInvalid -> "A chrootctl launcher is not owned by root. Reinstall the active module."
            ChrootctlReadinessCode.UnsafeBinaryPermissions -> "A chrootctl launcher is writable by group/others. Reinstall the active module."
            ChrootctlReadinessCode.UntrustedBinaryPath -> "A chrootctl launcher resolves outside its approved canonical path. Reinstall the active module."
            ChrootctlReadinessCode.BinaryUntrusted -> "No discovered chrootctl launcher passed the root ownership/path trust boundary."
            ChrootctlReadinessCode.VersionProbeFailed -> "The trusted chrootctl launcher did not complete its protocol compatibility probe."
            ChrootctlReadinessCode.BinaryExecFailed -> "The trusted chrootctl launcher could not execute its fixed app-capabilities probe."
            ChrootctlReadinessCode.ProbeFailed -> "The trusted chrootctl launcher returned an invalid or over-limit capabilities response."
            ChrootctlReadinessCode.BinaryVersionIncompatible -> "The trusted chrootctl launcher is not compatible with BKX's protocol-v1 chroot feature contract."
            else -> "No trusted compatible chrootctl launcher is available from the active module."
        }
        return base + if (pending) " A pending module update exists and will not be executed before reboot." else ""
    }

    internal fun validateExternalCompatibilityDocument(compatibility: JsonObject, identity: PackageIdentity): Boolean {
        val module = compatibility.obj("module") ?: return false
        val backend = compatibility.obj("backend") ?: return false
        val runtime = compatibility.obj("nativeRuntime") ?: return false
        return compatibility.int("schemaVersion") == COMPATIBILITY_SCHEMA_VERSION &&
            compatibility.string("project") == "chrootctl" &&
            compatibility.int("bridgeProtocolVersion") == BRIDGE_PROTOCOL_VERSION &&
            module.string("id") == MODULE_ID &&
            module.int("versionCode") == identity.moduleVersionCode &&
            module.string("version") == identity.moduleVersion &&
            (module.int("minimumSupportedBridgeProtocolVersion") ?: Int.MAX_VALUE) in 1..BRIDGE_PROTOCOL_VERSION &&
            backend.string("implementation") == EXPECTED_BACKEND_IMPLEMENTATION &&
            backend.string("version") == identity.backendVersion &&
            runtime.int("schemaVersion") == identity.runtimeSchemaVersion &&
            runtime.string("kind") == identity.runtimeKind &&
            runtime.string("platform") == NATIVE_RUNTIME_PLATFORM &&
            runtime.string("abi") == identity.runtimeAbi &&
            runtime.string("entrypoint") == identity.runtimeEntrypoint
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.obj(key: String): JsonObject? = runCatching { this[key]?.jsonObject }.getOrNull()
    private fun readJsonObject(file: File): JsonObject? = runCatching { parseJsonObject(file.readText()) }.getOrNull()
    private fun parseJsonObject(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    internal data class PackageIdentity(
        val moduleVersion: String,
        val moduleVersionCode: Int,
        val backendVersion: String,
        val bridgeProtocolVersion: Int,
        val runtimeSchemaVersion: Int,
        val runtimeKind: String,
        val runtimeAbi: String,
        val runtimeEntrypoint: String,
    )

    internal data class CapabilityContractResult(
        val compatible: Boolean,
        val detail: String,
        val backendVersion: String? = null,
        val operations: Set<String> = emptySet(),
    )

    private data class Candidate(val path: File, val source: String, val moduleInternal: Boolean)
    private data class TrustProbe(val trusted: Boolean, val candidate: ChrootctlBinaryCandidate)
    private data class CapabilityProbe(
        val compatible: Boolean,
        val exitCode: Int?,
        val detail: String,
        val backendVersion: String? = null,
        val operations: Set<String> = emptySet(),
    )
    private data class ProcessResult(
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val truncated: Boolean,
    )

    private class BoundedOutputStream(private val limit: Int) : OutputStream() {
        private val buffer = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        var truncated: Boolean = false
            private set
        override fun write(value: Int) {
            if (buffer.size() < limit) buffer.write(value) else truncated = true
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val remaining = (limit - buffer.size()).coerceAtLeast(0)
            val accepted = minOf(length, remaining)
            if (accepted > 0) buffer.write(bytes, offset, accepted)
            if (accepted < length) truncated = true
        }
        fun text(): String = buffer.toString(Charsets.UTF_8.name())
    }

    private fun drain(input: InputStream, output: OutputStream, name: String): Thread = Thread({
        runCatching { input.use { source -> source.copyTo(output, DEFAULT_BUFFER_SIZE) } }
    }, name).apply { isDaemon = true; start() }

    private fun extractJsonEnvelope(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        return raw.lineSequence().map(String::trim).firstOrNull { it.startsWith("{") && it.endsWith("}") }
    }

    private fun capabilityNames(array: JsonArray?): Set<String> = buildSet {
        if (array == null) return@buildSet
        for (element in array) {
            when (element) {
                is JsonObject -> element.string("name")?.takeIf(String::isNotBlank)?.let(::add)
                else -> runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    internal fun hasRequiredModulePackageMembers(paths: Set<String>): Boolean =
        REQUIRED_MODULE_PACKAGE_MEMBERS.all(paths::contains)

    internal fun isSafeModuleRelativePath(path: String): Boolean {
        if (path.isBlank() || path == MODULE_PACKAGE_MANIFEST_PATH || path.startsWith('/') || path.startsWith("\\")) return false
        return path.replace('\\', '/').split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    internal fun isSafeNativeRelativePath(path: String): Boolean {
        if (path.isBlank() || path == "runtime-manifest.json" || path.startsWith('/') || path.startsWith("\\")) return false
        return path.replace('\\', '/').split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    internal fun isModuleManagerMetadata(path: String): Boolean = path in MODULE_MANAGER_METADATA

    internal fun trustedRootMetadata(uid: Int, mode: Int): Boolean = uid == ROOT_UID && !hasUnsafeWriteBits(mode)
    private fun hasUnsafeWriteBits(mode: Int): Boolean = (mode and GROUP_OR_OTHER_WRITE_BITS) != 0

    internal fun validateAndroidArm64Elf(file: File): Boolean = runCatching {
        val header = file.inputStream().buffered().use { input ->
            ByteArray(64).also { bytes -> if (input.read(bytes) != bytes.size) return@runCatching false }
        }
        if (!isAndroidArm64ElfHeader(header)) return@runCatching false
        java.io.RandomAccessFile(file, "r").use { raf ->
            val phoff = littleEndianLong(header, 32)
            val phentsize = littleEndianU16(header, 54)
            val phnum = littleEndianU16(header, 56)
            if (phoff < 0 || phentsize < 56 || phnum < 0 || phnum > 4096) return@runCatching false
            var interpreter: String? = null
            repeat(phnum) { index ->
                val offset = phoff + index.toLong() * phentsize
                if (offset < 0 || offset + phentsize > raf.length()) return@runCatching false
                raf.seek(offset)
                val ph = ByteArray(phentsize)
                raf.readFully(ph)
                if (littleEndianInt(ph, 0) == PT_INTERP) {
                    val fileOffset = littleEndianLong(ph, 8)
                    val fileSize = littleEndianLong(ph, 32)
                    if (fileOffset < 0 || fileSize <= 0 || fileSize > 4096 || fileOffset + fileSize > raf.length()) return@runCatching false
                    val raw = ByteArray(fileSize.toInt())
                    raf.seek(fileOffset)
                    raf.readFully(raw)
                    interpreter = raw.toString(Charsets.UTF_8).trimEnd('\u0000')
                }
            }
            interpreter == null || interpreter == "/system/bin/linker64" || (interpreter?.startsWith("/apex/") == true && interpreter?.endsWith("/linker64") == true)
        }
    }.getOrDefault(false)

    internal fun isAndroidArm64ElfHeader(header: ByteArray): Boolean =
        header.size >= 64 &&
            header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[4].toInt() == 2 && header[5].toInt() == 1 && littleEndianU16(header, 18) == EM_AARCH64

    private fun littleEndianU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (0 until 4).fold(0) { acc, index -> acc or ((bytes[offset + index].toInt() and 0xff) shl (index * 8)) }

    private fun littleEndianLong(bytes: ByteArray, offset: Int): Long =
        (0 until 8).fold(0L) { acc, index -> acc or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8)) }

    private fun rootBridgeEnvironment(): Map<String, String> = linkedMapOf(
        "HOME" to "/data/adb/chrootctl",
        "PATH" to "/data/adb/chrootctl/bin:/system/bin:/system/xbin:/vendor/bin:/product/bin",
        "TMPDIR" to "/data/local/tmp",
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8",
    )

    private const val ROOT_UID = 0
    private const val GROUP_OR_OTHER_WRITE_BITS = 0x12
    private const val BRIDGE_PROTOCOL_VERSION = 1
    private const val COMPATIBILITY_SCHEMA_VERSION = 3
    private const val MODULE_PACKAGE_SCHEMA_VERSION = 1
    private const val NATIVE_RUNTIME_SCHEMA_VERSION = 2
    private const val MIN_MODULE_VERSION_CODE = 15
    private const val MODULE_ID = "chrootctl"
    private const val EXPECTED_BACKEND_IMPLEMENTATION = "go"
    private const val NATIVE_RUNTIME_KIND = "go-native"
    private const val NATIVE_RUNTIME_PLATFORM = "android"
    private const val EXPECTED_ABI = "arm64-v8a"
    private const val NATIVE_RUNTIME_ENTRYPOINT = "chrootctl"
    private const val NATIVE_RUNTIME_ROOT = "runtime/arm64-v8a"
    private const val COMPATIBILITY_MANIFEST_PATH = "config/chrootctl/compatibility-manifest.json"
    private const val MODULE_PACKAGE_MANIFEST_PATH = "config/chrootctl/module-package-manifest.json"
    private const val PROBE_TIMEOUT_SECONDS = 10L
    private const val PROBE_READER_JOIN_MS = 2_000L
    private const val MAX_PROBE_CAPTURE_BYTES = 256 * 1024
    private const val EM_AARCH64 = 183
    private const val PT_INTERP = 3
    private val MODULE_MANAGER_METADATA = setOf("update", "disable", "remove", "skip_mount")
    private val REQUIRED_MODULE_PACKAGE_MEMBERS = setOf(
        "system/bin/chrootctl",
        "runtime/$EXPECTED_ABI/$NATIVE_RUNTIME_ENTRYPOINT",
        "uninstall.sh",
    )
    private val REQUIRED_OPERATIONS = setOf(
        "chroots.list",
        "chroots.get",
        "transfer.artifact_root",
        "transfer.inspect_import",
        "transfer.import_preview",
        "transfer.preview_export",
        "transfer.import",
        "transfer.export",
        "artifacts.list",
        "operations.get",
        "operations.find_request",
    )
}
