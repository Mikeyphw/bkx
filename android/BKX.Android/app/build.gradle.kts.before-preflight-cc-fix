plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mikeyphw.bkx.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mikeyphw.bkx.android"
        minSdk = 23
        targetSdk = 36
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


tasks.register("verifyBkxAndroidReleaseDocs") {
    group = "verification"
    description = "Fail closed when BKX Android release documentation or setup guides are missing."
    doLast {
        val requiredDocs = listOf(
            "docs/FINAL_RELEASE_VALIDATION.md",
            "docs/SETUP_GUIDE.md",
            "docs/SIGNING_PLACEHOLDERS.md",
            "docs/AOV_11_12_AUDIT.md",
        )
        val missing = requiredDocs.filterNot { layout.projectDirectory.file(it).asFile.isFile }
        check(missing.isEmpty()) { "Missing BKX Android release docs: ${missing.joinToString()}" }
    }
}

tasks.register("verifyBkxAndroidSigningPlaceholders") {
    group = "verification"
    description = "Fail closed when release signing placeholders are absent or real secrets are accidentally committed."
    doLast {
        val example = layout.projectDirectory.file("gradle/bkx-release-signing.properties.example").asFile
        check(example.isFile) { "Missing signing placeholder example: ${example.relativeTo(projectDir)}" }
        val forbidden = listOf(
            layout.projectDirectory.file("gradle/bkx-release-signing.properties").asFile,
            layout.projectDirectory.file("release.keystore").asFile,
            layout.projectDirectory.file("bkx-release.jks").asFile,
        ).filter { it.exists() }
        check(forbidden.isEmpty()) {
            "Refusing release seal with local signing secrets inside project tree: ${forbidden.joinToString { it.relativeTo(projectDir).path }}"
        }
    }
}

tasks.register("verifyBkxAndroidManifestReleaseContract") {
    group = "verification"
    description = "Fail closed when the Android manifest omits notification or foreground-service release contracts."
    doLast {
        val manifest = layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile.readText()
        val required = listOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "BkxOperationForegroundService",
            "android:foregroundServiceType=\"dataSync\"",
        )
        val missing = required.filterNot { manifest.contains(it) }
        check(missing.isEmpty()) { "Missing Android release manifest contract markers: ${missing.joinToString()}" }
    }
}

tasks.register("bkxAndroidReleaseSeal") {
    group = "verification"
    description = "Fail-closed BKX Android release gate: lint, unit tests, instrumentation tests, debug package, docs, signing placeholders, and manifest contracts."
    dependsOn(
        "lintDebug",
        "testDebugUnitTest",
        "connectedDebugAndroidTest",
        "assembleDebug",
        "assembleRelease",
        "verifyBkxAndroidReleaseDocs",
        "verifyBkxAndroidSigningPlaceholders",
        "verifyBkxAndroidManifestReleaseContract",
    )
}
