import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.smartrequest"
// Pre-release suffix keeps this compatibility line below main's 1.2.0 on IDEs where both can install.
version = "1.2.0-2024.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localIdePath = providers.gradleProperty("localIdePath").orNull

dependencies {
    intellijPlatform {
        if (localIdePath != null) {
            // Point this only at a 2024.3.x installation. A newer local IDE would raise the
            // effective API level while plugin.xml still claims since-build=243.
            local(localIdePath)
        } else {
            // release-2024.3 builds against the oldest supported IDE to guarantee binary compatibility.
            // Keep this in sync with sinceBuild below.
            intellijIdea("2024.3.6")
        }
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
        zipSigner()
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.13.4")
}

kotlin {
    compilerOptions {
        // IntelliJ IDEA 2024.3 runs on JetBrains Runtime 21.
        jvmTarget.set(JvmTarget.JVM_21)
        // 2024.3 bundles Kotlin stdlib 2.0.21. Do not emit calls to newer stdlib APIs.
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// IntelliJ Platform Gradle Plugin derives some defaults from the Gradle JVM; pin the Kotlin
// compiler explicitly so the produced bytecode is Java 21 even when Gradle runs on JBR 25.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    // Emit JVM default methods without bridges for every ToolWindowFactory default.
    compilerOptions.freeCompilerArgs.add("-jvm-default=no-compatibility")
}

tasks.test {
    useJUnitPlatform()
}

// Ship the license and disclaimer with the plugin distribution, as required by Apache-2.0.
tasks.prepareSandbox {
    from("LICENSE") { into("smart-request") }
    from("NOTICE") { into("smart-request") }
    from("DISCLAIMER.md") { into("smart-request") }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.smartrequest.idea"
        name = "Smart Request"
        version = project.version.toString()

        ideaVersion {
            // Oldest supported IDE: IntelliJ IDEA 2024.3 (build 243), JBR 21.
            sinceBuild = "243"
            untilBuild = provider { null }
        }

        vendor {
            name = "liuzhaowei"
            url = "https://github.com/liuzwei/smart-request"
        }
    }

    pluginVerification {
        ides {
            // The build platform and the newest release before main's 2026.2 line takes over.
            current()
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")
        }
    }
}
