rootProject.name = "smart-request"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Provides JDK 21 for the IntelliJ IDEA 2024.3 toolchain on machines without a local JDK 21.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

