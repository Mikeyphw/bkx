plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}


// BR35: dependency locking is enabled for every configuration so release
// validation can pin resolved artifacts with `gradle --write-locks`.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
