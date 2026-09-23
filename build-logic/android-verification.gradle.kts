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
            "android.permission.INTERNET",
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

tasks.register("verifyBkxPersonalSigningContract") {
    group = "verification"
    description = "Verify BKX permanent personal signing stays external and signs debug/release with one certificate when configured."

    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val appBuild = androidRootDir.resolve("app/build.gradle.kts")
    val setup = androidRootDir.resolve("tools/setup-personal-signing.sh")
    val fingerprint = androidRootDir.resolve("tools/print-personal-signing-fingerprint.sh")
    val example = androidRootDir.resolve("gradle/bkx-release-signing.properties.example")
    val repositoryRoot = androidRootDir.resolve("../..").canonicalFile
    val devtool = repositoryRoot.resolve(".devtool.toml")
    val gitignore = repositoryRoot.resolve(".gitignore")
    inputs.files(appBuild, setup, fingerprint, example, devtool, gitignore)

    doLast {
        check(setup.isFile) { "Missing personal signing setup helper." }
        check(fingerprint.isFile) { "Missing signing fingerprint helper." }
        val buildText = appBuild.readText()
        val setupText = setup.readText()
        val exampleText = example.readText()
        val devtoolText = devtool.readText()
        val ignoreText = gitignore.readText()
        listOf(
            "BKX_ANDROID_SIGNING_PROPERTIES",
            ".config/devtool/bkx/android-signing.properties",
            "create(\"bkxPersonal\")",
            "getByName(\"debug\")",
            "getByName(\"release\")",
            "abstract class BkxSigningStatusTask",
            "abstract class VerifyBkxPersonalSigningConfiguredTask",
            "tasks.register<BkxSigningStatusTask>(\"bkxSigningStatus\")",
            "tasks.register<VerifyBkxPersonalSigningConfiguredTask>(\"verifyBkxPersonalSigningConfigured\")",
            "verifyBkxPersonalSigningConfigured",
            "assemblePersonalDebug",
            "assemblePersonalRelease",
            "bkxPersonalReleaseSeal",
            "bkxPersonalPackageAndReleaseLintSeal",
        ).forEach { token -> check(token in buildText) { "Personal signing build contract missing: $token" } }
        check("tasks.register(\"bkxSigningStatus\")" !in buildText) { "bkxSigningStatus must use a typed configuration-cache-safe task class." }
        check("tasks.register(\"verifyBkxPersonalSigningConfigured\")" !in buildText) { "verifyBkxPersonalSigningConfigured must use a typed configuration-cache-safe task class." }
        check("-storepass:env BKX_SIGNING_SETUP_PASSWORD" in setupText) { "Setup helper must not expose the password in keytool argv." }
        check("chmod 600" in setupText && "SHA1:" in setupText) { "Setup helper must restrict secrets and print the OAuth fingerprint." }
        check("storeFile=" in exampleText && "storePassword=" in exampleText && "keyAlias=" in exampleText && "keyPassword=" in exampleText) {
            "Signing example schema is incomplete."
        }
        val devtoolTargetTasks = devtoolText.substringAfter("[targets.bkx_android.tasks]").substringBefore("[targets.bkx_android.artifacts]")
        check("build = [\":app:assemblePersonalDebug\"]" in devtoolTargetTasks) { "Devtool build must fail closed on personal signing." }
        check("release = [\":app:bkxPersonalReleaseSeal\"]" in devtoolTargetTasks) { "Devtool release must fail closed on personal signing." }
        check("package = [\":app:bkxPersonalPackageAndReleaseLintSeal\"]" in devtoolTargetTasks) { "Devtool package must fail closed on personal signing." }
        check("*.jks" in ignoreText && "*.keystore" in ignoreText && "*.p12" in ignoreText && "*.pfx" in ignoreText) { "Signing key material must remain ignored by Git." }
    }
}

tasks.register("verifyBkxAutomationProductContract") {
    group = "verification"
    description = "Seal Tasker/Locale actions, capability-protected explicit intents, background execution, idempotency, and shared BKX safety semantics."

    val repositoryRoot = rootProject.layout.projectDirectory.dir("../..").asFile.canonicalFile
    val sourceRoot = layout.projectDirectory.dir("src/main/java/com/mikeyphw/bkx/android").asFile
    val testRoot = layout.projectDirectory.dir("src/test/java/com/mikeyphw/bkx/android/automation").asFile
    val manifest = layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile
    val contract = sourceRoot.resolve("automation/AutomationContract.kt")
    val stores = sourceRoot.resolve("automation/AutomationStores.kt")
    val codec = sourceRoot.resolve("automation/AutomationIntentCodec.kt")
    val engine = sourceRoot.resolve("automation/BkxAutomationEngine.kt")
    val dispatch = sourceRoot.resolve("automation/AutomationDispatch.kt")
    val receiver = sourceRoot.resolve("automation/BkxAutomationReceiver.kt")
    val service = sourceRoot.resolve("automation/BkxAutomationService.kt")
    val editor = sourceRoot.resolve("automation/BkxTaskerEditActivity.kt")
    val conditionContract = sourceRoot.resolve("automation/TaskerConditionContract.kt")
    val conditionStore = sourceRoot.resolve("automation/TaskerConditionStore.kt")
    val conditionPublisher = sourceRoot.resolve("automation/TaskerConditionPublisher.kt")
    val conditionPlugin = sourceRoot.resolve("automation/TaskerConditionPlugin.kt")
    val conditionActivities = sourceRoot.resolve("automation/BkxTaskerConditionActivities.kt")
    val graph = sourceRoot.resolve("app/di/DefaultBackendGraph.kt")
    val drive = sourceRoot.resolve("app/drive/GoogleDriveAuthorizationManager.kt")
    val coordinator = sourceRoot.resolve("app/runtime/BkxOperationCoordinator.kt")
    val navigation = sourceRoot.resolve("app/navigation/BkxNavigation.kt")
    val more = sourceRoot.resolve("feature/more/MoreScreen.kt")
    val automationScreen = sourceRoot.resolve("feature/more/AutomationScreen.kt")
    val engineTest = testRoot.resolve("BkxAutomationEngineTest.kt")
    val productTest = testRoot.resolve("AutomationProductContractTest.kt")
    val conditionTest = testRoot.resolve("TaskerConditionContractTest.kt")
    val versions = rootProject.layout.projectDirectory.file("gradle/libs.versions.toml").asFile
    val appBuild = rootProject.layout.projectDirectory.file("app/build.gradle.kts").asFile
    val docs = rootProject.layout.projectDirectory.file("docs/AUTOMATION_TASKER.md").asFile
    val verifier = repositoryRoot.resolve("scripts/verify-android-automation.sh")

    inputs.files(manifest, contract, stores, codec, engine, dispatch, receiver, service, editor, conditionContract, conditionStore, conditionPublisher, conditionPlugin, conditionActivities, graph, drive, coordinator, navigation, more, automationScreen, engineTest, productTest, conditionTest, versions, appBuild, docs, verifier)

    doLast {
        val manifestText = manifest.readText()
        val contractText = contract.readText()
        val storeText = stores.readText()
        val codecText = codec.readText()
        val engineText = engine.readText()
        val receiverText = receiver.readText()
        val serviceText = service.readText()
        val editorText = editor.readText()
        val conditionContractText = conditionContract.readText()
        val conditionPublisherText = conditionPublisher.readText()
        val conditionPluginText = conditionPlugin.readText()
        val graphText = graph.readText()
        val driveText = drive.readText()
        val versionsText = versions.readText()
        val appBuildText = appBuild.readText()
        val coordinatorText = coordinator.readText()
        val navigationText = navigation.readText()
        val moreText = more.readText()
        val docsText = docs.readText()

        listOf(
            "com.twofortyfouram.locale.intent.action.EDIT_SETTING",
            "com.twofortyfouram.locale.intent.action.FIRE_SETTING",
            ".automation.BkxTaskerEditActivity",
            ".automation.BkxAutomationReceiver",
            ".automation.BkxAutomationService",
            ".automation.BkxAutomationJobService",
            "android.permission.BIND_JOB_SERVICE",
            "net.dinglisch.android.tasker.ACTION_EDIT_EVENT",
            "com.twofortyfouram.locale.intent.action.EDIT_CONDITION",
            ".automation.BkxTaskerEventEditActivity",
            ".automation.BkxTaskerStateEditActivity",
        ).forEach { token -> check(token in manifestText) { "Automation manifest contract missing: $token" } }
        listOf("BACKUP", "RESTORE", "VERIFY", "ARCHIVE_LIST", "ARCHIVE_INFO", "ARCHIVE_BROWSE", "TRANSFER", "CANCEL", "STATUS").forEach { action ->
            check("ACTION_$action" in contractText) { "Automation contract action missing: $action" }
        }
        check("com.mikeyphw.bkx.android.automation.BACKUP" !in manifestText) {
            "Direct automation actions must be explicit-component only and must not be advertised by manifest intent filters."
        }
        check("android:name=\".automation.BkxAutomationService\"\n            android:exported=\"false\"" in manifestText) {
            "Automation execution service must remain non-exported."
        }
        check("tasker_capability_token" in storeText && "capability_token" in storeText && "isTaskerAuthorized" in storeText) {
            "Tasker and direct Intent capabilities must remain separate and private."
        }
        check("callingPackage !in TRUSTED_TASKER_HOSTS" in editorText && "access.taskerToken" in editorText) {
            "Tasker edit Activity must not disclose a capability to arbitrary callers."
        }
        check("AutomationAccessStore(context).isTaskerAuthorized" in receiverText && "AutomationAccessStore(context).isAuthorized" in receiverText) {
            "Tasker and direct receiver authorization contracts drifted."
        }
        listOf("BackupRequestBuilder", "RestoreRequestBuilder", "ArchiveRequestBuilder", "TransferRequestBuilder", "RestoreExtractionConfirmation.requiredPhrase", "GoogleManagedDriveRefreshPort", "automation_previous_dispatch_incomplete").forEach { token ->
            check(token in engineText) { "Shared automation safety/runtime contract missing: $token" }
        }
        check("manageForeground = false" in engineText && "manageForeground: Boolean = true" in coordinatorText) {
            "External automation must own its foreground service without changing default UI coordinator behavior."
        }
        check("AutomationDispatchStore" in serviceText && "START_REDELIVER_INTENT" in serviceText && "startForeground" in serviceText) {
            "Automation background/restart contract is incomplete."
        }
        check("EXTRA_COMPLETION_INTENT" in contractText && "EXTRA_VARIABLE_REPLACE_KEYS" in codecText && "%bkx_request_id" in contractText) {
            "Tasker async completion/variables contract is incomplete."
        }
        check("TaskerPluginContract.EXTRA_REQUESTED_TIMEOUT" in editorText) {
            "Tasker synchronous timeout must be returned by the edit Activity."
        }
        check("TaskerPluginContract.EXTRA_REQUESTED_TIMEOUT" !in codecText) {
            "Tasker requested timeout must not be persisted inside the Locale plugin bundle."
        }
        check("Automation" in navigationText && "MoreRoute.Automation" in moreText && automationScreen.isFile) {
            "Automation product settings route is missing."
        }
        check("BkxAutomationEngine" in graphText && "SharedPreferencesAutomationStateStore" in graphText) {
            "Process-scoped automation engine/state graph is missing."
        }
        listOf("operation_started", "progress_milestone", "operation_completed", "google_drive_authorization_required", "remote_unavailable", "remote_recovered").forEach { token ->
            check(token in conditionContractText) { "Tasker Event contract missing: $token" }
        }
        listOf("any_operation_running", "google_drive_connected", "remote_available", "last_operation_failed").forEach { token ->
            check(token in conditionContractText) { "Tasker State contract missing: $token" }
        }
        check("TaskerPluginRunnerConditionEvent" in conditionPluginText && "TaskerPluginRunnerConditionState" in conditionPluginText) {
            "Tasker Event/State runners are missing."
        }
        check("TaskerPluginRunnerCondition.requestQuery" in conditionPublisherText && "canonicalProgressBucket" in conditionPublisherText) {
            "Tasker Event publication/throttling contract is incomplete."
        }
        check("taskerConditionPublisher?.operationStarted" in coordinatorText && "taskerConditionPublisher?.operationProgress" in coordinatorText && "taskerConditionPublisher?.operationTerminal" in coordinatorText) {
            "Tasker signals must originate from the authoritative operation coordinator."
        }
        check("taskerConditionPublisher?.driveNeedsAuthorization" in driveText && "taskerConditionPublisher?.driveConnected" in driveText) {
            "Google Drive authorization state must feed Tasker conditions."
        }
        check("taskerPlugin = \"0.4.10\"" in versionsText && "implementation(libs.tasker.plugin.library)" in appBuildText) {
            "Tasker condition library must remain pinned to 0.4.10."
        }
        check("com.joaomgcd.taskerpluginlibrary.action.BroadcastReceiverAction" in manifestText && "tools:node=\"remove\"" in manifestText) {
            "The library Action receiver must be removed so BKX remains the sole FIRE_SETTING owner."
        }
        listOf("RESTORE ROOT", "rclone://bkx-google-drive/BKX", "%bkx_request_id", "automation_previous_dispatch_incomplete").forEach { token ->
            check(token in docsText) { "Automation documentation missing release-critical token: $token" }
        }
        check(engineTest.isFile && productTest.isFile && conditionTest.isFile && verifier.isFile) { "Automation test/verifier evidence is incomplete." }
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
        "verifyBkxRcloneRemoteTransportContract",
        "verifyBkx16KbNativePageSizeContract",
        "verifyBkxGoogleDriveProductContract",
        "verifyBkxAutomationProductContract",
        "verifyBkxCloudProductReleaseSeal",
        "verifyBkxAndroidReleaseDocs",
        "verifyBkxAndroidSigningPlaceholders",
        "verifyBkxPersonalSigningContract",
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
    val transfer = sourceRoot.resolve("feature/transfer/TransferScreen.kt")
    val theme = sourceRoot.resolve("app/ui/theme/BkxTheme.kt")

    inputs.files(navigation, shell, home, backup, archives, activity, more, transfer, theme)

    doLast {
        val navigationText = navigation.readText()
        val shellText = shell.readText()
        val productSources = listOf(home, backup, archives, activity, more, transfer).associateWith { it.readText() }
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
        check("OpenMoreRoute(MoreRoute.Transfer)" in more.readText()) {
            "More must route to the dedicated transfer workflow instead of embedding the form."
        }
        check("OpenMoreRoute(MoreRoute.Remotes)" in more.readText()) {
            "More must route to the dedicated remote-storage workflow instead of embedding cloud configuration."
        }
        check("RequestTransferPreview" in transfer.readText() && "RunTransfer" in transfer.readText()) {
            "Dedicated transfer screen must expose real local/rclone transfer review/execution."
        }
        check("ArchiveDetailPane" in archives.readText() && "ArchiveDetailSection.Restore" in archives.readText()) {
            "Archives must use the adaptive list/detail restore workflow."
        }
    }
}

tasks.register("verifyBkxRcloneRemoteTransportContract") {
    group = "verification"
    description = "Verify the structured Android rclone runtime, app-private remote configuration, remote browsing, transfer safety, and OV3 lint-warning cleanup contracts."

    val repositoryRoot = rootProject.layout.projectDirectory.dir("../..").asFile.canonicalFile
    val sourceRoot = layout.projectDirectory.dir("src/main/java/com/mikeyphw/bkx/android").asFile
    val testRoot = layout.projectDirectory.dir("src/test/java/com/mikeyphw/bkx/android/contracts").asFile
    val appBuild = layout.projectDirectory.file("build.gradle.kts").asFile
    val installer = repositoryRoot.resolve("scripts/install-rclone-android-runtime.sh")
    val runtimeContract = repositoryRoot.resolve("scripts/rclone-runtime-contract.sh")
    val nativePageVerifier = repositoryRoot.resolve("scripts/verify-native-page-size.go")
    val verifier = repositoryRoot.resolve("scripts/verify-rclone-remote-transport.sh")
    val gateway = sourceRoot.resolve("embedded/GoNativeGateway.kt")
    val manager = sourceRoot.resolve("core/remotes/RcloneConfigManager.kt")
    val parser = sourceRoot.resolve("core/remotes/RcloneConfigParser.kt")
    val models = sourceRoot.resolve("core/remotes/RcloneModels.kt")
    val more = sourceRoot.resolve("feature/more/MoreScreen.kt")
    val remotes = sourceRoot.resolve("feature/more/RcloneRemotesScreen.kt")
    val transfer = sourceRoot.resolve("feature/transfer/TransferScreen.kt")
    val browser = sourceRoot.resolve("feature/home/FilesystemBrowserDialog.kt")
    val browserModels = sourceRoot.resolve("core/files/FilesystemBrowserModels.kt")
    val backup = sourceRoot.resolve("feature/backup/BackupScreen.kt")
    val contractTest = testRoot.resolve("RcloneRemoteTransportContractTest.kt")
    val parserTest = testRoot.resolve("RcloneConfigParserTest.kt")

    inputs.files(appBuild, installer, runtimeContract, nativePageVerifier, verifier, gateway, manager, parser, models, more, remotes, transfer, browser, browserModels, backup, contractTest, parserTest)

    doLast {
        val buildText = appBuild.readText()
        val installerText = installer.readText()
        val runtimeContractText = runtimeContract.readText()
        val nativePageVerifierText = nativePageVerifier.readText()
        val gatewayText = gateway.readText()
        val managerText = manager.readText()
        val parserText = parser.readText()
        val modelText = models.readText()
        val moreText = more.readText()
        val remotesText = remotes.readText()
        val transferText = transfer.readText()
        val browserText = browser.readText()
        val browserModelsText = browserModels.readText()
        val backupText = backup.readText()

        check(
            "installBkxRcloneAndroidRuntime" in buildText &&
                "generated/bkx-rclone-runtime/jniLibs" in buildText &&
                "generated/bkx-rclone-runtime/assets" in buildText &&
                "bclone/github-latest-stable/android-24-arm64/16k" in buildText &&
                "@UntrackedTask" in buildText
        ) {
            "BKX Android must re-resolve the latest bclone release and package runtime provenance alongside the generated native runtime."
        }
        listOf(
            "BCLONE_REPOSITORY=\"https://github.com/BenjiThatFoxGuy/bclone.git\"",
            "BCLONE_LATEST_RELEASE_URL=\"https://github.com/BenjiThatFoxGuy/bclone/releases/latest\"",
            "--write-out '%{url_effective}'",
            "git ls-remote --tags",
            "refs/tags/${'$'}BCLONE_TAG",
            "checkout --quiet --detach \"${'$'}BCLONE_COMMIT\"",
            "go mod download",
            "go mod verify",
            "go build -mod=readonly",
            "-buildvcs=true",
            "resolution=github-latest-stable-release",
            "bkx-rclone-runtime.properties",
            "CGO_ENABLED=1",
            "rejected by Go/cgo + 16 KiB probe; trying next candidate",
            "termux-native-clang-ndk-sysroot",
            "make_termux_ndk_sysroot_shim",
            "max-page-size=${'$'}{BKX_ANDROID_PAGE_SIZE}",
            "common-page-size=${'$'}{BKX_ANDROID_PAGE_SIZE}",
            "verify-native-page-size.go",
            "bkx_rclone_validate_build_info",
            "bkx_rclone_validate_version_output",
        ).forEach { token -> check(token in installerText) { "Latest-release bclone Android runtime installer is missing '$token'." } }
        listOf(
            "RCLONE_VERSION=\"1.75.1\"",
            "RCLONE_SOURCE_SHA256=",
            "RCLONE_VENDOR_SHA256=",
            "downloads.rclone.org/",
        ).forEach { token -> check(token !in installerText) { "Latest bclone runtime must not retain pinned upstream token '$token'." } }
        listOf(
            "GOOS=android",
            "GOARCH=arm64",
            "CGO_ENABLED=1",
            "vcs.revision=",
            "vcs.modified=false",
            "os/type: android",
            "os/arch: arm64",
        ).forEach { token -> check(token in runtimeContractText) { "Shared rclone runtime contract is missing '$token'." } }
        listOf(
            "archive-safe",
            "runtime",
            "APK native-page-size contract",
            "rclone runtime is missing embedded version marker",
            "PT_LOAD",
        ).forEach { token -> check(token in nativePageVerifierText) { "Go-native page-size verifier is missing '$token'." } }
        check("beta.rclone.org" !in installerText && "linux-arm64" !in installerText) {
            "Android rclone packaging must not use the upstream 4 KiB testbuild or a Linux binary."
        }
        check("verifyBkx16KbNativePageSizeContract" in buildText && "androidComponents.sdkComponents.ndkDirectory" in buildText) {
            "BKX Android must source-build rclone with the project NDK lane and verify final APK native ELF page-size compatibility."
        }
        check("BKX_RCLONE_PATH" in gatewayText && "BKX_RCLONE_CONFIG" in gatewayText && "libbkxrclone_exec.so" in gatewayText) {
            "GoNativeGateway must pass the packaged rclone runtime and app-private config path to BKX."
        }
        check("filesDir.resolve(\"bkx/rclone\")" in managerText && "MAX_RCLONE_CONFIG_BYTES" in managerText && "RCLONE_ENCRYPT_V0:" in managerText) {
            "rclone.conf ownership/import limits are missing from the app-private config manager."
        }
        check("RcloneConfigParser" in managerText && "SAFE_RCLONE_REMOTE_NAME" in parserText) {
            "rclone configuration parsing must remain isolated and unit-testable."
        }
        check("token" !in modelText.lowercase() && "password" !in modelText.lowercase()) {
            "Remote UI models must not expose credential-bearing config values."
        }
        check("MoreRoute.Remotes" in moreText && "Import rclone.conf" in remotesText && "remote.rclone.configured" in remotesText) {
            "More must route to a dedicated rclone-remotes screen with runtime/config state."
        }
        check("Browse remote" in transferText && "Manual path · Advanced" in transferText) {
            "Transfer must remain picker-first and expose remote browsing without reverting to raw-path-first UX."
        }
        check("allowRemote" in browserModelsText && "rclone://" in browserText) {
            "Filesystem browser must gate remote navigation by selection target."
        }
        check("Build.VERSION.SDK_INT >= Build.VERSION_CODES.O" in moreText && "ACTION_APPLICATION_DETAILS_SETTINGS" in moreText) {
            "Notification settings must retain the minSdk-24 fallback instead of unguarded API-26 fields."
        }
        check("getAllocatableBytes" in backupText && "getUuidForPath" in backupText && "StatFs" in backupText && ".usableSpace" !in backupText) {
            "Backup free-space estimation must use allocatable storage on API 26+ with a StatFs fallback."
        }
        check(contractTest.isFile && parserTest.isFile && verifier.isFile) {
            "Structured OV4 remote transport regression evidence is incomplete."
        }
    }
}

tasks.register("verifyBkxGoogleDriveProductContract") {
    group = "verification"
    description = "Verify first-class Google Drive authorization, private managed-rclone ownership, verified cloud backup/archive/restore, and credential-free Android UI boundaries."

    val repositoryRoot = rootProject.layout.projectDirectory.dir("../..").asFile.canonicalFile
    val androidRoot = rootProject.layout.projectDirectory.asFile
    val sourceRoot = layout.projectDirectory.dir("src/main/java/com/mikeyphw/bkx/android").asFile
    val testRoot = layout.projectDirectory.dir("src/test/java/com/mikeyphw/bkx/android/contracts").asFile
    val manifest = layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile
    val catalog = androidRoot.resolve("gradle/libs.versions.toml")
    val appBuild = layout.projectDirectory.file("build.gradle.kts").asFile
    val auth = sourceRoot.resolve("app/drive/GoogleDriveAuthorizationManager.kt")
    val driveModels = sourceRoot.resolve("core/drive/GoogleDriveModels.kt")
    val driveConfig = sourceRoot.resolve("core/drive/GoogleDriveRcloneConfig.kt")
    val configManager = sourceRoot.resolve("core/remotes/RcloneConfigManager.kt")
    val remoteModels = sourceRoot.resolve("core/remotes/RcloneModels.kt")
    val navigation = sourceRoot.resolve("app/navigation/BkxNavigation.kt")
    val productApp = sourceRoot.resolve("feature/home/BkxProductApp.kt")
    val viewModel = sourceRoot.resolve("app/BkxAppViewModel.kt")
    val screen = sourceRoot.resolve("feature/more/GoogleDriveScreen.kt")
    val more = sourceRoot.resolve("feature/more/MoreScreen.kt")
    val backupUi = sourceRoot.resolve("feature/backup/BackupScreen.kt")
    val contractTest = testRoot.resolve("GoogleDriveProductContractTest.kt")
    val configTest = testRoot.resolve("GoogleDriveRcloneConfigTest.kt")
    val roadmapTest = testRoot.resolve("Ov56RoadmapSealContractTest.kt")
    val backupGo = repositoryRoot.resolve("internal/backupops/backup.go")
    val remoteArchiveGo = repositoryRoot.resolve("internal/remotearchive/materialize.go")
    val dispatcherGo = repositoryRoot.resolve("internal/services/dispatcher.go")
    val bridgeGo = repositoryRoot.resolve("internal/bridge/bridge.go")
    val verifier = repositoryRoot.resolve("scripts/verify-google-drive-product.sh")
    val guide = repositoryRoot.resolve("docs/guide/06_GOOGLE_DRIVE_PRODUCT.md")
    val checklist = repositoryRoot.resolve("docs/checklists/CHECKLIST_ANDROID_GOOGLE_DRIVE.md")

    inputs.files(
        manifest, catalog, appBuild, auth, driveModels, driveConfig, configManager, remoteModels,
        navigation, productApp, viewModel, screen, more, backupUi, contractTest, configTest, roadmapTest,
        backupGo, remoteArchiveGo, dispatcherGo, bridgeGo, verifier, guide, checklist,
    )

    doLast {
        val manifestText = manifest.readText()
        val catalogText = catalog.readText()
        val appBuildText = appBuild.readText()
        val authText = auth.readText()
        val modelText = driveModels.readText()
        val configText = driveConfig.readText()
        val managerText = configManager.readText()
        val remoteModelText = remoteModels.readText()
        val navigationText = navigation.readText()
        val productAppText = productApp.readText()
        val viewModelText = viewModel.readText()
        val screenText = screen.readText()
        val moreText = more.readText()
        val backupUiText = backupUi.readText()
        val backupGoText = backupGo.readText()
        val remoteArchiveText = remoteArchiveGo.readText()
        val dispatcherText = dispatcherGo.readText()
        val bridgeText = bridgeGo.readText()

        check("android.permission.INTERNET" in manifestText) {
            "First-class Drive/rclone networking requires Android INTERNET permission."
        }
        check("playServicesAuth = \"21.6.0\"" in catalogText && "implementation(libs.google.play.services.auth)" in appBuildText) {
            "Google Drive authorization must use the pinned Play Services Auth 21.6.0 dependency lane."
        }
        listOf(
            "Identity.getAuthorizationClient",
            "AuthorizationRequest.builder()",
            "Scope(Scopes.DRIVE_FILE)",
            "client.authorize(request)",
            "getAuthorizationResultFromIntent",
            "RevokeAccessRequest.builder()",
        ).forEach { token -> check(token in authText) { "Google authorization boundary is missing '$token'." } }
        listOf("GoogleSignInClient", "GoogleSignInOptions").forEach { legacy ->
            check(legacy !in authText) { "Deprecated Google Sign-In authorization surface returned: $legacy" }
        }
        check("https://www.googleapis.com/auth/drive.file" in configText && "scope = drive.file" in configText) {
            "Managed Google Drive must remain limited to drive.file scope."
        }
        check("BKX_GOOGLE_DRIVE_REMOTE_NAME = \"bkx-google-drive\"" in remoteModelText && "rclone://\$BKX_GOOGLE_DRIVE_REMOTE_NAME/BKX" in configText) {
            "BKX managed Drive remote/root identity drifted."
        }
        check("preserveManagedSection" in managerText && "updateManagedGoogleDriveToken" in managerText && "removeManagedGoogleDrive" in managerText) {
            "App-private rclone configuration must preserve imported remotes around the BKX-managed Drive section."
        }
        check("accessToken" !in modelText && "refreshToken" !in modelText && "password" !in modelText.lowercase()) {
            "Drive UI state must not expose credentials."
        }
        val googleDriveRouteDeclared = Regex("""enum\s+class\s+MoreRoute\s*\{[^}]*\bGoogleDrive\b""")
            .containsMatchIn(navigationText)
        check(googleDriveRouteDeclared && "MoreRoute.GoogleDrive -> GoogleDriveScreen" in moreText) {
            "Google Drive must be declared in MoreRoute and dispatched to its dedicated product screen."
        }
        check("StartIntentSenderForResult" in productAppText && "CompleteGoogleDriveAuthorization" in productAppText) {
            "Google authorization consent resolution must be owned by the Compose product shell."
        }
        check("authorizeGoogleDriveForOperation" in viewModelText && "requestUsesManagedGoogleDrive" in viewModelText) {
            "Managed Drive operations must refresh authorization before execution."
        }
        check("Back up to Google Drive" in screenText && "Open Drive archives" in screenText) {
            "Google Drive screen must expose direct backup and archive/restore entry points."
        }
        check("GoogleDriveRcloneConfig.rootUri" in backupUiText && "backup.remote" in backupUiText) {
            "Backup must expose and capability-gate Google Drive as a real destination."
        }
        check("Remote archive verification failed after upload" in backupGoText && "remotearchive.Acquire" in backupGoText) {
            "Remote backup publication must download and verify the final logical archive before success."
        }
        check("type Materialized struct" in remoteArchiveText && ".part%03d" in remoteArchiveText) {
            "Remote archive materialization must support split archives through private staging."
        }
        check("materializeArchive" in dispatcherText && "ListRecursive" in dispatcherText) {
            "Archive browse/verify/restore must share the remote materialization/index boundary."
        }
        listOf("backup.remote", "archive.remote", "restore.remote", "cloud.google_drive").forEach { capability ->
            check("\"$capability\"" in bridgeText) { "Bridge readiness is missing capability $capability." }
        }
        check(contractTest.isFile && configTest.isFile && roadmapTest.isFile && verifier.isFile && guide.isFile && checklist.isFile) {
            "Google Drive regression/acceptance evidence is incomplete."
        }
    }
}

tasks.register("verifyBkxCloudProductReleaseSeal") {
    group = "verification"
    description = "Replay the cumulative OV1-OV6 Android product promise matrix and require live rclone/Google Drive release evidence."
    dependsOn(
        "verifyBkxProductUiContract",
        "verifyBkxRcloneRemoteTransportContract",
        "verifyBkx16KbNativePageSizeContract",
        "verifyBkxGoogleDriveProductContract",
        "verifyBkxRestoreSafetySeal",
        "verifyBkxPerformanceAndProgressContract",
    )

    val repositoryRoot = rootProject.layout.projectDirectory.dir("../..").asFile.canonicalFile
    val roadmap = repositoryRoot.resolve("docs/guide/07_ANDROID_PRODUCT_ROADMAP_SEAL.md")
    val driveGuide = repositoryRoot.resolve("docs/guide/06_GOOGLE_DRIVE_PRODUCT.md")
    val remoteGuide = repositoryRoot.resolve("docs/guide/05_RCLONE_REMOTE_TRANSPORT.md")
    val driveChecklist = repositoryRoot.resolve("docs/checklists/CHECKLIST_ANDROID_GOOGLE_DRIVE.md")
    val remoteChecklist = repositoryRoot.resolve("docs/checklists/CHECKLIST_ANDROID_REMOTE_TRANSPORT.md")
    val acceptance = rootProject.layout.projectDirectory.file("docs/OV56_GOOGLE_DRIVE_RELEASE_ACCEPTANCE.md").asFile
    val driveVerifier = repositoryRoot.resolve("scripts/verify-google-drive-product.sh")
    val roadmapVerifier = repositoryRoot.resolve("scripts/verify-ov56-roadmap-seal.sh")
    val canonicalValidator = repositoryRoot.resolve("scripts/validate.sh")

    inputs.files(roadmap, driveGuide, remoteGuide, driveChecklist, remoteChecklist, acceptance, driveVerifier, roadmapVerifier, canonicalValidator)

    doLast {
        listOf(roadmap, driveGuide, remoteGuide, driveChecklist, remoteChecklist, acceptance, driveVerifier, roadmapVerifier, canonicalValidator).forEach { file ->
            check(file.isFile) { "Missing OV5/OV6 release evidence: ${file.absolutePath}" }
        }
        val roadmapText = roadmap.readText()
        (1..6).forEach { phase -> check("OV$phase" in roadmapText) { "OV$phase promise is missing from final roadmap seal." } }
        listOf(
            ":app:installBkxRcloneAndroidRuntime",
            ":app:verifyBkxRcloneRemoteTransportContract",
            ":app:verifyBkxGoogleDriveProductContract",
            ":app:verifyBkxCloudProductReleaseSeal",
            ":app:testDebugUnitTest",
            ":app:lintDebug",
        ).forEach { marker -> check(marker in acceptance.readText()) { "OV5/OV6 acceptance guide is missing $marker." } }
    }
}

// Final hardening and release-seal gates.
tasks.register("verifyBkxRuntimeHardeningContract") {
    group = "verification"
    description = "Verify process-scoped execution, interrupt recovery behind the BR28 startup barrier, cooperative progress/cancellation, and dynamic root routing."
    val sourceRoot = layout.projectDirectory.dir("src/main/java/com/mikeyphw/bkx/android").asFile
    val coordinator = sourceRoot.resolve("app/runtime/BkxOperationCoordinator.kt")
    val runtime = sourceRoot.resolve("app/runtime/BkxApplicationRuntime.kt")
    val application = sourceRoot.resolve("app/BkxAndroidApplication.kt")
    val graph = sourceRoot.resolve("app/di/DefaultBackendGraph.kt")
    val gateway = sourceRoot.resolve("embedded/RootAwareGateway.kt")
    val operations = sourceRoot.resolve("core/operations/OperationModels.kt")
    inputs.files(coordinator, runtime, application, graph, gateway, operations)
    doLast {
        val coordinatorText = coordinator.readText()
        val runtimeText = runtime.readText()
        val applicationText = application.readText()
        val graphText = graph.readText()
        val gatewayText = gateway.readText()
        val operationsText = operations.readText()
        check("SupervisorJob() + Dispatchers.IO" in coordinatorText) { "Long BKX work must be application-scoped, not ViewModel-scoped." }
        check(
            "markApplicationCreated()" in applicationText &&
                "initializeAndRecoverInterruptedOperations" in runtimeText &&
                "OperationFailureMapper.interrupted" in coordinatorText,
        ) {
            "Persisted in-flight work must be classified as interrupted through the BR28 authorized startup barrier, not eager Application startup."
        }
        check("startProgressPoller" in coordinatorText && "backendPort.progress" in coordinatorText) {
            "Embedded progress must be consumed while an operation is active."
        }
        check("foregroundOperationMutex" !in coordinatorText && "executeOwnedConcurrent" in coordinatorText) {
            "Unrelated repositories must not be serialized behind a process-global foreground-operation mutex."
        }
        check("stopForegroundShellIfIdle" in coordinatorText && "anotherForegroundOperationIsActive" in coordinatorText) {
            "Concurrent foreground operations must keep the service alive until the last foreground operation finishes."
        }
        check("modeProvider = { settings.readNow().rootAccessMode }" in graphText) { "Root preference must resolve dynamically for the next operation." }
        check("requestRoutes" in gatewayText && "parseRequest" in gatewayText && "requestRoutes[parsed.requestId]" in gatewayText) { "Root/local execution route must stay pinned for progress and cancellation of an active request." }
        check("Interrupted" in operationsText && "bounded(" in operationsText) { "Interrupted state and bounded operation history must remain persisted contracts." }
    }
}

tasks.register("verifyBkxRestoreSafetySeal") {
    group = "verification"
    description = "Verify privileged restore confirmation, protected Android destinations, and Go restore publication semantics."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val restoreModels = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/restore/RestoreModels.kt").asFile
    val restoreRequest = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/restore/RestoreRequestBuilder.kt").asFile
    val restoreUi = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/feature/archives/ArchivesScreen.kt").asFile
    val goRestore = repositoryRootDir.resolve("internal/archiveio/restore.go")
    inputs.files(restoreModels, restoreRequest, restoreUi, goRestore)
    doLast {
        val models = restoreModels.readText()
        val request = restoreRequest.readText()
        val ui = restoreUi.readText()
        val go = goRestore.readText()
        listOf("/proc", "/sys", "/dev", "/system", "/metadata", "/data/system", "/data/misc").forEach { root ->
            check(root in models) { "Protected restore root missing from Kotlin policy: $root" }
        }
        check("ROOT_CONFIRMATION_PHRASE = \"RESTORE ROOT\"" in models && "root_confirmation" in request) {
            "Root-sensitive restore must require explicit RESTORE ROOT evidence."
        }
        check("if (destination == null)" in models) { "Every Android restore mode must require an explicit destination." }
        check("Restore destination already exists and requires explicit confirmation" in go && "Restore destination must be a real directory when it already exists" in go) {
            "Go restore destination semantics must remain fail-closed."
        }
        check("Restore destination" in ui && "Manual path · Advanced" in ui && "FilesystemSelectionTarget.RestoreDestination" in ui) {
            "Restore UI must collect an explicit destination through the picker or advanced exact path."
        }
    }
}

tasks.register("verifyBkxPerformanceAndProgressContract") {
    group = "verification"
    description = "Verify bounded Android state plus Go duration/progress/cancellation paths."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val operations = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/operations/OperationModels.kt").asFile
    val bridgeModels = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/bridge/BridgeModels.kt").asFile
    val goBridge = repositoryRootDir.resolve("internal/bridge/bridge.go")
    val goArchive = repositoryRootDir.resolve("internal/archiveio/create.go")
    val goRestore = repositoryRootDir.resolve("internal/archiveio/restore.go")
    val goTransfer = repositoryRootDir.resolve("internal/transferops/transfer.go")
    val coordinator = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/app/runtime/BkxOperationCoordinator.kt").asFile
    val foregroundService = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/app/service/BkxOperationForegroundService.kt").asFile
    inputs.files(operations, bridgeModels, goBridge, goArchive, goRestore, goTransfer, coordinator, foregroundService)
    doLast {
        val operationText = operations.readText()
        val bridgeText = bridgeModels.readText()
        val goBridgeText = goBridge.readText()
        val archiveText = goArchive.readText()
        val restoreText = goRestore.readText()
        val transferText = goTransfer.readText()
        val coordinatorText = coordinator.readText()
        val serviceText = foregroundService.readText()
        check("maxOperations: Int = 64" in operationText && "maxEventsPerRequest: Int = 200" in operationText) { "Operation persistence must remain bounded." }
        check("duration_ms" in bridgeText && "DurationMS" in goBridgeText) { "Go embedded results must retain measured duration metrics." }
        check("run.progress" in goBridgeText && "run.cancel.cooperative" in goBridgeText) { "Go runtime must advertise progress and cooperative cancellation." }
        check("progress != nil" in archiveText && "progress != nil" in restoreText && "progress != nil" in transferText) { "Archive/restore/transfer paths must keep incremental progress callbacks." }
        check("MutableStateFlow(OperationRuntimeState())" in coordinatorText && "progressPreferenceProvider" in coordinatorText) { "Live runtime progress and user-selected polling cadence must remain production-wired." }
        check("override fun onTimeout" in serviceText && "operationCoordinator(this@BkxOperationForegroundService).cancel" in serviceText) { "Foreground-service timeout must cooperatively cancel." }
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
        check("symbolic links are skipped safely" in backupText.lowercase() && "Follow symbolic links" !in backupText) {
            "Product UI must not claim unsupported link following."
        }
        check("RESTORE ROOT" in archivesText || "requiredPhrase" in archivesText) { "Privileged restore confirmation must be visible in the product flow." }
        check("active" in activityText.lowercase()) { "Activity must continue to expose active operation state." }
    }
}

val verifyBkxEmbeddedParitySeal = tasks.register<Exec>("verifyBkxEmbeddedParitySeal") {
    group = "verification"
    description = "Run the GO17 immutable compatibility oracle and Go bridge/service seals."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val oracle = repositoryRootDir.resolve("migration/fixtures/v1/reference_oracle.json")
    val goSources = repositoryRootDir.resolve("internal")
    inputs.files(oracle, goSources)
    workingDir(repositoryRootDir)
    commandLine("go", "test", "./internal/compatfixtures", "./internal/bridge", "./internal/services")
}

tasks.register("verifyBkxReleaseHygiene") {
    group = "verification"
    description = "Reject obsolete Termux execution, generic privileged command APIs, generated source junk, and committed signing material."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val productionRoots = listOf(
        androidRootDir.resolve("app/src/main"),
        repositoryRootDir.resolve("cmd"),
        repositoryRootDir.resolve("internal"),
    )
    inputs.files(productionRoots)
    doLast {
        val sourceFiles = productionRoots.flatMap { root ->
            if (!root.exists()) emptyList() else root.walkTopDown().filter { it.isFile }.toList()
        }
        val forbiddenText = listOf("com.termux.permission.RUN_COMMAND", "com.termux.RUN_COMMAND", "TermuxBridgeAdapter(", "termux_run_command", "TermuxRunCommand", "su -c", "eval(", "exec(")
        sourceFiles.filter { it.extension in setOf("kt", "kts", "java", "xml", "go", "aidl") }.forEach { file ->
            val text = file.readText()
            forbiddenText.forEach { token -> check(token !in text) { "Release hygiene rejected '$token' in ${file.relativeTo(repositoryRootDir)}" } }
            if ("ProcessBuilder(" in text) {
                check(file.name == "GoNativeGateway.kt") { "ProcessBuilder is allowed only in the app-owned Go runtime launcher." }
                check("applicationInfo.nativeLibraryDir" in text && "android-runtime" in text && "bridge-session" in text) {
                    "Go runtime ProcessBuilder must launch only the packaged protocol bridge executable."
                }
            }
        }
        // Validation itself can create ignored Python bytecode caches before this task runs,
        // especially when Gradle executes independent verification tasks in parallel. Release
        // hygiene must reject files that could enter the repository/release, not transient files
        // that Git explicitly ignores. `--cached` keeps already-tracked ignored files visible,
        // while `--others --exclude-standard` also catches new, non-ignored junk/secrets.
        val releaseCandidatePaths = if (repositoryRootDir.resolve(".git").exists()) {
            val process = ProcessBuilder(
                "git", "-C", repositoryRootDir.absolutePath,
                "ls-files", "--cached", "--others", "--exclude-standard", "-z",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            check(process.waitFor() == 0) { "Release hygiene could not enumerate Git release candidates: $output" }
            output.split('\u0000').filter { it.isNotBlank() }
        } else {
            repositoryRootDir.walkTopDown()
                .onEnter { dir -> dir.name !in setOf(".git", ".gradle", "build", "artifacts", ".devtool", "__pycache__") }
                .filter { it.isFile && it.extension != "pyc" }
                .map { it.relativeTo(repositoryRootDir).invariantSeparatorsPath }
                .toList()
        }
        val junk = releaseCandidatePaths
            .filter { path -> path.substringAfterLast('.', "").lowercase() in setOf("pyc", "apk", "jks", "keystore", "p12", "pfx") }
        check(junk.isEmpty()) { "Release hygiene rejected generated/secret files: ${junk.joinToString()}" }
        val cachePaths = releaseCandidatePaths.filter { path ->
            path.split('/').any { segment -> segment == "__pycache__" }
        }
        check(cachePaths.isEmpty()) { "Release hygiene rejected Python caches under source tree: ${cachePaths.joinToString()}" }
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
    description = "Run the Go large-tree archive/verify performance seal."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    inputs.files(repositoryRootDir.resolve("internal/compatfixtures/large_tree_test.go"), repositoryRootDir.resolve("internal/archiveio"))
    workingDir(repositoryRootDir)
    commandLine("go", "test", "./internal/compatfixtures", "-run", "^TestGoLargeTreeReleaseSeal$", "-count=1")
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
    val transfer = root.resolve("feature/transfer/TransferScreen.kt")
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val goDispatcher = repositoryRootDir.resolve("internal/services/dispatcher.go")
    val goArchive = repositoryRootDir.resolve("internal/archiveio/archiveio.go")
    val goRestore = repositoryRootDir.resolve("internal/archiveio/restore.go")
    inputs.files(viewModel, storage, provider, browser, backup, archives, more, transfer, goDispatcher, goArchive, goRestore)
    doLast {
        val vm = viewModel.readText()
        check("filesystem.list" in vm && "OpenFilesystemBrowser" in vm) { "Product filesystem browser must execute canonical filesystem.list." }
        check("archiveRootsProvider" in vm && "sizeBytes = artifact.sizeBytes" in vm) { "Archive index must consume discovered roots and real metadata." }
        check("storageVolumes" in storage.readText() && "archiveSearchRoots" in storage.readText()) { "Android removable-volume discovery must remain wired." }
        val providerText = provider.readText()
        check("copyDocument" in providerText && "copyTree" in providerText && "COPY_BUFFER_BYTES" in providerText) { "Provider-only SAF resources must stream into bounded staging." }
        check("totals.entries < MAX_TREE_ENTRIES" in providerText && "totals.entries += 1" in providerText) { "Provider staging must bound total tree entries, including directories." }
        check("ImportProviderBackupTree" in backup.readText() && "ImportProviderArchive" in archives.readText() && "ImportProviderTransferTree" in transfer.readText()) {
            "Provider fallback must be reachable from backup, archive, and dedicated transfer workflows."
        }
        check("FilesystemBrowserDialog" in browser.readText()) { "Reusable root-aware filesystem selector is missing." }
        check("EntryType string `json:\"entry_type\"`" in goArchive.readText() && "Archive link members are unsafe for restore" in goRestore.readText() && "archive_entry_symlink" in goDispatcher.readText() && "archive_entry_special" in goDispatcher.readText()) {
            "Archive Link/Special product filters must be backed by canonical Go archive-entry type metadata."
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
        check(
            "LOCAL_STATE_NAMESPACE = \"go-local\"" in gatewayText &&
                "ROOT_STATE_NAMESPACE = \"go-root\"" in gatewayText &&
                "setOf(LOCAL_STATE_NAMESPACE, ROOT_STATE_NAMESPACE)" in gatewayText
        ) {
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

        check(Regex("""MigrationPhase\s*=\s*"GO(1[4-9]|[2-9][0-9]+)"""").containsMatchIn(phaseText)) { "GO14+ phase identity is missing." }
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
    val versionFile = repositoryRootDir.resolve("internal/version/version.go")
    val releaseContract = androidRootDir.resolve("docs/RELEASE_CONTRACT.md")
    val validation = androidRootDir.resolve("docs/RELEASE_VALIDATION.md")
    val architecture = androidRootDir.resolve("docs/ARCHITECTURE.md")
    inputs.files(appBuild, versionFile, releaseContract, validation, architecture)
    doLast {
        val buildText = appBuild.readText()
        val versionText = versionFile.readText()
        check(Regex("""(?m)^\s*Version\s*=\s*"1\.0\.0"\s*$""").containsMatchIn(versionText)) {
            "internal/version/version.go must remain the canonical BKX 1.0.0 version source for this release."
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
        "verifyBkxRcloneRemoteTransportContract",
        "verifyBkx16KbNativePageSizeContract",
        "verifyBkxGoogleDriveProductContract",
        "verifyBkxAutomationProductContract",
        "verifyBkxCloudProductReleaseSeal",
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

tasks.register("verifyBkxBackupAutomationRoadmapFinalSeal") {
    group = "verification"
    description = "Verify the BR01-BR20 Saved Jobs, Restic, Android, Tasker, scheduling, migration, and cross-client parity seal."

    val repositoryRoot = rootProject.layout.projectDirectory.dir("../..").asFile.canonicalFile
    val finalDoc = repositoryRoot.resolve("docs/BR19_BR20_FINAL_BACKUP_AUTOMATION_SEAL.md")
    val savedJobsDoc = repositoryRoot.resolve("docs/guide/SAVED_JOBS_V2.md")
    val taskerDoc = rootProject.layout.projectDirectory.file("docs/AUTOMATION_TASKER.md").asFile
    val statePaths = repositoryRoot.resolve("internal/statepaths/statepaths.go")
    val runStore = repositoryRoot.resolve("internal/runstore/store.go")
    val publicCli = repositoryRoot.resolve("internal/publiccli/app.go")
    val resticMaintenance = repositoryRoot.resolve("internal/resticops/maintenance.go")
    val dispatcher = repositoryRoot.resolve("internal/services/dispatcher.go")
    val savedJobService = repositoryRoot.resolve("internal/services/saved_jobs.go")
    val snapshotCli = repositoryRoot.resolve("internal/publiccli/snapshot.go")
    val repositoryCli = repositoryRoot.resolve("internal/publiccli/repository.go")
    val jobsCli = repositoryRoot.resolve("internal/publiccli/jobs.go")
    val bridgeRuntime = repositoryRoot.resolve("internal/bridge/bridge.go")
    val gateway = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt").asFile
    val automation = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/automation/AutomationContract.kt").asFile
    val taskerEditor = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/automation/BkxTaskerEditActivity.kt").asFile
    val taskerEngine = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/automation/BkxAutomationEngine.kt").asFile
    val runHistoryBridge = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/runs/RunHistoryBridge.kt").asFile
    val coordinator = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/app/runtime/BkxOperationCoordinator.kt").asFile
    val manifest = layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile
    val jobsScreen = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/feature/backup/SavedJobsScreen.kt").asFile
    val repositoriesScreen = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/feature/backup/RepositoriesScreen.kt").asFile
    val snapshotsScreen = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/feature/backup/SnapshotsScreen.kt").asFile
    val unitTestRoot = layout.projectDirectory.dir("src/test").asFile
    val androidTestRoot = layout.projectDirectory.dir("src/androidTest").asFile

    inputs.files(
        finalDoc, savedJobsDoc, taskerDoc, statePaths, runStore, publicCli,
        resticMaintenance, dispatcher, savedJobService, snapshotCli, repositoryCli, jobsCli, bridgeRuntime,
        gateway, automation, taskerEditor, taskerEngine, runHistoryBridge, coordinator, manifest,
        jobsScreen, repositoriesScreen, snapshotsScreen,
    )
    inputs.dir(unitTestRoot)
    inputs.dir(androidTestRoot)

    doLast {
        check(finalDoc.isFile) { "BR19+BR20 final roadmap seal document is missing." }
        val finalText = finalDoc.readText()
        listOf("BR01+02", "BR19+20", "latest~N", "run_id", "overwrite=never", "full-validation overlay").forEach { token ->
            check(token in finalText) { "Final roadmap document missing token: $token" }
        }

        val stateText = statePaths.readText()
        listOf("JobStorePath", "RepositoryStorePath", "ScheduleStorePath", "ExecutionControlPath", "RunHistoryPath", "SharedAndroidRoot").forEach { token ->
            check(token in stateText) { "Shared control plane missing path contract: $token" }
        }
        check("MergeLegacyHistory" in runStore.readText()) { "Legacy run history union migration is missing." }

        val cliText = publicCli.readText()
        listOf("\"job run\"", "\"run replay\"", "\"snapshot browse\"", "\"schedule add\"", "\"state status\"").forEach { token ->
            check(token in cliText) { "Final CLI surface missing token: $token" }
        }
        check("strings.HasPrefix(value, \"latest~\")" in resticMaintenance.readText()) {
            "Restic latest~N snapshot selector support is missing."
        }

        val dispatcherText = dispatcher.readText()
        listOf("\"backup.snapshot\"", "\"repositories.add\"", "\"snapshots.restore\"", "\"repository.prune\"", "\"retention.apply\"", "\"jobs.run\"", "\"runs.replay\"", "\"schedules.put\"", "\"bytes_reused\"", "run_json").forEach { token ->
            check(token in dispatcherText) { "Shared service surface missing token: $token" }
        }
        val runStoreText = runStore.readText()
        listOf("RecordStartWithOrigin", "RecordPhase", "ReplayOperation", "cancelled", "interrupted").forEach { token ->
            check(token in runStoreText) { "Durable run/provenance contract missing token: $token" }
        }
        val savedJobServiceText = savedJobService.readText()
        listOf("applyOperationExecutionOverrides", "case \"source\"", "case \"repository\", \"repository_ref\"", "RunOrigin: origin", "data_added", "bytes_reused").forEach { token ->
            check(token in savedJobServiceText) { "Saved-job execution parity missing token: $token" }
        }
        check("snapshotContextAccess" in snapshotCli.readText() && "snapshotAccessAndPositionals" in snapshotCli.readText()) {
            "Snapshot CLI positional saved-job/repository context is missing."
        }
        check("func repositoryStatus" in repositoryCli.readText()) { "repository status CLI is missing." }
        check("operation = \"jobs.plan\"" in jobsCli.readText()) { "job run --dry-run must dispatch the saved-job planning path." }
        val bridgeRuntimeText = bridgeRuntime.readText()
        check("json:\"run_id,omitempty\"" in bridgeRuntimeText && "json:\"job_id,omitempty\"" in bridgeRuntimeText && "p.RunID" in bridgeRuntimeText && "p.JobID" in bridgeRuntimeText) {
            "Shared progress contract must carry run_id and job_id."
        }

        val gatewayText = gateway.readText()
        check("desiredStateRoot.resolve(\"runs\").resolve(\"runs.jsonl\")" in gatewayText && "BKX_LEGACY_RUN_HISTORY" in gatewayText) {
            "Android run history is not converged on the shared control plane."
        }
        val automationText = automation.readText()
        listOf("RunSavedJob", "CreateSnapshot", "RepositoryCheck", "RetentionApply", "RepositoryPrune", "SnapshotRestore", "%bkx_job_id", "%bkx_run_id", "%bkx_repository_id", "%bkx_snapshot_id", "%bkx_files_processed", "%bkx_bytes_processed", "%bkx_bytes_added", "%bkx_bytes_reused", "%bkx_duration_ms", "%bkx_success", "%bkx_message").forEach { token ->
            check(token in automationText) { "Tasker V2 final contract missing token: $token" }
        }
        listOf("Repository override", "source=/path", "repository=repo-id").forEach { token ->
            check(token in taskerEditor.readText()) { "Tasker Saved Job override UX missing token: $token" }
        }
        listOf("savedJobRepositoryOverride", "bytesAdded", "bytesReused", "durationMillis").forEach { token ->
            check(token in taskerEngine.readText()) { "Tasker effective repository/metrics producer missing token: $token" }
        }
        val runHistoryBridgeText = runHistoryBridge.readText()
        listOf("runs.list", "run_json", "Instant.parse", "LocalDateTime.parse", "ZoneId.systemDefault").forEach { token ->
            check(token in runHistoryBridgeText) { "Android canonical run-history bridge missing token: $token" }
        }
        val coordinatorText = coordinator.readText()
        check("foregroundOperationMutex" !in coordinatorText) { "Android still globally serializes unrelated repository operations." }
        check("stopForegroundShellIfIdle" in coordinatorText && "anotherForegroundOperationIsActive" in coordinatorText) {
            "Concurrent foreground operations do not preserve foreground-service lifetime."
        }

        check(jobsScreen.isFile && repositoriesScreen.isFile && snapshotsScreen.isFile) {
            "Android Jobs/Repositories/Snapshots product surfaces are incomplete."
        }
        val manifestText = manifest.readText()
        listOf("RECEIVE_BOOT_COMPLETED", "BkxSavedJobScheduleService", "BkxAutomationRecoveryReceiver", "MY_PACKAGE_REPLACED").forEach { token ->
            check(token in manifestText) { "Android scheduling/recovery manifest contract missing: $token" }
        }

        val savedJobsText = savedJobsDoc.readText()
        check("canonical Saved Job V2 store" in savedJobsText && "SharedPreferences job store remains only as a compatibility/migration source" in savedJobsText) {
            "Saved Jobs documentation still describes a split authoritative store."
        }
        val taskerText = taskerDoc.readText()
        listOf("Run saved job", "Create snapshot", "Prune repository", "%bkx_bytes_added", "%bkx_duration_ms").forEach { token ->
            check(token in taskerText) { "Tasker documentation missing V2 final contract token: $token" }
        }

        val unsupportedTestImports = listOf("import kotlin.test", "import org.junit.jupiter")
        listOf(unitTestRoot, androidTestRoot).forEach { root ->
            if (root.isDirectory) {
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { testFile ->
                        val testText = testFile.readText()
                        unsupportedTestImports.forEach { unsupportedImport ->
                            check(unsupportedImport !in testText) {
                                "Unsupported Android test framework import '$unsupportedImport' in ${testFile.relativeTo(layout.projectDirectory.asFile)}; use project-standard JUnit 4."
                            }
                        }
                    }
            }
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxBackupAutomationRoadmapFinalSeal")
}

tasks.register("verifyBkxRuntimeLifetimeForegroundCancellationSeal") {
    group = "verification"
    description = "Verify BR21 runtime lifetime, foreground ownership, request ID, and cancellation contracts."

    val repositoryRoot = rootProject.layout.projectDirectory.dir("../..").asFile.canonicalFile
    val coordinator = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/app/runtime/BkxOperationCoordinator.kt").asFile
    val tracker = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/operations/OperationProgressTracker.kt").asFile
    val models = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/operations/OperationModels.kt").asFile
    val classifier = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/operations/OperationClassifier.kt").asFile
    val service = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/app/service/BkxOperationForegroundService.kt").asFile
    val notificationModels = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/notifications/BkxNotificationModels.kt").asFile
    val bridgeLayout = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/bridge/BridgeFileLayout.kt").asFile
    val requestIdPolicy = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/bridge/BridgeRequestIdPolicy.kt").asFile
    val savedJobBuilder = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/jobs/SavedJobRequestBuilder.kt").asFile
    val viewModel = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/app/BkxAppViewModel.kt").asFile
    val goBridge = repositoryRoot.resolve("internal/bridge/bridge.go")
    val runtimeTest = layout.projectDirectory.file("src/test/java/com/mikeyphw/bkx/android/core/operations/OperationRuntimeContractTest.kt").asFile
    val classifierTest = layout.projectDirectory.file("src/test/java/com/mikeyphw/bkx/android/core/operations/OperationClassifierTest.kt").asFile
    val bridgeLayoutTest = layout.projectDirectory.file("src/test/java/com/mikeyphw/bkx/android/core/bridge/BridgeFileLayoutTest.kt").asFile
    val savedJobTest = layout.projectDirectory.file("src/test/java/com/mikeyphw/bkx/android/core/jobs/SavedJobModelsTest.kt").asFile

    inputs.files(
        coordinator, tracker, models, classifier, service, notificationModels, bridgeLayout,
        requestIdPolicy, savedJobBuilder, viewModel, goBridge, runtimeTest, classifierTest,
        bridgeLayoutTest, savedJobTest,
    )

    doLast {
        val coordinatorText = coordinator.readText()
        val trackerText = tracker.readText()
        val modelsText = models.readText()
        val classifierText = classifier.readText()
        val serviceText = service.readText()
        val notificationText = notificationModels.readText()
        val bridgeLayoutText = bridgeLayout.readText()
        val requestIdText = requestIdPolicy.readText()
        val savedJobText = savedJobBuilder.readText()
        val viewModelText = viewModel.readText()
        val goBridgeText = goBridge.readText()
        val runtimeTestText = runtimeTest.readText()
        val classifierTestText = classifierTest.readText()
        val bridgeLayoutTestText = bridgeLayoutTest.readText()
        val savedJobTestText = savedJobTest.readText()

        check("OperationClassifier.effectiveOperation(request.operation, request.payload)" in coordinatorText) {
            "Coordinator must classify wrapper operations by effective operation before foreground decisions."
        }
        check("startProgressPoller(request, manageForeground)" in coordinatorText && "if (manageForeground) updateForeground(operation)" in coordinatorText) {
            "Progress polling must not mutate foreground state when foreground management is disabled."
        }
        check("BKX_DUPLICATE_REQUEST_MISMATCH" in coordinatorText && "requestFingerprint(request)" in coordinatorText) {
            "Coordinator must reject duplicate request IDs with different payloads."
        }
        check("BKX_REQUEST_NOT_CANCELLABLE" in coordinatorText && "BridgeFileLayout.forRequest(requestBaseDirectoryPath, requestId)" in coordinatorText) {
            "Cancel paths must fail closed for invalid or inactive targets before backend cancellation."
        }
        check("requestFingerprint" in trackerText && "return state" in trackerText && "OperationClassifier.isTerminal(active.status)" in trackerText) {
            "Progress tracker must preserve existing request bindings and ignore late terminal progress."
        }
        check("ActiveOperationStatus.Cancelling) ActiveOperationStatus.Cancelling" in modelsText && "fun activeWork()" in modelsText) {
            "Operation model must keep cancelling sticky and expose a single active-work classifier."
        }
        check("wrapperOperations = setOf(\"jobs.run\", \"runs.replay\")" in classifierText && "foregroundOperations" in classifierText) {
            "Operation classifier must explicitly protect saved-job/replay wrappers."
        }
        check("EXTRA_EFFECTIVE_OPERATION" in serviceText && "operation.effectiveOperation" in serviceText) {
            "Foreground service and notification intents must preserve effective operations."
        }
        check("displayOperation(operation.effectiveOperation)" in notificationText) {
            "Notification display models must render the protected effective operation."
        }
        check("BridgeRequestIdPolicy.normalize(requestId)" in bridgeLayoutText && "request_id must not be a relative path segment" in requestIdText) {
            "Android bridge file layout must share one strict request-id policy."
        }
        check("maxRequestIDLength = 120" in goBridgeText && "request_id must not be a relative path segment" in goBridgeText) {
            "Go bridge must mirror Android request-id path-segment hardening."
        }
        check("effective_operation" in savedJobText && "savedJobRequestBuilder.run(nextRequestId(\"job-run\"), jobId, backendHint, effectiveOperation)" in viewModelText && "savedJobRequestBuilder.replay(nextRequestId(\"run-replay\"), runId, jobId, effectiveOperation)" in viewModelText) {
            "Saved Job Run and Run Again must carry effective-operation hints for foreground policy."
        }
        check("terminalOperationsAreNotResurrectedByLateProgress" in runtimeTestText && "cancellingOperationsStayCancellingWhenProgressArrives" in runtimeTestText && "duplicateRequestIdCannotBeReboundToDifferentPayload" in runtimeTestText) {
            "BR21 runtime regression tests are missing."
        }
        check("savedJobWrappersHonorEffectiveOperationHint" in classifierTestText && "nonExecutionWrappersIgnoreEffectiveOperationHint" in classifierTestText) {
            "BR21 classifier regression tests are missing."
        }
        check("rejectsParentDirectoryRequestIdEvenWithoutSlash" in bridgeLayoutTestText && "rejectsOverlongRequestId" in bridgeLayoutTestText) {
            "BR21 request-id regression tests are missing."
        }
        check("savedJobRunAndReplayCarryEffectiveOperationHintsForAndroidForegroundPolicy" in savedJobTestText) {
            "BR21 saved-job foreground regression test is missing."
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxRuntimeLifetimeForegroundCancellationSeal")
}


tasks.register("verifyBkxAndroidGoBridgeResultContractSeal") {
    group = "verification"
    description = "BR22 seal: Android-Go bridge session/result contract must remain status-aware and request-pinned."

    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val gateway = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt")
    val wire = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeSessionWire.kt")
    val backend = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/EmbeddedBkxBackend.kt")
    val bridgeJson = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/bridge/BridgeJson.kt")
    val bridgeModels = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/bridge/BridgeModels.kt")
    val viewModel = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/app/BkxAppViewModel.kt")
    val opModels = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/operations/OperationModels.kt")
    val mainGo = repositoryRootDir.resolve("cmd/bkx/main.go")
    inputs.files(gateway, wire, backend, bridgeJson, bridgeModels, viewModel, opModels, mainGo)

    doLast {
        val gatewayText = gateway.readText()
        val wireText = wire.readText()
        val backendText = backend.readText()
        val bridgeJsonText = bridgeJson.readText()
        val bridgeModelsText = bridgeModels.readText()
        val viewModelText = viewModel.readText()
        val opModelsText = opModels.readText()
        val mainText = mainGo.readText()

        check("PendingCommand" in gatewayText && "sessionId" in gatewayText && "completePendingForSession" in gatewayText) {
            "BR22 requires session-scoped pending replies so old readers cannot fail replacement-session commands."
        }
        check("invalidateOnFailure = false" in gatewayText && "progressJson" in gatewayText && "cancelJson" in gatewayText) {
            "BR22 progress/cancel probes must not invalidate the shared execute session."
        }
        check("MAX_COMMAND_BYTES" in wireText && "MAX_REPLY_BYTES" in wireText) {
            "BR22 requires explicit Android bridge command/reply size ceilings."
        }
        check("publish(files.requestFilePath, requestJson)" in backendText && backendText.indexOf("publish(files.requestFilePath, requestJson)") < backendText.indexOf("gateway.executeJson(requestJson)")) {
            "BR22 request descriptors must be durable before execution starts."
        }
        check("parseFinalResult(resultJson, expectedRequestId = request.requestId)" in backendText && "parseProgress(gateway.progressJson(requestId), expectedRequestId = requestId)" in backendText) {
            "BR22 embedded backend must pin result and progress routes to the requested request_id."
        }
        check("BridgeRequestIdPolicy.requireValid(requestId)" in bridgeModelsText && "failure result must include" in bridgeModelsText) {
            "BR22 bridge models must enforce request ids and failure evidence."
        }
        check("fun parseFinalResult(raw: String, expectedRequestId: String? = null)" in bridgeJsonText && "fun parseProgress(raw: String, expectedRequestId: String? = null)" in bridgeJsonText) {
            "BR22 BridgeJson must expose expected-request-id parsing."
        }
        check("deliverFailureResults" in viewModelText && "OperationFailureMapper.fromFinalResult(result)" in viewModelText) {
            "BR22 ViewModel must route terminal backend failures through failure handlers by default."
        }
        check("fromFinalResult(result: BridgeResultEnvelope)" in opModelsText) {
            "BR22 must expose a typed final-result-to-failure mapper."
        }
        check("maxBridgeSessionConcurrency" in mainText && "maxBridgeSessionReplyBytes" in mainText && "semaphore <- struct{}{}" in mainText) {
            "BR22 Go bridge session must bound goroutine fanout and outbound reply size."
        }
    }
}

tasks.register("verifyBkxStorageFilesystemRootSafetySeal") {
    group = "verification"
    description = "BR23 seal: storage, filesystem and root route policy must stay canonical, lease-aware, and capacity-aware."

    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val pathPolicy = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/files/AndroidPathPolicy.kt")
    val stagedPlanner = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/files/StagedFilePlanner.kt")
    val providerPort = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/files/ProviderStagingPort.kt")
    val providerManager = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/files/AndroidProviderStagingManager.kt")
    val rootPolicy = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/root/RootExecutionPolicy.kt")
    val rootGateway = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/embedded/RootAwareGateway.kt")
    val storageModels = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/storage/StorageAccessModels.kt")
    val storageManager = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/storage/AndroidStorageAccessManager.kt")
    val goPathPolicy = repositoryRootDir.resolve("internal/validation/path_policy.go")
    val goValidation = repositoryRootDir.resolve("internal/validation/validation.go")
    val goDispatcher = repositoryRootDir.resolve("internal/services/dispatcher.go")
    inputs.files(pathPolicy, stagedPlanner, providerPort, providerManager, rootPolicy, rootGateway, storageModels, storageManager, goPathPolicy, goValidation, goDispatcher)

    doLast {
        val pathPolicyText = pathPolicy.readText()
        val stagedPlannerText = stagedPlanner.readText()
        val providerPortText = providerPort.readText()
        val providerManagerText = providerManager.readText()
        val rootPolicyText = rootPolicy.readText()
        val rootGatewayText = rootGateway.readText()
        val storageModelsText = storageModels.readText()
        val storageManagerText = storageManager.readText()
        val goPathPolicyText = goPathPolicy.readText()
        val goValidationText = goValidation.readText()
        val goDispatcherText = goDispatcher.readText()

        check("restrictedWriteReason" in pathPolicyText && "/Android/data" in pathPolicyText && "/Android/obb" in pathPolicyText) {
            "BR23 requires canonical Android path policy for restricted shared-storage trees."
        }
        check("InsufficientStagingCapacity" in stagedPlannerText && "STAGING_HEADROOM_BYTES" in stagedPlannerText) {
            "BR23 staging planner must enforce provider staging capacity evidence."
        }
        check("leaseId" in providerPortText && "releaseStagingLease" in providerPortText && "isStagingLeaseActive" in providerPortText) {
            "BR23 provider staging contract must expose leases."
        }
        check("StatFs" in providerManagerText && "ACTIVE_LEASE_MARKER" in providerManagerText && "activeLeaseIds" in providerManagerText) {
            "BR23 provider staging manager must reserve capacity and protect active staging leases."
        }
        check("RootExecutionRequest" in rootPolicyText && "rootSensitivePaths" in rootPolicyText && "RootExecutionRoute.Blocked" in rootPolicyText) {
            "BR23 root policy must be request/path-aware and fail closed for root-sensitive local fallback."
        }
        check("candidatePathsFrom" in rootGatewayText && "route(parsed.request)" in rootGatewayText) {
            "BR23 root gateway must route based on effective request paths."
        }
        check("usableForDirectFileAccess" in storageModelsText && "restrictedWriteReason" in storageManagerText) {
            "BR23 storage discovery must report direct usability truth."
        }
        check("RequireSafeLocalWriteDestination" in goPathPolicyText && "RequireSafeLocalReadPath" in goPathPolicyText && "IsContentURI" in goPathPolicyText) {
            "BR23 Go validation must enforce local path policy and content URI staging."
        }
        check("RequireSafeLocalWriteDestination(destination)" in goValidationText && "Transfer destination must be a staged filesystem path" in goValidationText) {
            "BR23 transfer/restore validation must reject protected destinations and content URIs."
        }
        check("storageMetadata" in goDispatcherText && "validation.RequireSafeLocalReadPath" in goDispatcherText) {
            "BR23 dispatcher must expose storage usability metadata and protect filesystem operations."
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxStorageFilesystemRootSafetySeal")
}


tasks.register("verifyBkxBackupArchiveRestoreCorrectnessSeal") {
    group = "verification"
    description = "BR24 seal: backup plans, archive browsing/verification, and restore extraction must stay request-bound and confirmation-safe."

    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRootDir = androidRootDir.resolve("../..").canonicalFile
    val backupBuilder = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/backup/BackupRequestBuilder.kt")
    val backupModels = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/backup/BackupModels.kt")
    val archiveBuilder = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/archives/ArchiveRequestBuilder.kt")
    val archiveModels = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/archives/ArchiveModels.kt")
    val restoreBuilder = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/restore/RestoreRequestBuilder.kt")
    val restoreModels = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/restore/RestoreModels.kt")
    val backupTest = androidRootDir.resolve("app/src/test/java/com/mikeyphw/bkx/android/core/backup/BackupPlanningContractTest.kt")
    val archiveTest = androidRootDir.resolve("app/src/test/java/com/mikeyphw/bkx/android/core/archives/ArchiveReadOnlyContractTest.kt")
    val restoreTest = androidRootDir.resolve("app/src/test/java/com/mikeyphw/bkx/android/core/restore/RestoreSafetyContractTest.kt")
    val dispatcher = repositoryRootDir.resolve("internal/services/dispatcher.go")
    val dispatcherTest = repositoryRootDir.resolve("internal/services/dispatcher_br24_contract_test.go")
    val backupOps = repositoryRootDir.resolve("internal/backupops/backup.go")
    val archiveIo = repositoryRootDir.resolve("internal/archiveio/archiveio.go")
    val restoreIo = repositoryRootDir.resolve("internal/archiveio/restore.go")
    inputs.files(
        backupBuilder, backupModels, archiveBuilder, archiveModels, restoreBuilder, restoreModels,
        backupTest, archiveTest, restoreTest, dispatcher, dispatcherTest, backupOps, archiveIo, restoreIo,
    )

    doLast {
        val backupBuilderText = backupBuilder.readText()
        val archiveModelsText = archiveModels.readText()
        val restoreBuilderText = restoreBuilder.readText()
        val restoreTestText = restoreTest.readText()
        val archiveTestText = archiveTest.readText()
        val dispatcherText = dispatcher.readText()
        val dispatcherTestText = dispatcherTest.readText()

        check("plan_request_id" in backupBuilderText && "plan_token" in backupBuilderText && "backup.create preview does not match" in backupBuilderText) {
            "BR24 backup create must require request-bound plan evidence from the current draft."
        }
        check("Archive browse page token must be the numeric next-page offset" in archiveModelsText && "rclone://" in archiveModelsText) {
            "BR24 archive browsing must keep page tokens parseable and archive locations explicit."
        }
        check("confirmOverwrite = false" in restoreBuilderText && "destructive_confirmation_accepted" in restoreBuilderText && "confirmation_evidence" in restoreBuilderText) {
            "BR24 restore extract must send confirmation as accepted evidence, not as a pending confirm_overwrite flag."
        }
        check("assertEquals(false, request.payload[\"confirm_overwrite\"]" in restoreTestText && "destructive_confirmation_accepted" in restoreTestText) {
            "BR24 restore Android regression test must prove confirmed extracts clear confirm_overwrite."
        }
        check("nonNumericArchiveBrowsePageTokensAreRejected" in archiveTestText) {
            "BR24 archive regression test must reject opaque/non-numeric page tokens."
        }
        check("requirePlanEvidence" in dispatcherText && "restorePreviewTokenSpec" in dispatcherText && "archiveBoundArtifact" in dispatcherText) {
            "BR24 dispatcher must enforce plan tokens and bind archive read-only results to the requested archive identity."
        }
        check("TestBR24BackupCreateRequiresMatchingPlanEvidence" in dispatcherTestText && "TestBR24RestorePreviewTokenSurvivesConfirmedOverwriteExtraction" in dispatcherTestText) {
            "BR24 Go service regression tests are missing."
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxBackupArchiveRestoreCorrectnessSeal")
}


tasks.register("verifyBkxRepositoryResticSnapshotControlPlaneSeal") {
    group = "verification"
    description = "Verify BR25 repository, Restic credential, snapshot listing/diff, and repository-removal guard contracts."

    val repositoryRoot = rootProject.layout.projectDirectory.dir("../..").asFile.canonicalFile
    val models = repositoryRoot.resolve("internal/models/repositories.go")
    val backupModels = repositoryRoot.resolve("internal/models/backup_engines.go")
    val store = repositoryRoot.resolve("internal/repositorystore/store.go")
    val restic = repositoryRoot.resolve("internal/resticops/restic.go")
    val maintenance = repositoryRoot.resolve("internal/resticops/maintenance.go")
    val services = repositoryRoot.resolve("internal/services/dispatcher.go")
    val snapshotServices = repositoryRoot.resolve("internal/services/snapshot_maintenance.go")
    val androidRepositoryModels = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/repositories/RepositoryModels.kt").asFile
    val androidRepositoryBuilder = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/repositories/RepositoryRequestBuilder.kt").asFile
    val androidSnapshotModels = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/repositories/SnapshotModels.kt").asFile
    val androidSnapshotCodec = layout.projectDirectory.file("src/main/java/com/mikeyphw/bkx/android/core/repositories/SnapshotBridgeCodec.kt").asFile

    inputs.files(models, backupModels, store, restic, maintenance, services, snapshotServices, androidRepositoryModels, androidRepositoryBuilder, androidSnapshotModels, androidSnapshotCodec)

    doLast {
        check("func ValidateCredentialRef" in models.readText() && "CredentialEnvironmentKey" in models.readText()) {
            "BR25 credential references must have a collision-free environment-key policy."
        }
        check("duplicate repository physical location" in store.readText() && "rejectPhysicalCollision" in store.readText()) {
            "BR25 repository store must reject aliases for the same physical Restic repository."
        }
        check("removeEnvironmentKeys" in restic.readText() && "competing password channels" in restic.readText()) {
            "BR25 Restic credential resolution must scrub direct passwords and reject competing channels."
        }
        check("parseSnapshotTime" in maintenance.readText() && "DefaultSnapshotListLimit" in maintenance.readText() && "DiffSnapshotsWithOptions" in maintenance.readText()) {
            "BR25 snapshot maintenance must sort by parsed time and bound list/diff results."
        }
        check("repositoryRemovalBlockers" in services.readText() && "would leave saved jobs or schedules dangling" in services.readText()) {
            "BR25 repository removal must guard saved jobs and schedules."
        }
        check("limit: Int = 250" in androidRepositoryBuilder.readText() && "maxEntries: Int = 1000" in androidRepositoryBuilder.readText()) {
            "BR25 Android snapshot requests must carry explicit bounded result contracts."
        }
        check("truncated: Boolean" in androidSnapshotModels.readText() && "metadata?.get(\"truncated\")" in androidSnapshotCodec.readText()) {
            "BR25 Android snapshot diff codec must expose truncation metadata."
        }
    }
}

tasks.register("verifyBkxTransferRcloneGoogleDriveRemoteSeal") {
    group = "verification"
    description = "Verify BR26 transfer, rclone remote, and Google Drive readiness contracts."

    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRoot = androidRootDir.resolve("../..").canonicalFile
    val transferModels = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/transfer/TransferModels.kt")
    val transferBuilder = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/transfer/TransferRequestBuilder.kt")
    val transferTest = androidRootDir.resolve("app/src/test/java/com/mikeyphw/bkx/android/core/transfer/TransferSafetyContractTest.kt")
    val driveModels = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/drive/GoogleDriveModels.kt")
    val driveConfig = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/drive/GoogleDriveRcloneConfig.kt")
    val driveManager = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/app/drive/GoogleDriveAuthorizationManager.kt")
    val rcloneManager = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/remotes/RcloneConfigManager.kt")
    val viewModel = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/app/BkxAppViewModel.kt")
    val automationEngine = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/automation/BkxAutomationEngine.kt")
    val goTransfer = repositoryRoot.resolve("internal/transferops/transfer.go")
    val goTransferTest = repositoryRoot.resolve("internal/transferops/transfer_test.go")
    val goRcloneRuntime = repositoryRoot.resolve("internal/rcloneops/runtime.go")
    val goRcloneRuntimeTest = repositoryRoot.resolve("internal/rcloneops/runtime_test.go")
    val goRcloneTransfer = repositoryRoot.resolve("internal/rcloneops/transfer.go")
    val goValidation = repositoryRoot.resolve("internal/validation/validation.go")
    val goDispatcher = repositoryRoot.resolve("internal/services/dispatcher.go")
    val doc = repositoryRoot.resolve("BR26_TRANSFER_RCLONE_GOOGLE_DRIVE_REMOTE_SEAL.md")

    inputs.files(
        transferModels, transferBuilder, transferTest, driveModels, driveConfig, driveManager,
        rcloneManager, viewModel, automationEngine, goTransfer, goTransferTest, goRcloneRuntime,
        goRcloneRuntimeTest, goRcloneTransfer, goValidation, goDispatcher, doc,
    )

    doLast {
        check("planToken: String" in transferModels.readText() && "toTransferPreviewSummary" in transferModels.readText()) {
            "BR26 transfer UI model must decode authoritative plan_token evidence from transfer.plan artifacts."
        }
        check("plan_token" in transferBuilder.readText() && "sync_delete_limit" in transferBuilder.readText()) {
            "BR26 transfer request builder must carry transfer.run plan evidence and sync delete ceilings."
        }
        check("previewEnvelopeExtractsPlanTokenMappingsAndTruncationWarning" in transferTest.readText()) {
            "BR26 Android transfer regression must prove plan artifact decoding and truncation display."
        }
        check("GoogleDriveRemoteReadiness" in driveModels.readText() && "readyForRemoteOperations" in driveModels.readText()) {
            "BR26 Drive UI state must distinguish connection labels from operation-ready managed remote state."
        }
        check("managedSectionHasAccessToken" in driveConfig.readText() && "containsManagedRemoteReference" in driveConfig.readText()) {
            "BR26 Drive config must centralize managed remote reference and token-readiness checks."
        }
        check("managedRemoteReadiness" in driveManager.readText() && "hasManagedConfiguration" in driveManager.readText()) {
            "BR26 authorization manager must expose one managed Drive readiness authority."
        }
        check("managedGoogleDriveReadiness" in rcloneManager.readText() && "MissingAccessToken" in rcloneManager.readText()) {
            "BR26 rclone config manager must fail closed when managed Drive config lacks a token."
        }
        check("GoogleDriveRcloneConfig.containsManagedRemoteReference" in viewModel.readText()) {
            "BR26 ViewModel must use the shared managed-Drive detector before remote operations."
        }
        check("toTransferPreviewSummary(draft)" in automationEngine.readText() && "missing plan proof" in automationEngine.readText()) {
            "BR26 automation transfer must decode the backend preview instead of fabricating executable proof."
        }
        check("MappingsMore" in goTransfer.readText() && "SyncDeleteLimit" in goTransfer.readText() && "destinationChild" in goTransfer.readText()) {
            "BR26 Go transfer planning must expose bounded mappings and sync delete ceilings."
        }
        check("TestBR26TransferPlanCarriesBoundedExecutableMappings" in goTransferTest.readText() && "TestBR26SyncDeleteLimitIsFrozenIntoSpecSnapshot" in goTransferTest.readText()) {
            "BR26 Go transfer regression tests are missing."
        }
        check("ConfiguredRemoteNames" in goRcloneRuntime.readText() && "remoteNamesUsedBy" in goRcloneRuntime.readText()) {
            "BR26 rclone runtime must validate configured remote sections before executing remote operations."
        }
        check("TestConfiguredRemoteMustExistBeforeRemoteOperations" in goRcloneRuntimeTest.readText()) {
            "BR26 rclone configured-remote regression test is missing."
        }
        check("--max-delete" in goRcloneTransfer.readText() && "Warnings []string" in goRcloneTransfer.readText()) {
            "BR26 rclone transfer must carry sync delete ceilings and sanitized warning diagnostics."
        }
        check("IsTransferChecksumToken" in goValidation.readText() && "sync_delete_limit" in goValidation.readText()) {
            "BR26 validation must fail closed for unsupported checksums and invalid sync delete ceilings."
        }
        check("transferPlanArtifacts" in goDispatcher.readText() && "requirePlanEvidence(p, \"transfer.run\"" in goDispatcher.readText()) {
            "BR26 dispatcher must return executable transfer plan artifacts and require matching plan evidence on run."
        }
        check("Overlay 6 of 16" in doc.readText()) {
            "BR26 handoff doc must preserve overlay number and roadmap total."
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxTransferRcloneGoogleDriveRemoteSeal")
}

tasks.register("verifyBkxSavedJobsSchedulerTaskerRunHistorySeal") {
    group = "verification"
    description = "Verify BR27 saved-job, scheduler, Tasker automation, and run-history control-plane contracts."

    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRoot = androidRootDir.resolve("../..").canonicalFile
    val savedJobsGo = repositoryRoot.resolve("internal/services/saved_jobs.go")
    val schedulesGo = repositoryRoot.resolve("internal/services/schedules.go")
    val scheduleStoreGo = repositoryRoot.resolve("internal/schedulestore/store.go")
    val runStoreGo = repositoryRoot.resolve("internal/runstore/store.go")
    val jobStoreGo = repositoryRoot.resolve("internal/jobstore/store.go")
    val execControlGo = repositoryRoot.resolve("internal/executioncontrol/store.go")
    val dispatcherGo = repositoryRoot.resolve("internal/services/dispatcher.go")
    val automationContract = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/automation/AutomationContract.kt")
    val automationIntentCodec = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/automation/AutomationIntentCodec.kt")
    val automationDispatch = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/automation/AutomationDispatch.kt")
    val automationService = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/automation/BkxAutomationService.kt")
    val automationStores = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/automation/AutomationStores.kt")
    val scheduler = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/automation/BkxSavedJobScheduler.kt")
    val taskerBuilder = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/automation/TaskerV2RequestBuilder.kt")
    val runSummary = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/runs/RunSummary.kt")
    val runBridge = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/runs/RunHistoryBridge.kt")
    val scheduleModels = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android/core/jobs/SavedJobScheduleModels.kt")
    val manifest = androidRootDir.resolve("app/src/main/AndroidManifest.xml")
    val androidAutomationTest = androidRootDir.resolve("app/src/test/java/com/mikeyphw/bkx/android/automation/TaskerV2ActionContractTest.kt")
    val androidSchedulerTest = androidRootDir.resolve("app/src/test/java/com/mikeyphw/bkx/android/automation/BkxSavedJobSchedulerContractTest.kt")
    val goScheduleTest = repositoryRoot.resolve("internal/schedulestore/store_test.go")
    val goRunTest = repositoryRoot.resolve("internal/runstore/store_test.go")
    val doc = repositoryRoot.resolve("BR27_SAVED_JOBS_SCHEDULER_TASKER_RUN_HISTORY_SEAL.md")

    inputs.files(
        savedJobsGo, schedulesGo, scheduleStoreGo, runStoreGo, jobStoreGo, execControlGo, dispatcherGo,
        automationContract, automationIntentCodec, automationDispatch, automationService, automationStores,
        scheduler, taskerBuilder, runSummary, runBridge, scheduleModels, manifest,
        androidAutomationTest, androidSchedulerTest, goScheduleTest, goRunTest, doc,
    )

    doLast {
        check("savedJobFromPayloadWithDefault" in savedJobsGo.readText() && "current.Enabled" in savedJobsGo.readText()) {
            "BR27 jobs.update must preserve enabled when clients omit it."
        }
        check("SetEnabledForJob(updated.JobID, false)" in savedJobsGo.readText() && "DeleteForJob(jobID)" in savedJobsGo.readText()) {
            "BR27 saved-job disable/delete must clean dependent schedules."
        }
        check("expected_revision" in savedJobsGo.readText() && "saved job revision changed before scheduled execution" in savedJobsGo.readText()) {
            "BR27 jobs.run must support expected_revision revision fencing."
        }
        check("MaxIntervalMinutes" in scheduleStoreGo.readText() && "validateDocument(doc)" in scheduleStoreGo.readText() && "doc.Schedules = []Schedule{}" in scheduleStoreGo.readText()) {
            "BR27 schedule store must validate loaded documents, cap intervals, and preserve empty arrays."
        }
        check("current revision must be newer than history" in jobStoreGo.readText()) {
            "BR27 saved-job document validation must reject impossible current/history revision ordering."
        }
        check("ListPageResult" in runStoreGo.readText() && "parsePageOffset" in runStoreGo.readText() && "time.Now().UTC().Format(time.RFC3339Nano)" in runStoreGo.readText()) {
            "BR27 run history must be paginated with UTC RFC3339 timestamps."
        }
        check("IsReplayable" in runStoreGo.readText() && "runs.replay requires a terminal replayable run" in savedJobsGo.readText()) {
            "BR27 run replay must be terminal-only."
        }
        check("redactedRunRecord" in dispatcherGo.readText() && "privacy" in dispatcherGo.readText() && "redacted_paths" in dispatcherGo.readText()) {
            "BR27 run artifacts must redact private paths before Android consumes run_json."
        }
        check("run_history_page" in dispatcherGo.readText() && "next_page_token" in dispatcherGo.readText()) {
            "BR27 runs.list must expose page metadata."
        }
        check("ScheduleJobIdStore" in scheduler.readText() && "EXTRA_JOB_REVISION" in scheduler.readText() && "return true" in scheduler.readText()) {
            "BR27 Android scheduler must avoid ID collisions, bind job revisions, and hold JobService lifetime."
        }
        check("currentSchedule == null" in scheduler.readText() && "scheduleSignature(currentSchedule, currentJob.revision)" in scheduler.readText()) {
            "BR27 scheduled dispatch must reconcile stale/deleted/disabled schedules before execution."
        }
        check("expectedJobRevision" in automationContract.readText() && "EXTRA_EXPECTED_JOB_REVISION" in automationIntentCodec.readText() && "expectedRevision" in taskerBuilder.readText()) {
            "BR27 automation saved-job requests must carry expected_revision end-to-end."
        }
        val automationContractText = automationContract.readText()
        check("status == \"success\"" in automationContractText && !automationContractText.contains(".flatMap { it.split(',')")) {
            "BR27 Tasker variables must not mark queued/running as success and list parsing must preserve commas."
        }
        check("markDeliveryStarted" in automationDispatch.readText() && "cancelFallback" in automationService.readText()) {
            "BR27 automation dispatch must avoid duplicate callback delivery and cleanup fallback jobs."
        }
        check("clearActiveRequests" in automationStores.readText() && "AutomationDispatchStore(appContext).clear()" in automationStores.readText()) {
            "BR27 disabling automation must revoke queued and active accepted dispatches."
        }
        check("FALLBACK_JOB_ID_PREFIX = 0x40000000" in automationDispatch.readText()) {
            "BR27 automation fallback JobScheduler IDs must not collide with periodic saved-job IDs."
        }
        check("replayable: Boolean" in runSummary.readText() && "metadata[\"replayable\"]" in runBridge.readText()) {
            "BR27 Android activity/history must display replay only for backend-confirmed replayable runs."
        }
        check("525_600L" in scheduleModels.readText()) {
            "BR27 Android schedule model must cap intervals before bridge dispatch."
        }
        check("android:exported=\"false\"" in manifest.readText() && "BkxAutomationRecoveryReceiver" in manifest.readText()) {
            "BR27 recovery receiver must not expose custom reconciliation to external apps."
        }
        check("acceptedOrRunningTaskerResultsAreNotReportedAsSuccessfulTerminalWork" in androidAutomationTest.readText()) {
            "BR27 Android automation regression tests are missing."
        }
        check("TestBR27RunHistoryPageTokensUTCAndUnknownFields" in goRunTest.readText() && "TestBR27ScheduleStoreBulkDisableAndDeleteForJob" in goScheduleTest.readText()) {
            "BR27 Go store regression tests are missing."
        }
        check("Overlay 7 of 16" in doc.readText()) {
            "BR27 handoff doc must preserve overlay number and roadmap total."
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxSavedJobsSchedulerTaskerRunHistorySeal")
}

tasks.register("verifyBkxStartupAppGraphReadinessSeal") {
    group = "verification"
    description = "Verifies BR28 startup/app graph readiness barrier and lazy hydration contracts."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRoot = androidRootDir.resolve("../..").canonicalFile
    val appRoot = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android")
    val testRoot = androidRootDir.resolve("app/src/test/java/com/mikeyphw/bkx/android")
    dependsOn("testDebugUnitTest")
    doLast {
        val application = appRoot.resolve("app/BkxAndroidApplication.kt").readText()
        val runtime = appRoot.resolve("app/runtime/BkxApplicationRuntime.kt").readText()
        val graph = appRoot.resolve("app/di/DefaultBackendGraph.kt").readText()
        val viewModel = appRoot.resolve("app/BkxAppViewModel.kt").readText()
        val scheduler = appRoot.resolve("automation/BkxSavedJobScheduler.kt").readText()
        val receiver = appRoot.resolve("automation/BkxAutomationRecoveryReceiver.kt").readText()
        val resolver = appRoot.resolve("core/state/BkxControlPlaneRootResolver.kt").readText()
        check(application.contains("applicationRuntime(this).markApplicationCreated()")) {
            "Application startup must only install the runtime owner."
        }
        check(!application.contains("recoverInterruptedOperations()") && !application.contains("recoverPending(this)") && !application.contains("reconcile(this)")) {
            "Application.onCreate must not perform recovery, pending-dispatch scheduling or scheduler reconciliation."
        }
        check(runtime.contains("awaitReadyForExecution") && runtime.contains("Mutex()") && runtime.contains("BkxApplicationRuntimePhase.Ready")) {
            "BR28 requires a suspendable startup readiness barrier with explicit phases."
        }
        check(graph.contains("applicationRuntimeInstance") && graph.contains("resetForTests")) {
            "DefaultBackendGraph must expose a runtime owner and test reset seam."
        }
        check(receiver.contains("goAsync()") && scheduler.contains("awaitReadyForExecution(BkxStartupIngress.ScheduledJob)")) {
            "Background ingress must pass through the startup barrier off the main receiver/service thread."
        }
        check(resolver.contains("MAX_CONTROL_PLANE_DOCUMENT_BYTES") && resolver.contains("markerIsValid") && scheduler.contains("readBoundedDocument")) {
            "Control-plane consumers must share bounded marker-validated document reads."
        }
        val initSection = viewModel.substringAfter("init {").substringBefore("fun onEvent")
        check(!initSection.contains("refreshSavedJobs()") && !initSection.contains("refreshSavedJobSchedules()") && !initSection.contains("refreshRepositories()")) {
            "ViewModel startup must not eagerly hydrate feature catalogs."
        }
        check(viewModel.contains("readinessSequence") && viewModel.contains("hydrateDestination")) {
            "Readiness refresh must be generation-bound and feature catalogs hydrated lazily."
        }
        check(testRoot.resolve("contracts/StartupReadinessSealContractTest.kt").isFile) {
            "BR28 startup readiness contract test is missing."
        }
        val br28HandoffText = repositoryRoot.resolve("BR28_STARTUP_APP_GRAPH_READINESS_SEAL.md").readText().replace("**", "")
        check(br28HandoffText.contains("Overlay 8 of 16")) {
            "BR28 handoff doc must preserve overlay number and roadmap total."
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxStartupAppGraphReadinessSeal")
}


tasks.register("verifyBkxAndroidNavigationStaleStateBindingSeal") {
    group = "verification"
    description = "Verifies BR29 Android navigation, route-local state and stale response binding contracts."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRoot = androidRootDir.resolve("../..").canonicalFile
    val appRoot = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android")
    val testRoot = androidRootDir.resolve("app/src/test/java/com/mikeyphw/bkx/android")
    dependsOn("testDebugUnitTest")
    doLast {
        val navigation = appRoot.resolve("app/navigation/BkxNavigation.kt").readText()
        val viewModel = appRoot.resolve("app/BkxAppViewModel.kt").readText()
        val mainActivity = appRoot.resolve("app/MainActivity.kt").readText()
        val doc = repositoryRoot.resolve("BR29_ANDROID_NAVIGATION_STALE_STATE_BINDING_SEAL.md")
        check(navigation.contains("data class BkxRouteKey") && navigation.contains("data class BkxResponseBinding") && navigation.contains("object BkxDeepLinkCodec")) {
            "BR29 must expose structured route keys, response bindings and deep-link parsing."
        }
        check(viewModel.contains("routeGeneration") && viewModel.contains("activeResponseBindings") && viewModel.contains("acceptActiveBinding")) {
            "BR29 ViewModel must bind asynchronous results to route generations and active response bindings."
        }
        check(viewModel.contains("BackupDraft") && viewModel.contains("RestoreDraft") && viewModel.contains("TransferDraft") && viewModel.contains("SnapshotBrowsePath")) {
            "BR29 must cover backup, restore, transfer and snapshot stale-state bindings."
        }
        check(viewModel.contains("selectedStillPresent") && viewModel.contains("infoState = if (selectedStillPresent)")) {
            "BR29 archive refresh must reconcile stale archive selections."
        }
        check(mainActivity.contains("Intent.ACTION_VIEW") && mainActivity.contains("OpenDeepLink")) {
            "BR29 MainActivity must route VIEW intents through typed deep-link events."
        }
        check(testRoot.resolve("contracts/NavigationStaleStateBindingSealContractTest.kt").isFile) {
            "BR29 navigation/stale-state contract test is missing."
        }
        check(doc.isFile && doc.readText().contains("Overlay 9 of 16")) {
            "BR29 handoff doc must preserve overlay number and roadmap total."
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxAndroidNavigationStaleStateBindingSeal")
}


tasks.register("verifyBkxUiTruthfulnessAccessibilityProductCopySeal") {
    group = "verification"
    description = "Verifies BR30 UI truthfulness, busy gating, accessibility semantics and localized product copy contracts."
    val androidRootDir = rootProject.layout.projectDirectory.asFile
    val repositoryRoot = androidRootDir.resolve("../..").canonicalFile
    val appRoot = androidRootDir.resolve("app/src/main/java/com/mikeyphw/bkx/android")
    val testRoot = androidRootDir.resolve("app/src/test/java/com/mikeyphw/bkx/android")
    dependsOn("testDebugUnitTest")
    doLast {
        val truth = appRoot.resolve("app/BkxUiTruthPolicy.kt").readText()
        val components = appRoot.resolve("app/ui/components/BkxComponents.kt").readText()
        val home = appRoot.resolve("feature/home/HomeScreen.kt").readText()
        val backup = appRoot.resolve("feature/backup/BackupScreen.kt").readText()
        val transfer = appRoot.resolve("feature/transfer/TransferScreen.kt").readText()
        val drive = appRoot.resolve("feature/more/GoogleDriveScreen.kt").readText()
        val resources = androidRootDir.resolve("app/src/main/res/values/bkx_product_copy.xml").readText()
        val doc = repositoryRoot.resolve("BR30_UI_TRUTHFULNESS_ACCESSIBILITY_PRODUCT_COPY_SEAL.md")
        check(truth.contains("fun BkxHomeUiState.uiTruthSurface") && truth.contains("fun BkxHomeUiState.actionGate")) {
            "BR30 requires a shared UI truth surface and action gate policy."
        }
        check(truth.contains("googleDrive.connected") && truth.contains("remoteReadiness.userMessage()") && truth.contains("operationRuntime.hasActiveWork")) {
            "READY/CONNECTED/BUSY copy must derive from proven backend, managed Drive remote and active-operation truth."
        }
        check(home.contains("truth.engine.label") && home.contains("truth.engine.badge")) {
            "Home must not claim readiness from unverified setup copy."
        }
        check(backup.contains("BkxProductAction.ReviewBackup") && backup.contains("BkxProductAction.StartBackup") && backup.contains("startGate.allowed")) {
            "Backup review/start buttons must use the BR30 action gate."
        }
        check(transfer.contains("BkxProductAction.PreviewTransfer") && transfer.contains("BkxProductAction.RunTransfer") && transfer.contains("runGate.allowed")) {
            "Transfer preview/run buttons must use the BR30 action gate."
        }
        check(drive.contains("truth.badge") && drive.contains("connectGate.allowed")) {
            "Google Drive UI must distinguish authorized from verified managed-remote readiness."
        }
        listOf("heading()", "LiveRegionMode.Polite", "progressBarRangeInfo", "stateDescription", "contentDescription").forEach { token ->
            check(token in components) { "Shared component accessibility contract missing: $token" }
        }
        listOf("bkx_truth_engine_verified", "bkx_truth_drive_verified", "bkx_gate_backend_unverified", "bkx_gate_operation_busy", "bkx_a11y_progress_updated").forEach { token ->
            check(token in resources) { "Localized product copy resource missing: $token" }
        }
        check(testRoot.resolve("contracts/UiTruthfulnessAccessibilityContractTest.kt").isFile) {
            "BR30 UI truth/accessibility contract test is missing."
        }
        check(doc.isFile && doc.readText().contains("Overlay 10 of 16")) {
            "BR30 handoff doc must preserve overlay number and roadmap total."
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxUiTruthfulnessAccessibilityProductCopySeal")
}


tasks.register("verifyBkxCliParserConfigErrorContractSeal") {
    group = "verification"
    description = "Verifies BR31 CLI parser, config loading, JSON/error and exit-code contracts."
    doLast {
        val androidRootDir = rootProject.layout.projectDirectory.asFile
        val repositoryRoot = androidRootDir.resolve("../..").canonicalFile
        val publicCliRoot = repositoryRoot.resolve("internal/publiccli")
        val configRoot = repositoryRoot.resolve("internal/config")
        val berrorRoot = repositoryRoot.resolve("internal/berror")
        val app = publicCliRoot.resolve("app.go").readText()
        val parse = publicCliRoot.resolve("parse.go").readText()
        val render = publicCliRoot.resolve("render.go").readText()
        val cfg = configRoot.resolve("config.go").readText()
        val errors = berrorRoot.resolve("errors.go").readText()
        val doc = repositoryRoot.resolve("BR31_CLI_PARSER_CONFIG_ERROR_CONTRACT_SEAL.md")
        check(app.contains("renderHelpWithoutSideEffects") && app.contains("cliUsageError") && app.contains("wantsJSON")) {
            "BR31 requires one side-effect-free help and usage/error boundary."
        }
        check(parse.contains("strings.Cut(token, \"=\")") && app.contains("--progress=")) {
            "BR31 requires standard --option=value parsing including --progress=."
        }
        check(render.contains("dec.UseNumber()") && !render.contains("payload[\"timing\"] = []any{float64(0), duration}")) {
            "BR31 renderer must preserve 64-bit JSON numbers and avoid fabricated timing arrays."
        }
        check(render.contains("func (r renderer) domainError") && render.contains("tool_exit_code") && render.contains("error_type")) {
            "BR31 typed JSON error metadata contract is missing."
        }
        check(errors.contains("errors.As(err, &bkx)") && errors.contains("cloneValue")) {
            "BR31 wrapped-error classification and deep-detail copy are missing."
        }
        check(cfg.contains("parseYAMLScalar") && cfg.contains("stripYAMLComment") && cfg.contains("Permission denied reading BKX configuration")) {
            "BR31 YAML/config permission contracts are missing."
        }
        listOf(
            publicCliRoot.resolve("br31_contract_test.go"),
            configRoot.resolve("br31_yaml_config_test.go"),
            berrorRoot.resolve("br31_errors_test.go")
        ).forEach { file -> check(file.isFile) { "BR31 contract test missing: ${file.name}" } }
        check(doc.isFile && doc.readText().contains("Overlay 11 of 16")) {
            "BR31 handoff doc must preserve overlay number and roadmap total."
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxCliParserConfigErrorContractSeal")
}

tasks.register("verifyBkxCliBackupArchiveRestoreDomainSeal") {
    group = "verification"
    description = "Verifies BR32 CLI backup/archive/restore domain safety and parity contracts."
    doLast {
        val androidRootDir = rootProject.layout.projectDirectory.asFile
        val repositoryRoot = androidRootDir.resolve("../..").canonicalFile
        val validation = repositoryRoot.resolve("internal/validation/validation.go").readText()
        val backup = repositoryRoot.resolve("internal/backupops/backup.go").readText()
        val archive = repositoryRoot.resolve("internal/archiveio/archiveio.go").readText()
        val archiveLimits = repositoryRoot.resolve("internal/archiveio/br32_limits.go").readText()
        val create = repositoryRoot.resolve("internal/archiveio/create.go").readText()
        val multipart = repositoryRoot.resolve("internal/archiveio/multipart.go").readText()
        val restore = repositoryRoot.resolve("internal/archiveio/restore.go").readText()
        val commands = repositoryRoot.resolve("internal/publiccli/commands.go").readText()
        val plans = repositoryRoot.resolve("internal/publiccli/plans.go").readText()
        val parse = repositoryRoot.resolve("internal/publiccli/parse.go").readText()
        val doc = repositoryRoot.resolve("BR32_CLI_BACKUP_ARCHIVE_RESTORE_DOMAIN_SEAL.md")
        check(validation.contains("MinSplitSizeBytes") && validation.contains("split_size must be at least 1 MiB")) {
            "BR32 split-size safety minimum is missing."
        }
        check(backup.contains("BuildSourceManifest(valid.Sources, valid.Includes, valid.Excludes") && backup.contains("backup destination is required")) {
            "BR32 backup plan/create parity is missing manifest or destination enforcement."
        }
        check(parse.contains("func (a leafArgs) has") && commands.contains("rejectIncompatibleBackupOptions") && commands.contains("requireBackupDestination")) {
            "BR32 CLI backup option compatibility guard is missing."
        }
        check(commands.contains("archiveCreateSnapshot") && commands.contains("RecordStart(lease.requestID, \"archive\"") && commands.contains("RecordCompletion(lease.requestID, \"success\"")) {
            "BR32 archive-create run-history provenance is missing."
        }
        check(archiveLimits.contains("safePageWindow") && archiveLimits.contains("archiveBudget") && archiveLimits.contains("compressionExtensionWarnings")) {
            "BR32 archive resource/compression limits are missing."
        }
        check(archive.contains("safePageWindow(page, pageSize") && archive.contains("duplicate normalized member destination") && archive.contains("result.ChecksumsValid = false")) {
            "BR32 archive list/verify duplicate-member protections are missing."
        }
        check(create.contains("if validated.DryRun") && create.contains("compressionExtensionWarnings") && multipart.contains("publishArchivePartNoReplace")) {
            "BR32 archive dry-run compression and no-replace multipart publication are missing."
        }
        check(restore.contains("EnsureCapacityForWriteDestination") && restore.contains("PathsOverlap(destination, archive)") && restore.contains("createRestoreJournal")) {
            "BR32 restore capacity/source-overlap/journal contracts are missing."
        }
        check(restore.contains("RestoredPathsTruncated") && restore.contains("boundRestorePaths") && plans.contains("archiveio.PreviewRestore")) {
            "BR32 restore preview/result-bounding contracts are missing."
        }
        listOf(
            repositoryRoot.resolve("internal/validation/br32_split_test.go"),
            repositoryRoot.resolve("internal/archiveio/br32_limits_test.go"),
            repositoryRoot.resolve("internal/publiccli/br32_cli_domain_test.go")
        ).forEach { file -> check(file.isFile) { "BR32 contract test missing: ${file.name}" } }
        check(doc.isFile && doc.readText().contains("Overlay 12 of 16")) {
            "BR32 handoff doc must preserve overlay number and roadmap total."
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxCliBackupArchiveRestoreDomainSeal")
}

tasks.register("verifyBkxCliRepositorySnapshotTransferJobsDomainSeal") {
    group = "verification"
    description = "Verifies BR33 CLI repository, snapshot, transfer, saved-job, schedule, and run-history domain contracts."
    doLast {
        val androidRootDir = rootProject.layout.projectDirectory.asFile
        val repositoryRoot = androidRootDir.resolve("../..").canonicalFile
        val publicCliRoot = repositoryRoot.resolve("internal/publiccli")
        val resticOpsRoot = repositoryRoot.resolve("internal/resticops")
        val rcloneOpsRoot = repositoryRoot.resolve("internal/rcloneops")
        val scheduleStoreRoot = repositoryRoot.resolve("internal/schedulestore")
        val modelsRoot = repositoryRoot.resolve("internal/models")
        val commands = publicCliRoot.resolve("commands.go").readText()
        val repository = publicCliRoot.resolve("repository.go").readText()
        val jobs = publicCliRoot.resolve("jobs.go").readText()
        val schedule = publicCliRoot.resolve("schedule.go").readText()
        val snapshot = publicCliRoot.resolve("snapshot.go").readText()
        val retention = publicCliRoot.resolve("retention.go").readText()
        val maintenance = resticOpsRoot.resolve("maintenance.go").readText()
        val transfer = rcloneOpsRoot.resolve("transfer.go").readText()
        val store = scheduleStoreRoot.resolve("store.go").readText()
        val retentionModel = modelsRoot.resolve("snapshot_maintenance.go").readText()
        val doc = repositoryRoot.resolve("BR33_CLI_REPOSITORY_SNAPSHOT_TRANSFER_JOBS_DOMAIN_SEAL.md")
        check(commands.contains("\"transfer sync\":       transferSync") && commands.contains("--sync-delete-limit") && commands.contains("beginCLIExecution(c, \"transfer.run\"")) {
            "BR33 transfer sync/delete-ceiling/execution-lease CLI contract is missing."
        }
        check(transfer.contains("SyncDeleteLimit") && transfer.contains("--max-delete") && transfer.contains("transferTargetKey") && transfer.contains("multiple sources")) {
            "BR33 rclone sync delete ceiling or duplicate-target guard is missing."
        }
        check(commands.contains("ListPage(limit, operation, pageToken)") && commands.contains("NextPageToken")) {
            "BR33 run-history pagination contract is missing."
        }
        check(repository.contains("repositoryDependencies") && repository.contains("--force") && repository.contains("cleanupRepositoryInitParent") && repository.contains("validateResticPruneOption")) {
            "BR33 repository dependency/init/prune safety contract is missing."
        }
        check(snapshot.contains("BrowseSnapshotWithOptions") && snapshot.contains("DiffSnapshotsWithOptions") && snapshot.contains("snapshot_forget") && snapshot.contains("validation.NormalizePatterns")) {
            "BR33 snapshot CLI bounds/dry-run/pattern contract is missing."
        }
        check(maintenance.contains("DefaultSnapshotBrowseLimit") && maintenance.contains("Truncated") && maintenance.contains("validation.NormalizePatterns") && maintenance.contains("emitRetentionProgress")) {
            "BR33 Restic snapshot browse/restore/retention contract is missing."
        }
        check(retentionModel.contains("case \"\", \"none\", \"host\", \"tags\"") && retentionModel.contains("KeepTags") && retentionModel.contains("resticDurationPattern")) {
            "BR33 retention model compatibility contract is missing."
        }
        check(retention.contains("dry_run") && retention.contains("func retentionSet") && retention.contains("func retentionClear")) {
            "BR33 retention dry-run CLI contract is missing."
        }
        check(jobs.contains("validateExecutableOperation") && jobs.contains("safeWriteExport") && jobs.contains("validateJobOverrideKeys") && jobs.contains("jobRevisions")) {
            "BR33 saved-job executable/export/override/revision contract is missing."
        }
        check(schedule.contains("expected_revision") && schedule.contains("disabled saved job") && store.contains("PutExpected") && store.contains("SetEnabledExpected") && store.contains("readDocument")) {
            "BR33 schedule revision and side-effect-free read contract is missing."
        }
        listOf(
            modelsRoot.resolve("br33_retention_contract_test.go"),
            scheduleStoreRoot.resolve("br33_schedule_revision_test.go"),
            publicCliRoot.resolve("br33_cli_repository_snapshot_transfer_jobs_test.go"),
            rcloneOpsRoot.resolve("transfer_test.go")
        ).forEach { file -> check(file.isFile) { "BR33 contract test missing: ${file.name}" } }
        check(doc.isFile && doc.readText().contains("Overlay 13 of 16")) {
            "BR33 handoff doc must preserve overlay number and roadmap total."
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxCliRepositorySnapshotTransferJobsDomainSeal")
}

tasks.register("verifyBkxPersistenceSettingsMigrationSeal") {
    group = "verification"
    description = "Verifies BR34 persistence, settings, migration, shared-root, and atomic-publication contracts."
    doLast {
        val androidRootDir = rootProject.layout.projectDirectory.asFile
        val repositoryRoot = androidRootDir.resolve("../..").canonicalFile
        val statepaths = repositoryRoot.resolve("internal/statepaths/statepaths.go").readText()
        val config = repositoryRoot.resolve("internal/config/config.go").readText()
        val scheduleStore = repositoryRoot.resolve("internal/schedulestore/store.go").readText()
        val rcloneRuntime = repositoryRoot.resolve("internal/rcloneops/runtime.go").readText()
        val sharedContract = repositoryRoot.resolve("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/state/BkxSharedStateContract.kt").readText()
        val rootResolver = repositoryRoot.resolve("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/state/BkxControlPlaneRootResolver.kt").readText()
        val atomicPublisher = repositoryRoot.resolve("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/state/BkxAtomicFilePublisher.kt").readText()
        val embeddedBackend = repositoryRoot.resolve("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/EmbeddedBkxBackend.kt").readText()
        val scheduler = repositoryRoot.resolve("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/automation/BkxSavedJobScheduler.kt").readText()
        val dispatch = repositoryRoot.resolve("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/automation/AutomationDispatch.kt").readText()
        val settings = repositoryRoot.resolve("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/settings/BkxSettings.kt").readText()
        val viewModel = repositoryRoot.resolve("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/BkxAppViewModel.kt").readText()
        val doc = repositoryRoot.resolve("BR34_PERSISTENCE_SETTINGS_MIGRATION_SEAL.md")
        check(statepaths.contains("StateSchemaVersion       = 2") && statepaths.contains("ValidateMarker") && statepaths.contains("quarantineInvalidMarker") && statepaths.contains("AtomicWriteFile")) {
            "BR34 state marker compatibility/recovery/atomic write contract is missing."
        }
        check(statepaths.contains("LegacyScheduleStorePath") && scheduleStore.contains("LegacyPath") && scheduleStore.contains("SeedLegacyFile") && scheduleStore.contains("normalizeLegacyDocument")) {
            "BR34 schedule shared-state migration/legacy seed contract is missing."
        }
        check(config.contains("sort.Strings(keys)") && config.contains("sort.Strings(envNames)") && config.contains("configuration path is a directory") && config.contains("normalizePathWithHome")) {
            "BR34 deterministic config parsing/root-normalization contract is missing."
        }
        check(rcloneRuntime.contains("func (r Runtime) Configured() bool") && rcloneRuntime.contains("len(names) > 0") && rcloneRuntime.contains("BKX rclone config path is not configured")) {
            "BR34 rclone configured truth contract is missing."
        }
        check(sharedContract.contains("SCHEMA_VERSION = 2") && sharedContract.contains("MIN_SCHEMA_VERSION = 1") && rootResolver.contains("BkxAtomicFilePublisher.publishText") && rootResolver.contains("MIN_SCHEMA_VERSION..BkxSharedStateContract.SCHEMA_VERSION")) {
            "BR34 Android shared-state marker schema/atomic creation contract is missing."
        }
        check(atomicPublisher.contains("Direct overwrite") && atomicPublisher.contains("if (!temp.renameTo(target))") && !embeddedBackend.contains("target.writeText(content)")) {
            "BR34 embedded request/result atomic publication contract is missing."
        }
        check(scheduler.contains("SAVED_JOB_SCHEMA_VERSION") && dispatch.contains("synchronized(dispatchLock)") && settings.contains("BKX_SETTINGS_SCHEMA_VERSION") && viewModel.contains("operationFeedbackByRequest")) {
            "BR34 Android scheduler/dispatch/settings/request-feedback persistence contract is missing."
        }
        listOf(
            repositoryRoot.resolve("internal/statepaths/statepaths_test.go"),
            repositoryRoot.resolve("internal/config/config_test.go"),
            repositoryRoot.resolve("internal/schedulestore/store_test.go"),
            repositoryRoot.resolve("internal/rcloneops/runtime_test.go"),
            repositoryRoot.resolve("android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/core/state/BkxSharedStateContractTest.kt"),
            repositoryRoot.resolve("android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/automation/BkxSavedJobSchedulerContractTest.kt")
        ).forEach { file -> check(file.isFile) { "BR34 contract test missing: ${file.name}" } }
        check(doc.isFile && doc.readText().contains("Overlay 14 of 16")) {
            "BR34 handoff doc must preserve overlay number and roadmap total."
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxPersistenceSettingsMigrationSeal")
}

tasks.register("verifyBkxReleaseMergedManifestContract") {
    group = "verification"
    description = "Verify the final merged release manifest instead of only source AndroidManifest.xml."
    dependsOn("processReleaseMainManifest")
    val mergedManifest = layout.buildDirectory.file("intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml")
    inputs.file(mergedManifest)
    doLast {
        val manifestFile = mergedManifest.get().asFile
        check(manifestFile.isFile) { "Missing merged release manifest: ${manifestFile.absolutePath}" }
        val manifest = manifestFile.readText()
        val required = listOf(
            "android.permission.INTERNET",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "com.mikeyphw.bkx.android.app.service.BkxOperationForegroundService",
            "android:foregroundServiceType=\"dataSync\"",
            "android:allowBackup=\"false\"",
            "android:fullBackupContent=\"false\"",
        )
        val missing = required.filterNot { it in manifest }
        check(missing.isEmpty()) {
            "Merged release manifest is missing BKX release markers: ${missing.joinToString()}"
        }
    }
}

tasks.register("bkxAndroidReleasePreflightSeal") {
    group = "verification"
    description = "Stage 1 BKX release gate: optimized static source, dependency lane, docs, signing placeholders and contracts."
    dependsOn("bkxBr37StaticPreflightSeal")
}

tasks.register("bkxAndroidReleasePackageSeal") {
    group = "verification"
    description = "Stage 2 BKX release gate: package, shrink, release lint, merged manifest and native 16 KiB runtime checks."
    dependsOn(
        "assembleRelease",
        "lintRelease",
        "verifyBkxReleaseMergedManifestContract",
        "verifyBkx16KbNativePageSizeContract",
    )
}

tasks.register("bkxAndroidReleaseInstrumentationSeal") {
    group = "verification"
    description = "Stage 3 BKX release gate: instrumentation qualification when a device is available."
    dependsOn("connectedDebugAndroidTest")
}

tasks.register("bkxAndroidReleaseFinalSeal") {
    group = "verification"
    description = "Staged BKX Android final release seal."
    dependsOn(
        "bkxAndroidReleasePreflightSeal",
        "bkxAndroidReleasePackageSeal",
        "bkxAndroidReleaseInstrumentationSeal",
    )
}

tasks.register("verifyBkxReleasePackagingSigningNativeRuntimeSeal") {
    group = "verification"
    description = "Verifies BR35 release packaging, signing, native runtime, CI, and exact-artifact qualification contracts."
    doLast {
        val androidRootDir = rootProject.layout.projectDirectory.asFile
        val repositoryRoot = androidRootDir.resolve("../..").canonicalFile
        val appBuild = repositoryRoot.resolve("android/BKX.Android/app/build.gradle.kts").readText()
        val androidBuild = repositoryRoot.resolve("android/BKX.Android/build.gradle.kts").readText()
        val verification = repositoryRoot.resolve("build-logic/android-verification.gradle.kts").readText()
        val devtool = repositoryRoot.resolve(".devtool.toml").readText()
        val goRuntimeScript = repositoryRoot.resolve("scripts/build-go-android-runtime.sh").readText()
        val rcloneInstaller = repositoryRoot.resolve("scripts/install-rclone-android-runtime.sh").readText()
        val qualifyDevice = repositoryRoot.resolve("android/BKX.Android/scripts/qualify-device.sh").readText()
        val ci = repositoryRoot.resolve(".github/workflows/ci.yml").readText()
        val releaseContract = repositoryRoot.resolve("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/release/BkxReleaseArtifactPolicy.kt").readText()
        val releaseDocs = repositoryRoot.resolve("android/BKX.Android/docs/RELEASE_VALIDATION.md").readText()
        val multipart = repositoryRoot.resolve("internal/archiveio/multipart.go").readText()
        val create = repositoryRoot.resolve("internal/archiveio/create.go").readText()
        val render = repositoryRoot.resolve("internal/publiccli/render.go").readText()
        val doc = repositoryRoot.resolve("BR35_RELEASE_PACKAGING_SIGNING_NATIVE_RUNTIME_SEAL.md")

        check(appBuild.contains("bkxResolvedBuildCommit") && appBuild.contains("environment(\"BKX_BUILD_COMMIT\", buildCommit.get())") && goRuntimeScript.contains("BKX_BUILD_COMMIT must be a real Git revision")) {
            "BR35 Git commit stamping for the Android Go runtime is missing."
        }
        check(appBuild.contains("inputs.file(bkxRepositoryRoot.resolve(\"go.sum\"))") && appBuild.contains("inputs.file(bkxVersionFile)") && appBuild.contains("inputs.property(\"bkxBuildCommit\", bkxBuildCommit)")) {
            "BR35 generated Go runtime task does not model all release-critical inputs."
        }
        check(appBuild.contains("bkxAndroidVersionCode") && appBuild.contains("major * 1_000_000 + minor * 1_000 + patch") && releaseContract.contains("androidVersionCode")) {
            "BR35 non-colliding Android versionCode lane is missing."
        }
        check(appBuild.contains("isMinifyEnabled = true") && appBuild.contains("isShrinkResources = true") && repositoryRoot.resolve("android/BKX.Android/app/proguard-rules.pro").isFile) {
            "BR35 release shrink/minify contract is missing."
        }
        check(androidBuild.contains("dependencyLocking") && androidBuild.contains("lockAllConfigurations")) {
            "BR35 dependency locking is not enabled for Android builds."
        }
        check(verification.contains("verifyBkxReleaseMergedManifestContract") && verification.contains("processReleaseMainManifest")) {
            "BR35 must verify the merged release manifest, not only source text."
        }
        check(verification.contains("bkxAndroidReleasePreflightSeal") && verification.contains("bkxAndroidReleasePackageSeal") && verification.contains("bkxAndroidReleaseInstrumentationSeal") && verification.contains("bkxAndroidReleaseFinalSeal")) {
            "BR35 staged release seal tasks are missing."
        }
        val devtoolValidationPhases = devtool.substringAfter("[validation.phases]").substringBefore("[validation]")
        val devtoolPreflightPhase = devtoolValidationPhases.substringAfter("preflight = [").substringBefore("]")
        val devtoolPackagePhase = devtoolValidationPhases.substringAfter("package = [").substringBefore("]")
        check(
            "verifyBkx16KbNativePageSizeContract" !in devtoolPreflightPhase &&
                (
                    "verifyBkx16KbNativePageSizeContract" in devtoolPackagePhase ||
                        "bkxBr37PackageAndReleaseLintSeal" in devtoolPackagePhase
                )
        ) {
            "BR35 native page-size APK verification must run in the package phase, not cheap preflight."
        }
        check(
            "lintRelease" in verification &&
                ("lintRelease" in devtool || "bkxBr37PackageAndReleaseLintSeal" in devtool) &&
                ("lintRelease" in releaseDocs || "bkxBr37PackageAndReleaseLintSeal" in releaseDocs)
        ) {
            "BR35 release lint is missing from the validation lane."
        }
        check(
            rcloneInstaller.contains("BCLONE_LATEST_RELEASE_URL=\"https://github.com/BenjiThatFoxGuy/bclone/releases/latest\"") &&
                rcloneInstaller.contains("git ls-remote --tags") &&
                rcloneInstaller.contains("-buildvcs=true") &&
                rcloneInstaller.contains("printf 'commit=%s\\n'") &&
                rcloneInstaller.contains("resolution=github-latest-stable-release") &&
                rcloneInstaller.contains("BKX_REQUIRED_NDK_REVISION=\"29.0.14206865\"") &&
                rcloneInstaller.contains("BKX_ANDROID_PAGE_SIZE=\"16384\"") &&
                !rcloneInstaller.contains("RCLONE_SOURCE_SHA256") &&
                !rcloneInstaller.contains("RCLONE_VENDOR_SHA256")
        ) {
            "BR35 native-runtime policy must track latest stable bclone while pinning the Android toolchain and recording the resolved Git revision."
        }
        check(qualifyDevice.contains("--release-apk") && qualifyDevice.contains("--android-test-apk") && qualifyDevice.contains("bkxExactReleaseArtifact true") && qualifyDevice.contains("release-apk.sha256")) {
            "BR35 exact release APK physical qualification is missing."
        }
        check(ci.contains("android-release-packaging") && ci.contains("gradle-version: '9.7.1'") && ci.contains("verifyBkxReleasePackagingSigningNativeRuntimeSeal")) {
            "BR35 GitHub CI Android release packaging job is missing."
        }
        check(multipart.contains("MultipartManifest") && multipart.contains("PartCount") && multipart.contains("SHA256") && create.contains("MultipartManifestPath")) {
            "BR35 split archive manifest/part-count metadata contract is missing."
        }
        check(render.contains("formatCollectionTextValue") && render.contains("json.Marshal(value)")) {
            "BR35 human typed-error collection rendering must not use Go-native slice formatting."
        }
        listOf(
            repositoryRoot.resolve("android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/contracts/ReleasePackagingSigningNativeRuntimeContractTest.kt"),
            repositoryRoot.resolve("android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/core/release/BkxReleaseArtifactPolicyTest.kt"),
            repositoryRoot.resolve("internal/archiveio/multipart_manifest_test.go"),
            repositoryRoot.resolve("internal/publiccli/br35_render_text_test.go"),
        ).forEach { file -> check(file.isFile) { "BR35 contract test missing: ${file.name}" } }
        check(doc.isFile && doc.readText().contains("Overlay 15 of 16")) {
            "BR35 handoff doc must preserve overlay number and roadmap total."
        }
    }
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxReleasePackagingSigningNativeRuntimeSeal", "bkxAndroidReleaseFinalSeal")
}

tasks.register("verifyBkxRegressionHarnessFinalAuditSeal") {
    group = "verification"
    description = "Verifies BR36 behavioral regression harness, cross-target fixtures, residual defect ledger, and final-gate contracts."
    doLast {
        val androidRootDir = rootProject.layout.projectDirectory.asFile
        val repositoryRoot = androidRootDir.resolve("../..").canonicalFile
        val ledger = repositoryRoot.resolve("docs/BR36_DEFECT_LEDGER.md")
        check(ledger.isFile) { "BR36 defect ledger is missing." }
        val ledgerText = ledger.readText()
        listOf("Overlay 16 of 16", "A01-24", "A02-17", "A12-21", "C06-02").forEach { marker ->
            check(ledgerText.contains(marker)) { "BR36 defect ledger is missing $marker." }
        }

        val runtime = repositoryRoot.resolve("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/runtime/BkxApplicationRuntime.kt").readText()
        check(runtime.contains("internal interface BkxStartupSteps") && runtime.contains("startupSteps.recoverOperations()") && runtime.contains("startupSteps.recoverAutomation()") && runtime.contains("startupSteps.reconcileSchedules()")) {
            "BR36 injectable behavioral startup barrier is missing."
        }
        val runtimeBehaviorTest = repositoryRoot.resolve("android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/app/runtime/BkxApplicationRuntimeBehaviorTest.kt").readText()
        check(runtimeBehaviorTest.contains("concurrentAuthorizedIngressRunsColdStartBarrierExactlyOnceInOrder") && runtimeBehaviorTest.contains("startupFailureIsIsolatedAndLaterStepsStillRun")) {
            "BR36 startup behavior tests are missing."
        }
        val delayedBackendTest = repositoryRoot.resolve("android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/app/BkxBackendOutOfOrderBehaviorTest.kt").readText()
        check(delayedBackendTest.contains("newerReadinessRefreshWinsWhenOlderBackendProbeCompletesLast") && delayedBackendTest.contains("CompletableDeferred")) {
            "BR36 delayed/out-of-order backend readiness behavior test is missing."
        }

        val viewModel = repositoryRoot.resolve("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/BkxAppViewModel.kt").readText()
        check(!viewModel.contains("val screenStatuses:") && !viewModel.contains("screenStatuses =") && viewModel.contains("fun screenStatus(destination: BkxDestination): ScreenStatus")) {
            "BR36 must derive destination status from canonical state instead of independently mutating screenStatuses."
        }
        val statusTest = repositoryRoot.resolve("android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/app/BkxHomeUiStateStatusTest.kt").readText()
        check(statusTest.contains("aggregateStatusMapCannotDivergeBecauseStateHasNoStoredScreenStatusesProperty")) {
            "BR36 derived UI status regression test is missing."
        }

        val driveCoordinator = repositoryRoot.resolve("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/drive/GoogleDriveAuthorizationCoordinator.kt").readText()
        check(driveCoordinator.contains("interface GoogleDriveAuthorizationClient") && driveCoordinator.contains("class GoogleDriveAuthorizationCoordinator") && driveCoordinator.contains("generation") && driveCoordinator.contains("activeAuthorizationGeneration") && driveCoordinator.contains("credentialStore.disconnectLocal()")) {
            "BR36 generation-serialized Google Drive authorization coordinator is missing."
        }
        val driveTest = repositoryRoot.resolve("android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/app/drive/GoogleDriveAuthorizationCoordinatorTest.kt").readText()
        listOf(
            "concurrentUiAndAutomationRefreshCoalesceOntoOneAuthorizationRequest",
            "consentCompletionSupersedesOlderAuthorizeGenerationAndRejectsItsLateCallback",
            "disconnectClearsLocalCredentialBeforeRemoteRevokeCompletesAndRejectsLateAuthorizeCallback",
            "failedAuthorizationDoesNotMutateExistingCredential",
        ).forEach { marker -> check(driveTest.contains(marker)) { "BR36 Drive lifecycle test missing $marker." } }

        listOf(
            "android/BKX.Android/app/src/test/resources/bridge/br36_request_backup_plan.json",
            "android/BKX.Android/app/src/test/resources/bridge/br36_result_failure.json",
            "android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/core/bridge/Br36GoldenBridgeFixtureTest.kt",
            "internal/bridge/testdata/br36_request_backup_plan.json",
            "internal/bridge/testdata/br36_result_failure.json",
            "internal/bridge/br36_golden_test.go",
            "internal/audit/br36_final_gate_test.go",
        ).forEach { relative ->
            check(repositoryRoot.resolve(relative).isFile) { "BR36 cross-target regression artifact missing: $relative" }
        }

        val snapshots = repositoryRoot.resolve("internal/resticops/maintenance.go").readText()
        val snapshotTest = repositoryRoot.resolve("internal/resticops/br36_snapshot_ordering_test.go").readText()
        check(snapshots.contains("parseSnapshotTime(item.Time)") && snapshots.contains("ti.After(tj)") && snapshots.contains("snapshots[i].ID > snapshots[j].ID")) {
            "BR36 absolute-time snapshot ordering source contract is missing."
        }
        check(snapshotTest.contains("+02:00") && snapshotTest.contains("+01:00") && snapshotTest.contains("malformed timestamp")) {
            "BR36 snapshot ordering regression cases are missing."
        }

        val devtool = repositoryRoot.resolve(".devtool.toml").readText()
        check(
            devtool.contains(":app:verifyBkxRegressionHarnessFinalAuditSeal") ||
                devtool.contains(":app:bkxBr37StaticPreflightSeal")
        ) {
            "BR36 verification must remain wired into Devtool preflight directly or through the BR37 optimized aggregate."
        }
        val goDoc = repositoryRoot.resolve("BR36_GO_REGRESSION_HARNESS_FINAL_AUDIT_SEAL.md")
        val androidDoc = repositoryRoot.resolve("BR36_ANDROID_REGRESSION_HARNESS_FINAL_AUDIT_SEAL.md")
        check(goDoc.isFile && goDoc.readText().contains("Overlay 16 of 16")) { "BR36 Go target seal doc is missing." }
        check(androidDoc.isFile && androidDoc.readText().contains("Overlay 16 of 16")) { "BR36 Android target seal doc is missing." }
    }
}

val bkxBr37StaticPreflightTasks = listOf(
    "verifyBkxGradleTaskGraphOptimizationSeal",
    "verifyBkxAndroidSdk36DependencyLane",
    "verifyBkxEmbeddedEngineContract",
    "verifyBkxRootStorageContract",
    "verifyBkxProductUiContract",
    "verifyBkxRcloneRemoteTransportContract",
    "verifyBkxGoogleDriveProductContract",
    "verifyBkxAutomationProductContract",
    "verifyBkxRuntimeHardeningContract",
    "verifyBkxRestoreSafetySeal",
    "verifyBkxPerformanceAndProgressContract",
    "verifyBkxStorageCompletenessContract",
    "verifyBkxAccessibilityVisualContract",
    "verifyBkxUxHardeningContract",
    "verifyBkxReleaseHygiene",
    "verifyBkxDeviceQualificationContract",
    "verifyBkxFinalReleaseContract",
    "verifyBkxAndroidManifestReleaseContract",
    "verifyBkxAndroidSigningPlaceholders",
    "verifyBkxPersonalSigningContract",
    "verifyBkxAndroidReleaseDocs",
    "verifyBkxBackupAutomationRoadmapFinalSeal",
    "verifyBkxRuntimeLifetimeForegroundCancellationSeal",
    "verifyBkxAndroidGoBridgeResultContractSeal",
    "verifyBkxStorageFilesystemRootSafetySeal",
    "verifyBkxBackupArchiveRestoreCorrectnessSeal",
    "verifyBkxRepositoryResticSnapshotControlPlaneSeal",
    "verifyBkxTransferRcloneGoogleDriveRemoteSeal",
    "verifyBkxSavedJobsSchedulerTaskerRunHistorySeal",
    "verifyBkxCliParserConfigErrorContractSeal",
    "verifyBkxCliBackupArchiveRestoreDomainSeal",
    "verifyBkxCliRepositorySnapshotTransferJobsDomainSeal",
    "verifyBkxPersistenceSettingsMigrationSeal",
    "verifyBkxReleasePackagingSigningNativeRuntimeSeal",
    "verifyBkxRegressionHarnessFinalAuditSeal",
)

val bkxBr37BehavioralUnitTasks = listOf(
    "compileDebugUnitTestKotlin",
    "testDebugUnitTest",
    "verifyBkxStartupAppGraphReadinessSeal",
    "verifyBkxAndroidNavigationStaleStateBindingSeal",
    "verifyBkxUiTruthfulnessAccessibilityProductCopySeal",
)

val bkxBr37PackageAndReleaseLintTasks = listOf(
    "bkxAndroidReleasePackageSeal",
    "verifyBkxCloudProductReleaseSeal",
    "verifyBkxGoProductionCutoverContract",
    "verifyBkxGoOnlyAndroidRuntimeContract",
)

tasks.register("bkxBr37StaticPreflightSeal") {
    group = "verification"
    description = "BR37 optimized cheap static preflight; excludes unit-test, package/lint, instrumentation, and Go Exec lanes."
    dependsOn(bkxBr37StaticPreflightTasks)
}

tasks.register("bkxBr37BehavioralUnitSeal") {
    group = "verification"
    description = "BR37 optimized behavioral/unit gate; runs the full unit-test suite once, then BR28-BR30 behavioral seals, so overlay validation fixes real regressions instead of routing around them."
    dependsOn(bkxBr37BehavioralUnitTasks)
}

tasks.register("bkxBr37PackageAndReleaseLintSeal") {
    group = "verification"
    description = "BR37 optimized package gate; runs release packaging, release lint, merged-manifest, native-page-size, cloud, and Go-runtime cutover evidence once."
    dependsOn(bkxBr37PackageAndReleaseLintTasks)
}

tasks.register("bkxBr36FinalAuditSeal") {
    group = "verification"
    description = "Optimized BR21-BR36 final gate grouped into static, unit, debug-lint, and package/release-lint phases; instrumentation remains device-conditional in Devtool."
    dependsOn(
        "bkxBr37StaticPreflightSeal",
        "bkxBr37BehavioralUnitSeal",
        "lintDebug",
        "bkxBr37PackageAndReleaseLintSeal",
    )
}

tasks.register("verifyBkxGradleTaskGraphOptimizationSeal") {
    group = "verification"
    description = "Verifies the BR37 Gradle task graph optimization and Devtool split-phase wiring."
    doLast {
        val androidRootDir = rootProject.layout.projectDirectory.asFile
        val repositoryRoot = androidRootDir.resolve("../..").canonicalFile
        val devtool = repositoryRoot.resolve(".devtool.toml").readText()
        val verification = repositoryRoot.resolve("build-logic/android-verification.gradle.kts").readText()
        val validationGuide = androidRootDir.resolve("docs/RELEASE_VALIDATION.md").readText()
        val doc = repositoryRoot.resolve("BR37_GRADLE_TASK_GRAPH_OPTIMIZATION_SEAL.md")

        check(":app:bkxBr37StaticPreflightSeal" in devtool) {
            "BR37 Devtool preflight must use the optimized static aggregate."
        }
        check(":app:bkxBr37BehavioralUnitSeal" in devtool) {
            "BR37 Devtool unit-test phase must use the optimized behavioral/unit aggregate."
        }
        check(":app:bkxBr37PackageAndReleaseLintSeal" in devtool) {
            "BR37 Devtool package phase must use the optimized package/release-lint aggregate."
        }
        val validationPhases = devtool.substringAfter("[validation.phases]").substringBefore("[validation]")
        val lintPhase = Regex("""(?s)lint = \[.*?\]""").find(validationPhases)?.value
            ?: error("BR37 Devtool validation lint phase is missing.")
        check(":app:lintRelease" !in lintPhase) {
            "BR37 Devtool validation lint phase must not rerun release lint outside the package aggregate."
        }
        check("val bkxBr37StaticPreflightTasks = listOf" in verification) {
            "BR37 static preflight task list is missing."
        }
        val optimizedGateMarker = """tasks.register("bkxBr37OptimizedGradleTaskSeal")"""
        val optimizedGateStart = verification.lastIndexOf(optimizedGateMarker)
        check(optimizedGateStart >= 0) { "BR37 optimized apply gate task registration is missing." }
        val optimizedGateBody = verification.substring(optimizedGateStart)
            .substringBefore("tasks.named(\"bkxAndroidReleasePreflightSeal\")", "")
        check("dependsOn(\"bkxBr37StaticPreflightSeal\")" in optimizedGateBody && "bkxBr36FinalAuditSeal" !in optimizedGateBody && "testDebugUnitTest" !in optimizedGateBody) {
            "BR37 optimized apply gate must stop at static preflight and must not rerun unit tests or the BR36 full final gate."
        }
        check("verifyBkxEmbeddedParitySeal" !in bkxBr37StaticPreflightTasks && "verifyBkxLargeTreePerformanceSeal" !in bkxBr37StaticPreflightTasks) {
            "BR37 static preflight must not rerun Go compatibility/performance Exec tests that belong to the bkx target lane."
        }
        check("testDebugUnitTest" !in bkxBr37StaticPreflightTasks && "lintRelease" !in bkxBr37StaticPreflightTasks && "connectedDebugAndroidTest" !in bkxBr37StaticPreflightTasks) {
            "BR37 static preflight must not invoke unit tests, release lint or instrumentation."
        }
        val behavioralStart = verification.indexOf("val bkxBr37BehavioralUnitTasks = listOf")
        check(behavioralStart >= 0) { "BR37 behavioral task list is missing." }
        val behavioralTasksBlock = verification.substring(behavioralStart).substringBefore("val bkxBr37PackageAndReleaseLintTasks")
        check("compileDebugUnitTestKotlin" in behavioralTasksBlock && "testDebugUnitTest" in behavioralTasksBlock) {
            "BR37 behavioral lane must run the full unit-test suite once after compiling unit sources."
        }
        check("BkxApplicationRuntime.kt" in verification && "markApplicationCreated()" in verification) {
            "BR37 must refresh the runtime hardening static contract for the BR28 startup barrier architecture."
        }
        check("bkxBr37OptimizedGradleTaskSeal" in validationGuide && "bkxBr37StaticPreflightSeal" in validationGuide) {
            "BR37 release validation guide must document the optimized aggregate tasks."
        }
        val docText = doc.readText()
        check(doc.isFile && docText.contains("Overlay 17 of 17") && docText.contains("v3 correction") && docText.contains("v6 focused behavioral lane correction") && docText.contains("v7 full unit repair correction")) {
            "BR37 Gradle optimization seal doc is missing its cumulative correction notes."
        }
        val coordinatorText = repositoryRoot.resolve("android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/runtime/BkxOperationCoordinator.kt").readText()
        check("progressTracker.fail(current, requestId, failure" in coordinatorText) {
            "BR37 v3 must persist typed cancellation-rejection failures into OperationRuntimeState.failureStates."
        }
        check("For BR37 v7 full regression repair validation" in validationGuide && "testDebugUnitTest" in validationGuide) {
            "BR37 release validation guide must document that the behavioral lane owns the full unit suite."
        }
    }
}

tasks.register("bkxBr37OptimizedGradleTaskSeal") {
    group = "verification"
    description = "BR37 optimized apply gate: validates the reduced static preflight graph without rerunning unit tests, release packaging, release lint, instrumentation, or Go-backed package evidence."
    dependsOn("bkxBr37StaticPreflightSeal")
}

tasks.named("bkxAndroidReleasePreflightSeal") {
    dependsOn("verifyBkxRegressionHarnessFinalAuditSeal")
}

tasks.named("bkxAndroidReleaseSeal") {
    dependsOn("verifyBkxRegressionHarnessFinalAuditSeal")
}
