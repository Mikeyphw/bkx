plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val bkxValidationSdk = 36

android {
    namespace = "com.mikeyphw.bkx.android"
    compileSdk = bkxValidationSdk

    defaultConfig {
        applicationId = "com.mikeyphw.bkx.android"
        minSdk = 23
        targetSdk = bkxValidationSdk
        versionCode = 1
        versionName = "0.1.0-aov12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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


val bkxSdk36DependencyLane = mapOf(
    "Compose BOM" to libs.versions.composeBom.get(),
    "Activity Compose" to libs.versions.activity.get(),
    "AppCompat" to libs.versions.appcompat.get(),
    "Core KTX" to libs.versions.coreKtx.get(),
    "Lifecycle Compose" to libs.versions.lifecycle.get(),
)

val expectedBkxSdk36DependencyLane = mapOf(
    "Compose BOM" to "2026.06.01",
    "Activity Compose" to "1.12.4",
    "AppCompat" to "1.7.1",
    "Core KTX" to "1.17.0",
    "Lifecycle Compose" to "2.10.0",
)

tasks.register("verifyBkxAndroidSdk36DependencyLane") {
    group = "verification"
    description = "Fail closed when BKX Android drifts from the dependency lane verified for compileSdk 36."

    inputs.property("bkxValidationSdk", bkxValidationSdk)
    inputs.properties(bkxSdk36DependencyLane)

    doLast {
        check(bkxValidationSdk == 36) {
            "BKX Android SDK policy changed from API 36; update the Devtool platform, dependency lane, and SDK validation policy together."
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
        "verifyBkxAndroidReleaseDocs",
        "verifyBkxAndroidSigningPlaceholders",
        "verifyBkxAndroidManifestReleaseContract",
    )
}
