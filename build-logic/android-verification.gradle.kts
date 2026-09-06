val bkxEmbeddedPythonVersion = extra["bkxEmbeddedPythonVersion"] as String
@Suppress("UNCHECKED_CAST")
val bkxSupportedEmbeddedPythonVersions = extra["bkxSupportedEmbeddedPythonVersions"] as Set<String>

tasks.register("verifyBkxEmbeddedEngineContract") {
    group = "verification"
    description = "Fail closed when the embedded BKX/Chaquopy contract drifts."

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
            "Embedded runtime requires Chaquopy 17.0.0."
        }
        check(catalogText.contains("agp = \"9.2.1\"")) {
            "Embedded runtime pins AGP 9.2.1, inside Chaquopy 17's tested 9.2.x lane."
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
            "Production graph must not instantiate the legacy Termux adapter."
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
    description = "Verify GO13 production Go cutover, privileged Go route, packaged ARM64 runtime, and device-test evidence sources."
    dependsOn("buildBkxGoAndroidRuntime")

    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val policy = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/GoRuntimeCutoverPolicy.kt")
    val graph = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/app/di/DefaultBackendGraph.kt")
    val application = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/app/BkxAndroidApplication.kt")
    val selector = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/NativePreferredGateway.kt")
    val rootGateway = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/RootAwareGateway.kt")
    val rootService = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/root/BkxRootEngineService.kt")
    val goGateway = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt")
    val localDeviceTest = androidRootDir.resolve("app/src/androidTest/java/com/mikeyphw/bkx/android/embedded/GoProductionCutoverInstrumentationTest.kt")
    val qualificationTest = androidRootDir.resolve("app/src/androidTest/java/com/mikeyphw/bkx/android/release/ReleaseQualificationInstrumentationTest.kt")
    val runtime = layout.buildDirectory.file("generated/bkx-go-runtime/jniLibs/arm64-v8a/libbkxgo.so")

    inputs.files(policy, graph, application, selector, rootGateway, rootService, goGateway, localDeviceTest, qualificationTest)
    inputs.file(runtime)

    doLast {
        val policyText = policy.readText()
        val graphText = graph.readText()
        val applicationText = application.readText()
        val selectorText = selector.readText()
        val rootText = rootService.readText()
        val gatewayText = goGateway.readText()
        val localTestText = localDeviceTest.readText()
        val qualificationText = qualificationTest.readText()

        check("ENABLED_BY_DEFAULT: Boolean = true" in policyText) { "GO13 production Go cutover is not enabled." }
        check("REQUIRE_GO_ON_ARM64: Boolean = true" in policyText && "TARGET_ABI: String = \"arm64-v8a\"" in policyText) { "GO13 ARM64 production cutover must fail closed when Go is unavailable." }
        check("RootAwareGateway" in graphText && "NativePreferredGateway" in graphText && "GoRuntimeCutoverPolicy.ENABLED_BY_DEFAULT" in graphText) {
            "GO13 production graph is not routed through the sealed Go cutover boundary."
        }
        check("ProductionGoRuntimeUnavailableException" in selectorText && "if (goRequiredProvider()) throw ProductionGoRuntimeUnavailableException()" in selectorText) {
            "GO13 ARM64 production selector must fail closed instead of silently falling back to Python."
        }
        check("Python.start(" !in applicationText) { "GO13 Python rollback runtime must remain lazy after Go cutover." }
        check("GoNativeGateway(baseContext, stateNamespace = ROOT_STATE_NAMESPACE)" in rootText) {
            "GO13 privileged RootService must launch the packaged Go runtime."
        }
        check("Os.getuid() == ROOT_UID" in rootText && "check(goGateway.isAvailable())" in rootText) {
            "GO13 privileged Go route must verify UID 0 and fail closed when the runtime is unavailable."
        }
        check("com.chaquo.python" !in rootText && "bkx.android_api" !in rootText && "ProcessBuilder(" !in rootText) {
            "GO13 privileged service must not retain Python or arbitrary process execution."
        }
        check("LOCAL_STATE_NAMESPACE = \"go-local\"" in gatewayText && "setOf(LOCAL_STATE_NAMESPACE, \"go-root\")" in gatewayText) {
            "GO13 local/root Go state ownership isolation is missing."
        }
        check("Python fallback must not be selected on ARM64 GO13" in localTestText && "go-native-android" in localTestText) {
            "GO13 local production device cutover instrumentation is missing."
        }
        check("Privileged production route must be Go-native" in qualificationText && "Local production route must be Go-native" in qualificationText) {
            "GO13 explicit physical-device qualification must prove both Go production routes."
        }

        val runtimeFile = runtime.get().asFile
        check(runtimeFile.isFile && runtimeFile.canExecute()) { "Generated GO13 ARM64 runtime is missing or not executable." }
        val bytes = runtimeFile.readBytes()
        check(bytes.size >= 20 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            "Generated GO13 runtime is not ELF."
        }
        val littleEndian = bytes[5].toInt() == 1
        val machine = if (littleEndian) {
            (bytes[18].toInt() and 0xff) or ((bytes[19].toInt() and 0xff) shl 8)
        } else {
            ((bytes[18].toInt() and 0xff) shl 8) or (bytes[19].toInt() and 0xff)
        }
        check(machine == 183) { "Generated GO13 runtime ELF e_machine=$machine, expected AArch64 (183)." }
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
        "verifyBkxFinalReleaseContract",
    )
}
