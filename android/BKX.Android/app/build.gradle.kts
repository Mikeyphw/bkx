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
        versionCode = 10000
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
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



tasks.register("verifyBkxEmbeddedEngineContract") {
    group = "verification"
    description = "Fail closed when the AOV-13→16 embedded BKX/Chaquopy contract drifts."

    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val versionCatalog = androidRootDir.resolve("gradle/libs.versions.toml")
    val appBuild = androidRootDir.resolve("app/build.gradle.kts")
    val graph = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    val embeddedBackend = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/EmbeddedBkxBackend.kt")
    val androidApi = repositoryRootDir.resolve("src/bkx/android_api/engine.py")

    inputs.files(versionCatalog, appBuild, graph, embeddedBackend, androidApi)

    doLast {
        val catalogText = versionCatalog.readText()
        val appText = appBuild.readText()
        val graphText = graph.readText()
        val engineText = androidApi.readText()

        check(catalogText.contains("chaquopy = \"17.0.0\"")) {
            "AOV-13→16 requires Chaquopy 17.0.0."
        }
        check(catalogText.contains("agp = \"9.2.1\"")) {
            "AOV-13→16 pins AGP 9.2.1, inside Chaquopy 17's tested 9.2.x lane."
        }
        check(appText.contains("minSdk = 24")) {
            "Embedded CPython requires minSdk 24."
        }
        check(bkxEmbeddedPythonVersion in bkxSupportedEmbeddedPythonVersions) {
            "Embedded BKX must use a Chaquopy 17 runtime matched to build Python 3.13 or 3.14."
        }
        check(appText.contains("version = bkxEmbeddedPythonVersion")) {
            "Embedded BKX must derive the packaged Python runtime from the resolved build interpreter."
        }
        check(appText.contains("buildPython(bkxBuildPythonCommand)")) {
            "Chaquopy must receive the explicitly resolved buildPython command."
        }
        check(appText.contains("arm64-v8a") && appText.contains("x86_64")) {
            "Embedded CPython ABI lane must include arm64-v8a and x86_64."
        }
        check(graphText.contains("EmbeddedBkxBackend") && graphText.contains("ChaquopyPythonGateway")) {
            "Production BackendPort must resolve to the embedded BKX backend."
        }
        check(!graphText.contains("TermuxBridgeAdapter(")) {
            "Production graph must not instantiate the legacy Termux adapter after AOV-15."
        }
        check(embeddedBackend.isFile && androidApi.isFile) {
            "Embedded Kotlin/Python boundary files are missing."
        }
        check(!engineText.contains("bkx.__main__") && !engineText.contains("argparse") && !engineText.contains("textual")) {
            "Android embedded engine must call bkx.core directly, never the CLI/TUI entrypoint."
        }
    }
}



tasks.register("verifyBkxRootStorageContract") {
    group = "verification"
    description = "Fail closed when AOV-17→20 root/all-files/Termux-private-storage contracts drift."

    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val manifest = androidRootDir.resolve("app/src/main/AndroidManifest.xml")
    val catalog = androidRootDir.resolve("gradle/libs.versions.toml")
    val graph = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    val rootService = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt")
    val rootClient = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/root/RootBkxServiceClient.kt")
    val storage = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/storage/StorageAccessModels.kt")
    val androidApi = repositoryRootDir.resolve("src/bkx/android_api/engine.py")

    inputs.files(manifest, catalog, graph, rootService, rootClient, storage, androidApi)

    doLast {
        val manifestText = manifest.readText()
        val catalogText = catalog.readText()
        val graphText = graph.readText()
        val rootServiceText = rootService.readText()
        val rootClientText = rootClient.readText()
        val storageText = storage.readText()
        val engineText = androidApi.readText()

        check(catalogText.contains("libsu = \"6.0.0\"")) {
            "AOV-17→20 requires the audited libsu 6.0.0 root transport lane."
        }
        check(manifestText.contains("android.permission.MANAGE_EXTERNAL_STORAGE")) {
            "BKX personal backup build must declare All files access."
        }
        check(manifestText.contains(".root.BkxRootEngineService")) {
            "BKX root engine service is missing from the manifest."
        }
        check(!manifestText.contains("com.termux.permission.RUN_COMMAND") && !manifestText.contains("com.termux.RUN_COMMAND")) {
            "Termux RUN_COMMAND must not return as an execution dependency."
        }
        check(graphText.contains("RootAwarePythonGateway") && graphText.contains("RootBkxServiceClient")) {
            "Production embedded backend must be root-aware."
        }
        check(rootServiceText.contains("RootService") && rootServiceText.contains("Os.getuid()") && rootServiceText.contains("bkx.android_api")) {
            "Root service must run the canonical embedded BKX API and verify UID 0."
        }
        check(!rootServiceText.contains("ProcessBuilder(") && !rootServiceText.contains("su -c")) {
            "Root transport must not expose a shell-command implementation."
        }
        check(rootClientText.contains("Shell.getShell().isRoot") && rootClientText.contains("RootService.bind")) {
            "Root client must use libsu authorization and RootService binding."
        }
        check(
            storageText.contains("/data/data/com.termux/files") &&
                storageText.contains("/data/data/com.termux/files/home") &&
                storageText.contains("/data/data/com.termux/files/home/storage") &&
                storageText.contains("/data/data/com.termux/files/usr")
        ) {
            "Termux private files/home/storage/prefix locations must remain first-class root storage targets."
        }
        check(engineText.contains("root.filesystem") && engineText.contains("storage.termux.private")) {
            "Root BKX Python runtime must advertise root and Termux-private capabilities."
        }
    }
}

tasks.register("verifyBkxAndroidReleaseDocs") {
    group = "verification"
    description = "Fail closed when BKX Android release documentation or setup guides are missing."

    // These files live at the Android project root, not under :app.
    // Resolve them during configuration so the execution action captures
    // only plain File values and remains configuration-cache compatible.
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val requiredDocs = listOf(
        "docs/FINAL_RELEASE_VALIDATION.md",
        "docs/SETUP_GUIDE.md",
        "docs/SIGNING_PLACEHOLDERS.md",
        "docs/AOV_11_12_AUDIT.md",
        "docs/AOV_25_32_AUDIT.md",
        "docs/AOV_13_32_COMPLETENESS_REPLAY.md",
    ).map { androidRootDir.resolve(it) }

    inputs.files(requiredDocs)

    doLast {
        val missing = requiredDocs
            .filterNot { it.isFile }
            .map { it.relativeTo(androidRootDir).invariantSeparatorsPath }

        check(missing.isEmpty()) {
            "Missing BKX Android release docs: ${missing.joinToString()}"
        }
    }
}

tasks.register("verifyBkxAndroidSigningPlaceholders") {
    group = "verification"
    description = "Fail closed when release signing placeholders are absent or real secrets are accidentally committed."

    // Signing files also live at the Android project root.
    val androidRootDir = rootProject.layout.projectDirectory.asFile

    val example =
        androidRootDir.resolve("gradle/bkx-release-signing.properties.example")

    val forbidden = listOf(
        androidRootDir.resolve("gradle/bkx-release-signing.properties"),
        androidRootDir.resolve("release.keystore"),
        androidRootDir.resolve("bkx-release.jks"),
    )

    inputs.file(example)
    inputs.files(forbidden)

    doLast {
        check(example.isFile) {
            "Missing signing placeholder example: ${
                example.relativeTo(androidRootDir).invariantSeparatorsPath
            }"
        }

        val present = forbidden.filter { it.exists() }

        check(present.isEmpty()) {
            "Refusing release seal with local signing secrets inside project tree: " +
                present.joinToString {
                    it.relativeTo(androidRootDir).invariantSeparatorsPath
                }
        }
    }
}

tasks.register("verifyBkxAndroidManifestReleaseContract") {
    group = "verification"
    description = "Fail closed when the Android manifest omits notification or foreground-service release contracts."

    // Resolve during configuration. doLast must not reach back into Project.
    val manifestFile =
        layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile

    inputs.file(manifestFile)

    doLast {
        val manifest = manifestFile.readText()

        val required = listOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "BkxOperationForegroundService",
            "android:foregroundServiceType=\"dataSync\"",
        )

        val missing = required.filterNot { manifest.contains(it) }

        check(missing.isEmpty()) {
            "Missing Android release manifest contract markers: ${
                missing.joinToString()
            }"
        }
    }
}

tasks.register("bkxAndroidReleaseSeal") {
    group = "verification"
    description = "Fail-closed BKX Android release gate: SDK/dependency compatibility, lint, unit tests, instrumentation tests, packages, docs, signing placeholders, and manifest contracts."
    dependsOn(
        "lintDebug",
        "testDebugUnitTest",
        "connectedDebugAndroidTest",
        "assembleDebug",
        "assembleRelease",
        "verifyBkxAndroidSdk36DependencyLane",
        "verifyBkxEmbeddedEngineContract",
        "verifyBkxRootStorageContract",
        "verifyBkxProductUiContract",
        "verifyBkxAndroidReleaseDocs",
        "verifyBkxAndroidSigningPlaceholders",
        "verifyBkxAndroidManifestReleaseContract",
    )
}



tasks.register("verifyBkxProductUiContract") {
    group = "verification"
    description = "Fail closed when AOV-21→24 product navigation, Material 3 UI, and user-facing terminology drift."

    val sourceRoot = layout.projectDirectory.dir("src/main/java/com/mikeyphw/bkx/android").asFile
    val navigation = sourceRoot.resolve("app/navigation/BkxNavigation.kt")
    val shell = sourceRoot.resolve("feature/home/BkxProductApp.kt")
    val home = sourceRoot.resolve("feature/home/HomeScreen.kt")
    val backup = sourceRoot.resolve("feature/backup/BackupScreen.kt")
    val archives = sourceRoot.resolve("feature/archives/ArchivesScreen.kt")
    val activity = sourceRoot.resolve("feature/activity/ActivityScreen.kt")
    val more = sourceRoot.resolve("feature/more/MoreScreen.kt")
    val theme = sourceRoot.resolve("app/ui/theme/BkxTheme.kt")

    inputs.files(navigation, shell, home, backup, archives, activity, more, theme)

    doLast {
        val navigationText = navigation.readText()
        val shellText = shell.readText()
        val productSources = listOf(home, backup, archives, activity, more).associateWith { it.readText() }
        val themeText = theme.readText()

        listOf("Home", "Backup", "Archives", "Activity", "More").forEach { destination ->
            check("$destination(" in navigationText) { "AOV-21→24 primary destination missing: $destination" }
        }
        listOf("Restore(\"Restore\")", "Transfers(\"Transfers\")", "Tools(\"Tools\")", "Settings(\"Settings\")").forEach { old ->
            check(old !in navigationText) { "Legacy developer-style primary destination returned: $old" }
        }
        check("NavigationBar" in shellText && "NavigationRail" in shellText) {
            "BKX product shell must provide phone bottom navigation and wide-screen navigation rail."
        }
        check("dynamicLightColorScheme" in themeText && "AppearancePreference" in themeText) {
            "BKX theme must support Material 3 dynamic color and persisted appearance preference."
        }
        val forbiddenUserTerms = listOf("RUN_COMMAND", "Termux backend", "bridge request", "request JSON", "response JSON", "Preview token:")
        productSources.forEach { (file, text) ->
            forbiddenUserTerms.forEach { forbidden ->
                check(forbidden !in text) { "User-facing product UI leaks internal term '$forbidden' in ${file.name}." }
            }
        }
        check("RequestBackupPlan" in backup.readText() && "CreateBackup" in backup.readText()) {
            "Backup product screen must invoke real embedded BKX plan/create actions."
        }
        check("RequestRestorePreview" in archives.readText() && "ExecuteRestore" in archives.readText()) {
            "Archives product screen must expose contextual restore review/execution."
        }
        check("Search archives" in archives.readText() && "ArchiveSort" in archives.readText() && "ArchiveEntryFilter" in archives.readText()) {
            "Archives product screen must provide archive search/sort and loaded-entry filtering."
        }
        check("RequestTransferPreview" in more.readText() && "RunTransfer" in more.readText()) {
            "More screen must expose real local transfer review/execution."
        }
    }
}

// AOV-25 -> AOV-32 final hardening and release-seal gates.
tasks.register("verifyBkxRuntimeHardeningContract") {
    group = "verification"
    description = "Verify process-scoped execution, interruption recovery, cooperative progress/cancellation, and dynamic root routing."
    val sourceRoot = layout.projectDirectory.dir("src/main/java/com/mikeyphw/bkx/android").asFile
    val coordinator = sourceRoot.resolve("app/runtime/BkxOperationCoordinator.kt")
    val application = sourceRoot.resolve("app/BkxAndroidApplication.kt")
    val graph = sourceRoot.resolve("app/di/DefaultBackendGraph.kt")
    val gateway = sourceRoot.resolve("embedded/RootAwarePythonGateway.kt")
    val operations = sourceRoot.resolve("core/operations/OperationModels.kt")
    inputs.files(coordinator, application, graph, gateway, operations)
    doLast {
        val coordinatorText = coordinator.readText()
        val applicationText = application.readText()
        val graphText = graph.readText()
        val gatewayText = gateway.readText()
        val operationsText = operations.readText()
        check("SupervisorJob() + Dispatchers.IO" in coordinatorText) { "Long BKX work must be application-scoped, not ViewModel-scoped." }
        check("recoverInterruptedOperations" in applicationText && "OperationFailureMapper.interrupted" in coordinatorText) {
            "Persisted in-flight work must be classified as interrupted after process death."
        }
        check("startProgressPoller" in coordinatorText && "backendPort.progress" in coordinatorText) {
            "Embedded progress must be consumed while an operation is active."
        }
        check("foregroundOperationMutex" in coordinatorText) { "Long foreground operations must serialize their service lifecycle." }
        check("modeProvider = { settings.readNow().rootAccessMode }" in graphText) { "Root preference must resolve dynamically for the next operation." }
        check("requestRoutes" in gatewayText && "requestIdFrom" in gatewayText) { "Root/local execution route must stay pinned for progress and cancellation of an active request." }
        check("Interrupted" in operationsText && "bounded(" in operationsText) { "Interrupted state and bounded operation history must remain persisted contracts." }
    }
}

tasks.register("verifyBkxRestoreSafetySeal") {
    group = "verification"
    description = "Verify root-sensitive restore confirmation, protected Android destinations, and exact destination semantics."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val restoreModels = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/restore/RestoreModels.kt").asFile
    val restoreUi = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/feature/archives/ArchivesScreen.kt").asFile
    val engine = repositoryRootDir.resolve("src/bkx/android_api/engine.py")
    inputs.files(restoreModels, restoreUi, engine)
    doLast {
        val models = restoreModels.readText()
        val ui = restoreUi.readText()
        val python = engine.readText()
        listOf("/proc", "/sys", "/dev", "/system", "/metadata", "/data/system", "/data/misc").forEach { root ->
            check(root in models && root in python) { "Protected restore root drifted between Kotlin/Python policy: $root" }
        }
        check("ROOT_CONFIRMATION_PHRASE = \"RESTORE ROOT\"" in models && "root_confirmation" in python) {
            "Root-sensitive restore must require explicit RESTORE ROOT evidence."
        }
        check("if (destination == null)" in models) { "Every Android restore mode must require an explicit destination." }
        check("does not already exist" in python && "requires an existing directory" in python) {
            "Fresh/existing restore destination semantics must fail closed in the canonical embedded boundary."
        }
        check("New destination path" in ui && "Existing destination path" in ui) { "Restore UI must collect an exact path for both output modes." }
    }
}

tasks.register("verifyBkxPerformanceAndProgressContract") {
    group = "verification"
    description = "Verify bounded runtime state, operation duration metrics, and cooperative progress/cancellation callbacks."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val operations = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/operations/OperationModels.kt").asFile
    val bridge = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/bridge/BridgeModels.kt").asFile
    val engine = repositoryRootDir.resolve("src/bkx/android_api/engine.py")
    val archiver = repositoryRootDir.resolve("src/bkx/core/impl/archiver.py")
    val coordinator = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/app/runtime/BkxOperationCoordinator.kt").asFile
    val foregroundService = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/app/service/BkxOperationForegroundService.kt").asFile
    inputs.files(operations, bridge, engine, archiver, coordinator, foregroundService)
    doLast {
        val operationText = operations.readText()
        val bridgeText = bridge.readText()
        val engineText = engine.readText()
        val archiverText = archiver.readText()
        val coordinatorText = coordinator.readText()
        val serviceText = foregroundService.readText()
        check("maxOperations: Int = 64" in operationText && "maxEventsPerRequest: Int = 200" in operationText) {
            "Operation persistence must remain bounded."
        }
        check("duration_ms" in bridgeText && "duration_ms" in engineText) { "Embedded results must retain measured duration metrics." }
        check("run.progress" in engineText && "run.cancel.cooperative" in engineText) { "Embedded runtime must advertise progress and cooperative cancellation." }
        check("progress_callback" in archiverText) { "Archive create/verify/restore paths must keep incremental progress callbacks." }
        check("MutableStateFlow(OperationRuntimeState())" in coordinatorText && "progressPreferenceProvider" in coordinatorText) {
            "Live runtime progress and user-selected polling cadence must remain production-wired."
        }
        check("override fun onTimeout" in serviceText && "operationCoordinator(this@BkxOperationForegroundService).cancel" in serviceText) {
            "Foreground-service timeout must cooperatively cancel rather than leave unprotected filesystem work running."
        }
    }
}

tasks.register("verifyBkxUxHardeningContract") {
    group = "verification"
    description = "Verify adaptive Material 3 UX, truthful symlink behavior, and recovery/navigation affordances."
    val sourceRoot = layout.projectDirectory.dir("src/main/java/com/mikeyphw/bkx/android").asFile
    val shell = sourceRoot.resolve("feature/home/BkxProductApp.kt")
    val backup = sourceRoot.resolve("feature/backup/BackupScreen.kt")
    val archives = sourceRoot.resolve("feature/archives/ArchivesScreen.kt")
    val activity = sourceRoot.resolve("feature/activity/ActivityScreen.kt")
    inputs.files(shell, backup, archives, activity)
    doLast {
        val shellText = shell.readText()
        val backupText = backup.readText()
        val archivesText = archives.readText()
        val activityText = activity.readText()
        check("NavigationBar" in shellText && "NavigationRail" in shellText) { "Phone/tablet adaptive navigation is required." }
        check("Symbolic links are skipped safely" in backupText && "Follow symbolic links" !in backupText) {
            "Product UI must not claim unsupported link following."
        }
        check("RESTORE ROOT" in archivesText || "requiredPhrase" in archivesText) { "Privileged restore confirmation must be visible in the product flow." }
        check("active" in activityText.lowercase()) { "Activity must continue to expose active operation state." }
    }
}

val verifyBkxEmbeddedParitySeal = tasks.register<Exec>("verifyBkxEmbeddedParitySeal") {
    group = "verification"
    description = "Run the stdlib-only direct bkx.core versus embedded Android API parity seal."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val script = androidRootDir.resolve("scripts/verify-embedded-parity.py")
    inputs.files(script, repositoryRootDir.resolve("src/bkx/android_api"), repositoryRootDir.resolve("src/bkx/core"))
    workingDir(repositoryRootDir)
    environment("PYTHONPATH", repositoryRootDir.resolve("src").absolutePath)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine(System.getenv("PYTHON")?.takeIf { it.isNotBlank() } ?: "python3", script.absolutePath)
}

tasks.register("verifyBkxReleaseHygiene") {
    group = "verification"
    description = "Reject obsolete Termux execution, generic privileged command APIs, generated source junk, and committed signing material."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val productionRoots = listOf(
        androidRootDir.resolve("app/src/main"),
        repositoryRootDir.resolve("src/bkx/android_api"),
    )
    inputs.files(productionRoots)
    doLast {
        val sourceFiles = productionRoots.flatMap { root ->
            if (!root.exists()) emptyList() else root.walkTopDown().filter { it.isFile }.toList()
        }
        val forbiddenText = listOf("com.termux.permission.RUN_COMMAND", "com.termux.RUN_COMMAND", "TermuxBridgeAdapter(", "termux_run_command", "TermuxRunCommand", "ProcessBuilder(", "su -c", "eval(", "exec(")
        sourceFiles.filter { it.extension in setOf("kt", "kts", "java", "xml", "py", "aidl") }.forEach { file ->
            val text = file.readText()
            forbiddenText.forEach { token -> check(token !in text) { "Release hygiene rejected '$token' in ${file.relativeTo(repositoryRootDir)}" } }
        }
        val junk = repositoryRootDir.walkTopDown()
            .onEnter { dir -> dir.name !in setOf(".git", ".gradle", "build", "artifacts", ".devtool") }
            .filter { file -> file.isFile && (file.extension == "pyc" || file.extension == "apk" || file.extension == "jks" || file.extension == "keystore") }
            .toList()
        check(junk.isEmpty()) { "Release hygiene rejected generated/secret files: ${junk.joinToString { it.relativeTo(repositoryRootDir).path }}" }
        val cacheDirs = repositoryRootDir.walkTopDown()
            .onEnter { it.name !in setOf(".git", ".gradle", "build", "artifacts", ".devtool") }
            .filter { it.isDirectory && it.name == "__pycache__" }
            .toList()
        check(cacheDirs.isEmpty()) { "Release hygiene rejected Python caches under source tree." }
    }
}

tasks.register("verifyBkxDeviceQualificationContract") {
    group = "verification"
    description = "Verify the explicit rooted physical-device qualification test and launcher are present."
    val test = layout.projectDirectory.file("src/androidTest/java/com/mikeyphw/bkx/android/release/ReleaseQualificationInstrumentationTest.kt").asFile
    val script = rootProject.layout.projectDirectory.file("scripts/qualify-device.sh").asFile
    inputs.files(test, script)
    doLast {
        val testText = test.readText()
        val scriptText = script.readText()
        check("bkxDeviceQualification" in testText && "MANAGE_EXTERNAL_STORAGE" in scriptText) { "Device qualification must be explicitly opt-in and grant All files access." }
        check("TERMUX_FILES" in testText && "isRoot" in testText && "RootAccessMode.Disabled" in testText) {
            "Device qualification must prove Termux-private root access and local embedded fallback."
        }
    }
}



val verifyBkxLargeTreePerformanceSeal = tasks.register<Exec>("verifyBkxLargeTreePerformanceSeal") {
    group = "verification"
    description = "Profile a representative nested Android backup tree and enforce bounded Python memory/time."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val script = androidRootDir.resolve("scripts/verify-large-tree-profile.py")
    inputs.files(script, repositoryRootDir.resolve("src/bkx/android_api"), repositoryRootDir.resolve("src/bkx/core"))
    workingDir(repositoryRootDir)
    environment("PYTHONPATH", repositoryRootDir.resolve("src").absolutePath)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine(System.getenv("PYTHON")?.takeIf { it.isNotBlank() } ?: "python3", script.absolutePath)
}

tasks.register("verifyBkxStorageCompletenessContract") {
    group = "verification"
    description = "Verify direct/root path browsing, removable-volume discovery, archive indexing and provider-only SAF staging are wired into production UI."
    val root = layout.projectDirectory.dir("src/main/java/com/mikeyphw/bkx/android").asFile
    val viewModel = root.resolve("app/BkxAppViewModel.kt")
    val storage = root.resolve("storage/AndroidStorageAccessManager.kt")
    val provider = root.resolve("files/AndroidProviderStagingManager.kt")
    val browser = root.resolve("feature/home/FilesystemBrowserDialog.kt")
    val backup = root.resolve("feature/backup/BackupScreen.kt")
    val archives = root.resolve("feature/archives/ArchivesScreen.kt")
    val more = root.resolve("feature/more/MoreScreen.kt")
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val engine = repositoryRootDir.resolve("src/bkx/android_api/engine.py")
    val archiveResults = repositoryRootDir.resolve("src/bkx/core/results/archive.py")
    val archiver = repositoryRootDir.resolve("src/bkx/core/impl/archiver.py")
    inputs.files(viewModel, storage, provider, browser, backup, archives, more, engine, archiveResults, archiver)
    doLast {
        val vm = viewModel.readText()
        check("filesystem.list" in vm && "OpenFilesystemBrowser" in vm) { "Product filesystem browser must execute canonical filesystem.list." }
        check("archiveRootsProvider" in vm && "sizeBytes = artifact.sizeBytes" in vm) { "Archive index must consume discovered roots and real metadata." }
        check("storageVolumes" in storage.readText() && "archiveSearchRoots" in storage.readText()) { "Android removable-volume discovery must remain wired." }
        val providerText = provider.readText()
        check("copyDocument" in providerText && "copyTree" in providerText && "COPY_BUFFER_BYTES" in providerText) { "Provider-only SAF resources must stream into bounded staging." }
        check("totals.entries < MAX_TREE_ENTRIES" in providerText && "totals.entries += 1" in providerText) { "Provider staging must bound total tree entries, including directories." }
        check("ImportProviderBackupTree" in backup.readText() && "ImportProviderArchive" in archives.readText() && "ImportProviderTransferTree" in more.readText()) {
            "Provider fallback must be reachable from backup, archive, and transfer product workflows."
        }
        check("FilesystemBrowserDialog" in browser.readText()) { "Reusable root-aware filesystem selector is missing." }
        check("entry_type" in archiveResults.readText() && "member.issym()" in archiver.readText() && "archive_entry_symlink" in engine.readText() && "archive_entry_special" in engine.readText()) {
            "Archive Link/Special product filters must be backed by canonical archive-entry type metadata."
        }
    }
}

tasks.register("verifyBkxAccessibilityVisualContract") {
    group = "verification"
    description = "Verify product navigation semantics and opt-in physical-device screenshot qualification."
    val shell = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/feature/home/BkxProductApp.kt").asFile
    val test = layout.projectDirectory.file("src/androidTest/java/com/mikeyphw/bkx/android/release/ProductAccessibilityVisualQualificationTest.kt").asFile
    val script = rootProject.layout.projectDirectory.file("scripts/qualify-device.sh").asFile
    inputs.files(shell, test, script)
    doLast {
        val shellText = shell.readText()
        val testText = test.readText()
        val scriptText = script.readText()
        listOf("nav-home", "nav-backup", "nav-archives", "nav-activity", "nav-more").forEach { tag ->
            check(tag in shellText || "nav-${'$'}{destination.name.lowercase()}" in shellText) { "Adaptive navigation is missing stable test tags." }
        }
        check("createAndroidComposeRule<MainActivity>" in testText && "assertExists" in testText && "assertHasClickAction" in testText) { "Accessibility instrumentation must exercise product navigation semantics." }
        check("captureToImage" in testText && "bkxVisualQualification" in testText) { "Physical-device visual qualification must capture product screenshots." }
        check("bkxVisualQualification" in scriptText && "qualification-screenshots" in scriptText) { "Qualification launcher must request and collect screenshots." }
    }
}

tasks.register("verifyBkxFinalReleaseContract") {
    group = "verification"
    description = "Final AOV-32 source/version/document contract."
    val buildFile = layout.projectDirectory.file("build.gradle.kts").asFile
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val audit = androidRootDir.resolve("docs/AOV_25_32_AUDIT.md")
    val replay = androidRootDir.resolve("docs/AOV_13_32_COMPLETENESS_REPLAY.md")
    val validation = androidRootDir.resolve("docs/FINAL_RELEASE_VALIDATION.md")
    inputs.files(buildFile, audit, replay, validation)
    doLast {
        val buildText = buildFile.readText()
        check("versionCode = 10000" in buildText && "versionName = \"1.0.0\"" in buildText) { "AOV-32 must seal BKX Android 1.0.0/versionCode 10000." }
        check(audit.isFile && replay.isFile && validation.isFile) { "Final AOV-13→32 audit/validation documentation is missing." }
        check("AOV-32" in audit.readText() && "every promise" in replay.readText() && "qualify-device.sh" in validation.readText()) { "Final docs do not describe the completeness/release/device seal." }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn(
        "verifyBkxRuntimeHardeningContract",
        "verifyBkxRestoreSafetySeal",
        "verifyBkxPerformanceAndProgressContract",
        verifyBkxLargeTreePerformanceSeal,
        "verifyBkxStorageCompletenessContract",
        "verifyBkxAccessibilityVisualContract",
        "verifyBkxUxHardeningContract",
        verifyBkxEmbeddedParitySeal,
        "verifyBkxReleaseHygiene",
        "verifyBkxDeviceQualificationContract",
        "verifyBkxFinalReleaseContract",
    )
}
