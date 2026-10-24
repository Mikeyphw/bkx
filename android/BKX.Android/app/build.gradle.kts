import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import org.gradle.util.GradleVersion
import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import java.nio.file.Files
import groovy.json.JsonSlurper
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

abstract class BuildBkxGoAndroidRuntimeTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val repositoryRootPath: Property<String>

    @get:Input
    abstract val buildScriptPath: Property<String>

    @get:Input
    abstract val buildCommit: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun buildRuntime() {
        execOperations.exec {
            workingDir(repositoryRootPath.get())
            environment("BKX_BUILD_COMMIT", buildCommit.get())
            commandLine(
                "bash",
                buildScriptPath.get(),
                outputDirectory.get().asFile.absolutePath,
            )
        }
    }
}

@UntrackedTask(because = "resolves the mutable GitHub latest-release pointer at execution time")
abstract class InstallBkxRcloneAndroidRuntimeTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val repositoryRootPath: Property<String>

    @get:Input
    abstract val installerScriptPath: Property<String>

    @get:Input
    abstract val ndkDirectoryPath: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val provenanceOutputDirectory: DirectoryProperty

    @TaskAction
    fun installRuntime() {
        execOperations.exec {
            workingDir(repositoryRootPath.get())
            commandLine(
                "bash",
                installerScriptPath.get(),
                outputDirectory.get().asFile.absolutePath,
                ndkDirectoryPath.get(),
                provenanceOutputDirectory.get().asFile.absolutePath,
            )
        }
    }
}

// Resolve the real, single AGP output for this variant. Unsigned release builds
// normally emit app-<variant>-unsigned.apk, not app-<variant>.apk.
// This is also the source of truth used by produce-bkx-unsigned-candidates.py.
object BkxReleaseApkOutputs {
    fun locate(directory: File, variant: String): File {
        check(variant in setOf("embeddedRelease", "liteRelease")) {
            "Unsupported BKX release variant: $variant"
        }
        val metadata = directory.resolve("output-metadata.json")
        check(metadata.isFile && !Files.isSymbolicLink(metadata.toPath())) {
            "Missing regular AGP metadata for $variant: ${metadata.absolutePath}"
        }
        check(metadata.length() in 1..1_048_576) { "Invalid AGP metadata size for $variant" }
        val doc = JsonSlurper().parse(metadata) as? Map<*, *>
            ?: error("Invalid AGP metadata document for $variant")
        check(doc["variantName"] == variant) { "AGP variant mismatch for $variant" }
        check(doc["applicationId"] == "com.mikeyphw.bkx.android") {
            "AGP package identity mismatch for $variant"
        }
        val elements = doc["elements"] as? List<*>
            ?: error("AGP output elements missing for $variant")
        check(elements.size == 1) { "Expected one APK output for $variant" }
        val element = elements.single() as? Map<*, *>
            ?: error("Invalid APK output metadata for $variant")
        val fileName = element["outputFile"] as? String
            ?: error("AGP APK filename missing for $variant")
        check(Regex("""[A-Za-z0-9_.-]+\.apk""").matches(fileName)) {
            "Unsafe APK output filename for $variant"
        }
        val apk = directory.resolve(fileName)
        check(apk.isFile && !Files.isSymbolicLink(apk.toPath())) {
            "Missing declared $variant APK: ${apk.absolutePath}"
        }
        val apks = directory.listFiles()?.filter { it.isFile && it.extension == "apk" }
            ?: error("Missing AGP output directory for $variant")
        check(apks.size == 1 && apks.single().canonicalFile == apk.canonicalFile) {
            "Unexpected additional APK outputs for $variant"
        }
        return apk
    }
}

abstract class VerifyBkxApkNativePageSizeTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val verifierProgramPath: Property<String>

    @get:Input
    abstract val minimumPageSize: Property<Int>

    @get:InputDirectory
    abstract val releaseOutputDirectory: DirectoryProperty

    @get:Input
    abstract val expectedVariant: Property<String>

    @TaskAction
    fun verifyPageSize() {
        val releaseApk = BkxReleaseApkOutputs.locate(
            releaseOutputDirectory.get().asFile, expectedVariant.get(),
        )
        execOperations.exec {
            commandLine(
                "go", "run", verifierProgramPath.get(), "apk",
                "--min-page-size", minimumPageSize.get().toString(),
                releaseApk.absolutePath,
            )
        }
    }
}

abstract class BkxSigningStatusTask : DefaultTask() {
    @get:Input
    abstract val configured: Property<Boolean>

    @get:Input
    abstract val propertiesPath: Property<String>

    @get:Input
    abstract val keystorePath: Property<String>

    @get:Input
    abstract val keyAlias: Property<String>

    @TaskAction
    fun reportStatus() {
        if (configured.get()) {
            println("BKX personal signing: configured")
            println("Properties: ${propertiesPath.get()}")
            println("Keystore: ${keystorePath.get()}")
            println("Alias: ${keyAlias.get()}")
            println("Debug and release APKs use the same permanent certificate.")
        } else {
            println("BKX personal signing: NOT configured")
            println("Expected properties: ${propertiesPath.get()}")
            println("Run bash tools/setup-personal-signing.sh before installing the permanent BKX build.")
        }
    }
}

abstract class VerifyBkxPersonalSigningConfiguredTask : DefaultTask() {
    @get:Input
    abstract val configured: Property<Boolean>

    @get:Input
    abstract val keystorePath: Property<String>

    @TaskAction
    fun verifyConfigured() {
        check(configured.get() && File(keystorePath.get()).isFile) {
            "BKX permanent personal signing is not configured. Run bash tools/setup-personal-signing.sh."
        }
        println("BKX permanent personal signing configuration: PASS")
    }
}

val bkxValidationSdk = 36
val bkxBuildToolsVersion = "36.0.0"
val bkxJavaVersion = JavaVersion.VERSION_21
val bkxNdkVersion = "29.0.14206865"
val bkxGoRuntimeJniDir = layout.buildDirectory.dir("generated/bkx-go-runtime/jniLibs")
val bkxRcloneRuntimeJniDir = layout.buildDirectory.dir("generated/bkx-rclone-runtime/jniLibs")
val bkxRcloneRuntimeAssetsDir = layout.buildDirectory.dir("generated/bkx-rclone-runtime/assets")

val bkxRepositoryRoot = rootProject.layout.projectDirectory.dir("../..").asFile.canonicalFile
val bkxVersionFile = bkxRepositoryRoot.resolve("internal/version/version.go")
val bkxVersionName = Regex("""(?m)^\s*Version\s*=\s*"([^"]+)"\s*$""")
    .find(bkxVersionFile.readText())
    ?.groupValues
    ?.get(1)
    ?: error("BKX version is missing from internal/version/version.go")
fun bkxAndroidVersionCode(versionName: String): Int {
    val parts = versionName.split(".").map { part ->
        part.toIntOrNull() ?: error("BKX Android requires a numeric major.minor.patch version; found $versionName")
    }
    check(parts.size == 3) { "BKX Android requires major.minor.patch; found $versionName" }
    val (major, minor, patch) = parts
    check(major in 0..1999 && minor in 0..999 && patch in 0..999) {
        "BKX Android versionCode supports major 0..1999 and minor/patch 0..999; found $versionName"
    }
    return major * 1_000_000 + minor * 1_000 + patch
}

val bkxVersionCode = bkxAndroidVersionCode(bkxVersionName)

fun bkxResolvedBuildCommit(repositoryRoot: File): String {
    providers.environmentVariable("BKX_BUILD_COMMIT").orNull?.trim()?.takeIf { it.isNotEmpty() && it != "dev" }?.let { return it }
    return runCatching {
        providers.exec {
            workingDir(repositoryRoot)
            commandLine("git", "rev-parse", "--short=12", "HEAD")
        }.standardOutput.asText.get().trim()
    }.getOrElse { "unknown" }.ifBlank { "unknown" }
}

val bkxBuildCommit = bkxResolvedBuildCommit(bkxRepositoryRoot)

val bkxSigningPropertiesFile = File(
    providers.environmentVariable("BKX_ANDROID_SIGNING_PROPERTIES").orNull
        ?: File(System.getProperty("user.home"), ".config/devtool/bkx/android-signing.properties").absolutePath,
).absoluteFile
val bkxPersonalSigningConfigured = bkxSigningPropertiesFile.isFile
val bkxSigningProperties = Properties().apply {
    if (bkxPersonalSigningConfigured) {
        bkxSigningPropertiesFile.inputStream().use { load(it) }
    }
}
fun bkxSigningValue(name: String): String {
    val value = bkxSigningProperties.getProperty(name)?.trim().orEmpty()
    check(value.isNotEmpty() && value != "REPLACE_ME") {
        "BKX personal signing property '$name' is missing in ${bkxSigningPropertiesFile.absolutePath}"
    }
    return value
}
val bkxPersonalSigningStoreFile = if (bkxPersonalSigningConfigured) {
    check(!bkxSigningPropertiesFile.canonicalFile.toPath().startsWith(bkxRepositoryRoot.toPath())) {
        "BKX personal signing properties must live outside the repository: ${bkxSigningPropertiesFile.absolutePath}"
    }
    File(bkxSigningValue("storeFile")).canonicalFile.also { store ->
        check(store.isAbsolute && store.isFile) {
            "BKX personal signing keystore does not exist: ${store.absolutePath}"
        }
        check(!store.toPath().startsWith(bkxRepositoryRoot.toPath())) {
            "BKX personal signing keystore must live outside the repository: ${store.absolutePath}"
        }
    }
} else {
    null
}

// GitHub's unsigned release producer must never read, load or use a private signing identity.
val bkxHostedUnsignedRelease = providers.environmentVariable("BKX_UNSIGNED_CI_RELEASE").orNull == "1"
check(!bkxHostedUnsignedRelease || !bkxPersonalSigningConfigured) {
    "Hosted unsigned BKX releases refuse locally configured signing credentials"
}

// BKX editions share package identity and Go runtime. Only Embedded packages rclone.
// No applicationIdSuffix: editions replace one another when signed with the same authorized key.
android {
    namespace = "com.mikeyphw.bkx.android"
    compileSdk = bkxValidationSdk
    buildToolsVersion = bkxBuildToolsVersion
    // Keep this literal so Devtool project inspection resolves --ndk-version auto to r29.
    ndkVersion = "29.0.14206865"

    flavorDimensions += "edition"
    productFlavors {
        create("embedded") { dimension = "edition" }
        create("lite") { dimension = "edition" }
    }

    signingConfigs {
        if (bkxPersonalSigningConfigured) {
            create("bkxPersonal") {
                storeFile = bkxPersonalSigningStoreFile
                storePassword = bkxSigningValue("storePassword")
                keyAlias = bkxSigningValue("keyAlias")
                keyPassword = bkxSigningValue("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (bkxPersonalSigningConfigured) {
                signingConfig = signingConfigs.getByName("bkxPersonal")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (bkxPersonalSigningConfigured) {
                signingConfig = signingConfigs.getByName("bkxPersonal")
            }
        }
    }

    defaultConfig {
        applicationId = "com.mikeyphw.bkx.android"
        minSdk = 24
        targetSdk = bkxValidationSdk
        versionCode = bkxVersionCode
        versionName = bkxVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
        aidl = true
    }

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
            // BKX Android is intentionally arm64-v8a only. ChromeOS alternate-ABI
            // support is outside policy; do not add a fake runtime ABI.
            "ChromeOsAbiSupport",
        )
    }
}


tasks.register<BkxSigningStatusTask>("bkxSigningStatus") {
    group = "help"
    description = "Show whether BKX debug and release APKs use the permanent personal signing certificate."
    configured.set(bkxPersonalSigningConfigured)
    propertiesPath.set(bkxSigningPropertiesFile.absolutePath)
    keystorePath.set(bkxPersonalSigningStoreFile?.absolutePath.orEmpty())
    keyAlias.set(if (bkxPersonalSigningConfigured) bkxSigningValue("keyAlias") else "")
}

tasks.register<VerifyBkxPersonalSigningConfiguredTask>("verifyBkxPersonalSigningConfigured") {
    group = "verification"
    description = "Fail unless the permanent BKX personal signing configuration is available outside the repository."
    configured.set(bkxPersonalSigningConfigured)
    keystorePath.set(bkxPersonalSigningStoreFile?.absolutePath.orEmpty())
}

tasks.configureEach {
    if (name in setOf("assembleEmbeddedDebug", "assembleLiteDebug", "assembleEmbeddedRelease", "assembleLiteRelease")) {
        mustRunAfter("verifyBkxPersonalSigningConfigured")
    }
}

tasks.register("assemblePersonalDebug") {
    group = "build"
    description = "Build a debug APK using the permanent BKX personal certificate."
    dependsOn("verifyBkxPersonalSigningConfigured", "assembleEmbeddedDebug")
}

tasks.register("assemblePersonalRelease") {
    group = "build"
    description = "Build a release APK using the permanent BKX personal certificate."
    dependsOn("verifyBkxPersonalSigningConfigured", "assembleEmbeddedRelease")
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
    implementation(libs.google.play.services.auth)
    implementation(libs.tasker.plugin.library)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    // JVM Android unit tests need a real JSONObject; android.jar supplies only stubs.
    testImplementation(libs.json.jvm)
    // kotlin.test.Test/assert* and assertFailsWith are used by the chroot contract suite.
    // Bind kotlin.test to our existing JUnit 4 runner rather than rewriting tests.
    testImplementation(libs.kotlin.test.junit)
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
    "Kotlin" to libs.versions.kotlin.get(),
    "Compose BOM" to libs.versions.composeBom.get(),
    "Activity Compose" to libs.versions.activity.get(),
    "AppCompat" to libs.versions.appcompat.get(),
    "Core KTX" to libs.versions.coreKtx.get(),
    "Lifecycle Compose" to libs.versions.lifecycle.get(),
    "libsu" to libs.versions.libsu.get(),
    "Play Services Auth" to libs.versions.playServicesAuth.get(),
    "Tasker Plugin Library" to libs.versions.taskerPlugin.get(),
)

val expectedBkxSdk36DependencyLane = mapOf(
    "AGP" to "9.2.1",
    "Kotlin" to "2.4.10",
    // Compose 1.12+ requires compileSdk 37; keep the stable SDK-36 lane here.
    "Compose BOM" to "2026.06.01",
    "Activity Compose" to "1.12.4",
    "AppCompat" to "1.7.1",
    "Core KTX" to "1.17.0",
    "Lifecycle Compose" to "2.10.0",
    "libsu" to "6.0.0",
    "Play Services Auth" to "21.6.0",
    "Tasker Plugin Library" to "0.4.10",
)

tasks.register("verifyBkxAndroidSdk36DependencyLane") {
    group = "verification"
    description = "Fail closed when BKX Android drifts from the stable Java/Gradle/AGP/SDK/dependency lane."

    inputs.property("bkxGradleVersion", bkxGradleVersion)
    inputs.property("bkxValidationSdk", bkxValidationSdk)
    inputs.property("bkxBuildToolsVersion", bkxBuildToolsVersion)
    inputs.property("bkxJavaVersion", bkxJavaVersion.toString())
    inputs.property("bkxNdkVersion", bkxNdkVersion)
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
        val bkxNdkMajor = bkxNdkVersion.substringBefore('.').toIntOrNull()
            ?: error("BKX Android NDK version is malformed: $bkxNdkVersion")
        check(bkxNdkMajor >= 29) {
            "BKX Android requires NDK r29 or newer; found $bkxNdkVersion."
        }
        check(bkxNdkVersion == "29.0.14206865") {
            "BKX Android currently pins NDK 29.0.14206865 so Devtool auto-resolution cannot select AGP 9's older r28 default."
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


val buildBkxGoAndroidRuntime = tasks.register<BuildBkxGoAndroidRuntimeTask>("buildBkxGoAndroidRuntime") {
    group = "build"
    description = "Build the generated ARM64 Go protocol-v1 runtime packaged by BKX Android."
    repositoryRootPath.set(bkxRepositoryRoot.absolutePath)
    buildScriptPath.set(bkxRepositoryRoot.resolve("scripts/build-go-android-runtime.sh").absolutePath)
    buildCommit.set(bkxBuildCommit)
    outputDirectory.set(bkxGoRuntimeJniDir)
    inputs.file(bkxRepositoryRoot.resolve("go.mod"))
    inputs.file(bkxRepositoryRoot.resolve("go.sum"))
    inputs.file(bkxVersionFile)
    inputs.dir(bkxRepositoryRoot.resolve("cmd/bkx"))
    inputs.dir(bkxRepositoryRoot.resolve("internal"))
    inputs.file(bkxRepositoryRoot.resolve("scripts/build-go-android-runtime.sh"))
    inputs.property("bkxBuildCommit", bkxBuildCommit)
}

val installBkxRcloneAndroidRuntime = tasks.register<InstallBkxRcloneAndroidRuntimeTask>("installBkxRcloneAndroidRuntime") {
    group = "build"
    description = "Build the optional Embedded-edition rclone-compatible ARM64 runtime with 16 KiB page-size alignment."
    repositoryRootPath.set(bkxRepositoryRoot.absolutePath)
    installerScriptPath.set(bkxRepositoryRoot.resolve("scripts/install-rclone-android-runtime.sh").absolutePath)
    ndkDirectoryPath.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
    outputDirectory.set(bkxRcloneRuntimeJniDir)
    provenanceOutputDirectory.set(bkxRcloneRuntimeAssetsDir)
    inputs.file(bkxRepositoryRoot.resolve("scripts/install-rclone-android-runtime.sh"))
    inputs.file(bkxRepositoryRoot.resolve("scripts/rclone-runtime-contract.sh"))
    inputs.file(bkxRepositoryRoot.resolve("scripts/verify-bclone-source-lock.py"))
    inputs.file(bkxRepositoryRoot.resolve("config/bclone-android-source.lock"))
    inputs.file(bkxRepositoryRoot.resolve("scripts/verify-native-page-size.go"))
    inputs.property("rcloneAndroidRuntime", "bclone/source-reviewed-or-development-latest/android-24-arm64/16k")
    // The task is @UntrackedTask because GitHub's latest-release pointer is mutable;
    // the installer itself reuses a verified cache keyed by resolved tag + commit.
}

// A release must prove both APKs independently. Retain the aggregate task name
// as a compatibility alias for BR35–BR37 and existing Devtool task profiles.
val verifyBkxEmbeddedRelease16Kb = tasks.register<VerifyBkxApkNativePageSizeTask>("verifyBkxEmbeddedRelease16Kb") {
    group = "verification"
    description = "Verify 16 KiB ELF segments in the actual Embedded release APK."
    dependsOn("assembleEmbeddedRelease")
    verifierProgramPath.set(bkxRepositoryRoot.resolve("scripts/verify-native-page-size.go").absolutePath)
    minimumPageSize.set(16_384)
    releaseOutputDirectory.set(layout.buildDirectory.dir("outputs/apk/embedded/release"))
    expectedVariant.set("embeddedRelease")
    inputs.file(bkxRepositoryRoot.resolve("scripts/verify-native-page-size.go"))
}
val verifyBkxLiteRelease16Kb = tasks.register<VerifyBkxApkNativePageSizeTask>("verifyBkxLiteRelease16Kb") {
    group = "verification"
    description = "Verify 16 KiB ELF segments in the actual Lite release APK."
    dependsOn("assembleLiteRelease")
    verifierProgramPath.set(bkxRepositoryRoot.resolve("scripts/verify-native-page-size.go").absolutePath)
    minimumPageSize.set(16_384)
    releaseOutputDirectory.set(layout.buildDirectory.dir("outputs/apk/lite/release"))
    expectedVariant.set("liteRelease")
    inputs.file(bkxRepositoryRoot.resolve("scripts/verify-native-page-size.go"))
}
tasks.register("verifyBkx16KbNativePageSizeContract") {
    group = "verification"
    description = "Require independent Embedded and Lite 16 KiB APK verification."
    dependsOn(verifyBkxEmbeddedRelease16Kb, verifyBkxLiteRelease16Kb)
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            buildBkxGoAndroidRuntime,
            BuildBkxGoAndroidRuntimeTask::outputDirectory,
        )
    }
    // AGP registers the rclone producer only on the Embedded flavor's source graph.
    // This is variant-scoped: a previous Embedded build cannot contaminate Lite.
    onVariants(selector().withFlavor("edition" to "embedded")) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            installBkxRcloneAndroidRuntime,
            InstallBkxRcloneAndroidRuntimeTask::outputDirectory,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            installBkxRcloneAndroidRuntime,
            InstallBkxRcloneAndroidRuntimeTask::provenanceOutputDirectory,
        )
    }
}

// Both editions must contain BKX Go; rclone and provenance belong only to Embedded.
// Verify real ZIP members, not generated directories that may contain stale prior outputs.
tasks.register("verifyBkxEditionApkContents") {
    group = "verification"
    description = "Inspect Embedded/Lite release APK member invariants; fails closed on mixed editions."
    dependsOn("assembleEmbeddedRelease", "assembleLiteRelease")
    val embeddedOutput = layout.buildDirectory.dir("outputs/apk/embedded/release")
    val liteOutput = layout.buildDirectory.dir("outputs/apk/lite/release")
    inputs.dir(embeddedOutput)
    inputs.dir(liteOutput)
    doLast {
        fun verify(apk: File, edition: String) {
            check(apk.isFile) { "Missing $edition release APK: ${apk.absolutePath}" }
            ZipFile(apk).use { zip ->
                val members = zip.entries().asSequence().map { it.name }.toSet()
                check("lib/arm64-v8a/libbkxgo.so" in members) { "$edition omitted BKX Go runtime" }
                val rcloneMembers = setOf(
                    "lib/arm64-v8a/libbkxrclone_exec.so",
                    "assets/bkx-rclone-runtime.properties",
                )
                if (edition == "embedded") {
                    check(members.containsAll(rcloneMembers)) { "Embedded release omitted rclone or provenance" }
                } else {
                    check(rcloneMembers.none { it in members }) { "Lite release contains Embedded rclone components" }
                }
                check(members.none { it.startsWith("lib/") && !it.startsWith("lib/arm64-v8a/") }) {
                    "$edition contains unsupported ABI libraries"
                }
            }
        }
        verify(BkxReleaseApkOutputs.locate(embeddedOutput.get().asFile, "embeddedRelease"), "embedded")
        verify(BkxReleaseApkOutputs.locate(liteOutput.get().asFile, "liteRelease"), "lite")
    }
}

// Verification tasks live outside the application build configuration.
apply(from = rootProject.file("../../build-logic/android-verification.gradle.kts"))

// Personal Devtool lanes are fail-closed without making CI/reproducible verification
// require access to the developer's private signing key. When the signing verifier
// is present in a task graph, run it before every other task so missing/invalid
// personal signing fails before expensive compilation, packaging, or lint work.
tasks.configureEach {
    if (name != "verifyBkxPersonalSigningConfigured") {
        mustRunAfter("verifyBkxPersonalSigningConfigured")
    }
}

tasks.register("bkxPersonalReleaseSeal") {
    group = "verification"
    description = "Run the full BKX Android release seal only after permanent personal signing is verified."
    dependsOn("verifyBkxPersonalSigningConfigured", "bkxAndroidReleaseSeal")
}

tasks.register("bkxPersonalPackageAndReleaseLintSeal") {
    group = "verification"
    description = "Run the optimized BKX package/release-lint seal only after permanent personal signing is verified."
    dependsOn("verifyBkxPersonalSigningConfigured", "bkxBr37PackageAndReleaseLintSeal")
}
