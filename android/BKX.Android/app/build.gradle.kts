import org.gradle.util.GradleVersion

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val bkxValidationSdk = 36
val bkxBuildToolsVersion = "36.0.0"
val bkxJavaVersion = JavaVersion.VERSION_21
val bkxGoRuntimeJniDir = layout.buildDirectory.dir("generated/bkx-go-runtime/jniLibs")

val bkxRepositoryRoot = rootProject.layout.projectDirectory.dir("../..").asFile.canonicalFile
val bkxPyprojectFile = bkxRepositoryRoot.resolve("pyproject.toml")
val bkxVersionName = Regex("""(?m)^version\s*=\s*"([^"]+)"\s*$""")
    .find(bkxPyprojectFile.readText())
    ?.groupValues
    ?.get(1)
    ?: error("BKX version is missing from repository-root pyproject.toml")
val bkxVersionParts = bkxVersionName.split(".").map { part ->
    part.toIntOrNull() ?: error("BKX Android requires a numeric major.minor.patch version; found $bkxVersionName")
}
check(bkxVersionParts.size == 3) { "BKX Android requires major.minor.patch; found $bkxVersionName" }
val bkxVersionCode = bkxVersionParts[0] * 10_000 + bkxVersionParts[1] * 100 + bkxVersionParts[2]

// Chaquopy 17 requires buildPython to have the same major/minor version as
// the Python runtime packaged into the app. Termux may move its python3
// package between 3.13 and 3.14, so resolve the installed build interpreter
// first and select the matching Chaquopy runtime instead of hard-coding one.
val bkxSupportedEmbeddedPythonVersions = setOf("3.13", "3.14")
val bkxExplicitBuildPython = providers.gradleProperty("bkx.buildPython").orNull
    ?: providers.environmentVariable("BKX_BUILD_PYTHON").orNull
val bkxBuildPythonCandidates = bkxExplicitBuildPython
    ?.takeIf { it.isNotBlank() }
    ?.let { listOf(it) }
    ?: listOf(
        "/data/data/com.termux/files/usr/bin/python3",
        "python3.14",
        "python3.13",
        "python3",
        "python",
    )

var bkxDetectedBuildPythonCommand: String? = null
var bkxDetectedEmbeddedPythonVersion: String? = null
val bkxBuildPythonAttempts = mutableListOf<String>()

for (candidate in bkxBuildPythonCandidates.distinct()) {
    try {
        val process = ProcessBuilder(
            candidate,
            "-c",
            "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        if (exitCode == 0 && output in bkxSupportedEmbeddedPythonVersions) {
            bkxDetectedBuildPythonCommand = candidate
            bkxDetectedEmbeddedPythonVersion = output
            break
        }
        bkxBuildPythonAttempts += "$candidate -> exit=$exitCode version/output=$output"
    } catch (error: Exception) {
        bkxBuildPythonAttempts += "$candidate -> ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
    }
}

val bkxBuildPythonCommand = bkxDetectedBuildPythonCommand ?: error(
    "BKX Android requires build Python 3.13 or 3.14 for Chaquopy 17. " +
        "Install a supported Python or set -Pbkx.buildPython=/path/to/python. " +
        "Attempts: ${bkxBuildPythonAttempts.joinToString("; ")}"
)
val bkxEmbeddedPythonVersion = bkxDetectedEmbeddedPythonVersion ?: error(
    "BKX Android could not determine the Chaquopy Python runtime version."
)

android {
    namespace = "com.mikeyphw.bkx.android"
    compileSdk = bkxValidationSdk
    buildToolsVersion = bkxBuildToolsVersion

    defaultConfig {
        applicationId = "com.mikeyphw.bkx.android"
        minSdk = 24
        targetSdk = bkxValidationSdk
        versionCode = bkxVersionCode
        versionName = bkxVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    sourceSets.getByName("main").jniLibs.srcDir(bkxGoRuntimeJniDir)

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = bkxJavaVersion
        targetCompatibility = bkxJavaVersion
    }

    packaging {
        jniLibs {
            // The Go runtime is executed as an app-owned PIE from nativeLibraryDir.
            // Force extraction so ProcessBuilder receives a real executable filesystem path.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // The Termux/chroot validator installs SDK packages through sdkmanager.
        // Keep validation on the latest resolvable stable SDK (API 36) instead
        // of failing before compile on unavailable android-37 packages.
        disable += setOf(
            "OldTargetApi",
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
        )
    }
}

chaquopy {
    defaultConfig {
        version = bkxEmbeddedPythonVersion
        buildPython(bkxBuildPythonCommand)
        // BKX core's embedded lane uses only the Python standard library.
        // Do not install CLI/TUI dependencies into the APK.
        pyc {
            src = true
        }
    }
    sourceSets {
        getByName("main") {
            // Keep src/bkx as the single canonical Python implementation.
            srcDir(rootProject.file("../../src"))
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.io)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}


val bkxGradleVersion = gradle.gradleVersion
val bkxVersionCatalogFile = rootProject.layout.projectDirectory.file("gradle/libs.versions.toml").asFile
val bkxSettingsFile = rootProject.layout.projectDirectory.file("settings.gradle.kts").asFile

val bkxSdk36DependencyLane = mapOf(
    "AGP" to libs.versions.agp.get(),
    "Chaquopy" to libs.versions.chaquopy.get(),
    "Kotlin" to libs.versions.kotlin.get(),
    "Compose BOM" to libs.versions.composeBom.get(),
    "Activity Compose" to libs.versions.activity.get(),
    "AppCompat" to libs.versions.appcompat.get(),
    "Core KTX" to libs.versions.coreKtx.get(),
    "Lifecycle Compose" to libs.versions.lifecycle.get(),
    "libsu" to libs.versions.libsu.get(),
)

val expectedBkxSdk36DependencyLane = mapOf(
    "AGP" to "9.2.1",
    "Chaquopy" to "17.0.0",
    "Kotlin" to "2.4.10",
    // Compose 1.12+ requires compileSdk 37; keep the stable SDK-36 lane here.
    "Compose BOM" to "2026.06.01",
    "Activity Compose" to "1.12.4",
    "AppCompat" to "1.7.1",
    "Core KTX" to "1.17.0",
    "Lifecycle Compose" to "2.10.0",
    "libsu" to "6.0.0",
)

tasks.register("verifyBkxAndroidSdk36DependencyLane") {
    group = "verification"
    description = "Fail closed when BKX Android drifts from the stable Java/Gradle/AGP/SDK/dependency lane."

    inputs.property("bkxGradleVersion", bkxGradleVersion)
    inputs.property("bkxValidationSdk", bkxValidationSdk)
    inputs.property("bkxBuildToolsVersion", bkxBuildToolsVersion)
    inputs.property("bkxJavaVersion", bkxJavaVersion.toString())
    inputs.property("bkxBuildPythonCommand", bkxBuildPythonCommand)
    inputs.property("bkxEmbeddedPythonVersion", bkxEmbeddedPythonVersion)
    inputs.properties(bkxSdk36DependencyLane)
    inputs.file(bkxVersionCatalogFile)
    inputs.file(bkxSettingsFile)

    doLast {
        val minimumGradleVersion = GradleVersion.version("9.7.1")
        val runningGradleVersion = GradleVersion.version(bkxGradleVersion)
        check(runningGradleVersion >= minimumGradleVersion) {
            "BKX Android requires Gradle 9.7.1 or newer; found $bkxGradleVersion."
        }
        check(bkxValidationSdk == 36) {
            "BKX Android SDK policy changed from API 36; update the Devtool platform, dependency lane, and SDK validation policy together."
        }
        check(bkxBuildToolsVersion == "36.0.0") {
            "BKX Android Build Tools policy changed from 36.0.0; keep the stable SDK/build-tools lane synchronized."
        }
        check(bkxJavaVersion == JavaVersion.VERSION_21) {
            "BKX Android JVM policy changed from Java 21; update the fleet toolchain contract deliberately."
        }
        check(bkxEmbeddedPythonVersion in bkxSupportedEmbeddedPythonVersions) {
            "BKX Android Chaquopy runtime must match a supported build Python (3.13 or 3.14); found $bkxEmbeddedPythonVersion."
        }
        check(bkxBuildPythonCommand.isNotBlank()) {
            "BKX Android buildPython command must resolve explicitly before Chaquopy source merging."
        }

        val settingsText = bkxSettingsFile.readText()
        check("https://jitpack.io" in settingsText) {
            "BKX Android libsu 6.0.0 requires the JitPack dependency repository in settings.gradle.kts."
        }
        check("includeGroup(\"com.github.topjohnwu.libsu\")" in settingsText) {
            "BKX Android JitPack access must stay restricted to com.github.topjohnwu.libsu."
        }

        val prereleaseToken = Regex(
            """(?i)(?:^|[._+-])(alpha|beta|rc[0-9]*|preview|canary|eap|snapshot|nightly|milestone)(?:[._+-]|$)"""
        )
        val versionAssignment = Regex("""^([A-Za-z0-9_.-]+)\s*=\s*"([^"]+)"\s*(?:#.*)?$""")
        var inVersions = false
        val prereleaseVersions = mutableListOf<String>()

        bkxVersionCatalogFile.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("[")) {
                inVersions = line == "[versions]"
            } else if (inVersions) {
                val match = versionAssignment.matchEntire(line)
                if (match != null) {
                    val (name, version) = match.destructured
                    if (prereleaseToken.containsMatchIn(version)) {
                        prereleaseVersions += "$name=$version"
                    }
                }
            }
        }

        check(prereleaseVersions.isEmpty()) {
            "BKX Android stable-only dependency policy rejected prerelease versions: ${prereleaseVersions.joinToString()}."
        }

        val drift = expectedBkxSdk36DependencyLane.mapNotNull { (name, expected) ->
            val actual = bkxSdk36DependencyLane.getValue(name)
            if (actual == expected) null else "$name=$actual (expected $expected)"
        }

        check(drift.isEmpty()) {
            "BKX Android API-36 dependency lane drifted into an unverified set: ${drift.joinToString()}. " +
                "Do not accept newer-version lint suggestions independently of compileSdk/validator availability."
        }
    }
}


val buildBkxGoAndroidRuntime by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the generated ARM64 Go protocol-v1 runtime packaged by BKX Android."
    workingDir(bkxRepositoryRoot)
    inputs.file(bkxRepositoryRoot.resolve("go.mod"))
    inputs.dir(bkxRepositoryRoot.resolve("cmd/bkx"))
    inputs.dir(bkxRepositoryRoot.resolve("internal"))
    inputs.file(bkxRepositoryRoot.resolve("scripts/build-go-android-runtime.sh"))
    outputs.file(bkxGoRuntimeJniDir.map { it.file("arm64-v8a/libbkxgo.so") })
    commandLine(
        "bash",
        bkxRepositoryRoot.resolve("scripts/build-go-android-runtime.sh").absolutePath,
        bkxGoRuntimeJniDir.get().asFile.absolutePath,
    )
}

tasks.named("preBuild").configure {
    dependsOn(buildBkxGoAndroidRuntime)
}

// Verification tasks live outside the application build configuration.
extra["bkxEmbeddedPythonVersion"] = bkxEmbeddedPythonVersion
extra["bkxSupportedEmbeddedPythonVersions"] = bkxSupportedEmbeddedPythonVersions
apply(from = rootProject.file("../../build-logic/android-verification.gradle.kts"))
