pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Shizuku artifacts live on JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "screenoff-airplane"
include(":app")
