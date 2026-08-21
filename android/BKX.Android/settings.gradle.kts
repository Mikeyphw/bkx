pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.topjohnwu.libsu")
            }
        }
        gradlePluginPortal()
    }
}

rootProject.name = "BKXAndroidCompanion"
include(":app")
