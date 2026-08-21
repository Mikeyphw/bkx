plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val bkxValidationSdk = 36
val bkxBuildToolsVersion = "36.0.0"
val bkxJavaVersion = JavaVersion.VERSION_21

android {
    namespace = "com.mikeyphw.bkx.android"
    compileSdk = bkxValidationSdk
    buildToolsVersion = bkxBuildToolsVersion

    defaultConfig {
        applicationId = "com.mikeyphw.bkx.android"
        minSdk = 24
        targetSdk = bkxValidationSdk
        versionCode = 1
        versionName = "0.1.0-aov24"
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
        version = "3.13"
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
    inputs.properties(bkxSdk36DependencyLane)
    inputs.file(bkxVersionCatalogFile)

    doLast {
        check(bkxGradleVersion == "9.7.0") {
            "BKX Android must run with Gradle 9.7.0; found $bkxGradleVersion."
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
        check(appText.contains("version = \"3.13\"")) {
            "Embedded BKX must stay on Python 3.13 for this lane."
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
