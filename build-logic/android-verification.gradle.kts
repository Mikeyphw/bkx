tasks.register("verifyBkxEmbeddedEngineContract") {
    group = "verification"
    description = "Fail closed when the packaged Android BKX engine is not Go-only."

    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val versionCatalog = androidRootDir.resolve("gradle/libs.versions.toml")
    val rootBuild = androidRootDir.resolve("build.gradle.kts")
    val appBuild = androidRootDir.resolve("app/build.gradle.kts")
    val graph = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    val embeddedBackend = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/EmbeddedBkxBackend.kt")
    val goGateway = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt")
    val buildScript = repositoryRootDir.resolve("scripts/build-go-android-runtime.sh")
    val retired = listOf(
        androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/ChaquopyPythonGateway.kt"),
        androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/EmbeddedPythonGateway.kt"),
        androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/NativePreferredGateway.kt"),
        androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/GoRuntimeCutoverPolicy.kt"),
    )

    inputs.files(versionCatalog, rootBuild, appBuild, graph, embeddedBackend, goGateway, buildScript)

    doLast {
        val catalogText = versionCatalog.readText()
        val rootBuildText = rootBuild.readText()
        val appText = appBuild.readText()
        val graphText = graph.readText()
        val backendText = embeddedBackend.readText()
        val gatewayText = goGateway.readText()
        val scriptText = buildScript.readText()

        check("chaquopy" !in catalogText.lowercase()) {
            "GO14 Android must not retain a Chaquopy version or plugin alias."
        }
        check("libs.plugins.chaquopy" !in rootBuildText && "libs.plugins.chaquopy" !in appText) {
            "GO14 Android must not apply the Chaquopy Gradle plugin."
        }
        listOf("chaquopy {", "buildPython(", "bkxEmbeddedPythonVersion", "bkxBuildPythonCommand").forEach { forbidden ->
            check(forbidden !in appText) { "GO14 Android build still contains retired Python packaging token '$forbidden'." }
        }
        check(appText.contains("minSdk = 24")) { "BKX Android minSdk policy unexpectedly changed during GO14." }
        check(appText.contains("abiFilters += listOf(\"arm64-v8a\")") && "x86_64" !in appText) {
            "GO14 Android must be explicitly arm64-v8a-only after Python fallback retirement."
        }
        check(graphText.contains("EmbeddedBkxBackend") && graphText.contains("GoNativeGateway(appContext)")) {
            "Production BackendPort must resolve directly to the packaged Go runtime."
        }
        check("ChaquopyPythonGateway" !in graphText && "NativePreferredGateway" !in graphText) {
            "GO14 production graph still contains an Android Python fallback selector."
        }
        check(retired.none { it.exists() }) {
            "GO14 Android still contains retired Python/fallback gateway source files: " +
                retired.filter { it.exists() }.joinToString { it.name }
        }
        check("setOf(\"go-native-android\")" in backendText && "embedded-cpython" !in backendText) {
            "GO14 backend must accept only the go-native-android runtime identity."
        }
        check("const val ABI = \"arm64-v8a\"" in gatewayText) {
            "GO14 Go gateway must be sealed to the packaged arm64-v8a ABI."
        }
        check("GOOS=android GOARCH=arm64" in scriptText && "expected AArch64 (183)" in scriptText) {
            "GO14 runtime builder is missing the ARM64/AArch64 lane."
        }
        check("GOARCH=amd64" !in scriptText && "x86_64" !in scriptText) {
            "GO14 must not claim an unsupported CGO-free Android x86_64 runtime lane."
        }
    }
}



tasks.register("verifyBkxRootStorageContract") {
    group = "verification"
    description = "Fail closed when root/all-files/Termux-private-storage contracts drift."

    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val manifest = androidRootDir.resolve("app/src/main/AndroidManifest.xml")
    val catalog = androidRootDir.resolve("gradle/libs.versions.toml")
    val graph = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    val rootService = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt")
    val rootClient = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/root/RootBkxServiceClient.kt")
    val storage = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/storage/StorageAccessModels.kt")
    val goBridge = repositoryRootDir.resolve("internal/bridge/bridge.go")

    inputs.files(manifest, catalog, graph, rootService, rootClient, storage, goBridge)

    doLast {
        val manifestText = manifest.readText()
        val catalogText = catalog.readText()
        val graphText = graph.readText()
        val rootServiceText = rootService.readText()
        val rootClientText = rootClient.readText()
        val storageText = storage.readText()
        val bridgeText = goBridge.readText()

        check(catalogText.contains("libsu = \"6.0.0\"")) {
            "Root transport requires the audited libsu 6.0.0 lane."
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
        check(graphText.contains("RootAwareGateway") && graphText.contains("RootBkxServiceClient")) {
            "Production embedded backend must be root-aware."
        }
        check(rootServiceText.contains("RootService") && rootServiceText.contains("Os.getuid()") && rootServiceText.contains("GoNativeGateway")) {
            "Root service must run the canonical Go protocol runtime and verify UID 0."
        }
        check(!rootServiceText.contains("ProcessBuilder(") && !rootServiceText.contains("su -c") && !rootServiceText.contains("com.chaquo.python")) {
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
        check(bridgeText.contains("root.filesystem") && bridgeText.contains("storage.termux.private")) {
            "Root BKX Go runtime must advertise root and Termux-private capabilities."
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
        "docs/RELEASE_VALIDATION.md",
        "docs/RELEASE_CONTRACT.md",
        "docs/ARCHITECTURE.md",
        "docs/SETUP_GUIDE.md",
        "docs/SIGNING_PLACEHOLDERS.md",
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
    description = "Fail closed when product navigation, Material 3 UI, and user-facing terminology drift."

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
            check("$destination(" in navigationText) { "Primary destination missing: $destination" }
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

// Final hardening and release-seal gates.
tasks.register("verifyBkxRuntimeHardeningContract") {
    group = "verification"
    description = "Verify process-scoped execution, interruption recovery, cooperative progress/cancellation, and dynamic root routing."
    val sourceRoot = layout.projectDirectory.dir("src/main/java/com/mikeyphw/bkx/android").asFile
    val coordinator = sourceRoot.resolve("app/runtime/BkxOperationCoordinator.kt")
    val application = sourceRoot.resolve("app/BkxAndroidApplication.kt")
    val graph = sourceRoot.resolve("app/di/DefaultBackendGraph.kt")
    val gateway = sourceRoot.resolve("embedded/RootAwareGateway.kt")
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
        val forbiddenText = listOf("com.termux.permission.RUN_COMMAND", "com.termux.RUN_COMMAND", "TermuxBridgeAdapter(", "termux_run_command", "TermuxRunCommand", "su -c", "eval(", "exec(")
        sourceFiles.filter { it.extension in setOf("kt", "kts", "java", "xml", "py", "aidl") }.forEach { file ->
            val text = file.readText()
            forbiddenText.forEach { token -> check(token !in text) { "Release hygiene rejected '$token' in ${file.relativeTo(repositoryRootDir)}" } }
            if ("ProcessBuilder(" in text) {
                check(file.name == "GoNativeGateway.kt") { "ProcessBuilder is allowed only in the app-owned Go runtime launcher." }
                check("applicationInfo.nativeLibraryDir" in text && "android-runtime" in text && "bridge-session" in text) {
                    "Go runtime ProcessBuilder must launch only the packaged protocol bridge executable."
                }
            }
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



tasks.register("verifyBkxGoProductionCutoverContract") {
    group = "verification"
    description = "Verify the production Go route, privileged Go route, and packaged ARM64 runtime."
    dependsOn("buildBkxGoAndroidRuntime")

    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val graph = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    val application = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/app/BkxAndroidApplication.kt")
    val rootGateway = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/RootAwareGateway.kt")
    val rootService = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt")
    val goGateway = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt")
    val localDeviceTest = androidRootDir.resolve("app/src/androidTest/java/com/mikeyphw/bkx/android/embedded/GoProductionCutoverInstrumentationTest.kt")
    val qualificationTest = androidRootDir.resolve("app/src/androidTest/java/com/mikeyphw/bkx/android/release/ReleaseQualificationInstrumentationTest.kt")
    val runtime = layout.buildDirectory.file("generated/bkx-go-runtime/jniLibs/arm64-v8a/libbkxgo.so")

    inputs.files(graph, application, rootGateway, rootService, goGateway, localDeviceTest, qualificationTest)
    inputs.file(runtime)

    doLast {
        val graphText = graph.readText()
        val applicationText = application.readText()
        val rootText = rootService.readText()
        val gatewayText = goGateway.readText()
        val localTestText = localDeviceTest.readText()
        val qualificationText = qualificationTest.readText()

        check("RootAwareGateway" in graphText && "val local = GoNativeGateway(appContext)" in graphText) {
            "Production graph is not routed directly through the packaged Go runtime."
        }
        check("ChaquopyPythonGateway" !in graphText && "NativePreferredGateway" !in graphText) {
            "Production graph still contains a retired Python/fallback route."
        }
        check("Python.start(" !in applicationText) { "Android Application must not initialize embedded Python." }
        check("GoNativeGateway(baseContext, stateNamespace = ROOT_STATE_NAMESPACE)" in rootText) {
            "Privileged RootService must launch the packaged Go runtime."
        }
        check("Os.getuid() == ROOT_UID" in rootText && "check(goGateway.isAvailable())" in rootText) {
            "Privileged Go route must verify UID 0 and fail closed when the runtime is unavailable."
        }
        check("com.chaquo.python" !in rootText && "bkx.android_api" !in rootText && "ProcessBuilder(" !in rootText) {
            "Privileged service must not retain Python or arbitrary process execution."
        }
        check("LOCAL_STATE_NAMESPACE = \"go-local\"" in gatewayText && "setOf(LOCAL_STATE_NAMESPACE, \"go-root\")" in gatewayText) {
            "Local/root Go state ownership isolation is missing."
        }
        check("go-native-android" in localTestText) {
            "Local production Go instrumentation is missing."
        }
        check("Privileged production route must be Go-native" in qualificationText && "Local production route must be Go-native" in qualificationText) {
            "Physical-device qualification must prove both Go production routes."
        }

        val runtimeFile = runtime.get().asFile
        check(runtimeFile.isFile && runtimeFile.canExecute()) { "Generated ARM64 Go runtime is missing or not executable." }
        val bytes = runtimeFile.readBytes()
        check(bytes.size >= 20 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            "Generated ARM64 runtime is not ELF."
        }
        val littleEndian = bytes[5].toInt() == 1
        val machine = if (littleEndian) {
            (bytes[18].toInt() and 0xff) or ((bytes[19].toInt() and 0xff) shl 8)
        } else {
            ((bytes[18].toInt() and 0xff) shl 8) or (bytes[19].toInt() and 0xff)
        }
        check(machine == 183) { "Generated ARM64 runtime ELF e_machine=$machine, expected AArch64 (183)." }
    }
}

tasks.register("verifyBkxGoOnlyAndroidRuntimeContract") {
    group = "verification"
    description = "GO14 seal: Android is arm64-v8a Go-only with no Chaquopy fallback."
    dependsOn("buildBkxGoAndroidRuntime")

    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val appBuild = androidRootDir.resolve("app/build.gradle.kts")
    val rootBuild = androidRootDir.resolve("build.gradle.kts")
    val catalog = androidRootDir.resolve("gradle/libs.versions.toml")
    val graph = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    val backend = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/EmbeddedBkxBackend.kt")
    val gateway = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt")
    val buildScript = repositoryRootDir.resolve("scripts/build-go-android-runtime.sh")
    val phaseFile = repositoryRootDir.resolve("internal/version/version.go")
    val arm64Runtime = layout.buildDirectory.file("generated/bkx-go-runtime/jniLibs/arm64-v8a/libbkxgo.so")
    val retired = listOf(
        androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/ChaquopyPythonGateway.kt"),
        androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/EmbeddedPythonGateway.kt"),
        androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/NativePreferredGateway.kt"),
        androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/GoRuntimeCutoverPolicy.kt"),
    )

    inputs.files(appBuild, rootBuild, catalog, graph, backend, gateway, buildScript, phaseFile)
    inputs.file(arm64Runtime)

    doLast {
        val appText = appBuild.readText()
        val rootBuildText = rootBuild.readText()
        val catalogText = catalog.readText()
        val graphText = graph.readText()
        val backendText = backend.readText()
        val gatewayText = gateway.readText()
        val scriptText = buildScript.readText()
        val phaseText = phaseFile.readText()

        check("MigrationPhase = \"GO14\"" in phaseText) { "GO14 phase identity is missing." }
        check("chaquopy" !in catalogText.lowercase() && "libs.plugins.chaquopy" !in rootBuildText && "libs.plugins.chaquopy" !in appText) {
            "GO14 Android still has a Chaquopy Gradle/plugin dependency."
        }
        check(retired.none { it.exists() }) {
            "GO14 Android still contains retired Python/fallback source files: " + retired.filter { it.exists() }.joinToString { it.name }
        }
        check("val local = GoNativeGateway(appContext)" in graphText && "NativePreferredGateway" !in graphText && "ChaquopyPythonGateway" !in graphText) {
            "GO14 local production graph is not Go-only."
        }
        check("setOf(\"go-native-android\")" in backendText && "embedded-cpython" !in backendText) {
            "GO14 backend runtime allowlist is not Go-only."
        }
        check("const val ABI = \"arm64-v8a\"" in gatewayText) {
            "GO14 gateway is not sealed to arm64-v8a."
        }
        check("GOOS=android GOARCH=arm64" in scriptText && "GOARCH=amd64" !in scriptText) {
            "GO14 runtime builder must produce only the CGO-free Android ARM64 runtime."
        }
        check("ndkVersion = \"29.0.14206865\"" in appText) { "GO14 must retain NDK 29.0.14206865." }
        check("check(bkxNdkMajor >= 29)" in appText) { "GO14 must retain the NDK r29+ floor." }

        fun elfMachine(file: java.io.File): Int {
            check(file.isFile && file.canExecute()) { "Generated Go runtime missing or not executable: ${file.absolutePath}" }
            val bytes = file.readBytes()
            check(bytes.size >= 20 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
                "Generated Go runtime is not ELF: ${file.absolutePath}"
            }
            val littleEndian = bytes[5].toInt() == 1
            return if (littleEndian) {
                (bytes[18].toInt() and 0xff) or ((bytes[19].toInt() and 0xff) shl 8)
            } else {
                ((bytes[18].toInt() and 0xff) shl 8) or (bytes[19].toInt() and 0xff)
            }
        }

        check(elfMachine(arm64Runtime.get().asFile) == 183) { "GO14 arm64-v8a runtime is not AArch64 ELF." }
    }
}

tasks.register("verifyBkxFinalReleaseContract") {
    group = "verification"
    description = "Verify the canonical version source and current release documentation."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val appBuild = layout.projectDirectory.file("build.gradle.kts").asFile
    val pyproject = repositoryRootDir.resolve("pyproject.toml")
    val releaseContract = androidRootDir.resolve("docs/RELEASE_CONTRACT.md")
    val validation = androidRootDir.resolve("docs/RELEASE_VALIDATION.md")
    val architecture = androidRootDir.resolve("docs/ARCHITECTURE.md")
    inputs.files(appBuild, pyproject, releaseContract, validation, architecture)
    doLast {
        val buildText = appBuild.readText()
        val pyprojectText = pyproject.readText()
        check(Regex("""(?m)^version\s*=\s*"1\.0\.0"\s*$""").containsMatchIn(pyprojectText)) {
            "Repository-root pyproject.toml must remain the canonical BKX 1.0.0 version source for this release."
        }
        check("versionName = bkxVersionName" in buildText && "versionCode = bkxVersionCode" in buildText) {
            "Android must derive versionName/versionCode from the canonical repository version."
        }
        check(releaseContract.isFile && validation.isFile && architecture.isFile) {
            "Current architecture/release documentation is incomplete."
        }
        check("qualify-device.sh" in validation.readText() && "protocol-v1" in architecture.readText()) {
            "Current release docs must describe physical-device qualification and the protocol boundary."
        }
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
        "verifyBkxGoProductionCutoverContract",
        "verifyBkxGoOnlyAndroidRuntimeContract",
        "verifyBkxFinalReleaseContract",
    )
}
